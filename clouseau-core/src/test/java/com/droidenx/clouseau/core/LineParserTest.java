package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LineParserTest {

    private static final LogParser SPACE = new RegexLogParser("space",
            Pattern.compile("(?<timestamp>\\S+ \\S+) (?<level>\\w+) (?<message>.*)"), "yyyy-MM-dd HH:mm:ss.SSS");
    private static final LogParser PIPE = new RegexLogParser("pipe",
            Pattern.compile("(?<level>\\w+)\\|(?<message>.*)"), "yyyy-MM-dd HH:mm:ss.SSS");

    @Test
    void autoDetectsPerLine() {
        LineParser parser = new LineParser(Optional.empty(), List.of(SPACE, PIPE), null);

        assertThat(parser.parse("2026-11-04 10:00:00.000 INFO hello").message()).isEqualTo("hello");
        assertThat(parser.parse("WARN|piped").level()).isEqualTo(LogEntry.LogLevel.WARN);
    }

    @Test
    void chosenParserIsTheOnlyOneTried() {
        LineParser parser = new LineParser(Optional.of(PIPE), List.of(SPACE, PIPE), null);

        LogEntry e = parser.parse("2026-11-04 10:00:00.000 INFO hello");

        assertThat(e.level()).isEqualTo(LogEntry.LogLevel.UNKNOWN);
    }

    @Test
    void unparsedLineKeepsItsText() {
        LineParser parser = new LineParser(Optional.empty(), List.of(SPACE), null);

        LogEntry e = parser.parse("\tat com.acme.App.main(App.java:3)");

        assertThat(e.level()).isEqualTo(LogEntry.LogLevel.UNKNOWN);
        assertThat(e.message()).isEqualTo("\tat com.acme.App.main(App.java:3)");
        assertThat(e.rawLine()).isEqualTo(e.message());
        assertThat(e.timestamp()).isNull();
    }

    @Test
    void sourceZoneRereadsTheWallTime() {
        ZoneId other = ZoneId.systemDefault().equals(ZoneId.of("Asia/Tokyo"))
                ? ZoneId.of("America/New_York") : ZoneId.of("Asia/Tokyo");
        LineParser parser = new LineParser(Optional.empty(), List.of(SPACE), other);

        Instant ts = parser.parse("2026-11-04 10:00:00.000 INFO hello").timestamp();

        assertThat(ts).isEqualTo(LocalDateTime.of(2026, 11, 4, 10, 0).atZone(other).toInstant());
    }

    @Test
    void nullZoneLeavesTimestampsAlone() {
        LineParser parser = new LineParser(Optional.empty(), List.of(SPACE), null);

        Instant ts = parser.parse("2026-11-04 10:00:00.000 INFO hello").timestamp();

        assertThat(ts).isEqualTo(LocalDateTime.of(2026, 11, 4, 10, 0).atZone(ZoneId.systemDefault()).toInstant());
    }
}
