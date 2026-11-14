package com.droidenx.clouseau.ui.theme;

import com.droidenx.clouseau.ui.AppPrefs;
import com.droidenx.clouseau.ui.ManagerDialogs;
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
    private static final String CARD_LIST  = "list";
    private static final String CARD_EMPTY = "empty";

    private final CardLayout listCards = new CardLayout();
    private final JPanel listHolder    = new JPanel(listCards);
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

        ManagerDialogs.onDeleteKey(themeList, this::uninstallSelected);

        JPanel content = new JPanel(new MigLayout("fill, insets 16, wrap 1, gapy 8", "[grow, fill]", "[][grow, fill][]"));
        content.add(ManagerDialogs.sectionTitle(Messages.get("themes.manage.installed.label")), "growx");

        JScrollPane scroll = ClouseauColors.noFocusFrame(new JScrollPane(themeList));
        scroll.setPreferredSize(new Dimension(400, 150));
        listHolder.add(scroll, CARD_LIST);
        listHolder.add(buildEmptyState(), CARD_EMPTY);
        content.add(listHolder, "grow");
        showListOrEmpty();

        // Where user themes live: 11px in text-dim.
        JLabel hint = ManagerDialogs.hint(ThemeManager.userThemesDir().toString());
        content.add(hint, "wmin 0");

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
        ManagerDialogs.rememberSize(this, "themes", getSize(), new Dimension(420, 280));
        setLocationRelativeTo(owner);
    }

    /** With no user themes: what goes here, and how to add one. */
    private JComponent buildEmptyState() {
        JPanel empty = new JPanel(new MigLayout("fill, insets 16", "[center]", "push[]6[]push"));
        ClouseauColors.themedBackground(empty, () -> UIManager.getColor("List.background"));
        JLabel title = new JLabel(Messages.get("themes.manage.empty.title"));
        ClouseauColors.themedForeground(title, ClouseauColors::dimForeground);
        JLabel hint = ManagerDialogs.hint(Messages.get("themes.manage.empty.hint"));
        ClouseauColors.themedForeground(hint, ClouseauColors::mutedForeground);
        empty.add(title, "wrap");
        empty.add(hint);
        empty.setBorder(UIManager.getBorder("ScrollPane.border"));
        return empty;
    }

    private void showListOrEmpty() {
        listCards.show(listHolder, userThemes.isEmpty() ? CARD_EMPTY : CARD_LIST);
    }

    private void rebuildList() {
        int sel = themeList.getSelectedIndex();
        listModel.clear();
        userThemes.forEach(t -> listModel.addElement(t.name()));
        if (sel >= 0 && sel < userThemes.size()) themeList.setSelectedIndex(sel);
        showListOrEmpty();
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
        boolean active = theme.name().equals(AppPrefs.getTheme());
        // Always ask first (the button and the Delete key both come here).
        if (!ManagerDialogs.confirm(this,
                Messages.get(active ? "themes.uninstall.active.title" : "themes.uninstall.title"),
                active ? Messages.get("themes.uninstall.active.message")
                       : Messages.format("themes.uninstall.message", theme.name()),
                Messages.get("themes.uninstall"))) return;
        if (active) AppPrefs.setTheme(ThemeManager.DEFAULT_THEME);
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
