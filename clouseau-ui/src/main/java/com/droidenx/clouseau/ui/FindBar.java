package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.text.MessageFormat;
import java.util.*;
import java.util.List;

/**
 * The find bar of one tab (Ctrl+F): searches every column of the rows the filters show,
 * in the background, and steps through the matches with Enter / Shift+Enter.
 *
 * <p>The table's renderer asks {@link #isMatch(int)} to tint matching rows. Must be used
 * on the EDT.
 */
final class FindBar extends JPanel {

    private final JTable table;
    private final LogTableModel model;
    private final JTextField field = new JTextField();
    private final JLabel counter = new JLabel();

    private Set<Integer>  matchModelRows = Set.of();
    /** Matching model rows in view order. */
    private List<Integer> matches = List.of();
    private int cursor = -1;
    private javax.swing.Timer debounce;

    FindBar(JTable table, LogTableModel model) {
        super(new MigLayout("insets 3 8 3 8, gap 4", "[][grow][][][]", "[center]"));
        this.table = table;
        this.model = model;
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ClouseauColors.separatorColor()));
        setVisible(false);

        JLabel label = new JLabel(Messages.get("find.label"));
        ClouseauColors.themedForeground(label, ClouseauColors::dimForeground);
        label.setFont(label.getFont().deriveFont(12f));

        field.putClientProperty("JTextField.placeholderText", Messages.get("find.placeholder"));

        JButton clearFieldBtn = new JButton("\u00d7");
        clearFieldBtn.setVisible(false);
        clearFieldBtn.setFont(clearFieldBtn.getFont().deriveFont(14f));
        clearFieldBtn.setBorderPainted(false);
        clearFieldBtn.setContentAreaFilled(false);
        clearFieldBtn.setFocusPainted(false);
        clearFieldBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearFieldBtn.addActionListener(e -> { field.setText(""); field.requestFocusInWindow(); });
        field.putClientProperty("JTextField.trailingComponent", clearFieldBtn);

        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onTextChanged(); }
            public void removeUpdate(DocumentEvent e) { onTextChanged(); }
            public void changedUpdate(DocumentEvent e) {}
            private void onTextChanged() {
                clearFieldBtn.setVisible(!field.getText().isEmpty());
                if (debounce != null) debounce.stop();
                debounce = new javax.swing.Timer(250, ev -> search(field.getText()));
                debounce.setRepeats(false);
                debounce.start();
            }
        });
        field.addActionListener(e -> navigate(+1));
        field.registerKeyboardAction(e -> navigate(-1),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                JComponent.WHEN_FOCUSED);
        field.registerKeyboardAction(e -> close(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_FOCUSED);

        ClouseauColors.themedForeground(counter, ClouseauColors::dimForeground);
        counter.setFont(counter.getFont().deriveFont(11f));
        counter.setPreferredSize(new Dimension(80, counter.getPreferredSize().height));

        JButton prevBtn = arrowButton("\u25b2");
        JButton nextBtn = arrowButton("\u25bc");
        prevBtn.setToolTipText(Messages.get("find.prev.tooltip"));
        nextBtn.setToolTipText(Messages.get("find.next.tooltip"));
        prevBtn.addActionListener(e -> navigate(-1));
        nextBtn.addActionListener(e -> navigate(+1));

        JButton closeBtn = new JButton("\u00d7");
        closeBtn.setFont(closeBtn.getFont().deriveFont(13f));
        closeBtn.setMargin(new Insets(1, 4, 1, 4));
        closeBtn.addActionListener(e -> close());

        add(label);
        add(field, "growx");
        add(counter);
        add(prevBtn);
        add(nextBtn);
        add(closeBtn);
    }

    /** A small up/down arrow button, shared with {@link HighlightNavBar}. */
    static JButton arrowButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(btn.getFont().deriveFont(11f));
        btn.setPreferredSize(new Dimension(22, 18));
        btn.setMargin(new Insets(0, 2, 0, 2));
        return btn;
    }

    /** Shows the bar and puts the cursor in the search field, text selected. */
    void open() {
        setVisible(true);
        field.selectAll();
        field.requestFocusInWindow();
    }

    /** Hides the bar, drops the matches and returns focus to the table. */
    private void close() {
        setVisible(false);
        clearMatches();
        table.requestFocusInWindow();
    }

    /** The counter's text ("3 / 12", "0 matches"); for tests. */
    String counterText() {
        return counter.getText();
    }

    /** True when the model row is one of the current matches. */
    boolean isMatch(int modelRow) {
        return matchModelRows.contains(modelRow);
    }

    /** Forgets the current matches (the table was cleared or reloaded). */
    void clearMatches() {
        matchModelRows = Set.of();
        matches        = List.of();
        cursor         = -1;
        counter.setText("");
        table.repaint();
    }

    void search(String query) {
        if (query == null || query.isBlank()) {
            model.cancelFind();
            clearMatches();
            return;
        }
        String lower = query.toLowerCase();
        // Read in the background: on an indexed file, every visible row comes from disk.
        model.find(e -> matches(e, lower), percent -> {
            if (percent < 0) return;
            ClouseauColors.themedForeground(counter, ClouseauColors::dimForeground);
            counter.setText(MessageFormat.format(Messages.get("find.searching"), percent));
        }, this::showMatches);
    }

    /** Shows the result of a find: the ordinals of the matching entries, ascending. */
    private void showMatches(int[] ordinals) {
        List<Integer> list = new ArrayList<>();
        Set<Integer>  set  = new HashSet<>();
        for (int ordinal : ordinals) {
            int modelRow = model.rowOfOrdinal(ordinal);   // rows may have changed since
            if (modelRow >= 0) {
                list.add(modelRow);
                set.add(modelRow);
            }
        }
        // Next and previous follow the view, which may be sorted.
        list.sort(Comparator.comparingInt(table::convertRowIndexToView));
        matchModelRows = Collections.unmodifiableSet(set);
        matches        = Collections.unmodifiableList(list);
        cursor         = -1;
        int n = list.size();
        ClouseauColors.themedForeground(counter,
                () -> n == 0 ? ClouseauColors.statusError() : ClouseauColors.dimForeground());
        counter.setText(MessageFormat.format(Messages.get("find.count"), n));
        table.repaint();
        if (n > 0) navigate(+1);
    }

    static boolean matches(LogEntry entry, String lower) {
        if (entry.message() != null && entry.message().toLowerCase().contains(lower)) return true;
        if (entry.logger()  != null && entry.logger().toLowerCase().contains(lower))  return true;
        if (entry.thread()  != null && entry.thread().toLowerCase().contains(lower))  return true;
        if (entry.level()   != null && entry.level().name().toLowerCase().contains(lower)) return true;
        if (entry.fields()  != null) {
            for (String v : entry.fields().values()) {
                if (v != null && v.toLowerCase().contains(lower)) return true;
            }
        }
        return false;
    }

    void navigate(int direction) {
        if (matches.isEmpty()) return;
        if (cursor < 0) {
            int currentView  = table.getSelectedRow();
            int currentModel = currentView >= 0 ? table.convertRowIndexToModel(currentView) : -1;
            cursor = 0;
            if (direction > 0) {
                for (int i = 0; i < matches.size(); i++) {
                    if (matches.get(i) >= currentModel) { cursor = i; break; }
                }
            } else {
                cursor = matches.size() - 1;
                for (int i = matches.size() - 1; i >= 0; i--) {
                    if (matches.get(i) <= currentModel) { cursor = i; break; }
                }
            }
        } else {
            cursor = (cursor + direction + matches.size()) % matches.size();
        }
        int viewRow = table.convertRowIndexToView(matches.get(cursor));
        if (viewRow >= 0) {
            table.setRowSelectionInterval(viewRow, viewRow);
            table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
            counter.setText((cursor + 1) + " / " + matches.size());
        }
    }
}
