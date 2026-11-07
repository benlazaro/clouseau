package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.SourceConnection;
import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import java.awt.*;
import java.text.MessageFormat;

/**
 * The dot on a live-source tab's header (UC-6, issue #3): green while connected, yellow
 * while connecting, gray once disconnected; the tooltip says which, with the error if the
 * source failed. Hidden on tabs without a live source. The color is read from the theme
 * at each paint, so it follows a live theme switch (DESIGN.md 6.8.1).
 */
final class SourceLed extends JComponent {

    private static final int DIAMETER = 8;

    private SourceConnection.State state;

    SourceLed() {
        setOpaque(false);
        setVisible(false);
        Dimension size = new Dimension(DIAMETER + 2, DIAMETER + 2);
        setPreferredSize(size);
        setMinimumSize(size);
    }

    /** Shows {@code state} (null hides the dot), with {@code error} in the tooltip. EDT only. */
    void setState(SourceConnection.State state, String error) {
        this.state = state;
        setVisible(state != null);
        setToolTipText(state == null ? null : tooltip(state, error));
        repaint();
    }

    SourceConnection.State state() {
        return state;
    }

    static String tooltip(SourceConnection.State state, String error) {
        return switch (state) {
            case CONNECTING -> Messages.get("source.state.connecting");
            case CONNECTED  -> Messages.get("source.state.connected");
            case DISCONNECTED -> error == null || error.isBlank()
                    ? Messages.get("source.state.disconnected")
                    : MessageFormat.format(Messages.get("source.state.failed"), error);
        };
    }

    static Color color(SourceConnection.State state) {
        return switch (state) {
            case CONNECTING   -> ClouseauColors.statusWarn();
            case CONNECTED    -> ClouseauColors.statusOk();
            case DISCONNECTED -> ClouseauColors.offColor();
        };
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (state == null) return;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color(state));
            int x = (getWidth() - DIAMETER) / 2;
            int y = (getHeight() - DIAMETER) / 2;
            g2.fillOval(x, y, DIAMETER, DIAMETER);
        } finally {
            g2.dispose();
        }
    }
}
