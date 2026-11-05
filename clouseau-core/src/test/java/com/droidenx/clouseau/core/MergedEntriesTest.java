package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Several indexed files read as one timeline (D-17 step 3). */
class MergedEntriesTest {

    private static final LineParser PARSER = new LineParser(Optional.of(new RegexLogParser("test",
            Pattern.compile("(?<timestamp>\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d) (?<level>[A-Z]+) (?<message>.*)"),
            "yyyy-MM-dd HH:mm:ss")), List.of(), null);

    @TempDir Path dir;

    private IndexedEntries index(String name, String text) throws IOException {
        Path file = Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
        EntryIndex index = new EntryIndex();
        try (InputStream in = Files.newInputStream(file)) {
            LineScanner.scan(in, (o, l, t) -> index.add(o, l, PARSER.parse(t)), () -> false);
        }
        return IndexedEntries.open(file, index, PARSER);
    }

    private MergedEntries merge() throws IOException {
        IndexedEntries app = index("app.log", """
                2026-11-04 10:00:01 INFO app starts
                2026-11-04 10:00:03 ERROR app fails
                \tat com.acme.App.run
                \tat com.acme.App.main
                2026-11-04 10:00:05 INFO app retries
                """);
        IndexedEntries db = index("db.log", """
                2026-11-04 10:00:02 INFO db ready
                2026-11-04 10:00:04 WARN db slow
                """);
        List<MergedEntries.Source> sources = List.of(
                new MergedEntries.Source("app.log", app), new MergedEntries.Source("db.log", db));
        MergeOrder order = MergeOrder.build(List.of(
                new MergeOrder.Input(app.index(), app.index().size(), 0),
                new MergeOrder.Input(db.index(), db.index().size(), 0)));
        return new MergedEntries(sources, order);
    }

    private static List<String> messages(MergedEntries m) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < m.size(); i++) out.add(m.get(i).rawLine().strip());
        return out;
    }

    @Test
    void readsEveryFileInTimelineOrder() throws IOException {
        try (MergedEntries m = merge()) {
            assertThat(messages(m)).containsExactly(
                    "2026-11-04 10:00:01 INFO app starts",
                    "2026-11-04 10:00:02 INFO db ready",
                    "2026-11-04 10:00:03 ERROR app fails",
                    "at com.acme.App.run",
                    "at com.acme.App.main",
                    "2026-11-04 10:00:04 WARN db slow",
                    "2026-11-04 10:00:05 INFO app retries");
            assertThat(m.sourceCount()).isEqualTo(2);
            assertThat(m.sourceOf(1)).isEqualTo(1);
            assertThat(m.sourceName(m.sourceOf(1))).isEqualTo("db.log");
            assertThat(m.isContinuation(3)).isTrue();
            assertThat(m.isContinuation(5)).isFalse();
        }
    }

    @Test
    void filtersBySourceAndIndexColumns() throws IOException {
        try (MergedEntries m = merge()) {
            CombinedFilter onlyApp = new CombinedFilter(EnumSet.complementOf(EnumSet.of(LogLevel.UNKNOWN)),
                    Set.of(), false, Map.of(), null, null, null, Set.of(1));
            List<Integer> shown = new ArrayList<>();
            for (int i = 0; i < m.size(); i++) if (m.testIndex(onlyApp, i)) shown.add(i);
            assertThat(shown).containsExactly(0, 2, 6);
            assertThat(m.needsText(onlyApp)).isFalse();
        }
    }

    @Test
    void scanReadsOneRunPerSourceAndReportsMergedOrdinals() throws IOException {
        try (MergedEntries m = merge()) {
            List<String> visited = new ArrayList<>();
            boolean complete = m.scan(1, 6, new EntryRows.Visitor() {
                @Override public void startRun() { visited.add("|"); }
                @Override public boolean wanted(int ordinal, boolean continuation) { return !continuation; }
                @Override public void accept(int ordinal, LogEntry e) { visited.add(ordinal + ":" + e.message()); }
            }, () -> false);
            assertThat(complete).isTrue();
            assertThat(visited).containsExactly("|", "2:app fails", "|", "1:db ready", "5:db slow");
        }
    }
}
