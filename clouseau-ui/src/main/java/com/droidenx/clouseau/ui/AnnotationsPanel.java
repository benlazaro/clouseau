package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.core.AnnotationStore;
import com.droidenx.clouseau.core.Notes;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Every note on the current log (Edit → All Notes…), laid out like the managers: a list
 * styled like the log table, a trash can on the row under the mouse, and a footer with a
 * short hint and Close.
 * <p>
 * A click goes to the note's line; a double-click or Enter edits the note. The list follows
 * changes while it's open (the host calls {@link #refresh()}), so there's no Refresh button,
 * and refreshing keeps the column widths the user set.
 * <p>
 * The panel doesn't know how it's hosted: a floating {@link JDialog} now, possibly docked
 * later. {@code onDismiss} lets the host react to Close.
 */
public final class AnnotationsPanel extends JPanel {

    private static final int COL_LINE = 0, COL_NOTE = 1, COL_PREVIEW = 2, COL_AUTHOR = 3, COL_DATE = 4;
    /** The trash can's hit area, from the row's right edge. */
    private static final int TRASH = 30;
    private static final String CARD_LIST = "list", CARD_EMPTY = "empty";

    private final Supplier<Notes> storeSupplier;
    private final LogTableModel   logTableModel;
    private final JTable          logTable;
    private final Runnable        onDismiss;
    private final Consumer<Boolean> onAnnotationChanged;
    private final IntConsumer     editNoteAtOrdinal;

    private final DefaultTableModel panelModel;
    private final NotesTable        annotationsTable;
    private final CardLayout        cards = new CardLayout();
    private final JPanel            body  = new JPanel(cards);
    private final JLabel            hint  = new JLabel(Messages.get("annotations.panel.hint"));

    /**
     * Parallel to the panel rows: the entry ordinal (Integer) of an attached annotation, or
     * the {@link AnnotationStore.Annotation} itself for an orphan.
     */
    private final List<Object> rowKeys = new ArrayList<>();

    /**
     * @param editNoteAtOrdinal opens the note dialog for the entry with this ordinal
     */
    public AnnotationsPanel(Supplier<Notes> storeSupplier,
                            LogTableModel logTableModel,
                            JTable logTable,
                            Runnable onDismiss,
                            Consumer<Boolean> onAnnotationChanged,
                            IntConsumer editNoteAtOrdinal) {
        super(new BorderLayout());
        this.storeSupplier        = storeSupplier;
        this.logTableModel        = logTableModel;
        this.logTable             = logTable;
        this.onDismiss            = onDismiss;
        this.onAnnotationChanged  = onAnnotationChanged;
        this.editNoteAtOrdinal    = editNoteAtOrdinal;

        panelModel = new DefaultTableModel(columnNames(), 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        annotationsTable = new NotesTable(panelModel);

        buildUI();
        refresh();
        setInitialColumnWidths();
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    private void buildUI() {
        JTable t = annotationsTable;
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // The note takes whatever width the others leave; dragging one border moves only it.
        t.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        t.setRowHeight(AppPrefs.getRowHeight());
        t.setShowGrid(false);                                   // like the log table: no lines between rows
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setFillsViewportHeight(true);
        ClouseauColors.themedBackground(t, ClouseauColors::tableBackground);

        DefaultTableCellRenderer cells = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(table, value, sel, false, row, col);
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, col == COL_DATE ? TRASH : 8));
                int model = table.convertColumnIndexToModel(col);
                setHorizontalAlignment(model == COL_LINE ? SwingConstants.RIGHT : SwingConstants.LEFT);
                if (!sel) setForeground(model == COL_NOTE ? table.getForeground() : ClouseauColors.dimForeground());
                return this;
            }
        };
        t.setDefaultRenderer(Object.class, cells);

        // Headers like the log table's: left-aligned (numbers right), dim, a size smaller.
        JTableHeader header = t.getTableHeader();
        TableCellRenderer base = header.getDefaultRenderer();
        header.setDefaultRenderer((table, value, s, f, row, col) -> {
            Component c = base.getTableCellRendererComponent(table, value, s, f, row, col);
            if (c instanceof JLabel l) {
                l.setHorizontalAlignment(table.convertColumnIndexToModel(col) == COL_LINE
                        ? SwingConstants.RIGHT : SwingConstants.LEFT);
                l.setForeground(ClouseauColors.dimForeground());
                l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
                l.setBorder(BorderFactory.createCompoundBorder(l.getBorder(),
                        BorderFactory.createEmptyBorder(0, 8, 0, 8)));
            }
            return c;
        });
        header.setReorderingAllowed(false);

        // A click goes to the line; a double-click (or Enter) edits the note.
        t.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            Integer ordinal = selectedOrdinal();
            if (ordinal != null) jumpToOrdinal(ordinal);
        });
        t.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) editSelected();
            }
        });
        t.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "editNote");
        t.getActionMap().put("editNote", action(this::editSelected));
        t.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteNote");
        t.getActionMap().put("deleteNote", action(() -> delete(annotationsTable.getSelectedRow())));

        JScrollPane scroll = new JScrollPane(t);
        scroll.putClientProperty("FlatLaf.style", ClouseauColors.NO_FOCUS_FRAME);
        ClouseauColors.themedBackground(scroll.getViewport(), ClouseauColors::tableBackground);
        body.add(scroll, CARD_LIST);
        body.add(buildEmptyState(), CARD_EMPTY);

        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        center.add(body, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        // Footer: a short hint that gives way before the button does, and Close.
        ClouseauColors.themedForeground(hint, ClouseauColors::dimForeground);
        hint.setToolTipText(Messages.get("annotations.panel.saved"));
        JButton closeBtn = new JButton(Messages.get("annotations.panel.close"));
        closeBtn.addActionListener(e -> { if (onDismiss != null) onDismiss.run(); });

        JPanel buttons = new JPanel(new MigLayout("insets 10 16 10 12", "[0:pref, grow]16[]"));
        buttons.add(hint, "growx, wmin 0");
        buttons.add(closeBtn);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(new JSeparator(), BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    private JComponent buildEmptyState() {
        JPanel empty = new JPanel(new MigLayout("fill, insets 16", "[center]", "push[]6[]push"));
        ClouseauColors.themedBackground(empty, ClouseauColors::tableBackground);
        JLabel title = new JLabel(Messages.get("annotations.panel.empty.title"));
        ClouseauColors.themedForeground(title, ClouseauColors::dimForeground);
        JLabel how = new JLabel(Messages.get("annotations.panel.empty.hint"));
        ClouseauColors.themedForeground(how, ClouseauColors::mutedForeground);
        how.setFont(how.getFont().deriveFont(how.getFont().getSize2D() - 1f));
        empty.add(title, "wrap");
        empty.add(how);
        empty.setBorder(UIManager.getBorder("ScrollPane.border"));
        return empty;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Rebuilds the rows from the current notes, in entry order, keeping the selection and
     * the column widths. Line reads as the log table does: the file line ("16", or
     * "db.log:16" in a merged view), "#1042" on a live source, or – when the entry is
     * filtered out or gone. Notes whose line is no longer in the file come last.
     */
    public void refresh() {
        Object selected = annotationsTable.getSelectedRow() >= 0 && annotationsTable.getSelectedRow() < rowKeys.size()
                ? rowKeys.get(annotationsTable.getSelectedRow()) : null;
        Notes store = storeSupplier.get();
        panelModel.setRowCount(0);
        rowKeys.clear();

        for (AnnotationStore.Placed p : store.placed()) {
            rowKeys.add(p.ordinal());
            panelModel.addRow(row(where(p.ordinal(), store.sourceName(p.ordinal())), p.annotation()));
        }
        for (AnnotationStore.Annotation a : store.orphans()) {
            rowKeys.add(a);
            String source = store.orphanSource(a);
            panelModel.addRow(row(source != null ? source + ":–" : "–", a));
        }
        // The first column's title follows the log table's: Line, or # on a live source.
        annotationsTable.getColumnModel().getColumn(COL_LINE).setHeaderValue(Messages.get(
                logTableModel.numbering() == LogTableModel.Numbering.RECEIVED
                        ? "annotations.col.number" : "annotations.col.line"));
        annotationsTable.getTableHeader().repaint();

        cards.show(body, rowKeys.isEmpty() ? CARD_EMPTY : CARD_LIST);
        hint.setVisible(!rowKeys.isEmpty());                  // the empty state says how instead
        int keep = selected != null ? rowKeys.indexOf(selected) : -1;
        if (keep >= 0) annotationsTable.getSelectionModel().setSelectionInterval(keep, keep);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String[] columnNames() {
        return new String[] {
            Messages.get("annotations.col.line"), Messages.get("annotations.col.note"),
            Messages.get("annotations.col.preview"), Messages.get("annotations.col.author"),
            Messages.get("annotations.col.date")
        };
    }

    /** The note's place, as the log table shows it, or "–" when its row isn't there. */
    private String where(int ordinal, String source) {
        if (logTableModel.rowOfOrdinal(ordinal) < 0) return source != null ? source + ":–" : "–";
        int line = logTableModel.lineNumberOfOrdinal(ordinal);
        if (source != null) return source + ":" + line;
        return logTableModel.numbering() == LogTableModel.Numbering.RECEIVED ? "#" + line : String.valueOf(line);
    }

    private Object[] row(String where, AnnotationStore.Annotation a) {
        String preview = a.logPreview() != null ? a.logPreview() : "";
        String note    = a.note()   != null ? a.note().replace('\n', ' ') : "";
        String author  = a.author() != null ? a.author() : "";
        return new Object[]{ where, note, preview, author, when(a.createdAt()) };
    }

    /** When a note was written, in the display time zone and the Timestamp field's format, to the second. */
    private static String when(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) return "";
        try {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(AppPrefs.getDisplayTimezone())
                    .format(Instant.parse(createdAt));
        } catch (java.time.DateTimeException e) {
            return createdAt;
        }
    }

    /** Widths to start with, once: refreshing never undoes the user's resizing. */
    private void setInitialColumnWidths() {
        int[] widths = { 80, 300, 240, 80, 180 };
        for (int i = 0; i < widths.length; i++) {
            annotationsTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        annotationsTable.getColumnModel().getColumn(COL_LINE).setMinWidth(50);
        annotationsTable.getColumnModel().getColumn(COL_DATE).setMinWidth(170);   // the full time, and room for the trash can
    }

    private Integer selectedOrdinal() {
        int sel = annotationsTable.getSelectedRow();
        return sel >= 0 && sel < rowKeys.size() && rowKeys.get(sel) instanceof Integer o ? o : null;
    }

    private void editSelected() {
        Integer ordinal = selectedOrdinal();
        if (ordinal != null && editNoteAtOrdinal != null) editNoteAtOrdinal.accept(ordinal);
    }

    private void delete(int sel) {
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
        Notes store = storeSupplier.get();
        boolean saved = rowKeys.get(sel) instanceof Integer ordinal
                ? store.remove(ordinal)
                : store.removeOrphan((AnnotationStore.Annotation) rowKeys.get(sel));
        logTable.repaint();
        refresh();
        if (onAnnotationChanged != null) onAnnotationChanged.accept(saved);
        int newCount = annotationsTable.getRowCount();
        if (newCount > 0) {
            int next = Math.min(sel, newCount - 1);
            annotationsTable.setRowSelectionInterval(next, next);
        }
    }

    private void jumpToOrdinal(int ordinal) {
        int modelRow = logTableModel.rowOfOrdinal(ordinal);
        if (modelRow < 0) return;
        int viewRow = logTable.convertRowIndexToView(modelRow);
        if (viewRow >= 0) {
            logTable.setRowSelectionInterval(viewRow, viewRow);
            logTable.scrollRectToVisible(logTable.getCellRect(viewRow, 0, true));
        }
    }

    private static Action action(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        };
    }

    /**
     * The notes, with a trash can at the right of the row under the mouse (red when pointed
     * at). A press on it deletes that note without moving the selection.
     */
    private final class NotesTable extends JTable {
        private int hoverRow = -1;
        private boolean overTrash;

        NotesTable(DefaultTableModel model) {
            super(model);
            MouseAdapter hover = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e)  { track(e.getPoint()); }
                @Override public void mouseExited(MouseEvent e) { hoverRow = -1; overTrash = false; repaint(); }
            };
            addMouseListener(hover);
            addMouseMotionListener(hover);
        }

        private void track(Point p) {
            int row = rowAtPoint(p);
            boolean trash = row >= 0 && p.x >= getVisibleRect().x + getVisibleRect().width - TRASH;
            if (row != hoverRow || trash != overTrash) {
                hoverRow = row;
                overTrash = trash;
                repaint();
            }
        }

        @Override protected void processMouseEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(e)) {
                track(e.getPoint());
                if (overTrash && hoverRow >= 0) {
                    int row = hoverRow;
                    e.consume();
                    SwingUtilities.invokeLater(() -> delete(row));
                    return;
                }
            }
            if (e.getID() == MouseEvent.MOUSE_CLICKED && overTrash) return;
            super.processMouseEvent(e);
        }

        @Override public String getToolTipText(MouseEvent e) {
            track(e.getPoint());
            if (overTrash) return Messages.get("annotations.panel.delete.tooltip");
            int row = rowAtPoint(e.getPoint()), col = columnAtPoint(e.getPoint());
            if (row >= 0 && col >= 0 && convertColumnIndexToModel(col) == COL_NOTE) {
                String note = String.valueOf(getValueAt(row, col));
                // Only when the column cuts it short.
                int room = getCellRect(row, col, false).width - 16;
                if (note.isBlank() || getFontMetrics(getFont()).stringWidth(note) <= room) return null;
                return "<html><div style='width:320px'>"
                        + note.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>";
            }
            return super.getToolTipText(e);
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (hoverRow < 0 || hoverRow >= getRowCount()) return;
            Rectangle r = getCellRect(hoverRow, 0, true);
            Rectangle v = getVisibleRect();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = isRowSelected(hoverRow) ? getSelectionForeground() : ClouseauColors.dimForeground();
            ToolIcons.paintTrash(g2, v.x + v.width - TRASH + 9, r.y + (r.height - 14) / 2,
                    overTrash ? ClouseauColors.levelColor(LogEntry.LogLevel.ERROR) : base);
            g2.dispose();
        }
    }
}
