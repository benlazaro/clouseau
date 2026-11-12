package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A merged view's notes, kept per file (D-17 step 4). */
class MergedNotesTest {

    private static final long NONE = EntryIndex.NO_TIMESTAMP;

    @TempDir Path dir;

    private record Rows(long... ts) implements MergeOrder.Timeline {
        @Override public long timestamp(int row) { return ts[row]; }
        @Override public boolean isContinuation(int row) { return ts[row] == NONE; }
        MergeOrder.Input in() { return new MergeOrder.Input(this, ts.length, 0); }
    }

    private static AnnotationStore.Annotation note(String text) {
        return new AnnotationStore.Annotation("0123456789abcdef", null, null, "preview", text, "me", "now");
    }

    @Test
    void notesGoToTheirOwnFileUnderItsOwnEntryNumbers() throws Exception {
        Path app = Files.writeString(dir.resolve("app.log"), "x\n");
        Path db = Files.writeString(dir.resolve("db.log"), "y\n");
        AnnotationStore appStore = AnnotationStore.load(app, null);
        AnnotationStore dbStore = AnnotationStore.load(db, null);
        // app: 10, 30   db: 20, 40  →  merged: app0 db0 app1 db1
        MergeOrder order = MergeOrder.build(List.of(new Rows(10, 30).in(), new Rows(20, 40).in()));
        MergedNotes notes = new MergedNotes(List.of(appStore, dbStore), List.of("app.log", "db.log"), order);

        assertThat(notes.put(3, note("db second line"))).isTrue();
        assertThat(dbStore.at(1).note()).isEqualTo("db second line");
        assertThat(dbStore.at(1).line()).isEqualTo(2);           // db.log's own entry number
        assertThat(appStore.size()).isZero();
        assertThat(Files.readString(dir.resolve("db.log.clouseau"))).contains("db second line");
        assertThat(Files.exists(dir.resolve("app.log.clouseau"))).isFalse();

        assertThat(notes.at(3).note()).isEqualTo("db second line");
        assertThat(notes.has(2)).isFalse();
        assertThat(notes.sourceName(3)).isEqualTo("db.log");
        assertThat(notes.placed()).extracting(AnnotationStore.Placed::ordinal).containsExactly(3);

        notes.setHighlights(Map.of(0, "x", 1, "y"), 2);
        assertThat(appStore.highlights()).containsEntry(0, 2);
        assertThat(dbStore.highlights()).containsEntry(0, 2);
        assertThat(notes.highlights()).containsExactly(Map.entry(0, 2), Map.entry(1, 2));

        assertThat(notes.remove(3)).isTrue();
        assertThat(dbStore.size()).isZero();
        assertThat(notes.clearHighlights()).isTrue();
        assertThat(notes.highlights()).isEmpty();
    }

    @Test
    void aNewOrderMovesTheNotesWithTheirEntries() {
        AnnotationStore a = AnnotationStore.inMemory(), b = AnnotationStore.inMemory();
        MergeOrder first = MergeOrder.build(List.of(new Rows(10, 30).in(), new Rows(20).in()));
        MergedNotes notes = new MergedNotes(List.of(a, b), List.of("a", "b"), first);
        notes.put(1, note("on b"));                                  // b's row 0
        MergeOrder shifted = MergeOrder.build(List.of(new Rows(10, 30).in(),
                new MergeOrder.Input(new Rows(20), 1, 100)));         // b moves after a
        notes.setOrder(shifted);
        assertThat(notes.at(2).note()).isEqualTo("on b");
        assertThat(notes.has(1)).isFalse();
    }
}
