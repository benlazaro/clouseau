package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import java.awt.*;

/**
 * A find field's "nothing matches" look: a red outline and a light red tint, kept for as
 * long as the query finds nothing (or is an invalid pattern), so the answer is where you're
 * typing rather than in a counter across the bar. Turning red pulses once, a stronger tint
 * fading into the resting one, so the change is noticed; staying red (typing on, moving to
 * another entry that doesn't match either) doesn't pulse again.
 * <p>
 * The tint is the theme's error color blended into the field's background, worked out again
 * on a theme switch. EDT only.
 */
final class NoMatchState {

    private static final float REST = 0.14f, PEAK = 0.55f;
    private static final int PULSE_STEPS = 24, FRAME_MS = 16;     // about 0.4 s

    private final JTextField field;
    private final Timer pulse = new Timer(FRAME_MS, null);
    private boolean noMatch;
    private int step;

    NoMatchState(JTextField field) {
        this.field = field;
        pulse.addActionListener(e -> frame());
        ClouseauColors.onThemeChange(field, this::paintState);
    }

    /** Sets whether nothing matches; turning red pulses once. */
    void set(boolean noMatch) {
        if (this.noMatch == noMatch) return;
        this.noMatch = noMatch;
        field.putClientProperty("JComponent.outline", noMatch ? "error" : null);
        if (noMatch) {
            step = 0;
            pulse.restart();
            frame();
        } else {
            pulse.stop();
            paintState();
        }
    }

    boolean isNoMatch() { return noMatch; }

    /** Whether the pulse is running; for tests. */
    boolean isPulsing() { return pulse.isRunning(); }

    private void frame() {
        if (step >= PULSE_STEPS) { pulse.stop(); paintState(); return; }
        float t = REST + (PEAK - REST) * (1f - (float) step / PULSE_STEPS);
        field.setBackground(tint(t));
        step++;
    }

    /** The resting look for the state: tinted while nothing matches, else the theme's own. */
    private void paintState() {
        if (pulse.isRunning()) return;
        field.setBackground(noMatch ? tint(REST) : base());
    }

    /** The error color blended {@code t} into the field's background. Not a UIResource, so it stays put. */
    private static Color tint(float t) {
        Color a = ClouseauColors.statusError(), b = base();
        return new Color(
                Math.round(a.getRed()   * t + b.getRed()   * (1 - t)),
                Math.round(a.getGreen() * t + b.getGreen() * (1 - t)),
                Math.round(a.getBlue()  * t + b.getBlue()  * (1 - t)));
    }

    /** The theme's field background, a UIResource, so a later theme switch replaces it. */
    private static Color base() {
        Color c = UIManager.getColor("TextField.background");
        return c != null ? c : Color.WHITE;
    }
}
