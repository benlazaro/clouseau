package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.core.Notes;
import com.droidenx.clouseau.ui.theme.ClouseauColors;

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
 * column layouts. {@link LogPanel} owns one and reaches back through {@link Host}.
 * EDT only.
 */
final class LogTableView {

    /** What the table asks of its tab. */
    interface Host {
        /** The tab's notes, for the marker in the # column. */
        Notes notes();

        /** True if Find marked this model row. */
        boolean isFindMatch(int modelRow);

        /** Right-click on rows (model rows, possibly empty) at a point in the table. */
        void showRowMenu(List<Integer> modelRows, int x, int y);
    }

    /** Model column index of the Message column — always stretched to fill remaining width. */
    private static final int MESSAGE_MODEL_COL = 5;

    private final LogTableModel logTableModel;
    private final Host host;
    private final JTable logTable;
    private final JScrollPane tableScrollPane;
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
                boolean isSearchMatch = host.isFindMatch(rowModel);
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
                if (modelCol == 0 && host.notes().has(logTableModel.ordinalAt(rowModel))) {
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
                host.showRowMenu(modelRows, e.getX(), e.getY());
            }
        });

        tableScrollPane = new JScrollPane(logTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        ClouseauColors.themedBackground(tableScrollPane.getViewport(), ClouseauColors::viewportBackground);
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
}
