package com.droidenx.clouseau.example;

import com.droidenx.clouseau.api.LogEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogfmtParserTest {

    private final LogfmtParser parser = new LogfmtParser();

    @Test
    void recognizesLogfmtLines() {
        assertThat(parser.canParse("time=2026-03-27T08:00:00Z level=info msg=started")).isTrue();
        assertThat(parser.canParse("lvl=warn msg=\"disk low\"")).isTrue();
    }

    @Test
    void rejectsOtherFormats() {
        assertThat(parser.canParse("2026-03-27 08:00:00.001 [main] INFO com.example.App - started")).isFalse();
        assertThat(parser.canParse("{\"level\":\"info\",\"msg\":\"json\"}")).isFalse();
        assertThat(parser.canParse("errlevel=3 rawmsg=x")).isFalse();   // keys must match exactly
    }

    @Test
    void mapsStandardKeysAndKeepsTheRestAsFields() {
        String line = "time=2026-03-27T08:00:00.123Z level=warn logger=orders thread=worker-1 "
                + "msg=\"order created\" order=ORD-42 user=\"ann lee\"";

        LogEntry e = parser.parse(line);

        assertThat(e.timestamp()).isEqualTo(Instant.parse("2026-03-27T08:00:00.123Z"));
        assertThat(e.level()).isEqualTo(LogEntry.LogLevel.WARN);
        assertThat(e.logger()).isEqualTo("orders");
        assertThat(e.thread()).isEqualTo("worker-1");
        assertThat(e.message()).isEqualTo("order created");
        assertThat(e.rawLine()).isEqualTo(line);
        assertThat(e.fields()).isEqualTo(Map.of("order", "ORD-42", "user", "ann lee"));
    }

    @Test
    void handlesEscapesOffsetsAndAliases() {
        LogEntry e = parser.parse("ts=2026-03-27T10:00:00+02:00 lvl=ERR message=\"say \\\"hi\\\" C:\\\\tmp\"");

        assertThat(e.timestamp()).isEqualTo(Instant.parse("2026-03-27T08:00:00Z"));
        assertThat(e.level()).isEqualTo(LogEntry.LogLevel.ERROR);
        assertThat(e.message()).isEqualTo("say \"hi\" C:\\tmp");
        assertThat(e.fields()).isEmpty();
    }

    @Test
    void unreadableTimeIsKeptAsAField() {
        LogEntry e = parser.parse("time=yesterday level=info msg=x");

        assertThat(e.timestamp()).isNull();
        assertThat(e.fields()).containsEntry("time", "yesterday");
    }

    @Test
    void toleratesOddInput() {
        assertThat(LogfmtParser.tokenize("flag level=info msg=\"unterminated"))
                .containsEntry("flag", "")
                .containsEntry("msg", "unterminated");
        assertThat(parser.parse("level=nonsense msg=x").level()).isEqualTo(LogEntry.LogLevel.UNKNOWN);
        assertThat(LogfmtParser.tokenize("  =x  a=1 ")).containsEntry("a", "1");
    }
}
