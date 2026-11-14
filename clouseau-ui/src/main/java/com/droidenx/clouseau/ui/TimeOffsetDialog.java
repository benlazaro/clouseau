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

        JPanel panel = new JPanel(new MigLayout("insets 0", "[][]8[]8[]8[]", "[]6[]6[]"));
        panel.add(new JLabel(Messages.get("merge.offset.dialog.label")), "span, wrap");
        panel.add(sign);
        panel.add(labeled(hours, "merge.offset.dialog.hours"));
        panel.add(labeled(minutes, "merge.offset.dialog.minutes"));
        panel.add(labeled(seconds, "merge.offset.dialog.seconds"));
        panel.add(labeled(millis, "merge.offset.dialog.millis"), "wrap");
        panel.add(PromptDialog.hint(Messages.get("merge.offset.dialog.note")), "span");

        // No Offset sets the fields to zero; OK applies them.
        JButton reset = new JButton(Messages.get("merge.offset.dialog.reset"));
        reset.addActionListener(e -> {
            sign.setSelectedIndex(0);
            for (JSpinner s : new JSpinner[]{ hours, minutes, seconds, millis }) s.setValue(0);
        });
        String title = MessageFormat.format(Messages.get("merge.offset.dialog.title"), fileName);
        PromptDialog dialog = new PromptDialog(owner, title, panel, reset);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                ((JSpinner.DefaultEditor) hours.getEditor()).getTextField().requestFocusInWindow();
            }
        });
        if (!dialog.ask()) return null;
        long value = ((Number) hours.getValue()).longValue() * 3_600_000
                + ((Number) minutes.getValue()).longValue() * 60_000
                + ((Number) seconds.getValue()).longValue() * 1_000
                + ((Number) millis.getValue()).longValue();
        return sign.getSelectedIndex() == 1 ? -value : value;
    }

    private static JSpinner spinner(long value, int max) {
        JSpinner s = new JSpinner(new SpinnerNumberModel((int) value, 0, max, 1));
        JFormattedTextField field = ((JSpinner.DefaultEditor) s.getEditor()).getTextField();
        field.setColumns(max > 999 ? 4 : 3);
        // Take a typed value at once, so clicking OK right after typing uses it.
        if (field.getFormatter() instanceof javax.swing.text.DefaultFormatter f) f.setCommitsOnValidEdit(true);
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
