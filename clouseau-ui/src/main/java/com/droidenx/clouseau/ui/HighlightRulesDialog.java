package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry.LogLevel;
import com.droidenx.clouseau.core.HighlightRule;
import com.droidenx.clouseau.core.HighlightRule.Column;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Tools → Highlight Rules… (UC-3, D-29): the auto-highlight rules, a list on the left and the
 * selected rule's form on the right. Changes apply to every open tab as they're made and are
 * saved at once, so there's only Close. Modeless, one at a time, so the rows it colors stay
 * in view while a rule is edited.
 */
final class HighlightRulesDialog extends JDialog {

    private static HighlightRulesDialog open;

    /** Default color for a new rule: Red, the first of the palette. */
    private static final int NEW_RULE_COLOR = 0;
    private static final int SIDEBAR_WIDTH = 260;

    private final List<HighlightRule> rules = new ArrayList<>(HighlightRuleSet.rules());
    private final DefaultListModel<HighlightRule> listModel = new DefaultListModel<>();
    private final RuleList list = new RuleList();
    private final CardLayout formLayout = new CardLayout();
    private final JPanel formCards = new JPanel(formLayout);

    private final JTextField textField = new JTextField(24);
    private final JToggleButton caseToggle = new JToggleButton(Messages.get("detail.find.case"));
    private final JToggleButton regexToggle = new JToggleButton(Messages.get("detail.find.regex"));
    private final JComboBox<Column> columnCombo = new JComboBox<>(Column.values());
    private final JTextField fieldName = new JTextField(12);
    private final JComboBox<LogLevel> levelCombo = new JComboBox<>(new LogLevel[]{
            null, LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL});
    private final ColorPicker colorPicker = new ColorPicker();
    private final JCheckBox enabledBox = new JCheckBox(Messages.get("rules.enabled"));
    private final NoticeLabel notice = new NoticeLabel();
    private final JButton duplicateBtn = new JButton(Messages.get("rules.duplicate"));

    /** True while the form is being filled from the selected rule, so its listeners don't write back. */
    private boolean updatingForm;
    /** Typing applies after a pause, not on every key. */
    private final Timer applyLater = new Timer(250, e -> save());

    /** Opens the dialog, or brings the open one forward; {@code newRule} (or null) is added and selected. */
    static void open(Window owner, HighlightRule newRule) {
        if (open == null || !open.isDisplayable()) {
            open = new HighlightRulesDialog(owner);
            open.setVisible(true);
        } else {
            open.toFront();
        }
        if (newRule != null) open.insert(newRule);
    }

    private HighlightRulesDialog(Window owner) {
        super(owner, Messages.get("rules.title"), ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        applyLater.setRepeats(false);

        JPanel root = new JPanel(new BorderLayout());
        root.add(buildSidebar(), BorderLayout.WEST);
        formCards.add(buildEmptyState(), "empty");
        formCards.add(buildForm(), "form");
        root.add(formCards, BorderLayout.CENTER);

        JButton newBtn = new JButton(Messages.get("rules.new"));
        newBtn.addActionListener(e -> insert(new HighlightRule(true, Column.ANY, null, "", false, false, null, NEW_RULE_COLOR)));
        // New and Duplicate on the footer's left, as in the parser editor; Delete is the row's trash can.
        duplicateBtn.addActionListener(e -> { int i = list.getSelectedIndex(); if (i >= 0) insert(rules.get(i)); });
        JButton closeBtn = new JButton(Messages.get("rules.close"));
        closeBtn.addActionListener(e -> dispose());
        root.add(DialogFooter.of(new JComponent[]{ newBtn, duplicateBtn }, closeBtn), BorderLayout.SOUTH);
        setContentPane(root);
        DialogFooter.closeOnEscape(getRootPane(), this::dispose);

        for (HighlightRule r : rules) listModel.addElement(r);
        // Opens on the first one, so the form shows what an auto-highlight is right away. Unlike
        // the parser editor (DESIGN.md 6.13), selecting one here only shows it: nothing changes.
        if (!rules.isEmpty()) list.setSelectedIndex(0);
        showSelected();

        pack();
        ManagerDialogs.rememberSize(this, "highlight.rules", new Dimension(760, 400), new Dimension(620, 340));
        setLocationRelativeTo(owner);
    }

    @Override public void dispose() {
        if (applyLater.isRunning()) {
            applyLater.stop();
            save();
        }
        super.dispose();
    }

    // ── Sidebar ──────────────────────────────────────────────────────────────

    private JComponent buildSidebar() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new RuleRenderer());
        list.setOpaque(false);
        list.putClientProperty("FlatLaf.style", ClouseauColors.SIDEBAR_LIST);
        ToolTipManager.sharedInstance().registerComponent(list);
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelected(); });
        ManagerDialogs.onDeleteKey(list, this::deleteSelected);
        list.registerKeyboardAction(e -> { int i = list.getSelectedIndex(); if (i >= 0) insert(rules.get(i)); },
                KeyStroke.getKeyStroke(KeyEvent.VK_D, Keys.MENU), JComponent.WHEN_FOCUSED);
        list.registerKeyboardAction(e -> move(-1),
                KeyStroke.getKeyStroke(KeyEvent.VK_UP, java.awt.event.InputEvent.ALT_DOWN_MASK), JComponent.WHEN_FOCUSED);
        list.registerKeyboardAction(e -> move(+1),
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, java.awt.event.InputEvent.ALT_DOWN_MASK), JComponent.WHEN_FOCUSED);
        list.registerKeyboardAction(e -> {
                    int i = list.getSelectedIndex();
                    if (i >= 0) { replace(i, rules.get(i).withEnabled(!rules.get(i).enabled())); save(); }
                },
                KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), JComponent.WHEN_FOCUSED);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        JTextArea order = new JTextArea(Messages.get("rules.order.hint"));
        order.setEditable(false);
        order.setFocusable(false);
        order.setOpaque(false);
        order.setLineWrap(true);
        order.setWrapStyleWord(true);
        order.setFont(UIManager.getFont("Label.font").deriveFont(11f));
        order.setForeground(ClouseauColors.dimForeground());

        JPanel side = new JPanel(new MigLayout("insets 16 16 12 12, fill, gapy 8", "[grow, fill]", "[][grow, fill][]"));
        side.add(ManagerDialogs.sectionTitle(Messages.get("rules.list.title")), "wrap");
        side.add(scroll, "wrap, h 100:100%:, wmin 0");
        side.add(order, "wmin 0");
        side.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 10));
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Separator.foreground")));
        return side;
    }

    private JPopupMenu rowMenu() {
        int i = list.getSelectedIndex();
        JPopupMenu menu = new JPopupMenu();
        int ctrl = Keys.MENU, alt = java.awt.event.InputEvent.ALT_DOWN_MASK;
        JMenuItem duplicate = new JMenuItem(Messages.get("rules.duplicate"));
        duplicate.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, ctrl));
        duplicate.addActionListener(e -> insert(rules.get(i)));
        JMenuItem up = new JMenuItem(Messages.get("rules.move.up"));
        up.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_UP, alt));
        up.setEnabled(i > 0);
        up.addActionListener(e -> move(-1));
        JMenuItem down = new JMenuItem(Messages.get("rules.move.down"));
        down.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, alt));
        down.setEnabled(i < rules.size() - 1);
        down.addActionListener(e -> move(+1));
        JMenuItem delete = new JMenuItem(Messages.get("rules.delete.menu"));
        delete.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
        delete.addActionListener(e -> deleteSelected());
        menu.add(duplicate);
        menu.addSeparator();
        menu.add(up);
        menu.add(down);
        menu.addSeparator();
        menu.add(delete);
        return menu;
    }

    /**
     * The auto-highlights list, with the row tools of DESIGN.md 6.13: Duplicate and the trash can
     * in a gutter at the right edge, painted on the row under the mouse. A click on a row's check
     * box turns it on or off without opening it; a right-click opens the row menu.
     */
    private final class RuleList extends JList<HighlightRule> {
        static final int TOOL = 24;
        private enum Tool { NONE, DUPLICATE, TRASH }
        private int hoverRow = -1;
        private Tool overTool = Tool.NONE;
        private Point lastMouse;

        RuleList() {
            super(listModel);
            MouseAdapter hover = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { track(e.getPoint()); }
                @Override public void mouseExited(MouseEvent e) { lastMouse = null; track(null); }
            };
            addMouseMotionListener(hover);
            addMouseListener(hover);
        }

        private int rowAt(Point p) {
            if (p == null) return -1;
            int i = locationToIndex(p);
            return i >= 0 && getCellBounds(i, i).contains(p) ? i : -1;
        }

        private Tool toolAt(Point p) {
            if (rowAt(p) < 0) return Tool.NONE;
            if (p.x >= getWidth() - TOOL) return Tool.TRASH;
            if (p.x >= getWidth() - 2 * TOOL) return Tool.DUPLICATE;
            return Tool.NONE;
        }

        private void track(Point p) {
            if (p != null) lastMouse = p;
            int row = rowAt(p);
            Tool tool = toolAt(p);
            if (row != hoverRow || tool != overTool) {
                hoverRow = row;
                overTool = tool;
                repaint();
            }
        }

        /** Rows moved under a still mouse: work out again which row and tool it's over. */
        void retrack() { track(lastMouse); }

        @Override protected void processMouseEvent(MouseEvent e) {
            Point p = e.getPoint();
            int row = rowAt(p);
            Tool tool = toolAt(p);
            if (e.getID() == MouseEvent.MOUSE_PRESSED && row >= 0) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    setSelectedIndex(row);
                    requestFocusInWindow();
                    rowMenu().show(this, e.getX(), e.getY());
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && tool != Tool.NONE) {
                    // Acts on its own row without selecting it first (DESIGN.md 6.13), once this
                    // press is done, so a confirmation doesn't open mid-event.
                    SwingUtilities.invokeLater(() -> {
                        if (row >= rules.size()) return;
                        if (tool == Tool.TRASH) delete(row);
                        else insert(rules.get(row), row + 1);
                    });
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && p.x - getCellBounds(row, row).x < RuleRenderer.CHECK_WIDTH) {
                    replace(row, rules.get(row).withEnabled(!rules.get(row).enabled()));
                    save();
                    return;
                }
            }
            if (e.getID() == MouseEvent.MOUSE_CLICKED && tool != Tool.NONE) return;
            super.processMouseEvent(e);
        }

        @Override public String getToolTipText(MouseEvent e) {
            return switch (toolAt(e.getPoint())) {
                case TRASH -> Messages.get("rules.delete.tooltip");
                case DUPLICATE -> Messages.get("rules.duplicate.tooltip");
                case NONE -> super.getToolTipText(e);
            };
        }

        /**
         * As wide as the sidebar: a long rule is cut short with …, never scrolled sideways, which
         * would put the row tools (painted at the list's right edge) out of sight.
         */
        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (hoverRow < 0 || hoverRow >= getModel().getSize()) return;
            Rectangle r = getCellBounds(hoverRow, hoverRow);
            if (r == null) return;
            boolean selected = isSelectedIndex(hoverRow);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = ToolIcons.idleColor(selected, "List");
                int y = r.y + (r.height - 14) / 2;
                ToolIcons.paintTrash(g2, getWidth() - TOOL + 5, y,
                        overTool == Tool.TRASH ? ClouseauColors.levelColor(LogLevel.ERROR) : base);
                ToolIcons.paintDuplicate(g2, getWidth() - 2 * TOOL + 5, y,
                        overTool == Tool.DUPLICATE ? ToolIcons.litColor(selected, "List") : base);
            } finally {
                g2.dispose();
            }
        }
    }

    /** The list's row: a check box (on/off), the rule's color and what it matches. */
    private final class RuleRenderer extends JPanel implements ListCellRenderer<HighlightRule> {
        static final int CHECK_WIDTH = 26;
        private final JCheckBox check = new JCheckBox();
        private final Swatch swatch = new Swatch();
        private final JLabel text = new JLabel();

        RuleRenderer() {
            // The right inset leaves room for the row tools (two of RuleList.TOOL).
            super(new MigLayout("insets 3 4 3 " + (2 * RuleList.TOOL + 4) + ", gap 6", "[" + (CHECK_WIDTH - 10) + "!][][grow]", "[center]"));
            setOpaque(false);
            check.setOpaque(false);
            add(check);
            add(swatch, "w 14!, h 14!");
            add(text, "wmin 0");
        }

        @Override public Component getListCellRendererComponent(JList<? extends HighlightRule> l, HighlightRule rule,
                                                                int index, boolean selected, boolean focus) {
            check.setSelected(rule.enabled());
            swatch.slot = rule.color();
            String summary = summary(rule);
            text.setText(summary);
            text.setForeground(selected ? l.getSelectionForeground()
                    : rule.enabled() && rule.isComplete() ? l.getForeground() : ClouseauColors.mutedForeground());
            setToolTipText(summary);
            // Rows sit on the sidebar; only the selection is filled (DESIGN.md 6.13).
            setOpaque(selected);
            setBackground(l.getSelectionBackground());
            return this;
        }
    }

    /** What a rule matches, in a few words: “timeout” in Message · WARN and above. */
    static String summary(HighlightRule r) {
        String summary = null;
        if (!r.text().isEmpty()) {
            String text = r.regex() ? "/" + r.text() + "/" : MessageFormat.format(Messages.get("rules.summary.text"), r.text());
            if (r.matchCase()) text += " " + Messages.get("rules.summary.case");
            String where = r.column() == Column.FIELD ? (r.field() != null && !r.field().isBlank() ? r.field() : "?")
                         : Messages.get("rules.column." + r.column().name().toLowerCase(java.util.Locale.ROOT));
            summary = r.column() == Column.ANY ? text : MessageFormat.format(Messages.get("rules.summary.in"), text, where);
        }
        if (r.minLevel() != null) {
            String level = levelLabel(r.minLevel());
            summary = summary == null ? level : MessageFormat.format(Messages.get("rules.summary.join"), summary, level);
        }
        return summary != null ? summary : Messages.get("rules.summary.empty");
    }

    private static String levelLabel(LogLevel level) {
        if (level == null) return Messages.get("rules.level.any");
        return level == LogLevel.FATAL ? level.name() : MessageFormat.format(Messages.get("rules.level.above"), level.name());
    }

    // ── Form ─────────────────────────────────────────────────────────────────

    private JComponent buildEmptyState() {
        JPanel empty = new JPanel(new MigLayout("fill, insets 16", "[center]", "push[]6[]push"));
        JLabel title = new JLabel(Messages.get("rules.empty.title"));
        ClouseauColors.themedForeground(title, ClouseauColors::dimForeground);
        JLabel hint = new JLabel(Messages.get("rules.empty.hint"));
        ClouseauColors.themedForeground(hint, ClouseauColors::mutedForeground);
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        empty.add(title, "wrap");
        empty.add(hint);
        return empty;
    }

    private JComponent buildForm() {
        for (JToggleButton t : new JToggleButton[]{ caseToggle, regexToggle }) {
            FilterBar.applyToggleStyle(t);
            t.setFont(t.getFont().deriveFont(11f));
            t.setMargin(new Insets(1, 5, 1, 5));
            t.addActionListener(e -> formChanged(false));
        }
        caseToggle.setToolTipText(Messages.get("detail.find.case.tooltip"));
        regexToggle.setToolTipText(Messages.get("detail.find.regex.tooltip"));
        textField.putClientProperty("JTextField.placeholderText", Messages.get("rules.text.placeholder"));
        fieldName.putClientProperty("JTextField.placeholderText", Messages.get("rules.field.placeholder"));
        textField.getDocument().addDocumentListener(onEdit(() -> formChanged(true)));
        fieldName.getDocument().addDocumentListener(onEdit(() -> formChanged(true)));

        columnCombo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                String key = "rules.column." + ((Column) v).name().toLowerCase(java.util.Locale.ROOT);
                return super.getListCellRendererComponent(l, Messages.get(key), i, s, f);
            }
        });
        columnCombo.addActionListener(e -> {
            fieldName.setVisible(columnCombo.getSelectedItem() == Column.FIELD);
            formChanged(false);
            revalidate();
        });
        levelCombo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, levelLabel((LogLevel) v), i, s, f);
            }
        });
        levelCombo.addActionListener(e -> formChanged(false));
        colorPicker.onPick = slot -> formChanged(false);
        enabledBox.addActionListener(e -> formChanged(false));

        JPanel form = new JPanel(new MigLayout("insets 16 16 12 16, wrap 2, gapy 10, hidemode 3",
                "[right][grow, fill]", "[][][][][]push[]"));
        form.add(new JLabel(Messages.get("rules.text")));
        JPanel textRow = new JPanel(new MigLayout("insets 0, gap 4", "[grow, fill][][]"));
        textRow.add(textField, "wmin 0");
        textRow.add(caseToggle);
        textRow.add(regexToggle);
        form.add(textRow);
        form.add(new JLabel(Messages.get("rules.column")));
        JPanel columnRow = new JPanel(new MigLayout("insets 0, gap 6, hidemode 3", "[][grow, fill]"));
        columnRow.add(columnCombo);
        columnRow.add(fieldName, "wmin 0");
        form.add(columnRow);
        form.add(new JLabel(Messages.get("rules.level")));
        form.add(levelCombo, "growx 0");
        form.add(new JLabel(Messages.get("rules.color")));
        form.add(colorPicker, "growx 0");
        form.add(enabledBox, "skip 1");
        form.add(notice, "span 2, growx 0, wmin 0");
        return form;
    }

    private static DocumentListener onEdit(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    /** Fills the form from the selected rule, or shows the empty state. */
    private void showSelected() {
        int i = list.getSelectedIndex();
        duplicateBtn.setEnabled(i >= 0);
        if (i < 0) {
            formLayout.show(formCards, "empty");
            return;
        }
        HighlightRule r = rules.get(i);
        updatingForm = true;
        try {
            textField.setText(r.text());
            caseToggle.setSelected(r.matchCase());
            regexToggle.setSelected(r.regex());
            columnCombo.setSelectedItem(r.column());
            fieldName.setText(r.field() != null ? r.field() : "");
            fieldName.setVisible(r.column() == Column.FIELD);
            levelCombo.setSelectedItem(r.minLevel());
            colorPicker.setSlot(r.color());
            enabledBox.setSelected(r.enabled());
        } finally {
            updatingForm = false;
        }
        showNotice(r);
        formLayout.show(formCards, "form");
    }

    /** The form changed: the selected rule takes it, and it applies (after a pause while typing). */
    private void formChanged(boolean typing) {
        if (updatingForm) return;
        int i = list.getSelectedIndex();
        if (i < 0) return;
        Column column = (Column) columnCombo.getSelectedItem();
        HighlightRule r = new HighlightRule(enabledBox.isSelected(), column,
                column == Column.FIELD ? fieldName.getText().trim() : null, textField.getText(),
                caseToggle.isSelected(), regexToggle.isSelected(), (LogLevel) levelCombo.getSelectedItem(),
                colorPicker.slot);
        replace(i, r);
        showNotice(r);
        if (typing) applyLater.restart();
        else save();
    }

    /** What the rule still needs, or that it's off; blank when it applies. */
    private void showNotice(HighlightRule r) {
        String error = r.patternError();
        if (error != null) {
            notice.show(MessageFormat.format(Messages.get("rules.notice.pattern"), error), NoticeLabel.Kind.ERROR);
        } else if (r.text().isEmpty() && r.minLevel() == null) {
            notice.setText(Messages.get("rules.notice.needs"));
        } else if (r.column() == Column.FIELD && (r.field() == null || r.field().isBlank())) {
            notice.setText(Messages.get("rules.notice.field"));
        } else {
            notice.setText(" ");
        }
    }

    // ── Changes ──────────────────────────────────────────────────────────────

    private void replace(int i, HighlightRule r) {
        rules.set(i, r);
        listModel.set(i, r);
    }

    /** Adds {@code r} after the selected rule (or last), selects it and puts the cursor in its text. */
    private void insert(HighlightRule r) {
        insert(r, list.getSelectedIndex() >= 0 ? list.getSelectedIndex() + 1 : rules.size());
    }

    private void insert(HighlightRule r, int at) {
        rules.add(at, r);
        listModel.add(at, r);
        list.setSelectedIndex(at);
        list.ensureIndexIsVisible(at);
        list.retrack();
        save();
        textField.requestFocusInWindow();
    }

    private void move(int by) {
        int i = list.getSelectedIndex(), j = i + by;
        if (i < 0 || j < 0 || j >= rules.size()) return;
        HighlightRule r = rules.remove(i);
        rules.add(j, r);
        listModel.remove(i);
        listModel.add(j, r);
        list.setSelectedIndex(j);
        list.retrack();
        save();
    }

    private void deleteSelected() {
        if (list.getSelectedIndex() >= 0) delete(list.getSelectedIndex());
    }

    /** Deletes the auto-highlight at {@code i} once confirmed; another row's selection stays. */
    private void delete(int i) {
        if (!ManagerDialogs.confirmDelete(this, Messages.get("rules.delete.title"),
                MessageFormat.format(Messages.get("rules.delete.message"), summary(rules.get(i))))) return;
        int selected = list.getSelectedIndex();
        rules.remove(i);
        listModel.remove(i);
        if (selected == i && !rules.isEmpty()) list.setSelectedIndex(Math.min(i, rules.size() - 1));
        list.retrack();
        save();
        list.requestFocusInWindow();
    }

    /** Applies the rules to every tab and saves them, saying so if they couldn't be saved. */
    private void save() {
        applyLater.stop();
        if (!HighlightRuleSet.set(rules)) {
            notice.show(Messages.get("rules.notice.not.saved"), NoticeLabel.Kind.ERROR);
        }
    }

    // ── Color ────────────────────────────────────────────────────────────────

    /** A highlight palette square, ringed when it's the chosen one. */
    private static final class Swatch extends JComponent {
        int slot;
        boolean ringed;

        @Override protected void paintComponent(Graphics g) {
            Color[] palette = ClouseauColors.highlightColors();
            if (slot < 0 || slot >= palette.length) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = palette[slot];
            g2.setColor(c);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
            if (ringed) {
                g2.setColor(ClouseauColors.highlightRing());
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 4, 4);
            } else {
                ClouseauColors.paintHighlightEdge(g2, c, 0, 0, getWidth(), getHeight(), 5);
            }
            g2.dispose();
        }
    }

    /** The eight highlight colors in a row; the chosen one is ringed, as in the row menu. */
    private static final class ColorPicker extends JPanel {
        int slot;
        java.util.function.IntConsumer onPick = s -> {};
        private final List<Swatch> swatches = new ArrayList<>();

        ColorPicker() {
            super(new MigLayout("insets 0, gap 4"));
            setOpaque(false);
            int colors = ClouseauColors.highlightColors().length;
            for (int s = 0; s < colors; s++) {
                Swatch sw = new Swatch();
                sw.slot = s;
                sw.setToolTipText(Messages.get("highlight.color." + s));
                sw.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                int pick = s;
                sw.addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) {
                        setSlot(pick);
                        onPick.accept(pick);
                    }
                });
                swatches.add(sw);
                add(sw, "w 22!, h 22!");
            }
        }

        void setSlot(int s) {
            slot = s;
            for (Swatch sw : swatches) sw.ringed = sw.slot == s;
            repaint();
        }
    }
}
