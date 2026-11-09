package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSourceProvider;
import com.droidenx.clouseau.api.SourceSetting;
import com.droidenx.clouseau.core.MergeLoad;
import com.droidenx.clouseau.core.ReviewBundle;
import com.droidenx.clouseau.core.Session;
import com.droidenx.clouseau.core.SessionStore;
import com.droidenx.clouseau.core.SshConfig;
import com.droidenx.clouseau.core.SshLogSource;
import com.droidenx.clouseau.core.ZipLogs;
import com.droidenx.clouseau.runtime.ClouseauPluginManager;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.ui.theme.ClouseauFonts;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One Clouseau window (UC-16, D-18): its tabs, menu bar and welcome screen. What all
 * windows share (extensions, the window used last, "one tab per file") is in
 * {@link WindowManager}.
 */
@Slf4j
public final class MainFrame extends JFrame {

    private final WindowManager manager;
    private final ClouseauPluginManager pluginManager;
    private final JTabbedPane tabbedPane = new JTabbedPane();
    private final CardLayout cardLayout  = new CardLayout();
    private final JPanel contentArea     = new JPanel(cardLayout);
    private JMenuItem closeTabItem;
    private JMenuItem reconnectItem;
    private JMenu recentMenu;

    private static final String CARD_WELCOME = "welcome";
    private static final String CARD_TABS    = "tabs";

    MainFrame(WindowManager manager) {
        super(manager.windowTitle());
        this.manager = manager;
        this.pluginManager = manager.pluginManager();
        // Closing is decided in requestClose: the last window quits, others ask first.
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(800, 500));
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

        // Tab switching (UC-15): a dispatcher, not key bindings; see TabShortcuts. Each
        // window has its own, active only while that window is.
        KeyEventDispatcher tabShortcuts = new TabShortcuts(this, tabbedPane);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(tabShortcuts);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowActivated(java.awt.event.WindowEvent e) { manager.activated(MainFrame.this); }
            @Override public void windowClosing(java.awt.event.WindowEvent e) { requestClose(); }
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(tabShortcuts);
            }
        });
        addWindowStateListener(e -> manager.sessionChanged());
        WindowActivator.install(this);   // remembers maximized, for bringing it forward (#5)
        addComponentListener(new java.awt.event.ComponentAdapter() {
            // Later: maximizing reports the new size before the new state.
            @Override public void componentMoved(java.awt.event.ComponentEvent e)   { SwingUtilities.invokeLater(MainFrame.this::boundsChanged); }
            @Override public void componentResized(java.awt.event.ComponentEvent e) { SwingUtilities.invokeLater(MainFrame.this::boundsChanged); }
        });
        tabbedPane.addChangeListener(e -> manager.sessionChanged());
    }

    // ── The window's place, for the session (UC-17, D-19) ─────────────────────

    /** Differences smaller than this don't count as the user moving the window. */
    private static final int MOVE_TOLERANCE = 8;
    /** Where the window usually is when not maximized, and the screen that was on. */
    private Rectangle home;
    private String homeScreen;
    /** Where restoring put the window when its usual place wasn't on a screen, or null. */
    private Rectangle placed;

    /**
     * Puts a restored window at {@code bounds}, remembering {@code usual} (and its screen) as
     * where it belongs: a window moved off a missing monitor goes back there next time,
     * unless the user moves or resizes it first.
     */
    void placeRestored(Rectangle bounds, Rectangle usual, String usualScreen) {
        setBounds(bounds);
        home = new Rectangle(usual);
        homeScreen = usualScreen;
        placed = bounds.equals(usual) ? null : new Rectangle(bounds);
    }

    private void boundsChanged() {
        if (!isVisible() || (getExtendedState() & (MAXIMIZED_BOTH | ICONIFIED)) != 0) return;
        Rectangle now = getBounds();
        Rectangle reference = placed != null ? placed : home;
        if (reference == null || !close(now, reference)) {
            home = now;
            homeScreen = screenOf(now);
            placed = null;
            manager.sessionChanged();
        }
    }

    private static boolean close(Rectangle a, Rectangle b) {
        return Math.abs(a.x - b.x) <= MOVE_TOLERANCE && Math.abs(a.y - b.y) <= MOVE_TOLERANCE
                && Math.abs(a.width - b.width) <= MOVE_TOLERANCE && Math.abs(a.height - b.height) <= MOVE_TOLERANCE;
    }

    /** The id of the screen that holds most of {@code r}, or null. */
    private static String screenOf(Rectangle r) {
        if (GraphicsEnvironment.isHeadless()) return null;
        String best = null;
        long bestArea = 0;
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle i = d.getDefaultConfiguration().getBounds().intersection(r);
            long area = i.isEmpty() ? 0 : (long) i.width * i.height;
            if (area > bestArea) {
                bestArea = area;
                best = d.getIDstring();
            }
        }
        return best;
    }

    /** This window as the session keeps it: its usual place, maximized or not, and its tabs. */
    Session.Window sessionWindow() {
        Rectangle b = home;
        String screen = homeScreen;
        if (b == null) {
            b = getBounds();
            screen = screenOf(b);
        }
        List<Session.Tab> tabs = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (!(tabbedPane.getComponentAt(i) instanceof TabPanel lp)) continue;
            Session.Tab t = lp.sessionTab();
            if (t == null) continue;
            if (i == tabbedPane.getSelectedIndex()) selected = tabs.size();
            tabs.add(t);
        }
        return new Session.Window(new Session.Bounds(b.x, b.y, b.width, b.height), screen,
                (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH, selected, tabs);
    }

    // ── Shared state, through the window manager ──────────────────────────────

    private List<LogParser> parsers()                       { return manager.parsers(); }
    private List<LogFormatter> formatters()                 { return manager.formatters(); }
    private List<LogSyntaxHighlighter> syntaxHighlighters() { return manager.syntaxHighlighters(); }

    private TabPanel newTabPanel() {
        return new TabPanel(parsers(), formatters(), syntaxHighlighters());
    }

    /** This window's log tabs, in tab order. */
    List<TabPanel> panels() {
        List<TabPanel> list = new ArrayList<>();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getComponentAt(i) instanceof TabPanel lp) list.add(lp);
        }
        return list;
    }

    /** The selected tab's title, for naming this window in the Move to menu. */
    String selectedTabTitle() {
        int idx = tabbedPane.getSelectedIndex();
        return idx < 0 ? "" : tabTitle(idx);
    }

    // ── Closing the window ────────────────────────────────────────────────────

    /**
     * The close button: the last window quits, as closing Clouseau always has. Another
     * window asks first, as closing a tab does (UC-15), then closes with its tabs.
     */
    private void requestClose() {
        if (manager.windows().size() <= 1) {
            manager.exit();
            return;
        }
        int tabs = tabbedPane.getTabCount();
        if (tabs > 0 && AppPrefs.isTabCloseConfirm()) {
            JCheckBox dontAsk = new JCheckBox(Messages.get("tab.close.dontask"));
            int result = JOptionPane.showConfirmDialog(this,
                    new Object[]{ MessageFormat.format(Messages.get("window.close.confirm.message"), tabs), dontAsk },
                    Messages.get("window.close.confirm.title"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (dontAsk.isSelected()) AppPrefs.setTabCloseConfirm(false);
            if (result != JOptionPane.YES_OPTION) return;
        }
        closeWindow();
    }

    /** Closes this window and its tabs without asking. */
    private void closeWindow() {
        disposeWindow();
        manager.closed(this);
    }

    /**
     * Closes this window and its tabs for opening a named session in its place: without
     * asking, and without quitting if it was the last one.
     */
    void closeForSession() {
        disposeWindow();
        manager.forget(this);
    }

    private void disposeWindow() {
        for (TabPanel lp : panels()) lp.dispose();
        tabbedPane.removeAll();
        dispose();
    }

    // ── File → Sessions (UC-17) ───────────────────────────────────────────────

    private void rebuildSessionsMenu(JMenu menu) {
        menu.removeAll();
        SessionManager sessions = manager.sessions();
        String active = sessions.active();
        boolean anyTabs = !manager.allPanels().isEmpty();

        JMenuItem save = menuItem(active == null ? Messages.get("menu.file.sessions.save")
                : MessageFormat.format(Messages.get("menu.file.sessions.save.named"), active));
        save.setEnabled(active != null && anyTabs);
        save.addActionListener(e -> sessions.saveActive(this));
        menu.add(save);

        JMenuItem saveAs = menuItem(Messages.get("menu.file.sessions.save.as"));
        saveAs.setEnabled(anyTabs);
        saveAs.addActionListener(e -> sessions.saveAs(this));
        menu.add(saveAs);
        menu.addSeparator();

        List<SessionStore.Named> named = sessions.named();
        if (named.isEmpty()) {
            JMenuItem none = menuItem(Messages.get("menu.file.sessions.none"));
            none.setEnabled(false);
            menu.add(none);
        }
        for (SessionStore.Named n : named) {
            JMenuItem item = new JRadioButtonMenuItem(n.name(), n.name().equalsIgnoreCase(String.valueOf(active)));
            item.setMargin(menuMargin());
            item.addActionListener(e -> manager.openNamedSession(n, this));
            menu.add(item);
        }
        menu.addSeparator();

        JMenuItem manage = menuItem(Messages.get("menu.file.sessions.manage"));
        manage.setEnabled(!named.isEmpty());
        manage.addActionListener(e -> new ManageSessionsDialog(this, manager).setVisible(true));
        menu.add(manage);
    }

    // ── Menu bar ─────────────────────────────────────────────────────────────

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        menuBar.setBorder(BorderFactory.createEmptyBorder(5, 1, 2, 0));

        JMenu fileMenu = new JMenu(Messages.get("menu.file"));

        JMenuItem newWindowItem = menuItem(Messages.get("menu.file.new.window"));
        newWindowItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        newWindowItem.addActionListener(e -> manager.newWindow(this));
        fileMenu.add(newWindowItem);
        fileMenu.addSeparator();

        JMenuItem openItem = menuItem(Messages.get("menu.file.open"));
        openItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        openItem.addActionListener(e -> openFile());
        fileMenu.add(openItem);

        JMenuItem openMergedItem = menuItem(Messages.get("menu.file.open.merged"));
        openMergedItem.addActionListener(e -> openFile(true));
        fileMenu.add(openMergedItem);

        recentMenu = new JMenu(Messages.get("menu.file.recent"));
        recentMenu.setMargin(menuMargin());
        fileMenu.add(recentMenu);
        refreshRecentMenu();

        JMenuItem sshItem = menuItem(Messages.get("menu.file.ssh"));
        sshItem.addActionListener(e -> openSsh());
        fileMenu.add(sshItem);

        // Rebuilt each time it opens, so sources from newly installed plugins appear.
        JMenu sourceMenu = new JMenu(Messages.get("menu.file.source"));
        sourceMenu.setMargin(menuMargin());
        sourceMenu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) { rebuildSourceMenu(sourceMenu); }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) {}
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        fileMenu.add(sourceMenu);

        // Enabled while the active tab streams a live source (issue #4).
        reconnectItem = menuItem(Messages.get("menu.file.reconnect"));
        reconnectItem.setEnabled(false);
        reconnectItem.setAccelerator(reconnectKey());
        reconnectItem.addActionListener(e -> {
            TabPanel active = activeTabPanel();
            if (active != null && active.canReconnect()) active.reconnect();
        });
        fileMenu.add(reconnectItem);
        tabbedPane.addChangeListener(e -> updateReconnectItem());

        JMenuItem exportReviewItem = menuItem(Messages.get("menu.file.export.review"));
        exportReviewItem.addActionListener(e -> ReviewExporter.export(this, activeTabPanel()));
        fileMenu.add(exportReviewItem);

        closeTabItem = menuItem(Messages.get("menu.file.close.tab"));
        closeTabItem.setEnabled(false);
        closeTabItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_W,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        closeTabItem.addActionListener(e -> closeTab(tabbedPane.getSelectedIndex()));
        fileMenu.add(closeTabItem);

        fileMenu.addSeparator();

        // Named sessions (UC-17); rebuilt each time it opens.
        JMenu sessionsMenu = new JMenu(Messages.get("menu.file.sessions"));
        sessionsMenu.setMargin(menuMargin());
        sessionsMenu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) { rebuildSessionsMenu(sessionsMenu); }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) {}
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        fileMenu.add(sessionsMenu);

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
        exitItem.addActionListener(e -> manager.exit());
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);

        JMenu viewMenu = new JMenu(Messages.get("menu.view"));
        // Rebuilt each time it opens, so newly installed themes and a theme picked in
        // Settings show up.
        JMenu themeMenu = new JMenu(Messages.get("menu.view.theme"));
        themeMenu.setMargin(menuMargin());
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
                new ParserEditorDialog(this, manager::reloadParsers).setVisible(true));
        toolsMenu.add(parsersItem);
        JMenuItem pluginsItem = menuItem(Messages.get("menu.tools.plugins"));
        pluginsItem.setToolTipText(Messages.get("toolbar.plugins.tooltip"));
        pluginsItem.addActionListener(e ->
                new PluginManagerDialog(this, pluginManager, manager::reloadExtensions).setVisible(true));
        toolsMenu.add(pluginsItem);
        menuBar.add(toolsMenu);

        JMenu helpMenu = new JMenu(Messages.get("menu.help"));

        JMenuItem docsItem = menuItem(Messages.get("menu.help.docs"));
        docsItem.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(new java.net.URI("https://benlazaro.github.io/clouseau/guide/"));
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
            item.setMargin(menuMargin());
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
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.new.window"), "Ctrl+N");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.open"),      "Ctrl+O");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.close.tab"), "Ctrl+W");
        addShortcutRow(grid, Messages.get("shortcuts.shortcut.reconnect"), "Ctrl+R");
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

    /**
     * Padding for every menu-bar item: VSCode-style vertical padding, and a right margin
     * that balances the left. FlatLaf always reserves an icon column (16px) and gap (6px)
     * before the text, and only a gap plus a hidden arrow slot after it, so equal margins
     * left the right side visibly narrower. One value for every item keeps text and
     * accelerators aligned within a popup.
     */
    private static Insets menuMargin() {
        return new Insets(7, 0, 7, 12);
    }

    /** Creates a {@link JMenuItem} with {@link #menuMargin()} applied. */
    private static JMenuItem menuItem(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setMargin(menuMargin());
        return item;
    }

    private void openSettings() {
        new SettingsDialog(this, activeTabPanel(), () -> {
            manager.refreshRecentMenus();
            manager.allPanels().forEach(TabPanel::refreshTimezone);
            manager.allPanels().forEach(TabPanel::applyTableSettings);
            manager.restoreSettingChanged();
        }).setVisible(true);
    }

    void refreshRecentMenu() {
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
            clear.addActionListener(e -> { AppPrefs.clearRecentFiles(); manager.refreshRecentMenus(); });
            recentMenu.add(clear);
        }
    }

    // ── File opening ──────────────────────────────────────────────────────────

    private void openFile() {
        openFile(false);
    }

    /** The open dialog; {@code merged}: File → Open Merged…, which merges without asking. */
    private void openFile(boolean merged) {
        new FileChooserDialog(this, parsers(), manager.lastParserIndex(), merged).showDialog().ifPresent(r -> {
            manager.setLastParserIndex(r.parserIndex());
            ZoneId srcTz = r.sourceTimezone();
            for (Path file : r.files()) {
                log.info("Opening {} with parser: {}", file.getFileName(),
                        r.parser().map(LogParser::getName).orElse("Auto-detect"));
                // Persist the selected source timezone so CLI/double-click reopens use it.
                if (!srcTz.equals(ZoneId.systemDefault())) {
                    AppPrefs.setSourceTimezone(file.toAbsolutePath(), srcTz);
                }
            }
            openFiles(r.files(), r.parser(), r.merge());
        });
    }

    /**
     * Opens several files: zips as usual, and the rest in one merged view or each in its
     * own tab, as the user chooses (D-17, UC-12).
     */
    private void openFiles(List<Path> files, Optional<LogParser> parser) {
        openFiles(files, parser, false);
    }

    /** {@code merge}: open two or more files in one merged view without asking. */
    private void openFiles(List<Path> files, Optional<LogParser> parser, boolean merge) {
        List<Path> logs = new ArrayList<>();
        for (Path f : files) {
            if (f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) openFile(f, parser);
            else logs.add(f);
        }
        if (logs.size() >= 2) {
            AppPrefs.MultiOpen how = merge ? AppPrefs.MultiOpen.MERGE : chooseMultiOpen(logs.size());
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

    /**
     * Opens a version 2 review bundle (D-17): its logs in one merged view, each with the
     * timezone, offset and parser it was exported with. A parser this Clouseau doesn't have
     * falls back to the tab's choice (G-10).
     */
    private void openMergedBundle(Path zip, ReviewBundle.Manifest bundle, Optional<LogParser> parser) {
        List<MergeLoad.File> files = new ArrayList<>();
        Map<MergeLoad.File, MergeLoad.PerFile> setup = new HashMap<>();
        for (ReviewBundle.Source s : bundle.sources()) {
            MergeLoad.File f = new MergeLoad.File(zip, s.log());
            files.add(f);
            ZoneId zone = null;
            try {
                if (s.timezone() != null) zone = ZoneId.of(s.timezone());
            } catch (java.time.DateTimeException e) {
                log.warn("Review bundle {} names an unknown timezone {}", zip.getFileName(), s.timezone());
            }
            LogParser own = s.parser() == null ? null
                    : parsers().stream().filter(p -> s.parser().equals(p.getName())).findFirst().orElse(null);
            if (s.parser() != null && own == null) {
                log.info("Review bundle {} names a parser this Clouseau doesn't have, {}", zip.getFileName(), s.parser());
            }
            setup.put(f, new MergeLoad.PerFile(zone, s.offsetMillis(), own));
        }
        openMerged(files, parser, setup);
    }

    /** Opens {@code files} in one merged view (D-17). */
    private void openMerged(List<MergeLoad.File> files, Optional<LogParser> parser) {
        openMerged(files, parser, Map.of());
    }

    /** Opens {@code files} in one merged view (D-17), with the given per-file setup where there is one. */
    private void openMerged(List<MergeLoad.File> files, Optional<LogParser> parser,
                            Map<MergeLoad.File, MergeLoad.PerFile> setup) {
        files.forEach(f -> AppPrefs.addRecentFile(f.path()));
        manager.refreshRecentMenus();
        TabPanel panel = newTabPanel();
        ZoneId savedTz = AppPrefs.getSourceTimezone(files.get(0).path());
        if (savedTz != null) panel.setSourceTimezone(savedTz);
        String title = MessageFormat.format(Messages.get("merge.tab.title"), files.get(0).fileName(), files.size() - 1);
        addLogTab(title, null, panel);
        panel.setSessionOrigin(Session.Tab.merged(files.stream()
                .map(f -> new Session.MergedFile(f.path().toAbsolutePath().toString(), f.entry(), null, null, 0)).toList(),
                parserName(parser)));
        panel.presetMerged(setup);
        startMergedLoad(panel, files, parser, () -> owner(panel).removeTab(panel));
    }

    /** Loads a merged tab; {@code onFailed} runs if none of its files could be merged. */
    private void startMergedLoad(TabPanel panel, List<MergeLoad.File> files, Optional<LogParser> parser,
                                 Runnable onFailed) {
        // The tab may have moved to another window by the time these run (UC-16).
        panel.setCloseAction(() -> owner(panel).removeTab(panel));
        panel.loadMerged(files, parser, new TabPanel.MergeListener() {
            @Override public void skipped(List<MergeLoad.Skipped> skipped) { owner(panel).offerSkipped(skipped, parser); }

            @Override public void notMerged(List<MergeLoad.File> mergeable) {
                MainFrame window = owner(panel);
                window.removeTab(panel);
                for (MergeLoad.File f : mergeable) window.openLog(f.path(), f.entry(), parser);
            }

            @Override public void failed() { onFailed.run(); }

            @Override public void merged(List<MergeLoad.File> merged) {
                owner(panel).setTabTitle(panel, MessageFormat.format(Messages.get("merge.tab.title"),
                        merged.get(0).fileName(), merged.size() - 1));
            }
        });
    }

    /** Renames the tab showing {@code panel}. */
    private void setTabTitle(TabPanel panel, String title) {
        int idx = tabbedPane.indexOfComponent(panel);
        JLabel label = idx >= 0 ? tabLabel(idx) : null;
        if (label != null) {
            label.setText(title);
            label.getParent().revalidate();
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
        if (parsers().isEmpty()) {
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
        if (parsers().isEmpty()) {
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
            manager.refreshRecentMenus();
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
        if (bundle.isPresent() && bundle.get().isMerged()) {
            openMergedBundle(zip, bundle.get(), parser);
            return;
        }
        if (bundle.isPresent()) {
            openLog(zip, bundle.get().log(), parser);
            return;
        }
        if (entries.size() == 1) {
            openLog(zip, entries.get(0).name(), parser);
            return;
        }
        ZipPickerDialog picker = new ZipPickerDialog(this, zip.getFileName().toString(), entries);
        List<ZipLogs.Entry> chosen = picker.showDialog();
        if (picker.merge()) {
            openMerged(chosen.stream().map(e -> new MergeLoad.File(zip, e.name())).toList(), parser);
            return;
        }
        for (ZipLogs.Entry entry : chosen) openLog(zip, entry.name(), parser);
    }

    /**
     * Opens a log in a new tab, or selects the tab that already shows it, in whichever
     * window that is (UC-16).
     */
    private void openLog(Path file, String zipEntry, Optional<LogParser> parser) {
        Path abs = file.toAbsolutePath();
        Optional<TabPanel> open = manager.findTab(abs, zipEntry);
        if (open.isPresent()) {
            owner(open.get()).selectTab(open.get());
            return;
        }
        AppPrefs.addRecentFile(file);
        manager.refreshRecentMenus();
        TabPanel panel = newTabPanel();
        ZoneId savedTz = AppPrefs.getSourceTimezone(abs);
        if (savedTz != null) panel.setSourceTimezone(savedTz);
        String title = zipEntry == null ? file.getFileName().toString()
                : new ZipLogs.Entry(zipEntry, -1).fileName();
        addLogTab(title, abs, panel);
        panel.setSessionOrigin(Session.Tab.file(abs.toString(), zipEntry, parserName(parser), null));
        setUpFileTab(panel);
        panel.load(file, zipEntry, parser, () -> {
            AppPrefs.removeRecentFile(file);
            manager.refreshRecentMenus();
            owner(panel).removeTab(panel);
        });
    }

    /** A file tab's close and "Create a parser" actions. */
    private void setUpFileTab(TabPanel panel) {
        panel.setCloseAction(() -> owner(panel).removeTab(panel));
        panel.setCreateParserAction(samples -> new ParserEditorDialog(owner(panel), () -> {
            manager.reloadParsers();
            panel.retryWithParsers(parsers());
        }, samples).setVisible(true));
    }

    private static String parserName(Optional<LogParser> parser) {
        return parser.map(LogParser::getName).orElse(null);
    }

    // ── Restoring a session's tabs (UC-17, D-19) ──────────────────────────────

    /**
     * Adds a tab from a session, without selecting it. It loads the first time it is shown,
     * so only each window's selected tab loads at start. {@code sessionName} names a named
     * session for the filter strip, or is null for the last session.
     */
    void restoreTab(Session.Tab t, String sessionName) {
        List<String> notes = new ArrayList<>();
        Optional<LogParser> parser = parserNamed(t.parser(), notes);
        TabPanel panel = newTabPanel();
        if (t.timezone() != null) zone(t.timezone()).ifPresent(panel::setSourceTimezone);
        switch (t.kind()) {
            case FILE -> {
                Path file = Path.of(t.path()).toAbsolutePath();
                String title = t.entry() == null ? file.getFileName().toString()
                        : new ZipLogs.Entry(t.entry(), -1).fileName();
                insertLogTab(title, null, panel, -1, false);
                panel.presetFile(file, t.entry());
                setUpFileTab(panel);
                panel.restoreState(t, notes, sessionName);
                panel.deferLoad(() -> loadRestoredFile(panel, file, t.entry(), parser));
            }
            case MERGED -> {
                List<MergeLoad.File> files = new ArrayList<>();
                Map<MergeLoad.File, MergeLoad.PerFile> setup = new HashMap<>();
                for (Session.MergedFile mf : t.files()) {
                    MergeLoad.File f = new MergeLoad.File(Path.of(mf.path()), mf.entry());
                    files.add(f);
                    setup.put(f, new MergeLoad.PerFile(mf.timezone() == null ? null : zone(mf.timezone()).orElse(null),
                            mf.offsetMillis(), parserNamed(mf.parser(), notes).orElse(null)));
                }
                String title = MessageFormat.format(Messages.get("merge.tab.title"), files.get(0).fileName(), files.size() - 1);
                insertLogTab(title, null, panel, -1, false);
                panel.presetMerged(setup);
                panel.restoreState(t, notes, sessionName);
                panel.deferLoad(() -> loadRestoredMerged(panel, files, parser));
            }
            case SSH -> {
                Session.Ssh ssh = t.ssh();
                SshConfig shown = new SshConfig(ssh.host(), ssh.port(), ssh.user(), null, ssh.keyFile(), null, ssh.remotePath());
                insertLogTab(sshTabTitle(shown), sshTabTooltip(shown), panel, -1, false);
                panel.restoreState(t, notes, sessionName);
                panel.deferLoad(() -> connectRestoredSsh(panel, t, parser));
            }
            case SOURCE -> {
                String name = t.source().name() != null ? t.source().name() : t.source().provider();
                insertLogTab(name, null, panel, -1, false);
                panel.restoreState(t, notes, sessionName);
                panel.deferLoad(() -> openRestoredSource(panel, t, parser));
            }
        }
    }

    /** Selects a restored window's saved tab (or the first). */
    void selectRestoredTab(int index) {
        if (tabbedPane.getTabCount() == 0) return;
        tabbedPane.setSelectedIndex(index >= 0 && index < tabbedPane.getTabCount() ? index : 0);
    }

    /** The parser called {@code name}, or automatic detection with a note for the strip if there's none. */
    private Optional<LogParser> parserNamed(String name, List<String> notes) {
        if (name == null) return Optional.empty();
        Optional<LogParser> p = parsers().stream().filter(x -> name.equals(x.getName())).findFirst();
        if (p.isEmpty()) {
            String note = MessageFormat.format(Messages.get("session.notice.parser"), name);
            if (!notes.contains(note)) notes.add(note);
        }
        return p;
    }

    private static Optional<ZoneId> zone(String id) {
        try {
            return Optional.of(ZoneId.of(id));
        } catch (java.time.DateTimeException e) {
            log.warn("Session names an unknown timezone {}", id);
            return Optional.empty();
        }
    }

    private void loadRestoredFile(TabPanel panel, Path file, String entry, Optional<LogParser> parser) {
        if (!java.nio.file.Files.isRegularFile(file)) {
            panel.showMessage(MessageFormat.format(Messages.get("session.tab.missing"), file),
                    Messages.get("session.tab.retry"), () -> loadRestoredFile(panel, file, entry, parser));
            return;
        }
        panel.load(file, entry, parser, () -> panel.showMessage(
                MessageFormat.format(Messages.get("session.tab.failed"), file),
                Messages.get("session.tab.retry"), () -> loadRestoredFile(panel, file, entry, parser)));
    }

    private void loadRestoredMerged(TabPanel panel, List<MergeLoad.File> files, Optional<LogParser> parser) {
        List<String> missing = files.stream().map(MergeLoad.File::path).distinct()
                .filter(p -> !java.nio.file.Files.isRegularFile(p)).map(Path::toString).toList();
        if (!missing.isEmpty()) {
            panel.showMessage(MessageFormat.format(Messages.get("session.tab.missing"), String.join(", ", missing)),
                    Messages.get("session.tab.retry"), () -> loadRestoredMerged(panel, files, parser));
            return;
        }
        startMergedLoad(panel, files, parser, () -> panel.showMessage(
                Messages.get("session.tab.merge.failed"), Messages.get("session.tab.retry"),
                () -> loadRestoredMerged(panel, files, parser)));
    }

    /**
     * Connects a restored SSH tab if it needs no password, or one saved with a matching SSH
     * favorite gives it; otherwise the tab offers Reconnect, so starting Clouseau never
     * asks for passwords (D-19).
     */
    private void connectRestoredSsh(TabPanel panel, Session.Tab t, Optional<LogParser> parser) {
        Optional<SshConfig> config = sshCredentials(t.ssh());
        if (config.isPresent()) {
            SshConfig c = config.get();
            panel.loadSource(new SshLogSource(c), () -> new SshLogSource(c), parser);
            return;
        }
        panel.waitForReconnect(MessageFormat.format(Messages.get("session.tab.ssh.locked"), t.ssh().user(), t.ssh().host()),
                () -> reconnectSsh(panel, t));
    }

    /** The connection with the secret it needs, if it needs none or a saved favorite has it. */
    static Optional<SshConfig> sshCredentials(Session.Ssh s) {
        if ("none".equals(s.auth())) {
            return Optional.of(new SshConfig(s.host(), s.port(), s.user(), null, null, null, s.remotePath()));
        }
        if ("key".equals(s.auth()) && !s.keyPassphrase()) {
            return Optional.of(new SshConfig(s.host(), s.port(), s.user(), null, s.keyFile(), null, s.remotePath()));
        }
        for (AppPrefs.SshFavorite f : AppPrefs.getSshFavorites()) {
            if (!s.host().equalsIgnoreCase(f.host()) || s.port() != f.port() || !Objects.equals(s.user(), f.user())) continue;
            if ("key".equals(s.auth()) && "key".equals(f.authType()) && Objects.equals(s.keyFile(), f.keyFilePath())
                    && f.keyPassphrase() != null && !f.keyPassphrase().isEmpty()) {
                return Optional.of(new SshConfig(s.host(), s.port(), s.user(), null, s.keyFile(), f.keyPassphrase(), s.remotePath()));
            }
            if ("password".equals(s.auth()) && !"key".equals(f.authType()) && !"none".equals(f.authType())
                    && f.password() != null && !f.password().isEmpty()) {
                return Optional.of(new SshConfig(s.host(), s.port(), s.user(), f.password(), null, null, s.remotePath()));
            }
        }
        return Optional.empty();
    }

    /** Reconnect: the SSH dialog filled in from the session, asking only for the secret. */
    private void reconnectSsh(TabPanel panel, Session.Tab t) {
        SshConnectionDialog dialog = new SshConnectionDialog(owner(panel), parsers());
        dialog.prefill(t.ssh(), t.parser());
        dialog.showDialog().ifPresent(r -> {
            panel.setSessionOrigin(Session.Tab.ssh(toSession(r.config()), parserName(r.parser()), t.timezone())
                    .withState(t.follow(), t.columns(), t.filters()));
            owner(panel).setTabTitle(panel, sshTabTitle(r.config()));
            SshConfig c = r.config();
            panel.loadSource(new SshLogSource(c), () -> new SshLogSource(c), r.parser());
        });
    }

    static Session.Ssh toSession(SshConfig c) {
        String auth = c.keyFilePath() != null ? "key" : c.password() != null ? "password" : "none";
        return new Session.Ssh(c.host(), c.port(), c.user(), auth, c.keyFilePath(),
                c.keyPassphrase() != null && !c.keyPassphrase().isBlank(), c.remotePath());
    }

    /**
     * Opens a restored plugin source with its saved settings, taking secrets from the
     * provider's saved settings (D-14); if one it needs isn't saved, or the plugin is gone,
     * the tab says so instead.
     */
    private void openRestoredSource(TabPanel panel, Session.Tab t, Optional<LogParser> parser) {
        Session.PluginSource ps = t.source();
        Optional<LogSourceProvider> provider = manager.sourceProviders().stream()
                .filter(p -> p.getClass().getName().equals(ps.provider())).findFirst();
        if (provider.isEmpty()) {
            panel.showMessage(MessageFormat.format(Messages.get("session.tab.plugin.missing"),
                    ps.name() != null ? ps.name() : ps.provider()),
                    Messages.get("session.tab.retry"), () -> openRestoredSource(panel, t, parser));
            return;
        }
        try {
            List<SourceSetting> settings = List.copyOf(provider.get().settings());
            java.util.Set<String> secrets = new java.util.HashSet<>();
            for (SourceSetting s : settings) if (s.type() == SourceSetting.Type.PASSWORD) secrets.add(s.key());
            Map<String, String> values = new java.util.LinkedHashMap<>(ps.settings());
            Map<String, String> saved = AppPrefs.getSourceSettings(ps.provider(), secrets);
            boolean locked = false;
            for (SourceSetting s : settings) {
                if (s.type() != SourceSetting.Type.PASSWORD) continue;
                String secret = saved.get(s.key());
                if (secret != null && !secret.isEmpty()) values.put(s.key(), secret);
                else if (s.required()) locked = true;
            }
            if (!locked) {
                var source = provider.get().create(values);
                if (source != null) {
                    LogSourceProvider p = provider.get();
                    panel.loadSource(source, () -> p.create(values), parser);
                    return;
                }
            }
        } catch (Exception | LinkageError e) {
            log.warn("Could not reopen source '{}'", ps.name(), e);
        }
        panel.waitForReconnect(MessageFormat.format(Messages.get("session.tab.source.locked"),
                ps.name() != null ? ps.name() : ps.provider()), () -> reconnectSource(panel, t, provider.get()));
    }

    private void reconnectSource(TabPanel panel, Session.Tab t, LogSourceProvider provider) {
        try {
            List<SourceSetting> settings = List.copyOf(provider.settings());
            new SourceSettingsDialog(owner(panel), provider, settings, parsers()).showDialog().ifPresent(r -> {
                panel.setSessionOrigin(Session.Tab.source(new Session.PluginSource(provider.getClass().getName(),
                                t.source().name(), r.sessionValues()), parserName(r.parser()), t.timezone())
                        .withState(t.follow(), t.columns(), t.filters()));
                panel.loadSource(r.source(), () -> provider.create(r.values()), r.parser());
            });
        } catch (RuntimeException | LinkageError e) {
            log.error("Source plugin failed in settings()", e);
        }
    }

    /** The window that holds {@code panel} now, or this one if it's in none. */
    private MainFrame owner(TabPanel panel) {
        MainFrame w = WindowManager.windowOf(panel);
        return w != null ? w : this;
    }

    /** Selects {@code panel}'s tab and brings this window to the front. */
    void selectTab(TabPanel panel) {
        int idx = tabbedPane.indexOfComponent(panel);
        if (idx >= 0) tabbedPane.setSelectedIndex(idx);
        if ((getExtendedState() & ICONIFIED) != 0) setExtendedState(getExtendedState() & ~ICONIFIED);
        toFront();
        requestFocus();
    }

    /** Closes a tab without asking. */
    private void removeTab(TabPanel panel) {
        int idx = tabbedPane.indexOfComponent(panel);
        if (idx >= 0) {
            panel.dispose();
            tabbedPane.removeTabAt(idx);
            updateCard();
        }
    }

    private void openSsh() {
        new SshConnectionDialog(this, parsers()).showDialog().ifPresent(r -> {
            log.info("Opening SSH: {}", r.config().displayName());
            TabPanel panel = newTabPanel();
            SshConfig config = r.config();
            addLogTab(sshTabTitle(config), null, panel, sshTabTooltip(config));
            panel.setSessionOrigin(Session.Tab.ssh(toSession(config), parserName(r.parser()), null));
            // Reconnect signs in again with the same config, password included, without asking.
            panel.loadSource(new SshLogSource(config), () -> new SshLogSource(config), r.parser());
        });
    }

    private void rebuildSourceMenu(JMenu menu) {
        menu.removeAll();
        if (manager.sourceProviders().isEmpty()) {
            JMenuItem none = menuItem(Messages.get("menu.file.source.none"));
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        for (LogSourceProvider provider : manager.sourceProviders()) {
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
        new SourceSettingsDialog(this, provider, settings, parsers()).showDialog().ifPresent(r -> {
            log.info("Opening source '{}'", name);
            TabPanel panel = newTabPanel();
            addLogTab(name, null, panel);
            panel.setSessionOrigin(Session.Tab.source(
                    new Session.PluginSource(provider.getClass().getName(), name, r.sessionValues()),
                    parserName(r.parser()), null));
            // Reconnect asks the provider for a new source from the same values (secrets included).
            panel.loadSource(r.source(), () -> provider.create(r.values()), r.parser());
        });
    }

    // ── Tab management ────────────────────────────────────────────────────────

    /** An SSH tab's title: the remote file's name and the host, e.g. "app.log — 10.0.0.5". */
    static String sshTabTitle(SshConfig config) {
        return MessageFormat.format(Messages.get("ssh.tab.title"),
                config.fileName(), config.host());
    }

    /** An SSH tab's tooltip: user, host, path, and the port if it isn't 22. */
    static String sshTabTooltip(SshConfig config) {
        return config.port() == SshConfig.DEFAULT_PORT
                ? MessageFormat.format(Messages.get("ssh.tab.tooltip"),
                        config.user(), config.host(), config.remotePath())
                : MessageFormat.format(Messages.get("ssh.tab.tooltip.port"),
                        config.user(), config.host(), config.remotePath(), String.valueOf(config.port()));
    }

    private void addLogTab(String title, Path file, TabPanel panel) {
        addLogTab(title, file, panel, null);
    }

    private void addLogTab(String title, Path file, TabPanel panel, String tooltip) {
        insertLogTab(title, tooltip, panel, -1);
    }

    /** Adds {@code panel} as a tab at {@code index} (-1: at the end) and selects it. */
    private void insertLogTab(String title, String tooltip, TabPanel panel, int index) {
        insertLogTab(title, tooltip, panel, index, true);
    }

    /** Adds {@code panel} as a tab at {@code index} (-1: at the end), selecting it if {@code select}. */
    private void insertLogTab(String title, String tooltip, TabPanel panel, int index, boolean select) {
        int idx = index < 0 || index > tabbedPane.getTabCount() ? tabbedPane.getTabCount() : index;
        tabbedPane.insertTab(null, null, panel, null, idx);
        JPanel header = buildTabHeader(title, panel);
        if (tooltip != null) {
            for (Component c : header.getComponents()) {
                if (c instanceof JLabel label) label.setToolTipText(tooltip);
            }
        }
        tabbedPane.setTabComponentAt(idx, header);
        if (select) tabbedPane.setSelectedIndex(idx);
        updateCard();
    }

    /** The label of the tab header at {@code idx}, or null. */
    private JLabel tabLabel(int idx) {
        if (tabbedPane.getTabComponentAt(idx) instanceof JPanel header
                && header.getComponentCount() > 0 && header.getComponent(0) instanceof JLabel label) {
            return label;
        }
        return null;
    }

    private String tabTitle(int idx) {
        JLabel label = tabLabel(idx);
        return label != null ? label.getText() : "";
    }

    // ── Moving tabs between windows (UC-16) ───────────────────────────────────

    /**
     * Moves {@code panel} into {@code target} at {@code index} (-1: at the end). The panel
     * itself moves, so loading, following and Find carry on and nothing is re-read. A window
     * left with no tabs closes.
     */
    private void moveTabTo(TabPanel panel, MainFrame target, int index) {
        int idx = tabbedPane.indexOfComponent(panel);
        if (idx < 0 || target == this) return;
        JLabel label = tabLabel(idx);
        String title = label != null ? label.getText() : "";
        String tooltip = label != null ? label.getToolTipText() : null;
        tabbedPane.removeTabAt(idx);
        updateCard();
        target.insertLogTab(title, tooltip, panel, index);
        target.selectTab(panel);
        log.info("Moved tab {} to window {}", title, manager.numberOf(target));
        if (tabbedPane.getTabCount() == 0) closeWindow();
    }

    private void moveToNewWindow(TabPanel panel) {
        moveTabTo(panel, manager.newWindow(this), -1);
    }

    /**
     * A tab dragged and released at {@code screen}: over this window it stays (the drag has
     * already reordered it); over another Clouseau window it moves there, at the tab under
     * the cursor; anywhere else it opens in a new window at the cursor.
     */
    private void dropTab(TabPanel panel, Point screen) {
        if (getBounds().contains(screen)) return;
        Optional<MainFrame> target = manager.windowAt(screen, this);
        if (target.isPresent()) {
            moveTabTo(panel, target.get(), target.get().tabIndexAt(screen));
            return;
        }
        Dimension size = (getExtendedState() & MAXIMIZED_BOTH) != 0
                ? new Dimension(Math.max(800, getWidth() * 3 / 4), Math.max(500, getHeight() * 3 / 4))
                : getSize();
        // Put the new window's tab strip under the cursor.
        MainFrame window = manager.newWindowAt(new Rectangle(screen.x - 60, screen.y - 15, size.width, size.height));
        moveTabTo(panel, window, -1);
    }

    /** The index of the tab under a point on the screen, or -1 if it isn't over a tab. */
    private int tabIndexAt(Point screen) {
        Point p = new Point(screen);
        SwingUtilities.convertPointFromScreen(p, tabbedPane);
        return tabbedPane.indexAtLocation(p.x, p.y);
    }

    private JPanel buildTabHeader(String title, TabPanel panel) {
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

        // Connection dot for live-source tabs (issue #3); hidden on file tabs.
        SourceLed led = new SourceLed();
        panel.setConnectionListener(() -> {
            led.setState(panel.connectionState(), panel.connectionError());
            if (panel == activeTabPanel()) updateReconnectItem();
        });

        header.add(label);
        header.add(led);
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
                if (ghost[0] != null) {
                    ghost[0].dispose();
                    ghost[0] = null;
                    // After the release is handled, so the header isn't removed under it.
                    Point screen = e.getLocationOnScreen();
                    SwingUtilities.invokeLater(() -> dropTab(panel, screen));
                }
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
        // The dot has a tooltip, so it takes mouse events: let it select and drag too.
        led.addMouseListener(tabMouseAdapter);
        led.addMouseMotionListener(tabMouseAdapter);

        return header;
    }

    private void showTabContextMenu(java.awt.event.MouseEvent e, TabPanel panel) {
        JPopupMenu menu = new JPopupMenu();

        if (panel.canReconnect()) {
            JMenuItem reconnect = new JMenuItem(Messages.get("tab.context.reconnect"));
            reconnect.setAccelerator(reconnectKey());
            reconnect.addActionListener(ev -> panel.reconnect());
            menu.add(reconnect);
            menu.addSeparator();
        }

        JMenuItem tzItem = new JMenuItem(Messages.get("tab.context.source.timezone"));
        tzItem.addActionListener(ev -> panel.showSourceTimezoneDialog(this));
        menu.add(tzItem);

        JMenuItem tzAllItem = new JMenuItem(Messages.get("tab.context.source.timezone.all"));
        tzAllItem.setEnabled(manager.allPanels().size() > 1);
        tzAllItem.addActionListener(ev -> showSourceTimezoneAllTabsDialog(panel.getSourceTimezone()));
        menu.add(tzAllItem);

        // Moving between windows (UC-16). Moving a window's only tab to a new window would
        // just swap one window for another, so that item needs a second tab.
        menu.addSeparator();
        JMenuItem newWindow = new JMenuItem(Messages.get("tab.context.move.new.window"));
        newWindow.setEnabled(tabbedPane.getTabCount() > 1);
        newWindow.addActionListener(ev -> moveToNewWindow(panel));
        menu.add(newWindow);
        JMenu moveTo = new JMenu(Messages.get("tab.context.move.to"));
        for (MainFrame w : manager.windows()) {
            if (w == this) continue;
            JMenuItem item = new JMenuItem(MessageFormat.format(Messages.get("tab.context.move.to.window"),
                    manager.numberOf(w), w.selectedTabTitle()));
            item.addActionListener(ev -> moveTabTo(panel, w, -1));
            moveTo.add(item);
        }
        moveTo.setEnabled(moveTo.getItemCount() > 0);
        menu.add(moveTo);

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
                JMenuItem parser = new JMenuItem(java.text.MessageFormat.format(
                        Messages.get("merge.parser.menu"), sources.get(s)));
                parser.addActionListener(ev -> panel.showParserDialog(this, source));
                fileMenu.add(parser);
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
        for (TabPanel lp : manager.allPanels()) lp.applySourceTimezone(zone);  // every window's tabs
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
        if (comp instanceof TabPanel lp) lp.dispose();
        tabbedPane.removeTabAt(idx);
        updateCard();
    }

    private void updateCard() {
        boolean hasTabs = tabbedPane.getTabCount() > 0;
        cardLayout.show(contentArea, hasTabs ? CARD_TABS : CARD_WELCOME);
        if (closeTabItem != null) closeTabItem.setEnabled(hasTabs);
        updateReconnectItem();
    }

    /** Enables File → Reconnect when the active tab streams a live source. */
    private void updateReconnectItem() {
        if (reconnectItem == null) return;
        TabPanel active = activeTabPanel();
        reconnectItem.setEnabled(active != null && active.canReconnect());
    }

    /** Ctrl+R (Cmd+R on macOS): reconnect the active live-source tab. */
    private static KeyStroke reconnectKey() {
        return KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    private TabPanel activeTabPanel() {
        Component c = tabbedPane.getSelectedComponent();
        return c instanceof TabPanel lp ? lp : null;
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
        label.setFont(ClouseauFonts.mono(Font.PLAIN, 12));

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
