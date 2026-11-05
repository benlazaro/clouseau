package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Indexing one file, as single-file tabs and merged views both do (G-11). */
class FileIndexerTest {

    private static final RegexLogParser PARSER = new RegexLogParser("test",
            Pattern.compile("(?<timestamp>\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d) (?<level>[A-Z]+) (?<message>.*)"),
            "yyyy-MM-dd HH:mm:ss");
    private static final String TEXT = """
            2026-11-04 10:00:01 INFO one
            2026-11-04 10:00:02 ERROR two
            \tat com.acme.App.run
            """;

    @TempDir Path dir;

    private final LineParser lines = new LineParser(Optional.of(PARSER), List.of(PARSER), null);

    @Test
    void indexesAPlainFileAndEndsWhereFollowingResumes() throws Exception {
        Path log = Files.writeString(dir.resolve("app.log"), TEXT, StandardCharsets.UTF_8);
        EntryIndex index = new EntryIndex();
        List<LogEntry> seen = new ArrayList<>();
        FileIndexer.Result r = FileIndexer.index(log, null, null, lines, index, null, seen::add,
                () -> false, () -> false);
        assertThat(r.end()).isEqualTo(Files.size(log));
        assertThat(r.lines()).isEqualTo(3);
        assertThat(r.partial()).isFalse();
        assertThat(index.size()).isEqualTo(3);
        assertThat(seen).extracting(LogEntry::message).containsExactly("one", "two", "\tat com.acme.App.run");
    }

    @Test
    void unpacksACompressedFileToItsCopy() throws Exception {
        Path gz = dir.resolve("app.log.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(TEXT.getBytes(StandardCharsets.UTF_8));
        }
        Path copy = dir.resolve("copy.log");
        EntryIndex index = new EntryIndex();
        FileIndexer.Result r = FileIndexer.index(gz, null, copy, lines, index, null, e -> {},
                () -> false, () -> false);
        assertThat(r.end()).isEqualTo(-1);
        assertThat(Files.readString(copy)).isEqualTo(TEXT);
        try (IndexedEntries entries = IndexedEntries.open(copy, index, lines)) {
            assertThat(entries.get(1).message()).isEqualTo("two");
        }
    }

    @Test
    void stopsWhenMemoryRunsLow() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25_000; i++) sb.append("2026-11-04 10:00:01 INFO line ").append(i).append('\n');
        Path log = Files.writeString(dir.resolve("big.log"), sb.toString());
        FileIndexer.Result r = FileIndexer.index(log, null, null, lines, new EntryIndex(), null, e -> {},
                () -> true, () -> false);
        assertThat(r.lowMemory()).isTrue();
        assertThat(r.partial()).isTrue();
        assertThat(r.lines()).isEqualTo(LogFileReader.DEFAULT_CHECK_INTERVAL);
    }
}
