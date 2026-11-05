package com.droidenx.clouseau.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The notes and highlights of a merged view (D-17): each file keeps its own
 * {@link AnnotationStore}, keyed by its own entry numbers and saved next to that file, so
 * the sidecar format doesn't change. This maps merged ordinals to (file, row) and back
 * through the view's {@link MergeOrder}. Used on the UI thread only.
 */
public final class MergedNotes implements Notes {

    private final List<AnnotationStore> stores;
    private final List<String> names;
    private MergeOrder order;

    /** {@code stores} and {@code names} are per source, in the order's source order. */
    public MergedNotes(List<AnnotationStore> stores, List<String> names, MergeOrder order) {
        if (stores.size() != order.sourceCount() || names.size() != stores.size()) {
            throw new IllegalArgumentException(stores.size() + " stores, " + names.size() + " names, "
                    + order.sourceCount() + " sources");
        }
        this.stores = List.copyOf(stores);
        this.names = List.copyOf(names);
        this.order = order;
    }

    /** The view was merged again (an offset changed, or late lines arrived): map through this order now. */
    public void setOrder(MergeOrder order) {
        if (order.sourceCount() != stores.size()) throw new IllegalArgumentException("source count changed");
        this.order = order;
    }

    /** Each file's store, in source order. */
    public List<AnnotationStore> stores() { return stores; }

    private AnnotationStore storeOf(int ordinal) { return stores.get(order.source(ordinal)); }

    @Override
    public AnnotationStore.Annotation at(int ordinal) {
        return ordinal >= 0 && ordinal < order.size() ? storeOf(ordinal).at(order.row(ordinal)) : null;
    }

    @Override
    public boolean has(int ordinal) {
        return ordinal >= 0 && ordinal < order.size() && storeOf(ordinal).has(order.row(ordinal));
    }

    @Override
    public List<AnnotationStore.Placed> placed() {
        List<AnnotationStore.Placed> out = new ArrayList<>();
        for (int s = 0; s < stores.size(); s++) {
            for (AnnotationStore.Placed p : stores.get(s).placed()) {
                if (p.ordinal() < order.rowCount(s)) {
                    out.add(new AnnotationStore.Placed(order.ordinalOf(s, p.ordinal()), p.annotation()));
                }
            }
        }
        out.sort(Comparator.comparingInt(AnnotationStore.Placed::ordinal));
        return out;
    }

    @Override
    public List<AnnotationStore.Annotation> orphans() {
        List<AnnotationStore.Annotation> out = new ArrayList<>();
        for (AnnotationStore s : stores) out.addAll(s.orphans());
        return out;
    }

    @Override
    public int size() {
        int n = 0;
        for (AnnotationStore s : stores) n += s.size();
        return n;
    }

    @Override
    public boolean put(int ordinal, AnnotationStore.Annotation annotation) {
        return storeOf(ordinal).put(order.row(ordinal), annotation);
    }

    @Override
    public boolean remove(int ordinal) {
        return storeOf(ordinal).remove(order.row(ordinal));
    }

    @Override
    public boolean removeOrphan(AnnotationStore.Annotation annotation) {
        for (AnnotationStore s : stores) {
            if (s.orphans().contains(annotation)) return s.removeOrphan(annotation);
        }
        return true;
    }

    @Override
    public Map<Integer, Integer> highlights() {
        TreeMap<Integer, Integer> merged = new TreeMap<>();
        for (int s = 0; s < stores.size(); s++) {
            for (Map.Entry<Integer, Integer> h : stores.get(s).highlights().entrySet()) {
                if (h.getKey() < order.rowCount(s)) merged.put(order.ordinalOf(s, h.getKey()), h.getValue());
            }
        }
        return new LinkedHashMap<>(merged);
    }

    @Override
    public boolean setHighlights(Map<Integer, String> rawLines, Integer slot) {
        List<Map<Integer, String>> bySource = new ArrayList<>();
        for (int s = 0; s < stores.size(); s++) bySource.add(new LinkedHashMap<>());
        rawLines.forEach((ord, raw) -> bySource.get(order.source(ord)).put(order.row(ord), raw));
        boolean saved = true;
        for (int s = 0; s < stores.size(); s++) {
            if (!bySource.get(s).isEmpty()) saved &= stores.get(s).setHighlights(bySource.get(s), slot);
        }
        return saved;
    }

    @Override
    public boolean clearHighlights() {
        boolean saved = true;
        for (AnnotationStore s : stores) saved &= s.clearHighlights();
        return saved;
    }

    @Override
    public Path getSavePath() {
        return stores.get(0).getSavePath();
    }

    @Override
    public String sourceName(int ordinal) {
        return names.get(order.source(ordinal));
    }

    @Override
    public String orphanSource(AnnotationStore.Annotation annotation) {
        for (int s = 0; s < stores.size(); s++) {
            if (stores.get(s).orphans().contains(annotation)) return names.get(s);
        }
        return null;
    }
}
