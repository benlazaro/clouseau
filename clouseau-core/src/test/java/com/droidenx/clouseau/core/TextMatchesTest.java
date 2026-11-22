package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextMatchesTest {

    private static final String JSON = """
            {
              "orderId": "A-17",
              "items": [ { "sku": "a-17" } ]
            }""";

    @Test
    void plainTextIgnoresCaseByDefault() {
        TextMatches.Result r = TextMatches.find(JSON, "a-17", false, false);
        assertThat(r.error()).isNull();
        assertThat(r.matches()).hasSize(2);
        TextMatches.Range first = r.matches().get(0);
        assertThat(JSON.substring(first.start(), first.end())).isEqualTo("A-17");
    }

    @Test
    void matchCaseKeepsOnlyExactCase() {
        assertThat(TextMatches.find(JSON, "a-17", true, false).matches()).hasSize(1);
    }

    @Test
    void plainTextTreatsRegexCharactersLiterally() {
        assertThat(TextMatches.find("a.b axb", "a.b", false, false).matches()).hasSize(1);
        assertThat(TextMatches.find("a.b axb", "a.b", false, true).matches()).hasSize(2);
    }

    @Test
    void regexFindsHl7Fields() {
        String hl7 = "MSH|^~\\&|LAB\nPID|1||12345^^^MRN||DOE^JOHN\nOBX|1|NM|GLU";
        TextMatches.Result r = TextMatches.find(hl7, "^PID\\|", false, true);
        assertThat(r.matches()).hasSize(1);
        assertThat(r.matches().get(0).start()).isEqualTo(hl7.indexOf("PID"));
    }

    @Test
    void matchesDoNotOverlap() {
        assertThat(TextMatches.find("aaaa", "aa", false, false).matches())
                .containsExactly(new TextMatches.Range(0, 2), new TextMatches.Range(2, 4));
    }

    @Test
    void emptyMatchesAreSkipped() {
        assertThat(TextMatches.find("abc", "x*", false, true).matches()).isEmpty();
        assertThat(TextMatches.find("abc\ndef", "^", false, true).matches()).isEmpty();
    }

    @Test
    void invalidRegexIsAnErrorNotAnException() {
        TextMatches.Result r = TextMatches.find("abc", "(", false, true);
        assertThat(r.matches()).isEmpty();
        assertThat(r.error()).isNotBlank();
    }

    @Test
    void emptyQueryOrTextFindsNothing() {
        assertThat(TextMatches.find("abc", "", false, false)).isEqualTo(TextMatches.Result.NONE);
        assertThat(TextMatches.find("", "a", false, false)).isEqualTo(TextMatches.Result.NONE);
        assertThat(TextMatches.find(null, "a", false, false)).isEqualTo(TextMatches.Result.NONE);
    }

    @Test
    void stopsAtTheLimit() {
        String many = "a".repeat(TextMatches.MAX_MATCHES + 50);
        assertThat(TextMatches.find(many, "a", false, false).matches()).hasSize(TextMatches.MAX_MATCHES);
    }
}
