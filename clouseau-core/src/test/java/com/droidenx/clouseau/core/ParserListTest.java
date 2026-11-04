package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParserListTest {

    /** A parser that accepts lines starting with its prefix. */
    private record Prefix(String name, String prefix) implements LogParser {
        @Override public String getName() { return name; }
        @Override public boolean canParse(String line) { return line.startsWith(prefix); }
        @Override public LogEntry parse(String line) {
            return new LogEntry(null, LogEntry.LogLevel.INFO, name, null, line, line, Map.of());
        }
        @Override public List<String> customFields() { return List.of(name + "-field"); }
    }

    private static final class Broken implements LogParser {
        @Override public String getName() { return "Broken"; }
        @Override public boolean canParse(String line) { throw new IllegalStateException("boom"); }
        @Override public LogEntry parse(String line) { throw new IllegalStateException("boom"); }
        @Override public List<String> customFields() { throw new IllegalStateException("boom"); }
    }

    private static List<String> names(List<LogParser> parsers) {
        return parsers.stream().map(LogParser::getName).toList();
    }

    @Test
    void jsonParsersComeFirstThenPlugins() {
        List<LogParser> combined = ParserList.combine(
                List.of(new Prefix("Log4j", "L"), new Prefix("Spring", "S")),
                List.of(new Prefix("Custom", "C")));

        assertThat(names(combined)).containsExactly("Log4j", "Spring", "Custom");
    }

    @Test
    void pluginParserIsUsedForLinesNoJsonParserAccepts() {
        List<LogParser> combined = ParserList.combine(
                List.of(new Prefix("Log4j", "L")),
                List.of(new Prefix("Custom", "C")));

        LogParser chosen = combined.stream().filter(p -> p.canParse("C line")).findFirst().orElseThrow();
        assertThat(chosen.getName()).isEqualTo("Custom");
        assertThat(chosen.parse("C line").logger()).isEqualTo("Custom");
        assertThat(chosen.customFields()).containsExactly("Custom-field");
    }

    @Test
    void jsonParserWinsANameClash() {
        Prefix json = new Prefix("Log4j", "L");
        List<LogParser> combined = ParserList.combine(
                List.of(json),
                List.of(new Prefix("Log4j", "X"), new Prefix("Other", "O")));

        assertThat(names(combined)).containsExactly("Log4j", "Other");
        assertThat(combined.get(0)).isSameAs(json);
    }

    @Test
    void duplicateAndNamelessPluginParsersAreSkipped() {
        List<LogParser> combined = ParserList.combine(
                List.of(),
                List.of(new Prefix("A", "a"), new Prefix("A", "b"), new Prefix(" ", "c"), new Prefix(null, "d")));

        assertThat(names(combined)).containsExactly("A");
    }

    @Test
    void brokenPluginParserCannotEscapeWithAnException() {
        LogParser guarded = ParserList.combine(List.of(), List.of(new Broken())).get(0);

        assertThat(guarded.canParse("anything")).isFalse();
        LogEntry entry = guarded.parse("raw text");
        assertThat(entry.level()).isEqualTo(LogEntry.LogLevel.UNKNOWN);
        assertThat(entry.rawLine()).isEqualTo("raw text");
        assertThat(guarded.customFields()).isEmpty();
    }

    @Test
    void nullFromParseBecomesUnknown() {
        LogParser guarded = ParserList.guard(new LogParser() {
            @Override public String getName() { return "Null"; }
            @Override public boolean canParse(String line) { return true; }
            @Override public LogEntry parse(String line) { return null; }
        }, "Null");

        assertThat(guarded.parse("x").level()).isEqualTo(LogEntry.LogLevel.UNKNOWN);
    }
}
