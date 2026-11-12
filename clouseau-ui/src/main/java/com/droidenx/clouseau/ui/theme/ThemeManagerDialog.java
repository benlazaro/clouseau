package com.droidenx.clouseau.ui.theme;

import com.droidenx.clouseau.ui.AppPrefs;
import com.droidenx.clouseau.ui.Messages;

import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for installing and uninstalling user themes.
 * Built-in themes are always available and cannot be removed.
 */
public final class ThemeManagerDialog extends JDialog {

    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> themeList            = new JList<>(listModel);
    private final JButton uninstallBtn;
    private List<ThemeManager.ThemeEntry> userThemes;

    public ThemeManagerDialog(Window owner) {
        super(owner, Messages.get("themes.manage.title"), ModalityType.APPLICATION_MODAL);
        userThemes = new ArrayList<>(ThemeManager.getUserThemes());

        themeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        themeList.setFixedCellHeight(24);
        rebuildList();

        JButton installBtn   = new JButton(Messages.get("themes.install"));
        uninstallBtn         = new JButton(Messages.get("themes.uninstall"));
        JButton closeBtn     = new JButton(Messages.get("themes.close"));

        uninstallBtn.setEnabled(false);

        themeList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            uninstallBtn.setEnabled(themeList.getSelectedIndex() >= 0);
        });

        installBtn.addActionListener(e -> installTheme());
        uninstallBtn.addActionListener(e -> uninstallSelected());
        closeBtn.addActionListener(e -> dispose());

        JPanel content = new JPanel(new MigLayout("insets 16, wrap 1, gapy 8", "[400px, fill]"));
        content.add(new JLabel(Messages.get("themes.manage.installed.label")));

        JScrollPane scroll = new JScrollPane(themeList);
        scroll.setPreferredSize(new Dimension(400, 150));
        content.add(scroll, "grow, h 150!");

        JLabel hint = new JLabel("<html><small>" + ThemeManager.userThemesDir() + "</small></html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        content.add(hint);

        // The list's actions on the left, Close on the right, as in the Plugin Manager.
        JPanel root = new JPanel(new java.awt.BorderLayout());
        root.add(content, java.awt.BorderLayout.CENTER);
        root.add(com.droidenx.clouseau.ui.DialogFooter.of(new JComponent[]{ installBtn, uninstallBtn }, closeBtn),
                java.awt.BorderLayout.SOUTH);
        setContentPane(root);
        // No default button: the actions apply at once, so there is nothing to confirm, and
        // Close shouldn't be the amber one. Escape closes.
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void rebuildList() {
        int sel = themeList.getSelectedIndex();
        listModel.clear();
        userThemes.forEach(t -> listModel.addElement(t.name()));
        if (sel >= 0 && sel < userThemes.size()) themeList.setSelectedIndex(sel);
    }

    private void installTheme() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(Messages.get("themes.install.chooser.title"));
        fc.setFileFilter(new FileNameExtensionFilter(
                Messages.get("themes.install.chooser.filter"), "properties"));
        java.io.File lastDir = AppPrefs.getLastOpenDir();
        if (lastDir != null) fc.setCurrentDirectory(lastDir);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            String name = ThemeManager.installTheme(fc.getSelectedFile().toPath());
            userThemes = new ArrayList<>(ThemeManager.getUserThemes());
            rebuildList();
            for (int i = 0; i < userThemes.size(); i++) {
                if (userThemes.get(i).name().equals(name)) {
                    themeList.setSelectedIndex(i);
                    break;
                }
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    Messages.get("themes.install.error") + "\n" + ex.getMessage(),
                    Messages.get("themes.install.error.title"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void uninstallSelected() {
        int idx = themeList.getSelectedIndex();
        if (idx < 0) return;
        ThemeManager.ThemeEntry theme = userThemes.get(idx);
        if (theme.name().equals(AppPrefs.getTheme())) {
            int result = JOptionPane.showConfirmDialog(this,
                    Messages.get("themes.uninstall.active.message"),
                    Messages.get("themes.uninstall.active.title"),
                    JOptionPane.YES_NO_OPTION);
            if (result != JOptionPane.YES_OPTION) return;
            AppPrefs.setTheme(ThemeManager.DEFAULT_THEME);
        }
        try {
            ThemeManager.uninstallTheme(theme);
            userThemes = new ArrayList<>(ThemeManager.getUserThemes());
            rebuildList();
            int newSel = userThemes.isEmpty() ? -1 : Math.min(idx, userThemes.size() - 1);
            if (newSel >= 0) themeList.setSelectedIndex(newSel);
            uninstallBtn.setEnabled(newSel >= 0);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    Messages.get("themes.uninstall.error") + "\n" + ex.getMessage(),
                    Messages.get("themes.uninstall.error.title"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
