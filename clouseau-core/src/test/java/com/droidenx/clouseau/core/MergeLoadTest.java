package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Loading several files into one merged view (D-17 step 3). */
class MergeLoadTest {

    private static final LogParser PARSER = new RegexLogParser("test",
            Pattern.compile("(?<timestamp>\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d) (?<level>[A-Z]+) (?<message>.*)"),
            "yyyy-MM-dd HH:mm:ss");
    /** Recognizes "@@ …" lines but finds no timestamp in them. */
    private static final LogParser NO_TIME = new RegexLogParser("no time",
            Pattern.compile("@@ (?<message>.*)"), "yyyy-MM-dd HH:mm:ss");

    @TempDir Path dir;

    private MergeLoad.File plain(String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return new MergeLoad.File(p, null);
    }

    private MergeLoad.File gz(String name, String text) throws IOException {
        Path p = dir.resolve(name);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(p))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return new MergeLoad.File(p, null);
    }

    private MergeLoad.Result run(List<MergeLoad.File> files, AtomicInteger seen) throws Exception {
        Path cache = Files.createDirectories(dir.resolve("cache"));
        return MergeLoad.run(files, Optional.empty(), List.of(PARSER, NO_TIME), f -> MergeLoad.PerFile.DEFAULT, cache, "rotated", null,
                () -> false, () -> false, e -> seen.incrementAndGet());
    }

    @Test
    void mergesPlainAndCompressedFilesAndSkipsTheRest() throws Exception {
        MergeLoad.File app = plain("app.log", """
                2026-11-04 10:00:01 INFO app starts
                2026-11-04 10:00:03 ERROR app fails
                \tat com.acme.App.run
                """);
        MergeLoad.File old = gz("app.log.1.gz", """
                2026-11-04 09:59:59 INFO yesterday's last line
                """);
        MergeLoad.File db = plain("db.log", """
                2026-11-04 10:00:02 INFO db ready
                """);
        MergeLoad.File notes = plain("notes.txt", "just some notes\nmore notes\n");
        MergeLoad.File stamps = plain("plain.log", "@@ no time here\n@@ nor here\n");
        AtomicInteger seen = new AtomicInteger();

        MergeLoad.Result r = run(List.of(app, old, db, notes, stamps), seen);
        try (MergedEntries m = r.entries()) {
            assertThat(m).isNotNull();
            assertThat(r.mergeable()).containsExactly(app, old, db);
            assertThat(r.skipped()).containsExactly(
                    new MergeLoad.Skipped(notes, MergeLoad.Skip.UNRECOGNIZED),
                    new MergeLoad.Skipped(stamps, MergeLoad.Skip.NO_TIMESTAMPS));
            assertThat(r.copies()).hasSize(1);
            assertThat(seen.get()).isEqualTo(5);

            List<String> lines = new ArrayList<>();
            for (int i = 0; i < m.size(); i++) lines.add(m.sourceName(m.sourceOf(i)) + ": " + m.get(i).message());
            assertThat(lines).containsExactly(
                    "app.log.1.gz: yesterday's last line",
                    "app.log: app starts",
                    "db.log: db ready",
                    "app.log: app fails",
                    "app.log: \tat com.acme.App.run");
        } finally {
            r.copies().forEach(LogCache::delete);
        }
    }

    @Test
    void lessThanTwoMergeableFilesMergeNothing() throws Exception {
        MergeLoad.File app = plain("app.log", "2026-11-04 10:00:01 INFO app starts\n");
        MergeLoad.File notes = plain("notes.txt", "just notes\n");
        MergeLoad.Result r = run(List.of(app, notes), new AtomicInteger());
        assertThat(r.entries()).isNull();
        assertThat(r.mergeable()).containsExactly(app);
        assertThat(r.skipped()).extracting(MergeLoad.Skipped::file).containsExactly(notes);
    }

    @Test
    void sameFileNamesGetTheirFolder() throws Exception {
        MergeLoad.File a = plain("server1/app.log", "x\n");
        MergeLoad.File b = plain("server2/app.log", "x\n");
        MergeLoad.File c = plain("db.log", "x\n");
        assertThat(MergeLoad.names(List.of(a, b, c))).containsExactly("server1/app.log", "server2/app.log", "db.log");
        assertThat(MergeLoad.names(List.of(new MergeLoad.File(dir.resolve("logs.zip"), "a/app.log"),
                new MergeLoad.File(dir.resolve("logs.zip"), "b/app.log"))))
                .containsExactly("logs.zip!a/app.log", "logs.zip!b/app.log");
    }

    @Test
    void stopsWhenMemoryRunsLow() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25_000; i++) sb.append("2026-11-04 10:00:01 INFO line ").append(i).append('\n');
        MergeLoad.File a = plain("a.log", sb.toString());
        MergeLoad.File b = plain("b.log", sb.toString());
        Path cache = Files.createDirectories(dir.resolve("cache"));
        MergeLoad.Result r = MergeLoad.run(List.of(a, b), Optional.empty(), List.of(PARSER), f -> MergeLoad.PerFile.DEFAULT, cache, "rotated", null,
                () -> true, () -> false, (LogEntry e) -> {});
        assertThat(r.stopped()).isTrue();
        assertThat(r.entries()).isNull();
    }

    @Test
    void eachFileHasItsOwnTimezoneAndOffset() throws Exception {
        MergeLoad.File a = plain("a.log", """
                2026-11-04 10:00:01 INFO a 1
                2026-11-04 10:00:03 INFO a 3
                2026-11-04 11:00:00 INFO a 11
                """);
        MergeLoad.File b = plain("b.log", "2026-11-04 10:00:02 INFO b 2, an hour behind UTC\n");
        MergeLoad.File c = plain("c.log", "2026-11-04 10:00:02 INFO c 2, clock 2 s slow\n");
        java.util.Map<MergeLoad.File, MergeLoad.PerFile> setup = java.util.Map.of(
                a, new MergeLoad.PerFile(java.time.ZoneOffset.UTC, 0),
                b, new MergeLoad.PerFile(java.time.ZoneOffset.ofHours(-1), 0),
                c, new MergeLoad.PerFile(java.time.ZoneOffset.UTC, 2_000));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        MergeLoad.Result r = MergeLoad.run(List.of(a, b, c), Optional.empty(), List.of(PARSER), setup::get, cache,
                "rotated", null, () -> false, () -> false, e -> {});
        try (MergedEntries m = r.entries()) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < m.size(); i++) lines.add(m.get(i).message());
            assertThat(lines).containsExactly("a 1", "a 3", "c 2, clock 2 s slow", "a 11", "b 2, an hour behind UTC");
            assertThat(m.offsetMillis(2)).isEqualTo(2_000);
        }
    }

    @Test
    void aFileCanHaveItsOwnParser() throws Exception {
        LogParser pipes = new RegexLogParser("pipes",
                Pattern.compile("(?<timestamp>\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)\\|(?<level>[A-Z]+)\\|(?<device>\\w+)\\|(?<message>.*)"),
                "yyyy-MM-dd HH:mm:ss");
        MergeLoad.File app = plain("app.log", "2026-11-04 10:00:01 INFO app starts\n2026-11-04 10:00:03 INFO app ends\n");
        MergeLoad.File dev = plain("device.log", "2026-11-04 10:00:02|INFO|phone|device wakes\n");
        Path cache = Files.createDirectories(dir.resolve("cache"));

        // The tab's parser alone doesn't recognize device.log: it's left out.
        MergeLoad.Result without = MergeLoad.run(List.of(app, dev), Optional.of(PARSER), List.of(PARSER, pipes),
                f -> MergeLoad.PerFile.DEFAULT, cache, "rotated", null, () -> false, () -> false, e -> {});
        assertThat(without.entries()).isNull();
        assertThat(without.skipped()).containsExactly(new MergeLoad.Skipped(dev, MergeLoad.Skip.UNRECOGNIZED));

        // With its own parser it's merged, and that parser's fields are columns.
        java.util.Map<MergeLoad.File, MergeLoad.PerFile> setup = java.util.Map.of(
                app, MergeLoad.PerFile.DEFAULT, dev, new MergeLoad.PerFile(null, 0, pipes));
        MergeLoad.Result r = MergeLoad.run(List.of(app, dev), Optional.of(PARSER), List.of(PARSER, pipes),
                setup::get, cache, "rotated", null, () -> false, () -> false, e -> {});
        try (MergedEntries m = r.entries()) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < m.size(); i++) lines.add(m.get(i).message());
            assertThat(lines).containsExactly("app starts", "device wakes", "app ends");
            assertThat(m.get(1).fields()).containsEntry("device", "phone");
            assertThat(r.fields()).contains("device");
        }
    }
}
