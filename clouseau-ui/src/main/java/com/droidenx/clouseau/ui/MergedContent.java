package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.LogCache;
import com.droidenx.clouseau.core.MemoryGuard;
import com.droidenx.clouseau.core.MergeLoad;
import com.droidenx.clouseau.core.MergeOrder;
import com.droidenx.clouseau.core.MergedEntries;
import com.droidenx.clouseau.core.MergedFollow;
import com.droidenx.clouseau.core.MergedNotes;
import com.droidenx.clouseau.core.ReviewBundle;
import com.droidenx.clouseau.core.Session;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.event.TableModelListener;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * Several files merged into one timeline (UC-12, D-17, D-21): one load of them, the
 * unpacked copies it made, its notes, and following its files. How each file is read
 * ({@link MergedFileSettings}) belongs to the tab and outlives a reload. EDT only.
 */
@Slf4j
final class MergedContent implements TabContent {

    private final TabContext tab;
    private final Optional<LogParser> parser;
    private final MergedFileSettings settings;
    private final TabPanel.MergeListener listener;
    /** The files asked for; once loaded, those that could be merged. */
    private List<MergeLoad.File> files;
    private SwingWorker<MergeLoad.Result, LogEntry> worker;
    private boolean loading;
    /** The merged rows, once loaded; null before, or if the load didn't get that far. */
    private MergedEntries view;
    private MergedNotes notes;
    /** Unpacked copies of compressed files, deleted with this content. */
    private List<Path> copies = List.of();
    private MergedTailer tailer;
    /** Sizes the columns when the first filtered rows arrive; removed once it has. */
    private TableModelListener sizeColumns;
    /** True once the user cleared the table: the view is no longer shown or followed. */
    private boolean cleared;
    private boolean disposed;

    MergedContent(TabContext tab, List<MergeLoad.File> files, Optional<LogParser> parser,
                  MergedFileSettings settings, TabPanel.MergeListener listener) {
        this.tab = tab;
        this.files = List.copyOf(files);
        this.parser = parser;
        this.settings = settings;
        this.listener = listener;
    }

    @Override
    public void start() {
        loading = true;
        tab.reset(false);
        tab.model().setCustomFields(List.of());
        tab.showLoading();

        // Each file is read in its own source timezone (and parser, if it has one) and placed with its own offset.
        final List<MergeLoad.File> asked = files;
        final Map<MergeLoad.File, MergeLoad.PerFile> setup = new HashMap<>();
        for (MergeLoad.File f : asked) setup.put(f, settings.perFile(f));
        final MemoryGuard memory = new MemoryGuard();
        final List<LogParser> parsers = tab.parsers();
        worker = new SwingWorker<>() {
            @Override
            protected MergeLoad.Result doInBackground() throws Exception {
                return MergeLoad.run(asked, parser, parsers, setup::get, AppDirs.cacheDir(),
                        Messages.get("table.rotated.text"), AppDirs.annotationsDir(), memory::isLow, this::isCancelled, this::publish);
            }

            @Override
            protected void process(List<LogEntry> chunk) {
                if (!isCancelled()) tab.filterBar().addBatch(chunk);
            }

            @Override
            protected void done() {
                loading = false;
                tab.showRows();
                MergeLoad.Result r;
                try {
                    r = get();
                } catch (CancellationException | InterruptedException ex) {
                    return;     // the worker cleans up what it made
                } catch (ExecutionException ex) {
                    log.error("Failed to merge {}", asked, ex.getCause());
                    listener.failed();
                    JOptionPane.showMessageDialog(tab.window(), String.valueOf(ex.getCause().getMessage()),
                            Messages.get("filechooser.error.title"), JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (r == null) return;
                if (isCancelled() || disposed) {
                    if (r.entries() != null) r.entries().close();
                    r.copies().forEach(LogCache::delete);
                    return;
                }
                if (r.stopped()) {
                    listener.failed();
                    JOptionPane.showMessageDialog(tab.window(),
                            r.diskFull()
                                    ? MessageFormat.format(Messages.get("merge.diskfull.message"), AppDirs.cacheDir().toString())
                                    : MessageFormat.format(Messages.get("merge.lowmemory.message"), memory.maxMiB()),
                            Messages.get("load.lowmemory.title"), JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (r.entries() == null) {
                    // Fewer than two files can be merged: one opens as an ordinary tab.
                    listener.notMerged(r.mergeable());
                    if (!r.skipped().isEmpty()) listener.skipped(r.skipped());
                    return;
                }
                show(r, setup);
                if (!r.skipped().isEmpty()) listener.skipped(r.skipped());
            }
        };
        worker.execute();
    }

    /** Shows a finished load: its files, rows, notes, and following them. */
    private void show(MergeLoad.Result r, Map<MergeLoad.File, MergeLoad.PerFile> setup) {
        copies = r.copies();
        files = r.mergeable();    // a reload doesn't check the skipped ones again
        view = r.entries();
        tab.pathChanged();
        listener.merged(files);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < view.sourceCount(); i++) names.add(view.sourceName(i));
        tab.filterBar().setSources(names);
        tab.model().beginMergedLoad(view, view.size(), r.fields());
        // Each file's notes stay in its own store, saved next to that file (D-17).
        notes = new MergedNotes(r.notes(), names, view.order());
        tab.loaded(notes);
        tailer = new MergedTailer(view,
                files.stream().map(MergeLoad.File::path).toList(), r.ends(),
                files.stream().map(f -> setup.get(f).zone()).toList(),
                files.stream().map(f -> setup.get(f).parserOr(parser)).toList(),
                tab.parsers(), tailerHost());
        if (tab.follow()) tailer.start();
        sizeColumnsWhenRowsArrive();
        tab.model().applyFilter(tab.filterBar().buildFilter());
        tab.statusChanged();
    }

    /**
     * Sizes the columns once the first filter result is in: a merged view's rows only
     * appear when the background filter finishes, after loading.
     */
    private void sizeColumnsWhenRowsArrive() {
        LogTableModel model = tab.model();
        sizeColumns = e -> {
            if (model.getRowCount() == 0) return;
            model.removeTableModelListener(sizeColumns);
            sizeColumns = null;
            SwingUtilities.invokeLater(() -> {
                if (!shown()) return;
                LogTableView tableView = tab.tableView();
                tableView.autoResizeColumns();
                // Every file's name, not just those in the sampled rows.
                tableView.widenColumnToFit(Messages.get("table.col.source"), sourceLabels(view));
                tableView.applyDefaultLayoutIfSet();
            });
        };
        model.addTableModelListener(sizeColumns);
    }

    /** What following the files does with the rows it reads (D-17 step 5). */
    private MergedTailer.Host tailerHost() {
        return new MergedTailer.Host() {
            @Override
            public void placed(MergedFollow.Placed p) {
                if (shown()) placeFollowed(p);
            }

            @Override
            public void read(List<LogEntry> entries) {
                if (shown()) tab.filterBar().addBatch(entries);
            }

            @Override
            public void textLost() {
                if (shown()) tab.textLost();
            }
        };
    }

    /**
     * Shows followed rows: appended, or, if the tail re-merged, re-filtered from where it
     * changed, with highlights, notes and the selection kept on their entries.
     */
    private void placeFollowed(MergedFollow.Placed p) {
        MergeOrder after = p.after();
        if (!p.moved()) {
            List<LogEntry> added = new ArrayList<>(after.size() - p.from());
            for (int o = p.from(); o < after.size(); o++) added.add(view.get(o));
            tab.model().appendFollowed(added);
            return;
        }
        tab.reordered(p.before(), after, p.from());
    }

    /** Whether the loaded view is what the tab shows: not replaced, closed or cleared. */
    private boolean shown() {
        return !disposed && !cleared && view != null;
    }

    @Override
    public void setFollow(boolean on) {
        if (tailer == null) return;
        if (on) tailer.start();
        else tailer.stop();
    }

    @Override
    public void cleared() {
        cleared = true;
        if (tailer != null) tailer.stop();
        tailer = null;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        if (worker != null) worker.cancel(true);
        if (tailer != null) tailer.stop();
        tailer = null;
        if (sizeColumns != null) tab.model().removeTableModelListener(sizeColumns);
        sizeColumns = null;
        // The model reads rows from the copies: close it first (see TabPanel.deleteCacheCopy).
        if (view != null && tab.model().entryRows() == view) tab.model().clear();
        copies.forEach(LogCache::delete);
        copies = List.of();
    }

    @Override
    public String path() {
        return MessageFormat.format(Messages.get("merge.status.path"), files.size(),
                String.join(", ", files.stream().map(MergeLoad.File::fileName).toList()));
    }

    @Override
    public String pathTooltip() {
        return String.join("\n", files.stream().map(MergeLoad.File::toString).toList());
    }

    /** The files of the view, in order: those asked for, then, once loaded, those that could be merged. */
    List<MergeLoad.File> files() { return files; }

    Optional<LogParser> parser() { return parser; }

    TabPanel.MergeListener listener() { return listener; }

    /** The loaded view while the tab shows it, or null (loading, failed or cleared). */
    MergedEntries view() { return shown() && !loading ? view : null; }

    @Override
    public boolean loading() { return loading; }

    /**
     * The files as a review bundle takes them (D-17): each file, its notes and how it is
     * read; null while loading, or if nothing is shown.
     */
    List<ReviewBundle.Part> reviewParts() {
        MergedEntries v = view();
        if (v == null || notes == null) return null;
        List<ReviewBundle.Part> parts = new ArrayList<>();
        for (int s = 0; s < v.sourceCount(); s++) {
            MergeLoad.File f = files.get(s);
            String parserName = settings.parserOf(f).map(LogParser::getName).orElse(null);
            parts.add(new ReviewBundle.Part(f.path(), f.entry(), notes.stores().get(s).toJson(), parserName,
                    settings.zoneOf(f).getId(), v.offsetMillis(s)));
        }
        return parts;
    }

    /** The files as a session keeps them: each with its own parser, timezone and offset. */
    List<Session.MergedFile> sessionFiles() {
        MergedEntries v = view();
        List<Session.MergedFile> out = new ArrayList<>();
        for (int s = 0; s < files.size(); s++) {
            MergeLoad.File f = files.get(s);
            long offset = v != null && s < v.sourceCount() ? v.offsetMillis(s) : settings.offsetOf(f);
            LogParser own = settings.ownParser(f);
            out.add(new Session.MergedFile(f.path().toString(), f.entry(),
                    own != null ? own.getName() : null, settings.zoneOf(f).getId(), offset));
        }
        return out;
    }

    /** A merged view's Source column labels, with their offset marks. */
    /**
     * The widest each file's Source cell can be: its label and ":" with its largest line
     * number ("db.log:10482"), so the column fits them all.
     */
    static List<String> sourceLabels(MergedEntries view) {
        List<String> labels = new ArrayList<>();
        for (int s = 0; s < view.sourceCount(); s++) {
            long lines = view.sources().get(s).entries().lineCount();
            labels.add(LogTableModel.sourceCell(view, s, Math.max(1, lines)));
        }
        return labels;
    }
}
