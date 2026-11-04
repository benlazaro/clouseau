package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A compact index of a static log (D-16): one row per entry, in file order, holding where
 * the entry's line is in the file and the fields filters need, but not its text. Text is
 * read back from the file on demand.
 * <p>
 * Each row is about 29 bytes: offset, length, timestamp, level, and logger and thread ids.
 * Loggers, threads and custom field values are stored once each in a {@link Dictionary}.
 * A custom field stops being indexed once it has more than {@link #MAX_FIELD_VALUES}
 * distinct values; filtering on it then has to read text.
 * <p>
 * Columns grow in chunks, so no single array gets huge and appending stays cheap.
 * <p>
 * <b>Threads:</b> one thread appends. Other threads may read any getter, including the
 * dictionaries and field lookups, for rows below a size the appending thread handed them
 * through a happens-before edge (for example {@code SwingWorker.publish}); an id read
 * from such a row always resolves. What they see beyond that size may be partial.
 */
public final class EntryIndex {

    /** The timestamp of an entry that has none (continuation lines). */
    public static final long NO_TIMESTAMP = Long.MIN_VALUE;
    /** The id of a missing logger, thread or field value. */
    public static final int NONE = -1;
    /** Distinct values a custom field may have and still be indexed. */
    public static final int MAX_FIELD_VALUES = 1_000;

    private static final LogLevel[] LEVELS = LogLevel.values();
    /** The level byte of an entry whose parser gave no level (a plugin may). */
    private static final byte NO_LEVEL = -1;

    private final int chunkBits;
    private final LongColumn offsets;
    private final IntColumn  lengths;
    private final LongColumn timestamps;
    private final ByteColumn levels;
    private final IntColumn  loggerIds;
    private final IntColumn  threadIds;
    private final Dictionary loggers = new Dictionary();
    private final Dictionary threads = new Dictionary();
    /** Every custom field seen. A column is put here only once it covers every earlier row. */
    private final Map<String, FieldColumn> fields = new ConcurrentHashMap<>();
    /** The keys of {@link #fields} in first-seen order; replaced, never changed, when one is added. */
    private volatile List<String> fieldNames = List.of();
    private int size;

    public EntryIndex() {
        this(20);   // chunks of 1M rows
    }

    EntryIndex(int chunkBits) {
        this.chunkBits = chunkBits;
        offsets    = new LongColumn(chunkBits);
        lengths    = new IntColumn(chunkBits);
        timestamps = new LongColumn(chunkBits);
        levels     = new ByteColumn(chunkBits);
        loggerIds  = new IntColumn(chunkBits);
        threadIds  = new IntColumn(chunkBits);
    }

    /** Appends the entry whose line is {@code length} bytes at {@code offset}. */
    public void add(long offset, int length, LogEntry e) {
        offsets.add(offset);
        lengths.add(length);
        timestamps.add(e.timestamp() != null ? e.timestamp().toEpochMilli() : NO_TIMESTAMP);
        levels.add(e.level() != null ? (byte) e.level().ordinal() : NO_LEVEL);
        loggerIds.add(loggers.add(e.logger()));
        threadIds.add(threads.add(e.thread()));

        Map<String, String> values = e.fields() != null ? e.fields() : Map.of();
        for (String name : values.keySet()) {
            if (!fields.containsKey(name)) {
                FieldColumn col = new FieldColumn(chunkBits);
                col.ids.fill(size, NONE);   // earlier rows don't have it
                fields.put(name, col);
                List<String> names = new ArrayList<>(fieldNames);
                names.add(name);
                fieldNames = List.copyOf(names);
            }
        }
        for (String name : fieldNames) {
            FieldColumn col = fields.get(name);
            if (col.overflowed) continue;
            int id = col.values.add(values.get(name));
            if (col.values.size() > MAX_FIELD_VALUES) {
                col.overflowed = true;      // too many values to be worth indexing
                continue;
            }
            col.ids.add(id);
        }
        size++;
    }

    public int size() { return size; }

    public long offset(int row)        { return offsets.get(row); }
    public int length(int row)         { return lengths.get(row); }
    /** Epoch milliseconds, or {@link #NO_TIMESTAMP}. */
    public long timestamp(int row)     { return timestamps.get(row); }
    /** The level, or null if the parser gave none. */
    public LogLevel level(int row) {
        byte b = levels.get(row);
        return b == NO_LEVEL ? null : LEVELS[b];
    }

    /** True if the row is a continuation line: no timestamp, or level {@code UNKNOWN}. */
    public boolean isContinuation(int row) {
        return timestamp(row) == NO_TIMESTAMP || levels.get(row) == LogLevel.UNKNOWN.ordinal();
    }
    public int loggerId(int row)       { return loggerIds.get(row); }
    public int threadId(int row)       { return threadIds.get(row); }

    public Dictionary loggers()        { return loggers; }
    public Dictionary threads()        { return threads; }

    /** Every custom field seen so far, indexed or not, in the order first seen. */
    public List<String> fieldNames()   { return fieldNames; }

    public boolean isFieldIndexed(String name) {
        FieldColumn col = fields.get(name);
        return col != null && !col.overflowed;
    }

    /**
     * True if the field was seen but has too many values to be indexed, so a filter on it
     * must read text. (A field never seen is simply missing from every row.)
     */
    public boolean hasTooManyValues(String name) {
        FieldColumn col = fields.get(name);
        return col != null && col.overflowed;
    }

    /** The field's values, or null if it isn't indexed (unknown, or too many values). */
    public Dictionary fieldValues(String name) {
        FieldColumn col = fields.get(name);
        return col != null && !col.overflowed ? col.values : null;
    }

    /** The id of the row's value in {@link #fieldValues}, or {@link #NONE} (also if it isn't indexed). */
    public int fieldId(String name, int row) {
        FieldColumn col = fields.get(name);
        return col != null && !col.overflowed ? col.ids.get(row) : NONE;
    }

    /** Roughly how much heap the columns use, for diagnostics. */
    public long estimatedBytes() {
        long bytes = offsets.capacity() * 8 + timestamps.capacity() * 8 + levels.capacity()
                + (lengths.capacity() + loggerIds.capacity() + threadIds.capacity()) * 4;
        for (FieldColumn col : fields.values()) if (!col.overflowed) bytes += col.ids.capacity() * 4;
        return bytes;
    }

    // ── Dictionary ───────────────────────────────────────────────────────────

    /**
     * Distinct strings, each with a small id in the order first seen. Null has no id.
     * One thread adds; any thread may look up (see the class comment).
     */
    public static final class Dictionary {
        private final Map<String, Integer> ids = new ConcurrentHashMap<>();
        /** Grown by copying and published through the volatile write, like the columns. */
        private volatile String[] values = new String[16];
        private volatile int size;

        private Dictionary() {}

        /** The value's id, adding it if new; {@link #NONE} for null. */
        int add(String value) {
            if (value == null) return NONE;
            Integer id = ids.get(value);
            if (id != null) return id;
            int n = size;
            String[] vs = values;
            if (n == vs.length) {
                vs = Arrays.copyOf(vs, n * 2);
                vs[n] = value;
                values = vs;
            } else {
                vs[n] = value;
            }
            size = n + 1;           // publishes the value written above
            ids.put(value, n);
            return n;
        }

        /** The value's id, or {@link #NONE} if it hasn't been seen. */
        public int idOf(String value) {
            Integer id = value != null ? ids.get(value) : null;
            return id != null ? id : NONE;
        }

        /** The value with this id, or null for {@link #NONE}. */
        public String valueOf(int id) {
            if (id == NONE) return null;
            if (id < 0 || id >= size) throw new IndexOutOfBoundsException(id);
            return values[id];
        }

        public int size() { return size; }

        /** A copy of the values, in id order. */
        public List<String> values() {
            int n = size;
            return List.of(Arrays.copyOf(values, n));
        }
    }

    // ── Columns ──────────────────────────────────────────────────────────────

    private static final class FieldColumn {
        final IntColumn ids;
        final Dictionary values = new Dictionary();
        /** Set once the field has too many values; {@link #ids} stops growing then. */
        volatile boolean overflowed;

        FieldColumn(int chunkBits) { ids = new IntColumn(chunkBits); }
    }

    /*
     * Each column is a list of chunks of 2^chunkBits values. Only the last chunk is ever
     * partly full; it starts small and doubles up to the chunk size, so a short log
     * doesn't allocate full chunks.
     *
     * A chunk is never resized in place: growing copies the chunk list, swaps in the
     * bigger chunk, and publishes the new list through the volatile field. A reader sees
     * either the list from before (whose chunks still hold every row it was handed) or a
     * newer one, along with everything copied into it.
     */

    private static final int FIRST_CHUNK = 1024;

    private static final class LongColumn {
        private final int bits, mask;
        private volatile long[][] chunks = new long[0][];
        private int size;

        LongColumn(int bits) { this.bits = bits; this.mask = (1 << bits) - 1; }

        void add(long v) {
            int c = size >>> bits, i = size & mask;
            long[][] cs = chunks;
            if (c == cs.length) {
                cs = Arrays.copyOf(cs, c + 1);
                cs[c] = new long[Math.min(FIRST_CHUNK, 1 << bits)];
                chunks = cs;
            } else if (i == cs[c].length) {
                cs = cs.clone();
                cs[c] = Arrays.copyOf(cs[c], Math.min(i * 2, 1 << bits));
                chunks = cs;
            }
            cs[c][i] = v;
            size++;
        }

        long get(int row) {
            if (row < 0 || row >= size) throw new IndexOutOfBoundsException(row);
            return chunks[row >>> bits][row & mask];
        }

        long capacity() {
            long n = 0;
            for (long[] chunk : chunks) n += chunk.length;
            return n;
        }
    }

    private static final class IntColumn {
        private final int bits, mask;
        private volatile int[][] chunks = new int[0][];
        private int size;

        IntColumn(int bits) { this.bits = bits; this.mask = (1 << bits) - 1; }

        void add(int v) {
            int c = size >>> bits, i = size & mask;
            int[][] cs = chunks;
            if (c == cs.length) {
                cs = Arrays.copyOf(cs, c + 1);
                cs[c] = new int[Math.min(FIRST_CHUNK, 1 << bits)];
                chunks = cs;
            } else if (i == cs[c].length) {
                cs = cs.clone();
                cs[c] = Arrays.copyOf(cs[c], Math.min(i * 2, 1 << bits));
                chunks = cs;
            }
            cs[c][i] = v;
            size++;
        }

        void fill(int count, int v) {
            for (int k = 0; k < count; k++) add(v);
        }

        int get(int row) {
            if (row < 0 || row >= size) throw new IndexOutOfBoundsException(row);
            return chunks[row >>> bits][row & mask];
        }

        long capacity() {
            long n = 0;
            for (int[] chunk : chunks) n += chunk.length;
            return n;
        }
    }

    private static final class ByteColumn {
        private final int bits, mask;
        private volatile byte[][] chunks = new byte[0][];
        private int size;

        ByteColumn(int bits) { this.bits = bits; this.mask = (1 << bits) - 1; }

        void add(byte v) {
            int c = size >>> bits, i = size & mask;
            byte[][] cs = chunks;
            if (c == cs.length) {
                cs = Arrays.copyOf(cs, c + 1);
                cs[c] = new byte[Math.min(FIRST_CHUNK, 1 << bits)];
                chunks = cs;
            } else if (i == cs[c].length) {
                cs = cs.clone();
                cs[c] = Arrays.copyOf(cs[c], Math.min(i * 2, 1 << bits));
                chunks = cs;
            }
            cs[c][i] = v;
            size++;
        }

        byte get(int row) {
            if (row < 0 || row >= size) throw new IndexOutOfBoundsException(row);
            return chunks[row >>> bits][row & mask];
        }

        long capacity() {
            long n = 0;
            for (byte[] chunk : chunks) n += chunk.length;
            return n;
        }
    }
}
