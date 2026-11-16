package com.droidenx.clouseau.ui;

import javax.swing.BoundedRangeModel;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Whether a scroll bar is held at the newest end, so follow mode knows whether to scroll to
 * new rows. The newest end is the bottom, or the top when the table is sorted newest first.
 * Scrolling away lets go; scrolling back to that end holds it again. Rows arriving, or the
 * view being resized, leave it as it was: the bar's maximum (or extent) changes then while
 * its value doesn't, which is what tells them apart from the user moving it.
 * {@link #release} lets go without moving (Follow turned off at the newest end).
 */
final class NewestPin {

    private final BoundedRangeModel bar;
    private final IntSupplier slack;
    private final BooleanSupplier newestAtTop;
    private Consumer<Boolean> onChange = p -> {};
    private boolean pinned = true;
    /** Let go by {@link #release}: only the user moving the bar holds it again. */
    private boolean released;
    private int lastValue;
    private int lastMaximum;
    private int lastExtent;

    /** A view whose newest row is always at the bottom. */
    NewestPin(BoundedRangeModel bar, IntSupplier slack) {
        this(bar, slack, () -> false);
    }

    /**
     * @param slack       how far from the end still counts as at it (a row's height), so a
     *                    last row shown all but a pixel or two doesn't let go
     * @param newestAtTop whether the newest row is at the top now (sorted newest first)
     */
    NewestPin(BoundedRangeModel bar, IntSupplier slack, BooleanSupplier newestAtTop) {
        this.bar = bar;
        this.slack = slack;
        this.newestAtTop = newestAtTop;
        remember();
        bar.addChangeListener(e -> changed());
    }

    /** Whether the view is held at the newest end: new rows should scroll into view. */
    boolean pinned() { return pinned; }

    /** Runs with the new state whenever the view is held at the newest end or let go. */
    void onChange(Consumer<Boolean> listener) { onChange = listener != null ? listener : p -> {}; }

    /**
     * Lets go of the newest end where the view is, as if the user had scrolled away: rows
     * arriving no longer scroll it, and it holds again once the user moves it back.
     */
    void release() {
        released = true;
        set(false);
    }

    /** Holds the view at the newest end (it was just scrolled there), even if the bar didn't move. */
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
            set(atNewest());
        } else if (!released && atNewest()) {
            set(true);   // everything fits, or the view grew to the end
        }
        remember();
    }

    private void set(boolean value) {
        if (pinned == value) return;
        pinned = value;
        onChange.accept(value);
    }

    private boolean atNewest() {
        int s = Math.max(0, slack.getAsInt());
        return newestAtTop.getAsBoolean()
                ? bar.getValue() <= bar.getMinimum() + s
                : bar.getValue() + bar.getExtent() >= bar.getMaximum() - s;
    }

    private void remember() {
        lastValue = bar.getValue();
        lastMaximum = bar.getMaximum();
        lastExtent = bar.getExtent();
    }
}
