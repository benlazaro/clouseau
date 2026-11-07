package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Window placement arithmetic; no display needed. */
class WindowManagerTest {

    private static final Rectangle LAPTOP  = new Rectangle(0, 0, 1920, 1040);
    private static final Rectangle MONITOR = new Rectangle(1920, 0, 2560, 1400);

    @Test
    void aWindowThatFitsStaysWhereItIs() {
        Rectangle b = new Rectangle(100, 100, 1280, 800);
        assertThat(WindowManager.fitOnScreen(b, List.of(LAPTOP, MONITOR))).isEqualTo(b);
    }

    @Test
    void aWindowOnTheSecondScreenStaysThere() {
        Rectangle b = new Rectangle(2000, 50, 1280, 800);
        assertThat(WindowManager.fitOnScreen(b, List.of(LAPTOP, MONITOR))).isEqualTo(b);
    }

    @Test
    void aCascadedWindowPastTheEdgeIsPulledBackOnScreen() {
        Rectangle b = new Rectangle(1000, 600, 1280, 800);
        assertThat(WindowManager.fitOnScreen(b, List.of(LAPTOP)))
                .isEqualTo(new Rectangle(640, 240, 1280, 800));
    }

    @Test
    void aWindowLargerThanTheScreenIsShrunk() {
        Rectangle b = new Rectangle(10, 10, 2560, 1400);
        assertThat(WindowManager.fitOnScreen(b, List.of(LAPTOP)))
                .isEqualTo(new Rectangle(0, 0, 1920, 1040));
    }

    @Test
    void aWindowOffEveryScreenGoesToTheFirst() {
        Rectangle b = new Rectangle(-3000, 200, 1280, 800);
        assertThat(WindowManager.fitOnScreen(b, List.of(LAPTOP, MONITOR)))
                .isEqualTo(new Rectangle(0, 200, 1280, 800));
    }

    @Test
    void withNoScreensTheBoundsAreKept() {
        Rectangle b = new Rectangle(5, 5, 100, 100);
        assertThat(WindowManager.fitOnScreen(b, List.of())).isEqualTo(b);
    }
}
