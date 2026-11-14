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

    @Test
    void fieldNameWithUnderscoreIsRefused() {
        PatternConversionException e = catchThrowableOfType(PatternConversionException.class,
                () -> TemplatePatternConverter.convert("LEVEL {my_field} MESSAGE"));
        assertThat(e.key()).isEqualTo("parsereditor.error.template.field.name");
        assertThat(e.getMessage()).startsWith("Template:").contains("{my_field}");
    }

    @Test
    void emptyTemplateNamesTheTemplateField() {
        PatternConversionException e = catchThrowableOfType(PatternConversionException.class,
                () -> TemplatePatternConverter.convert(" "));
        assertThat(e.getMessage()).startsWith("Template:");
    }
}
