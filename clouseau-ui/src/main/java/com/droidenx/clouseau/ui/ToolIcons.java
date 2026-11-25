package com.droidenx.clouseau.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.UIManager;

import com.droidenx.clouseau.ui.theme.ClouseauColors;

/**
 * Small outline icons drawn on a row under the mouse (saved SSH connections, installed
 * plugins): line art in the row's dim text color, so they read as tools, not content.
 */
final class ToolIcons {

    private ToolIcons() {}

    /**
     * An idle tool's color on a row: {@code dimForeground}, or on a selected row the selection
     * foreground pulled toward the selection background, so the tool under the mouse
     * ({@link #litColor}) still stands out from it (DESIGN.md 6.13).
     *
     * @param ui the component's {@code UIManager} prefix, {@code "List"} or {@code "Tree"}
     */
    static Color idleColor(boolean selected, String ui) {
        if (!selected) return ClouseauColors.dimForeground();
        return blend(UIManager.getColor(ui + ".selectionForeground"),
                     UIManager.getColor(ui + ".selectionBackground"), 0.6f);
    }

    /** The color of the tool under the mouse: {@code foreground}, or the selection foreground on a selected row. */
    static Color litColor(boolean selected, String ui) {
        return selected ? UIManager.getColor(ui + ".selectionForeground") : ClouseauColors.foreground();
    }

    /** {@code t} of {@code a} and the rest of {@code b}. */
    static Color blend(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed()   * t + b.getRed()   * (1 - t)),
                Math.round(a.getGreen() * t + b.getGreen() * (1 - t)),
                Math.round(a.getBlue()  * t + b.getBlue()  * (1 - t)));
    }

    /**
     * The row tools' line: 1.2px where the screen is scaled (150% and up), where it renders
     * evenly; exactly 1px at 100%, where a 1.2px line can't sit on the pixel grid and smears
     * across two pixels. Java2D's stroke normalization then puts a 1px line on whole pixels,
     * so the small icons are crisp there too.
     */
    private static BasicStroke rowToolStroke(Graphics2D g) {
        double scale = g.getTransform().getScaleX();
        return new BasicStroke(scale < 1.25 ? 1f : 1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }


    /** A 12×14 outline trash can: lid with a handle, body with two ribs. */
    static void paintTrash(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.setStroke(rowToolStroke(g));
        g.drawLine(x, y + 3, x + 12, y + 3);                       // lid
        g.drawLine(x + 4, y + 3, x + 4, y + 1);                    // handle
        g.drawLine(x + 4, y + 1, x + 8, y + 1);
        g.drawLine(x + 8, y + 1, x + 8, y + 3);
        Path2D.Float body = new Path2D.Float();
        body.moveTo(x + 1.5f, y + 3);
        body.lineTo(x + 2.5f, y + 14);
        body.lineTo(x + 9.5f, y + 14);
        body.lineTo(x + 10.5f, y + 3);
        g.draw(body);
        g.drawLine(x + 5, y + 6, x + 5, y + 11);                   // ribs
        g.drawLine(x + 7, y + 6, x + 7, y + 11);
    }

    /**
     * A 12×12 outline pencil, tip at the lower left: the note marker. Drawn, not the ✎
     * character, which some monospace fonts lack (it showed as a box on Windows).
     */
    static void paintPencil(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D.Float body = new Path2D.Float();
        body.moveTo(x + 1f, y + 11f);          // tip
        body.lineTo(x + 1.6f, y + 8.4f);
        body.lineTo(x + 8.6f, y + 1.4f);
        body.lineTo(x + 10.6f, y + 3.4f);
        body.lineTo(x + 3.6f, y + 10.4f);
        body.closePath();
        g.draw(body);
        g.drawLine(x + 7, y + 3, x + 9, y + 5);  // the band behind the eraser
    }

    /** Two overlapping 9×10 rounded sheets, the back one offset up and left. */
    static void paintDuplicate(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.setStroke(rowToolStroke(g));
        g.draw(new RoundRectangle2D.Float(x + 3.5f, y + 4f, 8.5f, 9.5f, 2.5f, 2.5f));
        Path2D.Float back = new Path2D.Float();
        back.moveTo(x + 1f, y + 10.5f);
        back.lineTo(x + 1f, y + 2.5f);
        back.quadTo(x + 1f, y + 1f, x + 2.5f, y + 1f);
        back.lineTo(x + 8.5f, y + 1f);
        g.draw(back);
    }

    /**
     * A five-pointed star in a 16×16 box, like the file chooser's toolbar icons: filled when
     * {@code filled}, else an outline. Drawn, not the ★ character, so it matches the toolbar.
     */
    static void paintStar(Graphics2D g, int x, int y, Color c, boolean filled) {
        double cx = x + 8, cy = y + 8.5, outer = 7, inner = 2.9;
        Path2D.Double star = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double r = i % 2 == 0 ? outer : inner;
            double a = -Math.PI / 2 + i * Math.PI / 5;
            double px = cx + r * Math.cos(a), py = cy + r * Math.sin(a);
            if (i == 0) star.moveTo(px, py); else star.lineTo(px, py);
        }
        star.closePath();
        g.setColor(c);
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (filled) g.fill(star);
        g.draw(star);
    }

    /**
     * A new log: a plus in a 10×10 rounded square, on a connection's or folder's row, beside the
     * trash can, its bottom on the trash can's (y + 14). Every line is on whole pixels, as the
     * trash can's are, and the plus is centered (x + 6, y + 9) with arms of 3, so both strokes
     * render alike at any scale.
     */
    static void paintNewLog(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.setStroke(rowToolStroke(g));
        g.draw(new RoundRectangle2D.Float(x + 1, y + 4, 10, 10, 3, 3));
        g.drawLine(x + 6, y + 6, x + 6, y + 12);
        g.drawLine(x + 3, y + 9, x + 9, y + 9);
    }

    /**
     * An update: an arrow pointing up in an 11×11 circle, the mark on an installed plugin that has
     * a newer version (D-33). Drawn in the accent color, as content, not as a row tool.
     */
    static void paintUpdate(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.setStroke(rowToolStroke(g));
        g.draw(new java.awt.geom.Ellipse2D.Float(x + 0.5f, y + 0.5f, 11, 11));
        g.drawLine(x + 6, y + 3, x + 6, y + 9);                    // shaft
        g.drawLine(x + 3, y + 6, x + 6, y + 3);                    // head
        g.drawLine(x + 9, y + 6, x + 6, y + 3);
    }
}
