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
        super(new BorderLayout(16, 0));   // a gap between a shortened path and the badge
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
        // The path takes what's left and ends in "…" when it doesn't fit. A label's minimum
        // width is its whole text, so without this a merged tab's list of file names would
        // widen the tab, and with it every tab in the window, past the window's edge.
        pathLabel.setMinimumSize(new Dimension(0, 0));
        add(pathLabel, BorderLayout.CENTER);
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
    /**
     * "57 rows · 106 lines": the rows shown, and the lines they come from. Not "57 of 106
     * lines", which reads as lines missing: most of the difference is usually continuation
     * lines folded into their entry (a stack trace), not lines the app failed to show.
     */
    void setRows(int rows, long lines, long trimmed, String note) {
        String text;
        if (trimmed > 0) {
            text = (rows == lines)
                    ? MessageFormat.format(Messages.get("status.lines.trimmed"), lines, trimmed)
                    : MessageFormat.format(Messages.get("status.lines.filtered.trimmed"), rows, lines, trimmed);
        } else {
            text = (rows == lines)
                    ? MessageFormat.format(Messages.get("status.lines"), lines)
                    : MessageFormat.format(Messages.get("status.lines.filtered"), rows, lines);
        }
        countsLabel.setText(text + note);
        countsLabel.setToolTipText(rows == lines ? null : Messages.get("status.rows.tooltip"));
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
