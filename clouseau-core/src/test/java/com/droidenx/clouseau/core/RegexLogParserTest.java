package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RegexLogParserTest {

    /** Log fields often have underscores, which Java's group names don't take. */
    @Test
    void aGroupNameWithAnUnderscoreNamesItsColumn() {
        RegexLogParser p = new RegexLogParser("t", RegexLogParser.compile(
                "(?<timestamp>\\S+ \\S+) \\[(?<request_id>[^\\]]*)\\] (?<level>\\w+) (?<message>.*)"),
                "yyyy-MM-dd HH:mm:ss");

        LogEntry e = p.parse("2024-01-15 10:30:45 [r-17] WARN Slow response", ZoneOffset.UTC);

        assertThat(p.customFields()).containsExactly("request_id");
        assertThat(e.fields()).containsEntry("request_id", "r-17");
        assertThat(e.level()).isEqualTo(LogEntry.LogLevel.WARN);
        assertThat(e.message()).isEqualTo("Slow response");
    }

    @Test
    void aBackReferenceToSuchAGroupStillWorks() {
        RegexLogParser p = new RegexLogParser("t",
                RegexLogParser.compile("(?<a_b>\\w+)-\\k<a_b> (?<message>.*)"), "yyyy");

        assertThat(p.canParse("x-x hello")).isTrue();
        assertThat(p.canParse("x-y hello")).isFalse();
        assertThat(p.parse("x-x hello", ZoneOffset.UTC).fields()).containsEntry("a_b", "x");
    }

    @Test
    void aRegexWithoutSuchNamesCompilesAsWritten() {
        String regex = "(?<timestamp>\\S+) (?<userId>\\d+) (?<=\\d) (?<!x)(?<message>.*)";
        assertThat(RegexLogParser.compile(regex).pattern()).isEqualTo(regex);
    }

    @Test
    void groupNamesRoundTrip() {
        for (String field : new String[]{"request_id", "_x", "a__b", "plain", "clouseauHexabc"}) {
            String group = RegexLogParser.groupName(field);
            assertThat(group).matches("[A-Za-z][A-Za-z0-9]*");
            assertThat(RegexLogParser.fieldName(group)).isEqualTo(field);
        }
        assertThat(Pattern.compile("(?<" + RegexLogParser.groupName("trace_id") + ">x)")).isNotNull();
    }
}
