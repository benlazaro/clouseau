package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ThemeManager;
import com.droidenx.clouseau.ui.theme.ThemeManagerDialog;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;

/**
 * Modal application settings / user preferences dialog.
 */
final class SettingsDialog extends JDialog {

    private final LogPanel logPanel;
    private final Runnable onApply;

    // Table settings
    private final JSpinner rowHeightSpinner;

    // Detail panel settings
    private final JSpinner detailFontSizeSpinner;
    private final JCheckBox wrapLinesCheckBox;

    // Tab settings
    private final JCheckBox confirmCloseCheckBox;
    private final JCheckBox confirmHighlightClearAllCheckBox;
    private final JCheckBox confirmAnnotationDeleteCheckBox;
    private final JCheckBox followByDefaultCheckBox;

    // Files settings
    private final JSpinner recentMaxSpinner;

    // Memory settings
    private final JSpinner maxEntriesSpinner;

    // Appearance settings
    private final JComboBox<String> themeCombo;
    private final String originalThemeName;

    SettingsDialog(Frame owner, LogPanel logPanel, Runnable onApply) {
        super(owner, Messages.get("settings.title"), true);
        this.logPanel = logPanel;
        this.onApply  = onApply;

        rowHeightSpinner       = new JSpinner(new SpinnerNumberModel(AppPrefs.getRowHeight(), 14, 64, 1));
        detailFontSizeSpinner  = new JSpinner(new SpinnerNumberModel(AppPrefs.getDetailFontSize(), 8, 36, 1));
        wrapLinesCheckBox      = new JCheckBox(Messages.get("settings.detail.wrap"), AppPrefs.isDetailWrapLines());
        confirmCloseCheckBox            = new JCheckBox(Messages.get("settings.tab.confirm.close"),             AppPrefs.isTabCloseConfirm());
        confirmHighlightClearAllCheckBox = new JCheckBox(Messages.get("settings.highlight.confirm.clear.all"), AppPrefs.isHighlightClearAllConfirm());
        confirmAnnotationDeleteCheckBox  = new JCheckBox(Messages.get("settings.annotation.confirm.delete"),   AppPrefs.isAnnotationDeleteConfirm());
        followByDefaultCheckBox = new JCheckBox(Messages.get("settings.follow.by.default"), AppPrefs.isFollowByDefault());
        recentMaxSpinner       = new JSpinner(new SpinnerNumberModel(AppPrefs.getRecentFilesMax(), 1, 10, 1));
        maxEntriesSpinner      = new JSpinner(new SpinnerNumberModel(AppPrefs.getMaxEntriesPerTab(), 10_000, 2_000_000, 10_000));

        originalThemeName = AppPrefs.getTheme();
        themeCombo = new JComboBox<>();
        ThemeManager.getAllThemes().forEach(t -> themeCombo.addItem(t.name()));
        themeCombo.setSelectedItem(originalThemeName);
        themeCombo.addActionListener(e -> {
            String sel = (String) themeCombo.getSelectedItem();
            if (sel != null) {
                ThemeManager.applyTheme(sel);
                SwingUtilities.invokeLater(ThemeManager::updateUI);
            }
        });

        JPanel content = new JPanel(new MigLayout("insets 16, wrap 2, gapy 6", "[grow,fill][120px!]"));

        // ── Table section ────────────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.table")), "span 2, gaptop 0, gapbottom 4");
        content.add(new JLabel(Messages.get("settings.row.height")));
        content.add(rowHeightSpinner);

        // ── Detail panel section ─────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.detail")), "span 2, gaptop 12, gapbottom 4");
        content.add(new JLabel(Messages.get("settings.detail.font.size")));
        content.add(detailFontSizeSpinner);
        content.add(wrapLinesCheckBox, "span 2");

        // ── Tabs section ──────────────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.tabs")), "span 2, gaptop 12, gapbottom 4");
        content.add(confirmCloseCheckBox, "span 2");
        content.add(confirmHighlightClearAllCheckBox, "span 2");
        content.add(confirmAnnotationDeleteCheckBox, "span 2");
        content.add(followByDefaultCheckBox, "span 2");
        content.add(new JLabel(Messages.get("settings.max.entries.per.tab")));
        content.add(maxEntriesSpinner);

        // ── Files section ─────────────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.files")), "span 2, gaptop 12, gapbottom 4");
        content.add(new JLabel(Messages.get("settings.recent.max")));
        content.add(recentMaxSpinner);

        // ── Appearance section ────────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.appearance")), "span 2, gaptop 12, gapbottom 4");
        JButton manageThemesBtn = new JButton(Messages.get("settings.theme.manage"));
        manageThemesBtn.addActionListener(e -> {
            new ThemeManagerDialog(SwingUtilities.getWindowAncestor(this)).setVisible(true);
            String current = (String) themeCombo.getSelectedItem();
            themeCombo.removeAllItems();
            ThemeManager.getAllThemes().forEach(t -> themeCombo.addItem(t.name()));
            themeCombo.setSelectedItem(current != null ? current : AppPrefs.getTheme());
        });
        JPanel themeRow = new JPanel(new MigLayout("insets 0", "[][grow][]"));
        themeRow.add(new JLabel(Messages.get("settings.theme")));
        themeRow.add(themeCombo, "grow");
        themeRow.add(manageThemesBtn);
        content.add(themeRow, "span 2, growx");

        // ── Buttons ──────────────────────────────────────────────────────
        JButton ok     = new JButton(Messages.get("settings.button.ok"));
        JButton cancel = new JButton(Messages.get("settings.button.cancel"));
        ok.addActionListener(e -> { applySettings(); dispose(); });
        cancel.addActionListener(e -> {
            String cur = (String) themeCombo.getSelectedItem();
            if (!originalThemeName.equals(cur)) {
                ThemeManager.applyTheme(originalThemeName);
                ThemeManager.updateUI();
            }
            dispose();
        });

        JPanel buttons = new JPanel(new MigLayout("insets 8 0 0 0, align right", "[][]"));
        buttons.add(cancel);
        buttons.add(ok);
        content.add(buttons, "span 2, growx");

        setContentPane(content);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void applySettings() {
        JTable logTable = logPanel != null ? logPanel.getLogTable() : null;
        if (logTable != null) logTable.setRowHeight((int) rowHeightSpinner.getValue());
        int fontSize = (int) detailFontSizeSpinner.getValue();
        AppPrefs.setDetailFontSize(fontSize);
        if (logPanel != null) logPanel.applyDetailFontSize(fontSize);
        AppPrefs.setTabCloseConfirm(confirmCloseCheckBox.isSelected());
        AppPrefs.setHighlightClearAllConfirm(confirmHighlightClearAllCheckBox.isSelected());
        AppPrefs.setAnnotationDeleteConfirm(confirmAnnotationDeleteCheckBox.isSelected());
        AppPrefs.setFollowByDefault(followByDefaultCheckBox.isSelected());
        AppPrefs.setRecentFilesMax((int) recentMaxSpinner.getValue());
        AppPrefs.setMaxEntriesPerTab((int) maxEntriesSpinner.getValue());
        String selTheme = (String) themeCombo.getSelectedItem();
        if (selTheme != null) AppPrefs.setTheme(selTheme);
        if (onApply != null) onApply.run();
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }
}
