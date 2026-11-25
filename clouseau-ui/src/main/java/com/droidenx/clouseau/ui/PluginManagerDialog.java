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
 *
 * <p>A plugin with a newer version in its repository (D-33) has an update mark on its row and an
 * Update button in its details; opening the window checks again.
 */
public final class PluginManagerDialog extends JDialog {

    private static final String CARD_LIST  = "list";
    private static final String CARD_EMPTY = "empty";

    private final ClouseauPluginManager pluginManager;
    private final Runnable onRefresh;
    private final PluginUpdates updates;
    private final Runnable updatesListener = this::updatesChanged;
    /** The plugin being updated from the details, or null. */
    private Path updating;

    private final DefaultListModel<PluginInfo> model = new DefaultListModel<>();
    private final PluginList list = new PluginList(model);
    private final CardLayout listCards = new CardLayout();
    private final JPanel listHolder = new JPanel(listCards);
    /** Says what was just installed or updated, over the list. */
    private final Toast toast = new Toast();
    private final JPanel details = new JPanel(new MigLayout("insets 10 4 0 4, gapy 4, hidemode 3", "[]16[grow]"));
    private final JTabbedPane tabs = new JTabbedPane();
    private BrowsePluginsPanel browse;
    private String folderStamp;
    private final Timer folderWatch;

    public PluginManagerDialog(JFrame owner, ClouseauPluginManager pluginManager, PluginUpdates updates, Runnable onRefresh) {
        super(owner, Messages.get("plugins.dialog.title"), true);
        this.pluginManager = pluginManager;
        this.updates       = updates;
        this.onRefresh     = onRefresh;
        buildUI();
        reload(null);
        if (!model.isEmpty()) list.setSelectedIndex(0);
        showUpdateCount();

        // What has an update: known from the last check at once, then asked again.
        updates.addListener(updatesListener);
        updates.folderChanged();
        updates.check(null);

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
            @Override public void windowClosed(java.awt.event.WindowEvent e) { folderWatch.stop(); updates.removeListener(updatesListener); }
        });

        ManagerDialogs.rememberSize(this, "plugins", new Dimension(760, 540), new Dimension(600, 420));
        setLocationRelativeTo(owner);
        // The Installed list has the keys (Space, Delete) when the window opens; the Browse tab
        // moves the focus to its search field itself.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                if (tabs.getSelectedComponent() != browse) list.requestFocusInWindow();
            }
        });
    }

    private void buildUI() {
        // ── Installed ─────────────────────────────────────────────────────────
        list.setCellRenderer(new PluginRenderer(p -> updates.forFile(p.file())));
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
        browse = new BrowsePluginsPanel(pluginManager.getPluginsRoot(), this::install);

        tabs.addTab(Messages.get("plugins.tab.installed"), installed);
        tabs.addTab(Messages.get("plugins.tab.browse"), browse);
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == browse) {
                browse.shown();
                SwingUtilities.invokeLater(() -> browse.searchField().requestFocusInWindow());
            }
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
        updates.folderChanged();
        reload(select);
    }

    /**
     * Browse downloaded a plugin: an update first removes the version it replaces (stopped and
     * unloaded, so Windows lets go of the JAR; two versions loaded at once would clash), then the
     * new JAR moves into the folder and the plugins reload, with it selected in Installed.
     */
    private Path install(Path downloaded, Path replaces) throws IOException {
        Path root = pluginManager.getPluginsRoot();
        String oldVersion = null;
        if (replaces != null) {
            PluginInfo old = pluginManager.getPluginInfos().stream()
                    .filter(p -> samePath(p.file(), replaces)).findFirst().orElse(null);
            if (old != null && !old.failed()) oldVersion = old.version();
            if (old == null || !pluginManager.deletePlugin(old.id())) pluginManager.deleteUnloadedFile(replaces);
            if (Files.exists(replaces))
                throw new IOException(Messages.get("plugins.download.update.locked").formatted(replaces.getFileName()));
        }
        Files.createDirectories(root);
        Path target = root.resolve(downloaded.getFileName().toString());
        Files.move(downloaded, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        installed(target, replaces != null, oldVersion);
        return target;
    }

    /**
     * The new JAR is in the folder: reload, show it selected in Installed, and say in a toast over
     * the list what was installed or updated, and to which version. One that didn't load says so;
     * its details tell why.
     */
    private void installed(Path file, boolean update, String oldVersion) {
        reloadPlugins(file);
        tabs.setSelectedIndex(0);
        list.requestFocusInWindow();
        PluginInfo p = list.getSelectedValue();
        if (p == null || !samePath(p.file(), file)) return;
        String text = installedToast(p.name(), p.version(), p.failed(), update, oldVersion);
        int index = list.getSelectedIndex();
        // After the tab switch is laid out, so the toast finds the list on screen and keeps clear of the row.
        SwingUtilities.invokeLater(() -> {
            Rectangle row = list.getCellBounds(index, index);
            toast.show(listHolder, text, row == null ? null : SwingUtilities.convertRectangle(list, row, listHolder));
        });
    }

    /** "Updated Foo to 1.2.0 (was 1.1.0)", "Installed Foo 1.2.0", or that it didn't load. */
    static String installedToast(String name, String version, boolean failed, boolean update, String oldVersion) {
        if (failed) return java.text.MessageFormat.format(Messages.get("plugins.installed.toast.failed"), name);
        if (!update) return java.text.MessageFormat.format(Messages.get("plugins.installed.toast"), name, version);
        return oldVersion == null || oldVersion.equals(version)
                ? java.text.MessageFormat.format(Messages.get("plugins.updated.toast"), name, version)
                : java.text.MessageFormat.format(Messages.get("plugins.updated.toast.from"), name, version, oldVersion);
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
        if (!ManagerDialogs.confirmDelete(this, Messages.get("plugins.dialog.delete.confirm.title"),
                Messages.get("plugins.dialog.delete.confirm.message").formatted(p.name()))) return;
        // A file that never loaded isn't a plugin PF4J knows; delete the file itself.
        if (!pluginManager.deletePlugin(p.id())) pluginManager.deleteUnloadedFile(p.file());
        onRefresh.run();
        folderStamp = pluginManager.folderStamp();
        updates.folderChanged();
        reload(null);
    }

    // ── Updates (D-33) ────────────────────────────────────────────────────────

    /** The updates changed: the rows' marks, the tab's count and the details. */
    private void updatesChanged() {
        list.repaint();
        showUpdateCount();
        if (updating == null) showDetails(list.getSelectedValue());
    }

    /** The Installed tab says how many plugins have an update. */
    private void showUpdateCount() {
        int n = updates.current().size();
        tabs.setTitleAt(0, n == 0 ? Messages.get("plugins.tab.installed")
                : java.text.MessageFormat.format(Messages.get("plugins.tab.installed.updates"), n));
    }

    /**
     * Updates {@code p} from its details: downloads the new version from the repository the
     * update came from, in the background, then swaps it in as Browse's Update does.
     */
    private void runUpdate(PluginInfo p, PluginUpdates.Update u, JButton button) {
        PluginRepository repo = PluginRepository.configured().stream()
                .filter(r -> r.getName().equals(u.repository())).findFirst().orElse(null);
        if (repo == null) return;
        updating = u.file();
        button.setEnabled(false);
        button.setText(Messages.get("plugins.update.running"));
        new SwingWorker<Path, Void>() {
            private Path tempDir;
            @Override protected Path doInBackground() throws Exception {
                tempDir = Files.createTempDirectory("clouseau-plugin");
                return repo.download(u.latest(), tempDir, bytes -> {});
            }
            @Override protected void done() {
                updating = null;
                try {
                    install(get(), u.file());
                } catch (Exception ex) {
                    Throwable cause = ex instanceof java.util.concurrent.ExecutionException && ex.getCause() != null
                            ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(PluginManagerDialog.this,
                            Messages.get("plugins.update.failed").formatted(p.name(), cause.getMessage()),
                            Messages.get("plugins.download.install.error.title"), JOptionPane.WARNING_MESSAGE);
                    showDetails(list.getSelectedValue());
                } finally {
                    deleteQuietly(tempDir);
                }
            }
        }.execute();
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (IOException ignored) { /* temp: the OS cleans it */ }
            });
        } catch (IOException ignored) { /* temp: the OS cleans it */ }
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
            PluginUpdates.Update u = updates.forFile(p.file());
            if (u != null) {
                JButton update = new JButton(java.text.MessageFormat.format(
                        Messages.get("plugins.update.button"), u.latest().version()));
                if (updating != null && updating.equals(u.file())) {
                    update.setEnabled(false);
                    update.setText(Messages.get("plugins.update.running"));
                }
                update.addActionListener(e -> runUpdate(p, u, update));
                JLabel k = new JLabel(Messages.get("plugins.detail.update"));
                k.setForeground(ClouseauColors.dimForeground());
                details.add(k);
                details.add(update, "wrap");
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
            PluginUpdates.Update u = updates.forFile(p.file());
            if (u != null) return java.text.MessageFormat.format(Messages.get("plugins.row.update.tooltip"), u.latest().version(), u.installed());
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
     * A plugin that's off reads dimmed. One with a newer version has an update mark (an arrow
     * and the new version, in the accent color) after its version.
     */
    private static final class PluginRenderer extends ListRowPanel implements ListCellRenderer<PluginInfo> {
        private final java.util.function.Function<PluginInfo, PluginUpdates.Update> updateOf;
        private final JLabel update = new JLabel();
        private Color updateColor;
        /** The update mark's arrow, in {@link #updateColor}. */
        private final Icon updateIcon = new Icon() {
            @Override public int getIconWidth()  { return 12; }
            @Override public int getIconHeight() { return 12; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                ToolIcons.paintUpdate(g2, x, y, updateColor);
                g2.dispose();
            }
        };
        private final JCheckBox box = new JCheckBox();
        private final JLabel failedMark = new JLabel("!", SwingConstants.CENTER);
        private final JLabel name = new JLabel();
        private final JLabel version = new JLabel();
        private final JLabel types = new JLabel();
        private final JLabel description = new JLabel();

        PluginRenderer(java.util.function.Function<PluginInfo, PluginUpdates.Update> updateOf) {
            // 12px on the left: 8px inside the selection, which is inset 4px (List.selectionInsets).
            super(new MigLayout("insets 7 12 7 " + TRASH + ", gapx 8, gapy 2, hidemode 3",
                    "[" + (BOX - 16) + "!][][][grow][]", "[][]"));
            this.updateOf = updateOf;
            update.setIconTextGap(4);
            box.setOpaque(false);
            box.setContentAreaFilled(false);
            failedMark.setFont(failedMark.getFont().deriveFont(Font.BOLD));
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            add(box, "spany 2, aligny top, gaptop 1");
            add(failedMark, "spany 2, aligny top, gaptop 1, w 16!");
            add(name);
            add(version);
            add(update, "growx");
            add(types, "wrap");
            add(description, "span 4, growx, wmin 0");
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends PluginInfo> list, PluginInfo p,
                                                      int index, boolean selected, boolean focus) {
            Color fg = selected ? list.getSelectionForeground() : list.getForeground();
            Color dim = selected ? list.getSelectionForeground() : ClouseauColors.dimForeground();
            Color error = ClouseauColors.levelColor(LogEntry.LogLevel.ERROR);
            prepare(list, index, selected ? list.getSelectionBackground() : null);

            box.setVisible(!p.failed());
            box.setSelected(p.enabled());
            failedMark.setVisible(p.failed());
            failedMark.setForeground(error);

            name.setText(p.name());
            name.setForeground(p.enabled() || p.failed() ? fg : dim);
            version.setText(p.version());
            version.setForeground(dim);
            PluginUpdates.Update u = updateOf.apply(p);
            // Always there, empty when there's no update: it's the cell that stretches, which keeps
            // what the plugin adds (or Failed) on the right.
            update.setIcon(u != null ? updateIcon : null);
            update.setText(u != null ? u.latest().version() : "");
            if (u != null) {
                updateColor = selected ? list.getSelectionForeground() : ClouseauColors.accentBlue();
                update.setForeground(updateColor);
            }
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
