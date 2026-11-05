package com.droidenx.clouseau.core;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * The timeline order of a merged view (D-17): which source and row each merged ordinal
 * shows, and back. Built from the sources' timestamp and continuation columns only, with
 * no file I/O.
 * <p>
 * Rows are merged in <em>groups</em>: an entry and the continuation lines after it, never
 * split. A group's key is its first entry's timestamp plus the source's offset; lines
 * before a source's first entry join that entry's group, so they stay at the start of
 * their file. Equal keys keep the sources' order, then each source's row order.
 * <p>
 * With one source there are no arrays: ordinal and row are the same.
 * <p>
 * <b>Threads:</b> {@link #build} runs on any thread; hand the result over through a
 * happens-before edge. After that one thread appends, and others may read ordinals below
 * a size they were handed, as with {@link EntryIndex}.
 */
public final class MergeOrder {

    /** The most sources a merge can hold (a source is stored as an unsigned byte). */
    public static final int MAX_SOURCES = 256;

    /** What the merge reads from a source: {@link EntryIndex} provides it. */
    public interface Timeline {
        /** Epoch milliseconds, or {@link EntryIndex#NO_TIMESTAMP}. */
        long timestamp(int row);
        /** True if the row continues the entry before it. */
        boolean isContinuation(int row);
    }

    /**
     * One source to merge: its rows {@code 0} to {@code count - 1}, read through
     * {@code rows}, with {@code offsetMillis} added to every timestamp.
     */
    public record Input(Timeline rows, int count, long offsetMillis) {
        public Input {
            if (count < 0) throw new IllegalArgumentException("count " + count);
        }
    }

    private static final int CHUNK_BITS = 20;   // chunks of 1M ordinals
    private static final int CHECK_EVERY = 1 << 16;

    private final int sourceCount;
    /** Null with one source. */
    private final ByteColumn sources;
    private final IntColumn rows;
    private final IntColumn[] ordinals;
    private int size;

    private MergeOrder(int sourceCount) {
        this.sourceCount = sourceCount;
        if (sourceCount == 1) {
            sources = null;
            rows = null;
            ordinals = null;
        } else {
            sources = new ByteColumn();
            rows = new IntColumn();
            ordinals = new IntColumn[sourceCount];
            for (int s = 0; s < sourceCount; s++) ordinals[s] = new IntColumn();
        }
    }

    /** Merges {@code inputs} in timeline order. */
    public static MergeOrder build(List<Input> inputs) {
        return build(inputs, () -> false, done -> {});
    }

    /**
     * Merges {@code inputs} in timeline order, reporting the number of rows placed every
     * 64K rows. Returns null if {@code cancelled} turned true.
     */
    public static MergeOrder build(List<Input> inputs, BooleanSupplier cancelled, IntConsumer progress) {
        int k = inputs.size();
        if (k == 0 || k > MAX_SOURCES) throw new IllegalArgumentException(k + " sources");
        MergeOrder order = new MergeOrder(k);
        if (k == 1) {
            order.size = inputs.get(0).count();
            return order;
        }

        Timeline[] tl = new Timeline[k];
        int[] count = new int[k];
        long[] offset = new long[k];
        int[] next = new int[k];     // the first row of each source's next group
        long[] key = new long[k];    // that group's key
        for (int s = 0; s < k; s++) {
            Input in = inputs.get(s);
            tl[s] = in.rows();
            count[s] = in.count();
            offset[s] = in.offsetMillis();
            key[s] = groupKey(tl[s], 0, count[s], offset[s]);
        }

        int placed = 0;
        int nextCheck = CHECK_EVERY;
        while (true) {
            // The source with the smallest (key, source), and the runner-up to stop at.
            int best = -1, second = -1;
            for (int s = 0; s < k; s++) {
                if (next[s] >= count[s]) continue;
                if (best < 0 || key[s] < key[best]) { second = best; best = s; }
                else if (second < 0 || key[s] < key[second]) second = s;
            }
            if (best < 0) break;

            // Emit groups from best while they still come first: a run of a source that
            // doesn't overlap the others (a rotated file) costs one comparison per group.
            do {
                int end = groupEnd(tl[best], next[best], count[best]);
                for (int r = next[best]; r < end; r++) order.add(best, r);
                placed += end - next[best];
                next[best] = end;
                key[best] = end < count[best] ? keyAt(tl[best], end, offset[best]) : Long.MAX_VALUE;
                if (placed >= nextCheck) {
                    if (cancelled.getAsBoolean()) return null;
                    progress.accept(placed);
                    nextCheck = placed + CHECK_EVERY;
                }
            } while (next[best] < count[best]
                    && (second < 0 || key[best] < key[second] || (key[best] == key[second] && best < second)));
        }
        return order;
    }

    /** The key of the group starting at {@code row}, the first group of a source if 0. */
    private static long groupKey(Timeline tl, int row, int count, long offset) {
        if (row >= count) return Long.MAX_VALUE;
        if (row > 0) return keyAt(tl, row, offset);
        // Leading continuation lines take the first entry's timestamp.
        for (int r = 0; r < count; r++) {
            if (!tl.isContinuation(r)) return keyAt(tl, r, offset);
        }
        return Long.MIN_VALUE;   // no entry at all: the whole source goes first
    }

    private static long keyAt(Timeline tl, int row, long offset) {
        long ts = tl.timestamp(row);
        if (ts == EntryIndex.NO_TIMESTAMP) return Long.MIN_VALUE;
        try {
            return Math.addExact(ts, offset);
        } catch (ArithmeticException e) {
            return offset > 0 ? Long.MAX_VALUE - 1 : Long.MIN_VALUE + 1;
        }
    }

    /** The row after the group starting at {@code row}. */
    private static int groupEnd(Timeline tl, int row, int count) {
        int r = row;
        // The first group also takes the leading continuation lines before its entry.
        while (r < count && tl.isContinuation(r)) r++;
        r++;
        while (r < count && tl.isContinuation(r)) r++;
        return Math.min(r, count);
    }

    private void add(int source, int row) {
        sources.add((byte) source);
        rows.add(row);
        ordinals[source].add(size);
        size++;
    }

    /**
     * Places {@code row} of {@code source} at the end of the timeline. Rows of one source
     * must be appended in order, after the rows it already has.
     */
    public void append(int source, int row) {
        if (sourceCount == 1) {
            if (source != 0 || row != size) throw new IllegalArgumentException(source + ":" + row);
            size++;
            return;
        }
        if (row != ordinals[source].size) throw new IllegalArgumentException(source + ":" + row);
        add(source, row);
    }

    public int size() { return size; }

    public int sourceCount() { return sourceCount; }

    /** The source shown at {@code ordinal}. */
    public int source(int ordinal) {
        if (sourceCount > 1) return sources.get(ordinal) & 0xFF;
        check(ordinal, size);
        return 0;
    }

    /** The row, in its source, shown at {@code ordinal}. */
    public int row(int ordinal) {
        if (sourceCount > 1) return rows.get(ordinal);
        check(ordinal, size);
        return ordinal;
    }

    /** How many rows of {@code source} have been placed. */
    public int rowCount(int source) {
        check(source, sourceCount);
        return sourceCount > 1 ? ordinals[source].size : size;
    }

    /** The merged ordinal showing {@code row} of {@code source}. */
    public int ordinalOf(int source, int row) {
        check(source, sourceCount);
        if (sourceCount > 1) return ordinals[source].get(row);
        check(row, size);
        return row;
    }

    /** About how many bytes of heap the arrays use. */
    public long estimatedBytes() {
        if (sourceCount == 1) return 0;
        long n = sources.capacity() + 4L * rows.capacity();
        for (IntColumn c : ordinals) n += 4L * c.capacity();
        return n;
    }

    private static void check(int i, int size) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i);
    }

    // Columns as in EntryIndex: chunks are replaced, never resized in place, and the chunk
    // list is volatile, so a reader handed a size always sees its rows.

    private static final int FIRST_CHUNK = 1024;

    private static final class IntColumn {
        private volatile int[][] chunks = new int[0][];
        private int size;

        void add(int v) {
            int c = size >>> CHUNK_BITS, i = size & ((1 << CHUNK_BITS) - 1);
            int[][] cs = chunks;
            if (c == cs.length) {
                cs = Arrays.copyOf(cs, c + 1);
                cs[c] = new int[FIRST_CHUNK];
                chunks = cs;
            } else if (i == cs[c].length) {
                cs = cs.clone();
                cs[c] = Arrays.copyOf(cs[c], Math.min(i * 2, 1 << CHUNK_BITS));
                chunks = cs;
            }
            cs[c][i] = v;
            size++;
        }

        int get(int i) {
            if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i);
            return chunks[i >>> CHUNK_BITS][i & ((1 << CHUNK_BITS) - 1)];
        }

        long capacity() {
            long n = 0;
            for (int[] chunk : chunks) n += chunk.length;
            return n;
        }
    }

    private static final class ByteColumn {
        private volatile byte[][] chunks = new byte[0][];
        private int size;

        void add(byte v) {
            int c = size >>> CHUNK_BITS, i = size & ((1 << CHUNK_BITS) - 1);
            byte[][] cs = chunks;
            if (c == cs.length) {
                cs = Arrays.copyOf(cs, c + 1);
                cs[c] = new byte[FIRST_CHUNK];
                chunks = cs;
            } else if (i == cs[c].length) {
                cs = cs.clone();
                cs[c] = Arrays.copyOf(cs[c], Math.min(i * 2, 1 << CHUNK_BITS));
                chunks = cs;
            }
            cs[c][i] = v;
            size++;
        }

        byte get(int i) {
            if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i);
            return chunks[i >>> CHUNK_BITS][i & ((1 << CHUNK_BITS) - 1)];
        }

        long capacity() {
            long n = 0;
            for (byte[] chunk : chunks) n += chunk.length;
            return n;
        }
    }
}
