package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Several indexed files shown as one timeline (D-17): each ordinal is mapped through a
 * {@link MergeOrder} to its source and the row in that source's {@link IndexedEntries}.
 * <p>
 * {@link #scan} reads each source in file order, one after the other, so reads stay
 * sequential; the ordinals it reports ascend within each source's run.
 */
public final class MergedEntries implements EntryRows {

    /** One file of the view, and the short name the Source column shows. */
    public record Source(String name, IndexedEntries entries) {}

    private final List<Source> sources;
    private final MergeOrder order;

    /** {@code order} must have been built from these sources, in this order. */
    public MergedEntries(List<Source> sources, MergeOrder order) {
        if (sources.size() != order.sourceCount()) {
            throw new IllegalArgumentException(sources.size() + " sources, order has " + order.sourceCount());
        }
        this.sources = List.copyOf(sources);
        this.order = order;
    }

    public List<Source> sources() { return sources; }

    public MergeOrder order() { return order; }

    /** How many entries the merge holds. */
    public int size() { return order.size(); }

    @Override
    public LogEntry get(int ordinal) {
        return sources.get(order.source(ordinal)).entries().get(order.row(ordinal));
    }

    @Override
    public boolean isContinuation(int ordinal) {
        return index(order.source(ordinal)).isContinuation(order.row(ordinal));
    }

    @Override public int sourceCount() { return sources.size(); }

    @Override public int sourceOf(int ordinal) { return order.source(ordinal); }

    @Override public String sourceName(int source) { return sources.get(source).name(); }

    @Override
    public boolean testIndex(EntryFilter filter, int ordinal) {
        int s = order.source(ordinal);
        return filter.testSource(s) && filter.testIndex(index(s), order.row(ordinal));
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
        for (int s = 0; s < sources.size(); s++) {
            int source = s;
            int first = order.firstRowAtOrAfter(s, from);
            int end = order.firstRowAtOrAfter(s, to);
            visitor.startRun();
            if (first >= end) continue;
            EntryIndex index = index(s);
            boolean complete = sources.get(s).entries().scan(first, end,
                    row -> visitor.wanted(order.ordinalOf(source, row), index.isContinuation(row)),
                    (row, e) -> visitor.accept(order.ordinalOf(source, row), e),
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
}
