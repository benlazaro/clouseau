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
 * <p>The table's renderer asks {@link #isMatch(int)} to tint matching rows. What is searched
 * comes from a {@link Search}: a log table's entries ({@link #searchIn(LogTableModel)}) or
 * a plain-text view's lines. Must be used on the EDT.
 */
final class FindBar extends JPanel {

    /** Runs a find for the bar, in the background. */
    interface Search {
        /**
         * Finds the rows matching {@code lowerQuery} (already lower case), replacing any
         * find still running. On the EDT, {@code progress} gets a percentage while it reads,
         * and {@code done} gets the matching model rows; neither is called once cancelled.
         */
        void find(String lowerQuery, java.util.function.IntConsumer progress,
                  java.util.function.Consumer<int[]> done);

        /** Stops a running find; its callbacks won't be called. */
        void cancel();
    }

    /** Searches every column of the entries a log table shows. */
    static Search searchIn(LogTableModel model) {
        return new Search() {
            @Override
            public void find(String lowerQuery, java.util.function.IntConsumer progress,
                             java.util.function.Consumer<int[]> done) {
                model.find(e -> matches(e, lowerQuery), pct -> { if (pct >= 0) progress.accept(pct); },
                        ordinals -> done.accept(java.util.Arrays.stream(ordinals)
                                .map(model::rowOfOrdinal)          // rows may have changed since
                                .filter(row -> row >= 0)
                                .toArray()));
            }

            @Override
            public void cancel() {
                model.cancelFind();
            }
        };
    }

    private final JTable table;
    private final Search searcher;
    private final JTextField field = new JTextField();
    private final JLabel counter = new JLabel();

    private Set<Integer>  matchModelRows = Set.of();
    /** Matching model rows in view order. */
    private List<Integer> matches = List.of();
    private int cursor = -1;
    private javax.swing.Timer debounce;

    FindBar(JTable table, Search searcher) {
        super(new MigLayout("insets 3 8 3 8, gap 4", "[][grow][][][]", "[center]"));
        this.table = table;
        this.searcher = searcher;
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ClouseauColors.separatorColor()));
        setVisible(false);

        JLabel label = new JLabel(Messages.get("find.label"));
        ClouseauColors.themedForeground(label, ClouseauColors::dimForeground);
        label.setFont(label.getFont().deriveFont(12f));

        field.putClientProperty("JTextField.placeholderText", Messages.get("find.placeholder"));

        JButton clearFieldBtn = new JButton("\u00d7");
        clearFieldBtn.setVisible(false);
        clearFieldBtn.setFont(clearFieldBtn.getFont().deriveFont(14f));
        clearFieldBtn.setMargin(new Insets(0, 4, 0, 4));
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
            searcher.cancel();
            clearMatches();
            return;
        }
        // Read in the background: on an indexed file, every visible row comes from disk.
        searcher.find(query.toLowerCase(), percent -> {
            ClouseauColors.themedForeground(counter, ClouseauColors::dimForeground);
            counter.setText(MessageFormat.format(Messages.get("find.searching"), percent));
        }, this::showMatches);
    }

    /** Stops a running find, e.g. when the tab's content is replaced. */
    void cancel() {
        searcher.cancel();
        clearMatches();
    }

    /** Shows the result of a find: the matching model rows. */
    private void showMatches(int[] modelRows) {
        List<Integer> list = new ArrayList<>();
        Set<Integer>  set  = new HashSet<>();
        for (int modelRow : modelRows) {
            list.add(modelRow);
            set.add(modelRow);
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
