package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LicensesDialogTest {

    @Test
    void showsClouseausLicenseThenTheNotices() {
        String text = LicensesDialog.text();

        assertThat(text).startsWith("Clouseau\n========\n\nMIT License");
        assertThat(text.indexOf("third-party notices")).isGreaterThan(text.indexOf("MIT License"));
        // The licenses that require their text to ship with the software.
        assertThat(text).contains("SIL OPEN FONT LICENSE Version 1.1")
                .contains("Apache License")
                .contains("Robert Futrell")
                .contains("Legion of the Bouncy Castle");
    }
}
