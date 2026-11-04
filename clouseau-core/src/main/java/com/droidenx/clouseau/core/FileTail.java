package com.droidenx.clouseau.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Reads lines appended to a file since a known byte offset, for follow (tail) mode.
 *
 * <p>Lines are split by {@link LineScanner}, exactly as the first pass over the file splits
 * them, so a followed line can extend the file's {@link EntryIndex} (D-16). Only complete
 * lines are consumed: a trailing line still being written is left for the next read, so it
 * never becomes two entries. Bytes are decoded as UTF-8 with malformed input replaced, and
 * when reading from offset 0 a leading UTF-8 BOM is skipped (see {@link Utf8Bom}).
 */
public final class FileTail {

    private static final int BACKWARD_BLOCK = 8 * 1024;

    private FileTail() {}

    /**
     * Passes each complete, non-blank line written to {@code file} after byte offset
     * {@code from} to {@code sink}, without its line ending.
     *
     * @return the byte offset just past the last complete line, to pass as {@code from}
     *         on the next call; {@code from} itself if no complete line was available
     */
    public static long readAppendedLines(Path file, long from, Consumer<String> sink) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            return readAppendedLines(channel, from, (offset, length, text) -> sink.accept(text), () -> false);
        }
    }

    /**
     * Passes each complete, non-blank line in {@code channel} after byte offset {@code from}
     * to {@code sink}, with its offset and length in bytes. Stops early if {@code cancelled}
     * is true (checked after every line). Moves the channel's position.
     *
     * @return the byte offset just past the last line handed to {@code sink} (or skipped as
     *         blank), to pass as {@code from} on the next call
     */
    public static long readAppendedLines(SeekableByteChannel channel, long from, LineScanner.Sink sink,
                                         BooleanSupplier cancelled) throws IOException {
        return readAppendedLines(channel, from, sink, cancelled, false);
    }

    /**
     * Like {@link #readAppendedLines(SeekableByteChannel, long, LineScanner.Sink, BooleanSupplier)};
     * with {@code keepBlank}, blank lines are passed on too (the plain-text view, UC-13).
     */
    public static long readAppendedLines(SeekableByteChannel channel, long from, LineScanner.Sink sink,
                                         BooleanSupplier cancelled, boolean keepBlank) throws IOException {
        long end = endOfLastLine(channel, from);
        if (end <= from) return from;
        if (keepBlank && endsCrlfSplit(channel, from)) {
            from++;                                   // the \n of a \r\n cut after its \r last time
            if (end <= from) return from;
        }
        channel.position(from);
        InputStream in = limited(Channels.newInputStream(channel), end - from);
        return keepBlank ? LineScanner.scanAllLines(in, from, sink, cancelled)
                         : LineScanner.scan(in, from, sink, cancelled);
    }

    /**
     * True if the last read stopped after a {@code \r} and the next byte is its {@code \n}.
     * Without blank lines it would read as a blank line and be skipped; with them it must be
     * skipped here, or a {@code \r\n} split between two reads would add a blank line.
     */
    private static boolean endsCrlfSplit(SeekableByteChannel channel, long from) throws IOException {
        if (from == 0) return false;
        ByteBuffer two = ByteBuffer.allocate(2);
        channel.position(from - 1);
        while (two.hasRemaining()) {
            if (channel.read(two) < 0) return false;
        }
        return two.get(0) == '\r' && two.get(1) == '\n';
    }

    /**
     * The position just past the last line ending ({@code \n} or {@code \r}) after
     * {@code from}, or {@code from} if there is none. Cutting after a lone {@code \r} is
     * safe: if a {@code \n} follows it later, that reads as a blank line and is skipped.
     */
    private static long endOfLastLine(SeekableByteChannel channel, long from) throws IOException {
        long pos = channel.size();
        ByteBuffer buf = ByteBuffer.allocate(BACKWARD_BLOCK);
        while (pos > from) {
            int len = (int) Math.min(BACKWARD_BLOCK, pos - from);
            long blockStart = pos - len;
            buf.clear().limit(len);
            channel.position(blockStart);
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) return from;     // shrank meanwhile: try again later
            }
            byte[] bytes = buf.array();
            for (int k = len - 1; k >= 0; k--) {
                if (bytes[k] == '\n' || bytes[k] == '\r') return blockStart + k + 1;
            }
            pos = blockStart;
        }
        return from;
    }

    /** {@code in}, ending after {@code limit} bytes. Doesn't close {@code in}. */
    private static InputStream limited(InputStream in, long limit) {
        return new InputStream() {
            private long left = limit;

            @Override
            public int read() throws IOException {
                if (left <= 0) return -1;
                int b = in.read();
                if (b >= 0) left--;
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (left <= 0) return -1;
                int n = in.read(b, off, (int) Math.min(len, left));
                if (n > 0) left -= n;
                return n;
            }
        };
    }
}
