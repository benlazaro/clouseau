package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import java.awt.Color;

/**
 * The note a find bar shows for a moment when stepping goes past the last match back to the
 * first, or past the first back to the last: "↻ Back to the first match". In the current
 * match's amber, so it reads with the box it points at. Hidden otherwise, taking no room
 * (add it with {@code hidemode 3}). The find field's background pulses amber at the same
 * time, so a wrap is noticed even with the eyes on the rows. EDT only.
 */
final class WrapNotice extends JLabel {

    private static final int SHOWN_MS = 2000;
    /** The pulse: an amber tint on the find field that fades out in about 0.4 s. */
    private static final int PULSE_STEPS = 24, PULSE_FRAME_MS = 16;
    private static final float PULSE_PEAK = 0.45f;

    private final Timer hide = new Timer(SHOWN_MS, e -> setShown(false));
    /** The find field, pulsed so a wrap is seen even with the eyes on the rows. */
    private final JComponent field;
    private final Timer pulse = new Timer(PULSE_FRAME_MS, null);
    private int pulseStep;

    WrapNotice(JComponent field) {
        this.field = field;
        setVisible(false);
        setFont(getFont().deriveFont(11f));
        ClouseauColors.themedForeground(this, ClouseauColors::findCurrentBorder);
        hide.setRepeats(false);
        pulse.addActionListener(e -> pulseFrame());
    }

    /** Shows the note: forward wrapped from the last match to the first, else from the first to the last. */
    void wrapped(boolean forward) {
        setText(Messages.get(forward ? "find.wrapped.top" : "find.wrapped.bottom"));
        setShown(true);
        hide.restart();
        pulseStep = 0;
        pulse.restart();
        pulseFrame();
    }

    /** Hides the note now (the query changed or the bar closed). */
    void clear() {
        hide.stop();
        setShown(false);
        stopPulse();
    }

    /**
     * Shows or hides the note and repaints the whole bar: the note takes room from the field,
     * so the field and the counter move; repaint the bar, not just the note, so nothing is
     * left drawn where the field used to reach.
     */
    private void setShown(boolean visible) {
        if (isVisible() == visible) return;
        setVisible(visible);
        java.awt.Container bar = getParent();
        if (bar != null) {
            bar.revalidate();
            bar.repaint();
        }
    }

    /** Whether the field is pulsing; for tests. */
    boolean isPulsing() { return pulse.isRunning(); }

    /**
     * One frame: the field's background is the amber blended in, less each frame. Colors are
     * read each frame, so a theme switch mid-pulse ends in the new theme's background. The
     * end restores the theme's own background (a UIResource), so later switches keep working.
     */
    private void pulseFrame() {
        if (pulseStep >= PULSE_STEPS) { stopPulse(); return; }
        float t = PULSE_PEAK * (1f - (float) pulseStep / PULSE_STEPS);
        field.setBackground(blend(ClouseauColors.findCurrentBorder(), base(), t));
        pulseStep++;
    }

    private void stopPulse() {
        pulse.stop();
        field.setBackground(base());
    }

    private static Color base() {
        Color c = UIManager.getColor("TextField.background");
        return c != null ? c : Color.WHITE;
    }

    private static Color blend(Color a, Color b, float t) {
        return new javax.swing.plaf.ColorUIResource(
                Math.round(a.getRed()   * t + b.getRed()   * (1 - t)),
                Math.round(a.getGreen() * t + b.getGreen() * (1 - t)),
                Math.round(a.getBlue()  * t + b.getBlue()  * (1 - t)));
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
