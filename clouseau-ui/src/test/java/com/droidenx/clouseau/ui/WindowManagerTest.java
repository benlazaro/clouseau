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

    // ── Restoring a session's windows (UC-17) ─────────────────────────────────

    @Test
    void aRestoredWindowOnAConnectedMonitorGoesBackThere() {
        Rectangle b = new Rectangle(2200, 100, 1600, 1000);
        assertThat(WindowManager.placeRestored(b, List.of(LAPTOP, MONITOR), 0)).isEqualTo(b);
    }

    @Test
    void aWindowOnAMissingMonitorOpensOnThePrimaryScreen() {
        Rectangle b = new Rectangle(2200, 100, 1600, 1000);
        Rectangle at = WindowManager.placeRestored(b, List.of(LAPTOP), 0);
        assertThat(LAPTOP.contains(at)).isTrue();
        assertThat(at.getSize()).isEqualTo(b.getSize());
    }

    @Test
    void windowsMovedOffAMissingMonitorAreCascaded() {
        Rectangle b = new Rectangle(2200, 100, 1200, 700);
        Rectangle first = WindowManager.placeRestored(b, List.of(LAPTOP), 0);
        Rectangle second = WindowManager.placeRestored(b, List.of(LAPTOP), 1);
        assertThat(second.x - first.x).isEqualTo(WindowManager.CASCADE);
        assertThat(second.y - first.y).isEqualTo(WindowManager.CASCADE);
    }

    @Test
    void aWindowWithOnlyAnEdgeOnScreenCountsAsOffScreen() {
        // 50 px of its title bar on the laptop: too little to grab.
        Rectangle b = new Rectangle(1870, 100, 1200, 700);
        assertThat(WindowManager.isReachable(b, List.of(LAPTOP))).isFalse();
        assertThat(WindowManager.isReachable(new Rectangle(1700, 100, 1200, 700), List.of(LAPTOP))).isTrue();
    }

    @Test
    void aWindowWhoseTitleBarIsAboveTheScreenIsMoved() {
        Rectangle b = new Rectangle(100, -500, 1200, 700);
        assertThat(WindowManager.isReachable(b, List.of(LAPTOP))).isFalse();
        assertThat(LAPTOP.contains(WindowManager.placeRestored(b, List.of(LAPTOP), 0))).isTrue();
    }

    @Test
    void aReachableWindowTooBigForItsScreenIsShrunkOntoIt() {
        Rectangle b = new Rectangle(100, 100, 2560, 1400);
        assertThat(WindowManager.placeRestored(b, List.of(LAPTOP, MONITOR), 0))
                .isEqualTo(new Rectangle(0, 0, 1920, 1040));
    }
}
