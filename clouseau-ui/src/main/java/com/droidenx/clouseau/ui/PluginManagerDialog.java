package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.runtime.ClouseauPluginManager;
import com.droidenx.clouseau.runtime.ClouseauPluginManager.PluginInfo;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tools → Plugins: an Installed tab and a Browse tab in one window, like IntelliJ's and VS
 * Code's plugin managers.
 *
 * <p>Installed lists each plugin by name, its description under it, with an on/off box on
 * the left and a trash can on the row under the mouse. A file in the plugins folder that
 * didn't load is listed too, marked Failed, with the reason. The list follows the folder: a
 * JAR dropped in or removed while the window is open shows up without a Refresh button.
 */
public final class PluginManagerDialog extends JDialog {

    private static final String CARD_LIST  = "list";
    private static final String CARD_EMPTY = "empty";

    private final ClouseauPluginManager pluginManager;
    private final Runnable onRefresh;

    private final DefaultListModel<PluginInfo> model = new DefaultListModel<>();
    private final PluginList list = new PluginList(model);
    private final CardLayout listCards = new CardLayout();
    private final JPanel listHolder = new JPanel(listCards);
    private final JPanel details = new JPanel(new MigLayout("insets 10 4 0 4, gapy 4, hidemode 3", "[]16[grow]"));
    private final JTabbedPane tabs = new JTabbedPane();
    private BrowsePluginsPanel browse;
    private String folderStamp;
    private final Timer folderWatch;

    public PluginManagerDialog(JFrame owner, ClouseauPluginManager pluginManager, Runnable onRefresh) {
        super(owner, Messages.get("plugins.dialog.title"), true);
        this.pluginManager = pluginManager;
        this.onRefresh     = onRefresh;
        buildUI();
        reload(null);
        if (!model.isEmpty()) list.setSelectedIndex(0);

        // The folder is the source of truth: reload when what's in it changes.
        folderStamp = pluginManager.folderStamp();
        folderWatch = new Timer(1500, e -> {
            String now = pluginManager.folderStamp();
            if (!now.equals(folderStamp)) {
                folderStamp = now;
                reloadPlugins(null);
            }
        });
        folderWatch.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { folderWatch.stop(); }
        });

        setSize(760, 540);
        setMinimumSize(new Dimension(600, 420));
        setLocationRelativeTo(owner);
    }

    /** Opens on the Browse tab: File → Open Remote → Browse Plugins…. */
    public PluginManagerDialog browsing() {
        tabs.setSelectedComponent(browse);
        return this;
    }

    private void buildUI() {
        // ── Installed ─────────────────────────────────────────────────────────
        list.setCellRenderer(new PluginRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showDetails(list.getSelectedValue()); });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle");
        list.getActionMap().put("toggle", action(() -> toggle(list.getSelectedValue())));
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        list.getActionMap().put("delete", action(() -> delete(list.getSelectedValue())));

        JScrollPane scroll = new JScrollPane(list);
        scroll.putClientProperty("FlatLaf.style", ClouseauColors.NO_FOCUS_FRAME);
        listHolder.add(scroll, CARD_LIST);
        listHolder.add(buildEmptyState(), CARD_EMPTY);

        JPanel installed = new JPanel(new MigLayout("fill, insets 16 16 12 16", "[grow]", "[grow][]"));
        installed.add(listHolder, "grow, wrap");
        installed.add(details, "growx, hmin 112");

        // ── Browse ────────────────────────────────────────────────────────────
        browse = new BrowsePluginsPanel(pluginManager.getPluginsRoot(), this::installed);

        tabs.addTab(Messages.get("plugins.tab.installed"), installed);
        tabs.addTab(Messages.get("plugins.tab.browse"), browse);
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == browse) SwingUtilities.invokeLater(() -> browse.searchField().requestFocusInWindow());
        });

        // ── Footer: the folder on the left, Close on the right ───────────────
        Path root = pluginManager.getPluginsRoot();
        JButton openFolder = new JButton(Messages.get("plugins.open.folder"));
        openFolder.setToolTipText(root.toString());
        openFolder.addActionListener(e -> openFolder(root));
        JButton close = new JButton(Messages.get("plugins.dialog.close"));
        close.addActionListener(e -> dispose());

        JPanel footer = DialogFooter.of(new JComponent[]{ openFolder }, close);

        JPanel root2 = new JPanel(new BorderLayout());
        root2.add(tabs, BorderLayout.CENTER);
        root2.add(footer, BorderLayout.SOUTH);
        setContentPane(root2);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private JComponent buildEmptyState() {
        JPanel empty = new JPanel(new MigLayout("fill, insets 16", "[center]", "push[]6[]14[]push"));
        JLabel title = new JLabel(Messages.get("plugins.empty.title"));
        ClouseauColors.themedForeground(title, ClouseauColors::dimForeground);
        JLabel hint = new JLabel(Messages.get("plugins.empty.hint"));
        ClouseauColors.themedForeground(hint, ClouseauColors::mutedForeground);
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        JButton browseBtn = new JButton(Messages.get("plugins.empty.browse"));
        browseBtn.addActionListener(e -> tabs.setSelectedComponent(browse));
        empty.add(title, "wrap");
        empty.add(hint, "wrap");
        empty.add(browseBtn);
        return empty;
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    /** Rebuilds the list from the manager, keeping the selection (or selecting {@code file}). */
    private void reload(Path select) {
        PluginInfo was = list.getSelectedValue();
        model.clear();
        List<PluginInfo> infos = pluginManager.getPluginInfos();
        infos.forEach(model::addElement);
        listCards.show(listHolder, infos.isEmpty() ? CARD_EMPTY : CARD_LIST);
        details.setVisible(!infos.isEmpty());
        int index = -1;
        for (int i = 0; i < model.size(); i++) {
            PluginInfo p = model.get(i);
            if (select != null ? samePath(p.file(), select) : was != null && p.id().equals(was.id())) { index = i; break; }
        }
        if (index < 0 && !model.isEmpty() && was != null) index = 0;
        if (index >= 0) {
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        }
        showDetails(list.getSelectedValue());
    }

    /** Reloads the plugins themselves (the folder changed), then the list. */
    private void reloadPlugins(Path select) {
        pluginManager.refresh();
        onRefresh.run();
        folderStamp = pluginManager.folderStamp();
        reload(select);
    }

    private void installed(Path file) {
        reloadPlugins(file);
        tabs.setSelectedIndex(0);
        list.requestFocusInWindow();
    }

    private static boolean samePath(Path a, Path b) {
        if (a == null || b == null) return false;
        Path na = a.toAbsolutePath().normalize(), nb = b.toAbsolutePath().normalize();
        // A ZIP is loaded from the folder PF4J unpacks it into.
        return na.equals(nb) || na.equals(nb.resolveSibling(nb.getFileName().toString().replaceFirst("(?i)\\.zip$", "")));
    }

    private void toggle(PluginInfo p) {
        if (p == null || p.failed()) return;
        if (p.enabled()) pluginManager.disablePlugin(p.id());
        else pluginManager.enablePlugin(p.id());
        onRefresh.run();
        reload(null);
    }

    private void delete(PluginInfo p) {
        if (p == null) return;
        int confirm = JOptionPane.showConfirmDialog(this,
                Messages.get("plugins.dialog.delete.confirm.message").formatted(p.name()),
                Messages.get("plugins.dialog.delete.confirm.title"),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        // A file that never loaded isn't a plugin PF4J knows; delete the file itself.
        if (!pluginManager.deletePlugin(p.id())) pluginManager.deleteUnloadedFile(p.file());
        onRefresh.run();
        folderStamp = pluginManager.folderStamp();
        reload(null);
    }

    // ── Details ───────────────────────────────────────────────────────────────

    /** What the selected plugin adds, then where it came from. */
    private void showDetails(PluginInfo p) {
        details.removeAll();
        if (p == null) {
            JLabel none = new JLabel(Messages.get("plugins.detail.none"));
            ClouseauColors.themedForeground(none, ClouseauColors::dimForeground);
            details.add(none, "span 2");
        } else {
            if (p.failed()) {
                JLabel problem = new JLabel(p.error() != null ? p.error() : "");
                problem.setForeground(ClouseauColors.levelColor(LogEntry.LogLevel.ERROR));
                row(Messages.get("plugins.detail.problem"), problem);
            } else {
                // A plugin that's off has no extensions loaded to list.
                List<String> extensions = p.enabled() ? pluginManager.getExtensionDetails(p.id()) : List.of();
                if (extensions.isEmpty()) row(Messages.get("plugins.detail.adds"), new JLabel(Messages.get(
                        p.enabled() ? "plugins.detail.no.extensions" : "plugins.detail.off")));
                for (String line : extensions) {
                    int colon = line.indexOf(':');
                    row(colon > 0 ? line.substring(0, colon) : "", new JLabel(colon > 0 ? line.substring(colon + 1).trim() : line));
                }
                row(Messages.get("plugins.detail.id"), new JLabel(p.id()));
                if (!p.provider().isEmpty()) row(Messages.get("plugins.detail.author"), new JLabel(p.provider()));
                if (!p.license().isEmpty())  row(Messages.get("plugins.detail.license"), new JLabel(p.license()));
            }
            if (p.file() != null) {
                JLabel file = new JLabel(p.file().getFileName().toString());
                file.setToolTipText(p.file().toAbsolutePath().toString());
                row(Messages.get("plugins.detail.file"), file);
            }
        }
        details.revalidate();
        details.repaint();
    }

    private void row(String key, JLabel value) {
        JLabel k = new JLabel(key);
        k.setForeground(ClouseauColors.dimForeground());
        details.add(k);
        details.add(value, "wrap");
    }

    private void openFolder(Path path) {
        try {
            Files.createDirectories(path);
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException | UnsupportedOperationException ex) {
            JOptionPane.showMessageDialog(this,
                    Messages.get("plugins.dialog.open.folder.error").formatted(path),
                    Messages.get("plugins.dialog.open.folder.error.title"),
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private static Action action(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        };
    }

    // ── The list ──────────────────────────────────────────────────────────────

    /** Where the on/off box ends, from the row's left edge. */
    private static final int BOX = 34;
    /** The trash can's hit area, from the row's right edge. */
    private static final int TRASH = 30;

    /**
     * The installed plugins. A press on the box turns a plugin on or off; the trash can at the
     * right of the row under the mouse deletes it. Neither press moves the selection.
     */
    private final class PluginList extends JList<PluginInfo> {
        private int hoverRow = -1;
        private boolean overTrash;

        PluginList(ListModel<PluginInfo> m) {
            super(m);
            java.awt.event.MouseAdapter hover = new java.awt.event.MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e)  { track(e.getPoint()); }
                @Override public void mouseExited(MouseEvent e) { hoverRow = -1; overTrash = false; repaint(); }
            };
            addMouseListener(hover);
            addMouseMotionListener(hover);
            setToolTipText("");
        }

        private int rowAt(Point p) {
            int i = locationToIndex(p);
            Rectangle r = i >= 0 ? getCellBounds(i, i) : null;
            return r != null && r.contains(p) ? i : -1;
        }

        private void track(Point p) {
            int row = rowAt(p);
            boolean trash = row >= 0 && p.x >= getWidth() - TRASH;
            if (row != hoverRow || trash != overTrash) {
                hoverRow = row;
                overTrash = trash;
                repaint();
            }
        }

        @Override protected void processMouseEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(e)) {
                track(e.getPoint());
                int row = rowAt(e.getPoint());
                if (row >= 0 && (overTrash || e.getX() < BOX)) {
                    PluginInfo p = getModel().getElementAt(row);
                    boolean trash = overTrash;
                    e.consume();
                    SwingUtilities.invokeLater(() -> { if (trash) delete(p); else toggle(p); });
                    return;
                }
            }
            super.processMouseEvent(e);
        }

        @Override public String getToolTipText(MouseEvent e) {
            int row = rowAt(e.getPoint());
            if (row < 0) return null;
            if (e.getX() >= getWidth() - TRASH) return Messages.get("plugins.row.delete.tooltip");
            PluginInfo p = getModel().getElementAt(row);
            if (e.getX() < BOX && !p.failed()) return Messages.get("plugins.row.toggle.tooltip");
            return null;
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (hoverRow < 0 || hoverRow >= getModel().getSize()) return;
            Rectangle r = getCellBounds(hoverRow, hoverRow);
            if (r == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = isSelectedIndex(hoverRow) ? getSelectionForeground() : ClouseauColors.dimForeground();
            ToolIcons.paintTrash(g2, getWidth() - TRASH + 8, r.y + (r.height - 14) / 2,
                    overTrash ? ClouseauColors.levelColor(LogEntry.LogLevel.ERROR) : base);
            g2.dispose();
        }
    }

    /**
     * A plugin's row: the on/off box (or a Failed mark), its name in bold with the version
     * dimmed after it, and what it adds on the right; its description (or why it failed) below.
     * A plugin that's off reads dimmed.
     */
    private static final class PluginRenderer extends JPanel implements ListCellRenderer<PluginInfo> {
        private final JCheckBox box = new JCheckBox();
        private final JLabel failedMark = new JLabel("!", SwingConstants.CENTER);
        private final JLabel name = new JLabel();
        private final JLabel version = new JLabel();
        private final JLabel types = new JLabel();
        private final JLabel description = new JLabel();

        PluginRenderer() {
            super(new MigLayout("insets 7 8 7 " + TRASH + ", gapx 8, gapy 2, hidemode 3",
                    "[" + (BOX - 16) + "!][][grow][]", "[][]"));
            box.setOpaque(false);
            box.setContentAreaFilled(false);
            failedMark.setFont(failedMark.getFont().deriveFont(Font.BOLD));
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            add(box, "spany 2, aligny top, gaptop 1");
            add(failedMark, "spany 2, aligny top, gaptop 1, w 16!");
            add(name);
            add(version, "growx");
            add(types, "wrap");
            add(description, "span 3, growx, wmin 0");
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends PluginInfo> list, PluginInfo p,
                                                      int index, boolean selected, boolean focus) {
            Color fg = selected ? list.getSelectionForeground() : list.getForeground();
            Color dim = selected ? list.getSelectionForeground() : ClouseauColors.dimForeground();
            Color error = ClouseauColors.levelColor(LogEntry.LogLevel.ERROR);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setOpaque(true);

            box.setVisible(!p.failed());
            box.setSelected(p.enabled());
            failedMark.setVisible(p.failed());
            failedMark.setForeground(error);

            name.setText(p.name());
            name.setForeground(p.enabled() || p.failed() ? fg : dim);
            version.setText(p.version());
            version.setForeground(dim);
            if (p.failed()) {
                types.setText(Messages.get("plugins.state.failed"));
                types.setForeground(error);
                description.setText(p.error() != null ? p.error() : "");
                description.setForeground(selected ? fg : error);
            } else {
                types.setText(translateTypes(p.types()));
                types.setForeground(dim);
                description.setText(p.description().isEmpty() ? " " : p.description());
                description.setForeground(dim);
            }
            return this;
        }

        private static String translateTypes(String types) {
            if (types == null || "—".equals(types) || types.isBlank()) return "";
            return java.util.Arrays.stream(types.split(", "))
                    .map(t -> switch (t) {
                        case "Parser"      -> Messages.get("plugins.type.parser");
                        case "Formatter"   -> Messages.get("plugins.type.formatter");
                        case "Highlighter" -> Messages.get("plugins.type.highlighter");
                        case "Source"      -> Messages.get("plugins.type.source");
                        default            -> t;
                    })
                    .collect(java.util.stream.Collectors.joining(" · "));
        }
    }
}
