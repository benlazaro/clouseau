package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSource;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.core.AnnotationStore;
import com.droidenx.clouseau.core.CopyingInputStream;
import com.droidenx.clouseau.core.EntryIndex;
import com.droidenx.clouseau.core.FileFollower;
import com.droidenx.clouseau.core.IndexedEntries;
import com.droidenx.clouseau.core.LineParser;
import com.droidenx.clouseau.core.LineScanner;
import com.droidenx.clouseau.core.LogCache;
import com.droidenx.clouseau.core.LogFileReader;
import com.droidenx.clouseau.core.LogReaders;
import com.droidenx.clouseau.core.MemoryGuard;
import com.droidenx.clouseau.core.ReviewBundle;
import com.droidenx.clouseau.core.ZipLogs;
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
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Self-contained panel representing one open log file tab.
 * Owns its own LogTableModel, table and detail pane.
 */
@Slf4j
public final class LogPanel extends JPanel {

    private final List<LogParser> parsers;
    private final List<LogFormatter> formatters;
    private final List<LogSyntaxHighlighter> syntaxHighlighters;
    private final LogTableModel   logTableModel = new LogTableModel();
    private final FilterBar       filterBar;
    private JTable logTable;
    private DetailPanel detailPanel;
    private boolean tableColumnsManaged = false;
    private int fittedColumnsWidth = 0;
    /** model-column-index → hidden TableColumn, in insertion order for restore positioning */
    private final Map<Integer, TableColumn> hiddenColumns = new LinkedHashMap<>();
    /** Name of the layout most recently applied via applyColumnLayout; null if none or modified since. */
    private String activeLayoutName = null;
    private JScrollPane tableScrollPane;
    private volatile SwingWorker<?, ?> currentWorker;
    private volatile SwingWorker<Void, LogEntry> tailWorker;
    /** The live source streaming into this tab (SSH or a plugin), or null. */
    private volatile LogSource liveSource;
    private Path currentFile;
    /** The file inside a .zip that this tab shows, or null for a plain file (G-6). */
    private String currentEntry;
    /** The decompressed copy of a compressed log this tab reads rows from, or null (D-16). */
    private Path cacheCopy;
    private Optional<LogParser> currentParser = Optional.empty();
    private volatile long tailPosition = 0;
    /**
     * Held by the tail worker while it follows. A cancelled worker may still be finishing a
     * read; the next one waits for it, so only one thread ever adds to the index.
     */
    private final java.util.concurrent.locks.ReentrantLock tailLock = new java.util.concurrent.locks.ReentrantLock();
    /** True once the followed file was truncated in place, so older rows lost their text (D-16). */
    private boolean rotatedTextLost = false;
    private boolean follow  = AppPrefs.isFollowByDefault();
    private boolean loading = false;
    /** True when the last static load stopped early because memory ran low (UC-1). */
    private boolean partialLoad = false;
    /** Why the load was partial: the disk filled while unpacking, rather than low memory. */
    private boolean partialDiskFull = false;
    /**
     * Follow mode reads as soon as the OS reports a change; this is only the backstop for
     * file systems that send no notifications (some network drives).
     */
    private static final long FOLLOW_SAFETY_POLL_MS = 2_000;
    /** The running filter's progress while it reads text from the file, or -1. */
    private int filterPercent = -1;
    private AnnotationStore  annotationStore   = AnnotationStore.inMemory();
    /** True once this tab has told the user its annotations couldn't be saved. */
    private boolean          annotationSaveWarned;
    private JDialog          annotationsDialog;
    private AnnotationsPanel annotationsPanel;
    private Set<Integer>  searchMatchModelRows = Set.of();
    private List<Integer> searchMatchList      = List.of();
    private int           searchMatchCursor    = -1;
    private JPanel        findBar;
    private JTextField    findField;
    private JLabel        findCounter;
    private JPanel centerCards;
    private static final String CARD_CONTENT = "content";
    private static final String CARD_LOADING = "loading";
    private ZoneId sourceTimezone = ZoneId.systemDefault();
    private JPanel statusBar;
    private JLabel statusBarLabel;
    private JLabel statusBarPathLabel;
    private JLabel sourceTzLabel;
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
        fbHolder[0] = new FilterBar(() -> logTableModel.applyFilter(fbHolder[0].buildFilter()));
        this.filterBar = fbHolder[0];
        filterBar.initFollow(follow, this::setFollow);
        logTableModel.setFilterProgressListener(percent -> {
            filterPercent = percent;
            if (statusBarLabel != null) updateStatusBar();
        });
        logTableModel.applyFilter(filterBar.buildFilter());

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildLogTable(),
                detailPanel = new DetailPanel(formatters, syntaxHighlighters, this::currentDetail));
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
                refreshDetail();
                updateHighlightNavPosition();
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

        // Ctrl+Shift+N: open the annotations panel
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "showAnnotations");
        getActionMap().put("showAnnotations", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { showAnnotationsDialog(); }
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

    /** The entry of the zip this tab shows, or null for a plain file. */
    public String getZipEntry() { return currentEntry; }

    /**
     * The tab's annotations and highlights in the sidecar format, for a review bundle
     * (D-10), or null while the file is still loading (its notes aren't read yet).
     */
    public String annotationsJson() { return loading ? null : annotationStore.toJson(); }

    public void load(Path file, Optional<LogParser> parser) {
        load(file, null, parser, () -> {});
    }

    public void load(Path file, Optional<LogParser> parser, Runnable onError) {
        load(file, null, parser, onError);
    }

    /**
     * Loads a log. {@code entry} names a file inside the zip {@code file}, or is null for a
     * plain (or .gz) file.
     */
    public void load(Path file, String entry, Optional<LogParser> parser, Runnable onError) {
        currentFile = file.toAbsolutePath();
        currentEntry = entry;
        currentParser = parser;
        String shown = entry == null ? currentFile.toString() : currentFile + " › " + entry;
        statusBarPathLabel.setText(shown);
        statusBarPathLabel.setToolTipText(shown);
        final Path annotationKey = entry == null ? currentFile : ZipLogs.entryKey(currentFile, entry);
        stopTailing();
        SwingWorker<?, ?> old = currentWorker;
        if (old != null) old.cancel(true);
        loading = true;
        annotationStore = AnnotationStore.inMemory();
        annotationSaveWarned = false;
        partialLoad = false;
        rotatedTextLost = false;
        logTableModel.clear();
        deleteCacheCopy();
        filterBar.clearLoggers();
        hiddenColumns.clear();
        activeLayoutName = null;
        logTableModel.setCustomFields(parser.map(com.droidenx.clouseau.api.LogParser::customFields).orElse(List.of()));
        logTableModel.beginBatchLoad();

        ((CardLayout) centerCards.getLayout()).show(centerCards, CARD_LOADING);

        final ZoneId srcTz = this.sourceTimezone; // capture before worker starts

        final MemoryGuard memory = new MemoryGuard();

        // D-16: the file is indexed instead of held in memory. A compressed one is unpacked
        // to a copy in the cache folder during the same pass, and rows are read from that.
        final boolean compressed = entry != null || LogReaders.isCompressed(file);
        final Path copy = compressed ? newCacheCopy(file, entry) : null;
        final Path rowSource = compressed ? copy : file;
        final EntryIndex index = new EntryIndex();
        if (rowSource != null) {
            try {
                logTableModel.beginIndexedLoad(IndexedEntries.open(rowSource, index,
                        new LineParser(parser, parsers, srcTz), Messages.get("table.rotated.text")));
                cacheCopy = copy;
            } catch (IOException ex) {
                log.warn("Can't open {} for indexing; it will be loaded into memory", rowSource, ex);
                LogCache.delete(copy);
            }
        }
        final boolean indexed = logTableModel.isIndexed();

        SwingWorker<Void, LogEntry> worker = new SwingWorker<>() {
            /** Lines read before loading stopped for lack of memory or disk, or -1 if it didn't. */
            volatile long stoppedAtLine = -1;
            /** True if loading stopped because the unpacked copy filled the disk. */
            volatile boolean diskFull;
            /** Annotations read and anchored by the worker; handed to the tab in done(). */
            volatile AnnotationStore loadedAnnotations;
            /** Bytes of a plain file the index covers, where following resumes; -1 if not indexed. */
            volatile long indexedTo = -1;

            @Override
            protected Void doInBackground() throws Exception {
                // A review bundle's log starts from the bundle's notes until its own sidecar exists (D-10).
                AnnotationStore annotations = ReviewBundle.loadAnnotations(file, entry, annotationKey, AppDirs.annotationsDir());
                AnnotationStore.Anchoring anchoring = annotations.startAnchoring();
                LineParser lineParser = new LineParser(parser, parsers, srcTz);
                if (indexed) {
                    indexFile(lineParser, anchoring);
                } else try (BufferedReader reader = LogReaders.open(file, entry)) {
                    LogFileReader.Result result = LogFileReader.read(reader, lineParser,
                            LogFileReader.DEFAULT_CHECK_INTERVAL, memory::isLow, this::isCancelled,
                            e -> {
                                if (anchoring != null) anchoring.accept(e.rawLine());
                                publish(e);
                            });
                    if (result.partial()) {
                        stoppedAtLine = result.stoppedAtLine();
                        log.warn("Low memory: stopped loading {} after {} lines (max heap {} MiB)",
                                file, stoppedAtLine, memory.maxMiB());
                    }
                }
                if (anchoring != null) anchoring.finish();
                loadedAnnotations = annotations;
                return null;
            }

            /**
             * Reads the file once into the index. Each entry is still published, so the
             * table can filter it and the filter bar can learn its logger, but only the
             * index keeps it.
             */
            private void indexFile(LineParser lineParser, AnnotationStore.Anchoring anchoring) throws IOException {
                long[] count = {0};
                boolean[] lowMemory = {false};
                InputStream source = compressed
                        ? new CopyingInputStream(LogReaders.openBytes(file, entry), Files.newOutputStream(rowSource))
                        : Files.newInputStream(file);
                try (InputStream in = source) {
                    long read = LineScanner.scan(in, (offset, length, text) -> {
                        LogEntry e = lineParser.parse(text);
                        index.add(offset, length, e);
                        if (anchoring != null) anchoring.accept(e.rawLine());
                        publish(e);
                        if (++count[0] % LogFileReader.DEFAULT_CHECK_INTERVAL == 0 && memory.isLow()) {
                            lowMemory[0] = true;
                        }
                    }, () -> lowMemory[0] || isCancelled());
                    // Follow from exactly where the index ends, not the size at the end of the
                    // load: lines written in between would be skipped.
                    if (!compressed) indexedTo = read;
                }
                if (source instanceof CopyingInputStream c && c.writeFailure() != null && !isCancelled()) {
                    diskFull = true;
                    stoppedAtLine = count[0];
                    log.warn("Stopped unpacking {} to {} after {} entries", file, rowSource, stoppedAtLine,
                            c.writeFailure());
                } else if (lowMemory[0]) {
                    stoppedAtLine = count[0];
                    log.warn("Low memory: stopped indexing {} after {} entries (max heap {} MiB)",
                            file, stoppedAtLine, memory.maxMiB());
                }
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
                    if (loadedAnnotations != null) annotationStore = loadedAnnotations;
                    applySavedHighlights();
                    autoResizeColumns();
                    applyDefaultLayoutIfSet();
                    logTable.repaint();
                    if (stoppedAtLine >= 0) {
                        // Partial content: following would append after a gap, so don't.
                        partialLoad = true;
                        partialDiskFull = diskFull;
                        updateStatusBar();
                        String message = diskFull
                                ? java.text.MessageFormat.format(Messages.get("load.diskfull.message"),
                                        file.getFileName().toString(), stoppedAtLine, AppDirs.cacheDir().toString())
                                : java.text.MessageFormat.format(Messages.get("load.lowmemory.message"),
                                        file.getFileName().toString(), stoppedAtLine, memory.maxMiB());
                        JOptionPane.showMessageDialog(
                                SwingUtilities.getWindowAncestor(LogPanel.this),
                                message,
                                Messages.get("load.lowmemory.title"),
                                JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    if (indexedTo >= 0) tailPosition = indexedTo;
                    else try { tailPosition = Files.size(currentFile); } catch (IOException ignored) {}
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
        if (currentFile != null) load(currentFile, currentEntry, currentParser, () -> {});
    }

    public void cancelLoad() {
        stopTailing();
        SwingWorker<?, ?> w = currentWorker;
        if (w != null) w.cancel(true);
        LogSource s = liveSource;
        liveSource = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception | LinkageError e) {
                log.warn("Error closing source {}", s.getClass().getName(), e);
            }
        }
    }

    /**
     * Streams a live source (SSH, or one from a plugin) into this tab: {@code source.open}
     * runs on a background worker, and each line is parsed with {@code parser} (or
     * auto-detected) and appended as a live entry, subject to the entry cap. Rows appear
     * as they arrive. Closing the tab closes the source.
     */
    public void loadSource(LogSource source, Optional<LogParser> parser) {
        String name = sourceName(source);
        currentFile   = null;
        currentEntry  = null;
        currentParser = parser;
        statusBarPathLabel.setText(name);
        statusBarPathLabel.setToolTipText(name);
        cancelLoad();   // closes any prior source, cancels in-flight workers
        partialLoad = false;
        annotationStore = AnnotationStore.inMemory();

        logTableModel.clear();
        filterBar.clearLoggers();
        hiddenColumns.clear();
        activeLayoutName = null;
        logTableModel.setCustomFields(parser.map(com.droidenx.clouseau.api.LogParser::customFields).orElse(List.of()));
        logTableModel.beginBatchLoad();

        ((CardLayout) centerCards.getLayout()).show(centerCards, CARD_LOADING);

        liveSource = source;

        SwingWorker<Void, LogEntry> worker = new SwingWorker<>() {
            boolean firstBatch = true;

            @Override
            protected Void doInBackground() throws Exception {
                LineParser lineParser = new LineParser(parser, parsers, null);
                source.open(line -> {
                    if (line == null || line.isBlank() || isCancelled()) return;
                    publish(lineParser.parse(line));
                });
                return null;
            }

            @Override
            protected void process(List<LogEntry> chunk) {
                if (isCancelled()) return;
                int columnsBefore = logTableModel.getColumnCount();
                logTableModel.appendLive(chunk);
                if (firstBatch) {
                    // Size columns once the first rows (and their fields) are in, so the
                    // fit uses real content and field columns aren't pushed off-screen.
                    firstBatch = false;
                    logTableModel.endBatchLoad();
                    ((CardLayout) centerCards.getLayout()).show(centerCards, CARD_CONTENT);
                    applyDefaultLayoutIfSet();
                    autoResizeColumns();
                } else if (logTableModel.getColumnCount() != columnsBefore) {
                    autoResizeColumns();   // a later line brought a new field column
                }
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
                    log.error("Source error for {}", name, cause);
                    String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                    JOptionPane.showMessageDialog(
                            SwingUtilities.getWindowAncestor(LogPanel.this),
                            message,
                            Messages.get("source.error.title"),
                            JOptionPane.ERROR_MESSAGE);
                } catch (Exception ignored) {}
            }
        };
        currentWorker = worker;
        worker.execute();
    }

    /** The source's name, or its class name if a plugin's {@code getName} fails. */
    private static String sourceName(LogSource source) {
        try {
            String name = source.getName();
            if (name != null && !name.isBlank()) return name;
        } catch (RuntimeException e) {
            log.warn("getName() failed for source {}", source.getClass().getName(), e);
        }
        return source.getClass().getSimpleName();
    }

    private void startTailing() {
        stopTailing();
        if (currentFile == null || LogReaders.isCompressed(currentFile)) return;
        final Optional<LogParser> parser = currentParser;
        final Path file = currentFile;
        final ZoneId srcTz = this.sourceTimezone; // capture before worker starts
        // D-16: on an indexed tab, followed lines extend the index and are never trimmed.
        final IndexedEntries indexed = logTableModel.indexedEntries();
        tailWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                LineParser lineParser = new LineParser(parser, parsers, srcTz);
                try {
                    tailLock.lockInterruptibly();
                } catch (InterruptedException e) {
                    return null;                    // cancelled while the last worker finished
                }
                try {
                    if (isCancelled()) return null;
                    FileFollower.follow(file, tailPosition, new FileFollower.Listener() {
                        @Override
                        public void line(long offset, int length, String text) {
                            LogEntry e = lineParser.parse(text);
                            if (indexed != null) indexed.index().add(offset, length, e);
                            publish(e);
                        }

                        @Override
                        public void rotated(FileFollower.Rotation how) {
                            log.info("{} was rotated ({})", file, how);
                            if (indexed != null && indexed.rotated()) {
                                SwingUtilities.invokeLater(() -> {
                                    if (logTableModel.indexedEntries() != indexed) return;   // cleared
                                    rotatedTextLost = true;
                                    logTable.repaint();
                                    refreshDetail();
                                    updateStatusBar();
                                });
                            }
                        }

                        @Override
                        public void position(long position) {
                            if (!isCancelled()) tailPosition = position;
                        }
                    }, this::isCancelled, FOLLOW_SAFETY_POLL_MS);
                } catch (InterruptedException e) {
                    // cancel(true) interrupts the wait: following stopped
                } finally {
                    tailLock.unlock();
                }
                return null;
            }

            @Override
            protected void process(List<LogEntry> entries) {
                // Rows already in the index must reach the model even after a cancel, or
                // the model's count would fall behind the index for good.
                if (isCancelled() && indexed == null) return;
                if (indexed != null && logTableModel.indexedEntries() != indexed) return;  // cleared
                filterBar.addBatch(entries);
                logTableModel.appendFollowed(entries);
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
        deleteCacheCopy();
    }

    /**
     * Creates the file a compressed log is unpacked to, or returns null (the log is then
     * loaded into memory, as before D-16) if its unpacked size is known and the disk
     * doesn't have room for it, or the file can't be created.
     */
    private static Path newCacheCopy(Path file, String entry) {
        Path dir = AppDirs.cacheDir();
        try {
            long size = LogReaders.uncompressedSize(file, entry);
            if (!LogCache.hasRoom(dir, size)) {
                log.warn("Not enough free space in {} to unpack {} ({} bytes); loading it into memory",
                        dir, file, size);
                return null;
            }
            return LogCache.newCopy(dir, file, entry);
        } catch (IOException ex) {
            log.warn("Can't create a copy of {} in {}; loading it into memory", file, dir, ex);
            return null;
        }
    }

    /**
     * Deletes this tab's unpacked copy. Call after the model has closed its channel. The
     * load worker may still be writing it; files are opened with delete sharing, so the
     * copy goes when the last handle closes (and at the next start if it doesn't).
     */
    private void deleteCacheCopy() {
        LogCache.delete(cacheCopy);
        cacheCopy = null;
    }

    public JTable getLogTable() { return logTable; }

    public void applyDetailFontSize(int size) {
        refreshDetail();
    }

    public void applyWrapLines(boolean wrap) {
        detailPanel.setWrapLines(wrap);
    }

    // ── Source timezone ───────────────────────────────────────────────────────

    public ZoneId getSourceTimezone() { return sourceTimezone; }

    /** Sets the source timezone field and updates the status bar badge. Does NOT reload. */
    public void setSourceTimezone(ZoneId zone) {
        this.sourceTimezone = zone;
        updateSourceTimezoneLabel();
    }

    /** Sets the source timezone, persists it for the current file, then reloads. */
    public void applySourceTimezone(ZoneId zone) {
        this.sourceTimezone = zone;
        if (currentFile != null) AppPrefs.setSourceTimezone(currentFile, zone);
        updateSourceTimezoneLabel();
        reload();
    }

    /** Shows a timezone picker dialog; on confirmation calls {@link #applySourceTimezone}. */
    public void showSourceTimezoneDialog(Component parent) {
        JComboBox<SettingsDialog.TimezoneEntry> combo = SettingsDialog.buildTimezoneCombo(sourceTimezone);
        JLabel note = new JLabel(Messages.get("source.timezone.dialog.reload.note"));
        note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
        Object[] message = { new JLabel(Messages.get("source.timezone.dialog.label")), combo, note };
        int res = JOptionPane.showConfirmDialog(
                parent, message,
                Messages.get("source.timezone.dialog.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            SettingsDialog.TimezoneEntry sel = (SettingsDialog.TimezoneEntry) combo.getSelectedItem();
            if (sel != null) applySourceTimezone(sel.zone());
        }
    }

    private void updateSourceTimezoneLabel() {
        if (sourceTzLabel == null) return;
        String id = sourceTimezone.getId();
        sourceTzLabel.setText("[src: " + id + "]  ");
        boolean isSystem = sourceTimezone.equals(ZoneId.systemDefault());
        sourceTzLabel.setToolTipText(isSystem
                ? "Source timezone: " + id + " (system default) — click to change"
                : "Source timezone: " + id + " — click to change");
    }

    /**
     * Adjusts a parsed entry's timestamp from the parser's assumed zone (systemDefault) to
     * the configured source timezone. Only applied when they differ and the entry has a timestamp.
     */
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

        sourceTzLabel = new JLabel();
        sourceTzLabel.setForeground(UIManager.getColor("Button.foreground"));
        sourceTzLabel.setFont(sourceTzLabel.getFont().deriveFont(12f));
        sourceTzLabel.setBorder(BorderFactory.createEmptyBorder(6, 0, 8, 0));
        sourceTzLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sourceTzLabel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                showSourceTimezoneDialog(SwingUtilities.getWindowAncestor(LogPanel.this));
            }
        });
        updateSourceTimezoneLabel();

        JPanel eastPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        eastPanel.setOpaque(false);
        eastPanel.add(sourceTzLabel);
        eastPanel.add(statusBarLabel);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ClouseauColors.separatorColor()));
        panel.add(statusBarPathLabel, BorderLayout.WEST);
        panel.add(eastPanel, BorderLayout.EAST);
        return panel;
    }

    public void clearTable() {
        int confirm = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                Messages.get("table.clear.confirm.message"),
                Messages.get("table.clear.confirm.title"),
                JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        // A plain file stays indexed: what is followed from here on starts a new index.
        boolean reindex = logTableModel.isIndexed() && cacheCopy == null && currentFile != null;
        stopTailing();
        try { tailPosition = currentFile != null ? Files.size(currentFile) : 0; } catch (IOException ignored) { tailPosition = 0; }
        logTableModel.clear();
        rotatedTextLost = false;
        if (reindex) {
            try {
                logTableModel.beginIndexedLoad(IndexedEntries.open(currentFile, new EntryIndex(),
                        new LineParser(currentParser, parsers, sourceTimezone), Messages.get("table.rotated.text")));
            } catch (IOException ex) {
                log.warn("Can't open {} for indexing; followed lines will be held in memory", currentFile, ex);
            }
        }
        refreshDetail();
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
        if (partialLoad) text += Messages.get(partialDiskFull ? "status.partial.diskfull" : "status.partial");
        if (rotatedTextLost) text += Messages.get("status.rotated");
        if (filterPercent >= 0) text += java.text.MessageFormat.format(Messages.get("status.filtering"), filterPercent);
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
                int rowModel = logTable.convertRowIndexToModel(r);
                LogEntry entry = logTableModel.getEntry(rowModel);
                Color hl = logTableModel.getHighlight(rowModel);
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

                // Annotation indicator in the # column
                if (modelCol == 0 && annotationStore.has(logTableModel.ordinalAt(rowModel))) {
                    setText("\u270e " + getText());
                    if (!sel) setForeground(ClouseauColors.annotationIndicatorColor());
                }

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
                List<Integer> modelRows = new ArrayList<>();
                if (viewRow >= 0) {
                    if (!logTable.isRowSelected(viewRow)) logTable.setRowSelectionInterval(viewRow, viewRow);
                    for (int vr : logTable.getSelectedRows()) {
                        int mr = logTable.convertRowIndexToModel(vr);
                        if (logTableModel.getEntry(mr) != null) modelRows.add(mr);
                    }
                }
                showHighlightMenu(modelRows, e.getX(), e.getY());
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
            if (logTable.getColumnModel().getColumn(col).getModelIndex() == 0) {
                // Room for the note marker on any row, not just the sampled ones.
                FontMetrics fm = logTable.getFontMetrics(logTable.getFont().deriveFont(Font.BOLD));
                maxWidth = Math.max(maxWidth, fm.stringWidth("✎ " + logTable.getRowCount()) + 16);
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

    private void showHighlightMenu(List<Integer> entries, int x, int y) {
        boolean anyHighlighted = entries.stream().anyMatch(r -> logTableModel.getHighlight(r) != null);

        JPopupMenu menu = new JPopupMenu();

        if (!entries.isEmpty()) {
            JPanel swatches = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
            swatches.setOpaque(false);
            Color[] palette = ClouseauColors.highlightColors();
            for (int slotIndex = 0; slotIndex < palette.length; slotIndex++) {
                final Color color = palette[slotIndex];
                final int slot = slotIndex;
                boolean allMatch = entries.stream().allMatch(r -> color.equals(logTableModel.getHighlight(r)));
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
                    setHighlights(entries, color, slot);
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
                    setHighlights(entries, null, null);
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
                    warnIfAnnotationsNotSaved(annotationStore.clearHighlights());
                    refreshDetailAfterHighlight();
                });
            } else {
                clearAllItem.putClientProperty("FlatLaf.style", "disabledForeground: " + colorToHex(ClouseauColors.borderColor()));
                clearAllItem.setEnabled(false);
            }
            menu.add(clearAllItem);

            menu.addSeparator();
        }

        // ── Annotation section ────────────────────────────────────────────────
        if (entries.size() == 1) {
            int targetRow = entries.get(0);
            int ordinal = logTableModel.ordinalAt(targetRow);
            LogEntry annotationTarget = logTableModel.getEntry(targetRow);
            AnnotationStore.Annotation existing = annotationStore.at(ordinal);

            JMenuItem addEditItem = new JMenuItem(
                    existing != null ? Messages.get("annotation.edit") : Messages.get("annotation.add"));
            addEditItem.addActionListener(e -> showAnnotationDialog(annotationTarget, ordinal, existing));
            menu.add(addEditItem);

            JMenuItem removeItem = new JMenuItem(Messages.get("annotation.remove"));
            removeItem.setEnabled(existing != null);
            if (existing != null) {
                removeItem.addActionListener(e -> {
                    warnIfAnnotationsNotSaved(annotationStore.remove(ordinal));
                    logTable.repaint();
                    refreshDetail();
                });
            }
            menu.add(removeItem);
            menu.addSeparator();
        }

        JMenuItem viewAllItem = new JMenuItem(Messages.get("annotations.view.all"));
        viewAllItem.setEnabled(annotationStore.size() > 0);
        viewAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        viewAllItem.addActionListener(e -> showAnnotationsDialog());
        menu.add(viewAllItem);

        menu.addSeparator();

        JMenuItem reloadFileItem = new JMenuItem(Messages.get("table.context.reload.file"));
        reloadFileItem.setEnabled(currentFile != null);
        reloadFileItem.addActionListener(e -> reload());
        menu.add(reloadFileItem);

        JMenuItem clearTableItem = new JMenuItem(Messages.get("table.clear"));
        clearTableItem.addActionListener(e -> clearTable());
        menu.add(clearTableItem);

        menu.show(logTable, x, y);
    }

    /** Highlights model rows (null clears them) and saves them with the annotations (D-15). */
    private void setHighlights(List<Integer> modelRows, Color color, Integer slot) {
        Map<Integer, String> rawLines = new java.util.LinkedHashMap<>();
        for (int r : modelRows) {
            LogEntry entry = logTableModel.getEntry(r);
            if (entry == null) continue;
            logTableModel.setHighlight(r, color);
            rawLines.put(logTableModel.ordinalAt(r), entry.rawLine());
        }
        warnIfAnnotationsNotSaved(annotationStore.setHighlights(rawLines, slot));
    }

    /** Shows the highlights saved with the file on the rows they attached to. */
    private void applySavedHighlights() {
        Color[] palette = ClouseauColors.highlightColors();
        annotationStore.highlights().forEach((ord, slot) -> {
            if (slot >= 0 && slot < palette.length) logTableModel.setHighlightAtOrdinal(ord, palette[slot]);
        });
        refreshHighlightNav();
    }

    private void refreshDetailAfterHighlight() {
        refreshDetail();
        refreshHighlightNav();
    }

    private void showAnnotationDialog(LogEntry entry, int ordinal, AnnotationStore.Annotation existing) {
        if (entry == null || ordinal < 0) return;
        AnnotationDialog dlg = new AnnotationDialog(SwingUtilities.getWindowAncestor(this), existing);
        dlg.setVisible(true);
        if (!dlg.isConfirmed()) return;
        String note   = dlg.getNote();
        String author = dlg.getAuthor();
        if (note.isEmpty()) {
            warnIfAnnotationsNotSaved(annotationStore.remove(ordinal));
        } else {
            String ts      = entry.timestamp() != null ? entry.timestamp().toString() : "";
            String preview = entry.message()   != null
                    ? entry.message().substring(0, Math.min(80, entry.message().length()))
                    : entry.rawLine() != null
                    ? entry.rawLine().substring(0, Math.min(80, entry.rawLine().length()))
                    : "";
            warnIfAnnotationsNotSaved(annotationStore.put(ordinal, new AnnotationStore.Annotation(
                    AnnotationStore.hashOf(entry.rawLine()), null, ts, preview, note, author,
                    java.time.Instant.now().toString())));
        }
        logTable.repaint();
        refreshDetail();
        if (annotationsPanel != null && annotationsDialog != null && annotationsDialog.isVisible())
            annotationsPanel.refresh();
    }

    /**
     * Tells the user, once per opened file, that annotations couldn't be saved next to
     * the log or in the data folder. They stay in memory until the tab closes.
     */
    void warnIfAnnotationsNotSaved(boolean saved) {
        if (saved || annotationSaveWarned) return;
        annotationSaveWarned = true;
        JOptionPane.showMessageDialog(
                SwingUtilities.getWindowAncestor(this),
                java.text.MessageFormat.format(Messages.get("annotation.save.failed.message"),
                        String.valueOf(annotationStore.getSavePath())),
                Messages.get("annotation.save.failed.title"),
                JOptionPane.WARNING_MESSAGE);
    }

    /** Opens (or brings to front) the non-modal annotations panel dialog. */
    public void showAnnotationsDialog() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        if (annotationsDialog == null || !annotationsDialog.isDisplayable()) {
            annotationsPanel  = new AnnotationsPanel(
                    () -> annotationStore, logTableModel, logTable,
                    () -> annotationsDialog.setVisible(false),
                    saved -> { warnIfAnnotationsNotSaved(saved); refreshDetail(); });
            annotationsDialog = new JDialog(owner, Messages.get("annotations.dialog.title"),
                    java.awt.Dialog.ModalityType.MODELESS);
            annotationsDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            annotationsDialog.add(annotationsPanel);
            annotationsDialog.setSize(760, 280);
            annotationsDialog.setLocationRelativeTo(owner);
        } else {
            annotationsPanel.refresh();
        }
        annotationsDialog.setVisible(true);
        annotationsDialog.toFront();
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
            logTableModel.cancelFind();
            clearSearchMatches();
            return;
        }
        String lower = query.toLowerCase();
        // Read in the background: on an indexed file, every visible row comes from disk.
        logTableModel.find(e -> entryMatchesQuery(e, lower), percent -> {
            if (percent < 0) return;
            findCounter.setForeground(ClouseauColors.dimForeground());
            findCounter.setText(java.text.MessageFormat.format(Messages.get("find.searching"), percent));
        }, this::showSearchMatches);
    }

    /** Shows the result of a find: the ordinals of the matching entries, ascending. */
    private void showSearchMatches(int[] ordinals) {
        List<Integer> list = new ArrayList<>();
        Set<Integer>  set  = new HashSet<>();
        for (int ordinal : ordinals) {
            int modelRow = logTableModel.rowOfOrdinal(ordinal);   // rows may have changed since
            if (modelRow >= 0) {
                list.add(modelRow);
                set.add(modelRow);
            }
        }
        // Next and previous follow the view, which may be sorted.
        list.sort(java.util.Comparator.comparingInt(logTable::convertRowIndexToView));
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

    /** What the detail panel should show for the selected row, or null. */
    private DetailPanel.Content currentDetail() {
        int viewRow = logTable.getSelectedRow();
        int modelRow = viewRow >= 0 ? logTable.convertRowIndexToModel(viewRow) : -1;
        LogEntry entry = modelRow >= 0 ? logTableModel.getEntry(modelRow) : null;
        if (entry == null) return null;
        return new DetailPanel.Content(entry, logTableModel.getHighlight(modelRow),
                annotationStore.at(logTableModel.ordinalAt(modelRow)),
                logTableModel.getContinuationLines(modelRow));
    }

    private void refreshDetail() {
        detailPanel.refresh();
    }

    /** Rebuilds timestamp formatters from the current preference and refreshes the table and detail panel. */
    public void refreshTimezone() {
        logTableModel.refreshTimezone();
        detailPanel.refreshTimezone();
    }
}
