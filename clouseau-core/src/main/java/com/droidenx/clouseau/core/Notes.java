package com.droidenx.clouseau.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A tab's notes and highlights, by entry ordinal: one file's {@link AnnotationStore}, or
 * a merged view's {@link MergedNotes}, which keeps each file's notes in that file's own
 * store (D-17). Used on the UI thread only.
 */
public interface Notes {

    AnnotationStore.Annotation at(int ordinal);

    boolean has(int ordinal);

    /** Attached annotations in ordinal order. */
    List<AnnotationStore.Placed> placed();

    /** Annotations that match no entry as the files are now. */
    List<AnnotationStore.Annotation> orphans();

    /** Number of notes, attached or not. */
    int size();

    /** Sets (or replaces) the note on the entry at {@code ordinal}, then saves; false if saving failed. */
    boolean put(int ordinal, AnnotationStore.Annotation annotation);

    /** Removes the note on the entry at {@code ordinal}, then saves; false if saving failed. */
    boolean remove(int ordinal);

    /** Removes an orphan note, then saves; false if saving failed. */
    boolean removeOrphan(AnnotationStore.Annotation annotation);

    /** Attached highlights: ordinal → palette slot, in ordinal order. */
    Map<Integer, Integer> highlights();

    /** Sets (null slot: clears) the highlight of several entries, given their raw lines, then saves. */
    boolean setHighlights(Map<Integer, String> rawLines, Integer slot);

    /** Removes every highlight, including orphans, then saves. */
    boolean clearHighlights();

    /** Where the notes are saved, or null in memory; for a merged view, the first file's. */
    Path getSavePath();

    /** The file an entry's note belongs to, for a merged view; null for one file. */
    default String sourceName(int ordinal) { return null; }

    /** The file an orphan note belongs to, for a merged view; null for one file. */
    default String orphanSource(AnnotationStore.Annotation annotation) { return null; }
}
