package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The entries of an indexed static file (D-16), read back from disk on demand: each row's
 * line is read at its {@link EntryIndex} offset and parsed again with the tab's
 * {@link LineParser}, so the heap holds the index instead of the text.
 * <p>
 * The file is kept open with a {@link FileChannel} for {@link #get}, which on Windows lets the writer
 * still rename or delete it (log rotation). Positioned reads on it are safe from any
 * thread.
 * <p>
 * <b>Threads:</b> {@link #get} keeps a cache and is for one thread (the EDT). {@link #scan}
 * is for one other thread at a time (the filter executor); it uses no cache and reads
 * the file in large sequential blocks. Both may only ask for rows below a size handed to
 * them by the thread that builds the index (see {@link EntryIndex}).
 */
@Slf4j
public final class IndexedEntries implements Closeable {

    /** Entries kept parsed for {@link #get}: enough for scrolling and the detail panel. */
    public static final int CACHE_SIZE = 10_000;
    private static final int SCAN_BLOCK = 1 << 20;

    /** Receives one entry of a {@link #scan}. */
    @FunctionalInterface
    public interface RowSink {
        void row(int row, LogEntry entry);
    }

    private final Path file;
    private final EntryIndex index;
    private final LineParser parser;
    private final FileChannel channel;
    private final Map<Integer, LogEntry> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, LogEntry> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private volatile boolean readFailed;

    private IndexedEntries(Path file, EntryIndex index, LineParser parser, FileChannel channel) {
        this.file = file;
        this.index = index;
        this.parser = parser;
        this.channel = channel;
    }

    /**
     * Opens {@code file} for reading back the rows of {@code index}, which may still be
     * growing.
     */
    public static IndexedEntries open(Path file, EntryIndex index, LineParser parser) throws IOException {
        return new IndexedEntries(file, index, parser, FileChannel.open(file, StandardOpenOption.READ));
    }

    public EntryIndex index() { return index; }

    /**
     * The entry at this row, or null if its text can't be read any more (the file was
     * truncated or deleted). Logs the first failure only.
     */
    public LogEntry get(int row) {
        LogEntry e = cache.get(row);
        if (e != null) return e;
        int length = index.length(row);
        ByteBuffer buf = ByteBuffer.allocate(length);
        try {
            long pos = index.offset(row);
            while (buf.hasRemaining()) {
                int n = channel.read(buf, pos + buf.position());
                if (n < 0) {
                    failed(null);
                    return null;
                }
            }
        } catch (IOException ex) {
            failed(ex);
            return null;
        }
        e = parser.parse(new String(buf.array(), 0, length, StandardCharsets.UTF_8));
        cache.put(row, e);
        return e;
    }

    /**
     * Reads rows {@code from} (inclusive) to {@code to} (exclusive) in order, in large
     * sequential reads, and hands each one to {@code sink}. Stops early if
     * {@code cancelled} is true or the text can't be read.
     *
     * @return true if every row was read
     */
    public boolean scan(int from, int to, RowSink sink, BooleanSupplier cancelled) {
        ByteBuffer block = ByteBuffer.allocate(SCAN_BLOCK);
        long blockStart = 0;
        int blockLen = 0;
        // Its own channel: interrupting a thread inside FileChannel.read closes the channel,
        // and the filter executor is cancelled by interrupts. The shared one must survive.
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            for (int row = from; row < to; row++) {
                if (cancelled.getAsBoolean()) return false;
                long offset = index.offset(row);
                int length = index.length(row);
                String text;
                if (length > SCAN_BLOCK) {          // a huge line: read it on its own
                    byte[] bytes = readFully(channel, offset, length);
                    if (bytes == null) return false;
                    text = new String(bytes, StandardCharsets.UTF_8);
                } else {
                    if (offset < blockStart || offset + length > blockStart + blockLen) {
                        block.clear();
                        blockStart = offset;
                        while (block.hasRemaining() && channel.read(block, blockStart + block.position()) >= 0) {
                            // fill the block, or stop at the end of the file
                        }
                        blockLen = block.position();
                        if (length > blockLen) {
                            failed(null);
                            return false;
                        }
                    }
                    text = new String(block.array(), (int) (offset - blockStart), length, StandardCharsets.UTF_8);
                }
                sink.row(row, parser.parse(text));
            }
            return true;
        } catch (ClosedByInterruptException ex) {
            return false;                           // cancelled mid-read
        } catch (IOException ex) {
            failed(ex);
            return false;
        }
    }

    private byte[] readFully(FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            if (channel.read(buf, offset + buf.position()) < 0) {
                failed(null);
                return null;
            }
        }
        return buf.array();
    }

    private void failed(IOException ex) {
        if (readFailed) return;
        readFailed = true;
        if (ex != null) log.warn("Can't read {} any more", file, ex);
        else log.warn("{} is shorter than its index: it was truncated or replaced", file);
    }

    /** True once a read has failed, so the tab can tell the user. */
    public boolean hasReadFailed() { return readFailed; }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ex) {
            log.debug("Closing {}", file, ex);
        }
    }
}
