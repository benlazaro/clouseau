package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * What the manager dialogs (Plugins, Plugin Repositories, Sessions, Column Layouts, Themes)
 * share: a list with its actions on the footer's left, Delete to remove (asking first), a
 * plain Close, a section title over the list, and a size that's remembered.
 */
public final class ManagerDialogs {

    private ManagerDialogs() {}

    /** A section title with a hairline to the right edge, as the Settings dialog's. */
    public static JComponent sectionTitle(String text) {
        return SettingsDialog.sectionLabel(text);
    }

    /** A one-line hint under a list: 11px, in text-dim, following a theme switch. */
    public static JLabel hint(String text) {
        JLabel hint = new JLabel(text);
        hint.setFont(hint.getFont().deriveFont(11f));
        ClouseauColors.themedForeground(hint, ClouseauColors::dimForeground);
        return hint;
    }

    /** Runs {@code delete} when Delete is pressed while {@code list} has the focus. */
    public static void onDeleteKey(JComponent list, Runnable delete) {
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "clouseau.delete");
        list.getActionMap().put("clouseau.delete", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { delete.run(); }
        });
    }

    /** Asks before removing something: Delete / Cancel, true for Delete. */
    public static boolean confirmDelete(Component parent, String title, String message) {
        return confirm(parent, title, message, Messages.get("manager.delete.confirm"));
    }

    /** Asks before removing something: {@code action} / Cancel, Cancel the default; true for {@code action}. */
    public static boolean confirm(Component parent, String title, String message, String action) {
        String cancel = Messages.get("settings.button.cancel");
        int answer = JOptionPane.showOptionDialog(parent, message, title, JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE, null, new Object[]{ action, cancel }, cancel);
        return answer == 0;
    }

    /**
     * Gives {@code dialog} a minimum size and the size it was last left at ({@code fallback}
     * the first time), and saves its size under {@code id} when it closes. Call after
     * {@code pack()} and before placing the dialog.
     */
    public static void rememberSize(JDialog dialog, String id, Dimension fallback, Dimension minimum) {
        dialog.setResizable(true);
        dialog.setMinimumSize(minimum);
        Dimension saved = AppPrefs.getDialogSize(id);
        Dimension size = saved != null ? saved : fallback;
        Rectangle screen = dialog.getGraphicsConfiguration() != null
                ? dialog.getGraphicsConfiguration().getBounds() : new Rectangle(0, 0, 4096, 4096);
        dialog.setSize(Math.max(minimum.width, Math.min(size.width, screen.width)),
                       Math.max(minimum.height, Math.min(size.height, screen.height)));
        dialog.addWindowListener(new WindowAdapter() {
            private boolean saved;
            @Override public void windowClosing(WindowEvent e) { save(); }
            @Override public void windowClosed(WindowEvent e) { save(); }
            private void save() {
                if (saved) return;
                saved = true;
                AppPrefs.setDialogSize(id, dialog.getSize());
            }
        });
    }
}
