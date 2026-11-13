package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Supplier;

/**
 * A one-line notice in a dialog, such as what the default button is waiting for: a small
 * icon in a circle and the text, on a soft tint of the notice's status color with a fine
 * outline, so it reads as a note to act on rather than part of the form.
 * <p>
 * {@link #setText} shows a {@link Kind#NOTE} ("i", {@code status-warn}); {@link #show} picks
 * the kind, so a check can report that it passed ({@link Kind#OK}, a tick in
 * {@code status-ok}) or failed ({@link Kind#ERROR}, "!" in {@code status-error}).
 * <p>
 * With no text (a blank) it paints nothing but keeps its height, so a dialog doesn't change
 * size as the notice comes and goes.
 */
final class NoticeLabel extends JLabel {

    /** What the notice says about the state: something to do, a check passed, or a check failed. */
    enum Kind {
        NOTE(ClouseauColors::statusWarn), OK(ClouseauColors::statusOk), ERROR(ClouseauColors::statusError);

        private final Supplier<Color> color;
        Kind(Supplier<Color> color) { this.color = color; }
    }

    private static final int ICON = 14, PAD_X = 8, PAD_Y = 5;
    private Kind kind = Kind.NOTE;

    NoticeLabel() {
        super(" ");
        setIcon(new KindIcon());
        setIconTextGap(7);
        setBorder(BorderFactory.createEmptyBorder(PAD_Y, PAD_X, PAD_Y, PAD_X + 2));
    }

    /** Shows {@code text} as a {@link Kind#NOTE}. */
    @Override public void setText(String text) {
        kind = Kind.NOTE;
        super.setText(text);
    }

    /** Shows {@code text} as {@code kind}. */
    void show(String text, Kind kind) {
        super.setText(text);
        this.kind = kind;
        repaint();
    }

    Kind kind() {
        return kind;
    }

    private boolean blank() {
        String t = getText();
        return t == null || t.isBlank();
    }

    @Override protected void paintComponent(Graphics g) {
        if (blank()) return;
        Color c = kind.color.get();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        RoundRectangle2D box = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 8, 8);
        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 0x24));
        g2.fill(box);
        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 0x80));
        g2.draw(box);
        g2.dispose();
        setForeground(ClouseauColors.foreground());
        super.paintComponent(g);
    }

    /** An outlined circle in the kind's color, with an "i", a tick or a "!". */
    private final class KindIcon implements Icon {
        @Override public int getIconWidth()  { return ICON; }
        @Override public int getIconHeight() { return ICON; }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(kind.color.get());
            g2.setStroke(new BasicStroke(1.3f));
            g2.draw(new Ellipse2D.Float(x + 0.75f, y + 0.75f, ICON - 1.5f, ICON - 1.5f));
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case NOTE -> {
                    g2.drawLine(x + 7, y + 6, x + 7, y + 10);                       // the stem
                    g2.fill(new Ellipse2D.Float(x + 6.1f, y + 3.1f, 1.8f, 1.8f));   // the dot
                }
                case ERROR -> {
                    g2.drawLine(x + 7, y + 4, x + 7, y + 8);                        // the bar
                    g2.fill(new Ellipse2D.Float(x + 6.1f, y + 9.6f, 1.8f, 1.8f));   // the dot
                }
                case OK -> {
                    Path2D tick = new Path2D.Float();
                    tick.moveTo(x + 4.2f, y + 7.2f);
                    tick.lineTo(x + 6.3f, y + 9.3f);
                    tick.lineTo(x + 9.9f, y + 5.0f);
                    g2.draw(tick);
                }
            }
            g2.dispose();
        }
    }
}
