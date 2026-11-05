package com.droidenx.clouseau.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewBundleTest {

    private static final String[] LINES = {
            "2026-11-04 10:00:00.000 INFO  start",
            "2026-11-04 10:00:01.000 WARN  retry",
            "2026-11-04 10:00:02.000 WARN  retry",
            "2026-11-04 10:00:03.000 ERROR boom",
    };
    private static final String TEXT = String.join("\n", LINES) + "\n";

    @TempDir Path dir;

    private Path fallback() { return dir.resolve("data").resolve("annotations"); }

    private Path plainLog() throws IOException {
        Path log = dir.resolve("app.log");
        Files.writeString(log, TEXT, StandardCharsets.UTF_8);
        return log;
    }

    private Path gzLog() throws IOException {
        Path log = dir.resolve("app.log.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(log))) {
            out.write(TEXT.getBytes(StandardCharsets.UTF_8));
        }
        return log;
    }

    private static AnnotationStore.Annotation note(String line, String text, String author) {
        return new AnnotationStore.Annotation(AnnotationStore.hashOf(line), null,
                "", line, text, author, "2026-11-04T00:00:00Z");
    }

    /** Annotates {@code log} the way a tab does: note on entry 2, highlight on entry 4. */
    private static AnnotationStore annotate(Path key, Path fallbackDir) {
        AnnotationStore store = AnnotationStore.load(key, fallbackDir);
        store.put(1, note(LINES[1], "first retry", "field-engineer"));
        store.setHighlights(Map.of(3, LINES[3]), 5);
        return store;
    }

    /** Opens the bundle's log as a tab does: load the annotations, feed every line, finish. */
    private AnnotationStore openBundle(Path bundle) throws IOException {
        ReviewBundle.Manifest m = ReviewBundle.read(bundle).orElseThrow();
        Path key = ZipLogs.entryKey(bundle, m.log());
        AnnotationStore store = ReviewBundle.loadAnnotations(bundle, m.log(), key, fallback());
        AnnotationStore.Anchoring anchoring = store.startAnchoring();
        if (anchoring != null) {
            for (String line : readLines(bundle, m.log())) anchoring.accept(line);
            anchoring.finish();
        }
        return store;
    }

    private static List<String> readLines(Path file, String entry) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = LogReaders.open(file, entry)) {
            for (String l; (l = r.readLine()) != null; ) lines.add(l);
        }
        return lines;
    }

    private static List<Path> filesIn(Path folder) throws IOException {
        try (var files = Files.list(folder)) {
            return files.toList();
        }
    }

    private static byte[] entryBytes(Path zip, String entry) throws IOException {
        try (InputStream in = ZipLogs.open(zip, entry)) {
            return in.readAllBytes();
        }
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    @Test
    void bundleHoldsManifestLogAndAnnotations() throws IOException {
        Path log = plainLog();
        AnnotationStore store = annotate(log, fallback());
        Path bundle = dir.resolve("out").resolve("app-review.zip");

        Instant before = Instant.now().minusSeconds(1);
        ReviewBundle.write(log, null, store.toJson(), "1.2.3", bundle);

        assertThat(ZipLogs.list(bundle)).extracting(ZipLogs.Entry::name)
                .containsExactly("manifest.json", "app.log", "annotations.json");
        JsonObject manifest = JsonParser.parseString(
                new String(entryBytes(bundle, "manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(manifest.get("format").getAsString()).isEqualTo("clouseau-review-bundle");
        assertThat(manifest.get("formatVersion").getAsInt()).isEqualTo(1);
        assertThat(manifest.get("clouseauVersion").getAsString()).isEqualTo("1.2.3");
        assertThat(manifest.get("originalFileName").getAsString()).isEqualTo("app.log");
        assertThat(manifest.get("log").getAsString()).isEqualTo("app.log");
        assertThat(manifest.get("annotations").getAsString()).isEqualTo("annotations.json");
        assertThat(Instant.parse(manifest.get("exportedAt").getAsString())).isAfter(before);

        assertThat(entryBytes(bundle, "app.log")).isEqualTo(Files.readAllBytes(log));
        // Same format as the sidecar next to the log.
        assertThat(new String(entryBytes(bundle, "annotations.json"), StandardCharsets.UTF_8))
                .isEqualTo(Files.readString(dir.resolve("app.log.annotations")));
        assertThat(filesIn(bundle.getParent())).containsExactly(bundle);   // no temp file left
    }

    @Test
    void gzLogStaysCompressedByteForByte() throws IOException {
        Path log = gzLog();
        Path bundle = dir.resolve("app-review.zip");

        ReviewBundle.write(log, null, AnnotationStore.inMemory().toJson(), "dev", bundle);

        assertThat(ZipLogs.list(bundle)).extracting(ZipLogs.Entry::name).contains("app.log.gz");
        assertThat(entryBytes(bundle, "app.log.gz")).isEqualTo(Files.readAllBytes(log));
        assertThat(ReviewBundle.read(bundle).orElseThrow().originalFileName()).isEqualTo("app.log.gz");
    }

    @Test
    void zipEntryIsExportedUnderItsFileName() throws IOException {
        Path zip = dir.resolve("logs.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("server/server.log"));
            zos.write(TEXT.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("other.log"));
            zos.write("other\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path bundle = dir.resolve("review.zip");

        ReviewBundle.write(zip, "server/server.log", AnnotationStore.inMemory().toJson(), "dev", bundle);

        ReviewBundle.Manifest m = ReviewBundle.read(bundle).orElseThrow();
        assertThat(m.log()).isEqualTo("server.log");
        assertThat(entryBytes(bundle, "server.log")).isEqualTo(TEXT.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void reExportingABundleCarriesTheLogAndTheReviewersNotes() throws IOException {
        Path log = gzLog();
        Path first = dir.resolve("first.zip");
        ReviewBundle.write(log, null, annotate(log, fallback()).toJson(), "dev", first);

        AnnotationStore reviewed = openBundle(first);
        reviewed.put(0, note(LINES[0], "startup looks fine", "developer"));
        Path second = dir.resolve("second.zip");
        ReviewBundle.write(first, "app.log.gz", reviewed.toJson(), "dev", second);

        assertThat(entryBytes(second, "app.log.gz")).isEqualTo(Files.readAllBytes(log));
        AnnotationStore again = openBundle(second);
        assertThat(again.at(0).author()).isEqualTo("developer");
        assertThat(again.at(1).author()).isEqualTo("field-engineer");
    }

    @Test
    void logNamedLikeABundleEntryIsRenamed() throws IOException {
        Path log = dir.resolve("manifest.json");
        Files.writeString(log, TEXT, StandardCharsets.UTF_8);
        Path bundle = dir.resolve("b.zip");

        ReviewBundle.write(log, null, AnnotationStore.inMemory().toJson(), "dev", bundle);

        ReviewBundle.Manifest m = ReviewBundle.read(bundle).orElseThrow();
        assertThat(m.originalFileName()).isEqualTo("manifest.json");
        assertThat(m.log()).isEqualTo("log-manifest.json");
        assertThat(entryBytes(bundle, "log-manifest.json")).isEqualTo(TEXT.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void refusesToOverwriteTheLogItself() throws IOException {
        Path zip = dir.resolve("app.zip");
        ReviewBundle.write(plainLog(), null, "{}", "dev", zip);
        byte[] before = Files.readAllBytes(zip);

        assertThatThrownBy(() -> ReviewBundle.write(zip, "app.log", "{}", "dev", zip))
                .isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(zip)).isEqualTo(before);
    }

    @Test
    void failedWriteLeavesNoFileBehind() throws IOException {
        Path missing = dir.resolve("gone.log");
        Path bundle = dir.resolve("out").resolve("gone.zip");

        assertThatThrownBy(() -> ReviewBundle.write(missing, null, "{}", "dev", bundle))
                .isInstanceOf(IOException.class);
        assertThat(filesIn(bundle.getParent())).isEmpty();
    }

    // ── Detecting ────────────────────────────────────────────────────────────

    @Test
    void ordinaryZipIsNotABundleAndStillReadsAsACompressedLog() throws IOException {
        Path zip = dir.resolve("logs.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("app.log"));
            zos.write(TEXT.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        assertThat(ReviewBundle.read(zip)).isEmpty();
        assertThat(ReviewBundle.isBundle(zip)).isFalse();
        assertThat(readLines(zip, null)).containsExactly(LINES);
        // And its annotations come from its own sidecar only.
        AnnotationStore store = ReviewBundle.loadAnnotations(zip, "app.log",
                ZipLogs.entryKey(zip, "app.log"), fallback());
        assertThat(store.size()).isZero();
    }

    @Test
    void zipWithSomeOtherManifestIsNotABundle() throws IOException {
        // A browser extension, for example, has a manifest.json of its own.
        Path zip = dir.resolve("extension.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("{\"manifest_version\": 3, \"name\": \"x\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("app.log"));
            zos.write(TEXT.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        assertThat(ReviewBundle.read(zip)).isEmpty();
    }

    @Test
    void manifestNamingAMissingLogIsNotABundle() throws IOException {
        Path zip = dir.resolve("broken.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("{\"format\": \"clouseau-review-bundle\", \"formatVersion\": 1, \"log\": \"nope.log\"}"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        assertThat(ReviewBundle.read(zip)).isEmpty();
    }

    @Test
    void garbageAndNonZipFilesAreNotBundles() throws IOException {
        Path notZip = dir.resolve("fake.zip");
        Files.writeString(notZip, "not a zip");

        assertThat(ReviewBundle.read(notZip)).isEmpty();
        assertThat(ReviewBundle.read(plainLog())).isEmpty();
        assertThat(ReviewBundle.read(dir.resolve("missing.zip"))).isEmpty();
    }

    @Test
    void newerFormatVersionStillOpensWithDefaultsForMissingFields() throws IOException {
        Path zip = dir.resolve("future.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("{\"format\": \"clouseau-review-bundle\", \"formatVersion\": 7, \"originalFileName\": \"app.log\", \"extra\": true}"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("app.log"));
            zos.write(TEXT.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Optional<ReviewBundle.Manifest> m = ReviewBundle.read(zip);
        assertThat(m).isPresent();
        assertThat(m.get().log()).isEqualTo("app.log");
        assertThat(m.get().annotations()).isEqualTo("annotations.json");
    }

    // ── Opening ──────────────────────────────────────────────────────────────

    @Test
    void openingABundleAttachesEachAnnotationToItsLineWithItsAuthor() throws IOException {
        Path log = plainLog();
        Path bundle = dir.resolve("received").resolve("app-review.zip");
        ReviewBundle.write(log, null, annotate(log, fallback()).toJson(), "dev", bundle);

        AnnotationStore store = openBundle(bundle);

        assertThat(store.placed()).hasSize(1);
        assertThat(store.at(1).note()).isEqualTo("first retry");
        assertThat(store.at(1).author()).isEqualTo("field-engineer");
        assertThat(store.at(2)).isNull();                         // the identical line stays free
        assertThat(store.highlights()).containsExactly(Map.entry(3, 5));
        assertThat(store.orphans()).isEmpty();
    }

    @Test
    void gzBundleReadsTheDecompressedLog() throws IOException {
        Path log = gzLog();
        Path bundle = dir.resolve("app-review.zip");
        ReviewBundle.write(log, null, annotate(log, fallback()).toJson(), "dev", bundle);

        assertThat(readLines(bundle, "app.log.gz")).containsExactly(LINES);
        assertThat(LogReaders.uncompressedSize(bundle, "app.log.gz")).isEqualTo(-1);
        assertThat(openBundle(bundle).at(1).author()).isEqualTo("field-engineer");
    }

    @Test
    void openingWritesNothingUntilTheReviewerChangesSomething() throws IOException {
        Path log = plainLog();
        Path bundle = dir.resolve("received").resolve("app-review.zip");
        ReviewBundle.write(log, null, annotate(log, fallback()).toJson(), "dev", bundle);

        openBundle(bundle);

        assertThat(filesIn(bundle.getParent())).containsExactly(bundle);
    }

    @Test
    void reviewersNotesGoNextToTheBundleWhichIsLeftUnchanged() throws IOException {
        Path log = plainLog();
        Path bundle = dir.resolve("received").resolve("app-review.zip");
        ReviewBundle.write(log, null, annotate(log, fallback()).toJson(), "dev", bundle);
        byte[] bundleBytes = Files.readAllBytes(bundle);

        AnnotationStore store = openBundle(bundle);
        store.put(3, note(LINES[3], "root cause", "developer"));

        Path sidecar = bundle.resolveSibling("app-review.zip!app.log.annotations");
        assertThat(store.getSavePath()).isEqualTo(sidecar);
        assertThat(Files.readAllBytes(bundle)).isEqualTo(bundleBytes);

        // Reopening reads the sidecar, which has both the bundled and the new notes.
        AnnotationStore again = openBundle(bundle);
        assertThat(again.at(1).author()).isEqualTo("field-engineer");
        assertThat(again.at(3).author()).isEqualTo("developer");
        assertThat(again.highlights()).containsExactly(Map.entry(3, 5));
    }

    @Test
    void deletingABundledNoteSticksAfterReopening() throws IOException {
        Path log = plainLog();
        Path bundle = dir.resolve("app-review.zip");
        ReviewBundle.write(log, null, annotate(log, fallback()).toJson(), "dev", bundle);

        openBundle(bundle).remove(1);

        assertThat(openBundle(bundle).size()).isZero();
    }

    @Test
    void notesThatMatchNoLineArriveAsOrphans() throws IOException {
        Path log = plainLog();
        AnnotationStore store = AnnotationStore.load(log, fallback());
        store.put(0, note("a line that isn't in the file", "stale", "field-engineer"));
        Path bundle = dir.resolve("b.zip");
        ReviewBundle.write(log, null, store.toJson(), "dev", bundle);

        AnnotationStore opened = openBundle(bundle);

        assertThat(opened.placed()).isEmpty();
        assertThat(opened.orphans()).extracting(AnnotationStore.Annotation::note).containsExactly("stale");
    }

    @Test
    void bundleWithUnreadableAnnotationsStillOpens() throws IOException {
        Path zip = dir.resolve("b.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("{\"format\": \"clouseau-review-bundle\", \"formatVersion\": 1, \"log\": \"app.log\"}"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("app.log"));
            zos.write(TEXT.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("annotations.json"));
            zos.write("not json".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        assertThat(openBundle(zip).size()).isZero();
    }

    @Test
    void entryOtherThanTheBundlesLogGetsNoBundledNotes() throws IOException {
        Path log = plainLog();
        Path bundle = dir.resolve("b.zip");
        ReviewBundle.write(log, null, annotate(log, fallback()).toJson(), "dev", bundle);

        AnnotationStore store = ReviewBundle.loadAnnotations(bundle, "annotations.json",
                ZipLogs.entryKey(bundle, "annotations.json"), fallback());

        assertThat(store.size()).isZero();
    }

    // ── Merged views (version 2, D-17) ───────────────────────────────────────

    @Test
    void mergedBundleHoldsEveryLogAndOpensAsItsFirstInAnOlderClouseau() throws IOException {
        Path app = plainLog();
        Path other = Files.createDirectories(dir.resolve("server2")).resolve("app.log");
        Files.writeString(other, TEXT, StandardCharsets.UTF_8);
        Path db = gzLog();
        AnnotationStore appNotes = annotate(app, fallback());
        AnnotationStore otherNotes = AnnotationStore.inMemory();
        otherNotes.put(0, note(LINES[0], "on server 2", "reviewer"));
        Path bundle = dir.resolve("out").resolve("app-review.zip");

        ReviewBundle.writeMerged(List.of(
                new ReviewBundle.Part(app, null, appNotes.toJson(), "Logback", "Europe/Berlin", 0),
                new ReviewBundle.Part(other, null, otherNotes.toJson(), null, "UTC", 192_000),
                new ReviewBundle.Part(db, null, AnnotationStore.inMemory().toJson(), null, null, -5)),
                "1.2.3", bundle);

        assertThat(ZipLogs.list(bundle)).extracting(ZipLogs.Entry::name).containsExactly("manifest.json",
                "app.log", "annotations.json", "2-app.log", "2-app.log.annotations.json",
                "app.log.gz", "app.log.gz.annotations.json");
        JsonObject raw = JsonParser.parseString(
                new String(entryBytes(bundle, "manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(raw.get("formatVersion").getAsInt()).isEqualTo(2);
        // The version 1 fields describe the first log.
        assertThat(raw.get("log").getAsString()).isEqualTo("app.log");
        assertThat(raw.get("annotations").getAsString()).isEqualTo("annotations.json");
        assertThat(entryBytes(bundle, "app.log.gz")).isEqualTo(Files.readAllBytes(db));

        ReviewBundle.Manifest m = ReviewBundle.read(bundle).orElseThrow();
        assertThat(m.isMerged()).isTrue();
        assertThat(m.sources()).containsExactly(
                new ReviewBundle.Source("app.log", "annotations.json", "app.log", "Logback", "Europe/Berlin", 0),
                new ReviewBundle.Source("2-app.log", "2-app.log.annotations.json", "app.log", null, "UTC", 192_000),
                new ReviewBundle.Source("app.log.gz", "app.log.gz.annotations.json", "app.log.gz", null, null, -5));

        // Each log's notes come from its own entry.
        AnnotationStore second = ReviewBundle.loadAnnotations(bundle, "2-app.log",
                ZipLogs.entryKey(bundle, "2-app.log"), fallback());
        AnnotationStore.Anchoring anchoring = second.startAnchoring();
        for (String line : readLines(bundle, "2-app.log")) anchoring.accept(line);
        anchoring.finish();
        assertThat(second.at(0).note()).isEqualTo("on server 2");
        assertThat(openBundle(bundle).at(1).note()).isEqualTo("first retry");
    }

    @Test
    void aBundleOfOneLogStaysVersionOne() throws IOException {
        Path bundle = dir.resolve("one.zip");
        ReviewBundle.write(plainLog(), null, AnnotationStore.inMemory().toJson(), "1", bundle);
        ReviewBundle.Manifest m = ReviewBundle.read(bundle).orElseThrow();
        assertThat(m.formatVersion()).isEqualTo(1);
        assertThat(m.sources()).isEmpty();
        assertThat(m.isMerged()).isFalse();
    }

    @Test
    void aSourceNamingAMissingLogOpensTheFirstLogOnly() throws IOException {
        Path zip = dir.resolve("broken.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(("{\"format\": \"clouseau-review-bundle\", \"formatVersion\": 2, \"log\": \"app.log\","
                    + " \"sources\": [{\"log\": \"app.log\"}, {\"log\": \"gone.log\"}]}").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("app.log"));
            zos.write(TEXT.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        ReviewBundle.Manifest m = ReviewBundle.read(zip).orElseThrow();
        assertThat(m.log()).isEqualTo("app.log");
        assertThat(m.isMerged()).isFalse();
    }
}
