package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSource;
import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.core.AnnotationStore;
import com.droidenx.clouseau.core.LogSniffer;
import com.droidenx.clouseau.core.MergeLoad;
import com.droidenx.clouseau.core.MergeOrder;
import com.droidenx.clouseau.core.MergedEntries;
import com.droidenx.clouseau.core.Notes;
import com.droidenx.clouseau.core.ReviewBundle;
import com.droidenx.clouseau.core.Session;
import com.droidenx.clouseau.core.SourceConnection;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One tab (D-21): its layout, the methods the window calls, and what it shows. What every
 * tab has (table, bars, detail pane, notes, follow mode, timezone, status bar) is its
 * {@link SharedTabContext}; what it shows is one {@link TabContent} at a time (a file, a
 * merged view or a live source), or none yet (a restored tab that hasn't loaded). Each
 * content reaches the tab only through its own {@link TabContext}, withdrawn when the
 * content is replaced.
 */
@Slf4j
public final class TabPanel extends JPanel {

    private final SharedTabContext shared;
    /** A restored tab's message card, deferred load and filter notice (D-19, G-9). */
    private final RestoredTab restored;
    /**
     * How each file of a merged tab is read: offset, timezone, parser (D-17, G-9). The
     * tab's, not the content's, so it is kept across reloads.
     */
    private final MergedFileSettings mergedSettings;

    /** What the tab shows, or null (a restored tab that hasn't loaded yet). */
    private TabContent content;
    /** The context {@link #content} was made with; withdrawn when the content is replaced. */
    private ContentContext contentContext;

    /** The file a restored tab will show before it loads, for "one tab per file"; null once something is shown. */
    private Path presetFile;
    private String presetEntry;
    /** Opens the parser editor for a plain-text file's samples; set by the window. */
    private Consumer<List<String>> createParserAction;
    /** Closes this tab; set by the window. */
    private Runnable closeAction = () -> {};

    public TabPanel(List<LogParser> parsers, List<LogFormatter> formatters, List<LogSyntaxHighlighter> syntaxHighlighters) {
        super(new MigLayout("fill, insets 0, gapy 0", "[grow]", ""));
        mergedSettings = new MergedFileSettings(new MergedFileSettings.Host() {
            @Override public List<MergeLoad.File> files() { return getMergedFiles(); }
            @Override public MergedEntries view() { return shared.mergedView(); }
            @Override public ZoneId tabZone() { return shared.timezone(); }
            @Override public Optional<LogParser> tabParser() {
                return content instanceof MergedContent merged ? merged.parser() : Optional.empty();
            }
            @Override public List<LogParser> parsers() { return shared.parsers(); }
            @Override public void reload() { TabPanel.this.reload(); }
            @Override public void zonesChanged() { shared.updateTimezoneBadge(); }
            @Override public void reordered(MergedEntries view, MergeOrder before, MergeOrder after) {
                shared.reordered(before, after, 0);
                shared.tableView().widenColumnToFit(Messages.get("table.col.source"), MergedContent.sourceLabels(view));
                shared.table().repaint();
            }
        });
        shared = new SharedTabContext(this, new SharedTabContext.Host() {
            @Override public TabContent content() { return content; }
            @Override public void setFollow(boolean on) { TabPanel.this.setFollow(on); }
            @Override public void filtersChanged() { if (restored != null) restored.filtersChanged(); }
            @Override public boolean zonesDiffer() { return mergedSettings.zonesDiffer(); }
            @Override public ZoneId zoneOfSource(int source) { return mergedSettings.zoneOf(source); }
            @Override public void showTimezoneDialog() {
                TabPanel.this.showSourceTimezoneDialog(SwingUtilities.getWindowAncestor(TabPanel.this));
            }
            @Override public void showTimeOffsetDialog(int source) { TabPanel.this.showTimeOffsetDialog(source); }
            @Override public void showSourceTimezoneDialog(int source) {
                TabPanel.this.showSourceTimezoneDialog(SwingUtilities.getWindowAncestor(TabPanel.this), source);
            }
            @Override public void showParserDialog(int source) {
                TabPanel.this.showParserDialog(SwingUtilities.getWindowAncestor(TabPanel.this), source);
            }
            @Override public boolean canReload() { return content instanceof FileContent; }
            @Override public void reload() { TabPanel.this.reload(); }
        }, parsers, formatters, syntaxHighlighters);
        restored = new RestoredTab(this, shared.filterBar(), shared::showMessageCard);
        shared.addMessageCard(restored.messagePanel());

        add(restored.noticeStrip(), "growx, wrap, hidemode 3");
        add(shared.filterBar(), "growx, wrap, hidemode 3");
        add(shared.findBar(), "growx, wrap, hidemode 3");
        add(shared.highlightNavBar(), "growx, wrap, hidemode 3");
        add(shared.centerCards(), "grow, pushy, wrap");
        add(shared.statusBar(), "growx");

        // Ctrl+F: show find bar and focus the field
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "showFind");
        getActionMap().put("showFind", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { shared.openFind(); }
        });

        // Ctrl+Shift+N: open the annotations panel
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "showAnnotations");
        getActionMap().put("showAnnotations", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { showAnnotationsDialog(); }
        });
    }

    public boolean isFollow() { return shared.follow(); }

    public void setFollow(boolean follow) {
        shared.setFollow(follow);
        if (content != null) content.setFollow(follow);
    }

    // ── Content (D-21) ────────────────────────────────────────────────────────

    /** Replaces what the tab shows with the content {@code make} creates, and starts it. */
    private void show(Function<TabContext, TabContent> make) {
        restored.hideMessage();
        dropContent();
        presetFile = null;
        presetEntry = null;
        ContentContext context = new ContentContext();
        TabContent next = make.apply(context);
        context.owner = next;
        contentContext = context;
        content = next;
        // What the first column counts: lines of the file, the merged timeline, or what a
        // live source sent (LogTableModel.Numbering).
        shared.setNumbering(next instanceof FileContent ? LogTableModel.Numbering.FILE_LINES
                : next instanceof MergedContent ? LogTableModel.Numbering.POSITION
                : LogTableModel.Numbering.RECEIVED);
        shared.statusBar().setPath(next.path(), next.pathTooltip());
        next.start();
    }

    /** Disposes the current content and withdraws its context: the tab shows something else. */
    private void dropContent() {
        if (content == null) return;
        content.dispose();
        contentContext.revoke();
        content = null;
        contentContext = null;
    }

    /**
     * One content's way into the tab (D-21): forwards to the shared parts until
     * {@link #revoke}d, then the steps do nothing and the parts can't be had. EDT only.
     */
    private final class ContentContext implements TabContext {
        /** The content this context was made for. */
        private TabContent owner;
        private boolean revoked;

        void revoke() { revoked = true; }

        /** True if the content is still the tab's; checks the thread either way. */
        private boolean current() {
            assert SwingUtilities.isEventDispatchThread() : "TabContext used off the EDT";
            return !revoked;
        }

        private void requireCurrent() {
            if (!current()) throw new IllegalStateException("This content was replaced; its tab context is withdrawn");
        }

        @Override public LogTableModel model() { requireCurrent(); return shared.model(); }
        @Override public FilterBar filterBar() { requireCurrent(); return shared.filterBar(); }
        @Override public LogTableView tableView() { requireCurrent(); return shared.tableView(); }
        @Override public List<LogParser> parsers() { current(); return shared.parsers(); }
        @Override public boolean follow() { current(); return shared.follow(); }
        @Override public ZoneId timezone() { current(); return shared.timezone(); }
        @Override public Window window() { current(); return shared.window(); }

        @Override public void reset(boolean keepFilters) { if (current()) shared.reset(keepFilters); }
        @Override public void showLoading() { if (current()) shared.showLoading(); }
        @Override public void showRows() { if (current()) shared.showRows(); }
        @Override public void showPlain(PlainTextView view) { if (current()) shared.showPlain(view); }
        @Override public void setFollow(boolean on) { if (current()) TabPanel.this.setFollow(on); }

        @Override public void createParser(List<String> samples) {
            if (current() && createParserAction != null) createParserAction.accept(samples);
        }

        @Override public void close() { if (current()) closeAction.run(); }

        @Override public void pathChanged() {
            if (current()) shared.statusBar().setPath(owner.path(), owner.pathTooltip());
        }

        @Override public void loaded(Notes notes) { if (current()) shared.loaded(notes); }
        @Override public void reordered(MergeOrder before, MergeOrder after, int from) {
            if (current()) shared.reordered(before, after, from);
        }
        @Override public void textLost() { if (current()) shared.textLost(); }
        @Override public void statusChanged() { if (current()) shared.statusChanged(); }
        @Override public void connectionChanged() { if (current()) connectionListener.run(); }
    }

    // ── Files (D-16, G-11) ────────────────────────────────────────────────────

    /** The file this tab shows (or, before a restored tab loads, will show); null for other tabs. */
    public Path getFile() {
        if (content instanceof FileContent file) return file.file();
        return content == null ? presetFile : null;
    }

    /** The entry of the zip this tab shows, or null for a plain file. */
    public String getZipEntry() {
        if (content instanceof FileContent file) return file.entry();
        return content == null ? presetEntry : null;
    }

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
        show(context -> new FileContent(context, file, entry, parser, onError));
    }

    public void reload() {
        if (content instanceof MergedContent merged) loadMerged(merged.files(), merged.parser(), merged.listener());
        else if (content instanceof FileContent file) load(file.file(), file.entry(), file.parser(), () -> {});
    }

    /**
     * The tab's annotations and highlights in the sidecar format, for a review bundle
     * (D-10), or null while the file is still loading (its notes aren't read yet).
     */
    public String annotationsJson() {
        if (content != null && content.loading()) return null;
        return shared.notes() instanceof AnnotationStore s ? s.toJson() : null;
    }

    // ── Plain text (UC-13) ────────────────────────────────────────────────────

    /** True while the tab shows its file as plain text because no parser recognizes it. */
    public boolean isPlainText() { return shared.isPlain(); }

    /** What "Create a parser" does: open the parser editor with these sample lines. */
    public void setCreateParserAction(Consumer<List<String>> action) {
        createParserAction = action;
    }

    /** Closes this tab without asking, e.g. when the user declines to open a binary file. */
    public void setCloseAction(Runnable action) {
        closeAction = action != null ? action : () -> {};
    }

    /**
     * Takes the window's parsers after the user saved one. A plain-text tab reloads with
     * auto-detection if one of them now recognizes the file, and says so if none does.
     */
    public void retryWithParsers(List<LogParser> newParsers) {
        shared.setParsers(newParsers);
        if (!(content instanceof FileContent file) || !file.isPlain()) return;
        if (LogSniffer.detect(file.plainSamples(), Optional.empty(), newParsers).isPresent()) {
            load(file.file(), file.entry(), Optional.empty(), () -> {});
        } else {
            file.stillUnrecognized();
        }
    }

    // ── Merged view (UC-12, D-17) ─────────────────────────────────────────────

    /** What a merged load tells the window, on the EDT, once it ends. */
    public interface MergeListener {
        /** Some files can't go on a timeline; offer them in their own tabs. */
        void skipped(List<MergeLoad.Skipped> skipped);

        /**
         * Fewer than two files could be merged, so nothing was: close this tab, and open
         * {@code mergeable} (zero or one file) as an ordinary tab. {@link #skipped} follows.
         */
        void notMerged(List<MergeLoad.File> mergeable);

        /** Loading failed or stopped (the user was told why): close this tab. */
        void failed();

        /** The view shows these files (those that could be merged): title the tab after them. */
        void merged(List<MergeLoad.File> files);
    }

    /** True while this tab shows several files merged into one timeline. */
    public boolean isMerged() { return content instanceof MergedContent; }

    /** The files of a merged view, in the order given, or null for a single-file tab. */
    public List<MergeLoad.File> getMergedFiles() {
        return content instanceof MergedContent merged ? merged.files() : null;
    }

    /** Loads several files into one merged view (D-17); {@code listener} hears how it went. */
    public void loadMerged(List<MergeLoad.File> files, Optional<LogParser> parser, MergeListener listener) {
        show(context -> new MergedContent(context, files, parser, mergedSettings, listener));
    }

    /**
     * Sets how a merged load reads its files before {@link #loadMerged}: per file, a
     * timezone (if not null), a time offset and a parser (if not null), as a review bundle gives them (D-17).
     */
    public void presetMerged(Map<MergeLoad.File, MergeLoad.PerFile> setup) {
        mergedSettings.preset(setup);
    }

    /**
     * A merged tab's files as a review bundle takes them (D-17): each file, its notes and
     * how it is read; null if this isn't a merged tab or it is still loading.
     */
    public List<ReviewBundle.Part> reviewParts() {
        return content instanceof MergedContent merged ? merged.reviewParts() : null;
    }

    /** The names of a merged tab's files, as the Source column shows them; empty on other tabs. */
    public List<String> mergedSourceNames() {
        MergedEntries view = shared.mergedView();
        if (view == null) return List.of();
        List<String> names = new ArrayList<>();
        for (int s = 0; s < view.sourceCount(); s++) names.add(view.sourceName(s));
        return names;
    }

    /** Asks for a new time offset for one file of a merged tab, then re-merges. */
    public void showTimeOffsetDialog(int source) {
        mergedSettings.showTimeOffsetDialog(SwingUtilities.getWindowAncestor(this), source);
    }

    /** Sets one merged file's time offset and re-merges in the background. */
    void applyTimeOffset(int source, long millis) {
        mergedSettings.applyTimeOffset(source, millis);
    }

    /** Asks for the source timezone of one file of a merged tab, saves it, and reloads. */
    public void showSourceTimezoneDialog(Component parent, int source) {
        mergedSettings.showSourceTimezoneDialog(parent, source);
    }

    /** Asks which parser one file of a merged tab is read with (G-10), and reloads. */
    public void showParserDialog(Component parent, int source) {
        mergedSettings.showParserDialog(parent, source);
    }

    // ── Live sources (UC-6, D-20) ─────────────────────────────────────────────

    /** Told on the EDT when the live source's connection changes state; set by the window. */
    private Runnable connectionListener = () -> {};
    /** What Reconnect does on a restored tab that is waiting for the user, or null. */
    private Runnable restoredReconnect;
    /** Why a restored tab is waiting for the user, while {@link #restoredReconnect} is set. */
    private String restoredWaiting;

    /**
     * Streams a live source (SSH, or one from a plugin) into this tab, as a {@link LiveContent}.
     * Rows appear as they arrive; closing the tab closes the source.
     *
     * <p>{@code factory} creates a new source for {@link #reconnect}, since a source is
     * used once; null if the tab can't reconnect.
     */
    public void loadSource(LogSource source, Callable<LogSource> factory, Optional<LogParser> parser) {
        restoredReconnect = null;
        restoredWaiting = null;
        show(context -> new LiveContent(context, source, factory, parser, false));
    }

    /**
     * A restored live tab that can't connect without the user (a password sessions don't
     * keep, D-19): shows {@code text} with a Reconnect button, marks the tab disconnected so
     * its dot says so (D-20), and makes the tab menu's Reconnect and Ctrl+R run
     * {@code action} too, so there is one way to reconnect it.
     */
    void waitForReconnect(String text, Runnable action) {
        restoredReconnect = action;
        restoredWaiting = text;
        showMessage(text, Messages.get("session.tab.reconnect"), action);
        connectionListener.run();
    }

    /** True if this tab streams a live source that {@link #reconnect} can open again. */
    public boolean canReconnect() {
        return content instanceof LiveContent live && live.canReconnect() || restoredReconnect != null;
    }

    /** The live source's state, or null on a tab that shows no live source. */
    public SourceConnection.State connectionState() {
        if (content instanceof LiveContent live) return live.state();
        return restoredReconnect != null ? SourceConnection.State.DISCONNECTED : null;
    }

    /** Why the live source failed, or null. */
    public String connectionError() {
        if (content instanceof LiveContent live) return live.error();
        return restoredReconnect != null ? restoredWaiting : null;
    }

    /** Run on the EDT whenever the live source's state changes. */
    public void setConnectionListener(Runnable listener) {
        connectionListener = listener != null ? listener : () -> {};
    }

    /**
     * Closes the live source and streams a new one into the emptied tab, as if reopened
     * (UC-6): the tab keeps its title, parser, filters and columns. A plain
     * {@code tail -F} source fetches its last lines again, so rows aren't kept, or
     * they would be duplicated.
     */
    public void reconnect() {
        if (!(content instanceof LiveContent live) || !live.canReconnect()) {
            if (restoredReconnect != null) restoredReconnect.run();   // asks for what the session didn't keep
            return;
        }
        LogSource fresh;
        try {
            fresh = live.factory().call();
            if (fresh == null) throw new IllegalStateException("create() returned null");
        } catch (Exception | LinkageError e) {
            log.error("Couldn't create a source to reconnect", e);
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            live.stop(message);
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this),
                    java.text.MessageFormat.format(Messages.get("source.reconnect.failed"), message),
                    Messages.get("source.error.title"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        log.info("Reconnecting {}", LiveContent.sourceName(fresh));
        show(context -> new LiveContent(context, fresh, live.factory(), live.parser(), true));
    }

    // ── Sessions (UC-17, D-19) ────────────────────────────────────────────────

    /** How this tab was opened, so a session can open it again; null for a tab a session can't keep. */
    private Session.Tab sessionOrigin;

    /** Records how this tab was opened (file, merged files, SSH or plugin source). */
    void setSessionOrigin(Session.Tab origin) {
        sessionOrigin = origin;
    }

    /**
     * This tab as a session keeps it: how it was opened, plus its follow mode, timezone,
     * merged files' settings, column layout and filters. A tab that hasn't loaded (not
     * selected yet, or its file is missing) is kept exactly as it was restored. Null if the
     * tab can't be reopened.
     */
    Session.Tab sessionTab() {
        if (sessionOrigin == null) return null;
        if (restored.keepsOrigin()) return sessionOrigin;
        Session.Tab t = sessionOrigin;
        if (t.kind() == Session.Kind.MERGED && content instanceof MergedContent merged) {
            t = t.withFiles(merged.sessionFiles());
        } else if (t.kind() != Session.Kind.MERGED) {
            t = t.withTimezone(shared.timezone().getId());
        }
        Session.Layout layout = RestoredTab.toSession(shared.tableView().sessionLayout());
        return t.withState(shared.follow(), layout, shared.isPlain() ? null : shared.filterBar().state());
    }

    /**
     * Sets up a restored tab before it loads: follow mode, column layout and filters; the
     * filter strip says which filters came back. {@code notes} are extra lines for the strip
     * (a parser that no longer exists), and {@code sessionName} names a named session, or
     * is null for the last session.
     */
    void restoreState(Session.Tab t, List<String> notes, String sessionName) {
        sessionOrigin = t;
        shared.setFollow(t.follow());
        if (t.columns() != null) shared.tableView().restoreLayout(RestoredTab.fromSession(t.columns()));
        restored.restoreFilters(t.filters(), notes, sessionName, shared.follow());
    }

    /** Sets the file a not-yet-loaded tab will show, so "one tab per file" finds it. */
    void presetFile(Path file, String entry) {
        presetFile = file.toAbsolutePath();
        presetEntry = entry;
        String shown = entry == null ? presetFile.toString() : presetFile + " › " + entry;
        shared.statusBar().setPath(shown, shown);
    }

    /**
     * Holds the tab's load until it is first shown, so a large session opens quickly: only
     * each window's selected tab loads at once (D-19).
     */
    void deferLoad(Runnable load) {
        restored.deferLoad(load);
    }

    /** Whether the tab is waiting for its first showing before it loads. */
    boolean isDeferred() { return restored.isDeferred(); }

    /**
     * Shows a message instead of rows (a missing file, a source that needs a password),
     * with a button that runs {@code action}, or none if {@code buttonText} is null.
     */
    void showMessage(String text, String buttonText, Runnable action) {
        restored.showMessage(text, buttonText, action);
    }

    // ── Source timezone ───────────────────────────────────────────────────────

    public ZoneId getSourceTimezone() { return shared.timezone(); }

    /** Sets the source timezone field and updates the status bar badge. Does NOT reload. */
    public void setSourceTimezone(ZoneId zone) {
        shared.setTimezone(zone);
    }

    /** Sets the source timezone, persists it for the current file, then reloads. */
    public void applySourceTimezone(ZoneId zone) {
        shared.setTimezone(zone);
        if (content instanceof FileContent file) {
            AppPrefs.setSourceTimezone(file.file(), zone);
        } else if (content instanceof MergedContent merged) {
            mergedSettings.clearZones();
            merged.files().forEach(f -> AppPrefs.setSourceTimezone(f.path(), zone));
        }
        shared.updateTimezoneBadge();
        reload();
    }

    /** Opens the timezone popup above this tab's status bar (the window decides what "all tabs" are). */
    public void showSourceTimezoneDialog(Component parent) {
        if (SwingUtilities.getWindowAncestor(this) instanceof MainFrame window) window.showTimezonePopup(this);
    }

    /** Where the timezone popup opens from: the status bar's timezone label. */
    JComponent timezoneAnchor() { return shared.statusBar().timezoneAnchor(); }

    /** Whether a merged tab's files are read in more than one timezone. */
    boolean zonesDiffer() { return mergedSettings.zonesDiffer(); }

    // ── The rest of the window's calls ────────────────────────────────────────

    public JTable getLogTable() { return shared.table(); }

    /** What the window's Edit and View menus act on. */
    SharedTabContext actions() { return shared; }

    /** Whether Reload File applies: the tab shows a file. */
    boolean canReload() { return content instanceof FileContent; }

    public void clearTable() { shared.clearTable(); }

    public void showAnnotationsDialog() { shared.showAnnotationsDialog(); }

    void warnIfAnnotationsNotSaved(boolean saved) { shared.warnIfAnnotationsNotSaved(saved); }

    public void applyDetailFontSize(int size) { shared.refreshDetail(); }

    /** Applies the saved table settings (row height, stripes) after the Settings dialog. */
    public void applyTableSettings() {
        JTable table = getLogTable();
        if (table == null) return;
        table.setRowHeight(AppPrefs.getRowHeight());
        table.repaint();
    }

    public void applyWrapLines(boolean wrap) { shared.applyWrapLines(wrap); }

    /** Rebuilds timestamp formatters from the current preference and refreshes the table and detail panel. */
    public void refreshTimezone() { shared.refreshTimezone(); }

    public void dispose() {
        if (content != null) {
            content.dispose();          // kept, so the closed tab still reports its state
            contentContext.revoke();
        }
        shared.dispose();
    }
}
