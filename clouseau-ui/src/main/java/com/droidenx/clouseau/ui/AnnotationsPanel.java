package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.AnnotationStore;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Self-contained panel listing all annotations for the current log.
 * <p>
 * Clicking a row selects and scrolls to the matching log line. Designed so that
 * the panel itself has no dependency on how it is hosted — it can be wrapped in
 * a floating {@link JDialog} now and docked into a split-pane or tab later with
 * no changes to this class.
 * <p>
 * The {@code onDismiss} callback lets the host (dialog, dock, etc.) react when
 * the user clicks the close button inside the panel.
 */
public final class AnnotationsPanel extends JPanel {

    private static final String[] COLUMN_KEYS = {
        "annotations.col.line",
        "annotations.col.preview",
        "annotations.col.note",
        "annotations.col.author",
        "annotations.col.date"
    };

    private final Supplier<AnnotationStore> storeSupplier;
    private final LogTableModel             logTableModel;
    private final JTable                    logTable;
    private final Runnable                  onDismiss;
    private final Consumer<Boolean>          onAnnotationChanged;

    private final DefaultTableModel panelModel;
    private final JTable            annotationsTable;

    /**
     * Parallel to the panel rows: the entry ordinal (Integer) of an attached annotation, or
     * the {@link AnnotationStore.Annotation} itself for an orphan.
     */
    private final List<Object> rowKeys = new ArrayList<>();

    public AnnotationsPanel(Supplier<AnnotationStore> storeSupplier,
                            LogTableModel logTableModel,
                            JTable logTable,
                            Runnable onDismiss,
                            Consumer<Boolean> onAnnotationChanged) {
        super(new BorderLayout());
        this.storeSupplier        = storeSupplier;
        this.logTableModel        = logTableModel;
        this.logTable             = logTable;
        this.onDismiss            = onDismiss;
        this.onAnnotationChanged  = onAnnotationChanged;

        panelModel = new DefaultTableModel(columnNames(), 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Integer.class : String.class; }
        };
        annotationsTable = new JTable(panelModel);

        buildUI();
        refresh();
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    private void buildUI() {
        annotationsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        annotationsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        annotationsTable.setRowHeight(AppPrefs.getRowHeight());
        annotationsTable.setShowHorizontalLines(true);
        annotationsTable.setShowVerticalLines(false);
        annotationsTable.setGridColor(ClouseauColors.separatorColor());
        annotationsTable.setBackground(ClouseauColors.tableBackground());

        // Left-pad all cells
        DefaultTableCellRenderer padded = new DefaultTableCellRenderer();
        padded.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        annotationsTable.setDefaultRenderer(String.class,  padded);
        annotationsTable.setDefaultRenderer(Integer.class, padded);

        // Jump to line on selection change
        annotationsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int sel = annotationsTable.getSelectedRow();
            if (sel >= 0 && sel < rowKeys.size() && rowKeys.get(sel) instanceof Integer ordinal)
                jumpToOrdinal(ordinal);
        });

        // Delete key removes the selected annotation
        annotationsTable.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteAnnotation");
        annotationsTable.getActionMap().put("deleteAnnotation", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { deleteSelected(); }
        });

        JScrollPane scroll = new JScrollPane(annotationsTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        ClouseauColors.themedBackground(scroll.getViewport(), ClouseauColors::viewportBackground);
        add(scroll, BorderLayout.CENTER);

        // Footer: empty-state label on the left, buttons on the right
        JLabel emptyLabel = new JLabel(Messages.get("annotations.panel.empty"));
        ClouseauColors.themedForeground(emptyLabel, ClouseauColors::dimForeground);
        emptyLabel.setFont(emptyLabel.getFont().deriveFont(11f));

        JButton deleteBtn = new JButton(Messages.get("annotations.panel.delete"));
        deleteBtn.setMargin(new Insets(2, 8, 2, 8));
        deleteBtn.setEnabled(false);
        deleteBtn.addActionListener(e -> deleteSelected());

        JButton refreshBtn = new JButton(Messages.get("annotations.panel.refresh"));
        refreshBtn.setMargin(new Insets(2, 8, 2, 8));
        refreshBtn.addActionListener(e -> refresh());

        JButton closeBtn = new JButton(Messages.get("annotations.panel.close"));
        closeBtn.setMargin(new Insets(2, 8, 2, 8));
        closeBtn.addActionListener(e -> { if (onDismiss != null) onDismiss.run(); });

        annotationsTable.getSelectionModel().addListSelectionListener(
                e -> deleteBtn.setEnabled(annotationsTable.getSelectedRow() >= 0));

        JPanel footer = new JPanel(new MigLayout("insets 4 8 4 8", "[grow][]4[]4[]", "[]"));
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ClouseauColors.separatorColor()));
        footer.add(emptyLabel, "grow");
        footer.add(deleteBtn);
        footer.add(refreshBtn);
        footer.add(closeBtn);
        add(footer, BorderLayout.SOUTH);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Rebuilds the table from the current annotation store. Annotations are listed in
     * entry order; the # column shows the row number in the log table, or – when the
     * entry is filtered out. Orphans (annotations whose line is no longer in the file)
     * come last.
     */
    public void refresh() {
        AnnotationStore store = storeSupplier.get();
        panelModel.setRowCount(0);
        rowKeys.clear();

        for (AnnotationStore.Placed p : store.placed()) {
            int modelRow = logTableModel.rowOfOrdinal(p.ordinal());
            rowKeys.add(p.ordinal());
            panelModel.addRow(row(modelRow >= 0 ? modelRow + 1 : null, p.annotation()));
        }
        for (AnnotationStore.Annotation a : store.orphans()) {
            rowKeys.add(a);
            panelModel.addRow(row(null, a));
        }

        applyColumnWidths();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String[] columnNames() {
        String[] names = new String[COLUMN_KEYS.length];
        for (int i = 0; i < COLUMN_KEYS.length; i++) names[i] = Messages.get(COLUMN_KEYS[i]);
        return names;
    }

    private static Object[] row(Integer lineNumber, AnnotationStore.Annotation a) {
        String preview = a.logPreview() != null
                ? a.logPreview().substring(0, Math.min(80, a.logPreview().length())) : "";
        String note    = a.note()       != null ? a.note().replace('\n', ' ') : "";
        String author  = a.author()     != null ? a.author() : "";
        String date    = a.createdAt()  != null
                ? a.createdAt().replace("T", " ").replaceFirst("\\.\\d+Z$", " UTC") : "";
        return new Object[]{ lineNumber != null ? lineNumber : "–", preview, note, author, date };
    }

    private void applyColumnWidths() {
        if (annotationsTable.getColumnCount() < 5) return;
        int[] fixed = { 55, 220, 0, 100, 165 }; // 0 = stretch
        int taken   = 0;
        for (int w : fixed) taken += w;

        int viewport   = getWidth() > 0 ? getWidth() - 20 : 700;
        int noteWidth  = Math.max(150, viewport - taken);

        annotationsTable.getColumnModel().getColumn(0).setPreferredWidth(fixed[0]);
        annotationsTable.getColumnModel().getColumn(0).setMaxWidth(fixed[0]);
        annotationsTable.getColumnModel().getColumn(1).setPreferredWidth(fixed[1]);
        annotationsTable.getColumnModel().getColumn(2).setPreferredWidth(noteWidth);
        annotationsTable.getColumnModel().getColumn(3).setPreferredWidth(fixed[3]);
        annotationsTable.getColumnModel().getColumn(4).setPreferredWidth(fixed[4]);
    }

    private void deleteSelected() {
        int sel = annotationsTable.getSelectedRow();
        if (sel < 0 || sel >= rowKeys.size()) return;
        if (AppPrefs.isAnnotationDeleteConfirm()) {
            JCheckBox dontAsk = new JCheckBox(Messages.get("annotation.delete.dontask"));
            int result = JOptionPane.showConfirmDialog(
                    SwingUtilities.getWindowAncestor(this),
                    new Object[]{Messages.get("annotation.delete.confirm.message"), dontAsk},
                    Messages.get("annotation.delete.confirm.title"),
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (dontAsk.isSelected()) AppPrefs.setAnnotationDeleteConfirm(false);
            if (result != JOptionPane.YES_OPTION) return;
        }
        AnnotationStore store = storeSupplier.get();
        boolean saved = rowKeys.get(sel) instanceof Integer ordinal
                ? store.remove(ordinal)
                : store.removeOrphan((AnnotationStore.Annotation) rowKeys.get(sel));
        logTable.repaint();
        refresh();
        if (onAnnotationChanged != null) onAnnotationChanged.accept(saved);
        // keep selection on the next row if possible
        int newCount = annotationsTable.getRowCount();
        if (newCount > 0) annotationsTable.setRowSelectionInterval(Math.min(sel, newCount - 1), Math.min(sel, newCount - 1));
    }

    private void jumpToOrdinal(int ordinal) {
        int modelRow = logTableModel.rowOfOrdinal(ordinal);
        if (modelRow < 0) return;
        int viewRow = logTable.convertRowIndexToView(modelRow);
        if (viewRow >= 0) {
            logTable.setRowSelectionInterval(viewRow, viewRow);
            logTable.scrollRectToVisible(logTable.getCellRect(viewRow, 0, true));
            logTable.requestFocusInWindow();
        }
    }
}
