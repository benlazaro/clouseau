package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * A one-line notice in a dialog, such as what the default button is waiting for: a small
 * "i" in a circle and the text, on a soft tint of {@code status-warn} with a fine outline,
 * so it reads as a note to act on rather than part of the form.
 * <p>
 * With no text (a blank) it paints nothing but keeps its height, so a dialog doesn't change
 * size as the notice comes and goes.
 */
final class NoticeLabel extends JLabel {

    private static final int ICON = 14, PAD_X = 8, PAD_Y = 5;

    NoticeLabel() {
        super(" ");
        setIcon(new InfoIcon());
        setIconTextGap(7);
        setBorder(BorderFactory.createEmptyBorder(PAD_Y, PAD_X, PAD_Y, PAD_X + 2));
    }

    private boolean blank() {
        String t = getText();
        return t == null || t.isBlank();
    }

    @Override protected void paintComponent(Graphics g) {
        if (blank()) return;
        Color warn = ClouseauColors.statusWarn();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        RoundRectangle2D box = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 8, 8);
        g2.setColor(new Color(warn.getRed(), warn.getGreen(), warn.getBlue(), 0x24));
        g2.fill(box);
        g2.setColor(new Color(warn.getRed(), warn.getGreen(), warn.getBlue(), 0x80));
        g2.draw(box);
        g2.dispose();
        setForeground(ClouseauColors.foreground());
        super.paintComponent(g);
    }

    /** An outlined circle with an "i", in {@code status-warn}. */
    private static final class InfoIcon implements Icon {
        @Override public int getIconWidth()  { return ICON; }
        @Override public int getIconHeight() { return ICON; }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(ClouseauColors.statusWarn());
            g2.setStroke(new BasicStroke(1.3f));
            g2.draw(new Ellipse2D.Float(x + 0.75f, y + 0.75f, ICON - 1.5f, ICON - 1.5f));
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(x + 7, y + 6, x + 7, y + 10);                       // the stem
            g2.fill(new Ellipse2D.Float(x + 6.1f, y + 3.1f, 1.8f, 1.8f));   // the dot
            g2.dispose();
        }
    }
}
