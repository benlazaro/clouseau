package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Several indexed files shown as one timeline (D-17): each ordinal is mapped through a
 * {@link MergeOrder} to its source and the row in that source's {@link IndexedEntries}.
 * <p>
 * A source can have a <em>time offset</em>, which corrects a wrong clock: it is added to
 * the source's timestamps when they are merged, read ({@link #get}) and filtered by time,
 * so the view shows the corrected time. The entry's raw line stays as the file wrote it.
 * <p>
 * {@link #scan} reads each source in file order, one after the other, so reads stay
 * sequential; the ordinals it reports ascend within each source's run.
 */
public final class MergedEntries implements EntryRows {

    /** One file of the view, and the short name the Source column shows. */
    public record Source(String name, IndexedEntries entries) {}

    /** The order and the offsets it was built with, replaced together. */
    private record Layout(MergeOrder order, long[] offsets) {}

    private final List<Source> sources;
    /** Replaced when following re-merges or an offset changes; readers take it once per call. */
    private volatile Layout layout;

    /** {@code order} must have been built from these sources, in this order, with no offsets. */
    public MergedEntries(List<Source> sources, MergeOrder order) {
        this(sources, order, new long[sources.size()]);
    }

    /** {@code order} must have been built from these sources, in this order, with these offsets. */
    public MergedEntries(List<Source> sources, MergeOrder order, long[] offsetMillis) {
        if (sources.size() != order.sourceCount() || offsetMillis.length != sources.size()) {
            throw new IllegalArgumentException(sources.size() + " sources, order has " + order.sourceCount());
        }
        this.sources = List.copyOf(sources);
        this.layout = new Layout(order, offsetMillis.clone());
    }

    public List<Source> sources() { return sources; }

    public MergeOrder order() { return layout.order(); }

    /**
     * Shows {@code order} from now on: the same sources, extended ({@link MergeOrder#extend}).
     * Called by the one thread that extends the order.
     */
    public void setOrder(MergeOrder order) {
        setLayout(order, layout.offsets());
    }

    /**
     * Shows {@code order}, built (and extended) with {@code offsetMillis}, from now on.
     * Called by the one thread that extends the order.
     */
    public void setOrder(MergeOrder order, long[] offsetMillis) {
        setLayout(order, offsetMillis.clone());
    }

    private void setLayout(MergeOrder order, long[] offsets) {
        if (order.sourceCount() != sources.size() || offsets.length != sources.size()) {
            throw new IllegalArgumentException("sources differ");
        }
        layout = new Layout(order, offsets);
    }

    /** What is added to {@code source}'s timestamps to place and show them. */
    public long offsetMillis(int source) {
        return layout.offsets()[source];
    }

    /** Every source's offset. */
    public long[] offsetsMillis() {
        return layout.offsets().clone();
    }

    /** The merge's inputs: each source's first {@code counts[s]} rows, with the current offsets. */
    public List<MergeOrder.Input> inputs(int[] counts) {
        return inputs(counts, layout.offsets());
    }

    /** The merge's inputs: each source's first {@code counts[s]} rows, with these offsets. */
    public List<MergeOrder.Input> inputs(int[] counts, long[] offsetMillis) {
        List<MergeOrder.Input> inputs = new ArrayList<>(sources.size());
        for (int s = 0; s < sources.size(); s++) {
            inputs.add(new MergeOrder.Input(index(s), counts[s], offsetMillis[s]));
        }
        return inputs;
    }

    /** How many rows of each source the order holds now. */
    public int[] rowCounts() {
        MergeOrder o = layout.order();
        int[] counts = new int[sources.size()];
        for (int s = 0; s < counts.length; s++) counts[s] = o.rowCount(s);
        return counts;
    }

    /** How many entries the merge holds. */
    public int size() { return layout.order().size(); }

    @Override
    public LogEntry get(int ordinal) {
        Layout l = layout;
        int s = l.order().source(ordinal);
        return shifted(sources.get(s).entries().get(l.order().row(ordinal)), l.offsets()[s]);
    }

    /** The entry with its timestamp corrected by {@code offset}. */
    private static LogEntry shifted(LogEntry e, long offset) {
        if (offset == 0 || e == null || e.timestamp() == null) return e;
        return new LogEntry(e.timestamp().plusMillis(offset), e.level(), e.logger(), e.thread(),
                e.message(), e.rawLine(), e.fields());
    }

    @Override
    public boolean isContinuation(int ordinal) {
        MergeOrder o = layout.order();
        return index(o.source(ordinal)).isContinuation(o.row(ordinal));
    }

    @Override public int sourceCount() { return sources.size(); }

    @Override public int sourceOf(int ordinal) { return layout.order().source(ordinal); }

    @Override public long lineCount() {
        long n = 0;
        for (int s = 0; s < sources.size(); s++) n += index(s).lineCount();
        return n;
    }

    @Override public int lineNumber(int ordinal) {
        MergeOrder o = layout.order();
        return index(o.source(ordinal)).lineNumber(o.row(ordinal));
    }

    @Override public String sourceName(int source) { return sources.get(source).name(); }

    /** The name, with the offset if it has one: {@code device.log (+3m 12s)}. */
    @Override
    public String sourceLabel(int source) {
        long offset = offsetMillis(source);
        return offset == 0 ? sourceName(source) : sourceName(source) + " (" + TimeOffset.format(offset) + ")";
    }

    @Override
    public boolean testIndex(EntryFilter filter, int ordinal) {
        Layout l = layout;
        int s = l.order().source(ordinal);
        return filter.testSource(s) && filter.testIndex(index(s), l.order().row(ordinal), l.offsets()[s]);
    }

    @Override
    public boolean needsText(EntryFilter filter) {
        for (int s = 0; s < sources.size(); s++) {
            if (filter.testSource(s) && filter.needsText(index(s))) return true;
        }
        return false;
    }

    /** One run per source, each in file order. */
    @Override
    public boolean scan(int from, int to, Visitor visitor, BooleanSupplier cancelled) {
        Layout l = layout;
        MergeOrder order = l.order();
        for (int s = 0; s < sources.size(); s++) {
            int source = s;
            long offset = l.offsets()[s];
            int first = order.firstRowAtOrAfter(s, from);
            int end = order.firstRowAtOrAfter(s, to);
            visitor.startRun();
            if (first >= end) continue;
            EntryIndex index = index(s);
            boolean complete = sources.get(s).entries().scan(first, end,
                    row -> visitor.wanted(order.ordinalOf(source, row), index.isContinuation(row)),
                    (row, e) -> visitor.accept(order.ordinalOf(source, row), shifted(e, offset)),
                    cancelled);
            if (!complete) return false;
        }
        return true;
    }

    private EntryIndex index(int source) {
        return sources.get(source).entries().index();
    }

    @Override
    public void close() {
        for (Source s : sources) s.entries().close();
    }

    @Override
    public String toString() {
        return "MergedEntries" + Arrays.toString(sources.stream().map(Source::name).toArray());
    }
}
