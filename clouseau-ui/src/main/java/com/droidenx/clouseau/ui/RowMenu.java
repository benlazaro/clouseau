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

/**
 * The menu shown when the user right-clicks rows of a tab's table (G-9): highlight colors
 * and clearing them (D-15), the time range from a row, adding, editing and removing a note, the row's file on a merged
 * tab (offset, timezone, parser), the notes panel, reload and clear. {@link TabPanel}
 * builds one and reaches back through {@link Host}. EDT only.
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

        void showAnnotationsDialog();

        /** True if Reload File applies (a tab showing a file). */
        boolean canReload();

        void reload();

        void clearTable();

        /** True if Clear Table applies (a tab streaming a live source). */
        boolean canClearTable();
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
                    host.highlightsChanged();
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
                    host.highlightsChanged();
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
                                SwingUtilities.getWindowAncestor(logTable),
                                Messages.get("highlight.clear.all.confirm.message"),
                                Messages.get("highlight.clear.all.confirm.title"),
                                JOptionPane.YES_NO_OPTION);
                        if (confirm != JOptionPane.YES_OPTION) return;
                    }
                    logTableModel.clearAllHighlights();
                    host.notesSaved(host.notes().clearHighlights());
                    host.highlightsChanged();
                });
            } else {
                clearAllItem.putClientProperty("FlatLaf.style", "disabledForeground: " + colorToHex(ClouseauColors.borderColor()));
                clearAllItem.setEnabled(false);
            }
            menu.add(clearAllItem);

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
            menu.addSeparator();
        }

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

        JMenuItem viewAllItem = new JMenuItem(Messages.get("annotations.view.all"));
        viewAllItem.setEnabled(host.notes().size() > 0);
        viewAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        viewAllItem.addActionListener(e -> host.showAnnotationsDialog());
        menu.add(viewAllItem);

        menu.addSeparator();

        JMenuItem reloadFileItem = new JMenuItem(Messages.get("table.context.reload.file"));
        reloadFileItem.setEnabled(host.canReload());
        reloadFileItem.addActionListener(e -> host.reload());
        menu.add(reloadFileItem);

        JMenuItem clearTableItem = new JMenuItem(Messages.get("table.clear"));
        clearTableItem.setEnabled(host.canClearTable());
        clearTableItem.addActionListener(e -> host.clearTable());
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
        host.notesSaved(host.notes().setHighlights(rawLines, slot));
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

    private static String colorToHex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }
}
