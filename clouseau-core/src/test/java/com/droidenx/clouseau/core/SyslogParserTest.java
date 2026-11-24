package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SyslogParserTest {

    private final SyslogParser parser = new SyslogParser();
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void acceptsOnlyLinesWithAValidPriority() {
        assertThat(parser.canParse("<34>1 2003-10-11T22:14:15.003Z host su - ID47 - hi")).isTrue();
        assertThat(parser.canParse("<0>Oct 11 22:14:15 host app: hi")).isTrue();
        assertThat(parser.canParse("<191>x")).isTrue();
        assertThat(parser.canParse("<192>x")).isFalse();
        assertThat(parser.canParse("<034>x")).isFalse();
        assertThat(parser.canParse("Oct 11 22:14:15 host app: hi")).isFalse();
        assertThat(parser.canParse("<html>")).isFalse();
        assertThat(parser.canParse(null)).isFalse();
    }

    @Test
    void readsRfc5424() {
        LogEntry e = parser.parse("<34>1 2003-10-11T22:14:15.003Z mymachine.example.com su 1234 ID47 - 'su root' failed", UTC);

        assertThat(e.timestamp()).isEqualTo(Instant.parse("2003-10-11T22:14:15.003Z"));
        assertThat(e.level()).isEqualTo(LogLevel.FATAL);   // severity 2, critical
        assertThat(e.logger()).isEqualTo("su");
        assertThat(e.thread()).isEqualTo("1234");
        assertThat(e.message()).isEqualTo("'su root' failed");
        assertThat(e.fields()).containsEntry(SyslogParser.HOST, "mymachine.example.com")
                .containsEntry(SyslogParser.FACILITY, "auth")
                .containsEntry(SyslogParser.MSGID, "ID47")
                .doesNotContainKey(SyslogParser.STRUCTURED_DATA);
    }

    @Test
    void readsRfc5424StructuredDataAndNilValues() {
        LogEntry e = parser.parse("<165>1 2003-10-11T22:14:15.003-07:00 host evntslog - - "
                + "[exampleSDID@32473 iut=\"3\" eventSource=\"App\\]lication\"][x@1 a=\"b\"] An application event", UTC);

        assertThat(e.level()).isEqualTo(LogLevel.INFO);    // notice
        assertThat(e.timestamp()).isEqualTo(Instant.parse("2003-10-12T05:14:15.003Z"));
        assertThat(e.thread()).isEmpty();
        assertThat(e.message()).isEqualTo("An application event");
        assertThat(e.fields()).containsEntry(SyslogParser.FACILITY, "local4")
                .containsEntry(SyslogParser.STRUCTURED_DATA,
                        "[exampleSDID@32473 iut=\"3\" eventSource=\"App\\]lication\"][x@1 a=\"b\"]")
                .doesNotContainKey(SyslogParser.MSGID);
    }

    @Test
    void readsRfc5424WithoutAMessage() {
        LogEntry e = parser.parse("<14>1 2024-01-01T00:00:00Z h app - - -", UTC);

        assertThat(e.message()).isEmpty();
        assertThat(e.level()).isEqualTo(LogLevel.INFO);
    }

    @Test
    void readsBsdSyslogWithHostAndPid() {
        LogEntry e = parser.parse("<11>Feb  5 17:32:18 web01 nginx[812]: upstream timed out", UTC);

        ZonedDateTime t = e.timestamp().atZone(UTC);
        assertThat(t.getMonthValue()).isEqualTo(2);
        assertThat(t.getDayOfMonth()).isEqualTo(5);
        assertThat(t.toLocalTime()).hasToString("17:32:18");
        assertThat(e.level()).isEqualTo(LogLevel.ERROR);
        assertThat(e.logger()).isEqualTo("nginx");
        assertThat(e.thread()).isEqualTo("812");
        assertThat(e.message()).isEqualTo("upstream timed out");
        assertThat(e.fields()).containsEntry(SyslogParser.HOST, "web01").containsEntry(SyslogParser.FACILITY, "user");
    }

    @Test
    void readsBsdSyslogWithoutAHost() {
        LogEntry e = parser.parse("<13>Oct 11 22:14:15 app: hello", UTC);

        assertThat(e.logger()).isEqualTo("app");
        assertThat(e.message()).isEqualTo("hello");
        assertThat(e.fields()).doesNotContainKey(SyslogParser.HOST);
    }

    @Test
    void aBsdTimeIsNeverMoreThanADayAhead() {
        ZonedDateTime now = ZonedDateTime.now(UTC);
        LogEntry e = parser.parse("<13>" + String.format(java.util.Locale.ENGLISH, "%tb %2d %tT",
                now, now.getDayOfMonth(), now) + " host app: now", UTC);

        assertThat(e.timestamp()).isBetween(now.minusMinutes(1).toInstant(), now.plusMinutes(1).toInstant());
        assertThat(parser.parse("<13>Dec 31 23:59:59 host app: x", UTC).timestamp())
                .isBefore(now.plusDays(1).toInstant());
    }

    @Test
    void readsAnIsoTimeInPlaceOfTheBsdOne() {
        LogEntry e = parser.parse("<30>2024-05-01T12:00:00.250+02:00 host systemd[1]: Started foo.", UTC);

        assertThat(e.timestamp()).isEqualTo(Instant.parse("2024-05-01T10:00:00.250Z"));
        assertThat(e.logger()).isEqualTo("systemd");
        assertThat(e.fields()).containsEntry(SyslogParser.FACILITY, "daemon").containsEntry(SyslogParser.HOST, "host");
    }

    @Test
    void mapsEverySeverity() {
        assertThat(SyslogParser.level(0)).isEqualTo(LogLevel.FATAL);
        assertThat(SyslogParser.level(3)).isEqualTo(LogLevel.ERROR);
        assertThat(SyslogParser.level(4)).isEqualTo(LogLevel.WARN);
        assertThat(SyslogParser.level(5)).isEqualTo(LogLevel.INFO);
        assertThat(SyslogParser.level(6)).isEqualTo(LogLevel.INFO);
        assertThat(SyslogParser.level(7)).isEqualTo(LogLevel.DEBUG);
    }

    @Test
    void aLineWithOnlyAPriorityKeepsItsText() {
        LogEntry e = parser.parse("<14>just text", UTC);

        assertThat(e.message()).isEqualTo("just text");
        assertThat(e.level()).isEqualTo(LogLevel.INFO);
        assertThat(e.timestamp()).isNull();
    }
}
