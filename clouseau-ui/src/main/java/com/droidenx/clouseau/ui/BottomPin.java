package com.droidenx.clouseau.ui;

import javax.swing.BoundedRangeModel;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Whether a scroll bar is held at the bottom, so follow mode knows whether to scroll to new
 * rows. Scrolling up lets go; scrolling back down to the end holds it again. Rows arriving,
 * or the view being resized, leave it as it was: the bar's maximum (or extent) changes then
 * while its value doesn't, which is what tells them apart from the user moving it.
 * {@link #release} lets go without moving (Follow turned off at the bottom).
 */
final class BottomPin {

    private final BoundedRangeModel bar;
    private final IntSupplier slack;
    private Consumer<Boolean> onChange = p -> {};
    private boolean pinned = true;
    /** Let go by {@link #release}: only the user moving the bar holds it again. */
    private boolean released;
    private int lastValue;
    private int lastMaximum;
    private int lastExtent;

    /**
     * @param slack how far from the end still counts as at the bottom (a row's height), so a
     *              last row shown all but a pixel or two doesn't let go
     */
    BottomPin(BoundedRangeModel bar, IntSupplier slack) {
        this.bar = bar;
        this.slack = slack;
        remember();
        bar.addChangeListener(e -> changed());
    }

    /** Whether the view is held at the bottom: new rows should scroll into view. */
    boolean pinned() { return pinned; }

    /** Runs with the new state whenever the view is held at the bottom or let go. */
    void onChange(Consumer<Boolean> listener) { onChange = listener != null ? listener : p -> {}; }

    /**
     * Lets go of the bottom where the view is, as if the user had scrolled up: rows arriving
     * no longer scroll it, and it holds again once the user moves it back to the end.
     */
    void release() {
        released = true;
        set(false);
    }

    /** Holds the view at the bottom (it was just scrolled there), even if the bar didn't move. */
    void hold() {
        released = false;
        set(true);
    }

    private void changed() {
        boolean moved = bar.getValue() != lastValue;
        boolean resized = bar.getMaximum() != lastMaximum || bar.getExtent() != lastExtent;
        // A value that changes with the size is the bar clamping itself (rows trimmed from the
        // top, a taller window), not the user scrolling.
        if (moved && !resized) {
            released = false;
            set(atBottom());
        } else if (!released && atBottom()) {
            set(true);   // everything fits, or the view grew to the end
        }
        remember();
    }

    private void set(boolean value) {
        if (pinned == value) return;
        pinned = value;
        onChange.accept(value);
    }

    private boolean atBottom() {
        return bar.getValue() + bar.getExtent() >= bar.getMaximum() - Math.max(0, slack.getAsInt());
    }

    private void remember() {
        lastValue = bar.getValue();
        lastMaximum = bar.getMaximum();
        lastExtent = bar.getExtent();
    }
}
