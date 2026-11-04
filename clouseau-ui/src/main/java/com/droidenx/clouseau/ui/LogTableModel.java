package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.core.EntryFilter;
import com.droidenx.clouseau.core.EntryIndex;
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
import java.util.function.Consumer;
import java.util.function.IntConsumer;
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
 * {@link IndexedEntries}, read back from disk on demand. Following such a file adds to
 * the index too ({@link #appendFollowed}), so an indexed tab is never trimmed.
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
    private EntryFilter                  activeFilter = EntryFilter.ALL;
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
    private IntConsumer filterProgress = p -> {};
    /** Rows indexed during a text-filter run that are read on the EDT at the end, not in another run. */
    private static final int EDT_CATCH_UP_ROWS = 2_000;

    private final ExecutorService findExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clouseau-find");
        t.setDaemon(true);
        return t;
    });
    private Future<?>  findFuture;
    private long       findGeneration = 0;

    /** Stops the background filter and find threads. Call when the tab closes. */
    public void dispose() {
        filterExecutor.shutdownNow();
        findExecutor.shutdownNow();
        closeIndexed();
    }

    private void closeIndexed() {
        if (indexed != null) indexed.close();
        indexed = null;
        indexedCount = 0;
    }

    /** The ordinal of {@code allEntries.get(0)}. */
    private int listBase() {
        return indexed != null ? indexedCount + trimmedCount : trimmedCount;
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
            filterProgress.accept(-1);
            cancelFind();
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
        if (indexed != null) addIndexedRows(entries);
        else addEntries(entries);
        staticCount += entries.size();
    }

    /**
     * Appends entries read by following the tab's file. On an indexed tab they are the
     * rows just added to the index, in order, and are never trimmed (D-16); otherwise they
     * are live entries ({@link #appendLive}). Must be called on the EDT.
     */
    public void appendFollowed(List<LogEntry> entries) {
        if (indexed != null && allEntries.isEmpty()) addIndexedRows(entries);
        else appendLive(entries);
    }

    /** The indexed file's entries, or null if this tab isn't indexed. Must be called on the EDT. */
    public IndexedEntries indexedEntries() { return indexed; }

    /** Counts in rows just added to the index, and shows those that pass the filter. */
    private void addIndexedRows(List<LogEntry> entries) {
        int ordinal = indexedCount;
        indexedCount += entries.size();
        discoverFields(entries);
        int first = rows.size();
        for (LogEntry e : entries) {
            if (activeFilter.test(e)) rows.add(ordinal);
            ordinal++;
        }
        if (rows.size() > first) fireTableRowsInserted(first, rows.size() - 1);
    }

    /**
     * Appends entries from a live session (SSH, a plugin source, or following a file held
     * in memory), then drops the oldest entries if the tab is over its cap. Must be called
     * on the EDT.
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
     * <p>
     * On an indexed tab the indexed rows are never dropped: following adds to the index
     * ({@link #appendFollowed}). Only live entries appended after the index, which the
     * app doesn't do, would be capped at the configured limit.
     */
    private void trimToLimit() {
        int cap = indexed != null ? Math.max(maxEntries, 0) : entryCap();
        if (cap <= 0 || allEntries.size() <= cap) return;
        int toRemove = allEntries.size() - cap;

        allEntries.subList(0, toRemove).clear();
        trimmedCount += toRemove;
        int gapStart = droppedFrom(), firstKept = listBase();
        highlights.keySet().removeIf(ord -> ord >= gapStart && ord < firstKept);

        // rows is ascending, so the dropped entries' rows are one run
        int from = rows.countBelow(gapStart), to = rows.countBelow(firstKept);
        if (to > from) {
            rows.removeRange(from, to);
            fireTableRowsDeleted(from, to - 1);
        }
    }

    /** The first ordinal that may have been dropped: dropped entries are droppedFrom() to listBase() - 1. */
    private int droppedFrom() {
        return indexed != null ? indexedCount : 0;
    }

    /** Updates the per-tab entry cap (0 = unlimited). */
    public void setMaxEntries(int max) { this.maxEntries = max; }

    /** Total number of entries dropped since the tab was opened (on an indexed tab, followed ones). */
    public long getTrimmedCount() { return trimmedCount; }

    /**
     * Receives filter progress: a percentage while a filter run reads text from the file,
     * and -1 when the run has finished or was superseded. Called on the EDT.
     */
    public void setFilterProgressListener(IntConsumer listener) {
        this.filterProgress = listener != null ? listener : p -> {};
    }

    /** Installs a new filter and schedules an off-EDT recompute of visible rows. Must be called on the EDT. */
    public void applyFilter(EntryFilter filter) {
        activeFilter = filter;
        scheduleReapply();
    }

    private void scheduleReapply() {
        if (filterFuture != null) filterFuture.cancel(true);
        long generation = ++filterGeneration;
        submitFilterRun(generation, activeFilter, new IntList(), 0);
    }

    /**
     * Filters indexed rows {@code from} up to the current indexed count into {@code acc},
     * then the in-memory list, in the background, and installs the result on the EDT. If
     * many rows that need text were indexed meanwhile (a load still running), it goes
     * round again for those rather than reading them on the EDT.
     */
    private void submitFilterRun(long generation, EntryFilter filter, IntList acc, int from) {
        IndexedEntries indexedSnapshot = indexed;
        int to = indexedCount;
        List<LogEntry> snapshot = List.copyOf(allEntries);
        int base = listBase();

        filterFuture = filterExecutor.submit(() -> {
            if (indexedSnapshot != null
                    && !filterIndexed(indexedSnapshot, from, to, filter, acc, generation)) return;
            IntList listRows = new IntList();
            for (int i = 0; i < snapshot.size(); i++) {
                if (Thread.currentThread().isInterrupted()) return;
                if (filter.test(snapshot.get(i))) listRows.add(base + i);
            }
            SwingUtilities.invokeLater(() -> {
                if (filterGeneration != generation) return; // superseded by a newer filter
                if (indexed != null && indexedCount - to > EDT_CATCH_UP_ROWS
                        && filter.needsText(indexed.index())) {
                    submitFilterRun(generation, filter, acc, to);
                    return;
                }
                filterProgress.accept(-1);
                IntList newRows = acc;
                // Rows indexed after the snapshot (loading or following) come before the list.
                if (indexed != null) {
                    for (int ord = to; ord < indexedCount; ord++) {
                        if (passesOnEdt(filter, ord)) newRows.add(ord);
                    }
                }
                for (int i = 0; i < listRows.size(); i++) newRows.add(listRows.get(i));
                // drop entries trimmed meanwhile, and catch up any appended after the snapshot
                newRows.removeRange(newRows.countBelow(droppedFrom()), newRows.countBelow(listBase()));
                int end = nextOrdinal();
                for (int ord = Math.max(base + snapshot.size(), listBase()); ord < end; ord++) {
                    if (passesOnEdt(filter, ord)) newRows.add(ord);
                }
                rows = newRows;
                fireTableDataChanged();
            });
        });
    }

    /** Filters indexed rows [from, to) into {@code acc}; false if interrupted. Runs on the filter executor. */
    private boolean filterIndexed(IndexedEntries entries, int from, int to, EntryFilter filter,
                                  IntList acc, long generation) {
        EntryIndex index = entries.index();
        if (!filter.needsText(index)) {
            for (int row = from; row < to; row++) {
                if ((row & 0xFFFF) == 0 && Thread.currentThread().isInterrupted()) return false;
                if (filter.testIndex(index, row)) acc.add(row);
            }
            return true;
        }
        // Only rows the columns pass are read, in file order, in large sequential reads.
        Progress progress = new Progress(from, to, p -> postProgress(generation, p));
        entries.scan(from, to, row -> {
            progress.at(row);
            return filter.testIndex(index, row);
        }, (row, e) -> {
            if (filter.test(e)) acc.add(row);
        }, () -> Thread.currentThread().isInterrupted());
        return !Thread.currentThread().isInterrupted();
    }

    /** Tests one entry on the EDT, reading an indexed row's text only if the filter needs it. */
    private boolean passesOnEdt(EntryFilter filter, int ordinal) {
        if (indexed != null && ordinal < indexedCount) {
            EntryIndex index = indexed.index();
            if (!filter.testIndex(index, ordinal)) return false;
            if (!filter.needsText(index)) return true;
        }
        LogEntry e = entryAtOrdinal(ordinal);
        return e != null && filter.test(e);
    }

    private void postProgress(long generation, int percent) {
        SwingUtilities.invokeLater(() -> {
            if (filterGeneration == generation) filterProgress.accept(percent);
        });
    }

    // ── Find ─────────────────────────────────────────────────────────────────

    /**
     * Finds the visible entries that match, in the background. An entry also matches if
     * one of its continuation lines does, visible or not, since the detail panel shows
     * them together. On the EDT, {@code progress} gets a percentage while the file is read
     * and -1 at the end, and {@code done} gets the matching ordinals, ascending; neither
     * is called if another find or {@link #clear()} comes first. Must be called on the EDT.
     */
    public void find(Predicate<LogEntry> matches, IntConsumer progress, Consumer<int[]> done) {
        cancelFind();
        long generation = findGeneration;
        IntList visible = rows.copy();
        IndexedEntries indexedSnapshot = indexed;
        int indexedRows = indexedCount;
        List<LogEntry> snapshot = List.copyOf(allEntries);
        int base = listBase();

        findFuture = findExecutor.submit(() -> {
            FindScan scan = new FindScan(visible, matches);
            int first = visible.size() > 0 ? visible.get(0) : Integer.MAX_VALUE;
            if (indexedSnapshot != null && first < indexedRows) {
                EntryIndex index = indexedSnapshot.index();
                Progress p = new Progress(first, indexedRows, pct -> SwingUtilities.invokeLater(() -> {
                    if (findGeneration == generation) progress.accept(pct);
                }));
                indexedSnapshot.scan(first, indexedRows, row -> {
                    p.at(row);
                    return scan.wanted(row, index.isContinuation(row));
                }, scan::accept, () -> Thread.currentThread().isInterrupted());
                if (Thread.currentThread().isInterrupted()) return;
            }
            for (int i = Math.max(0, first - base); i < snapshot.size(); i++) {
                if (Thread.currentThread().isInterrupted()) return;
                LogEntry e = snapshot.get(i);
                if (scan.wanted(base + i, isContinuation(e))) scan.accept(base + i, e);
            }
            int[] found = scan.result();
            SwingUtilities.invokeLater(() -> {
                if (findGeneration != generation) return;
                progress.accept(-1);
                done.accept(found);
            });
        });
    }

    /** Stops a running {@link #find}; its callbacks won't be called. Must be called on the EDT. */
    public void cancelFind() {
        if (findFuture != null) findFuture.cancel(true);
        findGeneration++;
    }

    private static boolean isContinuation(LogEntry e) {
        return e.timestamp() == null || e.level() == LogEntry.LogLevel.UNKNOWN;
    }

    /**
     * One pass of {@link #find} over entries in ordinal order. Keeps the visible entries
     * whose continuation lines are still running ("open"), so a later continuation line
     * that matches marks them; any entry that isn't a continuation closes them.
     */
    private static final class FindScan {
        private final IntList visible;
        private final Predicate<LogEntry> matches;
        private final IntList open = new IntList();
        private final IntList found = new IntList();
        private int cursor = 0;
        private boolean currentVisible;

        FindScan(IntList visible, Predicate<LogEntry> matches) {
            this.visible = visible;
            this.matches = matches;
        }

        /** Called for every ordinal in order; true if the entry must be read and given to {@link #accept}. */
        boolean wanted(int ordinal, boolean continuation) {
            if (!continuation) open.clear();
            while (cursor < visible.size() && visible.get(cursor) < ordinal) cursor++;
            currentVisible = cursor < visible.size() && visible.get(cursor) == ordinal;
            return currentVisible || open.size() > 0;
        }

        void accept(int ordinal, LogEntry e) {
            if (matches.test(e)) {
                for (int i = 0; i < open.size(); i++) found.add(open.get(i));
                open.clear();
                if (currentVisible) found.add(ordinal);
            } else if (currentVisible) {
                open.add(ordinal);
            }
        }

        int[] result() {
            int[] r = found.toArray();
            Arrays.sort(r);
            return r;
        }
    }

    /** Turns rows done into a percentage, reporting only when it changes. */
    private static final class Progress {
        private final int from, total;
        private final IntConsumer out;
        private int last = -1;

        Progress(int from, int to, IntConsumer out) {
            this.from = from;
            this.total = to - from;
            this.out = out;
        }

        void at(int row) {
            if (total <= 0) return;
            int p = (int) ((row - from) * 100L / total);
            if (p != last) {
                last = p;
                out.accept(p);
            }
        }
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

        void clear() {
            start = 0;
            size = 0;
        }

        IntList copy() {
            IntList c = new IntList();
            c.data = Arrays.copyOfRange(data, start, start + Math.max(size, 64));
            c.size = size;
            return c;
        }

        int[] toArray() { return Arrays.copyOfRange(data, start, start + size); }

        void removeFirst(int n) {
            start += n;
            size  -= n;
        }

        /** Removes the values at positions {@code from} (inclusive) to {@code to} (exclusive). */
        void removeRange(int from, int to) {
            if (to <= from) return;
            if (from == 0) {
                removeFirst(to);
                return;
            }
            System.arraycopy(data, start + to, data, start + from, size - to);
            size -= to - from;
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
