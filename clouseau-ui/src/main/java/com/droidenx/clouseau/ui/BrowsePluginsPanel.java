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

    /** How long a search runs before the loader replaces the results, so quick ones don't flicker it. */
    static final int LOADER_DELAY_MS = 250;
    /** How long a search runs before the loader also counts the seconds. */
    static final int ELAPSED_AFTER_MS = 3000;

    private static final String CARD_RESULTS = "results", CARD_LOADING = "loading";
    private final CardLayout   resultCards   = new CardLayout();
    private final JPanel       resultArea    = new JPanel(resultCards);
    private final JLabel       loadingLabel  = new JLabel(" ");
    private final JLabel       elapsedLabel  = new JLabel(" ");
    private final JProgressBar loadingBar    = new JProgressBar();
    private final Timer        loaderDelay   = new Timer(LOADER_DELAY_MS, e -> resultCards.show(resultArea, CARD_LOADING));
    private final Timer        elapsedTick   = new Timer(1000, e -> showElapsed());
    /** The running search, or null; a cancelled or replaced search's results are dropped. */
    private SwingWorker<List<PluginRepository.Asset>, Void> searchWorker;
    private long searchStarted;

    /** @param onInstalled called on the EDT with the installed file */
    BrowsePluginsPanel(Path pluginsDir, Consumer<Path> onInstalled) {
        super(new MigLayout("fill, insets 16 16 12 16, wrap 1", "[grow]", "[]8[]8[grow]6[]"));
        this.pluginsDir  = pluginsDir;
        this.onInstalled = onInstalled;
        rebuildRepos();
        buildUI();
    }

    /** Closing the Plugin Manager stops a search that's still waiting. */
    @Override
    public void removeNotify() {
        if (searchWorker != null) cancelSearch();
        super.removeNotify();
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

        // While a search runs the button is Cancel; it keeps the wider label's width so it doesn't jump.
        searchBtn.addActionListener(e -> { if (searchWorker != null) cancelSearch(); else doSearch(); });
        searchField.addActionListener(e -> doSearch());
        searchBtn.setText(Messages.get("plugins.download.search.cancel"));
        int cancelWidth = searchBtn.getPreferredSize().width;
        searchBtn.setText(Messages.get("plugins.download.search"));
        searchBtn.setPreferredSize(new Dimension(Math.max(cancelWidth, searchBtn.getPreferredSize().width),
                searchBtn.getPreferredSize().height));
        loaderDelay.setRepeats(false);

        // ── Results table ─────────────────────────────────────────────────────
        table.setRowHeight(26);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        DefaultTableCellRenderer left = new DefaultTableCellRenderer();
        left.setHorizontalAlignment(SwingConstants.LEADING);
        ((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer())
                .setHorizontalAlignment(SwingConstants.LEADING);
        // Every column can be dragged wider or narrower; Artifact takes what's left at first.
        table.getTableHeader().setResizingAllowed(true);
        table.getTableHeader().setReorderingAllowed(false);
        int[] start = { 300, 110, 90, 80 };
        int[] least = { 120,  50, 60, 50 };
        for (int c = 0; c < start.length; c++) {
            table.getColumnModel().getColumn(c).setMinWidth(least[c]);
            table.getColumnModel().getColumn(c).setPreferredWidth(start[c]);
        }
        // The model gives dates and sizes as values, so they sort as such; these show them.
        table.getColumnModel().getColumn(ResultsModel.COL_SIZE).setCellRenderer(new DefaultTableCellRenderer() {
            { setHorizontalAlignment(SwingConstants.TRAILING); }
            @Override protected void setValue(Object v) {
                setText(v instanceof Long n ? formatBytes(n) : "—");
            }
        });
        table.getColumnModel().getColumn(ResultsModel.COL_DATE).setCellRenderer(new DefaultTableCellRenderer() {
            @Override protected void setValue(Object v) {
                setText(v instanceof java.time.Instant t ? ResultsModel.DAY.format(t) : "—");
            }
        });

        // Clicking a header sorts by it (again reverses). Versions sort as versions; a result
        // without a date or a size sorts before those with one.
        javax.swing.table.TableRowSorter<ResultsModel> sorter = new javax.swing.table.TableRowSorter<>(tableModel);
        sorter.setComparator(ResultsModel.COL_ARTIFACT, String.CASE_INSENSITIVE_ORDER);
        sorter.setComparator(ResultsModel.COL_VERSION, (a, b) -> compareVersions((String) a, (String) b));
        table.setRowSorter(sorter);
        table.getSelectionModel().addListSelectionListener(
                e -> installBtn.setEnabled(table.getSelectedRow() >= 0));
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0 && installBtn.isEnabled()) doInstall();
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.putClientProperty("FlatLaf.style", ClouseauColors.NO_FOCUS_FRAME);

        // The loader that takes the results' place while a search waits on the repository.
        loadingBar.setIndeterminate(true);
        elapsedLabel.setFont(elapsedLabel.getFont().deriveFont(11f));
        ClouseauColors.themedForeground(elapsedLabel, ClouseauColors::dimForeground);
        JPanel loading = new JPanel(new MigLayout("insets 0, wrap 1, align center center, gapy 8", "[center]"));
        loading.add(loadingLabel);
        loading.add(loadingBar, "w 220!, h 4!");
        loading.add(elapsedLabel);
        JScrollPane loadingFrame = new JScrollPane(loading);
        loadingFrame.putClientProperty("FlatLaf.style", ClouseauColors.NO_FOCUS_FRAME);
        loading.setBackground(table.getBackground());
        loadingFrame.getViewport().setBackground(table.getBackground());

        resultArea.add(scroll, CARD_RESULTS);
        resultArea.add(loadingFrame, CARD_LOADING);
        add(resultArea, "grow");

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

        if (searchWorker != null) cancelSearch();
        tableModel.setResults(List.of());
        status.setToolTipText(null);
        say(" ", false);      // the loader says it's searching; the line is for the outcome
        startLoader(repo.getName());

        searchWorker = new SwingWorker<List<PluginRepository.Asset>, Void>() {
            @Override
            protected List<PluginRepository.Asset> doInBackground() throws Exception {
                return repo.search(query);
            }
            @Override
            protected void done() {
                if (searchWorker != this) return;      // cancelled, or a newer search replaced it
                searchWorker = null;
                stopLoader();
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
                    say(Messages.get("plugins.download.search.error.title") + ": " + searchError(cause), true);
                }
            }
        };
        searchWorker.execute();
    }

    /** Stops the running search; its results, if they still come, are dropped. */
    private void cancelSearch() {
        SwingWorker<?, ?> w = searchWorker;
        searchWorker = null;
        if (w != null) w.cancel(true);
        stopLoader();
        say(Messages.get("plugins.download.search.cancelled"), false);
    }

    private void startLoader(String repoName) {
        searchStarted = System.currentTimeMillis();
        loadingLabel.setText(java.text.MessageFormat.format(Messages.get("plugins.download.searching.repo"), repoName));
        elapsedLabel.setText(" ");
        searchBtn.setText(Messages.get("plugins.download.search.cancel"));
        installBtn.setEnabled(false);
        loaderDelay.restart();
        elapsedTick.restart();
    }

    private void stopLoader() {
        loaderDelay.stop();
        elapsedTick.stop();
        resultCards.show(resultArea, CARD_RESULTS);
        searchBtn.setText(Messages.get("plugins.download.search"));
        installBtn.setEnabled(table.getSelectedRow() >= 0);
    }

    private void showElapsed() {
        long ms = System.currentTimeMillis() - searchStarted;
        if (ms >= ELAPSED_AFTER_MS)
            elapsedLabel.setText(java.text.MessageFormat.format(Messages.get("plugins.download.searching.elapsed"), ms / 1000));
    }

    /** What went wrong, in words: a timed-out request says so rather than showing an empty message. */
    private static String searchError(Throwable cause) {
        if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.http.HttpConnectTimeoutException)
            return Messages.get("plugins.download.search.timeout");
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private void doInstall() {
        int view = table.getSelectedRow();
        if (view < 0) return;
        int row = table.convertRowIndexToModel(view);      // the table may be sorted
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

        static final int COL_ARTIFACT = 0, COL_VERSION = 1, COL_DATE = 2, COL_SIZE = 3;

        private static final String[] COLS = {
            Messages.get("plugins.download.col.artifact"),
            Messages.get("plugins.download.col.version"),
            Messages.get("plugins.download.col.date"),
            Messages.get("plugins.download.col.size"),
        };

        /** The day the repository got the artifact, in this computer's timezone. */
        static final java.time.format.DateTimeFormatter DAY =
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneId.systemDefault());

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
                case COL_ARTIFACT -> a.groupId().isBlank() ? a.artifactId()
                                                           : a.groupId() + ":" + a.artifactId();
                case COL_VERSION  -> a.version();
                case COL_DATE     -> a.published();                              // null if unknown
                case COL_SIZE     -> a.sizeBytes() > 0 ? a.sizeBytes() : null;   // null if unknown
                default -> null;
            };
        }

        @Override
        public Class<?> getColumnClass(int col) {
            return switch (col) {
                case COL_DATE -> java.time.Instant.class;
                case COL_SIZE -> Long.class;
                default       -> String.class;
            };
        }
    }

    /**
     * Orders two versions: as Semantic Versioning does when both are ({@code 1.9.0} before
     * {@code 1.10.0}, {@code 2.0.0-rc.1} before {@code 2.0.0}); otherwise part by part, with
     * runs of digits compared as numbers ({@code 1.0.9.Final} before {@code 1.0.10.Final}).
     */
    static int compareVersions(String a, String b) {
        var va = com.droidenx.clouseau.core.UpdateCheck.Version.parse(a);
        var vb = com.droidenx.clouseau.core.UpdateCheck.Version.parse(b);
        if (va != null && vb != null) {
            int c = va.compareTo(vb);
            if (c != 0) return c;
        }
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                int c = new java.math.BigInteger(a.substring(si, i)).compareTo(new java.math.BigInteger(b.substring(sj, j)));
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
