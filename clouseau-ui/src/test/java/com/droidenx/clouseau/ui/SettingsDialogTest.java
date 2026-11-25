package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsDialogTest {

    @Test
    void aHintKeepsAWordWithAnApostropheWhole() {
        assertThat(SettingsDialog.wrapTo("looks in the IDE’s open projects", 300))
                .isEqualTo("<html><body style='width: 300px'>looks in the <nobr>IDE’s</nobr> open projects</body></html>");
    }

    @Test
    void aHintEscapesMarkup() {
        assertThat(SettingsDialog.wrapTo("a <b> & c", 100))
                .isEqualTo("<html><body style='width: 100px'>a &lt;b&gt; &amp; c</body></html>");
    }
}
