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
    /** The query the current matches are for, lower case; null with none. */
    private String matchedQuery;
    /** Matching model rows in view order. */
    private List<Integer> matches = List.of();
    private int cursor = -1;
    private javax.swing.Timer debounce;

    /** Colors rows (a palette slot, or null to clear) and reads a row's color; set by the tab. */
    interface Highlighter {
        void highlight(List<Integer> modelRows, Integer slot);
        Color highlightOf(int modelRow);
    }

    private Highlighter highlighter;
    private final JButton highlightBtn = new JButton(Messages.get("find.highlight"));

    FindBar(JTable table, Search searcher) {
        super(new MigLayout("insets 3 8 3 8, gap 6", "[][grow][][][]8[][]", "[center]"));
        this.table = table;
        this.searcher = searcher;
        setBorder(new BarSeparator());
        setVisible(false);

        JLabel label = new JLabel(Messages.get("find.label"));
        ClouseauColors.themedForeground(label, ClouseauColors::dimForeground);
        label.setFont(label.getFont().deriveFont(11f));

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
        // Highlight ⌄: color every match at once, with the row menu's swatches.
        FilterBar.pickerArrow(highlightBtn);
        highlightBtn.setFont(highlightBtn.getFont().deriveFont(11f));
        highlightBtn.setMargin(new Insets(1, 6, 1, 6));
        highlightBtn.setEnabled(false);
        highlightBtn.addActionListener(e -> showHighlightPopup());

        add(label);
        add(field, "growx");
        add(counter);
        add(prevBtn);
        add(nextBtn);
        add(highlightBtn, "hidemode 3");
        add(closeBtn);
    }

    /** Lets the bar color its matches; without one, the Highlight button stays hidden. */
    void setHighlighter(Highlighter highlighter) {
        this.highlighter = highlighter;
        highlightBtn.setVisible(highlighter != null);
    }

    /**
     * The swatches, under the button: a click colors every match in that color (a white ring
     * marks the color they all have already), and Clear Highlight uncolors them.
     */
    private void showHighlightPopup() {
        if (highlighter == null || matches.isEmpty()) return;
        List<Integer> rows = matches;
        JPopupMenu popup = new JPopupMenu();
        JLabel title = new JLabel(MessageFormat.format(Messages.get("find.highlight.title"), rows.size()));
        ClouseauColors.themedForeground(title, ClouseauColors::dimForeground);
        JPanel head = new JPanel(new MigLayout("insets 6 8 2 8, gap 0, wrap 1"));
        head.setOpaque(false);
        head.add(title, "gapleft 4");

        JPanel swatches = new JPanel(new MigLayout("insets 4, gap 4"));   // not FlowLayout: in a MigLayout it asks for ~600px
        swatches.setOpaque(false);
        Color[] palette = ClouseauColors.highlightColors();
        for (int i = 0; i < palette.length; i++) {
            Color color = palette[i];
            int slot = i;
            boolean all = rows.stream().allMatch(r -> color.equals(highlighter.highlightOf(r)));
            JButton swatch = RowMenu.swatch(color, all);
            swatch.addActionListener(e -> {
                popup.setVisible(false);
                highlighter.highlight(rows, slot);
            });
            swatches.add(swatch, "w 22!, h 22!");   // FlatLaf's button minimum width would stretch it
        }
        head.add(swatches);
        popup.add(head);
        popup.addSeparator();
        JMenuItem clear = new JMenuItem(Messages.get("find.highlight.clear"));
        clear.setEnabled(rows.stream().anyMatch(r -> highlighter.highlightOf(r) != null));
        clear.addActionListener(e -> highlighter.highlight(rows, null));
        popup.add(clear);
        // Right-aligned with the button: it sits at the window's right edge.
        popup.show(highlightBtn, highlightBtn.getWidth() - popup.getPreferredSize().width, highlightBtn.getHeight());
    }

    /**
     * The hairline under a bar above the table (this one and {@link HighlightNavBar}), drawn
     * only when another bar follows: the table's frame already draws the line above itself,
     * and both together read as one thick line. In the separator color, read when painted, so
     * it follows a theme switch.
     */
    static final class BarSeparator extends javax.swing.border.AbstractBorder {
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            if (!followedByBar(c)) return;
            g.setColor(ClouseauColors.separatorColor());
            g.fillRect(x, y + h - 1, w, 1);
        }

        @Override public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(0, 0, 1, 0);      // the same height either way, so nothing shifts
            return insets;
        }

        private static boolean followedByBar(Component c) {
            Container parent = c.getParent();
            if (parent == null) return false;
            boolean after = false;
            for (Component sibling : parent.getComponents()) {
                if (sibling == c) { after = true; continue; }
                if (after && sibling.isVisible()) return sibling instanceof FindBar || sibling instanceof HighlightNavBar;
            }
            return false;
        }
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

    /** The text the matches' marks go on (lower case), or null with no matches. */
    String markQuery() {
        return matches.isEmpty() ? null : matchedQuery;
    }

    /** The table and its scroll pane, whose gutter shows a tick on matching rows. */
    private void repaintTable() {
        table.repaint();
        Container scroll = SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        if (scroll != null) scroll.repaint();
    }

    /** True when the model row is one of the current matches. */
    boolean isMatch(int modelRow) {
        return matchModelRows.contains(modelRow);
    }

    /** Forgets the current matches (the table was cleared or reloaded). */
    void clearMatches() {
        matchModelRows = Set.of();
        matches        = List.of();
        matchedQuery   = null;
        cursor         = -1;
        highlightBtn.setEnabled(false);
        counter.setText("");
        repaintTable();
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
        }, rows -> {
            matchedQuery = query.toLowerCase();
            showMatches(rows);
        });
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
        highlightBtn.setEnabled(n > 0);
        highlightBtn.setToolTipText(n > 0 ? MessageFormat.format(Messages.get("find.highlight.tooltip"), n) : null);
        repaintTable();
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

    /** Whether the bar is open with something typed, so Find Next / Previous can step. */
    boolean hasQuery() { return isVisible() && !field.getText().isBlank(); }

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
