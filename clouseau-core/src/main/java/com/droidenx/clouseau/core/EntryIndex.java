package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Columns grow in chunks, so no single array gets huge and appending stays cheap. Not
 * thread-safe: one thread appends, and other threads may read only rows below a size the
 * appending thread has handed them.
 */
public final class EntryIndex {

    /** The timestamp of an entry that has none (continuation lines). */
    public static final long NO_TIMESTAMP = Long.MIN_VALUE;
    /** The id of a missing logger, thread or field value. */
    public static final int NONE = -1;
    /** Distinct values a custom field may have and still be indexed. */
    public static final int MAX_FIELD_VALUES = 1_000;

    private static final LogLevel[] LEVELS = LogLevel.values();

    private final int chunkBits;
    private final LongColumn offsets;
    private final IntColumn  lengths;
    private final LongColumn timestamps;
    private final ByteColumn levels;
    private final IntColumn  loggerIds;
    private final IntColumn  threadIds;
    private final Dictionary loggers = new Dictionary();
    private final Dictionary threads = new Dictionary();
    /** Every custom field seen, in first-seen order; null once a field has too many values. */
    private final Map<String, FieldColumn> fields = new LinkedHashMap<>();
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
        levels.add((byte) (e.level() != null ? e.level() : LogLevel.UNKNOWN).ordinal());
        loggerIds.add(loggers.add(e.logger()));
        threadIds.add(threads.add(e.thread()));

        Map<String, String> values = e.fields() != null ? e.fields() : Map.of();
        for (String name : values.keySet()) {
            if (!fields.containsKey(name)) {
                FieldColumn col = new FieldColumn(chunkBits);
                col.ids.fill(size, NONE);   // earlier rows don't have it
                fields.put(name, col);
            }
        }
        for (Map.Entry<String, FieldColumn> f : fields.entrySet()) {
            FieldColumn col = f.getValue();
            if (col == null) continue;
            int id = col.values.add(values.get(f.getKey()));
            if (col.values.size() > MAX_FIELD_VALUES) {
                f.setValue(null);           // too many values to be worth indexing
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
    public LogLevel level(int row)     { return LEVELS[levels.get(row)]; }
    public int loggerId(int row)       { return loggerIds.get(row); }
    public int threadId(int row)       { return threadIds.get(row); }

    public Dictionary loggers()        { return loggers; }
    public Dictionary threads()        { return threads; }

    /** Every custom field seen so far, indexed or not, in the order first seen. */
    public List<String> fieldNames()   { return List.copyOf(fields.keySet()); }

    public boolean isFieldIndexed(String name) { return fields.get(name) != null; }

    /** The field's values, or null if it isn't indexed (unknown, or too many values). */
    public Dictionary fieldValues(String name) {
        FieldColumn col = fields.get(name);
        return col != null ? col.values : null;
    }

    /** The id of the row's value in {@link #fieldValues}, or {@link #NONE}. */
    public int fieldId(String name, int row) {
        FieldColumn col = fields.get(name);
        return col != null ? col.ids.get(row) : NONE;
    }

    /** Roughly how much heap the columns use, for diagnostics. */
    public long estimatedBytes() {
        long bytes = offsets.capacity() * 8 + timestamps.capacity() * 8 + levels.capacity()
                + (lengths.capacity() + loggerIds.capacity() + threadIds.capacity()) * 4;
        for (FieldColumn col : fields.values()) if (col != null) bytes += col.ids.capacity() * 4;
        return bytes;
    }

    // ── Dictionary ───────────────────────────────────────────────────────────

    /** Distinct strings, each with a small id in the order first seen. Null has no id. */
    public static final class Dictionary {
        private final Map<String, Integer> ids = new HashMap<>();
        private final List<String> values = new ArrayList<>();

        private Dictionary() {}

        /** The value's id, adding it if new; {@link #NONE} for null. */
        int add(String value) {
            if (value == null) return NONE;
            Integer id = ids.get(value);
            if (id != null) return id;
            values.add(value);
            ids.put(value, values.size() - 1);
            return values.size() - 1;
        }

        /** The value's id, or {@link #NONE} if it hasn't been seen. */
        public int idOf(String value) {
            Integer id = value != null ? ids.get(value) : null;
            return id != null ? id : NONE;
        }

        /** The value with this id, or null for {@link #NONE}. */
        public String valueOf(int id) { return id == NONE ? null : values.get(id); }

        public int size() { return values.size(); }

        public List<String> values() { return Collections.unmodifiableList(values); }
    }

    // ── Columns ──────────────────────────────────────────────────────────────

    private static final class FieldColumn {
        final IntColumn ids;
        final Dictionary values = new Dictionary();

        FieldColumn(int chunkBits) { ids = new IntColumn(chunkBits); }
    }

    /*
     * Each column is a list of chunks of 2^chunkBits values. Only the last chunk is ever
     * partly full; it starts small and doubles up to the chunk size, so a short log
     * doesn't allocate full chunks.
     */

    private static final int FIRST_CHUNK = 1024;

    private static final class LongColumn {
        private final int bits, mask;
        private long[][] chunks = new long[0][];
        private int size;

        LongColumn(int bits) { this.bits = bits; this.mask = (1 << bits) - 1; }

        void add(long v) {
            int c = size >>> bits, i = size & mask;
            if (c == chunks.length) {
                chunks = Arrays.copyOf(chunks, c + 1);
                chunks[c] = new long[Math.min(FIRST_CHUNK, 1 << bits)];
            } else if (i == chunks[c].length) {
                chunks[c] = Arrays.copyOf(chunks[c], Math.min(i * 2, 1 << bits));
            }
            chunks[c][i] = v;
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
        private int[][] chunks = new int[0][];
        private int size;

        IntColumn(int bits) { this.bits = bits; this.mask = (1 << bits) - 1; }

        void add(int v) {
            int c = size >>> bits, i = size & mask;
            if (c == chunks.length) {
                chunks = Arrays.copyOf(chunks, c + 1);
                chunks[c] = new int[Math.min(FIRST_CHUNK, 1 << bits)];
            } else if (i == chunks[c].length) {
                chunks[c] = Arrays.copyOf(chunks[c], Math.min(i * 2, 1 << bits));
            }
            chunks[c][i] = v;
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
        private byte[][] chunks = new byte[0][];
        private int size;

        ByteColumn(int bits) { this.bits = bits; this.mask = (1 << bits) - 1; }

        void add(byte v) {
            int c = size >>> bits, i = size & mask;
            if (c == chunks.length) {
                chunks = Arrays.copyOf(chunks, c + 1);
                chunks[c] = new byte[Math.min(FIRST_CHUNK, 1 << bits)];
            } else if (i == chunks[c].length) {
                chunks[c] = Arrays.copyOf(chunks[c], Math.min(i * 2, 1 << bits));
            }
            chunks[c][i] = v;
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
