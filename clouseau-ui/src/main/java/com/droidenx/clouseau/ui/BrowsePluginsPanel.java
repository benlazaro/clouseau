package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * The Plugin Manager's Browse tab: search a plugin repository and install a JAR into the
 * plugins folder. Searching and downloading run on SwingWorker threads; all UI updates happen
 * on the EDT. Results and problems show under the table rather than in message boxes.
 */
final class BrowsePluginsPanel extends JPanel {

    private final Path           pluginsDir;
    private final Consumer<Path> onInstalled;

    private final DefaultComboBoxModel<PluginRepository> repoModel = new DefaultComboBoxModel<>();
    private final JComboBox<PluginRepository>            repoCombo = new JComboBox<>(repoModel);
    private final JTextField    searchField = new JTextField(24);
    private final JButton       searchBtn   = new JButton(Messages.get("plugins.download.search"));
    private final JButton       installBtn  = new JButton(Messages.get("plugins.download.install"));
    private final JProgressBar  progressBar = new JProgressBar();
    private final JLabel        status      = new JLabel(" ");
    private final ResultsModel  tableModel  = new ResultsModel();
    private final JTable        table       = new JTable(tableModel);

    /** @param onInstalled called on the EDT with the installed file */
    BrowsePluginsPanel(Path pluginsDir, Consumer<Path> onInstalled) {
        super(new MigLayout("fill, insets 16 16 12 16, wrap 1", "[grow]", "[]8[]8[grow]6[]"));
        this.pluginsDir  = pluginsDir;
        this.onInstalled = onInstalled;
        rebuildRepos();
        buildUI();
    }

    /** The field Browse starts in. */
    JTextField searchField() { return searchField; }

    private void rebuildRepos() {
        PluginRepository selected = (PluginRepository) repoCombo.getSelectedItem();
        repoModel.removeAllElements();
        repoModel.addElement(new MavenCentralPluginRepository());
        for (AppPrefs.PluginRepo r : AppPrefs.getPluginRepos()) {
            if ("nexus3".equals(r.type())) repoModel.addElement(new NexusPluginRepository(r));
        }
        if (selected != null) {
            for (int i = 0; i < repoModel.getSize(); i++) {
                if (repoModel.getElementAt(i).getName().equals(selected.getName())) {
                    repoCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private void buildUI() {
        // ── Repository row ────────────────────────────────────────────────────
        repoCombo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof PluginRepository r) setText(r.getName());
                return this;
            }
        });

        JButton manageBtn = new JButton(Messages.get("plugins.download.manage.repos"));
        manageBtn.addActionListener(e ->
                new PluginRepoManagerDialog(SwingUtilities.getWindowAncestor(this), this::rebuildRepos).setVisible(true));

        JPanel repoRow = new JPanel(new MigLayout("insets 0, gapx 6", "[][grow][]"));
        repoRow.add(new JLabel(Messages.get("plugins.download.repo.label")));
        repoRow.add(repoCombo, "growx");
        repoRow.add(manageBtn);
        add(repoRow, "growx");

        // ── Search row ────────────────────────────────────────────────────────
        JButton helpBtn = new JButton("?");
        helpBtn.setMargin(new Insets(2, 6, 2, 6));
        helpBtn.setToolTipText(Messages.get("plugins.download.search.help.tooltip"));
        helpBtn.addActionListener(e -> showSearchHelp());
        searchField.putClientProperty("JTextField.placeholderText", Messages.get("plugins.download.search.placeholder"));

        JPanel searchRow = new JPanel(new MigLayout("insets 0, gapx 6", "[grow][][]"));
        searchRow.add(searchField, "growx");
        searchRow.add(searchBtn);
        searchRow.add(helpBtn);
        add(searchRow, "growx");

        searchBtn.addActionListener(e -> doSearch());
        searchField.addActionListener(e -> doSearch());

        // ── Results table ─────────────────────────────────────────────────────
        table.setRowHeight(26);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        DefaultTableCellRenderer left = new DefaultTableCellRenderer();
        left.setHorizontalAlignment(SwingConstants.LEADING);
        ((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer())
                .setHorizontalAlignment(SwingConstants.LEADING);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.TRAILING);
        table.getColumnModel().getColumn(1).setMinWidth(90);
        table.getColumnModel().getColumn(1).setMaxWidth(90);
        table.getColumnModel().getColumn(2).setMinWidth(80);
        table.getColumnModel().getColumn(2).setMaxWidth(80);
        table.getColumnModel().getColumn(2).setCellRenderer(right);
        table.getSelectionModel().addListSelectionListener(
                e -> installBtn.setEnabled(table.getSelectedRow() >= 0));
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0 && installBtn.isEnabled()) doInstall();
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.putClientProperty("FlatLaf.style", ClouseauColors.NO_FOCUS_FRAME);
        add(scroll, "grow");

        // ── Status, progress and Install ──────────────────────────────────────
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        ClouseauColors.themedForeground(status, ClouseauColors::dimForeground);
        installBtn.setEnabled(false);
        installBtn.addActionListener(e -> doInstall());

        say(Messages.get("plugins.download.start"), false);
        JPanel bottom = new JPanel(new MigLayout("insets 0, hidemode 3, gapx 8", "[grow][]"));
        bottom.add(status, "growx");
        bottom.add(progressBar, "growx");
        bottom.add(installBtn);
        add(bottom, "growx");
    }

    private void say(String text, boolean problem) {
        status.setText(text == null || text.isBlank() ? " " : text);
        status.setForeground(problem ? ClouseauColors.levelColor(com.droidenx.clouseau.api.LogEntry.LogLevel.ERROR)
                                     : ClouseauColors.dimForeground());
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void showSearchHelp() {
        // Qualifier · what it matches · an example; the qualifiers and examples are typed, so
        // they're in the code font.
        int size = getFont().getSize();
        Font mono = com.droidenx.clouseau.ui.theme.ClouseauFonts.mono(Font.PLAIN, size);
        JPanel help = new JPanel(new MigLayout("insets 0, gapx 14, gapy 6", "[][][]"));
        for (String q : new String[]{ "g", "a", "v", "p", "c", "fc" }) {
            JLabel qualifier = new JLabel(q + ":");
            qualifier.setFont(mono);
            JLabel what = new JLabel(Messages.get("plugins.download.search.help." + q));
            String ex = Messages.get("plugins.download.search.help." + q + ".example");
            JLabel example = new JLabel(ex);
            example.setFont(mono);
            ClouseauColors.themedForeground(example, ClouseauColors::dimForeground);
            help.add(qualifier);
            help.add(what);
            help.add(example, "wrap");
        }
        JLabel note = new JLabel(Messages.get("plugins.download.search.help.note"));
        ClouseauColors.themedForeground(note, ClouseauColors::dimForeground);
        help.add(note, "span 3, gaptop 10");
        JOptionPane.showMessageDialog(this, help,
                Messages.get("plugins.download.search.help.title"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void doSearch() {
        PluginRepository repo = (PluginRepository) repoCombo.getSelectedItem();
        if (repo == null) return;
        String query = searchField.getText().trim();
        if (query.isBlank()) return;

        searchBtn.setEnabled(false);
        tableModel.setResults(List.of());
        say(Messages.get("plugins.download.searching"), false);

        new SwingWorker<List<PluginRepository.Asset>, Void>() {
            @Override
            protected List<PluginRepository.Asset> doInBackground() throws Exception {
                return repo.search(query);
            }
            @Override
            protected void done() {
                searchBtn.setEnabled(true);
                try {
                    tableModel.setResults(get());
                    if (tableModel.getRowCount() == 0) {
                        boolean authWarning = repo instanceof NexusPluginRepository nexusRepo
                                && nexusRepo.hasAuthConfigured();
                        say(Messages.get("plugins.download.search.no.results")
                                + (authWarning ? " " + Messages.get("plugins.download.search.no.results.auth.short") : ""),
                                authWarning);
                        if (authWarning) status.setToolTipText(
                                Messages.get("plugins.download.search.no.results.auth.hint").replace("\n", " "));
                    } else {
                        say(java.text.MessageFormat.format(Messages.get("plugins.download.found"), tableModel.getRowCount()), false);
                        status.setToolTipText(null);
                    }
                } catch (InterruptedException | ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    say(Messages.get("plugins.download.search.error.title") + ": " + cause.getMessage(), true);
                }
            }
        }.execute();
    }

    private void doInstall() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        PluginRepository       repo  = (PluginRepository) repoCombo.getSelectedItem();
        PluginRepository.Asset asset = tableModel.getAsset(row);

        installBtn.setEnabled(false);
        searchBtn.setEnabled(false);
        progressBar.setValue(0);
        progressBar.setIndeterminate(asset.sizeBytes() <= 0);
        progressBar.setString("");
        progressBar.setVisible(true);
        status.setVisible(false);

        new SwingWorker<Path, Long>() {
            @Override
            protected Path doInBackground() throws Exception {
                return repo.download(asset, pluginsDir, bytes -> publish(bytes));
            }
            @Override
            protected void process(List<Long> chunks) {
                long bytes = chunks.get(chunks.size() - 1);
                if (asset.sizeBytes() > 0) {
                    int pct = (int) (bytes * 100 / asset.sizeBytes());
                    progressBar.setValue(pct);
                    progressBar.setString(pct + "%  (" + formatBytes(bytes) + ")");
                } else {
                    progressBar.setString(formatBytes(bytes));
                }
            }
            @Override
            protected void done() {
                progressBar.setVisible(false);
                status.setVisible(true);
                installBtn.setEnabled(true);
                searchBtn.setEnabled(true);
                try {
                    Path installed = get();
                    say(" ", false);
                    onInstalled.accept(installed);
                } catch (InterruptedException | ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    say(Messages.get("plugins.download.install.error.title") + ": " + cause.getMessage(), true);
                }
            }
        }.execute();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L)        return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ── Results table model ───────────────────────────────────────────────────

    private static final class ResultsModel extends AbstractTableModel {

        private static final String[] COLS = {
            Messages.get("plugins.download.col.artifact"),
            Messages.get("plugins.download.col.version"),
            Messages.get("plugins.download.col.size"),
        };

        private List<PluginRepository.Asset> rows = new ArrayList<>();

        void setResults(List<PluginRepository.Asset> results) {
            rows = new ArrayList<>(results);
            fireTableDataChanged();
        }

        PluginRepository.Asset getAsset(int row) { return rows.get(row); }

        @Override public int    getRowCount()              { return rows.size(); }
        @Override public int    getColumnCount()           { return COLS.length; }
        @Override public String getColumnName(int col)     { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            PluginRepository.Asset a = rows.get(row);
            return switch (col) {
                case 0 -> a.groupId().isBlank() ? a.artifactId()
                                                : a.groupId() + ":" + a.artifactId();
                case 1 -> a.version();
                case 2 -> a.sizeBytes() > 0 ? formatBytes(a.sizeBytes()) : "—";
                default -> null;
            };
        }
    }
}
