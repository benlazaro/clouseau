package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ThemeManager;
import com.droidenx.clouseau.ui.theme.ThemeManagerDialog;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.time.ZoneId;

/**
 * Modal application settings / user preferences dialog.
 */
final class SettingsDialog extends JDialog {

    /** One width for every spinner, wide enough for "2,000,000" plus the arrows. */
    private static final String SPINNER = "w 120!";

    private final TabPanel tabPanel;
    private final Runnable onApply;

    // Table settings
    private final JSpinner rowHeightSpinner;
    private final JCheckBox stripesCheckBox;

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
    private final JComboBox<String> multiOpenCombo;
    private final JComboBox<String> startupCombo;

    // Memory settings
    private final JSpinner maxEntriesSpinner;

    // Appearance settings
    private final JComboBox<String> themeCombo;
    private final String originalThemeName;
    private final JComboBox<TimezoneEntry> timezoneCombo;

    SettingsDialog(Frame owner, TabPanel tabPanel, Runnable onApply) {
        super(owner, Messages.get("settings.title"), true);
        this.tabPanel = tabPanel;
        this.onApply  = onApply;

        rowHeightSpinner       = new JSpinner(new SpinnerNumberModel(AppPrefs.getRowHeight(), 14, 64, 1));
        stripesCheckBox        = new JCheckBox(Messages.get("settings.table.stripes"), AppPrefs.isTableStripes());
        detailFontSizeSpinner  = new JSpinner(new SpinnerNumberModel(AppPrefs.getDetailFontSize(), 8, 36, 1));
        wrapLinesCheckBox      = new JCheckBox(Messages.get("settings.detail.wrap"), AppPrefs.isDetailWrapLines());
        confirmCloseCheckBox            = new JCheckBox(Messages.get("settings.tab.confirm.close"),             AppPrefs.isTabCloseConfirm());
        confirmHighlightClearAllCheckBox = new JCheckBox(Messages.get("settings.highlight.confirm.clear.all"), AppPrefs.isHighlightClearAllConfirm());
        confirmAnnotationDeleteCheckBox  = new JCheckBox(Messages.get("settings.annotation.confirm.delete"),   AppPrefs.isAnnotationDeleteConfirm());
        followByDefaultCheckBox = new JCheckBox(Messages.get("settings.follow.by.default"), AppPrefs.isFollowByDefault());
        recentMaxSpinner       = new JSpinner(new SpinnerNumberModel(AppPrefs.getRecentFilesMax(), 1, 10, 1));
        multiOpenCombo = new JComboBox<>(new String[] {
                Messages.get("settings.multi.open.ask"), Messages.get("settings.multi.open.merge"),
                Messages.get("settings.multi.open.separate") });
        multiOpenCombo.setSelectedIndex(AppPrefs.getMultiOpen().ordinal());
        startupCombo = new JComboBox<>(new String[] {
                Messages.get("settings.startup.restore"), Messages.get("settings.startup.empty") });
        startupCombo.setSelectedIndex(AppPrefs.isRestoreSession() ? 0 : 1);
        maxEntriesSpinner      = new JSpinner(new SpinnerNumberModel(AppPrefs.getMaxEntriesPerTab(), 10_000, 2_000_000, 10_000));

        timezoneCombo = buildTimezoneCombo(AppPrefs.getDisplayTimezone());

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

        // The field column sizes to its widest control (the timezone combo), so nothing
        // overflows the dialog's right edge; the same insets pad every side.
        // Spinners keep one fixed width, left-aligned in that column; combos fill it.
        JPanel content = new JPanel(new MigLayout("insets 20, wrap 2, gapy 6", "[]24[grow]"));

        // ── Table section ────────────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.table")), "span 2, growx, gaptop 0, gapbottom 4");
        content.add(new JLabel(Messages.get("settings.row.height")));
        content.add(rowHeightSpinner, SPINNER);
        content.add(stripesCheckBox, "span 2");

        // ── Detail panel section ─────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.detail")), "span 2, growx, gaptop 12, gapbottom 4");
        content.add(new JLabel(Messages.get("settings.detail.font.size")));
        content.add(detailFontSizeSpinner, SPINNER);
        content.add(wrapLinesCheckBox, "span 2");

        // ── Tabs section ──────────────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.tabs")), "span 2, growx, gaptop 12, gapbottom 4");
        content.add(confirmCloseCheckBox, "span 2");
        content.add(confirmHighlightClearAllCheckBox, "span 2");
        content.add(confirmAnnotationDeleteCheckBox, "span 2");
        content.add(followByDefaultCheckBox, "span 2");
        content.add(new JLabel(Messages.get("settings.max.entries.per.tab")));
        content.add(maxEntriesSpinner, SPINNER);

        // ── Files section ─────────────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.files")), "span 2, growx, gaptop 12, gapbottom 4");
        content.add(new JLabel(Messages.get("settings.recent.max")));
        content.add(recentMaxSpinner, SPINNER);
        content.add(new JLabel(Messages.get("settings.multi.open")));
        content.add(multiOpenCombo, "growx");
        content.add(new JLabel(Messages.get("settings.startup")));
        content.add(startupCombo, "growx");

        // ── Appearance section ────────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.appearance")), "span 2, growx, gaptop 12, gapbottom 4");
        JButton manageThemesBtn = new JButton(Messages.get("settings.theme.manage"));
        manageThemesBtn.addActionListener(e -> {
            new ThemeManagerDialog(SwingUtilities.getWindowAncestor(this)).setVisible(true);
            String current = (String) themeCombo.getSelectedItem();
            themeCombo.removeAllItems();
            ThemeManager.getAllThemes().forEach(t -> themeCombo.addItem(t.name()));
            themeCombo.setSelectedItem(current != null ? current : AppPrefs.getTheme());
        });
        JPanel themeRow = new JPanel(new MigLayout("insets 0", "[grow,fill][]"));
        themeRow.add(themeCombo);
        themeRow.add(manageThemesBtn);
        content.add(new JLabel(Messages.get("settings.theme")));
        content.add(themeRow, "growx");
        content.add(new JLabel(Messages.get("settings.timezone")));
        content.add(timezoneCombo, "growx");

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
        // Saved, then applied to every open tab by onApply (TabPanel.applyTableSettings).
        AppPrefs.setRowHeight((int) rowHeightSpinner.getValue());
        AppPrefs.setTableStripes(stripesCheckBox.isSelected());
        int fontSize = (int) detailFontSizeSpinner.getValue();
        AppPrefs.setDetailFontSize(fontSize);
        if (tabPanel != null) tabPanel.applyDetailFontSize(fontSize);
        AppPrefs.setTabCloseConfirm(confirmCloseCheckBox.isSelected());
        AppPrefs.setHighlightClearAllConfirm(confirmHighlightClearAllCheckBox.isSelected());
        AppPrefs.setAnnotationDeleteConfirm(confirmAnnotationDeleteCheckBox.isSelected());
        AppPrefs.setFollowByDefault(followByDefaultCheckBox.isSelected());
        AppPrefs.setRecentFilesMax((int) recentMaxSpinner.getValue());
        AppPrefs.setMultiOpen(AppPrefs.MultiOpen.values()[multiOpenCombo.getSelectedIndex()]);
        AppPrefs.setRestoreSession(startupCombo.getSelectedIndex() == 0);
        AppPrefs.setMaxEntriesPerTab((int) maxEntriesSpinner.getValue());
        String selTheme = (String) themeCombo.getSelectedItem();
        if (selTheme != null) AppPrefs.setTheme(selTheme);
        TimezoneEntry selTz = (TimezoneEntry) timezoneCombo.getSelectedItem();
        if (selTz != null) AppPrefs.setDisplayTimezone(selTz.zone());
        if (onApply != null) onApply.run();
    }

    // ── Timezone helpers ──────────────────────────────────────────────────────

    record TimezoneEntry(ZoneId zone, String label) {
        @Override public String toString() { return label; }
    }

    private static final String[] CURATED_ZONE_IDS = {
        "UTC",
        "US/Eastern", "US/Central", "US/Mountain", "US/Pacific",
        "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
        "America/Sao_Paulo", "America/Toronto", "America/Vancouver",
        "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Amsterdam",
        "Europe/Moscow", "Europe/Istanbul",
        "Asia/Kolkata", "Asia/Dubai", "Asia/Singapore", "Asia/Shanghai",
        "Asia/Tokyo", "Asia/Seoul", "Asia/Jakarta",
        "Australia/Sydney", "Australia/Melbourne", "Pacific/Auckland",
        "Africa/Johannesburg", "Africa/Cairo",
    };

    static JComboBox<TimezoneEntry> buildTimezoneCombo(ZoneId current) {
        ZoneId systemZone = ZoneId.systemDefault();

        JComboBox<TimezoneEntry> combo = new JComboBox<>();
        combo.addItem(new TimezoneEntry(systemZone, "System default (" + systemZone.getId() + ")"));
        TimezoneEntry selected = (TimezoneEntry) combo.getItemAt(0);

        for (String id : CURATED_ZONE_IDS) {
            try {
                ZoneId zone = ZoneId.of(id);
                if (zone.equals(systemZone)) continue; // already shown as System default
                TimezoneEntry entry = new TimezoneEntry(zone, id);
                combo.addItem(entry);
                if (zone.equals(current)) selected = entry;
            } catch (Exception ignored) {}
        }

        combo.setSelectedItem(selected);
        return combo;
    }

    /** A section title, as IntelliJ's settings show one: regular weight, with a hairline to the right edge. */
    private static JComponent sectionLabel(String text) {
        JPanel title = new JPanel(new MigLayout("insets 0, gap 8", "[][grow]", "[center]"));
        title.setOpaque(false);
        title.add(new JLabel(text));
        title.add(new JSeparator(), "growx");
        return title;
    }
}
