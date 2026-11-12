package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.core.Notes;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.ui.theme.ClouseauFonts;
import com.formdev.flatlaf.ui.FlatTableHeaderUI;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tab's log table (G-9): the {@link JTable} over its {@link LogTableModel}, the row
 * renderer (levels, highlights, Find matches, the note marker), the header menu, column
 * sizing (fitted columns, with Message stretched to fill), column visibility and saved
 * column layouts. {@link TabPanel} owns one and reaches back through {@link Host}.
 * EDT only.
 */
final class LogTableView {

    /** What the table asks of its tab. */
    interface Host {
        /** The tab's notes, for the marker in the # column. */
        Notes notes();

        /** True if Find marked this model row. */
        boolean isFindMatch(int modelRow);

        /** What Find matched (lower case), to mark in the cells; null with no matches. */
        String findQuery();

        /** Right-click on rows (model rows, possibly empty) at a point in the table. */
        void showRowMenu(List<Integer> modelRows, int x, int y);

        /** Opens the note dialog for a model row: Add Note, or Edit Note if it has one. */
        void editNote(int modelRow);
    }

    /** Model column index of the Message column — always stretched to fill remaining width. */
    private static final int MESSAGE_MODEL_COL = 5;

    private final LogTableModel logTableModel;
    private final Host host;
    private final JTable logTable;
    private final JScrollPane tableScrollPane;
    /** Whether follow mode scrolls to new rows: only while the view is at the bottom. */
    private final BottomPin bottomPin;
    private boolean tableColumnsManaged = false;
    private int fittedColumnsWidth = 0;
    /** model-column-index → hidden TableColumn, in insertion order for restore positioning */
    private final Map<Integer, TableColumn> hiddenColumns = new LinkedHashMap<>();
    /** Name of the layout most recently applied via applyColumnLayout; null if none or modified since. */
    private String activeLayoutName = null;

    LogTableView(LogTableModel model, Host host) {
        this.logTableModel = model;
        this.host = host;
        logTable = new JTable(model) {
            @Override public boolean isCellEditable(int r, int c) { return false; }

            /** The gutter shows the same rows: it repaints with them (a note added, a filter, a scroll). */
            @Override public void repaint(long tm, int x, int y, int width, int height) {
                super.repaint(tm, x, y, width, height);
                if (gutter != null) gutter.repaint();
            }

            /** On the number column: an entry's line range, when lines are folded into it. */
            @Override public String getToolTipText(MouseEvent e) {
                int viewRow = rowAtPoint(e.getPoint()), viewCol = columnAtPoint(e.getPoint());
                if (viewRow >= 0 && viewCol >= 0 && convertColumnIndexToModel(viewCol) == 0) {
                    return foldedLinesTip(convertRowIndexToModel(viewRow));
                }
                return super.getToolTipText(e);
            }

            @Override
            public boolean getScrollableTracksViewportWidth() {
                // During a column resize drag, release the viewport constraint so the column
                // can grow freely and the horizontal scrollbar appears immediately.
                if (getTableHeader().getResizingColumn() != null) return false;
                // Otherwise fill the viewport when columns are narrower; scroll when wider.
                return getParent() == null || getPreferredSize().width < getParent().getWidth();
            }

            @Override
            protected JTableHeader createDefaultTableHeader() {
                return new JTableHeader(columnModel) {
                    @Override
                    public void updateUI() {
                        super.updateUI();
                        // Only sortable columns react to hover, so the others don't look clickable.
                        if (getUI() instanceof FlatTableHeaderUI) {
                            setUI(new FlatTableHeaderUI() {
                                @Override public int getRolloverColumn() {
                                    int col = super.getRolloverColumn();
                                    return col >= 0 && !isSortableViewColumn(col) ? -1 : col;
                                }
                            });
                        }
                    }
                };
            }
        };
        logTable.setBackground(ClouseauColors.tableBackground());
        logTable.setFont(ClouseauFonts.mono(Font.PLAIN, 13));
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
        // Only # and Timestamp sort: their use is reversing the order (newest first), and in a
        // merged view the two can differ. Sorting by level, thread, logger or message would
        // scramble the timeline; the filters answer those questions without losing the order.
        logTable.setRowSorter(new javax.swing.table.TableRowSorter<>(logTableModel) {
            @Override public boolean isSortable(int modelColumn) { return modelColumn == 0 || modelColumn == 1; }
        });
        logTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        logTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        logTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        logTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        logTable.getColumnModel().getColumn(4).setPreferredWidth(200);

        Border cellPad = BorderFactory.createEmptyBorder(0, 8, 0, 8);
        DefaultTableCellRenderer levelRenderer = new DefaultTableCellRenderer() {
            /** Find's query while this cell is in a matching row and a searched column; else null. */
            private String markQuery;

            @Override protected void paintComponent(Graphics g) {
                if (markQuery == null) { super.paintComponent(g); return; }
                // Background, then Find's marks, then the text on top of them.
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                FindMarks.paint(this, g, markQuery);
                setOpaque(false);
                try { super.paintComponent(g); } finally { setOpaque(true); }
            }

            @Override
            public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                int rowModel = logTable.convertRowIndexToModel(r);
                LogEntry entry = logTableModel.getEntry(rowModel);
                Color hl = logTableModel.getHighlight(rowModel);
                boolean hlSel = hl != null && sel;

                // Background: a highlight, else the selection, else plain or striped. Find marks
                // the matched text instead (see paintComponent), so it never hides a highlight.
                int searchedCol = t.convertColumnIndexToModel(c);
                markQuery = searchedCol >= 2 && host.isFindMatch(rowModel) ? host.findQuery() : null;
                if (hl != null) {
                    setBackground(sel ? blend(hl, t.getSelectionBackground(), 0.55f) : hl);
                } else if (sel) {
                    setBackground(ClouseauColors.selectionBackground());
                } else {
                    setBackground(r % 2 == 1 && AppPrefs.isTableStripes() ? ClouseauColors.stripeBackground() : t.getBackground());
                }

                // Ring border on any selected row
                if (sel) {
                    // A bright ring while the table has keyboard focus; a dim one otherwise, so
                    // you can tell whether the arrow keys will move the selection.
                    Color ring = !t.hasFocus() ? ClouseauColors.selectionRingInactive()
                            : hl != null ? hl.brighter().brighter() : ClouseauColors.selectionRing();
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
                    setForeground(ClouseauColors.selectionForeground());
                } else if (!sel) {
                    if (modelCol == 2 || modelCol == 5) {                  // Level + Message columns
                        LogEntry.LogLevel level = entry != null ? entry.level() : null;
                        setForeground(ClouseauColors.levelColor(level));
                    } else if (modelCol == 0) {                           // line numbers recede
                        setForeground(ClouseauColors.dimForeground());
                    } else {
                        setForeground(t.getForeground());
                    }
                }
                // Line numbers are right-aligned so their digits line up; everything else reads left.
                setHorizontalAlignment(modelCol == 0 ? SwingConstants.RIGHT : SwingConstants.LEFT);
                boolean isLevel = modelCol == 2;
                setFont(getFont().deriveFont(sel || isLevel ? Font.BOLD : Font.PLAIN));

                return this;
            }
        };
        levelRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        logTable.setDefaultRenderer(String.class,  levelRenderer);
        logTable.setDefaultRenderer(Integer.class, levelRenderer);

        // The header is a quiet label for the data: titles left-aligned where their column's text
        // starts (# right-aligned like its numbers), dim and a size smaller than the rows, with
        // short dividers that read as resize handles. FlatLaf's full-height column lines are
        // hidden (separatorColor = the header's own background); the bottom line stays. No side
        // cell margins, so cellPad alone sets the indent and titles line up with the rows.
        JTableHeader header = logTable.getTableHeader();
        header.putClientProperty("FlatLaf.style",
                "height: 28; cellMargins: 2,0,2,0; separatorColor: $TableHeader.background; "
                + "sortIconColor: $Label.disabledForeground");
        TableCellRenderer origHeaderRenderer = header.getDefaultRenderer();
        header.setDefaultRenderer((table, value, isSelected, hasFocus, row, column) -> {
            Component c = origHeaderRenderer.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (c instanceof JLabel label) {
                boolean numberColumn = table != null && table.convertColumnIndexToModel(column) == 0;
                boolean last = table != null && column == table.getColumnCount() - 1;
                Border existing = label.getBorder();
                Border inner = BorderFactory.createCompoundBorder(new HeaderDivider(!last), cellPad);
                label.setBorder(existing != null ? BorderFactory.createCompoundBorder(existing, inner) : inner);
                label.setHorizontalAlignment(numberColumn ? SwingConstants.RIGHT : SwingConstants.LEFT);
                if (label.getIcon() != null) {
                    // The sort arrow goes on the far side of the title from its alignment edge, so
                    // sorting never moves a title: after a left-aligned title, before the "#".
                    // Wrapped, FlatLaf no longer recognizes the arrow and leaves it where it's put.
                    label.setIcon(new SortIcon(label.getIcon()));
                    label.setHorizontalTextPosition(numberColumn ? SwingConstants.TRAILING : SwingConstants.LEADING);
                }
                label.setForeground(ClouseauColors.dimForeground());
                label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
                label.setToolTipText(numberColumn ? numberHeaderTip() : null);
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
                showAllItem.addActionListener(ev -> SwingUtilities.invokeLater(LogTableView.this::showAllColumns));
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
        // Row lines come from the theme ([style].logTable in its .properties file: showHorizontalLines,
        // gridColor), so a live theme switch updates them.
        logTable.putClientProperty("FlatLaf.styleClass", "logTable");
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
                host.showRowMenu(modelRows, e.getX(), e.getY());
            }
        });

        tableScrollPane = new JScrollPane(logTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        ClouseauColors.themedBackground(tableScrollPane.getViewport(), ClouseauColors::viewportBackground);
        // The note gutter: at the table's left edge, outside the columns, so hiding or moving
        // a column never hides a note.
        gutter = new NoteGutter();
        tableScrollPane.setRowHeaderView(gutter);
        tableScrollPane.setCorner(JScrollPane.UPPER_LEFT_CORNER, new GutterCorner());
        ClouseauColors.themedBackground(tableScrollPane.getRowHeader(), ClouseauColors::tableBackground);
        tableScrollPane.putClientProperty("FlatLaf.style", ClouseauColors.NO_FOCUS_FRAME);
        bottomPin = new BottomPin(tableScrollPane.getVerticalScrollBar().getModel(), logTable::getRowHeight);
        logTable.addFocusListener(new java.awt.event.FocusListener() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { logTable.repaint(); }
            @Override public void focusLost(java.awt.event.FocusEvent e)   { logTable.repaint(); }
        });
        tableScrollPane.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (tableColumnsManaged) stretchMessageColumn();
            }
        });
    }

    JTable table() { return logTable; }

    JScrollPane scrollPane() { return tableScrollPane; }

    void scrollToTop() {
        if (logTable.getRowCount() > 0) logTable.scrollRectToVisible(logTable.getCellRect(0, 0, true));
    }

    void scrollToBottom() {
        int last = logTable.getRowCount() - 1;
        if (last >= 0) logTable.scrollRectToVisible(logTable.getCellRect(last, 0, true));
    }

    /**
     * Follow mode's scroll after new rows: to the bottom, unless the user has scrolled up to
     * read earlier rows. Scrolling back down to the end (or Ctrl+End) follows again.
     */
    void followToBottom() {
        if (bottomPin.pinned()) scrollToBottom();
    }

    /** Forgets hidden columns and the applied layout, before the tab shows another log. */
    void resetColumns() {
        hiddenColumns.clear();
        activeLayoutName = null;
    }

    void autoResizeColumns() {
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
                // Measured as if selected: bold, with the selection ring, its widest look.
                Component rc = logTable.getCellRenderer(row, col).getTableCellRendererComponent(
                        logTable, logTable.getValueAt(row, col), true, false, row, col);
                maxWidth = Math.max(maxWidth, rc.getPreferredSize().width);
            }
            if (logTable.getColumnModel().getColumn(col).getModelIndex() == 0) {
                // The number is the line in the file (or position), so it can exceed the rows shown.
                FontMetrics fm = logTable.getFontMetrics(logTable.getFont().deriveFont(Font.BOLD));
                long widest = Math.max(logTable.getRowCount(), logTableModel.lineCount() + logTableModel.getTrimmedCount());
                maxWidth = Math.max(maxWidth, fm.stringWidth(String.valueOf(widest)) + 16);
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

    void stretchMessageColumn() {
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
            entries.add(new AppPrefs.ColumnLayout.ColumnEntry(
                    col.getModelIndex(), true, col.getWidth(), fieldOf(col.getModelIndex())));
        }
        hiddenColumns.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> entries.add(new AppPrefs.ColumnLayout.ColumnEntry(
                        e.getKey(), false, e.getValue().getWidth(), fieldOf(e.getKey()))));
        return new AppPrefs.ColumnLayout(name, entries);
    }

    /** The field a model column shows, or null for a fixed column. */
    private String fieldOf(int modelIndex) {
        int at = modelIndex - LogTableModel.FIXED_COLUMNS;
        List<String> fields = logTableModel.fieldColumns();
        return at >= 0 && at < fields.size() ? fields.get(at) : null;
    }

    /** Applies a saved layout: visibility, order and widths. Safe to call on any file (unknown model indices are skipped). */
    void applyColumnLayout(AppPrefs.ColumnLayout saved) {
        int modelCount = logTableModel.getColumnCount();
        // The saved columns as this model numbers them (a merged view's Source shifts the fields).
        AppPrefs.ColumnLayout layout = new AppPrefs.ColumnLayout(saved.name(),
                LayoutColumns.resolve(saved.columns(), logTableModel.fieldColumns()));

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

    /** A layout restored from a session (D-19), applied once in place of the default when the load completes. */
    private AppPrefs.ColumnLayout restoredLayout;

    /** Sets the layout a session saved; it applies when the tab's rows are loaded and its columns known. */
    void restoreLayout(AppPrefs.ColumnLayout layout) {
        restoredLayout = layout;
    }

    /** The layout a session should keep for this tab: the restored one until it has been applied. */
    AppPrefs.ColumnLayout sessionLayout() {
        return restoredLayout != null ? restoredLayout : captureLayout(activeLayoutName);
    }

    void applyDefaultLayoutIfSet() {
        if (restoredLayout != null) {
            AppPrefs.ColumnLayout layout = restoredLayout;
            restoredLayout = null;
            applyColumnLayout(layout);
            return;
        }
        String defaultName = AppPrefs.getDefaultColumnLayout();
        if (defaultName == null) return;
        AppPrefs.getColumnLayouts().stream()
                .filter(l -> l.name().equals(defaultName))
                .findFirst()
                .ifPresent(this::applyColumnLayout);
    }

    void saveCurrentLayout() {
        String name = (String) JOptionPane.showInputDialog(
                SwingUtilities.getWindowAncestor(logTable),
                Messages.get("table.columns.layout.save.message"),
                Messages.get("table.columns.layout.save.title"),
                JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (name == null || name.isBlank()) return;
        name = name.trim();

        List<AppPrefs.ColumnLayout> layouts = new ArrayList<>(AppPrefs.getColumnLayouts());
        final String finalName = name;
        if (layouts.stream().anyMatch(l -> l.name().equals(finalName))) {
            int confirm = JOptionPane.showConfirmDialog(
                    SwingUtilities.getWindowAncestor(logTable),
                    java.text.MessageFormat.format(Messages.get("table.columns.layout.overwrite.message"), finalName),
                    Messages.get("table.columns.layout.overwrite.title"),
                    JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
            layouts.removeIf(l -> l.name().equals(finalName));
        }
        layouts.add(captureLayout(finalName));
        AppPrefs.saveColumnLayouts(layouts);
    }

    /**
     * Widens the column titled {@code header} so every one of {@code labels} fits, e.g. the
     * Source column after an offset change ({@code db.log (+3s)}); never narrows it.
     */
    void widenColumnToFit(String header, List<String> labels) {
        for (int col = 0; col < logTable.getColumnCount(); col++) {
            if (!header.equals(logTable.getColumnName(col))) continue;
            FontMetrics fm = logTable.getFontMetrics(logTable.getFont().deriveFont(Font.BOLD));
            int needed = 0;
            for (String label : labels) needed = Math.max(needed, fm.stringWidth(label));
            // Cell padding (8 + 8), the selection ring on the last column (2), the grid
            // line, and the same slack autoResizeColumns adds (8).
            needed += 16 + 2 + logTable.getIntercellSpacing().width + 8;
            TableColumn c = logTable.getColumnModel().getColumn(col);
            if (needed <= c.getWidth()) return;
            fittedColumnsWidth += needed - c.getWidth();
            c.setPreferredWidth(needed);
            c.setWidth(needed);
            stretchMessageColumn();
            return;
        }
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(
                (int)(a.getRed()   * t + b.getRed()   * (1 - t)),
                (int)(a.getGreen() * t + b.getGreen() * (1 - t)),
                (int)(a.getBlue()  * t + b.getBlue()  * (1 - t))
        );
    }
    /**
     * A short vertical hairline at a header cell's right edge, inset from the top and bottom so
     * it reads as a resize handle rather than a table border. Drawn in the theme's separator color
     * at paint time, so a live theme switch updates it.
     */
    private boolean isSortableViewColumn(int viewColumn) {
        return logTable.getRowSorter() instanceof DefaultRowSorter<?, ?> sorter
                && sorter.isSortable(logTable.convertColumnIndexToModel(viewColumn));
    }

    private record SortIcon(Icon icon) implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) { icon.paintIcon(c, g, x, y); }
        @Override public int getIconWidth()  { return icon.getIconWidth(); }
        @Override public int getIconHeight() { return icon.getIconHeight(); }
    }

    private static final class HeaderDivider implements Border {
        private final boolean draw;
        HeaderDivider(boolean draw) { this.draw = draw; }

        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            if (!draw) return;
            Color line = UIManager.getColor("Separator.foreground");
            if (line == null) line = UIManager.getColor("Component.borderColor");
            if (line == null) return;
            int inset = Math.max(4, h / 4);
            g.setColor(line);
            g.fillRect(x + w - 1, y + inset, 1, h - 2 * inset);
        }
        @Override public Insets getBorderInsets(Component c) { return new Insets(0, 0, 0, 0); }
        @Override public boolean isBorderOpaque() { return false; }
    }

    // ── The number column: what it counts, and lines folded into an entry ───────────

    /** The number column's title follows what it counts (Line on a file, # otherwise). */
    void numberingChanged() {
        for (int i = 0; i < logTable.getColumnModel().getColumnCount(); i++) {
            javax.swing.table.TableColumn c = logTable.getColumnModel().getColumn(i);
            if (c.getModelIndex() == 0) c.setHeaderValue(logTableModel.getColumnName(0));
        }
        logTable.getTableHeader().repaint();
    }

    /** The number column's header tooltip: what the number is, and why it can skip. */
    private String numberHeaderTip() {
        return Messages.get(switch (logTableModel.numbering()) {
            case FILE_LINES -> "table.col.line.tooltip";
            case POSITION   -> "table.col.position.tooltip";
            case RECEIVED   -> "table.col.received.tooltip";
        });
    }

    /**
     * "Lines 49–56: one entry, with its continuation lines folded in", when lines after the
     * entry belong to it (a stack trace) and aren't rows of their own, so the next number
     * jumps. In a merged view it names the file. Null otherwise.
     */
    String foldedLinesTip(int modelRow) {
        int ord = logTableModel.ordinalAt(modelRow);
        if (ord < 0 || logTableModel.numbering() == LogTableModel.Numbering.RECEIVED) return null;
        if (logTableModel.rowOfOrdinal(ord + 1) >= 0) return null;     // the next line is a row: no gap
        int folded = logTableModel.getContinuationLines(modelRow).size();
        if (folded == 0) return null;
        int first = logTableModel.lineNumberOfOrdinal(ord);
        int last = logTableModel.lineNumberOfOrdinal(ord + folded);
        if (logTableModel.numbering() == LogTableModel.Numbering.POSITION) {
            String file = logTableModel.sourceNameAt(modelRow);
            return java.text.MessageFormat.format(Messages.get("table.lines.folded.file"),
                    file, String.valueOf(first), String.valueOf(last));
        }
        return java.text.MessageFormat.format(Messages.get("table.lines.folded"),
                String.valueOf(first), String.valueOf(last));
    }

    // ── The note gutter ───────────────────────────────────────────────────────────

    private NoteGutter gutter;

    /**
     * A strip at the table's left edge, like an IDE's gutter: a pencil on every row with a
     * note, whatever columns are shown. Pointing at one shows the note; clicking it opens the
     * note. On a row without one, a dim pencil appears under the mouse to add one.
     */
    private final class NoteGutter extends JComponent {
        static final int WIDTH = 20;
        private int hoverRow = -1;

        NoteGutter() {
            setOpaque(true);
            ToolTipManager.sharedInstance().registerComponent(this);
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e)  { hover(logTable.rowAtPoint(new Point(0, e.getY()))); }
                @Override public void mouseExited(MouseEvent e) { hover(-1); }
                @Override public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return;
                    int viewRow = logTable.rowAtPoint(new Point(0, e.getY()));
                    if (viewRow < 0) return;
                    logTable.setRowSelectionInterval(viewRow, viewRow);
                    int modelRow = logTable.convertRowIndexToModel(viewRow);
                    SwingUtilities.invokeLater(() -> host.editNote(modelRow));
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            // As tall as the table, so it scrolls with it in the row header's viewport.
            logTable.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override public void componentResized(java.awt.event.ComponentEvent e) { revalidate(); repaint(); }
            });
        }

        private void hover(int viewRow) {
            if (viewRow != hoverRow) { hoverRow = viewRow; repaint(); }
        }

        @Override public Dimension getPreferredSize() {
            return new Dimension(WIDTH, logTable.getPreferredSize().height);
        }

        private boolean hasNote(int viewRow) {
            int ord = logTableModel.ordinalAt(logTable.convertRowIndexToModel(viewRow));
            return ord >= 0 && host.notes().has(ord);
        }

        @Override protected void paintComponent(Graphics g) {
            g.setColor(ClouseauColors.tableBackground());
            Rectangle clip = g.getClipBounds();
            g.fillRect(clip.x, clip.y, clip.width, clip.height);
            if (logTable.getRowCount() == 0) return;
            int first = logTable.rowAtPoint(new Point(0, clip.y));
            int last = logTable.rowAtPoint(new Point(0, clip.y + clip.height - 1));
            if (first < 0) return;
            if (last < 0) last = logTable.getRowCount() - 1;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int rowHeight = logTable.getRowHeight();
            for (int r = first; r <= last; r++) {
                if (host.isFindMatch(logTable.convertRowIndexToModel(r)))
                    FindMarks.paintGutterTick(g2, WIDTH, r * rowHeight, rowHeight);
                boolean note = hasNote(r);
                if (!note && r != hoverRow) continue;
                int y = r * rowHeight + (rowHeight - 12) / 2;
                ToolIcons.paintPencil(g2, (WIDTH - 12) / 2, y,
                        note ? ClouseauColors.annotationIndicatorColor() : ClouseauColors.mutedForeground());
            }
            g2.dispose();
        }

        @Override public String getToolTipText(MouseEvent e) {
            int viewRow = logTable.rowAtPoint(new Point(0, e.getY()));
            if (viewRow < 0) return null;
            int ord = logTableModel.ordinalAt(logTable.convertRowIndexToModel(viewRow));
            com.droidenx.clouseau.core.AnnotationStore.Annotation a = ord >= 0 ? host.notes().at(ord) : null;
            if (a == null) return Messages.get("gutter.note.add.tooltip");
            String esc = a.note() == null ? "" : a.note().replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>");
            return "<html><div style='width:280px'><b>" + java.text.MessageFormat.format(
                    Messages.get("gutter.note.by"), a.author() == null ? "" : a.author()) + "</b><br>" + esc
                    + "<br><br><i>" + Messages.get("gutter.note.edit.hint") + "</i></div></html>";
        }
    }

    /** Above the gutter: the header's own background and bottom line, so the header runs edge to edge. */
    private static final class GutterCorner extends JComponent {
        @Override protected void paintComponent(Graphics g) {
            Color bg = UIManager.getColor("TableHeader.background");
            g.setColor(bg != null ? bg : ClouseauColors.tableBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            Color line = UIManager.getColor("TableHeader.bottomSeparatorColor");
            g.setColor(line != null ? line : ClouseauColors.separatorColor());
            g.fillRect(0, getHeight() - 1, getWidth(), 1);
        }
    }
}
