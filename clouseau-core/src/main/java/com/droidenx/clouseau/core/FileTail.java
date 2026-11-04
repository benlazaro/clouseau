package com.droidenx.clouseau.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Reads lines appended to a file since a known byte offset, for follow (tail) mode.
 *
 * <p>Bytes are decoded as UTF-8 with malformed input replaced, matching how files are read
 * on initial load. Only complete lines (terminated by {@code \n}) are consumed: a trailing
 * line still being written is left for the next read, so it never becomes two entries.
 * When reading from offset 0, a leading UTF-8 BOM is skipped (see {@link Utf8Bom}).
 */
public final class FileTail {

    private FileTail() {}

    /**
     * Passes each complete, non-blank line written to {@code file} after byte offset
     * {@code from} to {@code sink}, with any trailing {@code \r} removed.
     *
     * @return the byte offset just past the last complete line, to pass as {@code from}
     *         on the next call; {@code from} itself if no complete line was available
     */
    public static long readAppendedLines(Path file, long from, Consumer<String> sink) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            if (channel.size() <= from) return from;
            channel.position(from);

            InputStream in = new BufferedInputStream(Channels.newInputStream(channel));
            long start = from;
            if (from == 0) {
                // Start of file (first read, or after rotation): step over a UTF-8 BOM.
                in.mark(Utf8Bom.LENGTH);
                byte[] head = in.readNBytes(Utf8Bom.LENGTH);
                if (Utf8Bom.isBom(head, head.length)) start = Utf8Bom.LENGTH;
                else in.reset();
            }
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            long offset = start;
            long consumed = start;
            int b;
            while ((b = in.read()) != -1) {
                offset++;
                if (b == '\n') {
                    emit(line, sink);
                    line.reset();
                    consumed = offset;
                } else {
                    line.write(b);
                }
            }
            return consumed;
        }
    }

    private static void emit(ByteArrayOutputStream bytes, Consumer<String> sink) {
        // '\n' is a single byte that never occurs inside a multi-byte UTF-8 sequence,
        // so splitting on it before decoding cannot cut a character in half.
        String line = bytes.toString(StandardCharsets.UTF_8);
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        if (!line.isBlank()) sink.accept(line);
    }
}
