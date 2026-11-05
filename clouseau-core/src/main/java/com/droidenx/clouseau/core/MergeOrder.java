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
 * happens-before edge. After that one thread appends or extends, and others may read
 * ordinals below a size they were handed, as with {@link EntryIndex}. An order that
 * {@link #extend} replaced stays as it was for its readers.
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

    private MergeOrder(int sourceCount, ByteColumn sources, IntColumn rows, IntColumn[] ordinals) {
        this.sourceCount = sourceCount;
        this.sources = sources;
        this.rows = rows;
        this.ordinals = ordinals;
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
        return order.merge(inputs, new int[k], cancelled, progress) ? order : null;
    }

    /**
     * Places rows {@code next[s]} onwards of each input after the rows this order holds.
     * A source whose next row continues its last placed group goes first: {@link #placeFrom}
     * only cuts the order where that group ends. False if {@code cancelled} turned true.
     */
    private boolean merge(List<Input> inputs, int[] next, BooleanSupplier cancelled, IntConsumer progress) {
        int k = sourceCount;
        Timeline[] tl = new Timeline[k];
        int[] count = new int[k];
        long[] offset = new long[k];
        long[] key = new long[k];    // the key of each source's next group
        for (int s = 0; s < k; s++) {
            Input in = inputs.get(s);
            tl[s] = in.rows();
            count[s] = in.count();
            offset[s] = in.offsetMillis();
            while (next[s] > 0 && next[s] < count[s] && tl[s].isContinuation(next[s])) add(s, next[s]++);
            key[s] = groupKey(tl[s], next[s], count[s], offset[s]);
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
                for (int r = next[best]; r < end; r++) add(best, r);
                placed += end - next[best];
                next[best] = end;
                key[best] = end < count[best] ? keyAt(tl[best], end, offset[best]) : Long.MAX_VALUE;
                if (placed >= nextCheck) {
                    if (cancelled.getAsBoolean()) return false;
                    progress.accept(placed);
                    nextCheck = placed + CHECK_EVERY;
                }
            } while (next[best] < count[best]
                    && (second < 0 || key[best] < key[second] || (key[best] == key[second] && best < second)));
        }
        return true;
    }

    /**
     * The first ordinal whose row changes when the rows the sources gained since this order
     * was built or extended are placed: {@link #size} if they all go after the rows shown.
     * Each input describes the same source as before, with a count at least its
     * {@link #rowCount}.
     */
    public int placeFrom(List<Input> inputs) {
        checkInputs(inputs);
        if (sourceCount == 1) return size;
        int from = size;
        for (int s = 0; s < sourceCount; s++) {
            Input in = inputs.get(s);
            int n = ordinals[s].size;
            if (in.count() == n) continue;
            // Never before this source's own rows: a file keeps its order.
            int floor = n == 0 ? 0 : ordinalOf(s, n - 1) + 1;
            if (n > 0 && in.rows().isContinuation(n)) {      // continues the group placed last
                from = Math.min(from, floor);
                continue;
            }
            long key = groupKey(in.rows(), n, in.count(), in.offsetMillis());
            int q = size - 1;
            // Step back over whole groups that sort after the new one.
            while (q >= floor) {
                int t = source(q);
                Input other = inputs.get(t);
                int head = row(q);
                while (head > 0 && other.rows().isContinuation(head)) head--;
                long k = groupKey(other.rows(), head, other.count(), other.offsetMillis());
                if (k > key || (k == key && t > s)) q = ordinalOf(t, head) - 1;
                else break;
            }
            from = Math.min(from, q + 1);
        }
        return from;
    }

    /**
     * Places the rows the sources gained (see {@link #placeFrom}). If they all go at the end
     * they are appended to this order, which is returned. Otherwise returns a new order that
     * shares this one's rows below {@code placeFrom(inputs)} and merges the rest again; this
     * order is then left as it was, for readers still using it, and must not be extended.
     */
    public MergeOrder extend(List<Input> inputs) {
        int from = placeFrom(inputs);
        if (sourceCount == 1) {
            size = inputs.get(0).count();
            return this;
        }
        MergeOrder target = from == size ? this : prefix(from);
        int[] next = new int[sourceCount];
        for (int s = 0; s < sourceCount; s++) next[s] = target.ordinals[s].size;
        target.merge(inputs, next, () -> false, done -> {});
        return target;
    }

    private void checkInputs(List<Input> inputs) {
        if (inputs.size() != sourceCount) {
            throw new IllegalArgumentException(inputs.size() + " sources, order has " + sourceCount);
        }
        for (int s = 0; s < sourceCount; s++) {
            if (inputs.get(s).count() < rowCount(s)) {
                throw new IllegalArgumentException("source " + s + " has fewer rows than placed");
            }
        }
    }

    /** A new order holding this one's first {@code n} ordinals, sharing their full chunks. */
    private MergeOrder prefix(int n) {
        IntColumn[] ords = new IntColumn[sourceCount];
        for (int s = 0; s < sourceCount; s++) ords[s] = ordinals[s].prefix(firstRowAtOrAfter(s, n));
        MergeOrder o = new MergeOrder(sourceCount, sources.prefix(n), rows.prefix(n), ords);
        o.size = n;
        return o;
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

    /** The first row of {@code source} placed at {@code ordinal} or later (its row count if none). */
    public int firstRowAtOrAfter(int source, int ordinal) {
        check(source, sourceCount);
        if (sourceCount == 1) return Math.max(0, Math.min(ordinal, size));
        IntColumn col = ordinals[source];
        int lo = 0, hi = col.size;        // ordinals ascend with rows
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (col.get(mid) < ordinal) lo = mid + 1;
            else hi = mid;
        }
        return lo;
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

        /** The first {@code n} values; full chunks are shared, the partial one is copied (it is written again). */
        IntColumn prefix(int n) {
            IntColumn p = new IntColumn();
            int[][] cs = chunks;
            int full = n >>> CHUNK_BITS;
            int[][] out = Arrays.copyOf(cs, Math.min(cs.length, full + 1));
            if (full < out.length) out[full] = out[full].clone();
            p.chunks = out;
            p.size = n;
            return p;
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

        /** The first {@code n} values; full chunks are shared, the partial one is copied (it is written again). */
        ByteColumn prefix(int n) {
            ByteColumn p = new ByteColumn();
            byte[][] cs = chunks;
            int full = n >>> CHUNK_BITS;
            byte[][] out = Arrays.copyOf(cs, Math.min(cs.length, full + 1));
            if (full < out.length) out[full] = out[full].clone();
            p.chunks = out;
            p.size = n;
            return p;
        }
    }
}
