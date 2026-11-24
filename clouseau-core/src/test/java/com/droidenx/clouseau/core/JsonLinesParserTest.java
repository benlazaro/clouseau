package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class JsonLinesParserTest {

    private final JsonLinesParser parser = new JsonLinesParser();
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @Test
    void acceptsOnlyObjectsWithALogKey() {
        assertThat(parser.canParse("{\"message\":\"hi\"}")).isTrue();
        assertThat(parser.canParse("  {\"level\":\"info\"}  ")).isTrue();
        assertThat(parser.canParse("{\"ts\":1700000000.5}")).isTrue();
        assertThat(parser.canParse("{\"a\":1,\"b\":2}")).isFalse();
        assertThat(parser.canParse("[1,2]")).isFalse();
        assertThat(parser.canParse("{not json}")).isFalse();
        assertThat(parser.canParse("2024-01-01 12:00:00 INFO {\"message\":\"x\"}")).isFalse();
        assertThat(parser.canParse(null)).isFalse();
    }

    @Test
    void readsLogbackStyleJson() {
        LogEntry e = parser.parse("{\"@timestamp\":\"2024-03-01T10:15:30.123+01:00\",\"level\":\"WARN\","
                + "\"logger_name\":\"com.acme.Api\",\"thread_name\":\"main\",\"message\":\"slow\",\"requestId\":\"r-1\"}", PARIS);

        assertThat(e.timestamp()).isEqualTo(Instant.parse("2024-03-01T09:15:30.123Z"));
        assertThat(e.level()).isEqualTo(LogLevel.WARN);
        assertThat(e.logger()).isEqualTo("com.acme.Api");
        assertThat(e.thread()).isEqualTo("main");
        assertThat(e.message()).isEqualTo("slow");
        assertThat(e.fields()).containsExactly(java.util.Map.entry("requestId", "r-1"));
    }

    @Test
    void readsEcsNestedKeysAndKeepsTheRestOfTheObject() {
        LogEntry e = parser.parse("{\"@timestamp\":\"2024-03-01T09:00:00Z\",\"log\":{\"level\":\"error\",\"logger\":\"db\"},"
                + "\"message\":\"down\",\"error\":{\"type\":\"IOException\",\"stack_trace\":\"java.io.IOException\\n\\tat Db.open(Db.java:12)\"}}", PARIS);

        assertThat(e.level()).isEqualTo(LogLevel.ERROR);
        assertThat(e.logger()).isEqualTo("db");
        assertThat(e.message()).isEqualTo("down\njava.io.IOException\n\tat Db.open(Db.java:12)");
        assertThat(e.fields()).containsOnlyKeys("error");
        assertThat(e.fields().get("error")).isEqualTo("{\"type\":\"IOException\"}");
    }

    @Test
    void readsPinoNumbersForLevelAndTime() {
        LogEntry e = parser.parse("{\"level\":50,\"time\":1700000000123,\"pid\":7,\"hostname\":\"h\",\"msg\":\"boom\"}", PARIS);

        assertThat(e.level()).isEqualTo(LogLevel.ERROR);
        assertThat(e.timestamp()).isEqualTo(Instant.ofEpochMilli(1700000000123L));
        assertThat(e.message()).isEqualTo("boom");
        assertThat(e.fields()).containsEntry("pid", "7").containsEntry("hostname", "h");
    }

    @Test
    void readsEpochSecondsWithAFraction() {
        LogEntry e = parser.parse("{\"level\":\"debug\",\"ts\":1700000000.25,\"msg\":\"zap\"}", PARIS);

        assertThat(e.timestamp()).isEqualTo(Instant.ofEpochSecond(1700000000L, 250_000_000));
        assertThat(e.level()).isEqualTo(LogLevel.DEBUG);
    }

    @Test
    void readsATimeWithoutAnOffsetInTheLogsTimezone() {
        LogEntry e = parser.parse("{\"asctime\":\"2024-03-01 10:00:00,500\",\"levelname\":\"WARNING\",\"name\":\"app\",\"message\":\"m\"}", PARIS);

        assertThat(e.timestamp()).isEqualTo(Instant.parse("2024-03-01T09:00:00.500Z"));
        assertThat(e.level()).isEqualTo(LogLevel.WARN);
        assertThat(e.logger()).isEqualTo("app");
    }

    @Test
    void readsSerilogCompactJson() {
        LogEntry e = parser.parse("{\"@t\":\"2024-03-01T09:00:00.0000000Z\",\"@mt\":\"Hello {Name}\",\"@l\":\"Fatal\",\"Name\":\"Ada\"}", PARIS);

        assertThat(e.level()).isEqualTo(LogLevel.FATAL);
        assertThat(e.message()).isEqualTo("Hello {Name}");
        assertThat(e.fields()).containsEntry("Name", "Ada");
    }

    @Test
    void readsDockerJsonFileLines() {
        LogEntry e = parser.parse("{\"log\":\"started\\n\",\"stream\":\"stderr\",\"time\":\"2024-03-01T09:00:00.123456789Z\"}", PARIS);

        assertThat(e.message()).isEqualTo("started");
        assertThat(e.timestamp()).isEqualTo(Instant.parse("2024-03-01T09:00:00.123456789Z"));
        assertThat(e.fields()).containsEntry("stream", "stderr");
    }

    @Test
    void aRecordWithoutALevelIsInfoNotAContinuation() {
        LogEntry e = parser.parse("{\"message\":\"no level\"}", PARIS);

        assertThat(e.level()).isEqualTo(LogLevel.INFO);
        assertThat(e.timestamp()).isNull();
    }

    @Test
    void namesTheLevelsUsedAcrossLibraries() {
        assertThat(level("\"Verbose\"")).isEqualTo(LogLevel.TRACE);
        assertThat(level("\"Information\"")).isEqualTo(LogLevel.INFO);
        assertThat(level("\"notice\"")).isEqualTo(LogLevel.INFO);
        assertThat(level("\"err\"")).isEqualTo(LogLevel.ERROR);
        assertThat(level("\"CRITICAL\"")).isEqualTo(LogLevel.FATAL);
        assertThat(level("\"severe\"")).isEqualTo(LogLevel.ERROR);
        assertThat(level("10")).isEqualTo(LogLevel.TRACE);
        assertThat(level("60")).isEqualTo(LogLevel.FATAL);
    }

    @Test
    void aMessageThatIsNotAStringIsShownAsJson() {
        LogEntry e = parser.parse("{\"level\":\"info\",\"message\":{\"a\":1}}", PARIS);

        assertThat(e.message()).isEqualTo("{\"level\":\"info\",\"message\":{\"a\":1}}");
        assertThat(e.fields()).containsEntry("message", "{\"a\":1}");
    }

    private LogLevel level(String json) {
        return parser.parse("{\"level\":" + json + ",\"msg\":\"x\"}", PARIS).level();
    }
}
