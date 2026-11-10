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
    /** Settings sit indented under their section title, so the titles stand out. */
    private static final String INDENT = "gapleft 28";

    /** The dialog opens at least this wide... */
    private static final int MIN_WIDTH = 760;
    /** ...and at most this fraction of its width tall. */
    private static final float ASPECT = 0.72f;

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

        // Three columns: labels, fields, and a growing filler. The field column sizes to its
        // widest control (the timezone combo), so a wide window doesn't stretch the combos;
        // section titles and checkboxes span all three, so the title rules reach the right edge.
        // Spinners keep one fixed width, left-aligned in the field column; combos fill it.
        // Padding follows IntelliJ's settings: generous around the settings, tighter around
        // the buttons, with a divider between them.
        JPanel content = new ScrollablePanel(new MigLayout("insets 24 28 24 28, gapy 10", "[]24[][grow]"));

        // ── Table section ────────────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.table")), "span 3, growx, wrap, gaptop 0, gapbottom 8");
        content.add(new JLabel(Messages.get("settings.row.height")), INDENT);
        content.add(rowHeightSpinner, SPINNER + ", wrap");
        content.add(stripesCheckBox, "span 3, wrap, " + INDENT);

        // ── Detail panel section ─────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.detail")), "span 3, growx, wrap, gaptop 24, gapbottom 8");
        content.add(new JLabel(Messages.get("settings.detail.font.size")), INDENT);
        content.add(detailFontSizeSpinner, SPINNER + ", wrap");
        content.add(wrapLinesCheckBox, "span 3, wrap, " + INDENT);

        // ── Tabs section ──────────────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.tabs")), "span 3, growx, wrap, gaptop 24, gapbottom 8");
        content.add(confirmCloseCheckBox, "span 3, wrap, " + INDENT);
        content.add(confirmHighlightClearAllCheckBox, "span 3, wrap, " + INDENT);
        content.add(confirmAnnotationDeleteCheckBox, "span 3, wrap, " + INDENT);
        content.add(followByDefaultCheckBox, "span 3, wrap, " + INDENT);
        content.add(new JLabel(Messages.get("settings.max.entries.per.tab")), INDENT);
        content.add(maxEntriesSpinner, SPINNER + ", wrap");

        // ── Files section ─────────────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.files")), "span 3, growx, wrap, gaptop 24, gapbottom 8");
        content.add(new JLabel(Messages.get("settings.recent.max")), INDENT);
        content.add(recentMaxSpinner, SPINNER + ", wrap");
        content.add(new JLabel(Messages.get("settings.multi.open")), INDENT);
        content.add(multiOpenCombo, "growx, wrap");
        content.add(new JLabel(Messages.get("settings.startup")), INDENT);
        content.add(startupCombo, "growx, wrap");

        // ── Appearance section ────────────────────────────────────────────
        content.add(sectionLabel(Messages.get("settings.section.appearance")), "span 3, growx, wrap, gaptop 24, gapbottom 8");
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
        content.add(new JLabel(Messages.get("settings.theme")), INDENT);
        content.add(themeRow, "growx, wrap");
        content.add(new JLabel(Messages.get("settings.timezone")), INDENT);
        content.add(timezoneCombo, "growx, wrap");

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

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        // Open at the first section, whichever control takes the focus.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(new Point(0, 0)));
            }
        });

        JPanel buttons = new JPanel(new MigLayout("insets 14 28 14 28, align right", "[][]"));
        buttons.add(cancel);
        buttons.add(ok);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(new JSeparator(), BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.add(scroll, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        sizeLandscape();
        setLocationRelativeTo(owner);
    }

    /**
     * Opens wider than tall, as IntelliJ's settings do: at least {@link #MIN_WIDTH} wide and
     * at most {@link #ASPECT} of that width tall, so the settings scroll when they don't fit.
     * The window stays resizable, and never opens bigger than the screen.
     */
    private void sizeLandscape() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        Rectangle screen = gc.getBounds();
        Insets taskbar = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        int maxW = screen.width - taskbar.left - taskbar.right;
        int maxH = screen.height - taskbar.top - taskbar.bottom;
        Dimension packed = getSize();
        int w = Math.min(Math.max(packed.width, MIN_WIDTH), maxW);
        int h = Math.min(Math.min(packed.height, Math.round(w * ASPECT)), maxH);
        setSize(w, h);
        setMinimumSize(new Dimension(Math.min(w, 420), Math.min(h, 280)));
        setResizable(true);
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

    /**
     * The settings inside the scroll pane: they fill the viewport when the window is big
     * enough, and scroll (either way) when it isn't.
     */
    private static final class ScrollablePanel extends JPanel implements Scrollable {
        private static final int UNIT = 16;

        ScrollablePanel(LayoutManager layout) { super(layout); }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return UNIT;
        }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(UNIT, (orientation == SwingConstants.VERTICAL ? visible.height : visible.width) - UNIT);
        }
        @Override public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport v && v.getWidth() >= getPreferredSize().width;
        }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport v && v.getHeight() >= getPreferredSize().height;
        }
    }
}
