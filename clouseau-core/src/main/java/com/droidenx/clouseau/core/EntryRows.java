package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;

import java.io.Closeable;
import java.util.function.BooleanSupplier;

/**
 * Entries a tab reads back from disk, by ordinal (D-17): one indexed file
 * ({@link IndexedEntries}), or several merged into one timeline. The table model reads
 * every indexed tab through this interface.
 * <p>
 * Threads are as for {@link IndexedEntries}: {@link #get} on the EDT; the index methods and
 * {@link #scan} on any thread, for ordinals below a count handed over by the loader.
 */
public interface EntryRows extends Closeable {

    /** The entry at {@code ordinal}, read back from disk. EDT only. */
    LogEntry get(int ordinal);

    /** True if the entry at {@code ordinal} continues the one before it in its file. */
    boolean isContinuation(int ordinal);

    /** How many files these entries come from. */
    default int sourceCount() { return 1; }

    /** The source (its index among {@link #sourceCount()}) of the entry at {@code ordinal}. */
    default int sourceOf(int ordinal) { return 0; }

    /** The entry's line number in its own file, from 1, blank lines included. */
    default int lineNumber(int ordinal) { return ordinal + 1; }

    /** How many lines the entries came from, blank lines included (all files of a merge). */
    long lineCount();

    /** A short name for a source, such as its file name; null for a single file. */
    default String sourceName(int source) { return null; }

    /** What the Source column shows for a source: its name, and any mark such as a time offset. */
    default String sourceLabel(int source) { return sourceName(source); }

    /** The index part of {@code filter} for one ordinal (D-16): no I/O. */
    boolean testIndex(EntryFilter filter, int ordinal);

    /** True if {@code filter} has to read text for some rows (see {@link EntryFilter#needsText}). */
    boolean needsText(EntryFilter filter);

    /**
     * Visits ordinals {@code from} (inclusive) to {@code to} (exclusive), reading only those
     * {@link Visitor#wanted} asks for, in large sequential reads. Ordinals come in
     * <em>runs</em>, each ascending and in file order: one run for one file, one per source
     * for a merged view (so the reads stay sequential). Every ordinal in the range is
     * offered to {@code wanted} exactly once. Stops early if {@code cancelled} turns true,
     * the thread is interrupted, or the text can't be read.
     *
     * @return true if every ordinal was visited
     */
    boolean scan(int from, int to, Visitor visitor, BooleanSupplier cancelled);

    @Override
    void close();

    /** Receives a {@link #scan}. */
    interface Visitor {
        /** A new run starts: the ordinals after this ascend again from somewhere in the range. */
        default void startRun() {}

        /** Asked about every ordinal in order, just before it would be read. */
        boolean wanted(int ordinal, boolean continuation);

        /** An ordinal {@link #wanted} accepted, with its entry. */
        void accept(int ordinal, LogEntry entry);
    }
}
