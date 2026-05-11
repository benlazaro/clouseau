package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.runtime.ClouseauPluginManager;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.ui.theme.ThemeManager;
import com.droidenx.clouseau.ui.theme.ThemeManagerDialog;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.nio.file.Path;
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
        this.parsers = new ArrayList<>(UserParsersLoader.load());
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
    }

    // ── Extension list management ─────────────────────────────────────────────

    /** Called after parsers.json is saved to reload user-defined parsers. */
    void reloadParsers() {
        this.parsers = new ArrayList<>(UserParsersLoader.load());
        if (lastParserIndex > parsers.size()) lastParserIndex = 0;
    }

    /** Called after a plugin refresh to re-fetch formatters and highlighters from the plugin manager. */
    void reloadExtensions() {
        this.formatters = new ArrayList<>();
        this.formatters.add(new JsonMessageFormatter());
        this.formatters.addAll(pluginManager.getExtensions(LogFormatter.class));
        log.info("Reloaded {} formatter(s): {}", formatters.size(),
                formatters.stream().map(LogFormatter::getName).toList());

        this.syntaxHighlighters = new ArrayList<>();
        this.syntaxHighlighters.add(new JsonSyntaxHighlighter());
        this.syntaxHighlighters.addAll(pluginManager.getExtensions(LogSyntaxHighlighter.class));
        log.info("Reloaded {} syntax highlighter(s): {}", syntaxHighlighters.size(),
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
        BufferedImage icon64 = rasterizeSvg(
                MainFrame.class.getResource("/com/droidenx/clouseau/ui/icons/clouseau.svg").toString(), 64, 1.15f);
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
                openFile(file, r.parser());
            }
        });
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

    private void openFile(Path file, Optional<LogParser> parser) {
        Path abs = file.toAbsolutePath();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getComponentAt(i) instanceof LogPanel lp && abs.equals(lp.getFile())) {
                tabbedPane.setSelectedIndex(i);
                return;
            }
        }
        AppPrefs.addRecentFile(file);
        refreshRecentMenu();
        LogPanel panel = new LogPanel(parsers, formatters, syntaxHighlighters);
        ZoneId savedTz = AppPrefs.getSourceTimezone(abs);
        if (savedTz != null) panel.setSourceTimezone(savedTz);
        addLogTab(file.getFileName().toString(), abs, panel);
        panel.load(file, parser, () -> {
            AppPrefs.removeRecentFile(file);
            refreshRecentMenu();
            int idx = tabbedPane.indexOfComponent(panel);
            if (idx >= 0) {
                panel.dispose();
                tabbedPane.removeTabAt(idx);
                updateCard();
            }
        });
    }

    private void openSsh() {
        new SshConnectionDialog(this, parsers).showDialog().ifPresent(r -> {
            log.info("Opening SSH: {}", r.config().displayName());
            LogPanel panel = new LogPanel(parsers, formatters, syntaxHighlighters);
            String title = r.config().user() + "@" + r.config().host();
            addLogTab(title, null, panel);
            panel.loadSsh(r.config(), r.parser());
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
        menu.show((Component) e.getSource(), e.getX(), e.getY());
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

        BufferedImage watermark = renderSvgWatermark(256);
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

    private static BufferedImage renderSvgWatermark(int size) {
        var svgUrl = MainFrame.class.getResource("/com/droidenx/clouseau/ui/icons/clouseau.svg");
        if (svgUrl == null) return null;
        try {
            // rasterize at 2x for crisp rendering on HiDPI displays
            int renderSize = size * 2;
            var transcoder = new org.apache.batik.transcoder.image.PNGTranscoder();
            transcoder.addTranscodingHint(org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_WIDTH, (float) renderSize);
            transcoder.addTranscodingHint(org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_HEIGHT, (float) renderSize);
            var output = new java.io.ByteArrayOutputStream();
            transcoder.transcode(new org.apache.batik.transcoder.TranscoderInput(svgUrl.toString()),
                                 new org.apache.batik.transcoder.TranscoderOutput(output));
            BufferedImage hi = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(output.toByteArray()));

            // scale down smoothly to target size
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(hi, 0, 0, size, size, null);
            g2.dispose();

            new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null).filter(img, img);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int argb = img.getRGB(x, y);
                    int alpha = (argb >>> 24) & 0xFF;
                    img.setRGB(x, y, (int)(alpha * 0.23) << 24 | (argb & 0x00FFFFFF));
                }
            }
            return img;
        } catch (Exception e) {
            log.warn("Could not render SVG watermark", e);
            return null;
        }
    }

    private static BufferedImage rasterizeSvg(String svgUri, int size) {
        return rasterizeSvg(svgUri, size, 1.0f);
    }

    private static BufferedImage rasterizeSvg(String svgUri, int size, float zoom) {
        try {
            int renderSize = Math.round(size * zoom);
            var transcoder = new org.apache.batik.transcoder.image.PNGTranscoder();
            transcoder.addTranscodingHint(org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_WIDTH, (float) renderSize);
            transcoder.addTranscodingHint(org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_HEIGHT, (float) renderSize);
            var output = new java.io.ByteArrayOutputStream();
            transcoder.transcode(new org.apache.batik.transcoder.TranscoderInput(svgUri),
                                 new org.apache.batik.transcoder.TranscoderOutput(output));
            BufferedImage rendered = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(output.toByteArray()));
            if (zoom == 1.0f) return rendered;
            // center-crop back to target size
            int offset = (renderSize - size) / 2;
            BufferedImage cropped = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = cropped.createGraphics();
            g2.drawImage(rendered, 0, 0, size, size, offset, offset, offset + size, offset + size, null);
            g2.dispose();
            return cropped;
        } catch (Exception e) {
            log.warn("Could not rasterize SVG at size {}", size, e);
            return null;
        }
    }

    private static List<Image> loadAppIcons() {
        var svgUrl = MainFrame.class.getResource("/com/droidenx/clouseau/ui/icons/clouseau.svg");
        if (svgUrl == null) return List.of();
        return Stream.of(256, 64, 16)
                .map(size -> rasterizeSvg(svgUrl.toString(), size, 1.19f))
                .filter(Objects::nonNull)
                .map(img -> (Image) img)
                .toList();
    }
}
