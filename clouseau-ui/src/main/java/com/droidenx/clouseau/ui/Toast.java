package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import java.awt.*;

/**
 * A short note ("Text size 14", "Message copied", "Back to the first match") shown for about
 * a second (a longer one for longer, to be read) in the middle of an area of the window, so an action has visible feedback where
 * the eye is: not in a corner it would be missed in. A framed chip, inverted against the
 * window, on its popup layer, over the area and covering nothing else.
 * <p>
 * Given a rectangle to keep clear (the row or match just selected), it moves above or below
 * it, so it never covers what you were sent to. One toast per owner: showing another replaces
 * the one up. EDT only.
 */
final class Toast {

    /** How long a short note stays; a longer one stays longer, to be read ({@link #shownMs}). */
    private static final int SHOWN_MS = 1200;
    /** Notes up to this long are read at a glance. */
    private static final int GLANCE_CHARS = 20;
    /** Time added for each character past {@link #GLANCE_CHARS}: a slow reading pace. */
    private static final int MS_PER_CHAR = 70;
    private static final int MAX_SHOWN_MS = 6000;
    /** Room kept between the toast and the rectangle it keeps clear. */
    private static final int CLEAR_GAP = 8;

    private JLabel label;
    private Timer timer;

    /** Shows {@code text} in the middle of {@code area}. */
    void show(JComponent area, String text) {
        show(area, text, null);
    }

    /**
     * Shows {@code text} in the middle of {@code area}, or above or below {@code keepClear}
     * (in {@code area}'s coordinates, null for none) when the middle would cover it.
     * Does nothing while the area isn't on screen.
     */
    void show(JComponent area, String text, Rectangle keepClear) {
        JRootPane root = area.getRootPane();
        if (root == null || !area.isShowing()) return;
        if (label == null) {
            label = new Chip();
            timer = new Timer(SHOWN_MS, e -> label.setVisible(false));
            timer.setRepeats(false);
        }
        // Read when painted (the chip) or shown (the text), so a toast after a theme switch is
        // in the new theme.
        label.setForeground(ClouseauColors.toastForeground());
        label.setText(text);

        JLayeredPane layers = root.getLayeredPane();
        if (label.getParent() != layers) {
            if (label.getParent() != null) label.getParent().remove(label);   // the tab moved windows
            layers.add(label, JLayeredPane.POPUP_LAYER);
        }
        Dimension d = label.getPreferredSize();
        Rectangle box = SwingUtilities.convertRectangle(area, new Rectangle(area.getSize()), layers);
        Rectangle clear = keepClear != null ? SwingUtilities.convertRectangle(area, keepClear, layers) : null;
        label.setBounds(place(box, d, clear));
        label.setVisible(true);
        timer.setInitialDelay(shownMs(text));
        timer.restart();
    }

    /** How long {@code text} stays up: 1.2 s for a few words, more for a sentence, at most 6 s. */
    static int shownMs(String text) {
        int extra = Math.max(0, text.length() - GLANCE_CHARS) * MS_PER_CHAR;
        return Math.min(MAX_SHOWN_MS, SHOWN_MS + extra);
    }

    /**
     * The toast itself: a rounded chip in the theme's toast colors, inverted against the window
     * so it never reads as a find mark or a row, inside a 1px frame.
     */
    private static final class Chip extends JLabel {
        private static final int ARC = 10;

        Chip() {
            setOpaque(false);                       // the rounded chip is painted below
            setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
            setFont(getFont().deriveFont(Font.BOLD));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(ClouseauColors.toastBackground());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
            g2.setColor(ClouseauColors.toastBorder());
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** The text up now, or null; for tests. */
    String shownText() {
        return label != null && label.isVisible() ? label.getText() : null;
    }

    /**
     * Where a toast of size {@code d} goes in {@code area}: its middle, or, when that would
     * touch {@code clear}, the middle of the larger part of the area above or below it.
     */
    static Rectangle place(Rectangle area, Dimension d, Rectangle clear) {
        int x = area.x + (area.width - d.width) / 2;
        Rectangle middle = new Rectangle(x, area.y + (area.height - d.height) / 2, d.width, d.height);
        if (clear == null) return middle;
        Rectangle padded = new Rectangle(clear.x, clear.y - CLEAR_GAP, clear.width, clear.height + 2 * CLEAR_GAP);
        if (!middle.intersects(padded)) return middle;
        int above = padded.y - area.y, below = area.y + area.height - (padded.y + padded.height);
        int y = above >= below ? area.y + (above - d.height) / 2
                               : padded.y + padded.height + (below - d.height) / 2;
        return new Rectangle(x, Math.max(area.y, y), d.width, d.height);
    }
}
