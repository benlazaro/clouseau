package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import static java.awt.event.InputEvent.*;
import static java.awt.event.KeyEvent.*;
import static org.assertj.core.api.Assertions.assertThat;

class TabShortcutsTest {

    private static int target(int key, int mods, int selected, int count) {
        return TabShortcuts.targetIndex(key, mods, selected, count);
    }

    @Test
    void ctrlTabAndCtrlPageDownGoToNextTabAndWrap() {
        assertThat(target(VK_TAB, CTRL_DOWN_MASK, 0, 3)).isEqualTo(1);
        assertThat(target(VK_TAB, CTRL_DOWN_MASK, 2, 3)).isEqualTo(0);
        assertThat(target(VK_PAGE_DOWN, CTRL_DOWN_MASK, 1, 3)).isEqualTo(2);
        assertThat(target(VK_PAGE_DOWN, CTRL_DOWN_MASK, 2, 3)).isEqualTo(0);
    }

    @Test
    void ctrlShiftTabAndCtrlPageUpGoToPreviousTabAndWrap() {
        assertThat(target(VK_TAB, CTRL_DOWN_MASK | SHIFT_DOWN_MASK, 1, 3)).isEqualTo(0);
        assertThat(target(VK_TAB, CTRL_DOWN_MASK | SHIFT_DOWN_MASK, 0, 3)).isEqualTo(2);
        assertThat(target(VK_PAGE_UP, CTRL_DOWN_MASK, 2, 3)).isEqualTo(1);
        assertThat(target(VK_PAGE_UP, CTRL_DOWN_MASK, 0, 3)).isEqualTo(2);
    }

    @Test
    void singleTabStaysSelectedButKeyIsStillHandled() {
        assertThat(target(VK_TAB, CTRL_DOWN_MASK, 0, 1)).isZero();
        assertThat(target(VK_PAGE_UP, CTRL_DOWN_MASK, 0, 1)).isZero();
    }

    @Test
    void ctrlDigitJumpsToTabAndCtrl9ToLast() {
        assertThat(target(VK_1, CTRL_DOWN_MASK, 2, 5)).isEqualTo(0);
        assertThat(target(VK_4, CTRL_DOWN_MASK, 0, 5)).isEqualTo(3);
        assertThat(target(VK_8, CTRL_DOWN_MASK, 0, 10)).isEqualTo(7);
        assertThat(target(VK_9, CTRL_DOWN_MASK, 0, 5)).isEqualTo(4);
        assertThat(target(VK_9, CTRL_DOWN_MASK, 0, 12)).isEqualTo(11);
    }

    @Test
    void ctrlDigitBeyondLastTabIsNotHandled() {
        assertThat(target(VK_6, CTRL_DOWN_MASK, 0, 5)).isEqualTo(-1);
    }

    @Test
    void noTabsMeansNothingIsHandled() {
        assertThat(target(VK_TAB, CTRL_DOWN_MASK, -1, 0)).isEqualTo(-1);
        assertThat(target(VK_9, CTRL_DOWN_MASK, -1, 0)).isEqualTo(-1);
    }

    @Test
    void noSelectionGoesToFirstOrLast() {
        assertThat(target(VK_TAB, CTRL_DOWN_MASK, -1, 3)).isZero();
        assertThat(target(VK_PAGE_UP, CTRL_DOWN_MASK, -1, 3)).isEqualTo(2);
    }

    @Test
    void otherKeysAndModifierCombinationsAreLeftAlone() {
        assertThat(target(VK_TAB, 0, 0, 3)).isEqualTo(-1);                       // plain Tab: focus
        assertThat(target(VK_TAB, SHIFT_DOWN_MASK, 0, 3)).isEqualTo(-1);         // Shift+Tab: focus
        assertThat(target(VK_PAGE_DOWN, 0, 0, 3)).isEqualTo(-1);
        assertThat(target(VK_1, 0, 0, 3)).isEqualTo(-1);
        assertThat(target(VK_1, CTRL_DOWN_MASK | ALT_DOWN_MASK, 0, 3)).isEqualTo(-1);
        assertThat(target(VK_PAGE_DOWN, CTRL_DOWN_MASK | SHIFT_DOWN_MASK, 0, 3)).isEqualTo(-1);
        assertThat(target(VK_TAB, ALT_DOWN_MASK, 0, 3)).isEqualTo(-1);
        assertThat(target(VK_0, CTRL_DOWN_MASK, 0, 3)).isEqualTo(-1);
        assertThat(target(VK_W, CTRL_DOWN_MASK, 0, 3)).isEqualTo(-1);
    }

    @Test
    void mouseButtonModifiersDoNotMatter() {
        assertThat(target(VK_TAB, CTRL_DOWN_MASK | BUTTON1_DOWN_MASK, 0, 3)).isEqualTo(1);
    }
}
