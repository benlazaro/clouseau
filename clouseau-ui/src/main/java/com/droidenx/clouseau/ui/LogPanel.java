package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.core.LogIndex;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Self-contained panel representing one open log file tab.
 * Owns its own LogIndex, LogTableModel, table and detail pane.
 */
@Slf4j
public final class LogPanel extends JPanel {

    private final List<LogParser> parsers;
    private final List<LogFormatter> formatters;
    private final List<LogSyntaxHighlighter> syntaxHighlighters;
    private final LogIndex        logIndex      = new LogIndex();
    private final LogTableModel   logTableModel = new LogTableModel();
    private final FilterBar       filterBar;
    private JTable logTable;
    private JTextPane detailArea;
    private boolean tableColumnsManaged = false;
    private int fittedColumnsWidth = 0;
    /** model-column-index → hidden TableColumn, in insertion order for restore positioning */
    private final Map<Integer, TableColumn> hiddenColumns = new LinkedHashMap<>();
    /** Name of the layout most recently applied via applyColumnLayout; null if none or modified since. */
    private String activeLayoutName = null;
    private JScrollPane tableScrollPane;
    private volatile SwingWorker<?, ?> currentWorker;
    private volatile SwingWorker<Void, LogEntry> tailWorker;
    private volatile SshLogSource sshSource;
    private Path currentFile;
    private Optional<LogParser> currentParser = Optional.empty();
    private volatile long tailPosition = 0;
    private boolean follow  = AppPrefs.isFollowByDefault();
    private boolean loading = false;
    private final java.util.Set<String> disabledFormatters = new java.util.HashSet<>();
    private JPanel syntaxHighlightPanel;
    private final List<JToggleButton> formatterBtnList       = new ArrayList<>();
    private final List<JToggleButton> syntaxHighlightBtnList = new ArrayList<>();
    private JPanel formatterPanel;
    private Set<Integer>  searchMatchModelRows = Set.of();
    private List<Integer> searchMatchList      = List.of();
    private int           searchMatchCursor    = -1;
    private JPanel        findBar;
    private JTextField    findField;
    private JLabel        findCounter;
    private JButton       copyBtn;
    private String        lastFormattedMessage = "";
    private int           messageFieldStart    = -1;
    private int           messageFieldEnd      = -1;
    private boolean       wrapLines            = AppPrefs.isDetailWrapLines();
    private JPanel centerCards;
    private static final String CARD_CONTENT = "content";
    private static final String CARD_LOADING = "loading";
    private JPanel statusBar;
    private JLabel statusBarLabel;
    private JLabel statusBarPathLabel;
    private JPanel highlightNavBar;
    private JPanel highlightNavSwatches;
    private JLabel highlightNavPosition;
    private Color  highlightNavColor = null;

    public LogPanel(List<LogParser> parsers, List<LogFormatter> formatters, List<LogSyntaxHighlighter> syntaxHighlighters) {
        super(new MigLayout("fill, insets 0, gapy 0", "[grow]", ""));
        this.parsers    = parsers;
        this.formatters = formatters;
        this.syntaxHighlighters = syntaxHighlighters;
        FilterBar[] fbHolder = new FilterBar[1];
        fbHolder[0] = new FilterBar(() -> logTableModel.applyFilter(fbHolder[0].buildPredicate()));
        this.filterBar = fbHolder[0];
        filterBar.initFollow(follow, this::setFollow);
        logTableModel.applyFilter(filterBar.buildPredicate());

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildLogTable(), buildDetail());
        split.setResizeWeight(0.70);
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                split.removeComponentListener(this);
                split.setDividerLocation(0.70);
            }
        });

        centerCards = new JPanel(new CardLayout());
        centerCards.add(split, CARD_CONTENT);
        centerCards.add(buildLoadingPanel(), CARD_LOADING);

        statusBar = buildStatusBar();
        findBar = buildFindBar();

        add(filterBar, "growx, wrap");
        add(findBar, "growx, wrap, hidemode 3");
        add(buildHighlightNavBar(), "growx, wrap, hidemode 3");
        add(centerCards, "grow, pushy, wrap");
        add(statusBar, "growx");

        logTableModel.addTableModelListener(e -> {
            if (follow && !loading && e.getType() == TableModelEvent.INSERT)
                SwingUtilities.invokeLater(this::scrollToBottom);
            updateStatusBar();
        });

        logTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int viewRow = logTable.getSelectedRow();
                int modelRow = viewRow >= 0 ? logTable.convertRowIndexToModel(viewRow) : -1;
                showDetail(modelRow >= 0 ? logTableModel.getEntry(modelRow) : null, modelRow);
                updateHighlightNavPosition();
                if (copyBtn != null) copyBtn.setEnabled(modelRow >= 0);
            }
        });

        // Ctrl+F: show find bar and focus the field
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "showFind");
        getActionMap().put("showFind", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                findBar.setVisible(true);
                findField.selectAll();
                findField.requestFocusInWindow();
            }
        });
    }

    public boolean isFollow() { return follow; }

    public void setFollow(boolean follow) {
        this.follow = follow;
        filterBar.setFollowSelected(follow);
        if (follow) {
            startTailing();
        } else {
            stopTailing();
        }
    }

    private void scrollToTop() {
        if (logTable.getRowCount() > 0) logTable.scrollRectToVisible(logTable.getCellRect(0, 0, true));
    }

    private void scrollToBottom() {
        int last = logTable.getRowCount() - 1;
        if (last >= 0) logTable.scrollRectToVisible(logTable.getCellRect(last, 0, true));
    }

    // ── File loading ─────────────────────────────────────────────────────────

    public Path getFile() { return currentFile; }

    public void load(Path file, Optional<LogParser> parser) {
        load(file, parser, () -> {});
    }

    public void load(Path file, Optional<LogParser> parser, Runnable onError) {
        currentFile = file.toAbsolutePath();
        currentParser = parser;
        statusBarPathLabel.setText(currentFile.toString());
        statusBarPathLabel.setToolTipText(currentFile.toString());
        stopTailing();
        SwingWorker<?, ?> old = currentWorker;
        if (old != null) old.cancel(true);
        loading = true;
        logIndex.clear();
        logTableModel.clear();
        filterBar.clearLoggers();
        hiddenColumns.clear();
        activeLayoutName = null;
        logTableModel.setCustomFields(parser.map(com.droidenx.clouseau.api.LogParser::customFields).orElse(List.of()));
        logTableModel.beginBatchLoad();

        ((CardLayout) centerCards.getLayout()).show(centerCards, CARD_LOADING);

        SwingWorker<Void, LogEntry> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (BufferedReader reader = openReader(file)) {
                    String line;
                    while ((line = reader.readLine()) != null && !isCancelled()) {
                        if (line.isBlank()) continue;
                        final String candidate = line;
                        LogEntry entry = parser.map(Stream::of)
                              .orElseGet(parsers::stream)
                              .filter(p -> p.canParse(candidate))
                              .findFirst()
                              .map(p -> p.parse(candidate))
                              .orElseGet(() -> new LogEntry(
                                      null, LogEntry.LogLevel.UNKNOWN,
                                      null, null,
                                      candidate, candidate, Map.of()));
                        publish(entry);
                    }
                }
                return null;
            }

            @Override
            protected void process(List<LogEntry> chunk) {
                if (isCancelled()) return;
                logTableModel.appendBatch(chunk);
                filterBar.addBatch(chunk);
            }

            @Override
            protected void done() {
                loading = false;
                logTableModel.endBatchLoad();
                ((CardLayout) centerCards.getLayout()).show(centerCards, CARD_CONTENT);
                if (isCancelled()) return;
                try {
                    get();
                    autoResizeColumns();
                    applyDefaultLayoutIfSet();
                    try { tailPosition = Files.size(currentFile); } catch (IOException ignored) {}
                    if (follow) startTailing();
                } catch (Exception ex) {
                    log.error("Failed to load {}", file, ex);
                    onError.run();
                    JOptionPane.showMessageDialog(
                            SwingUtilities.getWindowAncestor(LogPanel.this),
                            ex.getMessage(),
                            Messages.get("filechooser.error.title"),
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        currentWorker = worker;
        worker.execute();
    }

    public void reload() {
        if (currentFile != null) load(currentFile, currentParser);
    }

    public void cancelLoad() {
        stopTailing();
        SwingWorker<?, ?> w = currentWorker;
        if (w != null) w.cancel(true);
        SshLogSource s = sshSource;
        if (s != null) { s.close(); sshSource = null; }
    }

    /**
     * Connects to a remote host via SSH and streams log lines using {@code tail -f}.
     * The panel immediately shows the content card; rows appear as they arrive.
     */
    public void loadSsh(SshConfig config, Optional<LogParser> parser) {
        currentFile   = null;
        currentParser = parser;
        statusBarPathLabel.setText(config.displayName());
        statusBarPathLabel.setToolTipText(config.displayName());
        cancelLoad();   // closes any prior SSH source, cancels in-flight workers

        logIndex.clear();
        logTableModel.clear();
        filterBar.clearLoggers();
        hiddenColumns.clear();
        activeLayoutName = null;
        logTableModel.setCustomFields(parser.map(com.droidenx.clouseau.api.LogParser::customFields).orElse(List.of()));
        logTableModel.beginBatchLoad();

        ((CardLayout) centerCards.getLayout()).show(centerCards, CARD_LOADING);

        SshLogSource source = new SshLogSource(config);
        sshSource = source;

        SwingWorker<Void, LogEntry> worker = new SwingWorker<>() {
            boolean firstBatch = true;

            @Override
            protected Void doInBackground() throws Exception {
                source.open(line -> {
                    if (line.isBlank() || isCancelled()) return;
                    final String candidate = line;
                    LogEntry entry = parser.map(Stream::of)
                            .orElseGet(parsers::stream)
                            .filter(p -> p.canParse(candidate))
                            .findFirst()
                            .map(p -> p.parse(candidate))
                            .orElseGet(() -> new LogEntry(
                                    null, LogEntry.LogLevel.UNKNOWN,
                                    null, null,
                                    candidate, candidate, Map.of()));
                    publish(entry);
                });
                return null;
            }

            @Override
            protected void process(List<LogEntry> chunk) {
                if (isCancelled()) return;
                if (firstBatch) {
                    firstBatch = false;
                    logTableModel.endBatchLoad();
                    ((CardLayout) centerCards.getLayout()).show(centerCards, CARD_CONTENT);
                    applyDefaultLayoutIfSet();
                    autoResizeColumns();
                }
                logTableModel.appendBatch(chunk);
                filterBar.addBatch(chunk);
                if (follow) scrollToBottom();
            }

            @Override
            protected void done() {
                ((CardLayout) centerCards.getLayout()).show(centerCards, CARD_CONTENT);
                if (isCancelled()) return;
                try {
                    get();
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log.error("SSH error for {}", config.displayName(), cause);
                    JOptionPane.showMessageDialog(
                            SwingUtilities.getWindowAncestor(LogPanel.this),
                            cause.getMessage(),
                            Messages.get("ssh.error.title"),
                            JOptionPane.ERROR_MESSAGE);
                } catch (Exception ignored) {}
            }
        };
        currentWorker = worker;
        worker.execute();
    }

    private static boolean isCompressed(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".gz") || name.endsWith(".zip");
    }

    static BufferedReader openReader(Path file) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".gz")) {
            return new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(file)), decoder));
        }
        if (name.endsWith(".zip")) {
            ZipInputStream zis = new ZipInputStream(Files.newInputStream(file));
            zis.getNextEntry();
            return new BufferedReader(new InputStreamReader(zis, decoder));
        }
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder));
    }

    private void startTailing() {
        stopTailing();
        if (currentFile == null || isCompressed(currentFile)) return;
        final Optional<LogParser> parser = currentParser;
        tailWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                while (!isCancelled()) {
                    try {
                        long currentSize = Files.size(currentFile);
                        if (currentSize < tailPosition) {
                            // File was rotated — reset position to read new file from the beginning
                            tailPosition = 0;
                        }
                        if (currentSize > tailPosition) {
                            try (RandomAccessFile raf = new RandomAccessFile(currentFile.toFile(), "r")) {
                                raf.seek(tailPosition);
                                String line;
                                while ((line = raf.readLine()) != null && !isCancelled()) {
                                    if (!line.isBlank()) {
                                        final String candidate = line;
                                        LogEntry entry = parser.map(Stream::of)
                                                .orElseGet(parsers::stream)
                                                .filter(p -> p.canParse(candidate))
                                                .findFirst()
                                                .map(p -> p.parse(candidate))
                                                .orElseGet(() -> new LogEntry(
                                                        null, LogEntry.LogLevel.UNKNOWN,
                                                        null, null,
                                                        candidate, candidate, Map.of()));
                                        publish(entry);
                                    }
                                }
                                tailPosition = raf.getFilePointer();
                            }
                        }
                    } catch (IOException ignored) {}
                    Thread.sleep(500);
                }
                return null;
            }

            @Override
            protected void process(List<LogEntry> entries) {
                if (isCancelled()) return;
                filterBar.addBatch(entries);
                for (LogEntry entry : entries) logTableModel.append(entry);
            }
        };
        tailWorker.execute();
    }

    private void stopTailing() {
        SwingWorker<?, ?> w = tailWorker;
        if (w != null) { w.cancel(true); tailWorker = null; }
    }

    public void dispose() {
        cancelLoad();
        logTableModel.dispose();
    }

    public JTable getLogTable() { return logTable; }

    public void applyDetailFontSize(int size) {
        int viewRow = logTable.getSelectedRow();
        int modelRow = viewRow >= 0 ? logTable.convertRowIndexToModel(viewRow) : -1;
        showDetail(modelRow >= 0 ? logTableModel.getEntry(modelRow) : null, modelRow);
    }

    public void applyWrapLines(boolean wrap) {
        this.wrapLines = wrap;
        detailArea.revalidate();
        refreshDetail();
    }

    // ── Status bar ────────────────────────────────────────────────────────────

    private JPanel buildStatusBar() {
        statusBarPathLabel = new JLabel(" ");
        statusBarPathLabel.setForeground(UIManager.getColor("Button.foreground"));
        statusBarPathLabel.setFont(statusBarPathLabel.getFont().deriveFont(12f));
        statusBarPathLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

        statusBarLabel = new JLabel(" ");
        statusBarLabel.setForeground(UIManager.getColor("Button.foreground"));
        statusBarLabel.setFont(statusBarLabel.getFont().deriveFont(12f));
        statusBarLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        statusBarLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ClouseauColors.separatorColor()));
        panel.add(statusBarPathLabel, BorderLayout.WEST);
        panel.add(statusBarLabel, BorderLayout.EAST);
        return panel;
    }

    public void clearTable() {
        int confirm = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                Messages.get("table.clear.confirm.message"),
                Messages.get("table.clear.confirm.title"),
                JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        stopTailing();
        try { tailPosition = currentFile != null ? Files.size(currentFile) : 0; } catch (IOException ignored) { tailPosition = 0; }
        logIndex.clear();
        logTableModel.clear();
        showDetail(null, -1);
        clearSearchMatches();
        updateStatusBar();
        if (follow) startTailing();
    }

    private void updateStatusBar() {
        int  visible  = logTableModel.getRowCount();
        int  total    = logTableModel.getTotalCount();
        long trimmed  = logTableModel.getTrimmedCount();
        String text;
        if (trimmed > 0) {
            text = (visible == total)
                    ? java.text.MessageFormat.format(Messages.get("status.lines.trimmed"), total, trimmed)
                    : java.text.MessageFormat.format(Messages.get("status.lines.filtered.trimmed"), visible, total, trimmed);
        } else {
            text = (visible == total)
                    ? java.text.MessageFormat.format(Messages.get("status.lines"), total)
                    : java.text.MessageFormat.format(Messages.get("status.lines.filtered"), visible, total);
        }
        statusBarLabel.setText(text);
    }

    // ── Loading overlay ───────────────────────────────────────────────────────

    private JPanel buildLoadingPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ClouseauColors.loadingBackground());

        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setBorderPainted(false);
        bar.setForeground(ClouseauColors.progressForeground());
        bar.setBackground(ClouseauColors.progressBackground());
        bar.setPreferredSize(new Dimension(280, 4));

        JLabel label = new JLabel(Messages.get("loading.message"), SwingConstants.CENTER);
        label.setForeground(ClouseauColors.dimForeground());
        label.setFont(label.getFont().deriveFont(13f));

        JPanel inner = new JPanel(new MigLayout("insets 0", "[280!]", "[]10[]"));
        inner.setOpaque(false);
        inner.add(bar,   "wrap, h 4!");
        inner.add(label, "growx");

        panel.add(inner, BorderLayout.CENTER);
        return panel;
    }

    // ── Log table ────────────────────────────────────────────────────────────

    private JScrollPane buildLogTable() {
        logTable = new JTable(logTableModel) {
            @Override public boolean isCellEditable(int r, int c) { return false; }

            @Override
            public boolean getScrollableTracksViewportWidth() {
                // During a column resize drag, release the viewport constraint so the column
                // can grow freely and the horizontal scrollbar appears immediately.
                if (getTableHeader().getResizingColumn() != null) return false;
                // Otherwise fill the viewport when columns are narrower; scroll when wider.
                return getParent() == null || getPreferredSize().width < getParent().getWidth();
            }
        };
        logTable.setBackground(ClouseauColors.tableBackground());
        logTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, InputEvent.CTRL_DOWN_MASK), "scrollToTop");
        logTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK), "scrollToBottom");
        logTable.getActionMap().put("scrollToTop",    new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { scrollToTop(); }
        });
        logTable.getActionMap().put("scrollToBottom", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { scrollToBottom(); }
        });
        logTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        logTable.setRowSorter(new javax.swing.table.TableRowSorter<>(logTableModel));
        logTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        logTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        logTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        logTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        logTable.getColumnModel().getColumn(4).setPreferredWidth(200);

        Border cellPad = BorderFactory.createEmptyBorder(0, 8, 0, 8);
        DefaultTableCellRenderer levelRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                LogEntry entry = logTableModel.getEntry(logTable.convertRowIndexToModel(r));
                Color hl = logTableModel.getHighlight(entry);
                boolean hlSel = hl != null && sel;

                // Background
                boolean isSearchMatch = searchMatchModelRows.contains(logTable.convertRowIndexToModel(r));
                if (hl != null) {
                    setBackground(sel ? blend(hl, t.getSelectionBackground(), 0.55f) : hl);
                } else if (sel) {
                    setBackground(ClouseauColors.selectionBackground());
                } else if (isSearchMatch) {
                    setBackground(ClouseauColors.searchMatchBackground());
                } else {
                    setBackground(t.getBackground());
                }

                // Ring border on any selected row
                if (sel) {
                    Color ring = hl != null ? hl.brighter().brighter() : ClouseauColors.selectionBackground().brighter();
                    int lastCol = t.getColumnCount() - 1;
                    setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(2, c == 0 ? 2 : 0, 2, c == lastCol ? 2 : 0, ring),
                            cellPad));
                } else {
                    setBorder(cellPad);
                }

                // Foreground + font
                int modelCol = t.convertColumnIndexToModel(c);
                if (sel && hl == null) {
                    setForeground(Color.BLACK);
                } else if (!sel) {
                    if (modelCol == 2 || modelCol == 5) {                  // Level + Message columns
                        LogEntry.LogLevel level = entry != null ? entry.level() : null;
                        setForeground(ClouseauColors.levelColor(level));
                    } else {
                        setForeground(t.getForeground());
                    }
                }
                boolean isLevel = modelCol == 2;
                setFont(getFont().deriveFont(sel || isLevel ? Font.BOLD : Font.PLAIN));
                return this;
            }
        };
        levelRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        logTable.setDefaultRenderer(String.class,  levelRenderer);
        logTable.setDefaultRenderer(Integer.class, levelRenderer);

        JTableHeader header = logTable.getTableHeader();
        TableCellRenderer origHeaderRenderer = header.getDefaultRenderer();
        header.setDefaultRenderer((table, value, isSelected, hasFocus, row, column) -> {
            Component c = origHeaderRenderer.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (c instanceof JLabel label) {
                Border existing = label.getBorder();
                label.setBorder(existing != null
                        ? BorderFactory.createCompoundBorder(existing, cellPad)
                        : cellPad);
            }
            return c;
        });

        header.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShowColumnMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowColumnMenu(e); }
            private void maybeShowColumnMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                JPopupMenu menu = new JPopupMenu();

                // ── Layouts section ──────────────────────────────────────────
                menu.add(menuSectionLabel(Messages.get("table.columns.layout.section")));
                menu.addSeparator();

                List<AppPrefs.ColumnLayout> layouts = AppPrefs.getColumnLayouts();
                String defaultName = AppPrefs.getDefaultColumnLayout();
                if (layouts.isEmpty()) {
                    JMenuItem none = new JMenuItem(Messages.get("table.columns.layout.none"));
                    none.setEnabled(false);
                    menu.add(none);
                } else {
                    for (AppPrefs.ColumnLayout layout : layouts) {
                        boolean isDefault = layout.name().equals(defaultName);
                        boolean isActive  = layout.name().equals(activeLayoutName);
                        String prefix = (isDefault ? "\u2605" : " ") + (isActive ? "\u2713 " : "  ");
                        JMenuItem applyItem = new JMenuItem(prefix + layout.name());
                        applyItem.addActionListener(ev ->
                            SwingUtilities.invokeLater(() -> applyColumnLayout(layout)));
                        menu.add(applyItem);
                    }
                }
                menu.addSeparator();
                JMenuItem showAllItem = new JMenuItem(Messages.get("table.columns.layout.show.all"));
                showAllItem.setEnabled(!hiddenColumns.isEmpty());
                showAllItem.addActionListener(ev -> SwingUtilities.invokeLater(LogPanel.this::showAllColumns));
                menu.add(showAllItem);
                menu.addSeparator();
                JMenuItem saveItem = new JMenuItem(Messages.get("table.columns.layout.save"));
                saveItem.addActionListener(ev -> saveCurrentLayout());
                menu.add(saveItem);
                JMenuItem manageItem = new JMenuItem(Messages.get("table.columns.layout.manage"));
                manageItem.setEnabled(!layouts.isEmpty());
                manageItem.addActionListener(ev ->
                    new ColumnLayoutManagerDialog(SwingUtilities.getWindowAncestor(logTable))
                            .setVisible(true));
                menu.add(manageItem);

                // ── Columns section ──────────────────────────────────────────
                menu.addSeparator();
                menu.add(menuSectionLabel(Messages.get("table.columns.menu.title")));
                menu.addSeparator();

                int totalCols = logTableModel.getColumnCount();
                for (int modelIdx = 0; modelIdx < totalCols; modelIdx++) {
                    String name = logTableModel.getColumnName(modelIdx);
                    boolean visible = !hiddenColumns.containsKey(modelIdx);
                    JCheckBoxMenuItem item = new JCheckBoxMenuItem(name, visible);
                    final int idx = modelIdx;
                    item.addActionListener(ev ->
                        SwingUtilities.invokeLater(() -> {
                            if (visible) hideColumn(idx);
                            else         showColumn(idx);
                        }));
                    menu.add(item);
                }
                menu.show(header, e.getX(), e.getY());
            }

            private JLabel menuSectionLabel(String text) {
                JLabel lbl = new JLabel(text);
                lbl.setBorder(BorderFactory.createEmptyBorder(3, 6, 1, 6));
                lbl.setForeground(ClouseauColors.dimForeground());
                lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 11f));
                return lbl;
            }
        });

        Font headerFont = header.getFont();
        for (int i = 0; i < logTable.getColumnCount(); i++) {
            JLabel measure = new JLabel(logTable.getColumnName(i));
            measure.setFont(headerFont);
            int minW = measure.getPreferredSize().width + 40;
            logTable.getColumnModel().getColumn(i).setMinWidth(minW);
        }
        logTable.setShowHorizontalLines(true);
        logTable.setShowVerticalLines(false);
        logTable.setGridColor(ClouseauColors.separatorColor());
        logTable.setRowHeight(AppPrefs.getRowHeight());
        logTable.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShowTableMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowTableMenu(e); }
            private void maybeShowTableMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int viewRow = logTable.rowAtPoint(e.getPoint());
                List<LogEntry> entries = new ArrayList<>();
                if (viewRow >= 0) {
                    if (!logTable.isRowSelected(viewRow)) logTable.setRowSelectionInterval(viewRow, viewRow);
                    for (int vr : logTable.getSelectedRows()) {
                        LogEntry entry = logTableModel.getEntry(logTable.convertRowIndexToModel(vr));
                        if (entry != null) entries.add(entry);
                    }
                }
                showHighlightMenu(entries, e.getX(), e.getY());
            }
        });

        tableScrollPane = new JScrollPane(logTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tableScrollPane.getViewport().setBackground(ClouseauColors.viewportBackground());
        tableScrollPane.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (tableColumnsManaged) stretchMessageColumn();
            }
        });
        return tableScrollPane;
    }

    /** Model column index of the Message column — always stretched to fill remaining width. */
    private static final int MESSAGE_MODEL_COL = 5;

    private void autoResizeColumns() {
        int totalFitted = 0;
        int sampleSize = Math.min(logTable.getRowCount(), 50);

        for (int col = 0; col < logTable.getColumnCount(); col++) {
            if (logTable.getColumnModel().getColumn(col).getModelIndex() == MESSAGE_MODEL_COL) continue;
            int maxWidth = 0;
            TableCellRenderer hr = logTable.getColumnModel().getColumn(col).getHeaderRenderer();
            if (hr == null) hr = logTable.getTableHeader().getDefaultRenderer();
            Component hc = hr.getTableCellRendererComponent(
                    logTable, logTable.getColumnName(col), false, false, -1, col);
            maxWidth = Math.max(maxWidth, hc.getPreferredSize().width);
            for (int row = 0; row < sampleSize; row++) {
                Component rc = logTable.prepareRenderer(logTable.getCellRenderer(row, col), row, col);
                maxWidth = Math.max(maxWidth, rc.getPreferredSize().width);
            }
            maxWidth += 8;
            logTable.getColumnModel().getColumn(col).setPreferredWidth(maxWidth);
            logTable.getColumnModel().getColumn(col).setWidth(maxWidth);
            totalFitted += maxWidth;
        }

        fittedColumnsWidth = totalFitted;
        tableColumnsManaged = true;
        stretchMessageColumn();
        logTable.repaint();
        logTable.getTableHeader().repaint();
    }

    private void stretchMessageColumn() {
        // Find the Message column by model index; fall back to the last visible column if hidden.
        int msgViewCol = -1;
        for (int i = 0; i < logTable.getColumnCount(); i++) {
            if (logTable.getColumnModel().getColumn(i).getModelIndex() == MESSAGE_MODEL_COL) {
                msgViewCol = i;
                break;
            }
        }
        if (msgViewCol < 0) {
            if (logTable.getColumnCount() == 0) return;
            msgViewCol = logTable.getColumnCount() - 1;
        }
        int margins = logTable.getColumnModel().getColumnMargin() * logTable.getColumnCount();
        int viewportWidth = (tableScrollPane != null) ? tableScrollPane.getViewport().getWidth() : logTable.getWidth();
        int msgWidth = Math.max(viewportWidth - fittedColumnsWidth - margins, 150);
        logTable.getColumnModel().getColumn(msgViewCol).setPreferredWidth(msgWidth);
        logTable.getColumnModel().getColumn(msgViewCol).setWidth(msgWidth);
    }

    // ── Column visibility ─────────────────────────────────────────────────────

    private void hideColumn(int modelIndex) {
        if (logTable.getColumnCount() <= 1) return; // always keep at least one column visible
        javax.swing.table.TableColumnModel cm = logTable.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            TableColumn col = cm.getColumn(i);
            if (col.getModelIndex() == modelIndex) {
                hiddenColumns.put(modelIndex, col);
                cm.removeColumn(col);
                activeLayoutName = null;
                recalcFittedColumnsWidth();
                return;
            }
        }
    }

    private void showColumn(int modelIndex) {
        TableColumn col = hiddenColumns.remove(modelIndex);
        if (col == null) return;
        javax.swing.table.TableColumnModel cm = logTable.getColumnModel();
        cm.addColumn(col);
        // Reposition: place after the last visible column whose model index is smaller
        int targetViewIndex = 0;
        for (int i = 0; i < cm.getColumnCount() - 1; i++) {
            if (cm.getColumn(i).getModelIndex() < modelIndex) targetViewIndex = i + 1;
        }
        cm.moveColumn(cm.getColumnCount() - 1, targetViewIndex);
        activeLayoutName = null;
        recalcFittedColumnsWidth();
    }

    /** Recomputes {@code fittedColumnsWidth} from current visible column widths and stretches Message. */
    private void recalcFittedColumnsWidth() {
        if (!tableColumnsManaged) return;
        int total = 0;
        for (int i = 0; i < logTable.getColumnCount(); i++) {
            if (logTable.getColumnModel().getColumn(i).getModelIndex() != MESSAGE_MODEL_COL)
                total += logTable.getColumnModel().getColumn(i).getWidth();
        }
        fittedColumnsWidth = total;
        stretchMessageColumn();
    }

    // ── Column layouts ────────────────────────────────────────────────────────

    /** Snapshots the current column order, visibility and widths into a named layout. */
    AppPrefs.ColumnLayout captureLayout(String name) {
        List<AppPrefs.ColumnLayout.ColumnEntry> entries = new ArrayList<>();
        for (int vi = 0; vi < logTable.getColumnCount(); vi++) {
            TableColumn col = logTable.getColumnModel().getColumn(vi);
            entries.add(new AppPrefs.ColumnLayout.ColumnEntry(col.getModelIndex(), true, col.getWidth()));
        }
        hiddenColumns.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> entries.add(
                        new AppPrefs.ColumnLayout.ColumnEntry(e.getKey(), false, e.getValue().getWidth())));
        return new AppPrefs.ColumnLayout(name, entries);
    }

    /** Applies a saved layout: visibility, order and widths. Safe to call on any file (unknown model indices are skipped). */
    void applyColumnLayout(AppPrefs.ColumnLayout layout) {
        int modelCount = logTableModel.getColumnCount();

        // 1. Make all currently-hidden columns visible again
        new ArrayList<>(hiddenColumns.keySet()).forEach(mi -> {
            TableColumn col = hiddenColumns.remove(mi);
            logTable.getColumnModel().addColumn(col);
        });

        // 2. Hide columns the layout marks as hidden (skip indices outside current model)
        for (AppPrefs.ColumnLayout.ColumnEntry entry : layout.columns()) {
            if (!entry.visible() && entry.modelIndex() < modelCount) {
                javax.swing.table.TableColumnModel cm = logTable.getColumnModel();
                for (int vi = 0; vi < cm.getColumnCount(); vi++) {
                    TableColumn col = cm.getColumn(vi);
                    if (col.getModelIndex() == entry.modelIndex()) {
                        hiddenColumns.put(entry.modelIndex(), col);
                        cm.removeColumn(col);
                        break;
                    }
                }
            }
        }

        // 3. Reorder visible columns to match layout order
        List<Integer> desired = layout.columns().stream()
                .filter(e -> e.visible() && e.modelIndex() < modelCount)
                .map(AppPrefs.ColumnLayout.ColumnEntry::modelIndex)
                .toList();

        for (int targetView = 0; targetView < desired.size() && targetView < logTable.getColumnCount(); targetView++) {
            int desiredModel = desired.get(targetView);
            for (int vi = targetView; vi < logTable.getColumnCount(); vi++) {
                if (logTable.getColumnModel().getColumn(vi).getModelIndex() == desiredModel) {
                    if (vi != targetView) logTable.getColumnModel().moveColumn(vi, targetView);
                    break;
                }
            }
        }

        // 4. Apply widths
        Map<Integer, Integer> widthByModel = new LinkedHashMap<>();
        layout.columns().forEach(e -> widthByModel.put(e.modelIndex(), e.width()));

        for (int vi = 0; vi < logTable.getColumnCount(); vi++) {
            TableColumn col = logTable.getColumnModel().getColumn(vi);
            Integer w = widthByModel.get(col.getModelIndex());
            if (w != null) { col.setPreferredWidth(w); col.setWidth(w); }
        }
        hiddenColumns.forEach((mi, col) -> {
            Integer w = widthByModel.get(mi);
            if (w != null) col.setPreferredWidth(w);
        });

        tableColumnsManaged = true;
        activeLayoutName = layout.name();
        recalcFittedColumnsWidth();
    }

    private void showAllColumns() {
        new ArrayList<>(hiddenColumns.keySet()).forEach(mi -> {
            TableColumn col = hiddenColumns.remove(mi);
            logTable.getColumnModel().addColumn(col);
            // Reposition: place after the last visible column whose model index is smaller
            javax.swing.table.TableColumnModel cm = logTable.getColumnModel();
            int targetViewIndex = 0;
            for (int i = 0; i < cm.getColumnCount() - 1; i++) {
                if (cm.getColumn(i).getModelIndex() < mi) targetViewIndex = i + 1;
            }
            cm.moveColumn(cm.getColumnCount() - 1, targetViewIndex);
        });
        activeLayoutName = null;
        recalcFittedColumnsWidth();
    }

    private void applyDefaultLayoutIfSet() {
        String defaultName = AppPrefs.getDefaultColumnLayout();
        if (defaultName == null) return;
        AppPrefs.getColumnLayouts().stream()
                .filter(l -> l.name().equals(defaultName))
                .findFirst()
                .ifPresent(this::applyColumnLayout);
    }

    void saveCurrentLayout() {
        String name = (String) JOptionPane.showInputDialog(
                SwingUtilities.getWindowAncestor(this),
                Messages.get("table.columns.layout.save.message"),
                Messages.get("table.columns.layout.save.title"),
                JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (name == null || name.isBlank()) return;
        name = name.trim();

        List<AppPrefs.ColumnLayout> layouts = new ArrayList<>(AppPrefs.getColumnLayouts());
        final String finalName = name;
        if (layouts.stream().anyMatch(l -> l.name().equals(finalName))) {
            int confirm = JOptionPane.showConfirmDialog(
                    SwingUtilities.getWindowAncestor(this),
                    java.text.MessageFormat.format(Messages.get("table.columns.layout.overwrite.message"), finalName),
                    Messages.get("table.columns.layout.overwrite.title"),
                    JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
            layouts.removeIf(l -> l.name().equals(finalName));
        }
        layouts.add(captureLayout(finalName));
        AppPrefs.saveColumnLayouts(layouts);
    }

    // ── Highlight menu ────────────────────────────────────────────────────────

    private void showHighlightMenu(List<LogEntry> entries, int x, int y) {
        boolean anyHighlighted = entries.stream().anyMatch(e -> logTableModel.getHighlight(e) != null);

        JPopupMenu menu = new JPopupMenu();

        if (!entries.isEmpty()) {
            JPanel swatches = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
            swatches.setOpaque(false);
            for (Color color : ClouseauColors.highlightColors()) {
                boolean allMatch = entries.stream().allMatch(e -> color.equals(logTableModel.getHighlight(e)));
                JButton swatch = new JButton() {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(color);
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                        if (allMatch) {
                            g2.setColor(Color.WHITE);
                            g2.setStroke(new BasicStroke(2f));
                            g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 5, 5);
                        }
                        g2.dispose();
                    }
                };
                swatch.setPreferredSize(new Dimension(22, 22));
                swatch.setBorderPainted(false);
                swatch.setContentAreaFilled(false);
                swatch.setFocusPainted(false);
                swatch.addActionListener(e -> {
                    entries.forEach(entry -> logTableModel.setHighlight(entry, color));
                    menu.setVisible(false);
                    refreshDetailAfterHighlight();
                });
                swatches.add(swatch);
            }
            menu.add(swatches);
            menu.addSeparator();

            final boolean hasHL = logTableModel.hasHighlights();

            JMenuItem clearItem = new JMenuItem(Messages.get("highlight.clear"));
            if (anyHighlighted) {
                clearItem.addActionListener(e -> {
                    entries.forEach(entry -> logTableModel.setHighlight(entry, null));
                    refreshDetailAfterHighlight();
                });
            } else {
                clearItem.putClientProperty("FlatLaf.style", "disabledForeground: " + colorToHex(ClouseauColors.borderColor()));
                clearItem.setEnabled(false);
            }
            menu.add(clearItem);

            JMenuItem clearAllItem = new JMenuItem(Messages.get("highlight.clear.all"));
            if (hasHL) {
                clearAllItem.addActionListener(e -> {
                    if (AppPrefs.isHighlightClearAllConfirm()) {
                        int confirm = JOptionPane.showConfirmDialog(
                                SwingUtilities.getWindowAncestor(this),
                                Messages.get("highlight.clear.all.confirm.message"),
                                Messages.get("highlight.clear.all.confirm.title"),
                                JOptionPane.YES_NO_OPTION);
                        if (confirm != JOptionPane.YES_OPTION) return;
                    }
                    logTableModel.clearAllHighlights();
                    refreshDetailAfterHighlight();
                });
            } else {
                clearAllItem.putClientProperty("FlatLaf.style", "disabledForeground: " + colorToHex(ClouseauColors.borderColor()));
                clearAllItem.setEnabled(false);
            }
            menu.add(clearAllItem);

            menu.addSeparator();
        }

        JMenuItem reloadFileItem = new JMenuItem(Messages.get("table.context.reload.file"));
        reloadFileItem.setEnabled(currentFile != null);
        reloadFileItem.addActionListener(e -> reload());
        menu.add(reloadFileItem);

        JMenuItem clearTableItem = new JMenuItem(Messages.get("table.clear"));
        clearTableItem.addActionListener(e -> clearTable());
        menu.add(clearTableItem);

        menu.show(logTable, x, y);
    }

    private void refreshDetailAfterHighlight() {
        int viewRow = logTable.getSelectedRow();
        int modelRow = viewRow >= 0 ? logTable.convertRowIndexToModel(viewRow) : -1;
        showDetail(modelRow >= 0 ? logTableModel.getEntry(modelRow) : null, modelRow);
        refreshHighlightNav();
    }

    // ── Highlight navigation ──────────────────────────────────────────────────

    private JPanel buildHighlightNavBar() {
        highlightNavBar = new JPanel(new MigLayout("insets 3 8 3 8, gapy 0", "[][grow][]", "[]"));
        highlightNavBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ClouseauColors.separatorColor()));
        highlightNavBar.setVisible(false);

        JLabel label = new JLabel(Messages.get("highlight.nav.label"));
        label.setForeground(ClouseauColors.dimForeground());
        label.setFont(label.getFont().deriveFont(12f));

        highlightNavSwatches = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        highlightNavSwatches.setOpaque(false);

        highlightNavPosition = new JLabel("0 / 0");
        highlightNavPosition.setForeground(ClouseauColors.dimForeground());
        highlightNavPosition.setFont(highlightNavPosition.getFont().deriveFont(12f));

        JButton prevBtn = makeNavArrowButton("\u25b2");
        JButton nextBtn = makeNavArrowButton("\u25bc");
        prevBtn.setToolTipText(Messages.get("highlight.nav.prev.tooltip"));
        nextBtn.setToolTipText(Messages.get("highlight.nav.next.tooltip"));
        prevBtn.addActionListener(e -> navigateHighlight(-1));
        nextBtn.addActionListener(e -> navigateHighlight(+1));

        JPanel rightPanel = new JPanel(new MigLayout("insets 0", "[]6[]4[]", "[]"));
        rightPanel.setOpaque(false);
        rightPanel.add(highlightNavPosition);
        rightPanel.add(prevBtn);
        rightPanel.add(nextBtn);

        highlightNavBar.add(label);
        highlightNavBar.add(highlightNavSwatches, "grow");
        highlightNavBar.add(rightPanel);
        return highlightNavBar;
    }

    private JButton makeNavArrowButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(btn.getFont().deriveFont(11f));
        btn.setPreferredSize(new Dimension(22, 18));
        btn.setMargin(new Insets(0, 2, 0, 2));
        return btn;
    }

    private void refreshHighlightNav() {
        java.util.Set<Color> activeColors = logTableModel.getActiveHighlightColors();
        if (activeColors.isEmpty()) {
            highlightNavBar.setVisible(false);
            highlightNavColor = null;
            return;
        }
        // If selected color was removed, fall back to "All"
        if (highlightNavColor != null && !activeColors.contains(highlightNavColor)) {
            highlightNavColor = null;
        }
        highlightNavBar.setVisible(true);
        highlightNavSwatches.removeAll();

        // "All" button
        final Color navColorSnap = highlightNavColor;
        JButton allBtn = new JButton(Messages.get("highlight.nav.all")) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(navColorSnap == null ? ClouseauColors.highlightNavSelected() : ClouseauColors.highlightNavUnselected());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                g2.setColor(navColorSnap == null ? Color.WHITE : ClouseauColors.dimForeground());
                FontMetrics fm = g2.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(getText())) / 2;
                int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(getText(), x, y);
                g2.dispose();
            }
        };
        allBtn.setPreferredSize(new Dimension(30, 16));
        allBtn.setBorderPainted(false);
        allBtn.setContentAreaFilled(false);
        allBtn.setFocusPainted(false);
        allBtn.setFont(allBtn.getFont().deriveFont(11f));
        allBtn.addActionListener(e -> { highlightNavColor = null; refreshHighlightNav(); });
        highlightNavSwatches.add(allBtn);

        // Color swatches in highlight palette order
        for (Color palette : ClouseauColors.highlightColors()) {
            if (!activeColors.contains(palette)) continue;
            final Color c = palette;
            final boolean selected = c.equals(highlightNavColor);
            JButton swatch = new JButton() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(c);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                    if (selected) {
                        g2.setColor(Color.WHITE);
                        g2.setStroke(new BasicStroke(1.5f));
                        g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 4, 4);
                    }
                    g2.dispose();
                }
            };
            swatch.setPreferredSize(new Dimension(16, 16));
            swatch.setBorderPainted(false);
            swatch.setContentAreaFilled(false);
            swatch.setFocusPainted(false);
            swatch.addActionListener(e -> { highlightNavColor = c; refreshHighlightNav(); });
            highlightNavSwatches.add(swatch);
        }

        highlightNavSwatches.revalidate();
        highlightNavSwatches.repaint();
        updateHighlightNavPosition();
    }

    private void updateHighlightNavPosition() {
        if (highlightNavBar == null || !highlightNavBar.isVisible()) return;
        List<Integer> highlighted = logTableModel.getHighlightedModelRows(highlightNavColor);
        if (highlighted.isEmpty()) { highlightNavPosition.setText("0 / 0"); return; }
        int currentView  = logTable.getSelectedRow();
        int currentModel = currentView >= 0 ? logTable.convertRowIndexToModel(currentView) : -1;
        int pos = 0;
        for (int i = 0; i < highlighted.size(); i++) {
            if (highlighted.get(i) <= currentModel) pos = i + 1;
        }
        highlightNavPosition.setText(pos + " / " + highlighted.size());
    }

    private void navigateHighlight(int direction) {
        List<Integer> highlighted = logTableModel.getHighlightedModelRows(highlightNavColor);
        if (highlighted.isEmpty()) return;
        int currentView  = logTable.getSelectedRow();
        int currentModel = currentView >= 0 ? logTable.convertRowIndexToModel(currentView) : -1;
        int targetModel;
        if (direction > 0) {
            targetModel = highlighted.stream()
                    .filter(r -> r > currentModel)
                    .findFirst()
                    .orElse(highlighted.get(0));
        } else {
            targetModel = highlighted.stream()
                    .filter(r -> r < currentModel)
                    .reduce((a, b) -> b)
                    .orElse(highlighted.get(highlighted.size() - 1));
        }
        int viewRow = logTable.convertRowIndexToView(targetModel);
        if (viewRow >= 0) {
            logTable.setRowSelectionInterval(viewRow, viewRow);
            logTable.scrollRectToVisible(logTable.getCellRect(viewRow, 0, true));
            logTable.requestFocusInWindow();
            updateHighlightNavPosition();
        }
    }

    // ── Find bar ──────────────────────────────────────────────────────────────

    private JPanel buildFindBar() {
        JPanel bar = new JPanel(new MigLayout("insets 3 8 3 8, gap 4", "[][grow][][][]", "[center]"));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ClouseauColors.separatorColor()));
        bar.setVisible(false);

        JLabel label = new JLabel("Find:");
        label.setForeground(ClouseauColors.dimForeground());
        label.setFont(label.getFont().deriveFont(12f));

        findField = new JTextField();
        findField.putClientProperty("JTextField.placeholderText", "Search all columns\u2026");

        JButton clearFieldBtn = new JButton("\u00d7");
        clearFieldBtn.setVisible(false);
        clearFieldBtn.setFont(clearFieldBtn.getFont().deriveFont(14f));
        clearFieldBtn.setBorderPainted(false);
        clearFieldBtn.setContentAreaFilled(false);
        clearFieldBtn.setFocusPainted(false);
        clearFieldBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearFieldBtn.addActionListener(e -> { findField.setText(""); findField.requestFocusInWindow(); });
        findField.putClientProperty("JTextField.trailingComponent", clearFieldBtn);

        javax.swing.Timer[] findDebounce = {null};
        findField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onFindTextChanged(); }
            public void removeUpdate(DocumentEvent e) { onFindTextChanged(); }
            public void changedUpdate(DocumentEvent e) {}
            private void onFindTextChanged() {
                clearFieldBtn.setVisible(!findField.getText().isEmpty());
                if (findDebounce[0] != null) findDebounce[0].stop();
                findDebounce[0] = new javax.swing.Timer(250, ev -> updateSearchMatches(findField.getText()));
                findDebounce[0].setRepeats(false);
                findDebounce[0].start();
            }
        });
        findField.addActionListener(e -> navigateSearch(+1));
        findField.registerKeyboardAction(
                e -> navigateSearch(-1),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                JComponent.WHEN_FOCUSED);
        findField.registerKeyboardAction(
                e -> { bar.setVisible(false); clearSearchMatches(); logTable.requestFocusInWindow(); },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_FOCUSED);

        findCounter = new JLabel();
        findCounter.setForeground(ClouseauColors.dimForeground());
        findCounter.setFont(findCounter.getFont().deriveFont(11f));
        findCounter.setPreferredSize(new Dimension(80, findCounter.getPreferredSize().height));

        JButton prevBtn = makeNavArrowButton("\u25b2");
        JButton nextBtn = makeNavArrowButton("\u25bc");
        prevBtn.setToolTipText("Previous match (Shift+Enter)");
        nextBtn.setToolTipText("Next match (Enter)");
        prevBtn.addActionListener(e -> navigateSearch(-1));
        nextBtn.addActionListener(e -> navigateSearch(+1));

        JButton closeBtn = new JButton("\u00d7");
        closeBtn.setFont(closeBtn.getFont().deriveFont(13f));
        closeBtn.setMargin(new Insets(1, 4, 1, 4));
        closeBtn.addActionListener(e -> { bar.setVisible(false); clearSearchMatches(); logTable.requestFocusInWindow(); });

        bar.add(label);
        bar.add(findField, "growx");
        bar.add(findCounter);
        bar.add(prevBtn);
        bar.add(nextBtn);
        bar.add(closeBtn);
        return bar;
    }

    private void updateSearchMatches(String query) {
        if (query == null || query.isBlank()) {
            clearSearchMatches();
            return;
        }
        String lower = query.toLowerCase();
        List<Integer> list = new ArrayList<>();
        Set<Integer>  set  = new HashSet<>();
        for (int viewRow = 0; viewRow < logTable.getRowCount(); viewRow++) {
            int modelRow = logTable.convertRowIndexToModel(viewRow);
            LogEntry entry = logTableModel.getEntry(modelRow);
            if (entry != null && (entryMatchesQuery(entry, lower) || continuationsMatch(modelRow, lower))) {
                list.add(modelRow);
                set.add(modelRow);
            }
        }
        searchMatchModelRows = Collections.unmodifiableSet(set);
        searchMatchList      = Collections.unmodifiableList(list);
        searchMatchCursor    = -1;
        int n = list.size();
        findCounter.setForeground(n == 0 ? ClouseauColors.statusError() : ClouseauColors.dimForeground());
        findCounter.setText(n + (n == 1 ? " match" : " matches"));
        logTable.repaint();
        if (n > 0) navigateSearch(+1);
    }

    private void clearSearchMatches() {
        searchMatchModelRows = Set.of();
        searchMatchList      = List.of();
        searchMatchCursor    = -1;
        if (findCounter != null) findCounter.setText("");
        logTable.repaint();
    }

    private boolean continuationsMatch(int modelRow, String lower) {
        for (LogEntry c : logTableModel.getContinuationLines(modelRow)) {
            if (entryMatchesQuery(c, lower)) return true;
        }
        return false;
    }

    private static boolean entryMatchesQuery(LogEntry entry, String lower) {
        if (entry.message() != null && entry.message().toLowerCase().contains(lower)) return true;
        if (entry.logger()  != null && entry.logger().toLowerCase().contains(lower))  return true;
        if (entry.thread()  != null && entry.thread().toLowerCase().contains(lower))  return true;
        if (entry.level()   != null && entry.level().name().toLowerCase().contains(lower)) return true;
        if (entry.fields()  != null) {
            for (String v : entry.fields().values()) {
                if (v != null && v.toLowerCase().contains(lower)) return true;
            }
        }
        return false;
    }

    private void navigateSearch(int direction) {
        if (searchMatchList.isEmpty()) return;
        if (searchMatchCursor < 0) {
            int currentView  = logTable.getSelectedRow();
            int currentModel = currentView >= 0 ? logTable.convertRowIndexToModel(currentView) : -1;
            searchMatchCursor = 0;
            if (direction > 0) {
                for (int i = 0; i < searchMatchList.size(); i++) {
                    if (searchMatchList.get(i) >= currentModel) { searchMatchCursor = i; break; }
                }
            } else {
                searchMatchCursor = searchMatchList.size() - 1;
                for (int i = searchMatchList.size() - 1; i >= 0; i--) {
                    if (searchMatchList.get(i) <= currentModel) { searchMatchCursor = i; break; }
                }
            }
        } else {
            searchMatchCursor = (searchMatchCursor + direction + searchMatchList.size()) % searchMatchList.size();
        }
        int modelRow = searchMatchList.get(searchMatchCursor);
        int viewRow  = logTable.convertRowIndexToView(modelRow);
        if (viewRow >= 0) {
            logTable.setRowSelectionInterval(viewRow, viewRow);
            logTable.scrollRectToVisible(logTable.getCellRect(viewRow, 0, true));
            findCounter.setText((searchMatchCursor + 1) + " / " + searchMatchList.size());
        }
    }

    // ── Level color palette ───────────────────────────────────────────────────
    // Colors are read from UIManager via ClouseauColors at paint time.

    private static String colorToHex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(
                (int)(a.getRed()   * t + b.getRed()   * (1 - t)),
                (int)(a.getGreen() * t + b.getGreen() * (1 - t)),
                (int)(a.getBlue()  * t + b.getBlue()  * (1 - t))
        );
    }

    // ── Highlight palette ─────────────────────────────────────────────────────
    // Colors are read from UIManager via ClouseauColors.highlightColors() at use time.

    // ── Detail panel ─────────────────────────────────────────────────────────

    private void refreshDetail() {
        int viewRow = logTable.getSelectedRow();
        int modelRow = viewRow >= 0 ? logTable.convertRowIndexToModel(viewRow) : -1;
        showDetail(modelRow >= 0 ? logTableModel.getEntry(modelRow) : null, modelRow);
    }

    private void flashMessageField() {
        if (messageFieldStart < 0 || messageFieldEnd <= messageFieldStart) return;
        final Color peak = ClouseauColors.flashPeakColor();
        final Color bg   = ClouseauColors.detailBackground();
        final int   steps = 25;
        final int[] step  = {0};
        final Object[] tag = {null};

        Highlighter hl = detailArea.getHighlighter();
        try {
            tag[0] = hl.addHighlight(messageFieldStart, messageFieldEnd,
                    new DefaultHighlighter.DefaultHighlightPainter(peak));
        } catch (BadLocationException ignored) { return; }

        Timer timer = new Timer(16, null);
        timer.addActionListener(e -> {
            step[0]++;
            hl.removeHighlight(tag[0]);
            if (step[0] >= steps) {
                ((Timer) e.getSource()).stop();
            } else {
                float t = 1f - (float) step[0] / steps;
                try {
                    tag[0] = hl.addHighlight(messageFieldStart, messageFieldEnd,
                            new DefaultHighlighter.DefaultHighlightPainter(blend(peak, bg, t)));
                } catch (BadLocationException ignored) {}
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    private JPanel buildDetailToolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ClouseauColors.separatorColor()));

        // ── Left: formatter / syntax highlight toggles ───────────────────────────────
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        controls.setOpaque(false);

        formatterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        formatterPanel.setOpaque(false);
        formatterPanel.setVisible(false);
        if (!formatters.isEmpty()) {
            JLabel label = new JLabel(Messages.get("detail.toolbar.format"));
            label.setForeground(ClouseauColors.dimForeground());
            label.setFont(label.getFont().deriveFont(11f));
            formatterPanel.add(label);

            for (LogFormatter formatter : formatters) {
                JToggleButton btn = new JToggleButton(formatter.getName());
                btn.setSelected(true);
                btn.setEnabled(false);
                btn.setFont(btn.getFont().deriveFont(11f));
                FilterBar.applyToggleStyle(btn);
                btn.addActionListener(e -> {
                    if (btn.isSelected()) disabledFormatters.remove(formatter.getName());
                    else                  disabledFormatters.add(formatter.getName());
                    refreshDetail();
                });
                formatterPanel.add(btn);
                formatterBtnList.add(btn);
            }
        }
        controls.add(formatterPanel);

        syntaxHighlightPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        syntaxHighlightPanel.setOpaque(false);
        syntaxHighlightPanel.setVisible(false);
        if (!syntaxHighlighters.isEmpty()) {
            if (!formatters.isEmpty()) {
                JSeparator sep = new JSeparator(JSeparator.VERTICAL);
                sep.setPreferredSize(new Dimension(1, 18));
                syntaxHighlightPanel.add(sep);
            }
            JLabel label = new JLabel(Messages.get("detail.toolbar.color"));
            label.setForeground(ClouseauColors.dimForeground());
            label.setFont(label.getFont().deriveFont(11f));
            syntaxHighlightPanel.add(label);

            for (LogSyntaxHighlighter hl : syntaxHighlighters) {
                JToggleButton btn = new JToggleButton(hl.getName());
                btn.setSelected(true);
                btn.setEnabled(false);
                btn.setFont(btn.getFont().deriveFont(11f));
                FilterBar.applyToggleStyle(btn);
                btn.addActionListener(e -> refreshDetail());
                syntaxHighlightPanel.add(btn);
                syntaxHighlightBtnList.add(btn);
            }
        }
        controls.add(syntaxHighlightPanel);

        bar.add(controls, BorderLayout.CENTER);

        // ── Right: font size + copy button ───────────────────────────────────
        copyBtn = new JButton(Messages.get("detail.toolbar.copy"));
        copyBtn.setFont(copyBtn.getFont().deriveFont(11f));
        copyBtn.setMargin(new Insets(2, 6, 2, 6));
        copyBtn.setEnabled(false);
        copyBtn.addActionListener(e -> {
            if (lastFormattedMessage.isEmpty()) return;
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(lastFormattedMessage), null);
            flashMessageField();
        });

        JButton fontDecBtn = new JButton("A\u207B");
        JButton fontIncBtn = new JButton("A\u207A");
        for (JButton btn : new JButton[]{fontDecBtn, fontIncBtn}) {
            btn.setFont(btn.getFont().deriveFont(11f));
            btn.setMargin(new Insets(2, 5, 2, 5));
        }
        fontDecBtn.setToolTipText("Decrease font size");
        fontIncBtn.setToolTipText("Increase font size");
        fontDecBtn.addActionListener(e -> {
            int sz = Math.max(8, AppPrefs.getDetailFontSize() - 1);
            AppPrefs.setDetailFontSize(sz);
            refreshDetail();
        });
        fontIncBtn.addActionListener(e -> {
            int sz = Math.min(32, AppPrefs.getDetailFontSize() + 1);
            AppPrefs.setDetailFontSize(sz);
            refreshDetail();
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 3));
        right.setOpaque(false);
        right.add(fontDecBtn);
        right.add(fontIncBtn);
        JSeparator fontSep = new JSeparator(JSeparator.VERTICAL);
        fontSep.setPreferredSize(new Dimension(1, 18));
        right.add(fontSep);
        right.add(copyBtn);
        bar.add(right, BorderLayout.EAST);

        return bar;
    }


    private static void disableDetailBtn(JToggleButton btn) {
        if (btn.isEnabled()) {
            btn.putClientProperty("savedSelected", btn.isSelected());
        }
        btn.setSelected(false);
        btn.setEnabled(false);
    }

    private static void enableDetailBtn(JToggleButton btn) {
        if (!btn.isEnabled()) {
            btn.setEnabled(true);
            Boolean saved = (Boolean) btn.getClientProperty("savedSelected");
            btn.setSelected(saved != null ? saved : true);
        }
    }

    private JPanel buildDetail() {
        detailArea = new JTextPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return wrapLines;
            }
        };
        detailArea.setEditable(false);
        detailArea.setBackground(ClouseauColors.detailBackground());
        detailArea.setForeground(ClouseauColors.foreground());
        detailArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JPanel toolbar = buildDetailToolbar();
        showDetail(null, -1);
        JScrollPane scroll = new JScrollPane(detailArea);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private static final DateTimeFormatter DETAIL_TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /** Apply all formatters that can handle this input, in sequence. */
    private String applyFormatters(String input) {
        String result = input;
        log.trace("applyFormatters: {} formatters available, input length={}", formatters.size(), input.length());
        for (LogFormatter formatter : formatters) {
            boolean canFormat = formatter.canFormat(result);
            log.trace("  {} canFormat={}", formatter.getName(), canFormat);
            if (canFormat && !disabledFormatters.contains(formatter.getName())) {
                try {
                    result = formatter.format(result);
                    log.trace("  {} formatted successfully, new length={}", formatter.getName(), result.length());
                } catch (Exception e) {
                    log.warn("Formatter {} failed", formatter.getName(), e);
                }
            }
        }
        return result;
    }

    private void showDetail(LogEntry entry, int modelRow) {
        formatterPanel.setVisible(entry != null && !formatters.isEmpty());
        StyledDocument doc = detailArea.getStyledDocument();
        try { doc.remove(0, doc.getLength()); } catch (BadLocationException ignored) {}

        int fontSize = AppPrefs.getDetailFontSize();

        SimpleAttributeSet key = new SimpleAttributeSet();
        StyleConstants.setFontFamily(key, Font.MONOSPACED);
        StyleConstants.setFontSize(key, fontSize);
        StyleConstants.setBold(key, true);
        StyleConstants.setForeground(key, ClouseauColors.detailKeyColor());

        SimpleAttributeSet val = new SimpleAttributeSet();
        StyleConstants.setFontFamily(val, Font.MONOSPACED);
        StyleConstants.setFontSize(val, fontSize);
        StyleConstants.setForeground(val, ClouseauColors.foreground());

        if (entry == null) {
            lastFormattedMessage = "";
            messageFieldStart = messageFieldEnd = -1;
            formatterPanel.setVisible(false);
            syntaxHighlightPanel.setVisible(false);
            insertText(doc, Messages.get("detail.placeholder"), val);
            detailArea.setCaretPosition(0);
            return;
        }

        SimpleAttributeSet levelVal = new SimpleAttributeSet(val);
        StyleConstants.setForeground(levelVal, ClouseauColors.levelColor(entry.level()));
        StyleConstants.setBold(levelVal, true);

        String ts     = entry.timestamp() != null ? DETAIL_TS_FMT.format(entry.timestamp()) : "";
        String level  = entry.level()     != null ? entry.level().name()  : "";
        String thread = entry.thread()    != null ? entry.thread()        : "";
        String logger = entry.logger()    != null ? entry.logger()        : "";

        Color hl = logTableModel.getHighlight(entry);
        if (hl != null) {
            SimpleAttributeSet swatchStyle = new SimpleAttributeSet();
            StyleConstants.setFontFamily(swatchStyle, Font.MONOSPACED);
            StyleConstants.setFontSize(swatchStyle, fontSize);
            StyleConstants.setForeground(swatchStyle, hl);
            appendField(doc, key, swatchStyle, Messages.get("detail.highlight.label"), "\u2588\u2588\u2588\u2588\u2588\u2588");
        }

        appendField(doc, key, val,      Messages.get("table.col.timestamp"), ts);
        appendField(doc, key, levelVal, Messages.get("table.col.level"),     level);
        appendField(doc, key, val,      Messages.get("table.col.thread"),    thread);
        appendField(doc, key, val,      Messages.get("table.col.logger"),    logger);

        if (entry.fields() != null) {
            entry.fields().forEach((k, v) -> appendField(doc, key, val, k, v));
        }

        formatterPanel.setVisible(!formatters.isEmpty());
        syntaxHighlightPanel.setVisible(!syntaxHighlighters.isEmpty());

        String rawMsg = entry.message() != null ? entry.message() : "";

        // Fold continuation lines (HL7 segments, stack traces, wrapped lines) into the message
        // so they are included in formatting, syntax highlighting, and the copy action.
        if (modelRow >= 0) {
            List<LogEntry> continuations = logTableModel.getContinuationLines(modelRow);
            if (!continuations.isEmpty()) {
                StringBuilder sb = new StringBuilder(rawMsg);
                for (LogEntry c : continuations) sb.append("\n").append(c.rawLine());
                rawMsg = sb.toString();
            }
        }

        for (int i = 0; i < formatters.size(); i++) {
            if (formatters.get(i).canFormat(rawMsg)) enableDetailBtn(formatterBtnList.get(i));
            else                                     disableDetailBtn(formatterBtnList.get(i));
        }

        String messageText = applyFormatters(rawMsg);
        lastFormattedMessage = messageText;

        for (int i = 0; i < syntaxHighlighters.size(); i++) {
            if (syntaxHighlighters.get(i).canHighlight(messageText)) enableDetailBtn(syntaxHighlightBtnList.get(i));
            else                                                      disableDetailBtn(syntaxHighlightBtnList.get(i));
        }

        LogSyntaxHighlighter colorizer = null;
        for (int i = 0; i < syntaxHighlighters.size(); i++) {
            if (syntaxHighlighters.get(i).canHighlight(messageText) && syntaxHighlightBtnList.get(i).isSelected()) {
                colorizer = syntaxHighlighters.get(i);
                break;
            }
        }
        String msgLabel = String.format("%-12s ", Messages.get("table.col.message") + ":");
        messageFieldStart = doc.getLength() + msgLabel.length();
        if (colorizer != null) {
            renderSyntaxHighlightedField(doc, key, val, Messages.get("table.col.message"), messageText, colorizer);
        } else {
            appendField(doc, key, val, Messages.get("table.col.message"), messageText);
        }
        messageFieldEnd = doc.getLength() - 1; // exclude trailing newline

//        if (entry.rawLine() != null) {
//            insertText(doc, "\n" + "─".repeat(60) + "\n", val);
//            insertText(doc, Messages.get("detail.raw.label") + "\n", key);
//            insertText(doc, entry.rawLine(), msgVal);
//        }

        detailArea.setCaretPosition(0);
    }

    private static void appendField(StyledDocument doc, SimpleAttributeSet keyStyle,
                                    SimpleAttributeSet valStyle, String label, String value) {
        insertText(doc, String.format("%-12s ", label + ":"), keyStyle);
        insertText(doc, value + "\n", valStyle);
    }

    private static void renderSyntaxHighlightedField(StyledDocument doc, SimpleAttributeSet keyStyle,
                                             SimpleAttributeSet baseStyle, String label,
                                             String text, LogSyntaxHighlighter colorizer) {
        insertText(doc, String.format("%-12s ", label + ":"), keyStyle);
        List<LogSyntaxHighlighter.ColorSpan> spans;
        try {
            spans = colorizer.highlight(text);
        } catch (Exception e) {
            insertText(doc, text + "\n", baseStyle);
            return;
        }
        int pos = 0;
        for (LogSyntaxHighlighter.ColorSpan span : spans) {
            if (span.start() > pos) {
                insertText(doc, text.substring(pos, span.start()), baseStyle);
            }
            SimpleAttributeSet spanStyle = new SimpleAttributeSet(baseStyle);
            StyleConstants.setForeground(spanStyle, new Color(span.rgb()));
            insertText(doc, text.substring(span.start(), span.end()), spanStyle);
            pos = span.end();
        }
        if (pos < text.length()) {
            insertText(doc, text.substring(pos), baseStyle);
        }
        insertText(doc, "\n", baseStyle);
    }

    private static void insertText(StyledDocument doc, String text, SimpleAttributeSet style) {
        try { doc.insertString(doc.getLength(), text, style); } catch (BadLocationException ignored) {}
    }
}
