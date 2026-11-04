package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.ZipLogs;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lets the user choose which files of a multi-file {@code .zip} to open (G-6). Each chosen
 * file opens in its own tab. A filter box narrows the list; "Select all" ticks or unticks
 * every file the filter shows.
 */
final class ZipPickerDialog extends JDialog {

    private final List<ZipLogs.Entry> entries;
    private final boolean[] ticked;
    private final EntryModel model;
    private final JTable table;
    private final TableRowSorter<EntryModel> sorter;
    private final JTextField filterField = new JTextField(24);
    private final JCheckBox selectAll = new JCheckBox(Messages.get("zip.picker.select.all"));
    private final JButton openButton = new JButton();
    private List<ZipLogs.Entry> result = List.of();
    private boolean updatingSelectAll;

    ZipPickerDialog(Frame owner, String zipName, List<ZipLogs.Entry> entries) {
        super(owner, MessageFormat.format(Messages.get("zip.picker.title"), zipName), true);
        this.entries = entries;
        this.ticked = new boolean[entries.size()];
        this.model  = new EntryModel();
        this.table  = new JTable(model);
        this.sorter = new TableRowSorter<>(model);
        setContentPane(buildContent());
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(openButton);
        updateState();
        pack();
        setSize(Math.max(getWidth(), 640), Math.min(Math.max(getHeight(), 360), 600));
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog; returns the chosen files in archive order (empty if cancelled). */
    List<ZipLogs.Entry> showDialog() {
        setVisible(true);
        return result;
    }

    private JPanel buildContent() {
        JPanel p = new JPanel(new MigLayout("insets 12, fill", "[grow]", "[][][grow][]"));
        p.add(new JLabel(MessageFormat.format(Messages.get("zip.picker.message"), entries.size())), "wrap");

        filterField.putClientProperty("JTextField.placeholderText", Messages.get("zip.picker.filter"));
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        selectAll.addActionListener(e -> {
            if (updatingSelectAll) return;
            for (int view = 0; view < table.getRowCount(); view++) {
                ticked[table.convertRowIndexToModel(view)] = selectAll.isSelected();
            }
            model.fireTableDataChanged();
            updateState();
        });
        p.add(filterField, "split 2, growx");
        p.add(selectAll, "gapleft 12, wrap");

        table.setRowSorter(sorter);
        table.setFillsViewportHeight(true);
        table.setRowHeight(AppPrefs.getRowHeight());
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setMaxWidth(120);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(2).setCellRenderer(right);
        // Space toggles every selected row, like ticking them one by one.
        table.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SPACE, 0), "toggle");
        table.getActionMap().put("toggle", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                int[] rows = table.getSelectedRows();
                if (rows.length == 0) return;
                boolean to = !ticked[table.convertRowIndexToModel(rows[0])];
                for (int view : rows) ticked[table.convertRowIndexToModel(view)] = to;
                model.fireTableDataChanged();
                for (int view : rows) table.addRowSelectionInterval(view, view);
                updateState();
            }
        });
        p.add(new JScrollPane(table), "grow, wrap");

        openButton.addActionListener(e -> {
            List<ZipLogs.Entry> chosen = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) if (ticked[i]) chosen.add(entries.get(i));
            result = chosen;
            dispose();
        });
        JButton cancel = new JButton(Messages.get("zip.picker.cancel"));
        cancel.addActionListener(e -> dispose());
        p.add(openButton, "split 2, right, tag ok");
        p.add(cancel, "tag cancel");
        return p;
    }

    private void applyFilter() {
        String text = filterField.getText().trim().toLowerCase(Locale.ROOT);
        sorter.setRowFilter(text.isEmpty() ? null : new RowFilter<>() {
            @Override public boolean include(Entry<? extends EntryModel, ? extends Integer> row) {
                return entries.get(row.getIdentifier()).name().toLowerCase(Locale.ROOT).contains(text);
            }
        });
        updateState();
    }

    /** Syncs "Select all" with the visible rows and the Open button with the ticked count. */
    private void updateState() {
        int visible = table.getRowCount();
        boolean all = visible > 0;
        for (int view = 0; view < visible && all; view++) {
            if (!ticked[table.convertRowIndexToModel(view)]) all = false;
        }
        updatingSelectAll = true;
        selectAll.setSelected(all);
        selectAll.setEnabled(visible > 0);
        updatingSelectAll = false;

        int count = 0;
        for (boolean t : ticked) if (t) count++;
        openButton.setText(count > 1
                ? MessageFormat.format(Messages.get("zip.picker.open.count"), count)
                : Messages.get("zip.picker.open"));
        openButton.setEnabled(count > 0);
    }

    static String formatSize(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    private final class EntryModel extends AbstractTableModel {
        @Override public int getRowCount()    { return entries.size(); }
        @Override public int getColumnCount() { return 3; }

        @Override public String getColumnName(int col) {
            return switch (col) {
                case 1 -> Messages.get("zip.picker.col.name");
                case 2 -> Messages.get("zip.picker.col.size");
                default -> "";
            };
        }

        @Override public Class<?> getColumnClass(int col) {
            return col == 0 ? Boolean.class : String.class;
        }

        @Override public boolean isCellEditable(int row, int col) { return col == 0; }

        @Override public Object getValueAt(int row, int col) {
            ZipLogs.Entry e = entries.get(row);
            return switch (col) {
                case 0 -> ticked[row];
                case 1 -> e.name();
                default -> formatSize(e.size());
            };
        }

        @Override public void setValueAt(Object value, int row, int col) {
            if (col != 0) return;
            ticked[row] = Boolean.TRUE.equals(value);
            fireTableCellUpdated(row, col);
            updateState();
        }
    }
}
