package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.AnnotationStore;
import com.droidenx.clouseau.core.EntryIndex;
import com.droidenx.clouseau.core.FileIndexer;
import com.droidenx.clouseau.core.IndexedEntries;
import com.droidenx.clouseau.core.IndexedFollow;
import com.droidenx.clouseau.core.LineParser;
import com.droidenx.clouseau.core.LogCache;
import com.droidenx.clouseau.core.LogFileReader;
import com.droidenx.clouseau.core.LogReaders;
import com.droidenx.clouseau.core.LogSniffer;
import com.droidenx.clouseau.core.MemoryGuard;
import com.droidenx.clouseau.core.ReviewBundle;
import com.droidenx.clouseau.core.ZipLogs;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * One log file in a tab (D-16, G-11, D-21): one load of it, indexed (a compressed one
 * unpacked to a copy in the cache folder), its notes, and following it through rotation.
 * A file no parser recognizes is shown as plain text instead (UC-13). EDT only.
 */
@Slf4j
final class FileContent implements TabContent {

    /**
     * Follow mode reads as soon as the OS reports a change; this is only the backstop for
     * file systems that send no notifications (some network drives).
     */
    private static final long FOLLOW_SAFETY_POLL_MS = 2_000;

    private final TabContext tab;
    private final Path file;
    /** The file inside a .zip this content shows, or null for a plain (or .gz) file (G-6). */
    private final String entry;
    private final Optional<LogParser> parser;
    /** Run when loading fails, after the user is told why. */
    private final Runnable onError;
    /** Follows the file and keeps its position (G-11). */
    private final IndexedFollow follower;
    /** The decompressed copy of a compressed log rows are read from, or null (D-16). */
    private Path cacheCopy;
    private SwingWorker<Void, LogEntry> worker;
    private SwingWorker<Void, LogEntry> tailWorker;
    private boolean loading;
    /** True if the rows are indexed rather than held in memory (D-16). */
    private boolean indexed;
    /** True when the load stopped early because memory (or, if {@link #partialDiskFull}, disk) ran out (UC-1). */
    private boolean partialLoad;
    private boolean partialDiskFull;
    /** Shows the file as plain text when no parser recognizes it (UC-13); null until then. */
    private PlainTextView plainView;
    /** The first lines of a plain-text file, offered as samples for a new parser. */
    private List<String> plainSamples = List.of();
    private boolean disposed;

    FileContent(TabContext tab, Path file, String entry, Optional<LogParser> parser, Runnable onError) {
        this.tab = tab;
        this.file = file.toAbsolutePath();
        this.entry = entry;
        this.parser = parser;
        this.onError = onError;
        this.follower = new IndexedFollow(this.file, 0);
    }

    @Override
    public void start() {
        loading = true;
        tab.reset(false);
        LogTableModel model = tab.model();
        model.setCustomFields(parser.map(LogParser::customFields).orElse(List.of()));
        model.beginBatchLoad();
        tab.showLoading();

        final ZoneId srcTz = tab.timezone(); // capture before the worker starts
        final List<LogParser> parsers = tab.parsers();
        final Path annotationKey = entry == null ? file : ZipLogs.entryKey(file, entry);
        final MemoryGuard memory = new MemoryGuard();

        // D-16: the file is indexed instead of held in memory. A compressed one is unpacked
        // to a copy in the cache folder during the same pass, and rows are read from that.
        final boolean compressed = entry != null || LogReaders.isCompressed(file);
        final Path copy = compressed ? newCacheCopy(file, entry) : null;
        final Path rowSource = compressed ? copy : file;
        final EntryIndex index = new EntryIndex();
        if (rowSource != null) {
            try {
                model.beginIndexedLoad(IndexedEntries.open(rowSource, index,
                        new LineParser(parser, parsers, srcTz), Messages.get("table.rotated.text")));
                cacheCopy = copy;
            } catch (IOException ex) {
                log.warn("Can't open {} for indexing; it will be loaded into memory", rowSource, ex);
                LogCache.delete(copy);
            }
        }
        indexed = model.isIndexed();
        final boolean indexing = indexed;

        worker = new SwingWorker<>() {
            /** Lines read before loading stopped for lack of memory or disk, or -1 if it didn't. */
            volatile long stoppedAtLine = -1;
            /** True if loading stopped because the unpacked copy filled the disk. */
            volatile boolean diskFull;
            /** Annotations read and anchored by the worker; handed to the tab in done(). */
            volatile AnnotationStore loadedAnnotations;
            /** Bytes of a plain file the index covers, where following resumes; -1 if not indexed. */
            volatile long indexedTo = -1;
            /** The file's first lines, when no parser recognizes them: it opens as plain text (UC-13). */
            volatile LogSniffer.Sample unrecognized;

            @Override
            protected Void doInBackground() throws Exception {
                LogSniffer.Sample sample = LogSniffer.read(file, entry);
                if (!sample.empty() && LogSniffer.detect(sample.lines(), parser, parsers).isEmpty()) {
                    unrecognized = sample;
                    return null;
                }
                // A review bundle's log starts from the bundle's notes until its own sidecar exists (D-10).
                AnnotationStore annotations = ReviewBundle.loadAnnotations(file, entry, annotationKey, AppDirs.annotationsDir());
                LineParser lineParser = new LineParser(parser, parsers, srcTz);
                if (indexing) {
                    indexFile(lineParser, annotations);
                    loadedAnnotations = annotations;
                    return null;
                }
                AnnotationStore.Anchoring anchoring = annotations.startAnchoring();
                try (BufferedReader reader = LogReaders.open(file, entry)) {
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
             * Reads the file once into the index, as every file of a merged view is read
             * (G-11). Each entry is still published, so the table can filter it and the
             * filter bar can learn its logger, but only the index keeps it.
             */
            private void indexFile(LineParser lineParser, AnnotationStore annotations) throws IOException {
                FileIndexer.Result r = FileIndexer.index(file, entry, compressed ? rowSource : null, lineParser,
                        index, annotations, this::publish, memory::isLow, this::isCancelled);
                // Follow from exactly where the index ends, not the size at the end of the
                // load: lines written in between would be skipped.
                if (!compressed) indexedTo = r.end();
                if (r.partial()) {
                    diskFull = r.diskFull();
                    stoppedAtLine = r.lines();
                    if (!diskFull) {
                        log.warn("Low memory: stopped indexing {} after {} entries (max heap {} MiB)",
                                file, stoppedAtLine, memory.maxMiB());
                    }
                }
            }

            @Override
            protected void process(List<LogEntry> chunk) {
                if (isCancelled()) return;
                tab.model().appendBatch(chunk);
                tab.filterBar().addBatch(chunk);
            }

            @Override
            protected void done() {
                loading = false;
                // Cancelled means replaced or closed: the tab is no longer this content's, and
                // its context throws for the model. Don't touch it. A throw here is worse than
                // it looks: Swing runs every worker's pending done() and process() calls in one
                // batch, and an exception drops the rest of that batch, which can include the
                // next load's rows (an empty tab after a reload during a load).
                if (isCancelled()) return;
                tab.model().endBatchLoad();
                tab.showRows();
                try {
                    get();
                    if (unrecognized != null) {
                        showPlainText(unrecognized);
                        return;
                    }
                    tab.loaded(loadedAnnotations);
                    LogTableView view = tab.tableView();
                    view.autoResizeColumns();
                    view.applyDefaultLayoutIfSet();
                    if (stoppedAtLine >= 0) {
                        // Partial content: following would append after a gap, so don't.
                        partialLoad = true;
                        partialDiskFull = diskFull;
                        tab.statusChanged();
                        String message = diskFull
                                ? MessageFormat.format(Messages.get("load.diskfull.message"),
                                        file.getFileName().toString(), stoppedAtLine, AppDirs.cacheDir().toString())
                                : MessageFormat.format(Messages.get("load.lowmemory.message"),
                                        file.getFileName().toString(), stoppedAtLine, memory.maxMiB());
                        JOptionPane.showMessageDialog(tab.window(), message,
                                Messages.get("load.lowmemory.title"), JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    tab.statusChanged();
                    if (indexedTo >= 0) follower.setPosition(indexedTo);
                    else try { follower.setPosition(Files.size(file)); } catch (IOException ignored) {}
                    if (tab.follow()) startTailing();
                } catch (Exception ex) {
                    log.error("Failed to load {}", file, ex);
                    onError.run();
                    JOptionPane.showMessageDialog(tab.window(), ex.getMessage(),
                            Messages.get("filechooser.error.title"), JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    /** Swaps the (empty) table for the plain-text view, after asking first for a binary file. */
    private void showPlainText(LogSniffer.Sample sample) {
        if (sample.binary()) {
            String name = entry != null ? entry : file.getFileName().toString();
            int answer = JOptionPane.showConfirmDialog(tab.window(),
                    MessageFormat.format(Messages.get("plain.binary.message"), name),
                    Messages.get("plain.binary.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                tab.close();
                return;
            }
        }
        log.info("No parser recognizes {}; showing it as plain text", file);
        plainSamples = sample.lines();
        tab.model().clear();          // closes the index; the unpacked copy is reused below
        tab.filterBar().clearLoggers();
        plainView = new PlainTextView(() -> tab.createParser(plainSamples), tab::setFollow, tab::statusChanged);
        tab.showPlain(plainView);
        String banner = parser.map(p -> MessageFormat.format(Messages.get("plain.banner.chosen"), p.getName()))
                .orElse(Messages.get("plain.banner.auto"));
        plainView.load(file, entry, cacheCopy, tab.follow(), banner);
        plainView.table().requestFocusInWindow();   // so Ctrl+F and copy reach the view
        tab.statusChanged();
    }

    private void startTailing() {
        stopTailing();
        if (LogReaders.isCompressed(file)) return;
        final ZoneId srcTz = tab.timezone();
        final List<LogParser> parsers = tab.parsers();
        // D-16: on an indexed tab, followed lines extend the index and are never trimmed.
        final IndexedEntries indexedRows = tab.model().indexedEntries();
        tailWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                follower.run(indexedRows, new LineParser(parser, parsers, srcTz), new IndexedFollow.Listener() {
                    @Override
                    public void entry(LogEntry e) {
                        publish(e);
                    }

                    @Override
                    public void textLost() {
                        SwingUtilities.invokeLater(() -> {
                            if (disposed || tab.model().indexedEntries() != indexedRows) return;   // cleared
                            tab.textLost();
                        });
                    }
                }, this::isCancelled, FOLLOW_SAFETY_POLL_MS);
                return null;
            }

            @Override
            protected void process(List<LogEntry> entries) {
                if (disposed) return;
                // Rows already in the index must reach the model even after a cancel, or
                // the model's count would fall behind the index for good.
                if (isCancelled() && indexedRows == null) return;
                LogTableModel model = tab.model();
                if (indexedRows != null && model.indexedEntries() != indexedRows) return;  // cleared
                tab.filterBar().addBatch(entries);
                model.appendFollowed(entries);
            }
        };
        tailWorker.execute();
    }

    private void stopTailing() {
        if (tailWorker != null) tailWorker.cancel(true);
        tailWorker = null;
    }

    @Override
    public void setFollow(boolean on) {
        if (plainView != null) plainView.setFollow(on);
        else if (on) startTailing();
        else stopTailing();
    }

    /**
     * The user cleared the table: follow on from the end of the file. A plain file stays
     * indexed, so what is followed from here on starts a new index.
     */
    @Override
    public void cleared() {
        try { follower.setPosition(Files.size(file)); } catch (IOException ignored) { follower.setPosition(0); }
        if (!indexed || cacheCopy != null) return;
        try {
            tab.model().beginIndexedLoad(IndexedEntries.open(file, new EntryIndex(),
                    new LineParser(parser, tab.parsers(), tab.timezone()), Messages.get("table.rotated.text")));
        } catch (IOException ex) {
            log.warn("Can't open {} for indexing; followed lines will be held in memory", file, ex);
        }
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        if (worker != null) worker.cancel(true);
        stopTailing();
        if (plainView != null) plainView.dispose();
        // The model reads rows from the copy: close it first. The load worker may still be
        // writing the copy; files are opened with delete sharing, so it goes when the last
        // handle closes (and at the next start if it doesn't).
        tab.model().clear();
        LogCache.delete(cacheCopy);
        cacheCopy = null;
    }

    @Override
    public boolean loading() { return loading; }

    @Override
    public String path() { return entry == null ? file.toString() : file + " › " + entry; }

    @Override
    public String pathTooltip() { return path(); }

    @Override
    public String statusNote() {
        return partialLoad ? Messages.get(partialDiskFull ? "status.partial.diskfull" : "status.partial") : "";
    }

    Path file() { return file; }

    String entry() { return entry; }

    Optional<LogParser> parser() { return parser; }

    /** True while the file is shown as plain text because no parser recognizes it. */
    boolean isPlain() { return plainView != null; }

    /** The plain-text file's first lines, for "retry with new parsers". */
    List<String> plainSamples() { return plainSamples; }

    /** A plain-text file still matches none of the parsers: say so in its banner. */
    void stillUnrecognized() {
        if (plainView != null) plainView.setBanner(Messages.get("plain.banner.still"));
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
}
