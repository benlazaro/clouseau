package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.core.AnnotationStore;
import com.droidenx.clouseau.core.MergeOrder;
import com.droidenx.clouseau.core.MergedEntries;
import com.droidenx.clouseau.core.MergedNotes;
import com.droidenx.clouseau.core.Notes;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.TableModelEvent;
import java.awt.*;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * What every tab has, whatever it shows (D-21): the table and its model, the filter, find
 * and highlight bars, the detail pane, notes and the annotations dialog, follow mode, the
 * source timezone, the status bar and the center cards (rows, loading, plain text,
 * message); and the steps that touch several of them. One per {@link TabPanel}, shared by
 * every content the tab shows; contents reach it only through their own
 * {@link TabContext}. EDT only.
 */
final class SharedTabContext {

    /** What the shared parts need from the tab that owns them. */
    interface Host {
        /** What the tab shows, or null. */
        TabContent content();
        /** The user turned follow mode on or off (the filter bar's checkbox). */
        void setFollow(boolean on);
        /** The user changed a filter. */
        void filtersChanged();
        /** Whether a merged tab's files are read in more than one timezone. */
        boolean zonesDiffer();
        /** The user clicked the status bar's timezone badge. */
        void showTimezoneDialog();
        void showTimeOffsetDialog(int source);
        void showSourceTimezoneDialog(int source);
        void showParserDialog(int source);
        /** Whether the row menu offers Reload: the tab shows a file. */
        boolean canReload();
        void reload();
    }

    private static final String CARD_ROWS = "content";
    private static final String CARD_LOADING = "loading";
    private static final String CARD_PLAIN = "plain";
    private static final String CARD_MESSAGE = "message";

    private final JComponent tab;
    private final Host host;
    /** Replaced by {@link #setParsers} after the user creates a parser. */
    private List<LogParser> parsers;
    private final LogTableModel model = new LogTableModel();
    private final FilterBar filterBar;
    private final LogTableView tableView;
    private final JTable table;
    private final DetailPanel detailPanel;
    /** The table's right-click menu; Edit → Add Note… opens its note dialog. */
    private final RowMenu[] rowMenu = new RowMenu[1];
    private final FindBar findBar;
    private final HighlightNavBar highlightNavBar;
    private final JPanel centerCards = new JPanel(new CardLayout());
    private final TabStatusBar statusBar;

    private boolean follow = AppPrefs.isFollowByDefault();
    private ZoneId timezone = ZoneId.systemDefault();
    /** One file's {@link AnnotationStore}, or a merged view's {@link MergedNotes} (D-17). */
    private Notes notes = AnnotationStore.inMemory();
    /** True once this tab has told the user its annotations couldn't be saved. */
    private boolean annotationSaveWarned;
    private JDialog annotationsDialog;
    private AnnotationsPanel annotationsPanel;
    /** True once a followed file was truncated in place, so older rows lost their text (D-16). */
    private boolean textLost;
    /** The running filter's progress while it reads text from the file, or -1. */
    private int filterPercent = -1;
    /** The plain-text view shown instead of the rows (UC-13), or null. Its content owns it. */
    private PlainTextView plainView;

    SharedTabContext(JComponent tab, Host host, List<LogParser> parsers,
                     List<LogFormatter> formatters, List<LogSyntaxHighlighter> highlighters) {
        this.tab = tab;
        this.host = host;
        this.parsers = parsers;
        FilterBar[] fb = new FilterBar[1];
        fb[0] = new FilterBar(() -> {
            model.applyFilter(fb[0].buildFilter());
            host.filtersChanged();
        });
        filterBar = fb[0];
        filterBar.initFollow(follow, host::setFollow);
        StatusHolder status = new StatusHolder();
        model.setFilterProgressListener(percent -> {
            filterPercent = percent;
            if (status.ready) updateStatusBar();
        });
        model.applyFilter(filterBar.buildFilter());

        // The find bar is built after the table, which it acts on; the renderer checks for null.
        FindBar[] find = new FindBar[1];
        RowMenu[] menu = rowMenu;
        tableView = new LogTableView(model, new LogTableView.Host() {
            @Override public Notes notes() { return notes; }
            @Override public boolean isFindMatch(int modelRow) { return find[0] != null && find[0].isMatch(modelRow); }
            @Override public void showRowMenu(List<Integer> modelRows, int x, int y) { menu[0].show(modelRows, x, y); }
            @Override public void editNote(int modelRow) { if (menu[0] != null) menu[0].editNote(modelRow); }
        });
        table = tableView.table();
        HighlightNavBar[] nav = new HighlightNavBar[1];
        menu[0] = new RowMenu(model, table, new RowMenu.Host() {
            @Override public Notes notes() { return notes; }
            @Override public void notesSaved(boolean saved) { warnIfAnnotationsNotSaved(saved); }

            @Override public void highlightsChanged() {
                refreshDetail();
                nav[0].refresh();
            }

            @Override public void notesChanged() {
                table.repaint();
                refreshDetail();
                if (annotationsPanel != null && annotationsDialog != null && annotationsDialog.isVisible())
                    annotationsPanel.refresh();
            }

            @Override public void showFrom(java.time.Instant at) { filterBar.showFrom(at); }
            @Override public void showUpTo(java.time.Instant at) { filterBar.showUpTo(at); }
            @Override public MergedEntries mergedView() { return SharedTabContext.this.mergedView(); }
            @Override public void showTimeOffsetDialog(int source) { host.showTimeOffsetDialog(source); }
            @Override public void showSourceTimezoneDialog(int source) { host.showSourceTimezoneDialog(source); }
            @Override public void showParserDialog(int source) { host.showParserDialog(source); }
            @Override public void showAnnotationsDialog() { SharedTabContext.this.showAnnotationsDialog(); }
            @Override public boolean canReload() { return host.canReload(); }
            @Override public void reload() { host.reload(); }
            @Override public void clearTable() { SharedTabContext.this.clearTable(); }
            @Override public boolean canClearTable() { return SharedTabContext.this.canClearTable(); }
            @Override public FilterBar filterBar() { return filterBar; }
        });

        // The time popup's "Use selected row" reads the selected row's (corrected) time.
        filterBar.setSelectedTime(() -> {
            int viewRow = table.getSelectedRow();
            LogEntry e = viewRow < 0 ? null : model.getEntry(table.convertRowIndexToModel(viewRow));
            return e != null ? e.timestamp() : null;
        });

        detailPanel = new DetailPanel(formatters, highlighters, this::currentDetail);
        JSplitPane split = new RememberedSplit(tableView.scrollPane(), detailPanel);
        // Ctrl+C copies the selected line's message, Ctrl+Alt+C its raw text, Ctrl+Shift+C the
        // whole entry, from the table or the detail pane alike; the pane flashes and confirms
        // what was copied. Ctrl+C is bound on the table itself, ahead of its row copy (tab-joined
        // cells), so the pane keeps its own Ctrl+C for selected text.
        table.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, java.awt.event.InputEvent.CTRL_DOWN_MASK), "copyMessage");
        table.getActionMap().put("copyMessage", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { detailPanel.copyMessage(); }
        });
        InputMap keys = split.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        keys.put(DetailPanel.COPY_RAW_KEY, "copyRawLine");
        keys.put(DetailPanel.COPY_ENTRY_KEY, "copyEntry");
        split.getActionMap().put("copyRawLine", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { detailPanel.copyRaw(); }
        });
        split.getActionMap().put("copyEntry", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { detailPanel.copyEntry(); }
        });
        centerCards.add(split, CARD_ROWS);
        centerCards.add(buildLoadingPanel(), CARD_LOADING);

        statusBar = new TabStatusBar(host::showTimezoneDialog);
        status.ready = true;
        updateTimezoneBadge();
        findBar = find[0] = new FindBar(table, FindBar.searchIn(model));
        highlightNavBar = nav[0] = new HighlightNavBar(table, model);
        ClouseauColors.onThemeChange(table, this::reapplyHighlightsForTheme);

        model.addTableModelListener(e -> {
            TabContent content = host.content();
            boolean loading = content != null && content.loading();
            if (follow && !loading && e.getType() == TableModelEvent.INSERT)
                SwingUtilities.invokeLater(tableView::scrollToBottom);
            updateStatusBar();
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshDetail();
                highlightNavBar.updatePosition();
            }
        });
    }

    /** Set once the status bar exists, so the filter's progress can show on it. */
    private static final class StatusHolder { boolean ready; }

    // ── Parts, for the tab's layout ──────────────────────────────────────────

    FilterBar filterBar() { return filterBar; }
    FindBar findBar() { return findBar; }
    HighlightNavBar highlightNavBar() { return highlightNavBar; }
    JComponent centerCards() { return centerCards; }
    TabStatusBar statusBar() { return statusBar; }
    LogTableModel model() { return model; }
    LogTableView tableView() { return tableView; }
    JTable table() { return table; }
    Notes notes() { return notes; }

    Window window() { return SwingUtilities.getWindowAncestor(tab); }

    // ── What the tab knows ───────────────────────────────────────────────────

    List<LogParser> parsers() { return parsers; }

    /** Takes the window's parsers after the user saved one. */
    void setParsers(List<LogParser> parsers) { this.parsers = parsers; }

    boolean follow() { return follow; }

    /** Records follow mode and ticks the filter bar's checkbox; the content starts or stops following. */
    void setFollow(boolean on) {
        follow = on;
        filterBar.setFollowSelected(on);
    }

    ZoneId timezone() { return timezone; }

    /** Sets the source timezone and updates the status bar badge. Doesn't reload. */
    void setTimezone(ZoneId zone) {
        timezone = zone;
        updateTimezoneBadge();
    }

    void updateTimezoneBadge() {
        statusBar.setTimezone(timezone, host.zonesDiffer());
    }

    /** The merged view the table shows, or null. */
    MergedEntries mergedView() {
        return model.entryRows() instanceof MergedEntries m ? m : null;
    }

    /** True while a file is shown as plain text instead of rows (UC-13). */
    boolean isPlain() { return plainView != null; }

    // ── Steps ────────────────────────────────────────────────────────────────

    /**
     * Empties the tab for a content's load (D-21): plain-text mode, rows, notes, what an
     * earlier load left in the status bar, and, unless {@code keepFilters}, the logger
     * filter and column layout.
     */
    void reset(boolean keepFilters) {
        leavePlain();
        textLost = false;
        notes = AnnotationStore.inMemory();
        annotationSaveWarned = false;
        model.clear();
        if (!keepFilters) {
            filterBar.clearLoggers();
            tableView.resetColumns();
        }
    }

    void showLoading() { showCard(CARD_LOADING); }

    void showRows() { showCard(CARD_ROWS); }

    /** Adds the message card a restored tab shows instead of rows (D-19). */
    void addMessageCard(JComponent card) { centerCards.add(card, CARD_MESSAGE); }

    void showMessageCard(boolean show) { showCard(show ? CARD_MESSAGE : CARD_ROWS); }

    private void showCard(String name) {
        ((CardLayout) centerCards.getLayout()).show(centerCards, name);
    }

    /** Shows a file as plain text instead of rows: hides the bars that act on rows (UC-13). */
    void showPlain(PlainTextView view) {
        leavePlain();
        findBar.cancel();
        filterBar.setVisible(false);
        findBar.setVisible(false);
        highlightNavBar.setVisible(false);
        plainView = view;              // only now: the status bar asks it for its line count
        centerCards.add(view, CARD_PLAIN);
        showCard(CARD_PLAIN);
        updateStatusBar();
    }

    /** Back to the rows; the plain-text view's content stops it. */
    private void leavePlain() {
        if (plainView == null) return;
        centerCards.remove(plainView);
        plainView = null;
        filterBar.setVisible(true);
        highlightNavBar.refresh();
        showCard(CARD_ROWS);
    }

    // ── The window's Edit and View menus act on the active tab through these ──────────

    /** Sets what the first column counts, and its title: Line on a file, # otherwise. */
    void setNumbering(LogTableModel.Numbering n) {
        model.setNumbering(n);
        tableView.numberingChanged();
    }

    /** Clear Table empties what a live source streamed; a file is reloaded instead. */
    boolean canClearTable() { return host.content() instanceof LiveContent; }

    boolean hasEntry() { return plainView == null && detailPanel.hasEntry(); }

    void copyMessage() { detailPanel.copyMessage(); }
    void copyRaw()     { detailPanel.copyRaw(); }
    void copyEntry()   { detailPanel.copyEntry(); }

    /** F3 / Shift+F3: the next or previous match, or the find bar when nothing is searched yet. */
    void findStep(int direction) {
        if (plainView == null && findBar.hasQuery()) findBar.navigate(direction);
        else openFind();
    }

    /** The one selected row's model index, or -1 (none, several, or the plain-text view). */
    private int singleSelectedRow() {
        if (plainView != null || table.getSelectedRowCount() != 1) return -1;
        return table.convertRowIndexToModel(table.getSelectedRow());
    }

    boolean canEditNote() { return singleSelectedRow() >= 0; }

    /** Whether the selected row already has a note: the menu then says Edit Note…. */
    boolean selectedHasNote() {
        int row = singleSelectedRow();
        return row >= 0 && notes.at(model.ordinalAt(row)) != null;
    }

    void editNote() {
        int row = singleSelectedRow();
        if (row >= 0) rowMenu[0].editNote(row);
    }

    boolean hasNotes() { return notes.size() > 0; }

    boolean wrapLines() { return detailPanel.wrapLines(); }
    void toggleWrap()   { detailPanel.toggleWrap(); }
    void changeTextSize(int step) { detailPanel.changeTextSize(step); }

    void clearFilters() { filterBar.clear(); }

    /** Ctrl+F: the plain-text view's find, or the find bar. */
    void openFind() {
        if (plainView != null) plainView.openFind();
        else findBar.open();
    }

    /** The rows are loaded with these notes (null: keep the empty ones): show them and their highlights. */
    void loaded(Notes loaded) {
        if (loaded != null) notes = loaded;
        applySavedHighlights();
    }

    /**
     * Shows rows whose order changed from {@code from} on ({@code before} still holds the
     * old one): re-filters, and keeps highlights, notes and the selection on their entries.
     */
    void reordered(MergeOrder before, MergeOrder after, int from) {
        IntUnaryOperator moved = o -> after.ordinalOf(before.source(o), before.row(o));
        int viewRow = table.getSelectedRow();
        int selected = viewRow >= 0 ? model.ordinalAt(table.convertRowIndexToModel(viewRow)) : -1;
        boolean done = model.reorderTail(from, moved, after.size());
        if (notes instanceof MergedNotes merged) merged.setOrder(after);
        findBar.clearMatches();
        Runnable restore = () -> {
            if (selected >= 0) {
                int row = model.rowOfOrdinal(selected >= from ? moved.applyAsInt(selected) : selected);
                int v = row >= 0 ? table.convertRowIndexToView(row) : -1;
                if (v >= 0) {
                    table.setRowSelectionInterval(v, v);
                    if (!follow) table.scrollRectToVisible(table.getCellRect(v, 0, true));
                }
            }
            if (follow) tableView.scrollToBottom();
            refreshDetail();
        };
        if (done) restore.run();
        else model.whenFiltered(restore);
    }

    /** A followed file was truncated in place, so older rows lost their text (D-16). */
    void textLost() {
        textLost = true;
        table.repaint();
        refreshDetail();
        updateStatusBar();
    }

    /** Counts, notes or timezones changed: repaint the rows and update the status bar. */
    void statusChanged() {
        table.repaint();
        updateStatusBar();
        updateTimezoneBadge();
    }

    /** Empties the table after asking; the content follows on from here (or stops showing what it loaded). */
    void clearTable() {
        int confirm = JOptionPane.showConfirmDialog(window(),
                Messages.get("table.clear.confirm.message"),
                Messages.get("table.clear.confirm.title"),
                JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        TabContent content = host.content();
        if (content != null) content.setFollow(false);
        model.clear();
        textLost = false;
        if (content != null) content.cleared();
        refreshDetail();
        findBar.clearMatches();
        updateStatusBar();
        if (follow && content != null) content.setFollow(true);
    }

    void updateStatusBar() {
        if (plainView != null) {
            statusBar.setPlainLines(plainView.lineCount());
            return;
        }
        TabContent content = host.content();
        String note = content != null ? content.statusNote() : "";
        if (textLost) note += Messages.get("status.rotated");
        if (filterPercent >= 0) note += MessageFormat.format(Messages.get("status.filtering"), filterPercent);
        statusBar.setRows(model.getRowCount(), model.lineCount(), model.getTrimmedCount(), note);
    }

    // ── Notes and the detail pane ────────────────────────────────────────────

    /**
     * Repaints highlighted rows in the new theme's palette. The model holds colors, but notes
     * store palette slots (DESIGN.md D-15), so the slots are applied again.
     */
    private void reapplyHighlightsForTheme() {
        model.clearAllHighlights();
        applySavedHighlights();
    }

    /** Shows the highlights saved with the file on the rows they attached to. */
    private void applySavedHighlights() {
        Color[] palette = ClouseauColors.highlightColors();
        notes.highlights().forEach((ord, slot) -> {
            if (slot >= 0 && slot < palette.length) model.setHighlightAtOrdinal(ord, palette[slot]);
        });
        highlightNavBar.refresh();
    }

    /**
     * Tells the user, once per opened file, that annotations couldn't be saved next to
     * the log or in the data folder. They stay in memory until the tab closes.
     */
    void warnIfAnnotationsNotSaved(boolean saved) {
        if (saved || annotationSaveWarned) return;
        annotationSaveWarned = true;
        JOptionPane.showMessageDialog(window(),
                MessageFormat.format(Messages.get("annotation.save.failed.message"),
                        String.valueOf(notes.getSavePath())),
                Messages.get("annotation.save.failed.title"),
                JOptionPane.WARNING_MESSAGE);
    }

    /** Opens (or brings to front) the non-modal annotations panel dialog. */
    void showAnnotationsDialog() {
        Window owner = window();
        if (annotationsDialog == null || !annotationsDialog.isDisplayable()) {
            annotationsPanel = new AnnotationsPanel(
                    () -> notes, model, table,
                    () -> annotationsDialog.setVisible(false),
                    saved -> { warnIfAnnotationsNotSaved(saved); refreshDetail(); },
                    ordinal -> {
                        int row = model.rowOfOrdinal(ordinal);
                        if (row >= 0 && rowMenu[0] != null) rowMenu[0].editNote(row);
                    });
            annotationsDialog = new JDialog(owner, Messages.get("annotations.dialog.title"),
                    Dialog.ModalityType.MODELESS);
            annotationsDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            annotationsDialog.add(annotationsPanel);
            annotationsDialog.setSize(880, 360);
            annotationsDialog.setMinimumSize(new Dimension(420, 220));
            annotationsDialog.setLocationRelativeTo(owner);
        } else {
            annotationsPanel.refresh();
        }
        annotationsDialog.setVisible(true);
        annotationsDialog.toFront();
    }

    /** What the detail panel should show for the selected row, or null. */
    private DetailPanel.Content currentDetail() {
        int viewRow = table.getSelectedRow();
        int modelRow = viewRow >= 0 ? table.convertRowIndexToModel(viewRow) : -1;
        LogEntry entry = modelRow >= 0 ? model.getEntry(modelRow) : null;
        if (entry == null) return null;
        int ordinal = model.ordinalAt(modelRow);
        MergedEntries merged = mergedView();
        long shift = merged != null && ordinal >= 0 && ordinal < merged.size()
                ? merged.offsetMillis(merged.sourceOf(ordinal)) : 0;
        return new DetailPanel.Content(entry, model.getHighlight(modelRow),
                notes.at(ordinal), model.getContinuationLines(modelRow), shift, where(modelRow, ordinal, merged));
    }

    /**
     * Where an entry is, said so it can't be taken for anything else: "file line 13" on a
     * file, "db.log, line 13" in a merged view (the file named, so just "line"), and
     * "#1042 since connecting" on a live source, which isn't a line of any file.
     */
    private String where(int modelRow, int ordinal, MergedEntries merged) {
        if (ordinal < 0) return null;
        String line = String.valueOf(model.lineNumber(modelRow));
        return switch (model.numbering()) {
            case FILE_LINES -> MessageFormat.format(Messages.get("detail.title.file.line"), line);
            case POSITION -> merged != null && ordinal < merged.size()
                    ? MessageFormat.format(Messages.get("detail.title.merged.line"),
                            merged.sources().get(merged.sourceOf(ordinal)).name(), line)
                    : null;
            case RECEIVED -> MessageFormat.format(Messages.get("detail.title.received"), String.valueOf(ordinal + 1));
        };
    }

    void refreshDetail() {
        detailPanel.refresh();
    }

    void applyWrapLines(boolean wrap) {
        detailPanel.setWrapLines(wrap);
    }

    /** Rebuilds timestamp formatters from the current preference and refreshes the table and detail panel. */
    void refreshTimezone() {
        model.refreshTimezone();
        detailPanel.refreshTimezone();
    }

    /** The tab closed: release the model. Its content was disposed first. */
    void dispose() {
        model.dispose();
    }

    private static JPanel buildLoadingPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        ClouseauColors.themedBackground(panel, ClouseauColors::loadingBackground);

        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setBorderPainted(false);
        ClouseauColors.themedForeground(bar, ClouseauColors::progressForeground);
        ClouseauColors.themedBackground(bar, ClouseauColors::progressBackground);
        bar.setPreferredSize(new Dimension(280, 4));

        JLabel label = new JLabel(Messages.get("loading.message"), SwingConstants.CENTER);
        ClouseauColors.themedForeground(label, ClouseauColors::dimForeground);
        label.setFont(label.getFont().deriveFont(13f));

        JPanel inner = new JPanel(new MigLayout("insets 0", "[280!]", "[]10[]"));
        inner.setOpaque(false);
        inner.add(bar, "wrap, h 4!");
        inner.add(label, "growx");

        panel.add(inner, BorderLayout.CENTER);
        return panel;
    }

    /**
     * The table over the detail pane. A new tab opens at the ratio the user last dragged the
     * divider to, in any tab (kept in the settings, so it outlasts a restart), and keeps that
     * ratio as the window resizes.
     */
    static final class RememberedSplit extends JSplitPane {
        private final java.awt.event.MouseListener onRelease = new java.awt.event.MouseAdapter() {
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { remember(); }
        };

        RememberedSplit(Component top, Component bottom) {
            super(JSplitPane.VERTICAL_SPLIT, top, bottom);
            double ratio = AppPrefs.getDetailSplitRatio();
            setResizeWeight(ratio);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override public void componentResized(java.awt.event.ComponentEvent e) {
                    if (getHeight() <= 0) return;
                    removeComponentListener(this);
                    setDividerLocation(ratio);
                }
            });
            hookDivider();
        }

        /** A theme switch installs a new UI, and with it a new divider to listen to. */
        @Override public void updateUI() {
            super.updateUI();
            hookDivider();
        }

        private void hookDivider() {
            if (onRelease != null && getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI ui) {
                ui.getDivider().removeMouseListener(onRelease);
                ui.getDivider().addMouseListener(onRelease);
            }
        }

        /** The ratio the user just dragged to: for new tabs, and for this one as it resizes. */
        private void remember() {
            int space = getHeight() - getDividerSize();
            if (space <= 0) return;
            double ratio = AppPrefs.clampSplitRatio((double) getDividerLocation() / space);
            setResizeWeight(ratio);
            AppPrefs.setDetailSplitRatio(ratio);
        }
    }
}
