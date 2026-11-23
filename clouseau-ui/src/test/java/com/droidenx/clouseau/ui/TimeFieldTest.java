package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;

import static org.assertj.core.api.Assertions.assertThat;

/** The Time popup's From and To fields: live formatting and the dimmed completion. */
class TimeFieldTest {

    private static void onEdt(ThrowingRunnable r) throws Exception {
        Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try { r.run(); } catch (Exception e) { failure[0] = e; }
        });
        if (failure[0] != null) throw failure[0];
    }

    interface ThrowingRunnable { void run() throws Exception; }

    /** Types {@code text} a character at a time at the caret, as the keyboard does. */
    private static void type(JTextField field, String text) {
        for (char c : text.toCharArray()) field.replaceSelection(String.valueOf(c));
    }

    @Test
    void digitsAreFormattedAsTheyAreTyped() throws Exception {
        onEdt(() -> {
            TimeField field = new TimeField("", false);
            type(field, "2026");
            assertThat(field.getText()).isEqualTo("2026");
            type(field, "1");
            assertThat(field.getText()).isEqualTo("2026-1");
            type(field, "1230307");
            assertThat(field.getText()).isEqualTo("2026-11-23 03:07");
            assertThat(field.getCaretPosition()).isEqualTo(field.getText().length());
            type(field, "00");
            assertThat(field.getText()).isEqualTo("2026-11-23 03:07:00");
        });
    }

    @Test
    void aPasteIsFormattedAtOnce() throws Exception {
        onEdt(() -> {
            TimeField field = new TimeField("", false);
            field.replaceSelection("20261123030700");
            assertThat(field.getText()).isEqualTo("2026-11-23 03:07:00");

            field.selectAll();
            field.replaceSelection("20261123T030700");
            assertThat(field.getText()).isEqualTo("2026-11-23 03:07:00");
        });
    }

    @Test
    void timesAloneAndRelativeEndsAreLeftAsTyped() throws Exception {
        onEdt(() -> {
            TimeField field = new TimeField("", true);
            type(field, "10:15");
            assertThat(field.getText()).isEqualTo("10:15");
            field.setText("");
            type(field, "+5m");
            assertThat(field.getText()).isEqualTo("+5m");
        });
    }

    @Test
    void backspaceOverASeparatorTakesTheDigitBeforeIt() throws Exception {
        onEdt(() -> {
            TimeField field = new TimeField("", false);
            field.replaceSelection("202611");          // 2026-11
            field.setCaretPosition(5);                 // after the '-'
            field.getDocument().remove(4, 1);          // what Backspace does there
            assertThat(field.getText()).isEqualTo("2021-1");
            assertThat(field.getCaretPosition()).isEqualTo(3);
        });
    }

    @Test
    void editingInTheMiddleKeepsTheCaretAfterTheSameDigit() throws Exception {
        onEdt(() -> {
            TimeField field = new TimeField("", false);
            field.replaceSelection("2026-11-23");
            field.setCaretPosition(4);                 // before the '-'
            field.getDocument().remove(3, 1);          // Backspace deletes the 6
            assertThat(field.getText()).isEqualTo("2021-12-3");
            assertThat(field.getCaretPosition()).isEqualTo(3);
        });
    }

    @Test
    void theStartCompletesWithTheEarliestInstantAndTheEndWithTheLast() throws Exception {
        onEdt(() -> {
            TimeField from = new TimeField("", false);
            TimeField to = new TimeField("", true);
            type(from, "20261123");
            type(to, "20261123");
            assertThat(from.ghost()).isEqualTo(" 00:00:00.000");
            assertThat(to.ghost()).isEqualTo(" 23:59:59.999");
            assertThat(to.effectiveText()).isEqualTo("2026-11-23 23:59:59.999");

            from.complete();
            to.complete();
            assertThat(from.getText()).isEqualTo("2026-11-23 00:00:00.000");
            assertThat(to.getText()).isEqualTo("2026-11-23 23:59:59.999");
            assertThat(to.ghost()).isNull();
        });
    }

    @Test
    void halfTypedFieldsHaveNoCompletion() throws Exception {
        onEdt(() -> {
            TimeField field = new TimeField("", false);
            type(field, "2026112");                    // 2026-11-2
            assertThat(field.ghost()).isNull();
            assertThat(field.effectiveText()).isEqualTo("2026-11-2");
            field.complete();
            assertThat(field.getText()).isEqualTo("2026-11-2");
        });
    }
}
