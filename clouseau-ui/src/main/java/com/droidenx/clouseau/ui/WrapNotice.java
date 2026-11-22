package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;

/**
 * The note a find bar shows for a moment when stepping goes past the last match back to the
 * first, or past the first back to the last: "↻ Back to the first match". In the current
 * match's amber, so it reads with the box it points at. Hidden otherwise, taking no room
 * (add it with {@code hidemode 3}). EDT only.
 */
final class WrapNotice extends JLabel {

    private static final int SHOWN_MS = 2000;
    private final Timer hide = new Timer(SHOWN_MS, e -> setVisible(false));

    WrapNotice() {
        setVisible(false);
        setFont(getFont().deriveFont(11f));
        ClouseauColors.themedForeground(this, ClouseauColors::findCurrentBorder);
        hide.setRepeats(false);
    }

    /** Shows the note: forward wrapped from the last match to the first, else from the first to the last. */
    void wrapped(boolean forward) {
        setText(Messages.get(forward ? "find.wrapped.top" : "find.wrapped.bottom"));
        setVisible(true);
        hide.restart();
    }

    /** Hides the note now (the query changed or the bar closed). */
    void clear() {
        hide.stop();
        setVisible(false);
    }

    /**
     * Whether stepping from match {@code from} to {@code to} by {@code direction} (+1 or -1)
     * went round the end of {@code count} matches. A single match wraps onto itself.
     */
    static boolean isWrap(int from, int to, int direction, int count) {
        if (from < 0 || count <= 0) return false;
        if (count == 1) return true;
        return direction > 0 ? to < from : to > from;
    }
}
