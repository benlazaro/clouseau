package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompactTimeTest {

    @Test
    void digitsAreFormattedDateFirstAsTheyAreTyped() {
        assertThat(CompactTime.mask("20261123030700")).isEqualTo("2026-11-23 03:07:00");
        assertThat(CompactTime.mask("20261123030700123")).isEqualTo("2026-11-23 03:07:00.123");
        assertThat(CompactTime.mask("20261123")).isEqualTo("2026-11-23");
        assertThat(CompactTime.mask("202611230307")).isEqualTo("2026-11-23 03:07");
        // A separator goes in only once a digit follows it.
        assertThat(CompactTime.mask("2026")).isEqualTo("2026");
        assertThat(CompactTime.mask("20261")).isEqualTo("2026-1");
        assertThat(CompactTime.mask("202611")).isEqualTo("2026-11");
        assertThat(CompactTime.mask("2026-11-")).isEqualTo("2026-11");
        assertThat(CompactTime.mask("2026-11-2")).isEqualTo("2026-11-2");
        assertThat(CompactTime.mask("2026-11-230")).isEqualTo("2026-11-23 0");
    }

    @Test
    void separatorsTypedOrPastedInOtherPlacesAreNormalized() {
        assertThat(CompactTime.mask("20261123 030700")).isEqualTo("2026-11-23 03:07:00");
        assertThat(CompactTime.mask("20261123T030700")).isEqualTo("2026-11-23 03:07:00");
        assertThat(CompactTime.mask("2026-11-23T03:07:00,250")).isEqualTo("2026-11-23 03:07:00.250");
        assertThat(CompactTime.mask("2026-11-23 03:07:00.250")).isEqualTo("2026-11-23 03:07:00.250");
    }

    @Test
    void whatIsNotDateFirstIsLeftAsTyped() {
        assertThat(CompactTime.mask("10:00")).isNull();
        assertThat(CompactTime.mask("10:00:05.250")).isNull();
        assertThat(CompactTime.mask("+5m")).isNull();
        assertThat(CompactTime.mask("-90s")).isNull();
        assertThat(CompactTime.mask("")).isNull();
        assertThat(CompactTime.mask("202")).isNull();
        assertThat(CompactTime.mask("202611230307001234")).isNull();    // 18 digits
        assertThat(CompactTime.mask("2026-11-23T03:07:00Z")).isNull();
        assertThat(CompactTime.mask("Nov 23")).isNull();
    }

    @Test
    void anEditInsideAFormattedValueIsFormattedAgain() {
        assertThat(CompactTime.reformat("2026-11-23", "202-11-23")).isEqualTo("2021-12-3");
        assertThat(CompactTime.reformat("2026-11-23", "2026-11-2:3")).isEqualTo("2026-11-23");
        assertThat(CompactTime.reformat("2026", "202")).isEqualTo("202");
        assertThat(CompactTime.reformat("", "20261123")).isEqualTo("2026-11-23");
        // Not formatted before, or not only digits after: left as typed.
        assertThat(CompactTime.reformat("10:0", "10:00")).isNull();
        assertThat(CompactTime.reformat("2026-11-23", "2026-11-23x")).isNull();
        assertThat(CompactTime.reformat("2026-11-23 03:07:00.123", "2026-11-23 03:07:00.1234")).isNull();
    }

    @Test
    void theCaretStaysAfterTheSameDigits() {
        String masked = "2026-11-23 03:07";
        assertThat(CompactTime.caretAfterDigits(masked, 0)).isZero();
        assertThat(CompactTime.caretAfterDigits(masked, 4)).isEqualTo(4);
        assertThat(CompactTime.caretAfterDigits(masked, 5)).isEqualTo(6);
        assertThat(CompactTime.caretAfterDigits(masked, 9)).isEqualTo(12);
        assertThat(CompactTime.caretAfterDigits(masked, 99)).isEqualTo(masked.length());
    }

    @Test
    void theStartFillsWithTheEarliestInstantAndTheEndWithTheLast() {
        assertThat(CompactTime.complete("2026-11-23", false)).isEqualTo("2026-11-23 00:00:00.000");
        assertThat(CompactTime.complete("2026-11-23", true)).isEqualTo("2026-11-23 23:59:59.999");
        assertThat(CompactTime.complete("2026-02", false)).isEqualTo("2026-02-01 00:00:00.000");
        assertThat(CompactTime.complete("2026-02", true)).isEqualTo("2026-02-28 23:59:59.999");
        assertThat(CompactTime.complete("2028-02", true)).isEqualTo("2028-02-29 23:59:59.999");
        assertThat(CompactTime.complete("2026-11-23 03", false)).isEqualTo("2026-11-23 03:00:00.000");
        assertThat(CompactTime.complete("2026-11-23 03", true)).isEqualTo("2026-11-23 03:59:59.999");
        assertThat(CompactTime.complete("2026-11-23 03:07", false)).isEqualTo("2026-11-23 03:07:00.000");
        assertThat(CompactTime.complete("2026-11-23 03:07", true)).isEqualTo("2026-11-23 03:07:59.999");
        assertThat(CompactTime.complete("2026-11-23 03:07:00", true)).isEqualTo("2026-11-23 03:07:00.999");
        assertThat(CompactTime.complete("2026-11-23 03:07:00.1", false)).isEqualTo("2026-11-23 03:07:00.100");
        assertThat(CompactTime.complete("2026-11-23 03:07:00.1", true)).isEqualTo("2026-11-23 03:07:00.199");
        assertThat(CompactTime.complete("2026-11-23 03:07:00.250", true)).isEqualTo("2026-11-23 03:07:00.250");
    }

    @Test
    void aTimeAloneStaysATimeAlone() {
        assertThat(CompactTime.complete("10:15", false)).isEqualTo("10:15:00.000");
        assertThat(CompactTime.complete("10:15", true)).isEqualTo("10:15:59.999");
        assertThat(CompactTime.complete("10:15:30", true)).isEqualTo("10:15:30.999");
        assertThat(CompactTime.complete("10:15:30.5", false)).isEqualTo("10:15:30.500");
    }

    @Test
    void onlyWholeFieldsAndRealTimesComplete() {
        assertThat(CompactTime.complete("2026", false)).isNull();
        assertThat(CompactTime.complete("2026-1", false)).isNull();
        assertThat(CompactTime.complete("2026-11-2", false)).isNull();
        assertThat(CompactTime.complete("2026-11-23 0", false)).isNull();
        assertThat(CompactTime.complete("2026-13", false)).isNull();
        assertThat(CompactTime.complete("2026-02-30", false)).isNull();
        assertThat(CompactTime.complete("2026-11-23 24", false)).isNull();
        assertThat(CompactTime.complete("2026-11-23 03:60", false)).isNull();
        assertThat(CompactTime.complete("25:00", false)).isNull();
        assertThat(CompactTime.complete("+5m", true)).isNull();
        assertThat(CompactTime.complete("", true)).isNull();
    }
}
