package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import com.droidenx.clouseau.core.CombinedFilter;
import com.droidenx.clouseau.core.EntryFilter;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.List;

/**
 * Per-tab filter bar: level toggles, logger picker, timestamp range, and text/regex search.
 * Call {@link #buildFilter()} to get the current combined filter.
 * Call {@link #updateLoggers(List)} after a file is loaded to populate the logger picker.
 */
final class FilterBar extends JPanel {

    private static final LogLevel[] LEVELS = {
        LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO,
        LogLevel.WARN,  LogLevel.ERROR, LogLevel.FATAL, LogLevel.UNKNOWN
    };

    // Level colors are read from UIManager via ClouseauColors at paint time.

    private static final List<DateTimeFormatter> TS_FORMATS = List.of(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("HH:mm:ss")
    );

    private final Map<LogLevel, JToggleButton> levelButtons = new EnumMap<>(LogLevel.class);
    private final JButton        loggerButton;
    private final JButton        fieldsButton;
    private final JButton        sourcesButton;
    private final JTextField     fromField   = new JTextField(19);
    private final JTextField     toField     = new JTextField(19);
    private final JTextField     searchField = new JTextField(16);
    private final Runnable       onChanged;
    private JToggleButton        followBtn;
    private Timer debounceTimer;

    // Logger picker state
    private List<String>     allLoggers      = List.of();
    private final Set<String> excludedLoggers = new LinkedHashSet<>();

    /** Sentinel value representing a null/blank/absent field value in the fields filter popup. */
    static final String EMPTY_SENTINEL = CombinedFilter.EMPTY_VALUE;

    // Field value picker state: fieldKey → sorted distinct values / excluded values
    private final Map<String, TreeSet<String>> allFieldValues      = new LinkedHashMap<>();
    private final Map<String, Set<String>>     excludedFieldValues = new LinkedHashMap<>();

    // Source picker state (a merged view, D-17): source names, and the indexes hidden
    private List<String>        allSources      = List.of();
    private final Set<Integer>  excludedSources = new LinkedHashSet<>();

    // Used when only HH:mm:ss is typed — anchors time to the date of the first log entry
    private LocalDate referenceDate = LocalDate.now();
    private boolean referenceDateSet = false;

    FilterBar(Runnable onChanged) {
        super(new MigLayout("insets 2 8 2 8, gap 4", "", "[center]"));
        this.onChanged = onChanged;

        // ── Level toggle buttons ───────────────────────────────────────────────
        for (LogLevel level : LEVELS) {
            JToggleButton btn = new JToggleButton(level.name());
            btn.setSelected(level != LogLevel.UNKNOWN);
            btn.setFocusPainted(false);
            btn.setMargin(new Insets(2, 5, 2, 5));
            btn.setFont(btn.getFont().deriveFont(11f));
            // Read at each use, so the colors follow theme switches.
            java.util.function.Supplier<Color> fg = () -> btn.isSelected()
                    ? ClouseauColors.levelColor(level) : ClouseauColors.offColor();
            ClouseauColors.themedForeground(btn, fg);
            btn.setToolTipText("Click to toggle · Alt+click to solo / restore all");
            btn.addItemListener(e -> {
                btn.setForeground(fg.get());
                onChanged.run();
            });
            btn.addMouseListener(new MouseAdapter() {
                private boolean wasSoloed;
                @Override public void mousePressed(MouseEvent e) {
                    if (e.isAltDown())
                        wasSoloed = levelButtons.entrySet().stream()
                                .allMatch(en -> en.getValue().isSelected() == (en.getKey() == level));
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.isAltDown()) soloLevel(level, wasSoloed);
                }
            });
            levelButtons.put(level, btn);
            add(btn);
        }

        add(new JSeparator(JSeparator.VERTICAL), "growy, gapx 4");

        // ── Source picker button (merged views only) ──────────────────────────
        sourcesButton = new JButton(Messages.get("filter.sources.all"));
        sourcesButton.addActionListener(e -> showSourcesPopup(sourcesButton));
        sourcesButton.setVisible(false);
        add(sourcesButton, "hidemode 3");

        // ── Logger picker button ──────────────────────────────────────────────
        loggerButton = new JButton(Messages.get("filter.loggers.all"));
        loggerButton.addActionListener(e -> showLoggerPopup(loggerButton));
        add(loggerButton);

        // ── Field value picker button ─────────────────────────────────────────
        fieldsButton = new JButton(Messages.get("filter.fields.all"));
        fieldsButton.addActionListener(e -> showFieldsPopup(fieldsButton));
        fieldsButton.setVisible(false);
        // Like the Sources button: takes no room while hidden, so the time fields keep theirs.
        add(fieldsButton, "hidemode 3");

        add(new JSeparator(JSeparator.VERTICAL), "growy, gapx 4");

        // ── Timestamp range ───────────────────────────────────────────────────
        fromField.putClientProperty("JTextField.placeholderText", Messages.get("filter.from.placeholder"));
        toField.putClientProperty("JTextField.placeholderText",   Messages.get("filter.to.placeholder"));

        DocumentListener tsDoc = docListener(() -> {
            validateTimestamp(fromField);
            validateTimestamp(toField);
            scheduleChange();
        });
        fromField.getDocument().addDocumentListener(tsDoc);
        toField.getDocument().addDocumentListener(tsDoc);

        add(new JLabel(Messages.get("filter.from.label")));
        add(fromField);
        add(new JLabel(Messages.get("filter.to.label")));
        add(toField);

        add(new JSeparator(JSeparator.VERTICAL), "growy, gapx 4");

        // ── Text / regex search ───────────────────────────────────────────────
        searchField.putClientProperty("JTextField.placeholderText", Messages.get("filter.search.placeholder"));

        JButton clearSearchBtn = new JButton("\u00d7");
        clearSearchBtn.setVisible(false);
        clearSearchBtn.setFont(clearSearchBtn.getFont().deriveFont(14f));
        clearSearchBtn.setBorderPainted(false);
        clearSearchBtn.setContentAreaFilled(false);
        clearSearchBtn.setFocusPainted(false);
        clearSearchBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearSearchBtn.addActionListener(e -> { searchField.setText(""); searchField.requestFocusInWindow(); });
        searchField.putClientProperty("JTextField.trailingComponent", clearSearchBtn);

        searchField.getDocument().addDocumentListener(docListener(() -> {
            clearSearchBtn.setVisible(!searchField.getText().isEmpty());
            scheduleChange();
        }));
        add(searchField, "growx");

        // ── Clear button ──────────────────────────────────────────────────────
        JButton clearBtn = new JButton(Messages.get("filter.clear"));
        clearBtn.addActionListener(e -> clear());
        add(clearBtn);

        add(new JSeparator(JSeparator.VERTICAL), "growy, gapx 4, pushx");
        add(new JSeparator(JSeparator.VERTICAL), "growy, gapx 4");

        // ── Follow toggle ─────────────────────────────────────────────────────
        followBtn = new JToggleButton(Messages.get("toolbar.follow"));
        followBtn.setFocusPainted(false);
        followBtn.setMargin(new Insets(2, 5, 2, 5));
        followBtn.setFont(followBtn.getFont().deriveFont(11f));
        followBtn.setToolTipText(Messages.get("toolbar.follow.tooltip"));
        applyToggleStyle(followBtn);
        add(followBtn);
    }

    /** Resets logger and field state at the start of a new file load. */
    void clearLoggers() {
        allLoggers = List.of();
        excludedLoggers.clear();
        referenceDateSet = false;
        updateLoggerButtonLabel();
        allFieldValues.clear();
        excludedFieldValues.clear();
        // Field filters restored from a session apply again to the reloaded rows (D-19).
        pendingFieldValues.forEach((k, v) -> excludedFieldValues.put(k, new LinkedHashSet<>(v)));
        fieldsButton.setVisible(false);
        updateFieldsButtonLabel();
        setSources(List.of());
    }

    /**
     * The files of a merged view (D-17), in the tab's order, for the Sources picker; an
     * empty list (one file) hides it. Clears what was hidden, except files a restored
     * session hides (D-19).
     */
    void setSources(List<String> names) {
        allSources = List.copyOf(names);
        excludedSources.clear();
        if (!names.isEmpty()) {
            pendingSources.stream().filter(i -> i < names.size()).forEach(excludedSources::add);
            pendingSources.clear();
        }
        sourcesButton.setVisible(allSources.size() > 1);
        updateSourcesButtonLabel();
    }

    // ── Sessions (UC-17, D-19) ────────────────────────────────────────────────

    /*
     * Filters restored from a session name loggers, field values and merged files that the
     * tab hasn't read yet, and the filter bar otherwise forgets exclusions for loggers it
     * doesn't know. So they wait here: a pending logger joins excludedLoggers when it first
     * appears, pending sources apply when the merged view names its files, and pending
     * field values are put back after every reset. buildFilter includes them meanwhile, so
     * the rows they hide are never shown. Clear All Filters drops them.
     */
    private final Set<String> pendingLoggers = new LinkedHashSet<>();
    private final Map<String, Set<String>> pendingFieldValues = new LinkedHashMap<>();
    private final Set<Integer> pendingSources = new LinkedHashSet<>();

    /** The filters as a session keeps them, or null when they show everything. */
    com.droidenx.clouseau.core.Session.Filters state() {
        List<String> levels = new ArrayList<>();
        levelButtons.forEach((level, btn) -> { if (btn.isSelected()) levels.add(level.name()); });
        Set<String> loggers = new LinkedHashSet<>(excludedLoggers);
        loggers.addAll(pendingLoggers);
        Map<String, List<String>> fields = new LinkedHashMap<>();
        excludedFieldValues.forEach((k, v) -> { if (!v.isEmpty()) fields.put(k, List.copyOf(v)); });
        Set<Integer> sources = new java.util.TreeSet<>(excludedSources);
        sources.addAll(pendingSources);
        var state = new com.droidenx.clouseau.core.Session.Filters(levels, List.copyOf(loggers), fields,
                fromField.getText().trim(), toField.getText().trim(), searchField.getText(), List.copyOf(sources));
        return isDefault(state) ? null : state;
    }

    /** Whether {@code f} shows everything: every level but UNKNOWN, and nothing else set. */
    static boolean isDefault(com.droidenx.clouseau.core.Session.Filters f) {
        Set<String> defaultLevels = new HashSet<>();
        for (LogLevel l : LEVELS) if (l != LogLevel.UNKNOWN) defaultLevels.add(l.name());
        return new HashSet<>(f.levels()).equals(defaultLevels) && f.excludedLoggers().isEmpty()
                && f.excludedFields().values().stream().allMatch(List::isEmpty)
                && f.from().isBlank() && f.to().isBlank() && f.text().isEmpty() && f.hiddenSources().isEmpty();
    }

    /** Sets the filters a session saved. Call before the tab loads (it also works after). */
    void restore(com.droidenx.clouseau.core.Session.Filters f) {
        Set<String> levels = new HashSet<>(f.levels());
        levelButtons.forEach((level, btn) -> btn.setSelected(levels.contains(level.name())));
        Set<String> known = new HashSet<>(allLoggers);
        for (String logger : f.excludedLoggers()) {
            if (known.contains(logger)) excludedLoggers.add(logger);
            else pendingLoggers.add(logger);
        }
        updateLoggerButtonLabel();
        f.excludedFields().forEach((k, v) -> {
            pendingFieldValues.put(k, new LinkedHashSet<>(v));
            excludedFieldValues.put(k, new LinkedHashSet<>(v));
        });
        updateFieldsButtonLabel();
        if (allSources.size() > 1) {
            f.hiddenSources().stream().filter(i -> i < allSources.size()).forEach(excludedSources::add);
            updateSourcesButtonLabel();
        } else {
            pendingSources.addAll(f.hiddenSources());
        }
        fromField.setText(f.from());
        toField.setText(f.to());
        searchField.setText(f.text());
        onChanged.run();
    }

    /** Pending loggers that have now appeared become ordinary exclusions. */
    private void adoptPendingLoggers(Set<String> known) {
        if (pendingLoggers.isEmpty()) return;
        for (var it = pendingLoggers.iterator(); it.hasNext(); ) {
            String logger = it.next();
            if (known.contains(logger)) {
                excludedLoggers.add(logger);
                it.remove();
            }
        }
    }

    /**
     * Incrementally merges loggers from a streamed batch into the logger picker.
     * Only rebuilds the sorted list when new loggers are found; only sets
     * {@code referenceDate} once, from the first entry with a timestamp.
     */
    void addBatch(List<LogEntry> entries) {
        if (!referenceDateSet) {
            entries.stream()
                    .filter(e -> e.timestamp() != null)
                    .findFirst()
                    .ifPresent(e -> {
                        referenceDate = e.timestamp().atZone(AppPrefs.getDisplayTimezone()).toLocalDate();
                        referenceDateSet = true;
                    });
        }
        Set<String> known = new HashSet<>(allLoggers);
        int sizeBefore = known.size();
        entries.stream()
                .map(LogEntry::logger)
                .filter(Objects::nonNull)
                .forEach(known::add);
        boolean changed = known.size() > sizeBefore;
        if (changed) {
            allLoggers = known.stream().sorted().toList();
            excludedLoggers.retainAll(known);
            adoptPendingLoggers(known);
            updateLoggerButtonLabel();
        }

        boolean fieldsChanged = false;
        for (LogEntry entry : entries) {
            if (entry.fields() == null || entry.fields().isEmpty()) continue;
            for (Map.Entry<String, String> fe : entry.fields().entrySet()) {
                boolean isNewKey = !allFieldValues.containsKey(fe.getKey());
                TreeSet<String> values = allFieldValues.computeIfAbsent(fe.getKey(), k -> new TreeSet<>());
                if (isNewKey) {
                    values.add(EMPTY_SENTINEL);
                    fieldsChanged = true;
                }
                String v = fe.getValue();
                if (v != null && !v.isBlank()) {
                    if (values.add(v)) fieldsChanged = true;
                } else {
                    if (values.add(EMPTY_SENTINEL)) fieldsChanged = true;
                }
            }
        }
        if (fieldsChanged) {
            fieldsButton.setVisible(true);
            updateFieldsButtonLabel();
        }
    }

    void initFollow(boolean initial, java.util.function.Consumer<Boolean> onToggle) {
        followBtn.setSelected(initial);
        followBtn.addActionListener(e -> onToggle.accept(followBtn.isSelected()));
    }

    void setFollowSelected(boolean selected) {
        followBtn.setSelected(selected);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call after a file is loaded to populate the logger picker list and update the
     * reference date used for time-only timestamp parsing.
     */
    void updateLoggers(List<LogEntry> entries) {
        allLoggers = entries.stream()
            .map(LogEntry::logger)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        excludedLoggers.retainAll(new HashSet<>(allLoggers));
        adoptPendingLoggers(new HashSet<>(allLoggers));

        entries.stream()
            .filter(e -> e.timestamp() != null)
            .findFirst()
            .ifPresent(e -> referenceDate = e.timestamp().atZone(AppPrefs.getDisplayTimezone()).toLocalDate());

        updateLoggerButtonLabel();
    }

    /** Returns the AND of all active filters. */
    EntryFilter buildFilter() {
        EnumSet<LogLevel> allowed = EnumSet.noneOf(LogLevel.class);
        levelButtons.forEach((level, btn) -> { if (btn.isSelected()) allowed.add(level); });
        Set<String> loggers = excludedLoggers;
        if (!pendingLoggers.isEmpty()) {
            loggers = new LinkedHashSet<>(excludedLoggers);
            loggers.addAll(pendingLoggers);
        }
        Set<Integer> sources = excludedSources;
        if (!pendingSources.isEmpty()) {
            sources = new LinkedHashSet<>(excludedSources);
            sources.addAll(pendingSources);
        }
        boolean noLoggers = !loggers.isEmpty() && !allLoggers.isEmpty() && loggers.containsAll(allLoggers);
        return new CombinedFilter(allowed, loggers, noLoggers, excludedFieldValues,
                parseTimestamp(fromField.getText().trim()), parseTimestamp(toField.getText().trim()),
                searchField.getText(), sources);
    }

    /** Resets all filters to their default (pass-through) state. */
    void clear() {
        levelButtons.forEach((level, btn) -> btn.setSelected(level != LogLevel.UNKNOWN));
        excludedLoggers.clear();
        pendingLoggers.clear();
        pendingFieldValues.clear();
        pendingSources.clear();
        updateLoggerButtonLabel();
        excludedFieldValues.values().forEach(Set::clear);
        updateFieldsButtonLabel();
        excludedSources.clear();
        updateSourcesButtonLabel();
        fromField.setText("");
        toField.setText("");
        searchField.setText("");
        onChanged.run();
    }

    private void soloLevel(LogLevel target, boolean restore) {
        levelButtons.forEach((lvl, btn) -> {
            btn.setSelected(restore ? lvl != LogLevel.UNKNOWN : lvl == target);   // item listener recolors
        });
        onChanged.run();
    }

    // ── Logger picker popup ───────────────────────────────────────────────────

    private void showLoggerPopup(JButton invoker) {
        JPopupMenu popup = new JPopupMenu();
        JPanel content = new JPanel(new MigLayout("fill, insets 6", "[grow]", "[grow][]"));
        content.setPreferredSize(new Dimension(300, 350));

        DefaultMutableTreeNode root = buildLoggerTree();
        JTree tree = new JTree(root);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setToggleClickCount(0); // expand/collapse only via handle; click = checkbox toggle
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);

        JCheckBox rendererCb = new JCheckBox();
        rendererCb.setOpaque(false);
        tree.setCellRenderer((TreeCellRenderer) (t, value, selected, expanded, leaf, row, hasFocus) -> {
            if (!(((DefaultMutableTreeNode) value).getUserObject() instanceof LoggerNode ln))
                return new JLabel();
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            CheckState state = getCheckState(node);
            rendererCb.setText(ln.segment());
            rendererCb.setSelected(state != CheckState.UNCHECKED);
            rendererCb.putClientProperty("JCheckBox.indeterminate", state == CheckState.INDETERMINATE);
            Color[] depthColors = ClouseauColors.loggerDepthColors();
            int depth = (node.getLevel() - 1) % depthColors.length;
            rendererCb.setForeground(depthColors[depth]);
            return rendererCb;
        });

        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (!(node.getUserObject() instanceof LoggerNode)) return;
                boolean nowChecked = getCheckState(node) != CheckState.CHECKED;
                setNodeChecked(node, nowChecked);
                tree.repaint();
                updateLoggerButtonLabel();
                onChanged.run();
            }
        });

        content.add(new JScrollPane(tree), "grow, wrap");

        JButton allBtn   = new JButton(Messages.get("filter.loggers.select.all"));
        JButton noneBtn  = new JButton(Messages.get("filter.loggers.select.none"));
        JButton closeBtn = new JButton(Messages.get("filter.loggers.close"));
        allBtn.addActionListener(e -> {
            excludedLoggers.clear();
            tree.repaint();
            updateLoggerButtonLabel();
            onChanged.run();
        });
        noneBtn.addActionListener(e -> {
            excludedLoggers.addAll(allLoggers);
            tree.repaint();
            updateLoggerButtonLabel();
            onChanged.run();
        });
        closeBtn.addActionListener(e -> popup.setVisible(false));
        JPanel btnRow = new JPanel(new MigLayout("insets 0", "[]push[][]"));
        btnRow.add(allBtn);
        btnRow.add(noneBtn);
        btnRow.add(closeBtn);
        content.add(btnRow, "growx");

        popup.add(content);
        popup.show(invoker, 0, invoker.getHeight());
    }

    private DefaultMutableTreeNode buildLoggerTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("ROOT");
        for (String logger : allLoggers) {
            String[] parts = logger.split("\\.", -1);
            DefaultMutableTreeNode current = root;
            int offset = 0;
            for (String part : parts) {
                String fullPath = logger.substring(0, offset + part.length());
                DefaultMutableTreeNode child = findTreeChild(current, part);
                if (child == null) {
                    child = new DefaultMutableTreeNode(new LoggerNode(part, fullPath));
                    current.add(child);
                }
                current = child;
                offset += part.length() + 1; // +1 for the dot separator
            }
        }
        return root;
    }

    private static DefaultMutableTreeNode findTreeChild(DefaultMutableTreeNode parent, String segment) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (child.getUserObject() instanceof LoggerNode ln && ln.segment().equals(segment))
                return child;
        }
        return null;
    }

    private CheckState getCheckState(DefaultMutableTreeNode node) {
        if (node.isLeaf()) {
            String path = node.getUserObject() instanceof LoggerNode ln ? ln.fullPath() : "";
            return excludedLoggers.contains(path) ? CheckState.UNCHECKED : CheckState.CHECKED;
        }
        boolean anyChecked = false, anyUnchecked = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            CheckState cs = getCheckState((DefaultMutableTreeNode) node.getChildAt(i));
            if (cs != CheckState.UNCHECKED) anyChecked = true;
            if (cs != CheckState.CHECKED)   anyUnchecked = true;
            if (anyChecked && anyUnchecked) return CheckState.INDETERMINATE;
        }
        return anyChecked ? CheckState.CHECKED : CheckState.UNCHECKED;
    }

    private void setNodeChecked(DefaultMutableTreeNode node, boolean checked) {
        if (node.isLeaf()) {
            if (node.getUserObject() instanceof LoggerNode ln) {
                if (checked) excludedLoggers.remove(ln.fullPath());
                else         excludedLoggers.add(ln.fullPath());
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++)
            setNodeChecked((DefaultMutableTreeNode) node.getChildAt(i), checked);
    }

    private record LoggerNode(String segment, String fullPath) {}
    private enum CheckState { CHECKED, UNCHECKED, INDETERMINATE }

    // ── Field value picker popup ──────────────────────────────────────────────

    private void showFieldsPopup(JButton invoker) {
        JPopupMenu popup = new JPopupMenu();
        JPanel content = new JPanel(new MigLayout("fill, insets 6", "[grow]", "[grow][]"));
        content.setPreferredSize(new Dimension(280, 320));

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        List<JCheckBox> allBoxes = new ArrayList<>();

        for (Map.Entry<String, TreeSet<String>> fieldEntry : allFieldValues.entrySet()) {
            String fieldKey = fieldEntry.getKey();
            Set<String> excluded = excludedFieldValues.computeIfAbsent(fieldKey, k -> new LinkedHashSet<>());

            JLabel sectionLabel = new JLabel(fieldKey);
            sectionLabel.setForeground(ClouseauColors.dimForeground());
            sectionLabel.setFont(sectionLabel.getFont().deriveFont(Font.BOLD, 11f));
            sectionLabel.setBorder(BorderFactory.createEmptyBorder(6, 4, 2, 4));
            sectionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            listPanel.add(sectionLabel);

            for (String value : fieldEntry.getValue()) {
                JCheckBox cb = new JCheckBox(value, !excluded.contains(value));
                cb.setFont(cb.getFont().deriveFont(12f));
                cb.setAlignmentX(Component.LEFT_ALIGNMENT);
                cb.addActionListener(e -> {
                    if (cb.isSelected()) excluded.remove(value);
                    else                 excluded.add(value);
                    pendingFieldValues.clear();   // the user has taken over field filters
                    updateFieldsButtonLabel();
                    onChanged.run();
                });
                listPanel.add(cb);
                allBoxes.add(cb);
            }
        }

        JScrollPane scroll = new JScrollPane(listPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(UIManager.getBorder("PopupMenu.border"));
        content.add(scroll, "grow, wrap");

        JButton allBtn   = new JButton(Messages.get("filter.loggers.select.all"));
        JButton noneBtn  = new JButton(Messages.get("filter.loggers.select.none"));
        JButton closeBtn = new JButton(Messages.get("filter.loggers.close"));

        allBtn.addActionListener(e -> {
            excludedFieldValues.values().forEach(Set::clear);
            allBoxes.forEach(cb -> cb.setSelected(true));
            pendingFieldValues.clear();   // the user has taken over field filters
            updateFieldsButtonLabel();
            onChanged.run();
        });
        noneBtn.addActionListener(e -> {
            allFieldValues.forEach((k, vals) ->
                    excludedFieldValues.computeIfAbsent(k, key -> new LinkedHashSet<>()).addAll(vals));
            allBoxes.forEach(cb -> cb.setSelected(false));
            pendingFieldValues.clear();   // the user has taken over field filters
            updateFieldsButtonLabel();
            onChanged.run();
        });
        closeBtn.addActionListener(e -> popup.setVisible(false));

        JPanel btnRow = new JPanel(new MigLayout("insets 0", "[]push[][]"));
        btnRow.add(allBtn);
        btnRow.add(noneBtn);
        btnRow.add(closeBtn);
        content.add(btnRow, "growx");

        popup.add(content);
        popup.show(invoker, 0, invoker.getHeight());
    }

    // ── Source picker popup ───────────────────────────────────────────────────

    private void showSourcesPopup(JButton invoker) {
        JPopupMenu popup = new JPopupMenu();
        JPanel content = new JPanel(new MigLayout("fill, insets 6", "[grow]", "[grow][]"));

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        List<JCheckBox> boxes = new ArrayList<>();
        for (int i = 0; i < allSources.size(); i++) {
            int source = i;
            JCheckBox cb = new JCheckBox(allSources.get(i), !excludedSources.contains(i));
            cb.setFont(cb.getFont().deriveFont(12f));
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            cb.addActionListener(e -> {
                if (cb.isSelected()) excludedSources.remove(source);
                else                 excludedSources.add(source);
                updateSourcesButtonLabel();
                onChanged.run();
            });
            listPanel.add(cb);
            boxes.add(cb);
        }
        JScrollPane scroll = new JScrollPane(listPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(UIManager.getBorder("PopupMenu.border"));
        content.add(scroll, "grow, wrap, hmax 320");

        JButton allBtn   = new JButton(Messages.get("filter.loggers.select.all"));
        JButton closeBtn = new JButton(Messages.get("filter.loggers.close"));
        allBtn.addActionListener(e -> {
            excludedSources.clear();
            boxes.forEach(cb -> cb.setSelected(true));
            updateSourcesButtonLabel();
            onChanged.run();
        });
        closeBtn.addActionListener(e -> popup.setVisible(false));
        JPanel btnRow = new JPanel(new MigLayout("insets 0", "[]push[]"));
        btnRow.add(allBtn);
        btnRow.add(closeBtn);
        content.add(btnRow, "growx");

        popup.add(content);
        popup.show(invoker, 0, invoker.getHeight());
    }

    private void updateSourcesButtonLabel() {
        int total = allSources.size();
        sourcesButton.setText(excludedSources.isEmpty() || total == 0
            ? Messages.get("filter.sources.all")
            : Messages.get("filter.sources.partial").formatted(total - excludedSources.size(), total));
    }

    // Logger-tree depth colors read from UIManager via ClouseauColors at render time.

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Instant parseTimestamp(String text) {
        if (text.isEmpty()) return null;
        for (DateTimeFormatter fmt : TS_FORMATS) {
            try { return LocalDateTime.parse(text, fmt).atZone(AppPrefs.getDisplayTimezone()).toInstant(); }
            catch (DateTimeParseException ignored) {}
            try { return LocalDate.parse(text, fmt).atStartOfDay(AppPrefs.getDisplayTimezone()).toInstant(); }
            catch (DateTimeParseException ignored) {}
            try { return LocalTime.parse(text, fmt).atDate(referenceDate).atZone(AppPrefs.getDisplayTimezone()).toInstant(); }
            catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    private void validateTimestamp(JTextField field) {
        String text = field.getText().trim();
        field.putClientProperty("JTextField.outline",
            !text.isEmpty() && parseTimestamp(text) == null ? "error" : null);
    }

    private void updateLoggerButtonLabel() {
        int total   = allLoggers.size();
        long active = allLoggers.stream().filter(l -> !excludedLoggers.contains(l)).count();
        loggerButton.setText(excludedLoggers.isEmpty() || total == 0
            ? Messages.get("filter.loggers.all")
            : Messages.get("filter.loggers.partial").formatted(active, total));
    }

    private void updateFieldsButtonLabel() {
        boolean anyExcluded = excludedFieldValues.values().stream().anyMatch(s -> !s.isEmpty());
        fieldsButton.setText(anyExcluded
            ? Messages.get("filter.fields.partial")
            : Messages.get("filter.fields.all"));
    }

    static void applyToggleStyle(JToggleButton btn) {
        java.util.function.Supplier<Color> fg = () -> btn.isSelected()
                ? UIManager.getColor("ToggleButton.foreground") : ClouseauColors.offColor();
        ClouseauColors.themedForeground(btn, fg);
        btn.addItemListener(e -> btn.setForeground(fg.get()));
    }

    /** The toggle for {@code level}; for tests. */
    JToggleButton levelButton(LogLevel level) {
        return levelButtons.get(level);
    }

    private void scheduleChange() {
        if (debounceTimer != null) debounceTimer.stop();
        debounceTimer = new javax.swing.Timer(250, e -> onChanged.run());
        debounceTimer.setRepeats(false);
        debounceTimer.start();
    }

    private static DocumentListener docListener(Runnable action) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { action.run(); }
            public void removeUpdate(DocumentEvent e)  { action.run(); }
            public void changedUpdate(DocumentEvent e) {}
        };
    }
}
