package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;

import java.util.function.Predicate;

/**
 * A filter on log entries that can also be tested against an {@link EntryIndex} row
 * without reading the entry's text (D-16).
 * <p>
 * {@link #testIndex} looks at the index columns only. It must return true for every row
 * whose entry passes {@link #test}. If {@link #needsText} is false, it must also be
 * exact: a row passes the filter if and only if {@code testIndex} is true. Otherwise the
 * rows {@code testIndex} passes are read back and given to {@link #test}.
 * <p>
 * The defaults suit a plain lambda: every row is read and tested.
 */
@FunctionalInterface
public interface EntryFilter extends Predicate<LogEntry> {

    /** The filter that passes everything. */
    EntryFilter ALL = new EntryFilter() {
        @Override public boolean test(LogEntry e) { return true; }
        @Override public boolean needsText(EntryIndex index) { return false; }
    };

    /** False if the row can't pass, from the index columns alone. Must be safe on any thread. */
    default boolean testIndex(EntryIndex index, int row) { return true; }

    /** True if rows that pass {@link #testIndex} must still be read and given to {@link #test}. */
    default boolean needsText(EntryIndex index) { return true; }
}
