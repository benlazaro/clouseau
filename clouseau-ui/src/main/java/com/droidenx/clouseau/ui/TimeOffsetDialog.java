package com.droidenx.clouseau.ui;

import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.text.MessageFormat;

/**
 * Asks for a merged file's time offset (D-17): a sign and hours, minutes, seconds and
 * milliseconds, added to the file's timestamps to correct a clock that is off.
 */
final class TimeOffsetDialog {

    private TimeOffsetDialog() {}

    /** Returns the new offset in milliseconds, or null if the user cancelled. */
    static Long ask(Window owner, String fileName, long currentMillis) {
        JComboBox<String> sign = new JComboBox<>(new String[]{"+", "−"});
        long a = Math.abs(currentMillis);
        sign.setSelectedIndex(currentMillis < 0 ? 1 : 0);
        JSpinner hours = spinner(a / 3_600_000, 9_999);
        JSpinner minutes = spinner(a / 60_000 % 60, 59);
        JSpinner seconds = spinner(a / 1_000 % 60, 59);
        JSpinner millis = spinner(a % 1_000, 999);

        JPanel panel = new JPanel(new MigLayout("insets 0", "[][]8[]8[]8[]", "[]4[]8[]"));
        panel.add(new JLabel(Messages.get("merge.offset.dialog.label")), "span, wrap");
        panel.add(sign);
        panel.add(labeled(hours, "merge.offset.dialog.hours"));
        panel.add(labeled(minutes, "merge.offset.dialog.minutes"));
        panel.add(labeled(seconds, "merge.offset.dialog.seconds"));
        panel.add(labeled(millis, "merge.offset.dialog.millis"), "wrap");
        JLabel note = new JLabel(Messages.get("merge.offset.dialog.note"));
        note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
        panel.add(note, "span");

        String title = MessageFormat.format(Messages.get("merge.offset.dialog.title"), fileName);
        Object[] options = { Messages.get("merge.offset.dialog.ok"), Messages.get("merge.offset.dialog.reset"),
                Messages.get("merge.offset.dialog.cancel") };
        int choice = JOptionPane.showOptionDialog(owner, panel, title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice == 1) return 0L;
        if (choice != 0) return null;
        long value = ((Number) hours.getValue()).longValue() * 3_600_000
                + ((Number) minutes.getValue()).longValue() * 60_000
                + ((Number) seconds.getValue()).longValue() * 1_000
                + ((Number) millis.getValue()).longValue();
        return sign.getSelectedIndex() == 1 ? -value : value;
    }

    private static JSpinner spinner(long value, int max) {
        JSpinner s = new JSpinner(new SpinnerNumberModel((int) value, 0, max, 1));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(max > 999 ? 4 : 3);
        return s;
    }

    private static JPanel labeled(JSpinner spinner, String key) {
        JPanel p = new JPanel(new MigLayout("insets 0, gap 2", "[][]", "[]"));
        p.setOpaque(false);
        p.add(spinner);
        p.add(new JLabel(Messages.get(key)));
        return p;
    }
}
