package com.droidenx.clouseau.ui;

import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Dialog for searching a plugin repository and installing JARs into the plugins folder.
 * Searching and downloading run on SwingWorker threads; all UI updates happen on the EDT.
 */
final class DownloadPluginDialog extends JDialog {

    private final Path     pluginsDir;
    private final Runnable onInstalled;

    private final DefaultComboBoxModel<PluginRepository> repoModel = new DefaultComboBoxModel<>();
    private final JComboBox<PluginRepository>            repoCombo = new JComboBox<>(repoModel);
    private final JTextField    searchField = new JTextField(24);
    private final JButton       searchBtn   = new JButton(Messages.get("plugins.download.search"));
    private final JButton       installBtn  = new JButton(Messages.get("plugins.download.install"));
    private final JProgressBar  progressBar = new JProgressBar();
    private final ResultsModel  tableModel  = new ResultsModel();
    private       JTable        table;

    DownloadPluginDialog(Window owner, Path pluginsDir, Runnable onInstalled) {
        super(owner, Messages.get("plugins.download.title"), ModalityType.APPLICATION_MODAL);
        this.pluginsDir  = pluginsDir;
        this.onInstalled = onInstalled;
        rebuildRepos();
        buildUI();
        pack();
        setMinimumSize(new Dimension(600, 420));
        setResizable(true);
        setLocationRelativeTo(owner);
    }

    private void rebuildRepos() {
        PluginRepository selected = (PluginRepository) repoCombo.getSelectedItem();
        repoModel.removeAllElements();
        repoModel.addElement(new MavenCentralPluginRepository());
        for (AppPrefs.PluginRepo r : AppPrefs.getPluginRepos()) {
            if ("nexus3".equals(r.type())) repoModel.addElement(new NexusPluginRepository(r));
        }
        // restore selection if possible
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
        JPanel content = new JPanel(new MigLayout(
                "fill, insets 16, wrap 1", "[grow]", "[]8[]8[grow]8[]8[]"));

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
        manageBtn.addActionListener(e -> {
            new PluginRepoManagerDialog(this, this::rebuildRepos).setVisible(true);
        });

        JPanel repoRow = new JPanel(new MigLayout("insets 0, gapx 6", "[][grow][]"));
        repoRow.add(new JLabel(Messages.get("plugins.download.repo.label")));
        repoRow.add(repoCombo, "growx");
        repoRow.add(manageBtn);
        content.add(repoRow, "growx");

        // ── Search row ────────────────────────────────────────────────────────
        JButton helpBtn = new JButton("?");
        helpBtn.setMargin(new java.awt.Insets(2, 6, 2, 6));
        helpBtn.setToolTipText(Messages.get("plugins.download.search.help.tooltip"));
        helpBtn.addActionListener(e -> showSearchHelp());

        JPanel searchRow = new JPanel(new MigLayout("insets 0, gapx 6", "[grow][][]"));
        searchRow.add(searchField, "growx");
        searchRow.add(searchBtn);
        searchRow.add(helpBtn);
        content.add(searchRow, "growx");

        searchBtn.addActionListener(e -> doSearch());
        searchField.addActionListener(e -> doSearch());

        // ── Results table ─────────────────────────────────────────────────────
        table = new JTable(tableModel);
        table.setRowHeight(26);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);

        table.getColumnModel().getColumn(1).setMinWidth(90);
        table.getColumnModel().getColumn(1).setMaxWidth(90);
        table.getColumnModel().getColumn(2).setMinWidth(80);
        table.getColumnModel().getColumn(2).setMaxWidth(80);
        table.getColumnModel().getColumn(2).setCellRenderer(right);

        table.getSelectionModel().addListSelectionListener(
                e -> installBtn.setEnabled(table.getSelectedRow() >= 0));

        content.add(new JScrollPane(table), "grow");

        // ── Progress bar ──────────────────────────────────────────────────────
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        content.add(progressBar, "growx");

        // ── Button bar ────────────────────────────────────────────────────────
        installBtn.setEnabled(false);
        installBtn.addActionListener(e -> doInstall());

        JButton cancelBtn = new JButton(Messages.get("plugins.download.cancel"));
        cancelBtn.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new MigLayout("insets 0", "push[][]"));
        buttons.add(installBtn);
        buttons.add(cancelBtn);
        content.add(buttons, "growx");

        setContentPane(content);
        getRootPane().setDefaultButton(searchBtn);
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void showSearchHelp() {
        JLabel label = new JLabel(
            "<html><body style='font-family:monospace; width:320px'>"
            + "<b>" + Messages.get("plugins.download.search.help.title") + "</b><br><br>"
            + "<table cellpadding='4' cellspacing='0' border='0'>"
            + "<tr><td><b>g:</b></td><td>groupId &mdash; e.g. <tt>g:com.example</tt></td></tr>"
            + "<tr><td><b>a:</b></td><td>artifactId &mdash; e.g. <tt>a:my-plugin</tt></td></tr>"
            + "<tr><td><b>v:</b></td><td>version &mdash; e.g. <tt>v:1.0.0</tt></td></tr>"
            + "<tr><td><b>p:</b></td><td>packaging &mdash; e.g. <tt>p:jar</tt></td></tr>"
            + "<tr><td><b>c:</b></td><td>classifier</td></tr>"
            + "<tr><td><b>fc:</b></td><td>fully qualified class name</td></tr>"
            + "</table><br>"
            + "<i>" + Messages.get("plugins.download.search.help.note") + "</i>"
            + "</body></html>"
        );
        JOptionPane.showMessageDialog(this, label,
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
                        String msg = authWarning
                                ? Messages.get("plugins.download.search.no.results") + "\n\n"
                                  + Messages.get("plugins.download.search.no.results.auth.hint")
                                : Messages.get("plugins.download.search.no.results");
                        JOptionPane.showMessageDialog(DownloadPluginDialog.this,
                                msg,
                                Messages.get("plugins.download.search.no.results.title"),
                                authWarning ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (InterruptedException | ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(DownloadPluginDialog.this,
                            cause.getMessage(),
                            Messages.get("plugins.download.search.error.title"),
                            JOptionPane.ERROR_MESSAGE);
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
                installBtn.setEnabled(true);
                searchBtn.setEnabled(true);
                try {
                    get();
                    onInstalled.run();
                    JOptionPane.showMessageDialog(DownloadPluginDialog.this,
                            Messages.get("plugins.download.install.success")
                                    .formatted(asset.artifactId() + " " + asset.version()),
                            Messages.get("plugins.download.install.success.title"),
                            JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                } catch (InterruptedException | ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(DownloadPluginDialog.this,
                            cause.getMessage(),
                            Messages.get("plugins.download.install.error.title"),
                            JOptionPane.ERROR_MESSAGE);
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
                case 2 -> a.sizeBytes() > 0 ? formatBytes(a.sizeBytes()) : "\u2014";
                default -> null;
            };
        }

        private static String formatBytes(long bytes) {
            if (bytes < 1024L)        return bytes + " B";
            if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
    }
}
