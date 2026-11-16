package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import javax.swing.DefaultBoundedRangeModel;

import static org.assertj.core.api.Assertions.assertThat;

class NewestPinTest {

    private static final int ROW = 20;

    /** A 200px view over {@code rows} rows of 20px, scrolled to {@code value}. */
    private static DefaultBoundedRangeModel bar(int rows, int value) {
        return new DefaultBoundedRangeModel(value, 200, 0, Math.max(200, rows * ROW));
    }

    @Test
    void startsPinnedAndStaysPinnedAsRowsArrive() {
        DefaultBoundedRangeModel bar = bar(5, 0);
        NewestPin pin = new NewestPin(bar, () -> ROW);
        assertThat(pin.pinned()).isTrue();

        bar.setMaximum(50 * ROW);   // rows arrive: the view is now far from the end
        assertThat(pin.pinned()).isTrue();

        bar.setValue(50 * ROW - 200);   // follow mode scrolls to them
        assertThat(pin.pinned()).isTrue();
    }

    @Test
    void scrollingUpLetsGoAndRowsArrivingDontPinAgain() {
        DefaultBoundedRangeModel bar = bar(50, 50 * ROW - 200);
        NewestPin pin = new NewestPin(bar, () -> ROW);

        bar.setValue(300);
        assertThat(pin.pinned()).isFalse();

        bar.setMaximum(80 * ROW);
        assertThat(pin.pinned()).isFalse();
    }

    @Test
    void scrollingBackToTheEndPinsAgain() {
        DefaultBoundedRangeModel bar = bar(50, 300);
        NewestPin pin = new NewestPin(bar, () -> ROW);
        bar.setValue(100);
        assertThat(pin.pinned()).isFalse();

        bar.setValue(50 * ROW - 200 - 5);   // within a row of the end counts
        assertThat(pin.pinned()).isTrue();
    }

    @Test
    void rowsTrimmedFromTheTopDontLetGo() {
        DefaultBoundedRangeModel bar = bar(50, 50 * ROW - 200);
        NewestPin pin = new NewestPin(bar, () -> ROW);

        // The entry limit drops old rows: the maximum shrinks and the value is clamped with it.
        bar.setRangeProperties(30 * ROW - 200, 200, 0, 30 * ROW, false);
        assertThat(pin.pinned()).isTrue();
    }

    @Test
    void releasingAtTheBottomStaysReleasedAsRowsArrive() {
        DefaultBoundedRangeModel bar = bar(50, 50 * ROW - 200);
        NewestPin pin = new NewestPin(bar, () -> ROW);
        java.util.List<Boolean> changes = new java.util.ArrayList<>();
        pin.onChange(changes::add);

        pin.release();   // Follow turned off at the bottom
        bar.setMaximum(51 * ROW);   // one row arrives: still within a row of the end
        assertThat(pin.pinned()).isFalse();

        bar.setValue(51 * ROW - 200);   // the user scrolls to the end
        assertThat(pin.pinned()).isTrue();
        assertThat(changes).containsExactly(false, true);
    }

    @Test
    void holdPinsWithoutTheBarMoving() {
        DefaultBoundedRangeModel bar = bar(50, 50 * ROW - 200);
        NewestPin pin = new NewestPin(bar, () -> ROW);
        pin.release();
        pin.hold();   // Follow turned on at the bottom: there's nowhere to scroll
        assertThat(pin.pinned()).isTrue();
    }

    @Test
    void aViewThatShowsEveryRowIsPinned() {
        DefaultBoundedRangeModel bar = bar(50, 100);
        NewestPin pin = new NewestPin(bar, () -> ROW);
        bar.setValue(50);
        assertThat(pin.pinned()).isFalse();

        bar.setRangeProperties(0, 200, 0, 200, false);   // filtered down to a few rows
        assertThat(pin.pinned()).isTrue();
    }

    // ── Newest first: the newest end is the top ──────────────────────────────

    @Test
    void newestFirstIsHeldAtTheTopAsRowsArrive() {
        DefaultBoundedRangeModel bar = bar(50, 0);
        NewestPin pin = new NewestPin(bar, () -> ROW, () -> true);
        assertThat(pin.pinned()).isTrue();

        bar.setMaximum(60 * ROW);   // rows arrive above: the view stays at the top
        assertThat(pin.pinned()).isTrue();
    }

    @Test
    void newestFirstLetsGoScrollingDownAndHoldsBackAtTheTop() {
        DefaultBoundedRangeModel bar = bar(50, 0);
        NewestPin pin = new NewestPin(bar, () -> ROW, () -> true);

        bar.setValue(300);
        assertThat(pin.pinned()).isFalse();

        bar.setValue(50 * ROW - 200);   // the bottom is the oldest row: not the newest end
        assertThat(pin.pinned()).isFalse();

        bar.setValue(5);   // within a row of the top counts
        assertThat(pin.pinned()).isTrue();
    }

    @Test
    void newestFirstReleasedStaysReleasedAsRowsArrive() {
        DefaultBoundedRangeModel bar = bar(50, 0);
        NewestPin pin = new NewestPin(bar, () -> ROW, () -> true);
        pin.release();   // Follow turned off at the top

        bar.setMaximum(51 * ROW);
        assertThat(pin.pinned()).isFalse();
    }
}
