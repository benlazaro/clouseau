package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import java.awt.event.InputEvent;

import static org.assertj.core.api.Assertions.assertThat;

class KeysTest {

    @Test
    void headlessTheShortcutKeyIsCtrl() {
        assertThat(Keys.MENU).isEqualTo(InputEvent.CTRL_DOWN_MASK);
        assertThat(Keys.label("Duplicate (Ctrl+D)")).isEqualTo("Duplicate (Ctrl+D)");
    }

    @Test
    void onMacOsCtrlReadsCmd() {
        assertThat(Keys.label("Duplicate (Ctrl+D)", true)).isEqualTo("Duplicate (Cmd+D)");
        assertThat(Keys.label("Text size (Ctrl+Plus, Ctrl+Minus, or Ctrl+wheel)", true))
                .isEqualTo("Text size (Cmd+Plus, Cmd+Minus, or Cmd+wheel)");
        assertThat(Keys.label("Select a file, or several with Ctrl or Shift.", true))
                .isEqualTo("Select a file, or several with Cmd or Shift.");
        assertThat(Keys.label("Ctrl+1…8", true)).isEqualTo("Cmd+1…8");
    }

    @Test
    void tabSwitchingStaysCtrlOnMacOs() {
        assertThat(Keys.label("Ctrl+Tab", true)).isEqualTo("Ctrl+Tab");
        assertThat(Keys.label("Ctrl+Shift+Tab", true)).isEqualTo("Ctrl+Shift+Tab");
        assertThat(Keys.label("Ctrl+PgDn", true)).isEqualTo("Ctrl+PgDn");
        assertThat(Keys.label("Ctrl+PageUp", true)).isEqualTo("Ctrl+PageUp");
    }

    @Test
    void textWithoutCtrlIsUnchanged() {
        assertThat(Keys.label("Controller started", true)).isEqualTo("Controller started");
        assertThat(Keys.label(null, true)).isNull();
    }
}
