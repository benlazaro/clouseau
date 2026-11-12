package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.Channel;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/**
 * The entries of an indexed file (D-16), read back from disk on demand: each row's line
 * is read at its {@link EntryIndex} offset and parsed again with the tab's
 * {@link LineParser}, so the heap holds the index instead of the text.
 * <p>
 * <b>Rotation.</b> A followed file's rows are kept in <em>segments</em>, one per file the
 * path has named, each with its own open channels. When the followed file is
 * {@linkplain #rotated() rotated}, later rows go in a new segment on the file the path names
 * now. A file renamed away stays open, so its rows stay readable; the channels are opened
 * with {@link FileChannel#open} and {@link AsynchronousFileChannel#open}, which on Windows
 * share delete access, so holding them never stops the writer from renaming the file. A
 * file truncated in place has lost its text: its rows keep what the index holds (time,
 * level, logger, thread, indexed fields) and a placeholder message.
 * <p>
 * <b>Threads:</b> {@link #get} keeps a cache and is for one thread (the EDT). {@link #scan}
 * may run on other threads (the filter and find executors); it uses no cache and reads
 * the file in large sequential blocks. Both may only ask for rows below a size handed to
 * them by the thread that builds the index (see {@link EntryIndex}). {@link #rotated} is
 * called by that thread, before it adds the first row of the new file.
 */
@Slf4j
public final class IndexedEntries implements EntryRows {

    /** Entries kept parsed for {@link #get}: enough for scrolling and the detail panel. */
    public static final int CACHE_SIZE = 10_000;
    /** The message of a row whose text was lost to rotation, unless another is given. */
    public static final String DEFAULT_ROTATED_TEXT = "Text no longer in the file (rotated)";
    private static final int SCAN_BLOCK = 1 << 20;

    /** Receives one entry of a {@link #scan}. */
    @FunctionalInterface
    public interface RowSink {
        void row(int row, LogEntry entry);
    }

    /** Rows from {@code firstRow} up to the next segment's, all in one file. */
    private static final class Segment {
        final int firstRow;
        /** For {@link #get}; null if the file couldn't be opened. */
        final FileChannel channel;
        /**
         * For {@link #scan}. Interrupting a thread inside {@code FileChannel.read} closes the
         * channel, and the executors are cancelled by interrupts; a file renamed away can't
         * be opened again by name. Interrupting a thread waiting for an asynchronous read
         * leaves the channel open.
         */
        final AsynchronousFileChannel scanChannel;
        /** Set once the file was truncated under these rows. */
        volatile boolean lost;

        Segment(int firstRow, FileChannel channel, AsynchronousFileChannel scanChannel) {
            this.firstRow = firstRow;
            this.channel = channel;
            this.scanChannel = scanChannel;
        }
    }

    private final Path file;
    private final EntryIndex index;
    private final LineParser parser;
    private final String rotatedText;
    /** Ascending by first row; replaced, never changed in place, when a segment is added. */
    private volatile List<Segment> segments;
    private volatile boolean closed;
    private final Map<Integer, LogEntry> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, LogEntry> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    /** The segments {@link #cache} was filled from; a rotation empties the cache. */
    private List<Segment> cachedSegments;
    private volatile boolean readFailed;

    private IndexedEntries(Path file, EntryIndex index, LineParser parser, String rotatedText, Segment first) {
        this.file = file;
        this.index = index;
        this.parser = parser;
        this.rotatedText = rotatedText;
        this.segments = List.of(first);
    }

    /**
     * Opens {@code file} for reading back the rows of {@code index}, which may still be
     * growing.
     */
    public static IndexedEntries open(Path file, EntryIndex index, LineParser parser) throws IOException {
        return open(file, index, parser, DEFAULT_ROTATED_TEXT);
    }

    /**
     * Like {@link #open(Path, EntryIndex, LineParser)}; rows whose text was lost to
     * rotation get {@code rotatedText} as their message.
     */
    public static IndexedEntries open(Path file, EntryIndex index, LineParser parser, String rotatedText)
            throws IOException {
        return new IndexedEntries(file, index, parser, rotatedText, openSegment(0, file));
    }

    private static Segment openSegment(int firstRow, Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        try {
            return new Segment(firstRow, channel, AsynchronousFileChannel.open(file, StandardOpenOption.READ));
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    public EntryIndex index() { return index; }

    @Override public int lineNumber(int ordinal) { return index.lineNumber(ordinal); }

    @Override public long lineCount() { return index.lineCount(); }

    @Override
    public boolean isContinuation(int row) { return index.isContinuation(row); }

    @Override
    public boolean testIndex(EntryFilter filter, int row) { return filter.testIndex(index, row); }

    @Override
    public boolean needsText(EntryFilter filter) { return filter.needsText(index); }

    /** One run, in file order. */
    @Override
    public boolean scan(int from, int to, Visitor visitor, BooleanSupplier cancelled) {
        visitor.startRun();
        return scan(from, to, row -> visitor.wanted(row, index.isContinuation(row)), visitor::accept, cancelled);
    }

    /**
     * The followed file was rotated: rows added from now on are in the file the path names
     * now. If the rows so far lost their text (the file was truncated in place rather than
     * renamed), they read from then on as the index columns and the placeholder message.
     * Call on the thread that adds rows to the index, before adding the new file's first.
     *
     * @return true if older rows lost their text
     */
    public synchronized boolean rotated() {
        if (closed) return false;
        List<Segment> segs = segments;
        Segment last = segs.get(segs.size() - 1);
        int size = index.size();
        boolean lost = false;
        if (!last.lost && size > last.firstRow && last.channel != null) {
            long end = index.offset(size - 1) + index.length(size - 1);
            try {
                lost = last.channel.size() < end;
            } catch (IOException e) {
                lost = true;
            }
            if (lost) {
                last.lost = true;
                log.info("{} was truncated: {} older rows keep only their index columns", file, size - last.firstRow);
            }
        }
        Segment next;
        try {
            next = openSegment(size, file);
        } catch (IOException e) {
            log.warn("Can't open {} after rotation", file, e);
            next = new Segment(size, null, null);
        }
        List<Segment> grown = new ArrayList<>(segs);
        grown.add(next);
        segments = List.copyOf(grown);
        return lost;
    }

    private static Segment segmentOf(List<Segment> segs, int row) {
        for (int i = segs.size() - 1; i > 0; i--) {
            if (segs.get(i).firstRow <= row) return segs.get(i);
        }
        return segs.get(0);
    }

    /**
     * The entry at this row, or null if its text can't be read any more (the file was
     * truncated or deleted while not followed). Logs the first failure only.
     */
    public LogEntry get(int row) {
        List<Segment> segs = segments;
        if (segs != cachedSegments) {
            cache.clear();
            cachedSegments = segs;
        }
        LogEntry e = cache.get(row);
        if (e != null) return e;
        Segment seg = segmentOf(segs, row);
        if (seg.lost) {
            e = rotatedEntry(row);
        } else {
            String text = readLine(seg, row);
            if (text == null) return null;
            e = parser.parse(text);
        }
        cache.put(row, e);
        return e;
    }

    private String readLine(Segment seg, int row) {
        if (seg.channel == null) {
            failed(null);
            return null;
        }
        int length = index.length(row);
        ByteBuffer buf = ByteBuffer.allocate(length);
        try {
            long pos = index.offset(row);
            while (buf.hasRemaining()) {
                int n = seg.channel.read(buf, pos + buf.position());
                if (n < 0) {
                    failed(null);
                    return null;
                }
            }
        } catch (IOException ex) {
            failed(ex);
            return null;
        }
        return new String(buf.array(), 0, length, StandardCharsets.UTF_8);
    }

    /** What the index still knows about a row whose text is gone. */
    private LogEntry rotatedEntry(int row) {
        long ts = index.timestamp(row);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String name : index.fieldNames()) {
            EntryIndex.Dictionary values = index.fieldValues(name);
            if (values == null) continue;
            String value = values.valueOf(index.fieldId(name, row));
            if (value != null) fields.put(name, value);
        }
        return new LogEntry(
                ts == EntryIndex.NO_TIMESTAMP ? null : Instant.ofEpochMilli(ts),
                index.level(row),
                index.loggers().valueOf(index.loggerId(row)),
                index.threads().valueOf(index.threadId(row)),
                rotatedText, rotatedText, Map.copyOf(fields));
    }

    /**
     * Reads rows {@code from} (inclusive) to {@code to} (exclusive) in order, in large
     * sequential reads, and hands each one to {@code sink}. Stops early if
     * {@code cancelled} is true, the thread is interrupted, or the text can't be read.
     *
     * @return true if every row was read
     */
    public boolean scan(int from, int to, RowSink sink, BooleanSupplier cancelled) {
        return scan(from, to, row -> true, sink, cancelled);
    }

    /**
     * Like {@link #scan(int, int, RowSink, BooleanSupplier)}, but reads and parses only the
     * rows {@code wanted} accepts. {@code wanted} is asked about every row, in order, just
     * before that row would be read, so it may keep state (for example, progress).
     */
    public boolean scan(int from, int to, IntPredicate wanted, RowSink sink, BooleanSupplier cancelled) {
        List<Segment> segs = segments;
        ByteBuffer block = ByteBuffer.allocate(SCAN_BLOCK);
        Segment blockSeg = null;
        long blockStart = 0;
        int blockLen = 0;
        try {
            for (int row = from; row < to; row++) {
                if (cancelled.getAsBoolean()) return false;
                if (!wanted.test(row)) continue;
                Segment seg = segmentOf(segs, row);
                if (seg.lost) {
                    sink.row(row, rotatedEntry(row));
                    continue;
                }
                if (seg.scanChannel == null) {
                    failed(null);
                    return false;
                }
                long offset = index.offset(row);
                int length = index.length(row);
                String text;
                if (length > SCAN_BLOCK) {          // a huge line: read it on its own
                    ByteBuffer buf = ByteBuffer.allocate(length);
                    if (fill(seg.scanChannel, buf, offset) < length) {
                        failed(null);
                        return false;
                    }
                    text = new String(buf.array(), StandardCharsets.UTF_8);
                } else {
                    if (seg != blockSeg || offset < blockStart || offset + length > blockStart + blockLen) {
                        block.clear();
                        blockSeg = seg;
                        blockStart = offset;
                        blockLen = fill(seg.scanChannel, block, blockStart);
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
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();     // cancelled mid-read: the channel stays open
            return false;
        } catch (IOException ex) {
            failed(ex);
            return false;
        }
    }

    /**
     * Reads into {@code buf} from {@code position} until it is full or the file ends.
     *
     * @return the bytes read
     */
    private static int fill(AsynchronousFileChannel channel, ByteBuffer buf, long position)
            throws IOException, InterruptedException {
        int start = buf.position();
        while (buf.hasRemaining()) {
            int n;
            try {
                Future<Integer> read = channel.read(buf, position + buf.position() - start);
                // get() returns a finished read without looking at the interrupt flag, so an
                // interrupted scan could run to the end (or not) depending on timing. Check it
                // here: the read was issued, and the channel stays open either way.
                if (Thread.interrupted()) throw new InterruptedException();
                n = read.get();
            } catch (ExecutionException ex) {
                throw ex.getCause() instanceof IOException io ? io : new IOException(ex.getCause());
            }
            if (n < 0) break;
        }
        return buf.position() - start;
    }

    private void failed(IOException ex) {
        if (readFailed || closed) return;   // a read cut short by close() is no failure
        readFailed = true;
        if (ex != null) log.warn("Can't read {} any more", file, ex);
        else log.warn("{} is shorter than its index: it was truncated or replaced", file);
    }

    /** True once a read has failed, so the tab can tell the user. */
    public boolean hasReadFailed() { return readFailed; }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Segment seg : segments) {
            close(seg.channel);
            close(seg.scanChannel);
        }
    }

    private void close(Channel channel) {
        if (channel == null) return;
        try {
            channel.close();
        } catch (IOException ex) {
            log.debug("Closing {}", file, ex);
        }
    }
}
