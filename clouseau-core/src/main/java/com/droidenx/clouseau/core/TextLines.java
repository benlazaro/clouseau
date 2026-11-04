package com.droidenx.clouseau.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Every line of a text file, blank ones included, read back from disk on demand: the heap
 * holds 12 bytes per line (where it starts and how long it is) instead of the text. This is
 * the plain-text view's store for a file no parser recognizes (UC-13), the counterpart of
 * {@link IndexedEntries} for parsed logs.
 * <p>
 * <b>Threads:</b> one thread builds it with {@link #add} (the loader, then the follower).
 * {@link #line} keeps a cache and is for one other thread (the EDT); {@link #scan} may run
 * on any thread and reads in large blocks without the cache. Readers may only ask for lines
 * below a {@link #size()} they have read: offsets live in fixed chunks that are never moved,
 * and {@code size} is published after the line it counts.
 */
public final class TextLines implements Closeable {

    /** Lines kept decoded for {@link #line}: enough for scrolling. */
    static final int CACHE_SIZE = 4_000;
    private static final int CHUNK_BITS = 16;
    private static final int CHUNK = 1 << CHUNK_BITS;
    private static final int SCAN_BLOCK = 1 << 20;

    /** Receives one line of a {@link #scan}. */
    @FunctionalInterface
    public interface LineSink {
        void line(int number, String text);
    }

    private final Path file;
    private final FileChannel channel;
    private volatile long[][] offsets = new long[16][];
    private volatile int[][] lengths = new int[16][];
    private volatile int size;
    private final Map<Integer, String> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private TextLines(Path file, FileChannel channel) {
        this.file = file;
        this.channel = channel;
    }

    /** Opens {@code file} for reading lines back; add the lines with {@link #add}. */
    public static TextLines open(Path file) throws IOException {
        return new TextLines(file, FileChannel.open(file, StandardOpenOption.READ));
    }

    /** The file the lines are read from. */
    public Path file() {
        return file;
    }

    /** Lines added so far. */
    public int size() {
        return size;
    }

    /** Adds the next line: where it starts in the file and its length in bytes. Builder thread only. */
    public void add(long offset, int length) {
        int n = size;
        int chunk = n >>> CHUNK_BITS;
        long[][] offs = offsets;
        int[][] lens = lengths;
        if (chunk == offs.length) {
            offs = Arrays.copyOf(offs, offs.length * 2);
            lens = Arrays.copyOf(lens, lens.length * 2);
        }
        if (offs[chunk] == null) {
            offs[chunk] = new long[CHUNK];
            lens[chunk] = new int[CHUNK];
        }
        offs[chunk][n & (CHUNK - 1)] = offset;
        lens[chunk][n & (CHUNK - 1)] = length;
        offsets = offs;
        lengths = lens;
        size = n + 1;                      // publishes the line (volatile write)
    }

    private long offsetOf(int line) {
        return offsets[line >>> CHUNK_BITS][line & (CHUNK - 1)];
    }

    private int lengthOf(int line) {
        return lengths[line >>> CHUNK_BITS][line & (CHUNK - 1)];
    }

    /**
     * The text of line {@code line} (0-based), decoded as UTF-8 with malformed bytes
     * replaced, or an empty string if it can't be read. For one thread (the EDT).
     */
    public String line(int line) {
        if (line < 0 || line >= size) throw new IndexOutOfBoundsException(line + " of " + size);
        String cached = cache.get(line);
        if (cached != null) return cached;
        String text;
        try {
            ByteBuffer buf = ByteBuffer.allocate(lengthOf(line));
            long pos = offsetOf(line);
            while (buf.hasRemaining()) {
                int r = channel.read(buf, pos + buf.position());
                if (r < 0) break;                       // the file shrank (truncated)
            }
            text = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "";
        }
        cache.put(line, text);
        return text;
    }

    /**
     * Reads lines {@code from} (inclusive) to {@code to} (exclusive) in order, in large
     * blocks, until {@code cancelled} is true (checked after every line). May run on any
     * thread; opens its own channel, so an interrupt can't close the one {@link #line} uses.
     */
    public void scan(int from, int to, LineSink sink, BooleanSupplier cancelled) throws IOException {
        to = Math.min(to, size);
        if (from >= to) return;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer block = ByteBuffer.allocate(SCAN_BLOCK);
            long blockStart = -1;
            int blockLen = 0;
            for (int i = from; i < to; i++) {
                long off = offsetOf(i);
                int len = lengthOf(i);
                if (blockStart < 0 || off < blockStart || off + len > blockStart + blockLen) {
                    if (len > block.capacity()) block = ByteBuffer.allocate(len);
                    block.clear();
                    while (block.hasRemaining()) {
                        int r = ch.read(block, off + block.position());
                        if (r < 0) break;
                    }
                    blockStart = off;
                    blockLen = block.position();
                }
                int start = (int) (off - blockStart);
                int avail = Math.max(0, Math.min(len, blockLen - start));
                sink.line(i, new String(block.array(), start, avail, StandardCharsets.UTF_8));
                if (cancelled.getAsBoolean()) return;
            }
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
