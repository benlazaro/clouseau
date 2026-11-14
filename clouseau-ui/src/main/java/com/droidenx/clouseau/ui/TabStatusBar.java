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
    private final JLabel timezoneLabel = label(new Insets(6, 0, 8, 4));
    private final JLabel countsLabel = label(new Insets(6, 8, 8, 8));

    /** {@code onTimezoneClick} runs when the user clicks the timezone badge. */
    TabStatusBar(Runnable onTimezoneClick) {
        super(new BorderLayout(16, 0));   // a gap between a shortened path and the badge
        Runnable topLine = () -> setBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ClouseauColors.separatorColor()));
        topLine.run();
        ClouseauColors.onThemeChange(this, topLine);
        pathLabel.setText(" ");
        countsLabel.setText(" ");
        countsLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        timezoneLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        // A chevron after the text, as on the buttons that open a popup.
        timezoneLabel.setIcon(new FilterBar.ChevronDown());
        timezoneLabel.setHorizontalTextPosition(SwingConstants.LEADING);
        timezoneLabel.setIconTextGap(6);
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

    /** Where the timezone popup opens from. */
    JComponent timezoneAnchor() { return timezoneLabel; }

    /**
     * The timezones: just one when the log is read and shown in the same zone
     * ("America/Chicago"), else how it's read, then how it's shown ("UTC → America/Chicago");
     * "Mixed" when a merged tab's files are read in different zones ({@code perFile}).
     */
    void setTimezone(ZoneId source, boolean perFile) {
        ZoneId display = AppPrefs.getDisplayTimezone();
        boolean same = !perFile && sameOffsets(source, display);
        String src = perFile ? Messages.get("status.timezone.mixed") : source.getId();
        timezoneLabel.setText(same ? display.getId() : src + "  \u2192  " + display.getId());
        String tip;
        if (perFile) tip = MessageFormat.format(Messages.get("status.timezone.tip.mixed"), display.getId());
        else if (same) tip = MessageFormat.format(Messages.get("status.timezone.tip.same"), display.getId());
        else tip = MessageFormat.format(Messages.get("status.timezone.tip"), source.getId(), display.getId(),
                difference(source, display));
        timezoneLabel.setToolTipText("<html>" + tip + "<br>" + Messages.get("status.timezone.tip.click") + "</html>");
    }

    /** Whether two zones give the same time now and all year (UTC and Etc/UTC, say). */
    static boolean sameOffsets(ZoneId a, ZoneId b) {
        return a.equals(b) || a.getRules().equals(b.getRules());
    }

    /** How far the shown time is from the written one, now: "−6h", "+5h 30m", "same time". */
    static String difference(ZoneId source, ZoneId display) {
        java.time.Instant now = java.time.Instant.now();
        int s = display.getRules().getOffset(now).getTotalSeconds() - source.getRules().getOffset(now).getTotalSeconds();
        if (s == 0) return Messages.get("status.timezone.same.time");
        int h = Math.abs(s) / 3600, m = Math.abs(s) % 3600 / 60;
        return (s < 0 ? "\u2212" : "+") + (h > 0 ? h + "h" : "") + (m > 0 ? (h > 0 ? " " : "") + m + "m" : "");
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
        l.setFont(l.getFont().deriveFont(11f));   // ui-caption, like the other small chrome
        l.setBorder(BorderFactory.createEmptyBorder(pad.top, pad.left, pad.bottom, pad.right));
        return l;
    }
}
