package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.core.AnnotationStore;
import com.droidenx.clouseau.core.MergedEntries;
import com.droidenx.clouseau.core.Notes;
import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.miginfocom.swing.MigLayout;

/**
 * The menu shown when the user right-clicks rows of a tab's table (DESIGN.md 6.14): copying the
 * selected line, highlight colors (D-15), the time range from a row, the rows' loggers, adding,
 * editing and removing a note, and the row's file on a merged tab (offset, timezone, parser). It
 * holds only what acts on those rows (D-25): Reload File, Clear Table… and All Notes… are in the
 * menu bar. {@link TabPanel} builds one and reaches back through {@link Host}. EDT only.
 */
final class RowMenu {

    /** What the menu asks of its tab. */
    interface Host {
        /** The tab's notes and highlights. */
        Notes notes();

        /** Tells the user, once, if notes couldn't be saved ({@code saved} false). */
        void notesSaved(boolean saved);

        /** Highlights changed: refresh the detail panel and the highlight bar. */
        void highlightsChanged();

        /** A note was added, changed or removed: repaint, refresh the detail panel and notes panel. */
        void notesChanged();

        /** Shows rows from {@code at} on (the filter bar's time range start). */
        void showFrom(java.time.Instant at);

        /** Shows rows up to {@code at} (the filter bar's time range end). */
        void showUpTo(java.time.Instant at);

        /** The merged view the tab shows, or null. */
        MergedEntries mergedView();

        void showTimeOffsetDialog(int source);

        void showSourceTimezoneDialog(int source);

        void showParserDialog(int source);

        /** Copies the selected line's message, its raw line or the whole entry, as the Edit menu does. */
        void copyMessage();

        void copyRaw();

        void copyEntry();

        /** The filter bar, which the logger items change (Hide, Show Only, Show All). */
        FilterBar filterBar();
    }

    private final LogTableModel logTableModel;
    private final JTable logTable;
    private final Host host;

    RowMenu(LogTableModel model, JTable table, Host host) {
        this.logTableModel = model;
        this.logTable = table;
        this.host = host;
    }

    /** Shows the menu for {@code entries} (model rows, possibly none) at a point in the table. */
    void show(List<Integer> entries, int x, int y) {
        JPopupMenu menu = new JPopupMenu();

        if (!entries.isEmpty()) {
            // ── Copy, named and keyed as in the Edit menu ─────────────────────────
            menu.add(copyItem("menu.edit.copy.message",
                    KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), host::copyMessage));
            menu.add(copyItem("menu.edit.copy.raw", DetailPanel.COPY_RAW_KEY, host::copyRaw));
            menu.add(copyItem("menu.edit.copy.entry", DetailPanel.COPY_ENTRY_KEY, host::copyEntry));
            menu.addSeparator();

            // ── Highlight: the swatch row (None, then the eight colors) ────────────
            Color[] palette = ClouseauColors.highlightColors();
            String header = entries.size() == 1 ? Messages.get("highlight.header")
                    : java.text.MessageFormat.format(Messages.get("highlight.header.many"), entries.size());
            menu.add(swatchRow(header, markedSlot(entries, logTableModel::getHighlight), slot -> {
                // None acts at once: the user just chose these rows (DESIGN.md 6.14).
                if (slot == NONE) setHighlights(entries, null, null);
                else setHighlights(entries, palette[slot], slot);
                host.highlightsChanged();
            }));

            // Clear All Highlights acts on the whole tab, so it's in the Edit menu and on the
            // highlight bar, not here (D-25).
            menu.addSeparator();
        }

        // ── Time range from this row ──────────────────────────────────────────
        if (entries.size() == 1) {
            LogEntry row = logTableModel.getEntry(entries.get(0));
            java.time.Instant at = row != null ? row.timestamp() : null;
            JMenuItem fromItem = new JMenuItem(Messages.get("table.context.show.from"));
            JMenuItem upToItem = new JMenuItem(Messages.get("table.context.show.upto"));
            fromItem.setEnabled(at != null);     // a continuation line has no time
            upToItem.setEnabled(at != null);
            fromItem.addActionListener(e -> host.showFrom(at));
            upToItem.addActionListener(e -> host.showUpTo(at));
            menu.add(fromItem);
            menu.add(upToItem);
        }
        // Undoes the two above, and a range typed in the Time popup; it names the range it drops.
        FilterBar timeFilters = host.filterBar();
        boolean ranged = timeFilters.hasTimeRange();
        String from = timeFilters.timeFrom(), to = timeFilters.timeTo();
        JMenuItem allTimes = new JMenuItem(!ranged ? Messages.get("table.context.time.all")
                : to.isEmpty()   ? java.text.MessageFormat.format(Messages.get("table.context.time.all.from"), from)
                : from.isEmpty() ? java.text.MessageFormat.format(Messages.get("table.context.time.all.upto"), to)
                : java.text.MessageFormat.format(Messages.get("table.context.time.all.range"), timeFilters.timeRangeLabel()));
        allTimes.setEnabled(ranged);
        allTimes.addActionListener(e -> timeFilters.showAllTimes());
        menu.add(allTimes);
        menu.addSeparator();

        // ── Loggers: hide the rows' loggers, or show only them ──────────────────
        addLoggerItems(menu, entries);

        // ── Annotation section ────────────────────────────────────────────────
        if (entries.size() == 1) {
            int targetRow = entries.get(0);
            int ordinal = logTableModel.ordinalAt(targetRow);
            LogEntry annotationTarget = logTableModel.getEntry(targetRow);
            AnnotationStore.Annotation existing = host.notes().at(ordinal);

            JMenuItem addEditItem = new JMenuItem(
                    existing != null ? Messages.get("annotation.edit") : Messages.get("annotation.add"));
            addEditItem.addActionListener(e -> showAnnotationDialog(annotationTarget, ordinal, existing));
            menu.add(addEditItem);

            JMenuItem removeItem = new JMenuItem(Messages.get("annotation.remove"));
            removeItem.setEnabled(existing != null);
            if (existing != null) {
                removeItem.addActionListener(e -> {
                    host.notesSaved(host.notes().remove(ordinal));
                    host.notesChanged();
                });
            }
            menu.add(removeItem);
            menu.addSeparator();
        }

        // ── The row's file, on a merged tab (D-17) ───────────────────────────────
        MergedEntries merged = host.mergedView();
        if (entries.size() == 1 && merged != null) {
            int ordinal = logTableModel.ordinalAt(entries.get(0));
            if (ordinal >= 0 && ordinal < merged.size()) {
                int source = merged.sourceOf(ordinal);
                String name = merged.sourceName(source);
                JMenuItem offsetItem = new JMenuItem(java.text.MessageFormat.format(
                        Messages.get("merge.offset.menu"), name));
                offsetItem.addActionListener(e -> host.showTimeOffsetDialog(source));
                menu.add(offsetItem);
                JMenuItem zoneItem = new JMenuItem(java.text.MessageFormat.format(
                        Messages.get("merge.timezone.menu"), name));
                zoneItem.addActionListener(e -> host.showSourceTimezoneDialog(source));
                menu.add(zoneItem);
                JMenuItem parserItem = new JMenuItem(java.text.MessageFormat.format(
                        Messages.get("merge.parser.menu"), name));
                parserItem.addActionListener(e -> host.showParserDialog(source));
                menu.add(parserItem);
                menu.addSeparator();
            }
        }

        // Each group ends with its own separator; drop the last one.
        while (menu.getComponentCount() > 0
                && menu.getComponent(menu.getComponentCount() - 1) instanceof JPopupMenu.Separator) {
            menu.remove(menu.getComponentCount() - 1);
        }
        menu.show(logTable, x, y);
    }

    private static JMenuItem copyItem(String key, KeyStroke accelerator, Runnable action) {
        JMenuItem item = new JMenuItem(Messages.get(key));
        item.setAccelerator(accelerator);
        item.addActionListener(e -> action.run());
        return item;
    }

    /** Highlights model rows (null clears them) and saves them with the annotations (D-15). */
    /**
     * Removes every highlight in the tab, the ones the filters hide too, after asking (unless
     * the user turned that off), and saves. Edit → Clear All Highlights… and the highlight bar's Clear All.
     */
    void clearAllHighlights() {
        if (!logTableModel.hasHighlights() && host.notes().highlights().isEmpty()) return;
        if (AppPrefs.isHighlightClearAllConfirm()) {
            int confirm = JOptionPane.showConfirmDialog(
                    SwingUtilities.getWindowAncestor(logTable),
                    Messages.get("highlight.clear.all.confirm.message"),
                    Messages.get("highlight.clear.all.confirm.title"),
                    JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
        }
        logTableModel.clearAllHighlights();
        host.notesSaved(host.notes().clearHighlights());
        host.highlightsChanged();
    }

    /**
     * Removes one highlight color from the tab, the rows the filters hide and the entries not
     * held in memory included, after asking as Clear All does, and saves. The highlight bar's
     * chip menu.
     */
    void clearHighlightColor(int slot) {
        Color color = ClouseauColors.highlightColors()[slot];
        Map<Integer, String> ordinals = new java.util.LinkedHashMap<>();
        host.notes().highlights().forEach((ord, s) -> { if (s == slot) ordinals.put(ord, ""); });
        for (int ord : logTableModel.highlightOrdinals(color)) ordinals.put(ord, "");
        if (ordinals.isEmpty()) return;
        if (AppPrefs.isHighlightClearAllConfirm()) {
            int confirm = JOptionPane.showConfirmDialog(
                    SwingUtilities.getWindowAncestor(logTable),
                    java.text.MessageFormat.format(Messages.get("highlight.clear.color.confirm.message"),
                            Messages.get("highlight.color." + slot), ordinals.size()),
                    Messages.get("highlight.clear.color.confirm.title"),
                    JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
        }
        logTableModel.clearHighlights(color);
        // A null slot clears: the raw lines aren't read.
        host.notesSaved(host.notes().setHighlights(ordinals, null));
        host.highlightsChanged();
    }

    /** The None swatch's slot: choosing it removes the highlight. */
    static final int NONE = -1;
    /** {@link #markedSlot} when the rows differ, so no swatch is ringed. */
    static final int NO_MARK = -2;

    /**
     * What every row in {@code rows} already is: a palette slot, {@link #NONE} when none of them
     * is highlighted, or {@link #NO_MARK} when they differ. Shared with the find bar.
     */
    static int markedSlot(List<Integer> rows, IntFunction<Color> highlightOf) {
        if (rows.isEmpty()) return NO_MARK;
        Color first = highlightOf.apply(rows.get(0));
        for (int r : rows) if (!java.util.Objects.equals(first, highlightOf.apply(r))) return NO_MARK;
        if (first == null) return NONE;
        Color[] palette = ClouseauColors.highlightColors();
        for (int i = 0; i < palette.length; i++) if (palette[i].equals(first)) return i;
        return NO_MARK;
    }

    /**
     * The swatch row of the row menu and the find bar's Highlight popup (DESIGN.md 6.14): a
     * header saying what it colors, then None and the eight colors, the one in {@code marked}
     * ringed in white. {@code pick} gets the chosen slot ({@link #NONE} for None) once the popup
     * has closed.
     */
    static SwatchRow swatchRow(String header, int marked, IntConsumer pick) {
        return new SwatchRow(header, marked, pick);
    }

    /**
     * The swatches as one menu element, so Up and Down reach the row as they reach a menu item,
     * Left and Right move along it, and Enter or Space applies the current swatch. The current
     * swatch, under the mouse or reached with the keys, sits on the menu's hover fill, as a menu
     * item does.
     */
    static final class SwatchRow extends JPanel implements MenuElement {
        private final List<Swatch> swatches = new java.util.ArrayList<>();
        private final IntConsumer pick;
        /** Where the keys start: the ringed swatch, else None. */
        private final int start;
        private int current = -1;
        private final JLabel title;
        /** Where a swatch's square starts inside its button (Swatch paints it 2px in). */
        private static final int SQUARE_INSET = 2;

        private SwatchRow(String header, int marked, IntConsumer pick) {
            super(new MigLayout("insets 0, gap 0"));
            this.pick = pick;
            setOpaque(false);
            title = new JLabel(header);
            ClouseauColors.themedForeground(title, ClouseauColors::dimForeground);
            add(title, "span, wrap");
            alignToMenuText();
            int colors = ClouseauColors.highlightColors().length;
            for (int slot = NONE; slot < colors; slot++) {
                Swatch swatch = new Swatch(slot, slot == marked);
                int index = swatches.size();
                swatch.addActionListener(e -> choose(index));
                swatch.addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover(index); }
                });
                swatches.add(swatch);
                add(swatch, "w 26!, h 26!");   // FlatLaf's button minimum width would stretch it
            }
            start = marked >= NONE ? marked - NONE : 0;
        }

        @Override public void addNotify() {
            super.addNotify();
            alignToMenuText();   // the popup's items are all in place by now
        }

        /**
         * Starts the header and the first square where the popup's menu items start their text,
         * so the row lines up with them (DESIGN.md 6.14). FlatLaf puts that text after the item's
         * inset, an icon column of {@code MenuItem.minimumIconSize} and the icon-text gap, so it's
         * worked out from a real item. A popup with no items (the find bar's) keeps a compact inset.
         */
        private void alignToMenuText() {
            int left = com.formdev.flatlaf.util.UIScale.scale(8);
            Container popup = getParent();
            if (popup != null) {
                for (Component c : popup.getComponents()) {
                    if (c instanceof JMenuItem item) {
                        Dimension icon = UIManager.getDimension("MenuItem.minimumIconSize");
                        int textX = item.getInsets().left
                                + com.formdev.flatlaf.util.UIScale.scale(icon != null ? icon.width : 16)
                                + com.formdev.flatlaf.util.UIScale.scale(item.getIconTextGap());
                        left = textX - SQUARE_INSET;
                        break;
                    }
                }
            }
            int s2 = com.formdev.flatlaf.util.UIScale.scale(2);
            setBorder(BorderFactory.createEmptyBorder(3 * s2, left, 2 * s2, 4 * s2));
            title.setBorder(BorderFactory.createEmptyBorder(0, SQUARE_INSET, s2, 0));
        }

        /** The mouse is on swatch {@code index}: it becomes current, and the row the menu's selection. */
        private void hover(int index) {
            JPopupMenu popup = (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, this);
            if (popup != null) MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{popup, this});
            setCurrent(index);
        }

        private void setCurrent(int index) {
            current = index;
            for (int i = 0; i < swatches.size(); i++) swatches.get(i).setCurrent(i == index);
        }

        private void choose(int index) {
            JPopupMenu popup = (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, this);
            MenuSelectionManager.defaultManager().clearSelectedPath();
            if (popup != null) popup.setVisible(false);
            pick.accept(swatches.get(index).slot);
        }

        @Override public void menuSelectionChanged(boolean selected) {
            if (!selected) setCurrent(-1);
            else if (current < 0) setCurrent(start);
        }

        @Override public void processKeyEvent(KeyEvent e, MenuElement[] path, MenuSelectionManager manager) {
            if (current < 0 || e.getID() != KeyEvent.KEY_PRESSED) return;
            int n = swatches.size();
            switch (e.getKeyCode()) {
                case KeyEvent.VK_LEFT  -> { setCurrent((current + n - 1) % n); e.consume(); }
                case KeyEvent.VK_RIGHT -> { setCurrent((current + 1) % n); e.consume(); }
                case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> { e.consume(); choose(current); }
                default -> { }
            }
        }

        @Override public void processMouseEvent(java.awt.event.MouseEvent e, MenuElement[] path,
                                                MenuSelectionManager manager) { }

        @Override public MenuElement[] getSubElements() { return new MenuElement[0]; }

        @Override public Component getComponent() { return this; }

        /** The swatch that's current, or -1: for tests. */
        int current() { return current; }

        /** The swatches, None first: for tests. */
        List<Swatch> swatches() { return swatches; }
    }

    /** One 22×22 swatch, in a 26×26 button whose 2px margin holds the hover fill. */
    static final class Swatch extends JButton {
        final int slot;
        final boolean marked;
        private boolean current;

        Swatch(int slot, boolean marked) {
            this.slot = slot;
            this.marked = marked;
            setToolTipText(Messages.get(slot == NONE ? "highlight.color.none" : "highlight.color." + slot));
            getAccessibleContext().setAccessibleName(getToolTipText());
            setPreferredSize(new Dimension(26, 26));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setFocusable(false);   // the row takes the keys, as a menu item does
        }

        void setCurrent(boolean current) {
            if (this.current == current) return;
            this.current = current;
            repaint();
        }

        boolean isCurrent() { return current; }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color hover = UIManager.getColor("MenuItem.selectionBackground");
            if (current && hover != null) {
                g2.setColor(hover);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            }
            int x = 2, y = 2, w = getWidth() - 4, h = getHeight() - 4;
            if (slot == NONE) {
                // Drawn, not filled: an outline with a stroke through it reads as "no color".
                g2.setColor(ClouseauColors.mutedForeground());
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new java.awt.geom.RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1f, h - 1f, 6, 6));
                g2.draw(new java.awt.geom.Line2D.Float(x + 5.5f, y + h - 5.5f, x + w - 5.5f, y + 5.5f));
            } else {
                Color color = ClouseauColors.highlightColors()[slot];
                g2.setColor(color);
                g2.fillRoundRect(x, y, w, h, 6, 6);
                // Its own hue, a step off the menu; the ring replaces it when marked.
                if (!marked) ClouseauColors.paintHighlightEdge(g2, color, x, y, w, h, 6);
            }
            if (marked) {
                g2.setColor(ClouseauColors.highlightRing());
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(x + 1, y + 1, w - 3, h - 3, 5, 5);
            }
            g2.dispose();
        }
    }

    /**
     * Highlights the model rows in palette slot {@code slot} (null clears them), saves, and
     * refreshes what shows highlights: the Find bar's "highlight every match".
     */
    void highlightRows(List<Integer> modelRows, Integer slot) {
        if (modelRows.isEmpty()) return;
        setHighlights(modelRows, slot == null ? null : ClouseauColors.highlightColors()[slot], slot);
        logTable.repaint();
        host.highlightsChanged();
    }

    private void setHighlights(List<Integer> modelRows, Color color, Integer slot) {
        Map<Integer, String> rawLines = new java.util.LinkedHashMap<>();
        for (int r : modelRows) {
            LogEntry entry = logTableModel.getEntry(r);
            if (entry == null) continue;
            logTableModel.setHighlight(r, color);
            rawLines.put(logTableModel.ordinalAt(r), entry.rawLine());
        }
        host.notesSaved(host.notes().setHighlights(rawLines, slot));
    }

    /**
     * Hide Logger "RouteResolver" and Show Only Logger "RouteResolver" for the selected rows'
     * loggers (or "Hide 3 Loggers" for several), then Show All Loggers with how many are hidden
     * (that's what it would undo), all in one group, as the time items are.
     */
    private void addLoggerItems(JPopupMenu menu, List<Integer> entries) {
        java.util.Set<String> loggers = new java.util.LinkedHashSet<>();
        for (int r : entries) {
            LogEntry e = logTableModel.getEntry(r);
            if (e != null && e.logger() != null && !e.logger().isBlank()) loggers.add(e.logger());
        }
        FilterBar filters = host.filterBar();
        int hidden = filters.hiddenLoggerCount();
        if (loggers.isEmpty() && hidden == 0) return;

        if (!loggers.isEmpty()) {
            String one = loggers.size() == 1 ? shortLogger(loggers.iterator().next()) : null;
            JMenuItem hide = new JMenuItem(one != null
                    ? java.text.MessageFormat.format(Messages.get("table.context.logger.hide"), one)
                    : java.text.MessageFormat.format(Messages.get("table.context.logger.hide.many"), loggers.size()));
            JMenuItem only = new JMenuItem(one != null
                    ? java.text.MessageFormat.format(Messages.get("table.context.logger.only"), one)
                    : java.text.MessageFormat.format(Messages.get("table.context.logger.only.many"), loggers.size()));
            String full = String.join(", ", loggers);
            hide.setToolTipText(full);
            only.setToolTipText(full);
            only.setEnabled(!filters.showsOnly(loggers));
            hide.addActionListener(e -> filters.hideLoggers(loggers));
            only.addActionListener(e -> filters.showOnlyLoggers(loggers));
            menu.add(hide);
            menu.add(only);
        }
        JMenuItem all = new JMenuItem(hidden > 0
                ? java.text.MessageFormat.format(Messages.get("table.context.logger.all.hidden"), hidden)
                : Messages.get("table.context.logger.all"));
        all.setEnabled(hidden > 0);
        all.addActionListener(e -> filters.showAllLoggers());
        menu.add(all);
        menu.addSeparator();
    }

    /** "com.example.gateway.route.RouteResolver" reads "RouteResolver"; the tooltip has it all. */
    static String shortLogger(String logger) {
        int dot = logger.lastIndexOf('.');
        return dot >= 0 && dot < logger.length() - 1 ? logger.substring(dot + 1) : logger;
    }

    /** Edit → Add Note… / Edit Note…: the note on a model row, as the row menu's item does. */
    void editNote(int modelRow) {
        int ordinal = logTableModel.ordinalAt(modelRow);
        showAnnotationDialog(logTableModel.getEntry(modelRow), ordinal, host.notes().at(ordinal));
    }

    private void showAnnotationDialog(LogEntry entry, int ordinal, AnnotationStore.Annotation existing) {
        if (entry == null || ordinal < 0) return;
        AnnotationDialog dlg = new AnnotationDialog(SwingUtilities.getWindowAncestor(logTable), existing);
        dlg.setVisible(true);
        if (!dlg.isConfirmed()) return;
        String note   = dlg.getNote();
        String author = dlg.getAuthor();
        if (note.isEmpty()) {
            host.notesSaved(host.notes().remove(ordinal));
        } else {
            String ts      = entry.timestamp() != null ? entry.timestamp().toString() : "";
            String preview = entry.message()   != null
                    ? entry.message().substring(0, Math.min(80, entry.message().length()))
                    : entry.rawLine() != null
                    ? entry.rawLine().substring(0, Math.min(80, entry.rawLine().length()))
                    : "";
            host.notesSaved(host.notes().put(ordinal, new AnnotationStore.Annotation(
                    AnnotationStore.hashOf(entry.rawLine()), null, ts, preview, note, author,
                    java.time.Instant.now().toString())));
        }
        host.notesChanged();
    }
}
