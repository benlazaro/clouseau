package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;

/**
 * The button bar at the bottom of every dialog, as in the Open dialog: a hairline in the
 * border color across the top, then 8px around and between the controls.
 * <p>
 * {@code left} holds what isn't a closing button (a folder button, a check box, a hint, a
 * list's own actions); {@code right} the buttons that close the dialog, Cancel before the
 * default one. A label on the left is cut short before any button is, so a narrow window
 * never loses its buttons.
 */
public final class DialogFooter {

    /** Room around and between the controls, as the Open dialog has. */
    static final int GAP = 8;

    private DialogFooter() {}

    /** A footer with only closing buttons, on the right. */
    public static JPanel of(JComponent... right) {
        return of(new JComponent[0], right);
    }

    public static JPanel of(JComponent[] left, JComponent... right) {
        // One column per control, the slack between the left group and the right one.
        String cols = "[]".repeat(left.length) + "push" + "[]".repeat(right.length);
        JPanel bar = new JPanel(new MigLayout("insets " + GAP + ", gap " + GAP + ", hidemode 3", cols));
        bar.setBorder(new TopHairline());
        for (JComponent c : left) bar.add(c, c instanceof JLabel ? "wmin 0" : "");
        for (JComponent c : right) bar.add(c);
        return bar;
    }

    /** One pixel in {@link ClouseauColors#borderColor()}, read when painted so it follows a theme switch. */
    private static final class TopHairline extends AbstractBorder {
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            g.setColor(ClouseauColors.borderColor());
            g.fillRect(x, y, w, 1);
        }

        @Override public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(1, 0, 0, 0);
            return insets;
        }
    }
}
