package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationStoreTest {

    @TempDir Path dir;

    private Path log;
    private Path fallbackDir;

    private Path writeLog(String... lines) throws IOException {
        log = dir.resolve("app.log");
        fallbackDir = dir.resolve("data").resolve("annotations");
        Files.writeString(log, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return log;
    }

    /** Reads the log the way LogPanel does: load the store, feed every line, finish. */
    private AnnotationStore open(String... lines) {
        AnnotationStore store = AnnotationStore.load(log, fallbackDir);
        AnnotationStore.Anchoring anchoring = store.startAnchoring();
        if (anchoring != null) {
            for (String line : lines) anchoring.accept(line);
            anchoring.finish();
        }
        return store;
    }

    private static AnnotationStore.Annotation note(String line, String text) {
        return new AnnotationStore.Annotation(AnnotationStore.hashOf(line), null,
                "", line, text, "me", "2026-11-04T00:00:00Z");
    }

    @Test
    void identicalLinesKeepTheirOwnAnnotations() throws IOException {
        String[] lines = {"start", "retry", "retry", "retry", "end"};
        writeLog(lines);
        AnnotationStore first = open(lines);
        first.put(1, note("retry", "first retry"));
        first.put(3, note("retry", "third retry"));

        AnnotationStore again = open(lines);

        assertThat(again.at(1).note()).isEqualTo("first retry");
        assertThat(again.at(2)).isNull();
        assertThat(again.at(3).note()).isEqualTo("third retry");
        assertThat(again.orphans()).isEmpty();
    }

    @Test
    void annotationFollowsItsLineToTheNearestMatch() throws IOException {
        writeLog("a", "error X", "b", "c", "error X");
        open("a", "error X", "b", "c", "error X").put(1, note("error X", "first error"));

        // Two lines were inserted at the top: entry 1 is now "new", and the nearest
        // "error X" is entry 3 (entry 6 is further away).
        String[] changed = {"new", "new", "a", "error X", "b", "c", "error X"};
        AnnotationStore again = open(changed);

        assertThat(again.at(1)).isNull();
        assertThat(again.at(3).note()).isEqualTo("first error");
        assertThat(again.at(3).line()).isEqualTo(4);
        assertThat(again.at(6)).isNull();
    }

    @Test
    void oldSidecarsWithoutLineNumbersStillAttach() throws IOException {
        writeLog("x", "boom", "y");
        Files.writeString(dir.resolve("app.log.annotations"), """
                {"annotations": [{"lineHash": "%s", "note": "old note", "author": "me"}]}
                """.formatted(AnnotationStore.hashOf("boom")));

        AnnotationStore store = open("x", "boom", "y");

        assertThat(store.at(1).note()).isEqualTo("old note");
    }

    @Test
    void annotationsWhoseLineIsGoneAreKeptAsOrphans() throws IOException {
        writeLog("a", "gone");
        open("a", "gone").put(1, note("gone", "keep me"));

        AnnotationStore again = open("a", "other");
        assertThat(again.placed()).isEmpty();
        assertThat(again.orphans()).extracting(AnnotationStore.Annotation::note).containsExactly("keep me");

        // Saving for another reason must not drop the orphan.
        again.put(0, note("a", "new"));
        assertThat(open("a", "other").orphans()).hasSize(1);
    }

    @Test
    void savesToTheDataFolderWhenTheLogFolderCantBeWritten() throws IOException {
        writeLog("a", "b");
        // A directory where the sidecar should go makes writing it fail on every OS.
        Files.createDirectory(dir.resolve("app.log.annotations"));

        AnnotationStore store = open("a", "b");
        assertThat(store.put(1, note("b", "saved elsewhere"))).isTrue();

        assertThat(store.isUsingFallback()).isTrue();
        assertThat(store.getSavePath()).startsWith(fallbackDir);
        assertThat(open("a", "b").at(1).note()).isEqualTo("saved elsewhere");
    }

    @Test
    void theNewerOfTheTwoSidecarsWins() throws IOException {
        writeLog("a");
        Path nextToLog = dir.resolve("app.log.annotations");
        Files.writeString(nextToLog, """
                {"annotations": [{"lineHash": "%s", "line": 1, "note": "old"}]}
                """.formatted(AnnotationStore.hashOf("a")));
        Files.setLastModifiedTime(nextToLog, FileTime.fromMillis(1_000_000));
        Path fallback = fallbackDir.resolve(
                AnnotationStore.hashOf(log.toAbsolutePath().normalize().toString()) + ".annotations");
        Files.createDirectories(fallbackDir);
        Files.writeString(fallback, """
                {"annotations": [{"lineHash": "%s", "line": 1, "note": "new"}]}
                """.formatted(AnnotationStore.hashOf("a")));

        assertThat(open("a").at(0).note()).isEqualTo("new");
    }

    @Test
    void nothingToAnchorMeansNoHashing() throws IOException {
        writeLog("a");
        assertThat(AnnotationStore.load(log, fallbackDir).startAnchoring()).isNull();
    }

    @Test
    void inMemoryStoreNeverWrites() {
        AnnotationStore store = AnnotationStore.inMemory();
        assertThat(store.put(0, note("a", "n"))).isTrue();
        assertThat(store.getSavePath()).isNull();
        assertThat(store.placed()).extracting(AnnotationStore.Placed::ordinal).isEqualTo(List.of(0));
    }

    @Test
    void highlightsAreSavedAndAnchoredLikeNotes() throws IOException {
        String[] lines = {"start", "retry", "retry", "retry"};
        writeLog(lines);
        AnnotationStore store = open(lines);
        store.setHighlights(java.util.Map.of(1, "retry", 3, "retry"), 2);
        store.put(1, note("retry", "same line as a highlight"));

        AnnotationStore again = open(lines);

        assertThat(again.highlights()).containsExactly(
                java.util.Map.entry(1, 2), java.util.Map.entry(3, 2));
        assertThat(again.at(1).note()).isEqualTo("same line as a highlight");
    }

    @Test
    void clearingHighlightsKeepsNotesAndDropsOrphanHighlights() throws IOException {
        writeLog("a", "b");
        AnnotationStore store = open("a", "b");
        store.setHighlights(java.util.Map.of(0, "a"), 0);
        store.put(1, note("b", "note"));

        AnnotationStore changed = open("x", "b");   // "a" is gone: its highlight is an orphan
        assertThat(changed.highlights()).isEmpty();
        changed.clearHighlights();

        AnnotationStore again = open("a", "b");
        assertThat(again.highlights()).isEmpty();
        assertThat(again.at(1).note()).isEqualTo("note");
    }

    @Test
    void settingANullSlotRemovesTheHighlight() throws IOException {
        writeLog("a");
        AnnotationStore store = open("a");
        store.setHighlights(java.util.Map.of(0, "a"), 5);
        store.setHighlights(java.util.Map.of(0, "a"), null);

        assertThat(open("a").highlights()).isEmpty();
    }

    @Test
    void hashMatchesEarlierVersions() {
        // First 8 bytes of SHA-256("hello"), as the old UI store wrote them.
        assertThat(AnnotationStore.hashOf("hello")).isEqualTo("2cf24dba5fb0a30e");
    }
}
