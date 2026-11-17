package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The plugin API's {@link LogEntry} builder (API 0.3.0). */
class LogEntryBuilderTest {

    @Test
    void startsWithNothingButTheLine() {
        LogEntry e = LogEntry.builder("plain text").build();

        assertThat(e.rawLine()).isEqualTo("plain text");
        assertThat(e.message()).isEqualTo("plain text");
        assertThat(e.level()).isEqualTo(LogLevel.UNKNOWN);
        assertThat(e.timestamp()).isNull();
        assertThat(e.logger()).isNull();
        assertThat(e.fields()).isEmpty();
    }

    @Test
    void buildsWhatTheConstructorBuilds() {
        Instant t = Instant.parse("2026-11-04T10:00:00Z");
        LogEntry built = LogEntry.builder("raw").timestamp(t).level(LogLevel.WARN).logger("app")
                .thread("main").message("hello").field("traceId", "abc").build();

        assertThat(built).isEqualTo(new LogEntry(t, LogLevel.WARN, "app", "main", "hello", "raw", Map.of("traceId", "abc")));
        assertThat(built.hashCode()).isEqualTo(
                new LogEntry(t, LogLevel.WARN, "app", "main", "hello", "raw", Map.of("traceId", "abc")).hashCode());
    }

    @Test
    void leavesNullFieldValuesOutAndCopiesTheMap() {
        Map<String, String> fields = new HashMap<>();
        fields.put("a", "1");
        fields.put("b", null);
        LogEntry e = LogEntry.builder("raw").fields(fields).field("c", null).build();
        fields.put("a", "changed");

        assertThat(e.fields()).containsExactly(Map.entry("a", "1"));
    }

    @Test
    void withTimestampChangesOnlyTheTime() {
        LogEntry e = LogEntry.builder("raw").level(LogLevel.ERROR).message("m").build();
        Instant t = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(e.withTimestamp(t)).isEqualTo(e.toBuilder().timestamp(t).build());
        assertThat(e.withTimestamp(t).level()).isEqualTo(LogLevel.ERROR);
    }

    @Test
    void needsTheRawLine() {
        assertThatThrownBy(() -> LogEntry.builder(null)).isInstanceOf(NullPointerException.class);
    }
}
