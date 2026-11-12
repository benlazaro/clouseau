package com.droidenx.clouseau.core;

import java.util.Arrays;

/**
 * Where a reader skipped blank lines. Blank lines aren't entries, but an editor counts
 * them, so turning an entry's position into its line number in the file needs them. Rare
 * in logs, so a small array, grown as needed. Written by the reading thread, read on the
 * EDT; every method is synchronized.
 */
public final class BlankLines {

    /** For each blank line, how many entries came before it; ascending. */
    private int[] before = new int[0];
    private int count;

    /** A blank line was skipped after {@code entriesBefore} entries. */
    public synchronized void add(int entriesBefore) {
        if (count == before.length) before = Arrays.copyOf(before, Math.max(8, count * 2));
        before[count++] = entriesBefore;
    }

    /** How many blank lines were skipped. */
    public synchronized int count() { return count; }

    /** The line number in the file of the entry at {@code position} (0-based), counted from 1. */
    public synchronized int lineNumber(int position) {
        int lo = 0, hi = count;               // blank lines before it: those with <= position entries before them
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (before[mid] <= position) lo = mid + 1;
            else hi = mid;
        }
        return position + 1 + lo;
    }
}
