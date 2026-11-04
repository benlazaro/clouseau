package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.core.IndexedEntries;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.Color;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * The entry store for one tab: every entry ({@code allEntries}), the rows that pass the
 * active filter, row highlights, and the live-session entry cap. Loaders and tailers hand
 * entries in on the EDT; filtering runs on a background executor.
 * <p>
 * Every entry has an <em>ordinal</em>: its 0-based position in the source. It never
 * changes while the tab is open, even as old live entries are dropped, so annotations
 * and highlights are keyed by it.
 * <p>
 * A static file can be <em>indexed</em> (D-16): its entries then live in an
 * {@link IndexedEntries}, read back from disk on demand, and only entries that arrive
 * afterwards (following) are held in the list, after them. An indexed tab is never trimmed.
 */
public final class LogTableModel extends AbstractTableModel {

    private static final int FIXED_COLUMNS = 6;
    private static final String[] COLUMN_KEYS = {
        "table.col.index", "table.col.timestamp", "table.col.level",
        "table.col.thread", "table.col.logger",   "table.col.message"
    };

    private final LinkedHashSet<String> knownFieldKeys = new LinkedHashSet<>();
    private List<String> customFields = List.of();
    /** True while a bulk file load is in progress; defers structure changes to {@link #endBatchLoad()}. */
    private boolean batchLoad = false;
    private boolean structureChangePending = false;
    private DateTimeFormatter tsFmt =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(AppPrefs.getDisplayTimezone());

    /** Rebuilds the timestamp formatter from the current preference and repaints the table. */
    public void refreshTimezone() {
        tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(AppPrefs.getDisplayTimezone());
        fireTableDataChanged();
    }

    /** The indexed static file's entries (ordinals 0 to indexedCount - 1), or null. */
    private IndexedEntries               indexed;
    /** Rows of {@link #indexed} handed to this model so far. */
    private int                          indexedCount = 0;
    /** Entries held in memory, oldest first; the entry at index i has ordinal listBase() + i. */
    private final List<LogEntry>         allEntries = new ArrayList<>();
    /** Ordinals of the entries that pass the active filter, ascending. */
    private       IntList                rows       = new IntList();
    private final Map<Integer, Color>    highlights = new HashMap<>();
    private Predicate<LogEntry>          activeFilter = e -> true;
    private int                          maxEntries = AppPrefs.getMaxEntriesPerTab();
    private int                          trimmedCount = 0;
    /** Entries added by static loads since the last {@link #clear()}; see {@link #entryCap()}. */
    private int                          staticCount  = 0;

    private final ExecutorService filterExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clouseau-filter");
        t.setDaemon(true);
        return t;
    });
    private Future<?>  filterFuture;
    private long       filterGeneration = 0;

    /** Stops the background filter thread. Call when the tab closes. */
    public void dispose() {
        filterExecutor.shutdownNow();
        closeIndexed();
    }

    private void closeIndexed() {
        if (indexed != null) indexed.close();
        indexed = null;
        indexedCount = 0;
    }

    /** The ordinal of {@code allEntries.get(0)}. */
    private int listBase() {
        return indexed != null ? indexedCount : trimmedCount;
    }

    /** True if this tab shows an indexed static file. */
    public boolean isIndexed() { return indexed != null; }

    // ── Ordinals ─────────────────────────────────────────────────────────────

    /** The ordinal of the entry at a model row, or -1 if out of range. */
    public int ordinalAt(int modelRow) {
        return modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : -1;
    }

    /** The model row showing the entry with this ordinal, or -1 if it's filtered out or gone. */
    public int rowOfOrdinal(int ordinal) {
        return rows.binarySearch(ordinal);
    }

    /** The entry with this ordinal, or null if it was dropped or doesn't exist yet. */
    public LogEntry entryAtOrdinal(int ordinal) {
        if (indexed != null && ordinal >= 0 && ordinal < indexedCount) return indexed.get(ordinal);
        int i = ordinal - listBase();
        return i >= 0 && i < allEntries.size() ? allEntries.get(i) : null;
    }

    /** The ordinal the next appended entry gets. */
    public int nextOrdinal() {
        return listBase() + allEntries.size();
    }

    // ── Highlights ───────────────────────────────────────────────────────────

    public Color getHighlight(int modelRow) {
        int ord = ordinalAt(modelRow);
        return ord >= 0 ? highlights.get(ord) : null;
    }

    public void setHighlight(int modelRow, Color color) {
        int ord = ordinalAt(modelRow);
        if (ord < 0) return;
        if (color == null) highlights.remove(ord);
        else highlights.put(ord, color);
        fireTableRowsUpdated(modelRow, modelRow);
    }

    /** Highlights the entry with this ordinal, if it is still held (used to restore saved highlights). */
    public void setHighlightAtOrdinal(int ordinal, Color color) {
        if (entryAtOrdinal(ordinal) == null) return;
        highlights.put(ordinal, color);
        int row = rowOfOrdinal(ordinal);
        if (row >= 0) fireTableRowsUpdated(row, row);
    }

    public void clearAllHighlights() {
        if (highlights.isEmpty()) return;
        highlights.clear();
        if (rows.size() > 0) fireTableRowsUpdated(0, rows.size() - 1);
    }

    public boolean hasHighlights() {
        return !highlights.isEmpty();
    }

    /** Returns model row indices of visible entries highlighted with the given color (null = any color). */
    public List<Integer> getHighlightedModelRows(Color filter) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Color c = highlights.get(rows.get(i));
            if (c != null && (filter == null || filter.equals(c))) result.add(i);
        }
        return result;
    }

    /** Returns the set of colors currently in use, in row order. */
    public Set<Color> getActiveHighlightColors() {
        Set<Color> set = new LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Color c = highlights.get(rows.get(i));
            if (c != null) set.add(c);
        }
        return set;
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    public void clear() {
        Runnable doClear = () -> {
            // A filter run still going is for the old entries: drop its result.
            if (filterFuture != null) filterFuture.cancel(true);
            filterGeneration++;
            allEntries.clear();
            closeIndexed();
            trimmedCount = 0;
            staticCount  = 0;
            highlights.clear();
            knownFieldKeys.clear();
            customFields = List.of();
            rows = new IntList();
            fireTableStructureChanged();
        };
        if (SwingUtilities.isEventDispatchThread()) doClear.run();
        else SwingUtilities.invokeLater(doClear);
    }

    /**
     * Makes this tab show an indexed static file. Call after {@link #clear()}, before the
     * first {@link #appendBatch}; the model closes {@code entries} when it is cleared or
     * disposed. Must be called on the EDT.
     */
    public void beginIndexedLoad(IndexedEntries entries) {
        if (!allEntries.isEmpty() || indexed != null) throw new IllegalStateException("clear() first");
        indexed = entries;
        indexedCount = 0;
    }

    /**
     * Appends a batch read from a static file. Never trims: opening a file must not drop
     * lines (D-7). In an indexed load, {@code entries} are the ones just added to the
     * index, in order; the model keeps only their count and reads them back when needed.
     * Must be called on the EDT.
     */
    public void appendBatch(List<LogEntry> entries) {
        if (indexed != null) {
            int ordinal = indexedCount;
            indexedCount += entries.size();
            discoverFields(entries);
            int first = rows.size();
            for (LogEntry e : entries) {
                if (activeFilter.test(e)) rows.add(ordinal);
                ordinal++;
            }
            if (rows.size() > first) fireTableRowsInserted(first, rows.size() - 1);
        } else {
            addEntries(entries);
        }
        staticCount += entries.size();
    }

    /**
     * Appends entries from a live session (file follow or SSH), then drops the oldest
     * entries if the tab is over its cap. Must be called on the EDT.
     */
    public void appendLive(List<LogEntry> entries) {
        addEntries(entries);
        trimToLimit();
    }

    private void addEntries(List<LogEntry> entries) {
        int ordinal = nextOrdinal();
        allEntries.addAll(entries);
        discoverFields(entries);
        int first = rows.size();
        for (LogEntry e : entries) {
            if (activeFilter.test(e)) rows.add(ordinal);
            ordinal++;
        }
        if (rows.size() > first) fireTableRowsInserted(first, rows.size() - 1);
    }

    /**
     * The most entries this tab keeps once live entries arrive: the configured limit, or
     * the size of the static load if that was larger, so following a big file never cuts
     * what was already loaded down to the limit at once. 0 = unlimited.
     */
    int entryCap() {
        return maxEntries <= 0 ? 0 : Math.max(maxEntries, staticCount);
    }

    /**
     * Drops the oldest entries from the front of the list when the entry count
     * exceeds {@link #entryCap()}. Must be called on the EDT.
     */
    private void trimToLimit() {
        if (indexed != null) return;   // the index costs too little per entry to need it (D-16)
        int cap = entryCap();
        if (cap <= 0 || allEntries.size() <= cap) return;
        int toRemove = allEntries.size() - cap;

        allEntries.subList(0, toRemove).clear();
        trimmedCount += toRemove;
        int firstKept = trimmedCount;
        highlights.keySet().removeIf(ord -> ord < firstKept);

        // rows is ascending, so the dropped entries' rows are a prefix
        int rowsToRemove = rows.countBelow(firstKept);
        if (rowsToRemove > 0) {
            rows.removeFirst(rowsToRemove);
            fireTableRowsDeleted(0, rowsToRemove - 1);
        }
    }

    /** Updates the per-tab entry cap (0 = unlimited). */
    public void setMaxEntries(int max) { this.maxEntries = max; }

    /** Total number of entries dropped from the front of this tab since it was opened. */
    public long getTrimmedCount() { return trimmedCount; }

    /** Installs a new filter and schedules an off-EDT recompute of visible rows. Must be called on the EDT. */
    public void applyFilter(Predicate<LogEntry> filter) {
        activeFilter = filter;
        scheduleReapply();
    }

    private void scheduleReapply() {
        if (filterFuture != null) filterFuture.cancel(true);
        List<LogEntry> snapshot = List.copyOf(allEntries);
        int base = listBase();
        IndexedEntries indexedSnapshot = indexed;
        int indexedRows = indexedCount;
        Predicate<LogEntry> filter = activeFilter;
        long generation = ++filterGeneration;

        filterFuture = filterExecutor.submit(() -> {
            IntList newRows = new IntList();
            if (indexedSnapshot != null) {
                // Reads the file in order; rows added after indexedRows are caught up below.
                indexedSnapshot.scan(0, indexedRows, (row, e) -> {
                    if (filter.test(e)) newRows.add(row);
                }, () -> Thread.currentThread().isInterrupted());
                if (Thread.currentThread().isInterrupted()) return;
            }
            for (int i = 0; i < snapshot.size(); i++) {
                if (Thread.currentThread().isInterrupted()) return;
                if (filter.test(snapshot.get(i))) newRows.add(base + i);
            }
            SwingUtilities.invokeLater(() -> {
                if (filterGeneration != generation) return; // superseded by a newer filter
                // drop entries trimmed meanwhile, and catch up any appended after the snapshot
                newRows.removeFirst(newRows.countBelow(trimmedCount));
                int end = nextOrdinal();
                int from = base + snapshot.size();
                for (int ord = Math.max(from, trimmedCount); ord < end; ord++) {
                    LogEntry e = entryAtOrdinal(ord);
                    if (e != null && filter.test(e)) newRows.add(ord);
                }
                rows = newRows;
                fireTableDataChanged();
            });
        });
    }

    /** Returns the LogEntry at the given model row, or null if out of range. */
    public LogEntry getEntry(int row) {
        int ord = ordinalAt(row);
        return ord >= 0 ? entryAtOrdinal(ord) : null;
    }

    /**
     * Returns the consecutive continuation lines (null timestamp) that follow the entry
     * at the given model row in the unfiltered entry list. Used to show stack traces in
     * the detail panel even when UNKNOWN entries are filtered out of the table.
     */
    public List<LogEntry> getContinuationLines(int modelRow) {
        int ord = ordinalAt(modelRow);
        if (ord < 0) return List.of();
        List<LogEntry> result = new ArrayList<>();
        int end = nextOrdinal();
        for (int o = ord + 1; o < end; o++) {
            LogEntry next = entryAtOrdinal(o);
            if (next == null) break;
            boolean isContinuation = next.timestamp() == null
                    || next.level() == LogEntry.LogLevel.UNKNOWN;
            if (!isContinuation) break;
            result.add(next);
        }
        return result;
    }

    public int getTotalCount() { return (indexed != null ? indexedCount : 0) + allEntries.size(); }

    /** Call before streaming a file batch-load; defers field-discovery structure changes to {@link #endBatchLoad()}. */
    public void beginBatchLoad() {
        batchLoad = true;
        structureChangePending = false;
    }

    /**
     * Call after the last batch has been appended. Fires a single {@code fireTableStructureChanged()}
     * if any new fields were discovered during loading, then resets batch-load mode.
     */
    public void endBatchLoad() {
        batchLoad = false;
        if (structureChangePending) {
            structureChangePending = false;
            fireTableStructureChanged();
        }
    }

    /** Sets the initial custom field columns (from parser declaration). Must be called on the EDT. */
    public void setCustomFields(List<String> fields) {
        knownFieldKeys.clear();
        knownFieldKeys.addAll(fields);
        List<String> newFields = List.copyOf(knownFieldKeys);
        if (newFields.equals(customFields)) return;
        customFields = newFields;
        fireTableStructureChanged();
    }

    /**
     * Scans entries for field keys not yet known and merges them in.
     * Returns true if new fields were discovered (caller should fire structure changed).
     * Must be called on the EDT.
     */
    private void discoverFields(List<LogEntry> entries) {
        boolean changed = false;
        for (LogEntry e : entries) {
            Map<String, String> fields = e.fields();
            if (fields == null || fields.isEmpty()) continue;
            for (String key : fields.keySet()) {
                if (knownFieldKeys.add(key)) changed = true;
            }
        }
        if (!changed) return;
        customFields = List.copyOf(knownFieldKeys);
        if (batchLoad) {
            structureChangePending = true;
        } else {
            fireTableStructureChanged();
        }
    }

    @Override public int getRowCount()    { return rows.size(); }
    @Override public int getColumnCount() { return FIXED_COLUMNS + customFields.size(); }

    @Override public String getColumnName(int col) {
        if (col < FIXED_COLUMNS) return Messages.get(COLUMN_KEYS[col]);
        return customFields.get(col - FIXED_COLUMNS);
    }

    @Override
    public Class<?> getColumnClass(int col) {
        return col == 0 ? Integer.class : String.class;
    }

    @Override
    public Object getValueAt(int row, int col) {
        LogEntry e = getEntry(row);
        if (e == null) return "";
        if (col < FIXED_COLUMNS) return switch (col) {
            case 0 -> row + 1;
            case 1 -> e.timestamp() != null ? tsFmt.format(e.timestamp()) : "";
            case 2 -> e.level()     != null ? e.level().name()             : "";
            case 3 -> e.thread()    != null ? e.thread()                   : "";
            case 4 -> e.logger()    != null ? e.logger()                   : "";
            case 5 -> e.message()   != null ? e.message()                  : "";
            default -> "";
        };
        Map<String, String> fields = e.fields();
        return fields != null ? fields.getOrDefault(customFields.get(col - FIXED_COLUMNS), "") : "";
    }

    /** A growable list of ascending ints: 4 bytes a row, and a cheap drop-from-front. */
    static final class IntList {
        private int[] data = new int[64];
        private int start = 0;
        private int size = 0;

        int size() { return size; }

        int get(int i) { return data[start + i]; }

        void add(int value) {
            if (start + size == data.length) {
                int capacity = size * 2 > data.length ? size * 2 : data.length;
                int[] grown = new int[Math.max(64, capacity)];
                System.arraycopy(data, start, grown, 0, size);
                data = grown;
                start = 0;
            }
            data[start + size++] = value;
        }

        void removeFirst(int n) {
            start += n;
            size  -= n;
        }

        /** How many leading values are below {@code value}. */
        int countBelow(int value) {
            int i = Arrays.binarySearch(data, start, start + size, value);
            return (i >= 0 ? i : -i - 1) - start;
        }

        /** Index of {@code value}, or -1. */
        int binarySearch(int value) {
            int i = Arrays.binarySearch(data, start, start + size, value);
            return i >= 0 ? i - start : -1;
        }
    }
}
