package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.MergeOrder;
import com.droidenx.clouseau.core.Notes;

import java.awt.*;
import java.time.ZoneId;
import java.util.List;

/**
 * The tab as a {@link TabContent} sees it, and its only way in (D-21): the shared parts,
 * what the tab knows (parsers, follow mode, its window), and the steps that touch several
 * parts at once.
 *
 * <p>Each content gets its own, which is withdrawn once the content is replaced: then the
 * steps do nothing and {@link #model}, {@link #filterBar} and {@link #tableView} throw
 * {@link IllegalStateException}, so a late {@code process} or {@code done} from an old
 * worker can't touch the new content's rows. Call it on the EDT only, and ask for the parts
 * each time rather than keeping them.
 */
interface TabContext {

    LogTableModel model();

    FilterBar filterBar();

    LogTableView tableView();

    /** The parsers the tab offers, for per-line detection. */
    List<LogParser> parsers();

    /** Whether the tab follows new rows. */
    boolean follow();

    /** The window the tab is in, for dialogs; null if it isn't in one. */
    Window window();

    /**
     * Empties the tab for a new load: rows, notes, plain-text mode, and what an earlier load
     * left behind; also the logger filter and the column layout unless {@code keepFilters}.
     */
    void reset(boolean keepFilters);

    /** Shows the loading card instead of the rows. */
    void showLoading();

    /** Shows the rows. */
    void showRows();

    /** The source timezone the tab reads timestamps in. */
    ZoneId timezone();

    /** Shows a file as plain text instead of the rows (UC-13); the content owns the view. */
    void showPlain(PlainTextView view);

    /** Turns follow mode on or off, as the user would (e.g. from the plain-text view). */
    void setFollow(boolean on);

    /** Opens the parser editor with these sample lines (UC-13's "Create a parser"). */
    void createParser(List<String> samples);

    /** Closes the tab, e.g. when the user declines to open a binary file. */
    void close();

    /** The content's {@link TabContent#path} changed: show it in the status bar again. */
    void pathChanged();

    /** The rows are loaded with these notes (null: keep the empty ones): show them and their highlights. */
    void loaded(Notes notes);

    /**
     * The rows' order changed from {@code from} on ({@code before} still holds the old
     * order): re-filter, and keep highlights, notes and the selection on their entries.
     */
    void reordered(MergeOrder before, MergeOrder after, int from);

    /** A followed file was truncated in place, so older rows lost their text (D-16). */
    void textLost();

    /** Counts, notes or timezones changed: repaint the rows and update the status bar. */
    void statusChanged();

    /** The live source's connection changed, so the window's dot may too (D-20). */
    void connectionChanged();
}
