package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.core.ClouseauEventBus;
import com.droidenx.clouseau.core.LogIndex;
import com.google.common.eventbus.Subscribe;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.Color;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * Table model backed by LogIndex events.
 * Subscribes to the async event bus; all mutations are dispatched to the EDT.
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

    private final List<LogEntry>         allEntries = new ArrayList<>();
    private       List<LogEntry>         rows       = new ArrayList<>();
    private final Map<LogEntry, Color>   highlights = new IdentityHashMap<>();
    private Predicate<LogEntry>          activeFilter = e -> true;
    private int                          maxEntries = AppPrefs.getMaxEntriesPerTab();
    private long                         trimmedCount = 0;

    private final ExecutorService filterExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clouseau-filter");
        t.setDaemon(true);
        return t;
    });
    private Future<?>  filterFuture;
    private long       filterGeneration = 0;

    public LogTableModel() {
        ClouseauEventBus.registerAsync(this);
    }

    public void dispose() {
        ClouseauEventBus.unregisterAsync(this);
        filterExecutor.shutdownNow();
    }

    @Subscribe
    public void onEntryAdded(LogIndex.LogEntryAddedEvent event) {
        LogEntry entry = event.entry();
        SwingUtilities.invokeLater(() -> {
            allEntries.add(entry);
            discoverFields(List.of(entry));
            if (activeFilter.test(entry)) {
                int idx = rows.size();
                rows.add(entry);
                fireTableRowsInserted(idx, idx);
            }
        });
    }

    @Subscribe
    public void onBatchAdded(LogIndex.LogBatchAddedEvent event) {
        List<LogEntry> batch = List.copyOf(event.entries());
        SwingUtilities.invokeLater(() -> {
            allEntries.addAll(batch);
            discoverFields(batch);
            int first = rows.size();
            List<LogEntry> matching = batch.stream().filter(activeFilter).toList();
            if (!matching.isEmpty()) {
                rows.addAll(matching);
                fireTableRowsInserted(first, rows.size() - 1);
            }
        });
    }

    public Color getHighlight(LogEntry entry) {
        return entry != null ? highlights.get(entry) : null;
    }

    public void setHighlight(LogEntry entry, Color color) {
        if (color == null) highlights.remove(entry);
        else highlights.put(entry, color);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) == entry) { fireTableRowsUpdated(i, i); return; }
        }
    }

    public void clearAllHighlights() {
        if (highlights.isEmpty()) return;
        highlights.clear();
        if (!rows.isEmpty()) fireTableRowsUpdated(0, rows.size() - 1);
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
        for (LogEntry e : rows) { Color c = highlights.get(e); if (c != null) set.add(c); }
        return set;
    }

    public void clear() {
        Runnable doClear = () -> {
            allEntries.clear();
            highlights.clear();
            knownFieldKeys.clear();
            customFields = List.of();
            int last = rows.size() - 1;
            rows.clear();
            fireTableStructureChanged();
        };
        if (SwingUtilities.isEventDispatchThread()) doClear.run();
        else SwingUtilities.invokeLater(doClear);
    }

    /** Appends a batch of entries from streaming load. Must be called on the EDT. */
    public void appendBatch(List<LogEntry> entries) {
        allEntries.addAll(entries);
        discoverFields(entries);
        int first = rows.size();
        List<LogEntry> matching = entries.stream().filter(activeFilter).toList();
        if (!matching.isEmpty()) {
            rows.addAll(matching);
            fireTableRowsInserted(first, rows.size() - 1);
        }
        trimToLimit();
    }

    /** Appends a single entry from file tailing. Must be called on the EDT. */
    public void append(LogEntry entry) {
        allEntries.add(entry);
        discoverFields(List.of(entry));
        if (activeFilter.test(entry)) {
            int idx = rows.size();
            rows.add(entry);
            fireTableRowsInserted(idx, idx);
        }
        trimToLimit();
    }

    /**
     * Drops the oldest entries from the front of the list when the entry count
     * exceeds {@code maxEntries}. Must be called on the EDT.
     */
    private void trimToLimit() {
        if (maxEntries <= 0 || allEntries.size() <= maxEntries) return;
        int toRemove = allEntries.size() - maxEntries;

        // Build identity set of the entries being evicted
        Set<LogEntry> evicted = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < toRemove; i++) evicted.add(allEntries.get(i));

        allEntries.subList(0, toRemove).clear();
        trimmedCount += toRemove;

        // Clean up highlight map for evicted entries
        evicted.forEach(highlights::remove);

        // Remove the corresponding leading rows (rows is always ordered oldest-first)
        int rowsToRemove = 0;
        while (rowsToRemove < rows.size() && evicted.contains(rows.get(rowsToRemove))) {
            rowsToRemove++;
        }
        if (rowsToRemove > 0) {
            rows.subList(0, rowsToRemove).clear();
            fireTableRowsDeleted(0, rowsToRemove - 1);
        }
    }

    /** Updates the per-tab entry cap (0 = unlimited). */
    public void setMaxEntries(int max) { this.maxEntries = max; }

    /** Total number of entries dropped from the front of this tab since it was opened. */
    public long getTrimmedCount() { return trimmedCount; }

    /** Replaces all entries and reapplies the active filter. Must be called on the EDT. */
    public void load(List<LogEntry> newEntries) {
        allEntries.clear();
        allEntries.addAll(newEntries);
        reapplyFilter();
    }

    /** Installs a new filter and schedules an off-EDT recompute of visible rows. Must be called on the EDT. */
    public void applyFilter(Predicate<LogEntry> filter) {
        activeFilter = filter;
        scheduleReapply();
    }

    private void scheduleReapply() {
        if (filterFuture != null) filterFuture.cancel(true);
        List<LogEntry> snapshot = List.copyOf(allEntries);
        Predicate<LogEntry> filter = activeFilter;
        long generation = ++filterGeneration;

        filterFuture = filterExecutor.submit(() -> {
            List<LogEntry> newRows = new ArrayList<>(snapshot.size());
            for (LogEntry e : snapshot) {
                if (Thread.currentThread().isInterrupted()) return;
                if (filter.test(e)) newRows.add(e);
            }
            SwingUtilities.invokeLater(() -> {
                if (filterGeneration != generation) return; // superseded by a newer filter
                // catch up any entries appended via streaming after the snapshot was taken
                for (int i = snapshot.size(); i < allEntries.size(); i++) {
                    if (filter.test(allEntries.get(i))) newRows.add(allEntries.get(i));
                }
                rows = newRows;
                fireTableDataChanged();
            });
        });
    }

    private void reapplyFilter() {
        rows.clear();
        allEntries.stream().filter(activeFilter).forEach(rows::add);
        fireTableDataChanged();
    }

    /** Returns the LogEntry at the given model row, or null if out of range. */
    public LogEntry getEntry(int row) {
        if (row < 0 || row >= rows.size()) return null;
        return rows.get(row);
    }

    /**
     * Returns the consecutive continuation lines (null timestamp) that follow the entry
     * at the given model row in the unfiltered entry list. Used to show stack traces in
     * the detail panel even when UNKNOWN entries are filtered out of the table.
     */
    public List<LogEntry> getContinuationLines(int modelRow) {
        if (modelRow < 0 || modelRow >= rows.size()) return List.of();
        LogEntry anchor = rows.get(modelRow);
        // Locate anchor in allEntries by identity (filter may have reordered rows)
        int allIdx = -1;
        for (int i = 0; i < allEntries.size(); i++) {
            if (allEntries.get(i) == anchor) { allIdx = i; break; }
        }
        if (allIdx < 0) return List.of();
        List<LogEntry> result = new ArrayList<>();
        for (int i = allIdx + 1; i < allEntries.size(); i++) {
            LogEntry next = allEntries.get(i);
            boolean isContinuation = next.timestamp() == null
                    || next.level() == LogEntry.LogLevel.UNKNOWN;
            if (!isContinuation) break;
            result.add(next);
        }
        return result;
    }

    public int getTotalCount() { return allEntries.size(); }

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
        LogEntry e = rows.get(row);
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
}
