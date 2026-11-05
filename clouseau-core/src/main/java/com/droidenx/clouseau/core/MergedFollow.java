package com.droidenx.clouseau.core;

import java.util.Arrays;

/**
 * Following a merged view (D-17). The files are written at about the same time, but not in
 * order: one is written a moment after another, or its writer buffers. So the rows each
 * followed file gains are <em>held back</em>, then placed in timeline order through
 * {@link MergeOrder#extend}.
 * <p>
 * Held rows are placed once every file that has written since following started has
 * delivered something at least as new, or after {@link #HOLD_MILLIS} at the most. A row
 * that still arrives too late for its place (a clock skew larger than the hold-back) makes
 * the order re-merge from that place, at most once per {@link #REMERGE_EVERY_MILLIS}; the
 * rows wait meanwhile.
 * <p>
 * <b>Threads:</b> confined to one thread (the EDT), which also extends the view's order.
 * Row counts are handed over from the tail threads through {@link #arrived}.
 */
public final class MergedFollow {

    /** The longest a followed row is held back. */
    public static final long HOLD_MILLIS = 1_000;
    /** The least time between two re-merges. */
    public static final long REMERGE_EVERY_MILLIS = 1_000;

    /**
     * Rows were placed. Ordinals from {@code from} on are new, or (if {@link #moved()}) show
     * other rows than in {@code before}, which is left as it was.
     */
    public record Placed(MergeOrder before, MergeOrder after, int from) {
        public boolean moved() { return before != after; }
    }

    private final MergedEntries entries;
    private final int sources;
    /** Rows each source's index holds, as handed over. */
    private final int[] available;
    /** True once a source has delivered a row while following. */
    private final boolean[] active;
    /** The newest timestamp each source delivered while following. */
    private final long[] latest;
    /** When the oldest held row arrived, or -1 if none is held. */
    private long pendingSince = -1;
    private long lastRemerge = Long.MIN_VALUE / 2;

    public MergedFollow(MergedEntries entries) {
        this.entries = entries;
        this.sources = entries.sourceCount();
        MergeOrder order = entries.order();
        available = new int[sources];
        for (int s = 0; s < sources; s++) available[s] = order.rowCount(s);
        active = new boolean[sources];
        latest = new long[sources];
        Arrays.fill(latest, Long.MIN_VALUE);
    }

    /** {@code source}'s index now holds {@code count} rows. {@code now} is in milliseconds. */
    public void arrived(int source, int count, long now) {
        if (count <= available[source]) return;
        EntryIndex index = entries.sources().get(source).entries().index();
        for (int r = available[source]; r < count; r++) {
            if (!index.isContinuation(r)) latest[source] = Math.max(latest[source], key(source, index, r));
        }
        available[source] = count;
        active[source] = true;
        if (pendingSince < 0) pendingSince = now;
    }

    /** True if rows are held back. */
    public boolean hasPending() {
        MergeOrder order = entries.order();
        for (int s = 0; s < sources; s++) {
            if (available[s] > order.rowCount(s)) return true;
        }
        return false;
    }

    /**
     * Places the held rows that are due, and installs the new order in the view if it
     * re-merged. Returns null if nothing was placed.
     */
    public Placed step(long now) {
        if (pendingSince < 0) return null;
        MergeOrder order = entries.order();
        int[] counts = now - pendingSince >= HOLD_MILLIS ? available.clone() : due(order);
        if (counts == null) return null;
        var inputs = entries.inputs(counts);
        int from = order.placeFrom(inputs);
        if (from < order.size() && now - lastRemerge < REMERGE_EVERY_MILLIS) return null;
        MergeOrder after = order.extend(inputs);
        if (after != order) {
            lastRemerge = now;
            entries.setOrder(after);
        }
        if (!hasPending()) pendingSince = -1;
        return new Placed(order, after, from);
    }

    /** Per source, the held rows up to the first entry newer than every active source's newest. */
    private int[] due(MergeOrder order) {
        long watermark = Long.MAX_VALUE;
        for (int s = 0; s < sources; s++) {
            if (active[s]) watermark = Math.min(watermark, latest[s]);
        }
        int[] counts = new int[sources];
        boolean any = false;
        for (int s = 0; s < sources; s++) {
            EntryIndex index = entries.sources().get(s).entries().index();
            int placed = order.rowCount(s), r = placed;
            while (r < available[s] && (index.isContinuation(r) || key(s, index, r) <= watermark)) r++;
            counts[s] = r;
            any |= r > placed;
        }
        return any ? counts : null;
    }

    private long key(int source, EntryIndex index, int row) {
        long ts = index.timestamp(row);
        return ts == EntryIndex.NO_TIMESTAMP ? Long.MIN_VALUE : ts + entries.offsetMillis(source);
    }
}
