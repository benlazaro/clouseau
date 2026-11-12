package com.droidenx.clouseau.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Small outline icons drawn on a row under the mouse (saved SSH connections, installed
 * plugins): line art in the row's dim text color, so they read as tools, not content.
 */
final class ToolIcons {

    private ToolIcons() {}

    /** A 12×14 outline trash can: lid with a handle, body with two ribs. */
    static void paintTrash(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
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
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Float(x + 3.5f, y + 4f, 8.5f, 9.5f, 2.5f, 2.5f));
        Path2D.Float back = new Path2D.Float();
        back.moveTo(x + 1f, y + 10.5f);
        back.lineTo(x + 1f, y + 2.5f);
        back.quadTo(x + 1f, y + 1f, x + 2.5f, y + 1f);
        back.lineTo(x + 8.5f, y + 1f);
        g.draw(back);
    }
}
