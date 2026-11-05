package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSourceProvider;
import com.droidenx.clouseau.api.SourceSetting;
import com.droidenx.clouseau.core.MergeLoad;
import com.droidenx.clouseau.core.ParserList;
import com.droidenx.clouseau.core.ReviewBundle;
import com.droidenx.clouseau.core.SshLogSource;
import com.droidenx.clouseau.core.ZipLogs;
import com.droidenx.clouseau.runtime.ClouseauPluginManager;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.ui.theme.ThemeManager;
import com.droidenx.clouseau.ui.theme.ThemeManagerDialog;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
public final class MainFrame extends JFrame {

    private final ClouseauPluginManager pluginManager;
    private List<LogParser> parsers;
    private List<LogFormatter> formatters;
    private List<LogSyntaxHighlighter> syntaxHighlighters;
    private List<LogSourceProvider> sourceProviders = List.of();
    private final JTabbedPane tabbedPane = new JTabbedPane();
    private final CardLayout cardLayout  = new CardLayout();
    private final JPanel contentArea     = new JPanel(cardLayout);
    private int lastParserIndex = 0;
    private JMenuItem closeTabItem;
    private JMenu recentMenu;

    private static final String CARD_WELCOME = "welcome";
    private static final String CARD_TABS    = "tabs";

    public MainFrame(ClouseauPluginManager pluginManager) {
        super(Messages.get("app.title"));
        this.pluginManager = pluginManager;
        reloadExtensions();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 800);
        setMinimumSize(new Dimension(800, 500));
        setLocationRelativeTo(null);
        setIconImages(loadAppIcons());

        contentArea.add(buildWelcomePanel(), CARD_WELCOME);
        contentArea.add(tabbedPane,          CARD_TABS);

        setLayout(new MigLayout("fill, insets 0", "[grow]", "[grow]"));
        setJMenuBar(buildMenuBar());
        add(contentArea, "grow");

        // VK-based binding drives the actual Ctrl+, shortcut.
        // The menu item uses a char-based KeyStroke so it renders "," not "Comma".
        KeyStroke settingsKs = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_COMMA,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(settingsKs, "openSettings");
        getRootPane().getActionMap().put("openSettings", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { openSettings(); }
        });

        // Dropped files open like "Open with": auto-detect; several can be merged (UC-1, UC-12).
        FileDropHandler.install(this, this::openFiles);

        // Tab switching (UC-15): a dispatcher, not key bindings; see TabShortcuts.
        KeyEventDispatcher tabShortcuts = new TabShortcuts(this, tabbedPane);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(tabShortcuts);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(tabShortcuts);
            }
        });
    }

    // ── Extension list management ─────────────────────────────────────────────

    /**
     * Rebuilds the parser list: JSON-defined parsers (built-in and {@code parsers.json})
     * first, then plugin parsers (see {@link ParserList}). Called at startup, after
     * parsers.json is saved, and after a plugin refresh.
     */
    void reloadParsers() {
        this.parsers = ParserList.combine(UserParsersLoader.load(),
                pluginManager.getExtensions(LogParser.class));
        if (lastParserIndex > parsers.size()) lastParserIndex = 0;
        log.info("Loaded {} parser(s): {}", parsers.size(),
                parsers.stream().map(LogParser::getName).toList());
    }

    /**
     * Fetches parsers, formatters and highlighters, including those from plugins.
     * Called at startup and after a plugin refresh. Open tabs keep the extensions they
     * were created with; new tabs get the new ones.
     */
    void reloadExtensions() {
        reloadParsers();

        this.sourceProviders = List.copyOf(pluginManager.getExtensions(LogSourceProvider.class));
        log.info("Loaded {} source provider(s)", sourceProviders.size());

        this.formatters = new ArrayList<>();
        this.formatters.add(new JsonMessageFormatter());
        this.formatters.addAll(pluginManager.getExtensions(LogFormatter.class));
        log.info("Loaded {} formatter(s): {}", formatters.size(),
                formatters.stream().map(LogFormatter::getName).toList());

        this.syntaxHighlighters = new ArrayList<>();
        this.syntaxHighlighters.add(new JsonSyntaxHighlighter());
        this.syntaxHighlighters.addAll(pluginManager.getExtensions(LogSyntaxHighlighter.class));
        log.info("Loaded {} syntax highlighter(s): {}", syntaxHighlighters.size(),
                syntaxHighlighters.stream().map(LogSyntaxHighlighter::getName).toList());
    }

    // ── Menu bar ─────────────────────────────────────────────────────────────

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        menuBar.setBorder(BorderFactory.createEmptyBorder(5, 1, 2, 0));

        JMenu fileMenu = new JMenu(Messages.get("menu.file"));

        JMenuItem openItem = menuItem(Messages.get("menu.file.open"));
        openItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        openItem.addActionListener(e -> openFile());
        fileMenu.add(openItem);

        recentMenu = new JMenu(Messages.get("menu.file.recent"));
        Insets menuDefaults = UIManager.getInsets("Menu.margin");
        int mh = menuDefaults != null ? menuDefaults.left : 0;
        recentMenu.setMargin(new Insets(7, mh, 7, mh));
        fileMenu.add(recentMenu);
        refreshRecentMenu();

        JMenuItem sshItem = menuItem(Messages.get("menu.file.ssh"));
        sshItem.addActionListener(e -> openSsh());
        fileMenu.add(sshItem);

        // Rebuilt each time it opens, so sources from newly installed plugins appear.
        JMenu sourceMenu = new JMenu(Messages.get("menu.file.source"));
        sourceMenu.setMargin(new Insets(7, mh, 7, mh));
        sourceMenu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) { rebuildSourceMenu(sourceMenu); }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) {}
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        fileMenu.add(sourceMenu);

        JMenuItem exportReviewItem = menuItem(Messages.get("menu.file.export.review"));
        exportReviewItem.addActionListener(e -> ReviewExporter.export(this, activeLogPanel()));
        fileMenu.add(exportReviewItem);

        closeTabItem = menuItem(Messages.get("menu.file.close.tab"));
        closeTabItem.setEnabled(false);
        closeTabItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_W,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        closeTabItem.addActionListener(e -> closeTab(tabbedPane.getSelectedIndex()));
        fileMenu.add(closeTabItem);

        fileMenu.addSeparator();

        // KeyStroke.getKeyStroke(char, int) casts char to int → ',' (44) == VK_COMMA,
        // so getKeyText renders "Comma". The string form with "typed" creates a true
        // char-based stroke (keyCode=0, keyChar=',') that renders the literal character.
        // The actual Ctrl+, binding lives in the root-pane InputMap in the constructor.
        int shortcutMask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        String modName = (shortcutMask & java.awt.event.InputEvent.META_DOWN_MASK) != 0 ? "meta" : "ctrl";
        JMenuItem settingsItem = menuItem(Messages.get("menu.file.settings"));
        settingsItem.setAccelerator(KeyStroke.getKeyStroke(modName + " typed ,"));
        settingsItem.addActionListener(e -> openSettings());
        fileMenu.add(settingsItem);

        fileMenu.addSeparator();

        JMenuItem exitItem = menuItem(Messages.get("menu.file.exit"));
        exitItem.addActionListener(e -> dispose());
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);

        JMenu viewMenu = new JMenu(Messages.get("menu.view"));
        // Rebuilt each time it opens, so newly installed themes and a theme picked in
        // Settings show up.
        JMenu themeMenu = new JMenu(Messages.get("menu.view.theme"));
        themeMenu.setMargin(new Insets(7, mh, 7, mh));
        themeMenu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) { rebuildThemeMenu(themeMenu); }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) {}
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        rebuildThemeMenu(themeMenu);
        viewMenu.add(themeMenu);
        menuBar.add(viewMenu);

        JMenu toolsMenu = new JMenu(Messages.get("menu.tools"));
        JMenuItem parsersItem = menuItem(Messages.get("menu.tools.parsers"));
        parsersItem.addActionListener(e ->
                new ParserEditorDialog(this, this::reloadParsers).setVisible(true));
        toolsMenu.add(parsersItem);
        JMenuItem pluginsItem = menuItem(Messages.get("menu.tools.plugins"));
        pluginsItem.setToolTipText(Messages.get("toolbar.plugins.tooltip"));
        pluginsItem.addActionListener(e ->
                new PluginManagerDialog(this, pluginManager, this::reloadExtensions).setVisible(true));
        toolsMenu.add(pluginsItem);
        menuBar.add(toolsMenu);

        JMenu helpMenu = new JMenu(Messages.get("menu.help"));

        JMenuItem docsItem = menuItem(Messages.get("menu.help.docs"));
        docsItem.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(new java.net.URI("https://github.com/benlazaro/clouseau#readme"));
            } catch (Exception ex) {
                log.warn("Could not open documentation URL", ex);
            }
        });
        helpMenu.add(docsItem);

        JMenuItem shortcutsItem = menuItem(Messages.get("menu.help.shortcuts"));
        shortcutsItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SLASH,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
        shortcutsItem.addActionListener(e -> openShortcuts());
        helpMenu.add(shortcutsItem);

        helpMenu.addSeparator();

        JMenuItem aboutItem = menuItem(Messages.get("menu.help.about"));
        aboutItem.addActionListener(e -> openAbout());
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);
        return menuBar;
    }

    /** One radio item per theme (the current one selected), then "Manage Themes…". */
    private void rebuildThemeMenu(JMenu menu) {
        menu.removeAll();
        String current = AppPrefs.getTheme();
        ButtonGroup group = new ButtonGroup();
        for (ThemeManager.ThemeEntry theme : ThemeManager.getAllThemes()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(theme.name(), theme.name().equals(current));
            item.setMargin(new Insets(7, 0, 7, 0));
            item.addActionListener(e -> selectTheme(theme.name()));
            group.add(item);
            menu.add(item);
        }
        menu.addSeparator();
        JMenuItem manage = menuItem(Messages.get("menu.view.theme.manage"));
        manage.addActionListener(e -> new ThemeManagerDialog(this).setVisible(true));
        menu.add(manage);
    }

    /** Applies and saves a theme: the same steps as the Settings dropdown plus OK. */
    private void selectTheme(String name) {
        log.info("Switching theme to {}", name);
        ThemeManager.applyTheme(name);
        AppPrefs.setTheme(name);
        // After the menu has closed, so the popup isn't repainted mid-switch.
        SwingUtilities.invokeLater(ThemeManager::updateUI);
    }

    private void openShortcuts() {
        JDialog dialog = new JDialog(this, Messages.get("shortcuts.title"), true);

        // Single unified grid — all rows share the same two columns so descriptions
        // and badges stay perfectly aligned across every section.
        JPanel grid = new JPanel(new MigLayout(
                "insets 24, wrap 2, gapy 10, gapx 40",
                "[grow, fill][right]"));

        addDialogSection(grid, Messages.get("shortcuts.section.file"),  "gaptop 0");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.open"),      "Ctrl+O");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.close.tab"), "Ctrl+W");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.settings"),  "Ctrl+,");

        addDialogSection(grid, Messages.get("shortcuts.section.tabs"), "gaptop 20");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.tab.next"),      "Ctrl+Tab");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.tab.prev"),      "Ctrl+Shift+Tab");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.tab.next"),      "Ctrl+PgDn");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.tab.prev"),      "Ctrl+PgUp");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.tab.jump"),      "Ctrl+1\u20268");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.tab.last"),      "Ctrl+9");

        addDialogSection(grid, Messages.get("shortcuts.section.table"), "gaptop 20");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.first.row"),  "Ctrl+Home");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.last.row"),   "Ctrl+End");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.solo.level"), "Alt+Click");

        addDialogSection(grid, Messages.get("shortcuts.section.find"),  "gaptop 20");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.find"),       "Ctrl+F");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.find.next"),  "Enter");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.find.prev"),  "Shift+Enter");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.find.close"), "Esc");

        dialog.setContentPane(grid);
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setSize(Math.max(dialog.getWidth(), 440), dialog.getHeight());
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private static void addDialogSection(JPanel grid, String title, String gapConstraint) {
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setForeground(ClouseauColors.accentBlue());
        grid.add(label, "span 2, " + gapConstraint + ", gapbottom 2");
        JSeparator sep = new JSeparator();
        sep.setForeground(ClouseauColors.separatorColor());
        grid.add(sep, "span 2, growx, gapbottom 4");
    }

    private void openAbout() {
        BufferedImage icon64 = loadIconImage("about-64.png");
        JOptionPane.showMessageDialog(
                this,
                "<html><b>" + Messages.get("about.version") + "</b>"
                        + "<br><font color='gray'>" + AppPrefs.getAppVersion() + "</font>"
                        + "<br><br>" + Messages.get("about.description") + "</html>",
                Messages.get("about.title"),
                JOptionPane.INFORMATION_MESSAGE,
                icon64 != null ? new ImageIcon(icon64) : null);
    }

    /** Creates a {@link JMenuItem} with VSCode-style vertical padding pre-applied. */
    private static JMenuItem menuItem(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setMargin(new Insets(7, 0, 7, 0));
        return item;
    }

    private void openSettings() {
        new SettingsDialog(this, activeLogPanel(), () -> {
            refreshRecentMenu();
            refreshAllTabTimezones();
        }).setVisible(true);
    }

    private void refreshAllTabTimezones() {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getComponentAt(i) instanceof LogPanel lp) {
                lp.refreshTimezone();
            }
        }
    }

    private void refreshRecentMenu() {
        recentMenu.removeAll();
        java.util.List<java.nio.file.Path> recents = AppPrefs.getRecentFiles();
        recentMenu.setEnabled(!recents.isEmpty());
        for (java.nio.file.Path p : recents) {
            JMenuItem item = menuItem(p.getFileName().toString());
            item.setToolTipText(p.toAbsolutePath().toString());
            item.addActionListener(e -> openFile(p));
            recentMenu.add(item);
        }
        if (!recents.isEmpty()) {
            recentMenu.addSeparator();
            JMenuItem clear = menuItem(Messages.get("menu.file.recent.clear"));
            clear.addActionListener(e -> { AppPrefs.clearRecentFiles(); refreshRecentMenu(); });
            recentMenu.add(clear);
        }
    }

    // ── File opening ──────────────────────────────────────────────────────────

    private void openFile() {
        new FileChooserDialog(this, parsers, lastParserIndex).showDialog().ifPresent(r -> {
            lastParserIndex = r.parserIndex();
            ZoneId srcTz = r.sourceTimezone();
            for (Path file : r.files()) {
                log.info("Opening {} with parser: {}", file.getFileName(),
                        r.parser().map(LogParser::getName).orElse("Auto-detect"));
                // Persist the selected source timezone so CLI/double-click reopens use it.
                if (!srcTz.equals(ZoneId.systemDefault())) {
                    AppPrefs.setSourceTimezone(file.toAbsolutePath(), srcTz);
                }
            }
            openFiles(r.files(), r.parser());
        });
    }

    /**
     * Opens several files: zips as usual, and the rest in one merged view or each in its
     * own tab, as the user chooses (D-17, UC-12).
     */
    private void openFiles(List<Path> files, Optional<LogParser> parser) {
        List<Path> logs = new ArrayList<>();
        for (Path f : files) {
            if (f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) openFile(f, parser);
            else logs.add(f);
        }
        if (logs.size() >= 2) {
            AppPrefs.MultiOpen how = chooseMultiOpen(logs.size());
            if (how == null) return;
            if (how == AppPrefs.MultiOpen.MERGE) {
                openMerged(logs.stream().map(p -> new MergeLoad.File(p, null)).toList(), parser);
                return;
            }
        }
        for (Path f : logs) openFile(f, parser);
    }

    /** Asks whether several files open merged or in separate tabs, unless remembered; null = cancel. */
    private AppPrefs.MultiOpen chooseMultiOpen(int count) {
        AppPrefs.MultiOpen saved = AppPrefs.getMultiOpen();
        if (saved != AppPrefs.MultiOpen.ASK) return saved;
        JCheckBox remember = new JCheckBox(Messages.get("merge.ask.remember"));
        Object[] message = { MessageFormat.format(Messages.get("merge.ask.message"), count), remember };
        String merge = Messages.get("merge.ask.merge"), separate = Messages.get("merge.ask.separate");
        int answer = JOptionPane.showOptionDialog(this, message, Messages.get("merge.ask.title"),
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new Object[] { merge, separate, Messages.get("settings.button.cancel") }, merge);
        AppPrefs.MultiOpen how = answer == 0 ? AppPrefs.MultiOpen.MERGE
                : answer == 1 ? AppPrefs.MultiOpen.SEPARATE : null;
        if (how != null && remember.isSelected()) AppPrefs.setMultiOpen(how);
        return how;
    }

    /** Opens {@code files} in one merged view (D-17). */
    private void openMerged(List<MergeLoad.File> files, Optional<LogParser> parser) {
        files.forEach(f -> AppPrefs.addRecentFile(f.path()));
        refreshRecentMenu();
        LogPanel panel = new LogPanel(parsers, formatters, syntaxHighlighters);
        ZoneId savedTz = AppPrefs.getSourceTimezone(files.get(0).path());
        if (savedTz != null) panel.setSourceTimezone(savedTz);
        String title = MessageFormat.format(Messages.get("merge.tab.title"), files.get(0).fileName(), files.size() - 1);
        addLogTab(title, null, panel);
        panel.setCloseAction(() -> removeTab(panel));
        panel.loadMerged(files, parser, new LogPanel.MergeListener() {
            @Override public void skipped(List<MergeLoad.Skipped> skipped) { offerSkipped(skipped, parser); }

            @Override public void notMerged(List<MergeLoad.File> mergeable) {
                removeTab(panel);
                for (MergeLoad.File f : mergeable) openLog(f.path(), f.entry(), parser);
            }

            @Override public void failed() { removeTab(panel); }

            @Override public void merged(List<MergeLoad.File> merged) {
                setTabTitle(panel, MessageFormat.format(Messages.get("merge.tab.title"),
                        merged.get(0).fileName(), merged.size() - 1));
            }
        });
    }

    /** Renames the tab showing {@code panel}. */
    private void setTabTitle(LogPanel panel, String title) {
        int idx = tabbedPane.indexOfComponent(panel);
        if (idx >= 0 && tabbedPane.getTabComponentAt(idx) instanceof JPanel header
                && header.getComponentCount() > 0 && header.getComponent(0) instanceof JLabel label) {
            label.setText(title);
            header.revalidate();
        }
    }

    /** Says which files couldn't be merged, and offers to open them in their own tabs. */
    private void offerSkipped(List<MergeLoad.Skipped> skipped, Optional<LogParser> parser) {
        StringBuilder list = new StringBuilder();
        for (MergeLoad.Skipped s : skipped) {
            list.append("\n  • ").append(s.file().fileName()).append(" — ")
                .append(Messages.get("merge.skipped." + s.reason().name().toLowerCase(java.util.Locale.ROOT)));
        }
        int answer = JOptionPane.showConfirmDialog(this,
                MessageFormat.format(Messages.get("merge.skipped.message"), skipped.size(), list.toString()),
                Messages.get("merge.skipped.title"), JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        for (MergeLoad.Skipped s : skipped) openLog(s.file().path(), s.file().entry(), parser);
    }

    /** Opens {@code file} in a new tab with auto-detect parser. Called via IPC or CLI args. */
    public void openFile(Path file) {
        if (parsers.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    Messages.get("error.no.parsers.message"),
                    Messages.get("error.no.parsers.title"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        openFile(file, Optional.empty());
    }

    /**
     * Opens files given on the command line or dropped on the window, with auto-detect;
     * several can open in one merged view (UC-12).
     */
    public void openFiles(List<Path> files) {
        if (files.isEmpty()) return;
        if (parsers.isEmpty()) {
            openFile(files.get(0));     // says there are no parsers
            return;
        }
        openFiles(files, Optional.empty());
    }

    private void openFile(Path file, Optional<LogParser> parser) {
        if (file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            openZip(file, parser);
            return;
        }
        openLog(file, null, parser);
    }

    /**
     * Opens the logs in a zip: the only file directly, or the ones the user picks, each in
     * its own tab (G-6).
     */
    private void openZip(Path zip, Optional<LogParser> parser) {
        List<ZipLogs.Entry> entries;
        try {
            entries = ZipLogs.list(zip);
        } catch (IOException e) {
            log.warn("Could not read zip {}", zip, e);
            JOptionPane.showMessageDialog(this,
                    MessageFormat.format(Messages.get("zip.error.read"), zip.getFileName(), e.getMessage()),
                    Messages.get("filechooser.error.title"), JOptionPane.ERROR_MESSAGE);
            AppPrefs.removeRecentFile(zip);
            refreshRecentMenu();
            return;
        }
        if (entries.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    MessageFormat.format(Messages.get("zip.error.empty"), zip.getFileName()),
                    Messages.get("filechooser.error.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        // A review bundle (D-10) opens its log directly; its annotations load with it.
        Optional<ReviewBundle.Manifest> bundle = ReviewBundle.read(zip);
        if (bundle.isPresent()) {
            openLog(zip, bundle.get().log(), parser);
            return;
        }
        List<ZipLogs.Entry> chosen = entries.size() == 1
                ? entries
                : new ZipPickerDialog(this, zip.getFileName().toString(), entries).showDialog();
        for (ZipLogs.Entry entry : chosen) openLog(zip, entry.name(), parser);
    }

    /** Opens a log in a new tab, or selects the tab that already shows it. */
    private void openLog(Path file, String zipEntry, Optional<LogParser> parser) {
        Path abs = file.toAbsolutePath();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getComponentAt(i) instanceof LogPanel lp && abs.equals(lp.getFile())
                    && java.util.Objects.equals(zipEntry, lp.getZipEntry())) {
                tabbedPane.setSelectedIndex(i);
                return;
            }
        }
        AppPrefs.addRecentFile(file);
        refreshRecentMenu();
        LogPanel panel = new LogPanel(parsers, formatters, syntaxHighlighters);
        ZoneId savedTz = AppPrefs.getSourceTimezone(abs);
        if (savedTz != null) panel.setSourceTimezone(savedTz);
        String title = zipEntry == null ? file.getFileName().toString()
                : new ZipLogs.Entry(zipEntry, -1).fileName();
        addLogTab(title, abs, panel);
        panel.setCloseAction(() -> removeTab(panel));
        panel.setCreateParserAction(samples -> new ParserEditorDialog(this, () -> {
            reloadParsers();
            panel.retryWithParsers(parsers);
        }, samples).setVisible(true));
        panel.load(file, zipEntry, parser, () -> {
            AppPrefs.removeRecentFile(file);
            refreshRecentMenu();
            removeTab(panel);
        });
    }

    /** Closes a tab without asking. */
    private void removeTab(LogPanel panel) {
        int idx = tabbedPane.indexOfComponent(panel);
        if (idx >= 0) {
            panel.dispose();
            tabbedPane.removeTabAt(idx);
            updateCard();
        }
    }

    private void openSsh() {
        new SshConnectionDialog(this, parsers).showDialog().ifPresent(r -> {
            log.info("Opening SSH: {}", r.config().displayName());
            LogPanel panel = new LogPanel(parsers, formatters, syntaxHighlighters);
            String title = r.config().user() + "@" + r.config().host();
            addLogTab(title, null, panel);
            panel.loadSource(new SshLogSource(r.config()), r.parser());
        });
    }

    private void rebuildSourceMenu(JMenu menu) {
        menu.removeAll();
        if (sourceProviders.isEmpty()) {
            JMenuItem none = menuItem(Messages.get("menu.file.source.none"));
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        for (LogSourceProvider provider : sourceProviders) {
            String name;
            try {
                name = provider.getName();
            } catch (RuntimeException e) {
                log.warn("Source plugin {} failed in getName()", provider.getClass().getName(), e);
                continue;
            }
            JMenuItem item = menuItem(name + "…");
            item.addActionListener(e -> openSource(provider, name));
            menu.add(item);
        }
    }

    /** Shows a source plugin's settings form, then streams the source into a new tab. */
    private void openSource(LogSourceProvider provider, String name) {
        List<SourceSetting> settings;
        try {
            settings = List.copyOf(provider.settings());
        } catch (RuntimeException | LinkageError e) {
            log.error("Source plugin '{}' failed in settings()", name, e);
            JOptionPane.showMessageDialog(this,
                    java.text.MessageFormat.format(Messages.get("source.dialog.create.failed"), e),
                    Messages.get("source.error.title"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        new SourceSettingsDialog(this, provider, settings, parsers).showDialog().ifPresent(r -> {
            log.info("Opening source '{}'", name);
            LogPanel panel = new LogPanel(parsers, formatters, syntaxHighlighters);
            addLogTab(name, null, panel);
            panel.loadSource(r.source(), r.parser());
        });
    }

    // ── Tab management ────────────────────────────────────────────────────────

    private void addLogTab(String title, Path file, LogPanel panel) {
        tabbedPane.addTab(null, panel);
        int idx = tabbedPane.getTabCount() - 1;
        tabbedPane.setTabComponentAt(idx, buildTabHeader(title, file, panel));
        tabbedPane.setSelectedIndex(idx);
        updateCard();
    }

    private JPanel buildTabHeader(String title, Path file, LogPanel panel) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        header.setOpaque(false);

        JLabel label = new JLabel(title);

        JButton close = new JButton("\u00d7"); // ×
        close.setPreferredSize(new Dimension(18, 18));
        close.setFocusable(false);
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        close.setFont(close.getFont().deriveFont(Font.BOLD));
        close.addActionListener(e -> closeTab(tabbedPane.indexOfTabComponent(header)));

        header.add(label);
        header.add(close);

        // Select on click + drag-to-reorder with floating ghost
        int[] clickOff = {0, 0};
        JWindow[] ghost = {null};

        java.awt.event.MouseAdapter tabMouseAdapter = new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) { showTabContextMenu(e, panel); return; }
                int idx = tabbedPane.indexOfTabComponent(header);
                if (idx >= 0) tabbedPane.setSelectedIndex(idx);
                Point pt = SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), header);
                clickOff[0] = pt.x;
                clickOff[1] = pt.y;
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) { showTabContextMenu(e, panel); return; }
                tabbedPane.setCursor(Cursor.getDefaultCursor());
                if (ghost[0] != null) { ghost[0].dispose(); ghost[0] = null; }
            }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                int src = tabbedPane.indexOfTabComponent(header);
                if (src < 0) return;
                tabbedPane.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                if (ghost[0] == null) ghost[0] = createGhostWindow(header);
                if (ghost[0] != null) {
                    Point screen = e.getLocationOnScreen();
                    ghost[0].setLocation(screen.x - clickOff[0], screen.y - clickOff[1]);
                    if (!ghost[0].isVisible()) ghost[0].setVisible(true);
                }
                Point p = SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), tabbedPane);
                int dst = tabbedPane.indexAtLocation(p.x, p.y);
                if (dst >= 0 && dst != src) moveTab(src, dst);
            }
        };
        header.addMouseListener(tabMouseAdapter);
        header.addMouseMotionListener(tabMouseAdapter);
        label.addMouseListener(tabMouseAdapter);
        label.addMouseMotionListener(tabMouseAdapter);

        return header;
    }

    private void showTabContextMenu(java.awt.event.MouseEvent e, LogPanel panel) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem tzItem = new JMenuItem(Messages.get("tab.context.source.timezone"));
        tzItem.addActionListener(ev -> panel.showSourceTimezoneDialog(this));
        menu.add(tzItem);

        JMenuItem tzAllItem = new JMenuItem(Messages.get("tab.context.source.timezone.all"));
        tzAllItem.setEnabled(tabbedPane.getTabCount() > 1);
        tzAllItem.addActionListener(ev -> showSourceTimezoneAllTabsDialog(panel.getSourceTimezone()));
        menu.add(tzAllItem);

        // A merged tab lists its files, each with its own offset and timezone (D-17).
        List<String> sources = panel.mergedSourceNames();
        if (!sources.isEmpty()) {
            menu.addSeparator();
            JMenu sourcesMenu = new JMenu(Messages.get("merge.sources.menu"));
            for (int s = 0; s < sources.size(); s++) {
                final int source = s;
                JMenu fileMenu = new JMenu(sources.get(s));
                JMenuItem offset = new JMenuItem(java.text.MessageFormat.format(
                        Messages.get("merge.offset.menu"), sources.get(s)));
                offset.addActionListener(ev -> panel.showTimeOffsetDialog(source));
                fileMenu.add(offset);
                JMenuItem zone = new JMenuItem(java.text.MessageFormat.format(
                        Messages.get("merge.timezone.menu"), sources.get(s)));
                zone.addActionListener(ev -> panel.showSourceTimezoneDialog(this, source));
                fileMenu.add(zone);
                sourcesMenu.add(fileMenu);
            }
            menu.add(sourcesMenu);
        }

        menu.show((Component) e.getSource(), e.getX(), e.getY());
    }

    private void showSourceTimezoneAllTabsDialog(ZoneId initial) {
        JComboBox<SettingsDialog.TimezoneEntry> combo = SettingsDialog.buildTimezoneCombo(initial);
        JLabel note = new JLabel(Messages.get("source.timezone.dialog.reload.note"));
        note.setFont(note.getFont().deriveFont(java.awt.Font.ITALIC, 11f));
        Object[] message = { new JLabel(Messages.get("source.timezone.dialog.label")), combo, note };
        int res = JOptionPane.showConfirmDialog(
                this, message,
                Messages.get("tab.context.source.timezone.all"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) return;
        SettingsDialog.TimezoneEntry sel = (SettingsDialog.TimezoneEntry) combo.getSelectedItem();
        if (sel == null) return;
        ZoneId zone = sel.zone();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getComponentAt(i) instanceof LogPanel lp) {
                lp.applySourceTimezone(zone);
            }
        }
    }

    private JWindow createGhostWindow(JPanel tabHeader) {
        int w = tabHeader.getWidth();
        int h = tabHeader.getHeight();
        if (w <= 0 || h <= 0) return null;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color bg = UIManager.getColor("TabbedPane.selectedBackground");
        g2.setColor(bg != null ? bg : ClouseauColors.tableBackground());
        g2.fillRect(0, 0, w, h);
        tabHeader.paint(g2);
        g2.dispose();

        JWindow window = new JWindow(this);
        try { window.setOpacity(0.80f); } catch (Exception ignored) {}
        JLabel content = new JLabel(new ImageIcon(img));
        window.setContentPane(content);
        window.setSize(w, h);
        return window;
    }

    private void moveTab(int from, int to) {
        Component comp    = tabbedPane.getComponentAt(from);
        Component tabComp = tabbedPane.getTabComponentAt(from);
        String    title   = tabbedPane.getTitleAt(from);
        String    tip     = tabbedPane.getToolTipTextAt(from);
        Icon      icon    = tabbedPane.getIconAt(from);
        boolean   enabled = tabbedPane.isEnabledAt(from);
        tabbedPane.remove(from);
        tabbedPane.insertTab(title, icon, comp, tip, to);
        tabbedPane.setEnabledAt(to, enabled);
        tabbedPane.setTabComponentAt(to, tabComp);
        tabbedPane.setSelectedIndex(to);
    }

    private void closeTab(int idx) {
        if (idx < 0 || idx >= tabbedPane.getTabCount()) return;
        if (AppPrefs.isTabCloseConfirm()) {
            JCheckBox dontAsk = new JCheckBox(Messages.get("tab.close.dontask"));
            int result = JOptionPane.showConfirmDialog(
                    this,
                    new Object[]{Messages.get("tab.close.confirm.message"), dontAsk},
                    Messages.get("tab.close.confirm.title"),
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (dontAsk.isSelected()) AppPrefs.setTabCloseConfirm(false);
            if (result != JOptionPane.YES_OPTION) return;
        }
        Component comp = tabbedPane.getComponentAt(idx);
        if (comp instanceof LogPanel lp) lp.dispose();
        tabbedPane.removeTabAt(idx);
        updateCard();
    }

    private void updateCard() {
        boolean hasTabs = tabbedPane.getTabCount() > 0;
        cardLayout.show(contentArea, hasTabs ? CARD_TABS : CARD_WELCOME);
        if (closeTabItem != null) closeTabItem.setEnabled(hasTabs);
    }

    private LogPanel activeLogPanel() {
        Component c = tabbedPane.getSelectedComponent();
        return c instanceof LogPanel lp ? lp : null;
    }

    // ── Welcome panel ─────────────────────────────────────────────────────────

    private JPanel buildWelcomePanel() {
        JPanel outer = new JPanel(new GridBagLayout());

        JPanel content = new JPanel(new MigLayout("insets 0, wrap 1, gapy 0", "[center]"));
        content.setOpaque(false);

        BufferedImage watermark = loadIconImage("watermark-256.png");
        if (watermark != null) {
            content.add(new JLabel(new ImageIcon(watermark)), "gapbottom 28");
        }

        JPanel grid = new JPanel(new MigLayout("insets 0, wrap 2, gapy 10, gapx 24", "[right][left]"));
        grid.setOpaque(false);
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.open"),      "Ctrl+O");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.close.tab"), "Ctrl+W");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.find"),     "Ctrl+F");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.settings"), "Ctrl+,");
        content.add(grid);

        outer.add(content);
        return outer;
    }

    /**
     * Adds one shortcut row to {@code grid}.
     * The shortcut string is split on {@code "+"} so each token gets its own
     * rounded key badge; the {@code "+"} separators are plain (unboxed) labels.
     */
    private static void addShortcutRow(JPanel grid, String description, String shortcut) {
        grid.add(new JLabel(description));

        JPanel badgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        badgeRow.setOpaque(false);

        String[] parts = shortcut.split("\\+", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                JLabel plus = new JLabel("+");
                plus.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
                badgeRow.add(plus);
            }
            badgeRow.add(keyBadge(parts[i]));
        }
        grid.add(badgeRow);
    }

    /** A rounded key-chip label, styled like a keyboard key. */
    private static JComponent keyBadge(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel badge = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ClouseauColors.keyBadgeFill());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(ClouseauColors.keyBadgeBorder());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
            }
        };
        badge.setOpaque(false);
        badge.setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
        badge.add(label);
        return badge;
    }

    /** Loads an icon PNG that the {@code generateIconPngs} Gradle task renders from clouseau.svg. */
    private static BufferedImage loadIconImage(String name) {
        try (var in = MainFrame.class.getResourceAsStream("/com/droidenx/clouseau/ui/icons/" + name)) {
            if (in == null) {
                log.warn("Icon resource not found: {}", name);
                return null;
            }
            return javax.imageio.ImageIO.read(in);
        } catch (Exception e) {
            log.warn("Could not load icon {}", name, e);
            return null;
        }
    }

    private static List<Image> loadAppIcons() {
        return Stream.of("app-256.png", "app-64.png", "app-16.png")
                .map(MainFrame::loadIconImage)
                .filter(Objects::nonNull)
                .map(img -> (Image) img)
                .toList();
    }
}
