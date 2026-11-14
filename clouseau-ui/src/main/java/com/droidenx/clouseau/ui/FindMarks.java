package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Find's marks: a light box behind each piece of a cell's text that matched, drawn by the
 * table renderers between the cell's background and its text.
 * <p>
 * Matches are marked on the text, not by filling the row, so a highlighted row keeps its
 * color and shows its matches too, and no match can be mistaken for a highlight: the mark is
 * a light, outlined box, unlike the eight dark highlight fills.
 */
final class FindMarks {

    private FindMarks() {}

    /**
     * Paints a mark behind each case-insensitive occurrence of {@code query} in the label's
     * text, as far as the text is shown (not past an ellipsis). The label must be laid out at
     * its painting size, as a renderer is when painted.
     */
    static void paint(JLabel label, Graphics g, String query) {
        String text = label.getText();
        if (text == null || text.isEmpty() || query == null || query.isEmpty()) return;
        FontMetrics fm = label.getFontMetrics(label.getFont());
        Insets in = label.getInsets();
        Rectangle view = new Rectangle(in.left, in.top,
                label.getWidth() - in.left - in.right, label.getHeight() - in.top - in.bottom);
        Rectangle iconR = new Rectangle(), textR = new Rectangle();
        String shown = SwingUtilities.layoutCompoundLabel(label, fm, text, null,
                label.getVerticalAlignment(), label.getHorizontalAlignment(),
                label.getVerticalTextPosition(), label.getHorizontalTextPosition(),
                view, iconR, textR, 0);
        int visible = shown.equals(text) ? text.length() : commonPrefix(text, shown);

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int q = query.length();
        for (int i = 0; i + q <= text.length() && i < visible; i++) {
            if (!text.regionMatches(true, i, query, 0, q)) continue;
            int end = Math.min(i + q, visible);
            float x = textR.x + fm.stringWidth(text.substring(0, i)) - 1.5f;
            float w = fm.stringWidth(text.substring(i, end)) + 3f;
            RoundRectangle2D box = new RoundRectangle2D.Float(x, textR.y + 0.5f, w, textR.height - 1f, 4, 4);
            g2.setColor(ClouseauColors.findMarkBackground());
            g2.fill(box);
            g2.setColor(ClouseauColors.findMarkBorder());
            g2.draw(box);
            i = end - 1;                        // marks don't overlap
        }
        g2.dispose();
    }

    /**
     * The gutter's mark for a matching row: a short bar at the gutter's edge, next to the
     * table, so a row whose match is in a hidden column still shows.
     */
    static void paintGutterTick(Graphics2D g, int gutterWidth, int y, int rowHeight) {
        g.setColor(ClouseauColors.findMarkBorder());
        g.fillRoundRect(gutterWidth - 3, y + 5, 3, rowHeight - 10, 3, 3);   // at the gutter's edge
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length()), i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }
}
