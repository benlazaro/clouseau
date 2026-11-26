package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.PluginCatalog;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.RowSorter.SortKey;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Plugin Manager's Browse tab (G-15, D-32). It opens on the Clouseau plugins the selected
 * repository has (the plugin list for Maven Central, plugins found by name on Nexus); typing
 * filters them, and Enter searches the whole repository for anything else. Plugins already in
 * the plugins folder show their version, and Install becomes Update when a newer one is out.
 * <p>
 * Listing, searching and downloading run on SwingWorker threads; all UI updates happen on the
 * EDT. Results and problems show under the table rather than in message boxes.
 */
@Slf4j
final class BrowsePluginsPanel extends JPanel {

    /** Puts a downloaded plugin in the plugins folder. Called on the EDT. */
    interface Installer {
        /**
         * @param downloaded the JAR, in a temporary folder of its own
         * @param replaces   the installed file it updates, or null for a new plugin
         * @return the installed file
         */
        Path install(Path downloaded, Path replaces) throws IOException;
    }

    private final Path      pluginsDir;
    private final Installer installer;

    private final DefaultComboBoxModel<PluginRepository> repoModel = new DefaultComboBoxModel<>();
    private final JComboBox<PluginRepository>            repoCombo = new JComboBox<>(repoModel);
    private final JTextField    searchField = new JTextField(24);
    private final JButton       searchBtn   = new JButton(Messages.get("plugins.download.search"));
    private final JButton       installBtn  = new JButton(Messages.get("plugins.download.install"));
    private final JProgressBar  progressBar = new JProgressBar();
    private final JLabel        status      = new JLabel(" ");
    private final ResultsModel  tableModel  = new ResultsModel();
    private final JTable        table       = new JTable(tableModel);
    private SortCycle.TableSorter<ResultsModel> sorter;

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
    /** The running list or search, or null; a cancelled or replaced one's results are dropped. */
    private SwingWorker<List<PluginRepository.Asset>, Void> searchWorker;
    private long searchStarted;

    /** Each repository's plugins, by its name, once listed; Refresh-free: reopening the Plugin Manager lists again. */
    private final Map<String, List<PluginRepository.Asset>> listed = new HashMap<>();
    /** True while the table shows a repository's plugins (typing filters them); false for a search's results. */
    private boolean showingList;
    /** The plugins folder's file names, for the Installed column. */
    private List<String> pluginFiles = List.of();
    private boolean rebuildingRepos;

    BrowsePluginsPanel(Path pluginsDir, Installer installer) {
        super(new MigLayout("fill, insets 16 16 12 16, wrap 1", "[grow]", "[]8[]8[grow]6[]"));
        this.pluginsDir = pluginsDir;
        this.installer  = installer;
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

    /** The tab was selected: the first time, list the repository's plugins. */
    void shown() {
        if (!showingList && tableModel.getRowCount() == 0 && searchWorker == null) showList();
    }

    /**
     * Fills the repository list (at the start, and after Manage…) and picks which one is
     * selected ({@link #pickRepository}). A repository just added (or renamed) is selected and
     * remembered, so it can be searched at once.
     */
    private void rebuildRepos() {
        rebuildingRepos = true;
        try {
            PluginRepository selected = (PluginRepository) repoCombo.getSelectedItem();
            List<String> before = new ArrayList<>();
            for (int i = 0; i < repoModel.getSize(); i++) before.add(repoModel.getElementAt(i).getName());
            repoModel.removeAllElements();
            PluginRepository.configured().forEach(repoModel::addElement);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < repoModel.getSize(); i++) names.add(repoModel.getElementAt(i).getName());

            String added = null;
            if (!before.isEmpty())
                for (String n : names) if (!before.contains(n)) added = n;     // the last new one
            String pick = pickRepository(names, added != null ? added
                    : selected != null ? selected.getName() : AppPrefs.getPluginBrowseRepository());
            repoCombo.setSelectedIndex(names.indexOf(pick));
            if (added != null) AppPrefs.setPluginBrowseRepository(added);
        } finally {
            rebuildingRepos = false;
        }
        listed.clear();      // a repository's address or sign-in may have changed
    }

    /**
     * Which repository Browse shows, from {@code names} in the list's order (Maven Central,
     * then each Nexus as added): {@code wanted} (the one used last) while it's there, else the
     * Nexus added last, since someone who added their own server most likely means it, else
     * the first. Null only for an empty list.
     */
    static String pickRepository(List<String> names, String wanted) {
        if (names.isEmpty()) return null;
        if (wanted != null && names.contains(wanted)) return wanted;
        return names.get(names.size() - 1);
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
        // The repository chosen is the one Browse opens on next time.
        repoCombo.addActionListener(e -> {
            if (rebuildingRepos) return;
            if (repoCombo.getSelectedItem() instanceof PluginRepository r) AppPrefs.setPluginBrowseRepository(r.getName());
            if (isShowing()) showList();
        });

        JButton manageBtn = new JButton(Messages.get("plugins.download.manage.repos"));
        manageBtn.addActionListener(e -> {
            new PluginRepoManagerDialog(SwingUtilities.getWindowAncestor(this), this::rebuildRepos).setVisible(true);
            showList();
        });

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
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchBtn.setToolTipText(Messages.get("plugins.download.search.tooltip"));

        JPanel searchRow = new JPanel(new MigLayout("insets 0, gapx 6", "[grow][][]"));
        searchRow.add(searchField, "growx");
        searchRow.add(searchBtn);
        searchRow.add(helpBtn);
        add(searchRow, "growx");

        // While a search runs the button is Cancel; it keeps the wider label's width so it doesn't jump.
        searchBtn.addActionListener(e -> { if (searchWorker != null) cancelSearch(); else doSearch(); });
        searchField.addActionListener(e -> doSearch());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { typed(); }
            @Override public void removeUpdate(DocumentEvent e)  { typed(); }
            @Override public void changedUpdate(DocumentEvent e) { typed(); }
        });
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
        ((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer())
                .setHorizontalAlignment(SwingConstants.LEADING);
        // Every column can be dragged wider or narrower; Plugin takes what's left at first.
        // The widths the user leaves are saved, and the next Plugin Manager starts with them.
        table.getTableHeader().setResizingAllowed(true);
        table.getTableHeader().setReorderingAllowed(false);
        int[] start = { 240, 100, 80, 130, 90, 70 };
        int[] least = { 120,  50, 50,  60, 60, 50 };
        int[] savedWidths = parseWidths(AppPrefs.getPluginSearchWidths());
        for (int c = 0; c < start.length; c++) {
            table.getColumnModel().getColumn(c).setMinWidth(least[c]);
            table.getColumnModel().getColumn(c).setPreferredWidth(Math.max(least[c], savedWidths[c] > 0 ? savedWidths[c] : start[c]));
        }
        // Saved when a press on the header ends with other widths, which only a drag does: not on
        // every width change, since resizing the window changes them too.
        table.getTableHeader().addMouseListener(new java.awt.event.MouseAdapter() {
            private int[] before;
            @Override public void mousePressed(java.awt.event.MouseEvent e) { before = columnWidths(); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                int[] after = columnWidths();
                if (before != null && !java.util.Arrays.equals(before, after))
                    AppPrefs.setPluginSearchWidths(widthsText(after));
                before = null;
            }
        });
        // A plugin from the list has its coordinates and description in its tooltip.
        table.getColumnModel().getColumn(ResultsModel.COL_PLUGIN).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                PluginRepository.Asset a = tableModel.getAsset(t.convertRowIndexToModel(row));
                setToolTipText(a.listing() == null ? null : tooltip(a));
                return this;
            }
        });
        // The installed version; when the repository has a newer one, it says so in the accent color.
        table.getColumnModel().getColumn(ResultsModel.COL_INSTALLED).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                PluginRepository.Asset a = tableModel.getAsset(t.convertRowIndexToModel(row));
                String installed = (String) v;
                boolean older = installed != null && compareVersions(installed, a.version()) < 0;
                setText(installed == null ? "" : older
                        ? MessageFormat.format(Messages.get("plugins.download.installed.older"), installed) : installed);
                if (!sel) setForeground(older ? ClouseauColors.accentBlue() : t.getForeground());
                return this;
            }
        });
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

        // Clicking a header sorts by it: ascending, descending, then unsorted (SortCycle).
        // Versions sort as versions; a result without a date or a size sorts before those with one.
        sorter = new SortCycle.TableSorter<>(tableModel);
        sorter.setComparator(ResultsModel.COL_PLUGIN, String.CASE_INSENSITIVE_ORDER);
        sorter.setComparator(ResultsModel.COL_VERSION, (a, b) -> compareVersions((String) a, (String) b));
        sorter.setComparator(ResultsModel.COL_INSTALLED, (a, b) -> compareVersions((String) a, (String) b));
        table.setRowSorter(sorter);
        // The sort lasts between searches anyway (the sorter keeps it when the rows change);
        // saving it keeps it after the Plugin Manager closes, and after a restart.
        SortKey saved = parseSort(AppPrefs.getPluginSearchSort());
        if (saved != null) sorter.setSortKeys(List.of(saved));
        sorter.addRowSorterListener(e -> {
            if (e.getType() == javax.swing.event.RowSorterEvent.Type.SORT_ORDER_CHANGED)
                AppPrefs.setPluginSearchSort(sortText(sorter.getSortKeys()));
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) selectionChanged();
        });
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
        // Install, Update and Installed take turns on the button; it keeps the widest one's width.
        int widest = 0;
        for (String key : new String[]{ "plugins.download.install", "plugins.download.update", "plugins.download.installed" }) {
            installBtn.setText(Messages.get(key));
            widest = Math.max(widest, installBtn.getPreferredSize().width);
        }
        installBtn.setText(Messages.get("plugins.download.install"));
        installBtn.setPreferredSize(new Dimension(widest, installBtn.getPreferredSize().height));

        JPanel bottom = new JPanel(new MigLayout("insets 0, hidemode 3, gapx 8", "[grow][]"));
        bottom.add(status, "growx, wmin 0");
        bottom.add(progressBar, "growx");
        bottom.add(installBtn);
        add(bottom, "growx");
    }

    private void say(String text, boolean problem) {
        status.setText(text == null || text.isBlank() ? " " : text);
        status.setForeground(problem ? ClouseauColors.levelColor(com.droidenx.clouseau.api.LogEntry.LogLevel.ERROR)
                                     : ClouseauColors.dimForeground());
    }

    private static String tooltip(PluginRepository.Asset a) {
        String desc = a.listing() != null ? a.listing().description() : "";
        return "<html>" + escape(a.coordinates()) + (desc.isEmpty() ? "" : "<br>" + escape(desc)) + "</html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void showSearchHelp() {
        // Qualifier · what it matches · an example; the qualifiers and examples are typed, so
        // they're in the code font.
        int size = getFont().getSize();
        Font mono = com.droidenx.clouseau.ui.theme.ClouseauFonts.mono(Font.PLAIN, size);
        JPanel help = new JPanel(new MigLayout("insets 0, gapx 14, gapy 6", "[][][]"));
        JLabel intro = new JLabel(Messages.get("plugins.download.search.help.intro"));
        help.add(intro, "span 3, wrap, gapbottom 6");
        for (String q : new String[]{ "g", "a" }) {
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

    /** Typing filters the listed plugins; clearing the field after a search goes back to them. */
    private void typed() {
        if (showingList) applyFilter();
        else if (searchField.getText().isBlank() && searchWorker == null) showList();
    }

    private void applyFilter() {
        String text = searchField.getText();
        sorter.setRowFilter(text.isBlank() ? null : new RowFilter<ResultsModel, Integer>() {
            @Override public boolean include(Entry<? extends ResultsModel, ? extends Integer> e) {
                return matches(e.getModel().getAsset(e.getIdentifier()), text);
            }
        });
        if (showingList) sayListed();
    }

    /** True if every word of {@code filter} is in the plugin's name, coordinates, description or what it adds. */
    static boolean matches(PluginRepository.Asset a, String filter) {
        StringBuilder hay = new StringBuilder(a.coordinates()).append(' ').append(a.displayName());
        if (a.listing() != null) {
            hay.append(' ').append(a.listing().description());
            for (PluginCatalog.Kind k : a.listing().provides())
                hay.append(' ').append(Messages.get("plugins.kind." + k.name().toLowerCase(Locale.ROOT)));
        }
        String h = hay.toString().toLowerCase(Locale.ROOT);
        for (String word : filter.toLowerCase(Locale.ROOT).trim().split("\\s+"))
            if (!word.isEmpty() && !h.contains(word)) return false;
        return true;
    }

    /** Shows the selected repository's plugins: from memory if listed before, else from the repository. */
    private void showList() {
        PluginRepository repo = (PluginRepository) repoCombo.getSelectedItem();
        if (repo == null) return;
        List<PluginRepository.Asset> known = listed.get(repo.getName());
        if (known != null) {
            if (searchWorker != null) cancelSearch();
            pluginFiles = readPluginFiles();
            showRows(known, true);
            sayListed();
            return;
        }
        run(repo, MessageFormat.format(Messages.get("plugins.download.listing.repo"), repo.getName()),
                repo::listPlugins, true);
    }

    private void doSearch() {
        PluginRepository repo = (PluginRepository) repoCombo.getSelectedItem();
        if (repo == null) return;
        String query = searchField.getText().trim();
        if (query.isBlank()) { listed.remove(repo.getName()); showList(); return; }   // Enter on an empty field lists again
        run(repo, MessageFormat.format(Messages.get("plugins.download.searching.repo"), repo.getName()),
                () -> repo.search(query), false);
    }

    /** Runs a list or a search in the background, with the loader, and shows what it returns. */
    private void run(PluginRepository repo, String loaderText, Callable<List<PluginRepository.Asset>> job, boolean list) {
        if (searchWorker != null) cancelSearch();
        showRows(List.of(), list);
        status.setToolTipText(null);
        say(" ", false);      // the loader says what it's doing; the line is for the outcome
        startLoader(loaderText);

        searchWorker = new SwingWorker<>() {
            private List<String> files = List.of();
            @Override
            protected List<PluginRepository.Asset> doInBackground() throws Exception {
                files = readPluginFiles();
                return job.call();
            }
            @Override
            protected void done() {
                if (searchWorker != this) return;      // cancelled, or a newer one replaced it
                searchWorker = null;
                stopLoader();
                try {
                    List<PluginRepository.Asset> rows = get();
                    pluginFiles = files;
                    if (list) listed.put(repo.getName(), rows);
                    showRows(rows, list);
                    if (list) sayListed();
                    else sayFound(repo);
                } catch (InterruptedException | ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log.warn("{} failed on {}", list ? "Listing plugins" : "Search", repo.getName(), cause);
                    say(Messages.get(list ? "plugins.download.list.error.title" : "plugins.download.search.error.title")
                            + ": " + searchError(cause), true);
                }
            }
        };
        searchWorker.execute();
    }

    private void showRows(List<PluginRepository.Asset> rows, boolean list) {
        showingList = list;
        tableModel.setResults(rows);
        if (list) applyFilter();
        else sorter.setRowFilter(null);
        selectionChanged();
    }

    /** The status line under a list: how many plugins, how many the filter keeps, or that there are none. */
    private void sayListed() {
        int all = tableModel.getRowCount(), shown = table.getRowCount();
        PluginRepository repo = (PluginRepository) repoCombo.getSelectedItem();
        status.setToolTipText(null);
        if (all == 0) {
            say(MessageFormat.format(Messages.get(repo instanceof MavenCentralPluginRepository
                    ? "plugins.download.listed.none.central" : "plugins.download.listed.none.nexus"),
                    repo != null ? repo.getName() : ""), false);
        } else if (shown == all) {
            say(MessageFormat.format(Messages.get("plugins.download.listed"), all), false);
        } else {
            say(MessageFormat.format(Messages.get("plugins.download.listed.filtered"), shown, all), false);
        }
    }

    private void sayFound(PluginRepository repo) {
        if (tableModel.getRowCount() == 0) {
            boolean authWarning = repo instanceof NexusPluginRepository nexusRepo && nexusRepo.hasAuthConfigured();
            say(Messages.get("plugins.download.search.no.results")
                    + (authWarning ? " " + Messages.get("plugins.download.search.no.results.auth.short") : ""),
                    authWarning);
            if (authWarning) status.setToolTipText(
                    Messages.get("plugins.download.search.no.results.auth.hint").replace("\n", " "));
        } else {
            say(MessageFormat.format(Messages.get("plugins.download.found"), tableModel.getRowCount()), false);
            status.setToolTipText(null);
        }
    }

    /** Stops the running list or search; its results, if they still come, are dropped. */
    private void cancelSearch() {
        SwingWorker<?, ?> w = searchWorker;
        searchWorker = null;
        if (w != null) w.cancel(true);
        stopLoader();
        say(Messages.get("plugins.download.search.cancelled"), false);
    }

    private void startLoader(String text) {
        searchStarted = System.currentTimeMillis();
        loadingLabel.setText(text);
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
        selectionChanged();
    }

    private void showElapsed() {
        long ms = System.currentTimeMillis() - searchStarted;
        if (ms >= ELAPSED_AFTER_MS)
            elapsedLabel.setText(MessageFormat.format(Messages.get("plugins.download.searching.elapsed"), ms / 1000));
    }

    /** What went wrong, in words: a timed-out request says so rather than showing an empty message. */
    private static String searchError(Throwable cause) {
        if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.http.HttpConnectTimeoutException)
            return Messages.get("plugins.download.search.timeout");
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /** The selected row, or null. */
    private PluginRepository.Asset selected() {
        int view = table.getSelectedRow();
        return view < 0 ? null : tableModel.getAsset(table.convertRowIndexToModel(view));
    }

    /**
     * The button says what a click does: Install, Update (the folder has an older version), or
     * Installed, disabled (it has this version or a newer one). A listed plugin's description
     * shows on the status line.
     */
    private void selectionChanged() {
        PluginRepository.Asset a = selected();
        String installed = a == null ? null : tableModel.installed(a);
        // Your version against the row's: a newer row updates, an older one downgrades.
        int cmp = installed == null ? 0 : compareVersions(installed, a.version());
        installBtn.setText(Messages.get(installed == null ? "plugins.download.install"
                : cmp < 0 ? "plugins.download.update"
                : cmp > 0 ? "plugins.download.downgrade" : "plugins.download.installed"));
        installBtn.setEnabled(a != null && searchWorker == null && !progressBar.isVisible() && (installed == null || cmp != 0));
        if (a != null && a.listing() != null && !a.listing().description().isEmpty()) {
            say(a.listing().description(), false);
            status.setToolTipText(null);
        } else if (a == null && showingList && searchWorker == null) {
            sayListed();
        }
    }

    /** The names of the plugins folder's files; empty if it can't be read. */
    private List<String> readPluginFiles() {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) return List.of();
        try (Stream<Path> files = Files.list(pluginsDir)) {
            return files.map(p -> p.getFileName().toString()).collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Could not list {}", pluginsDir, e);
            return List.of();
        }
    }

    /** The installed file of {@code a} at {@code version}, in the plugins folder, or null. */
    private Path installedFile(PluginRepository.Asset a, String version) {
        for (String f : pluginFiles)
            if (PluginCatalog.installedVersions(a.artifactId(), List.of(f)).contains(version)) return pluginsDir.resolve(f);
        return null;
    }

    private void doInstall() {
        PluginRepository.Asset asset = selected();
        if (asset == null) return;
        PluginRepository repo = (PluginRepository) repoCombo.getSelectedItem();
        String installed = tableModel.installed(asset);
        Path replaces = installed != null ? installedFile(asset, installed) : null;
        // Going back to an older version is rarely meant by accident; ask first.
        if (installed != null && compareVersions(installed, asset.version()) > 0
                && !ManagerDialogs.confirm(SwingUtilities.getWindowAncestor(this),
                        Messages.get("plugins.download.downgrade.title"),
                        ConfirmText.of(MessageFormat.format(Messages.get("plugins.download.downgrade.question"),
                                        asset.displayName(), installed, asset.version()),
                                Messages.get("plugins.download.downgrade.note")),
                        Messages.get("plugins.download.downgrade"))) return;

        installBtn.setEnabled(false);
        searchBtn.setEnabled(false);
        progressBar.setValue(0);
        progressBar.setIndeterminate(asset.sizeBytes() <= 0);
        progressBar.setString("");
        progressBar.setVisible(true);
        status.setVisible(false);

        new SwingWorker<Path, Long>() {
            /** The download lands in a folder of its own, so the plugins folder never sees half a JAR. */
            private Path tempDir;
            @Override
            protected Path doInBackground() throws Exception {
                tempDir = Files.createTempDirectory("clouseau-plugin");
                return repo.download(asset, tempDir, bytes -> publish(bytes));
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
                searchBtn.setEnabled(true);
                try {
                    Path file = installer.install(get(), replaces);
                    // Where it came from, so the update check knows where to look (D-33).
                    if (!asset.groupId().isBlank())
                        AppPrefs.setPluginOrigin(asset.artifactId(), new AppPrefs.PluginOrigin(repo.getName(), asset.groupId()));
                    pluginFiles = readPluginFiles();
                    tableModel.fireTableDataChanged();
                    say(" ", false);
                    log.info("Installed {} {} as {}", asset.coordinates(), asset.version(), file);
                } catch (InterruptedException | ExecutionException | IOException ex) {
                    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
                    log.warn("Installing {} failed", asset.coordinates(), cause);
                    say(Messages.get("plugins.download.install.error.title") + ": " + cause.getMessage(), true);
                } finally {
                    deleteQuietly(tempDir);
                    selectionChanged();
                }
            }
        }.execute();
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { /* temp: the OS cleans it */ }
            });
        } catch (IOException ignored) { /* temp: the OS cleans it */ }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L)        return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ── Results table model ───────────────────────────────────────────────────

    private final class ResultsModel extends AbstractTableModel {

        static final int COL_PLUGIN = 0, COL_ADDS = 1, COL_VERSION = 2, COL_INSTALLED = 3, COL_DATE = 4, COL_SIZE = 5;

        private final String[] cols = {
            Messages.get("plugins.download.col.artifact"),
            Messages.get("plugins.download.col.adds"),
            Messages.get("plugins.download.col.version"),
            Messages.get("plugins.download.col.installed"),
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

        /** The newest version of {@code a} in the plugins folder, or null. */
        String installed(PluginRepository.Asset a) {
            return PluginCatalog.installedVersions(a.artifactId(), pluginFiles).stream()
                    .max(BrowsePluginsPanel::compareVersions).orElse(null);
        }

        @Override public int    getRowCount()              { return rows.size(); }
        @Override public int    getColumnCount()           { return cols.length; }
        @Override public String getColumnName(int col)     { return cols[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            PluginRepository.Asset a = rows.get(row);
            return switch (col) {
                case COL_PLUGIN    -> a.listing() != null ? a.listing().name() : a.coordinates();
                case COL_ADDS      -> a.listing() == null ? "" : a.listing().provides().stream()
                        .map(k -> Messages.get("plugins.kind." + k.name().toLowerCase(Locale.ROOT)))
                        .collect(Collectors.joining(", "));
                case COL_VERSION   -> a.version();
                case COL_INSTALLED -> installed(a);                                 // null if not installed
                case COL_DATE      -> a.published();                              // null if unknown
                case COL_SIZE      -> a.sizeBytes() > 0 ? a.sizeBytes() : null;   // null if unknown
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

    /** The columns' names in the saved sort and widths, by index; saved by name so a new column can't shift them. */
    private static final String[] COLUMN_NAMES = { "artifact", "adds", "version", "installed", "date", "size" };

    /** The first sort key as {@code column:order}, such as {@code date:descending}; null when unsorted. */
    static String sortText(List<? extends SortKey> keys) {
        if (keys.isEmpty()) return null;
        SortKey k = keys.get(0);
        if (k.getColumn() < 0 || k.getColumn() >= COLUMN_NAMES.length || k.getSortOrder() == SortOrder.UNSORTED) return null;
        return COLUMN_NAMES[k.getColumn()] + ":" + k.getSortOrder().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The table's column widths now, by model column (columns can't be moved here). */
    private int[] columnWidths() {
        int[] widths = new int[table.getColumnCount()];
        for (int c = 0; c < widths.length; c++) widths[c] = table.getColumnModel().getColumn(c).getWidth();
        return widths;
    }

    /** Each column's width by name, such as {@code artifact:240,adds:100}, in column order. */
    static String widthsText(int[] widths) {
        StringBuilder out = new StringBuilder();
        for (int c = 0; c < Math.min(widths.length, COLUMN_NAMES.length); c++) {
            if (widths[c] <= 0) continue;
            if (!out.isEmpty()) out.append(',');
            out.append(COLUMN_NAMES[c]).append(':').append(widths[c]);
        }
        return out.isEmpty() ? null : out.toString();
    }

    /**
     * The widths {@link #widthsText} wrote, one per column, 0 for a column it doesn't name
     * (one added since, or a damaged value): that column keeps its usual width.
     */
    static int[] parseWidths(String text) {
        int[] widths = new int[COLUMN_NAMES.length];
        if (text == null) return widths;
        for (String part : text.split(",")) {
            String[] p = part.trim().split(":");
            if (p.length != 2) continue;
            int col = java.util.Arrays.asList(COLUMN_NAMES).indexOf(p[0].trim());
            try {
                int w = Integer.parseInt(p[1].trim());
                if (col >= 0 && w > 0) widths[col] = w;
            } catch (NumberFormatException ignored) {
                // that column keeps its usual width
            }
        }
        return widths;
    }

    /** The sort key {@link #sortText} wrote, or null for anything else (an old or hand-edited value). */
    static SortKey parseSort(String text) {
        if (text == null) return null;
        String[] p = text.trim().split(":");
        if (p.length != 2) return null;
        int col = java.util.Arrays.asList(COLUMN_NAMES).indexOf(p[0]);
        SortOrder order = switch (p[1]) {
            case "ascending"  -> SortOrder.ASCENDING;
            case "descending" -> SortOrder.DESCENDING;
            default -> null;
        };
        return col >= 0 && order != null ? new SortKey(col, order) : null;
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
