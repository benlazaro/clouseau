package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The converters' errors are message keys, and the sentences name the field they're about. */
class PatternConversionErrorTest {

    @Test
    void unknownConversionWordNamesThePatternField() {
        PatternConversionException e = catchThrowableOfType(PatternConversionException.class,
                () -> LogbackPatternConverter.convert("%d{HH:mm:ss} %bogus %msg"));
        assertThat(e.key()).isEqualTo("parsereditor.error.pattern.unknown");
        assertThat(e.getMessage()).startsWith("Pattern:").contains("%bogus");
    }

    @Test
    void valueWrittenTwiceIsSaidAsSuch() {
        PatternConversionException e = catchThrowableOfType(PatternConversionException.class,
                () -> TemplatePatternConverter.convert("LEVEL LEVEL MESSAGE"));
        assertThat(e.key()).isEqualTo("parsereditor.error.template.duplicate");
        assertThat(e.getMessage()).startsWith("Template:").contains("level");
    }

    /** Log fields often have underscores; the column keeps the name as written. */
    @Test
    void fieldNameWithUnderscoreNamesItsColumn() {
        var r = TemplatePatternConverter.convert("TIMESTAMP{yyyy-MM-dd HH:mm:ss} [{request_id}] LEVEL MESSAGE");
        var parser = new com.droidenx.clouseau.core.RegexLogParser("t",
                com.droidenx.clouseau.core.RegexLogParser.compile(r.regex()), r.timestampFormat());
        var entry = parser.parse("2024-01-15 10:30:45 [r-17] INFO Started", java.time.ZoneOffset.UTC);
        assertThat(parser.customFields()).containsExactly("request_id");
        assertThat(entry.fields()).containsEntry("request_id", "r-17");
    }

    @Test
    void emptyTemplateNamesTheTemplateField() {
        PatternConversionException e = catchThrowableOfType(PatternConversionException.class,
                () -> TemplatePatternConverter.convert(" "));
        assertThat(e.getMessage()).startsWith("Template:");
    }
}
