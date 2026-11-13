package com.droidenx.clouseau.ui;

import lombok.extern.slf4j.Slf4j;

import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * The log table's row sorter: # and Timestamp, each either way. It never reads an entry, so
 * sorting a tab of any size never stalls the window (Swing's {@code TableRowSorter} compared
 * cell values on the EDT, reading two lines back from disk for every comparison).
 * <ul>
 *   <li># ascending is the model's own order and descending its reverse: nothing to work
 *       out, whatever the size.</li>
 *   <li>Timestamp usually is the model's order too: always in a merged view (the merge is by
 *       time), nearly always in one file. The rows' times are read from the index (no I/O)
 *       on a background thread; if they're in order, the result is the same as #. Otherwise
 *       a stable sort of the row numbers gives the order, and the table shows # order until it
 *       arrives. A continuation line keeps its entry's time, so it stays under its entry.</li>
 * </ul>
 * Followed rows that keep time order are added in place; others start a new background sort.
 * Rows removed (trimmed, cleared) are dropped from the order on the EDT. An "all rows changed"
 * event that left the rows as they were (a repaint) keeps the order.
 */
@Slf4j
final class LogRowSorter extends RowSorter<LogTableModel> {

    static final int NUMBER_COLUMN = 0, TIME_COLUMN = 1;

    /** One thread for every tab's time sorts: they're short, and never more than one per tab is wanted. */
    private static final ExecutorService SORTS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clouseau-sort");
        t.setDaemon(true);
        return t;
    });

    private final LogTableModel model;
    private List<SortKey> keys = List.of();

    /** View → model, or null when the order is the model's own ({@link #reversed} false) or its reverse. */
    private int[] viewToModel;
    private int[] modelToView;
    private boolean reversed;
    /** The model's row count as of the last event: JTable converts old view rows after the model changed. */
    private int rowCount;

    /** For a time sort: the rows version the order was worked out for; -1 if none yet. */
    private long sortedVersion = -1;
    /** For a time sort: the time of the last model row and the latest time, to place followed rows. */
    private long lastKey = Long.MIN_VALUE, maxKey = Long.MIN_VALUE;
    /** Counts requests; a background result for an older one is dropped. */
    private int request;
    /** Told when a background time sort starts (true) and ends (false). */
    private java.util.function.Consumer<Boolean> busyListener = b -> {};

    LogRowSorter(LogTableModel model) {
        this.model = model;
        this.rowCount = model.getRowCount();
    }

    void setBusyListener(java.util.function.Consumer<Boolean> listener) {
        busyListener = listener;
    }

    boolean isSortable(int column) {
        return column == NUMBER_COLUMN || column == TIME_COLUMN;
    }

    // ── RowSorter ───────────────────────────────────────────────────────────

    @Override public LogTableModel getModel() { return model; }

    @Override public List<? extends SortKey> getSortKeys() { return keys; }

    /** First click sorts ascending, then each click flips it, as Swing's sorter does. */
    @Override
    public void toggleSortOrder(int column) {
        if (!isSortable(column)) return;
        SortOrder order = !keys.isEmpty() && keys.get(0).getColumn() == column
                && keys.get(0).getSortOrder() == SortOrder.ASCENDING ? SortOrder.DESCENDING : SortOrder.ASCENDING;
        setSortKeys(List.of(new SortKey(column, order)));
    }

    @Override
    public void setSortKeys(List<? extends SortKey> sortKeys) {
        List<SortKey> next = sortKeys == null ? List.of() : sortKeys.stream()
                .filter(k -> isSortable(k.getColumn()) && k.getSortOrder() != SortOrder.UNSORTED)
                .limit(1).map(k -> (SortKey) k).toList();
        if (next.equals(keys)) return;
        int[] previous = currentViewToModel();
        keys = next;
        fireSortOrderChanged();
        sortedVersion = -1;
        interimOrder();
        fireRowSorterChanged(previous);
        if (isTimeSort()) requestTimeSort();
    }

    @Override
    public int convertRowIndexToModel(int index) {
        int n = rowCount;
        if (index < 0 || index >= n) throw new IndexOutOfBoundsException("view row " + index + " of " + n);
        return viewToModel != null ? viewToModel[index] : reversed ? n - 1 - index : index;
    }

    @Override
    public int convertRowIndexToView(int index) {
        int n = rowCount;
        if (index < 0 || index >= n) throw new IndexOutOfBoundsException("model row " + index + " of " + n);
        return modelToView != null ? modelToView[index] : reversed ? n - 1 - index : index;
    }

    @Override public int getViewRowCount()  { return rowCount; }
    @Override public int getModelRowCount() { return rowCount; }

    @Override public void modelStructureChanged() { allRowsChanged(); }

    @Override
    public void allRowsChanged() {
        rowCount = model.getRowCount();
        if (!isTimeSort()) {
            interimOrder();
            return;
        }
        // A repaint (highlights, timezone) leaves the rows, and so the order, as they were.
        if (sortedVersion == model.rowsVersion() && arraysFit()) return;
        sortedVersion = -1;
        interimOrder();
        requestTimeSort();
    }

    /**
     * The model only ever adds rows at its end. In # order they're placed at once; in time
     * order too if they come after every time so far, else a background sort places them.
     */
    @Override
    public void rowsInserted(int firstRow, int endRow) {
        if (!isTimeSort()) {
            rowCount += endRow - firstRow + 1;
            interimOrder();
            return;
        }
        boolean inOrder = sortedVersion != -1;
        long last = lastKey, max = maxKey;
        for (int r = firstRow; r <= endRow && inOrder; r++) {
            long ts = model.timestampAt(r);
            if (ts != com.droidenx.clouseau.core.EntryIndex.NO_TIMESTAMP) last = ts;
            if (last < max) inOrder = false;
            max = Math.max(max, last);
        }
        int added = endRow - firstRow + 1;
        rowCount += added;
        if (viewToModel != null) insertIntoArrays(firstRow, added);
        if (inOrder) {
            lastKey = last;
            maxKey = max;
            sortedVersion = model.rowsVersion();
        } else {
            sortedVersion = -1;
            requestTimeSort();
        }
    }

    @Override
    public void rowsDeleted(int firstRow, int endRow) {
        rowCount -= endRow - firstRow + 1;
        if (viewToModel != null) removeFromArrays(firstRow, endRow - firstRow + 1);
        if (isTimeSort() && sortedVersion != -1) sortedVersion = model.rowsVersion();
    }

    @Override public void rowsUpdated(int firstRow, int endRow) {}

    @Override public void rowsUpdated(int firstRow, int endRow, int column) {}

    // ── Orders ──────────────────────────────────────────────────────────────

    private boolean isTimeSort() {
        return !keys.isEmpty() && keys.get(0).getColumn() == TIME_COLUMN;
    }

    private boolean descending() {
        return !keys.isEmpty() && keys.get(0).getSortOrder() == SortOrder.DESCENDING;
    }

    /** The model's order, or its reverse for a descending sort: right for #, and close for time. */
    private void interimOrder() {
        viewToModel = null;
        modelToView = null;
        reversed = descending();
    }

    private boolean arraysFit() {
        return viewToModel == null || viewToModel.length == model.getRowCount();
    }

    private int[] currentViewToModel() {
        int n = rowCount;
        if (viewToModel != null) return viewToModel.clone();
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = reversed ? n - 1 - i : i;
        return a;
    }

    /** Works out the time order off the EDT, from the index's timestamps. */
    private void requestTimeSort() {
        int id = ++request;
        LogTableModel.TimeKeys snapshot = model.timeKeys();
        busyListener.accept(true);
        BooleanSupplier stale = () -> id != request;
        try {
            SORTS.execute(() -> {
                long start = System.nanoTime();
                Result result = null;
                try {
                    long[] times = snapshot.compute(stale);
                    if (times != null) {
                        int[] order = ascendingOrder(times, stale);
                        if (!stale.getAsBoolean()) result = new Result(order, times);
                    }
                } catch (RuntimeException e) {
                    log.debug("Time sort dropped: the rows changed while it ran", e);
                }
                Result done = result;
                long ms = (System.nanoTime() - start) / 1_000_000;
                SwingUtilities.invokeLater(() -> finish(id, snapshot.version(), done, ms));
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            busyListener.accept(false);
        }
    }

    private record Result(int[] ascending, long[] times) {}

    private void finish(int id, long version, Result result, long ms) {
        if (id != request) return;   // a newer request is on its way
        busyListener.accept(false);
        if (result == null || !isTimeSort()) return;
        if (version != model.rowsVersion() || result.times().length != model.getRowCount()) {
            requestTimeSort();       // the rows changed meanwhile
            return;
        }
        long[] t = result.times();
        lastKey = t.length > 0 ? t[t.length - 1] : Long.MIN_VALUE;
        maxKey = Long.MIN_VALUE;
        for (long k : t) maxKey = Math.max(maxKey, k);
        sortedVersion = version;
        log.debug("Time sort of {} rows in {} ms ({})", t.length, ms,
                result.ascending() == null ? "already in order" : "reordered");
        if (result.ascending() == null) return;   // the interim order is the time order

        int[] previous = currentViewToModel();
        int n = t.length;
        int[] v2m = result.ascending();
        if (descending()) {
            for (int i = 0, j = n - 1; i < j; i++, j--) { int x = v2m[i]; v2m[i] = v2m[j]; v2m[j] = x; }
        }
        int[] m2v = new int[n];
        for (int v = 0; v < n; v++) m2v[v2m[v]] = v;
        viewToModel = v2m;
        modelToView = m2v;
        fireRowSorterChanged(previous);
    }

    /** Model rows {@code first} on were added: at the view's end ascending, at its start descending. */
    private void insertIntoArrays(int first, int added) {
        int old = viewToModel.length, n = old + added;
        int[] v2m = new int[n];
        if (descending()) {
            for (int i = 0; i < added; i++) v2m[i] = n - 1 - i;
            System.arraycopy(viewToModel, 0, v2m, added, old);
        } else {
            System.arraycopy(viewToModel, 0, v2m, 0, old);
            for (int i = 0; i < added; i++) v2m[old + i] = first + i;
        }
        setArrays(v2m);
    }

    /** Model rows {@code first} to {@code first + count - 1} were removed; the rest keep their order. */
    private void removeFromArrays(int first, int count) {
        int[] v2m = new int[viewToModel.length - count];
        int j = 0;
        for (int m : viewToModel) {
            if (m < first) v2m[j++] = m;
            else if (m >= first + count) v2m[j++] = m - count;
        }
        setArrays(v2m);
    }

    private void setArrays(int[] v2m) {
        int[] m2v = new int[v2m.length];
        for (int v = 0; v < v2m.length; v++) m2v[v2m[v]] = v;
        viewToModel = v2m;
        modelToView = m2v;
    }

    /**
     * The rows in ascending time, ties in model order (stable); null if they already are. A
     * natural merge sort: it merges the runs that are already in order, so a log with a few
     * lines out of place costs little more than reading the times. Null too if cancelled.
     */
    static int[] ascendingOrder(long[] keys, BooleanSupplier cancelled) {
        int n = keys.length;
        int[] bounds = new int[n + 1];
        int count = 0;
        bounds[count++] = 0;
        for (int i = 1; i < n; i++) if (keys[i] < keys[i - 1]) bounds[count++] = i;
        if (count == 1) return null;
        bounds[count++] = n;

        int[] a = new int[n], b = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        while (count > 2) {
            if (cancelled.getAsBoolean()) return null;
            int next = 0;
            for (int r = 0; r + 1 < count; r += 2) {
                int lo = bounds[r], mid = bounds[r + 1], hi = r + 2 < count ? bounds[r + 2] : mid;
                int i = lo, j = mid, k = lo;
                while (i < mid && j < hi) b[k++] = keys[a[j]] < keys[a[i]] ? a[j++] : a[i++];
                while (i < mid) b[k++] = a[i++];
                while (j < hi) b[k++] = a[j++];
                bounds[next++] = lo;
            }
            bounds[next++] = n;
            int[] x = a; a = b; b = x;
            count = next;
        }
        return a;
    }
}
