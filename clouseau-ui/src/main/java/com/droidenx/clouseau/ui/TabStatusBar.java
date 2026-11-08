package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.MessageFormat;
import java.time.ZoneId;

/**
 * The strip under a tab's rows (G-9, D-21): what the tab shows on the left, and on the
 * right the source timezone badge (click to change) and the row counts. It only displays
 * what the tab tells it. EDT only.
 */
final class TabStatusBar extends JPanel {

    private final JLabel pathLabel = label(new Insets(6, 8, 8, 8));
    private final JLabel timezoneLabel = label(new Insets(6, 0, 8, 0));
    private final JLabel countsLabel = label(new Insets(6, 8, 8, 8));

    /** {@code onTimezoneClick} runs when the user clicks the timezone badge. */
    TabStatusBar(Runnable onTimezoneClick) {
        super(new BorderLayout());
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ClouseauColors.separatorColor()));
        pathLabel.setText(" ");
        countsLabel.setText(" ");
        countsLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        timezoneLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        timezoneLabel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { onTimezoneClick.run(); }
        });

        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        east.setOpaque(false);
        east.add(timezoneLabel);
        east.add(countsLabel);
        add(pathLabel, BorderLayout.WEST);
        add(east, BorderLayout.EAST);
    }

    /** What the tab shows: a file, a merged view's files, or a source's name. */
    void setPath(String text, String tooltip) {
        pathLabel.setText(text);
        pathLabel.setToolTipText(tooltip);
    }

    /** The source timezone badge; {@code perFile} when a merged tab's files differ. */
    void setTimezone(ZoneId zone, boolean perFile) {
        String id = perFile ? Messages.get("merge.timezone.perfile") : zone.getId();
        timezoneLabel.setText("[src: " + id + "]  ");
        boolean isSystem = zone.equals(ZoneId.systemDefault());
        timezoneLabel.setToolTipText(isSystem
                ? "Source timezone: " + id + " (system default) — click to change"
                : "Source timezone: " + id + " — click to change");
    }

    /**
     * The row counts: {@code visible} of {@code total} after filtering, {@code trimmed}
     * dropped by the entry limit, then {@code note} (partial load, rotation, filtering).
     */
    void setRows(int visible, int total, long trimmed, String note) {
        String text;
        if (trimmed > 0) {
            text = (visible == total)
                    ? MessageFormat.format(Messages.get("status.lines.trimmed"), total, trimmed)
                    : MessageFormat.format(Messages.get("status.lines.filtered.trimmed"), visible, total, trimmed);
        } else {
            text = (visible == total)
                    ? MessageFormat.format(Messages.get("status.lines"), total)
                    : MessageFormat.format(Messages.get("status.lines.filtered"), visible, total);
        }
        countsLabel.setText(text + note);
    }

    /** The line count of a file shown as plain text (UC-13). */
    void setPlainLines(int lines) {
        countsLabel.setText(MessageFormat.format(Messages.get("status.plain"), lines));
    }

    private static JLabel label(Insets pad) {
        JLabel l = new JLabel();
        ClouseauColors.themedForeground(l, () -> UIManager.getColor("Button.foreground"));
        l.setFont(l.getFont().deriveFont(12f));
        l.setBorder(BorderFactory.createEmptyBorder(pad.top, pad.left, pad.bottom, pad.right));
        return l;
    }
}
