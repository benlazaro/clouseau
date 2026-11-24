package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import com.droidenx.clouseau.core.HighlightRule.Column;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HighlightRulesTest {

    private static LogEntry entry(LogLevel level, String logger, String message) {
        return LogEntry.builder(message).timestamp(Instant.EPOCH).level(level)
                .logger(logger).thread("main").message(message).field("user", "alice").build();
    }

    private static LogEntry continuation(String line) {
        return LogEntry.builder(line).build();
    }

    private static HighlightRule rule(Column column, String text, LogLevel min, int color) {
        return new HighlightRule(true, column, column == Column.FIELD ? "user" : null, text, false, false, min, color);
    }

    @Test
    void firstMatchingRuleWins() {
        HighlightRules rules = HighlightRules.of(List.of(
                rule(Column.MESSAGE, "timeout", null, 2),
                rule(Column.ANY, "time", null, 5)));
        assertThat(rules.slotOf(entry(LogLevel.INFO, "a.B", "Read TIMEOUT after 3s"))).isEqualTo(2);
        assertThat(rules.slotOf(entry(LogLevel.INFO, "a.Time", "nothing here"))).isEqualTo(5);
        assertThat(rules.slotOf(entry(LogLevel.INFO, "a.B", "nothing here"))).isEqualTo(-1);
    }

    @Test
    void columnsLevelCaseAndRegex() {
        LogEntry warn = entry(LogLevel.WARN, "com.acme.Db", "Pool exhausted");
        assertThat(HighlightRules.of(List.of(rule(Column.LOGGER, "acme", null, 1))).slotOf(warn)).isEqualTo(1);
        assertThat(HighlightRules.of(List.of(rule(Column.THREAD, "acme", null, 1))).slotOf(warn)).isEqualTo(-1);
        assertThat(HighlightRules.of(List.of(rule(Column.FIELD, "ALICE", null, 1))).slotOf(warn)).isEqualTo(1);
        assertThat(HighlightRules.of(List.of(rule(Column.ANY, "", LogLevel.WARN, 3))).slotOf(warn)).isEqualTo(3);
        assertThat(HighlightRules.of(List.of(rule(Column.ANY, "", LogLevel.ERROR, 3))).slotOf(warn)).isEqualTo(-1);

        HighlightRule caseSensitive = new HighlightRule(true, Column.MESSAGE, null, "pool", true, false, null, 4);
        assertThat(HighlightRules.of(List.of(caseSensitive)).slotOf(warn)).isEqualTo(-1);
        HighlightRule regex = new HighlightRule(true, Column.MESSAGE, null, "^pool\\s+ex", false, true, null, 6);
        assertThat(HighlightRules.of(List.of(regex)).slotOf(warn)).isEqualTo(6);
    }

    @Test
    void disabledIncompleteAndBadRulesAreLeftOut() {
        assertThat(HighlightRules.of(List.of(
                rule(Column.ANY, "x", null, 1).withEnabled(false),
                rule(Column.ANY, "", null, 1),
                new HighlightRule(true, Column.ANY, null, "(", false, true, null, 1),
                new HighlightRule(true, Column.FIELD, " ", "x", false, false, null, 1))).isEmpty()).isTrue();
        assertThat(new HighlightRule(true, Column.ANY, null, "(", false, true, null, 1).patternError()).isNotNull();
    }

    @Test
    void continuationLinesMarkTheirEntryOnceInOrder() {
        HighlightRules rules = HighlightRules.of(List.of(rule(Column.MESSAGE, "OutOfMemoryError", LogLevel.ERROR, 0)));
        List<LogEntry> entries = List.of(
                entry(LogLevel.ERROR, "a", "Request failed"),       // 0: marked by its trace
                continuation("java.lang.OutOfMemoryError: heap"),    // 1
                continuation("  at Foo.bar(Foo.java:1) OutOfMemoryError"),
                entry(LogLevel.INFO, "a", "retrying"),              // 3: below ERROR, its trace doesn't count
                continuation("OutOfMemoryError again"),
                entry(LogLevel.ERROR, "a", "OutOfMemoryError direct")); // 5
        List<int[]> hits = new ArrayList<>();
        HighlightRules.Scan scan = rules.scan();
        for (int i = 0; i < entries.size(); i++) scan.accept(i, entries.get(i), (o, s) -> hits.add(new int[]{o, s}));
        // The entry, then each matching line of its trace; the INFO entry's trace is below the level.
        assertThat(hits).containsExactly(new int[]{0, 0}, new int[]{1, 0}, new int[]{2, 0}, new int[]{5, 0});
    }

    @Test
    void storeRoundTripsAndSetsAsideADamagedFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(HighlightRuleStore.FILE_NAME);
        List<HighlightRule> rules = List.of(
                new HighlightRule(true, Column.FIELD, "user", "bob", true, false, LogLevel.WARN, 3),
                new HighlightRule(false, Column.MESSAGE, null, "a.*b", false, true, null, 7));
        HighlightRuleStore.write(file, rules);
        assertThat(HighlightRuleStore.read(file)).isEqualTo(rules);

        Files.writeString(file, "{not json");
        assertThat(HighlightRuleStore.read(file)).isEmpty();
        assertThat(dir.resolve(HighlightRuleStore.FILE_NAME + ".bad")).exists();
        assertThat(HighlightRuleStore.read(dir.resolve("missing.json"))).isEmpty();
    }

    @Test
    void storeSkipsUnreadableRules() {
        List<HighlightRule> rules = HighlightRuleStore.parse(
                "{\"version\":1,\"rules\":[{\"in\":\"nowhere\",\"text\":\"x\",\"color\":1},"
                + "{\"text\":\"ok\",\"color\":2,\"extra\":true},{\"text\":\"y\",\"color\":12}]}");
        assertThat(rules).containsExactly(new HighlightRule(true, Column.ANY, null, "ok", false, false, null, 2));
    }
}
