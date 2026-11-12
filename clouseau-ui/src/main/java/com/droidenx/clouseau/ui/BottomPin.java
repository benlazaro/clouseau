package com.droidenx.clouseau.ui;

import javax.swing.BoundedRangeModel;
import java.util.function.IntSupplier;

/**
 * Whether a scroll bar is held at the bottom, so follow mode knows whether to scroll to new
 * rows. Scrolling up lets go; scrolling back down to the end holds it again. Rows arriving,
 * or the view being resized, leave it as it was: the bar's maximum (or extent) changes then
 * while its value doesn't, which is what tells them apart from the user moving it.
 */
final class BottomPin {

    private final BoundedRangeModel bar;
    private final IntSupplier slack;
    private boolean pinned = true;
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

    private void changed() {
        boolean moved = bar.getValue() != lastValue;
        boolean resized = bar.getMaximum() != lastMaximum || bar.getExtent() != lastExtent;
        // A value that changes with the size is the bar clamping itself (rows trimmed from the
        // top, a taller window), not the user scrolling.
        if (moved && !resized) pinned = atBottom();
        else if (atBottom()) pinned = true;   // everything fits, or the view grew to the end
        remember();
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
