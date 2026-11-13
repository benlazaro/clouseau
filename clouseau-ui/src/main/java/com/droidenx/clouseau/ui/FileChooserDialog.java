package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.FilePattern;
import com.droidenx.clouseau.core.LogSniffer;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.ui.theme.ClouseauFonts;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicFileChooserUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * File → Open…. Favorites and recent folders on the
 * left, the files in the middle, and on the right what the selection will become: each
 * file's verdict (which parser reads it, or why it opens as plain text), how several open
 * (one merged view or separate tabs), what reads them (parser, source time zone) and a
 * preview of a file as the table will show it.
 * <p>
 * A pattern typed in File Name ({@code app.log*}) selects every file it matches.
 */
@Slf4j
final class FileChooserDialog extends JDialog {

    /** {@code multiOpen}: how several logs open, decided here; null for one file. */
    record Result(List<Path> files, Optional<LogParser> parser, int parserIndex, ZoneId sourceTimezone,
                  AppPrefs.MultiOpen multiOpen) {}

    /** What opening one file will give. */
    private record Verdict(Path file, String text, Color color, boolean log, LogParser parser,
                           LogSniffer.Sample sample) {}

    /** Most files checked at once; more are opened, just not checked ahead. */
    private static final int CHECK_LIMIT = 100;
    private static final int RIGHT_WIDTH = 360;
    private static final DateTimeFormatter LIST_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final List<LogParser> parsers;
    private final JFileChooser chooser = new JFileChooser();
    private int selectedParserIndex;
    private ZoneId selectedSourceTimezone = ZoneId.systemDefault();
    /** The user picked a zone for this selection, so moving to another file keeps it. */
    private boolean zoneChosen;
    private boolean settingZone;
    private JTextField nameField;
    private JButton openBtn;
    private Result result;

    // The selection, as last checked
    private List<Path> files = List.of();
    private boolean fromPattern;
    private final DefaultListModel<Object> verdicts = new DefaultListModel<>();
    private Path previewing;

    // Right panel
    /** One line, cut short with … rather than wrapped, so the section's height never changes. */
    private final JLabel summary = new JLabel(" ");
    private final JList<Object> fileList = new JList<>(verdicts);
    private final JScrollPane fileScroll = ClouseauColors.noFocusFrame(new JScrollPane(fileList));
    private final JRadioButton asMerged = new JRadioButton(Messages.get("filechooser.as.merged"));
    private final JRadioButton asTabs = new JRadioButton(Messages.get("filechooser.as.tabs"));
    private final JComponent openAsRow;
    private final JComboBox<String> parserCombo;
    private final ZoneCombo tzCombo;
    private final JTextArea readNote = wrapText("", true);
    private final JComponent previewSection = SettingsDialog.sectionLabel(Messages.get("filechooser.preview.label"));
    private final JLabel previewTitle = (JLabel) previewSection.getComponent(0);
    private final JTextPane preview = new JTextPane();

    // Left panel
    private final DefaultListModel<Object> places = new DefaultListModel<>();
    private final PlacesList placesList = new PlacesList();

    private JSplitPane leftSplit, mainSplit;
    private SwingWorker<List<Verdict>, Verdict> checker;
    private final javax.swing.Timer debounce;

    FileChooserDialog(Frame owner, List<LogParser> parsers, int initialParserIndex) {
        super(owner, Messages.get("filechooser.title"), true);
        this.parsers = parsers;
        this.selectedParserIndex = initialParserIndex;
        parserCombo = new JComboBox<>(Stream.concat(Stream.of(Messages.get("filechooser.parser.autodetect")),
                parsers.stream().map(LogParser::getName)).toArray(String[]::new));
        parserCombo.setSelectedIndex(Math.min(initialParserIndex, parserCombo.getItemCount() - 1));
        tzCombo = SettingsDialog.buildTimezoneCombo(selectedSourceTimezone);
        openAsRow = buildOpenAs();
        debounce = new javax.swing.Timer(150, e -> checkSelection());
        debounce.setRepeats(false);

        setUpChooser();
        leftSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildPlaces(), chooser);
        leftSplit.setBorder(null);
        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, buildDetails());
        mainSplit.setResizeWeight(1.0);           // the files take a resize; the details keep their width
        mainSplit.setBorder(null);

        JPanel content = new JPanel(new BorderLayout());
        content.add(mainSplit, BorderLayout.CENTER);
        content.add(buildButtonBar(), BorderLayout.SOUTH);
        setContentPane(content);

        // Where it was left last time, or a size that fits the three panes.
        int[] layout = AppPrefs.getOpenDialogLayout();
        setSize(layout != null ? layout[0] : 1180, layout != null ? layout[1] : 680);
        setMinimumSize(new Dimension(900, 520));
        leftSplit.setDividerLocation(layout != null ? layout[2] : 200);
        SwingUtilities.invokeLater(() -> mainSplit.setDividerLocation(layout != null && layout[3] > 0
                ? layout[3] : mainSplit.getWidth() - RIGHT_WIDTH - mainSplit.getDividerSize()));
        setLocationRelativeTo(owner);
        checkSelection();
        // The file chooser makes its own (hidden) approve button the default when it joins the
        // window; Open is the default, so it's amber and Enter outside the list opens.
        SwingUtilities.invokeLater(() -> getRootPane().setDefaultButton(openBtn));
    }

    /** Shows the dialog modally and returns the result, or empty if cancelled. */
    Optional<Result> showDialog() {
        setVisible(true); // blocks until disposed
        return Optional.ofNullable(result);
    }

    @Override public void dispose() {
        AppPrefs.setOpenDialogLayout(getWidth(), getHeight(), leftSplit.getDividerLocation(), mainSplit.getDividerLocation());
        if (checker != null) checker.cancel(true);
        super.dispose();
    }

    // ── The chooser ──────────────────────────────────────────────────────────

    private void setUpChooser() {
        chooser.setFileFilter(new FileNameExtensionFilter(
                Messages.get("filechooser.filter.desc"), "log", "txt", "out", "gz", "zip"));
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setMultiSelectionEnabled(true);
        chooser.setControlButtonsAreShown(false);
        ClouseauColors.noFocusFrames(chooser);         // its list and details views: no amber frame
        File lastDir = AppPrefs.getLastOpenDir();
        if (lastDir != null) chooser.setCurrentDirectory(lastDir);
        Action detailsView = chooser.getActionMap().get("viewTypeDetails");
        if (detailsView != null) detailsView.actionPerformed(new ActionEvent(chooser, ActionEvent.ACTION_PERFORMED, null));
        useAppDates(chooser);

        // Approve/cancel fired by double-click, Enter, or our own buttons
        chooser.addActionListener(e -> {
            if (JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand())) doApprove();
            else if (JFileChooser.CANCEL_SELECTION.equals(e.getActionCommand())) dispose();
        });
        chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY, e -> debounce.restart());
        chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILES_CHANGED_PROPERTY, e -> debounce.restart());
        chooser.addPropertyChangeListener(JFileChooser.DIRECTORY_CHANGED_PROPERTY, e -> {
            chooser.setSelectedFile(null);
            if (chooser.getUI() instanceof BasicFileChooserUI basicUI) basicUI.setFileName("");
            useAppDates(chooser);
            debounce.restart();
            rebuildPlaces();
        });
        hookPatternTyping();
    }

    /**
     * The file list's dates in the app's format, not the system's short one: each column's
     * renderer is wrapped so a date value shows as yyyy-MM-dd HH:mm. The list rebuilds its
     * columns now and then, so this runs again on a folder change.
     */
    private static void useAppDates(Container c) {
        SwingUtilities.invokeLater(() -> {
            JTable table = find(c, JTable.class);
            if (table == null) return;
            for (int i = 0; i < table.getColumnCount(); i++) {
                javax.swing.table.TableColumn col = table.getColumnModel().getColumn(i);
                javax.swing.table.TableCellRenderer base = col.getCellRenderer() != null
                        ? col.getCellRenderer() : table.getDefaultRenderer(Object.class);
                if (base instanceof AppDates) continue;
                col.setCellRenderer(new AppDates(base));
            }
            table.repaint();
        });
    }

    private record AppDates(javax.swing.table.TableCellRenderer base) implements javax.swing.table.TableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
            Component comp = base.getTableCellRendererComponent(t, v, s, f, r, c);
            java.util.Date d = v instanceof java.util.Date date ? date
                    : v instanceof Long ms && t.getColumnName(c).toLowerCase(Locale.ROOT).contains("modif") ? new java.util.Date(ms) : null;
            if (d != null && comp instanceof JLabel l) l.setText(LIST_DATE.format(d.toInstant().atZone(ZoneId.systemDefault())));
            return comp;
        }
    }

    private static <T extends Component> T find(Container c, Class<T> type) {
        for (Component child : c.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container sub) {
                T t = find(sub, type);
                if (t != null) return t;
            }
        }
        return null;
    }

    /**
     * The look and feel turns a typed {@code app.log*} into a file filter and never approves,
     * so Enter and Open take a pattern in the file name field straight to {@link #doApprove()};
     * as it's typed, the files it matches are checked like a selection.
     */
    private void hookPatternTyping() {
        nameField = findNameField(chooser);
        if (nameField == null) return;
        nameField.putClientProperty("JTextField.placeholderText", Messages.get("filechooser.name.placeholder"));
        Action accept = nameField.getActionMap().get(JTextField.notifyAction);
        nameField.getActionMap().put(JTextField.notifyAction, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (typedPattern() != null) doApprove();
                else if (accept != null) accept.actionPerformed(e);
            }
            @Override public boolean isEnabled() {
                return typedPattern() != null || (accept != null && accept.isEnabled());
            }
        });
        nameField.getDocument().addDocumentListener(docListener(() -> {
            if (typedPattern() != null || fromPattern) debounce.restart();
        }));
    }

    private static JTextField findNameField(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JComboBox<?>) continue;
            if (child instanceof JTextField f) return f;
            if (child instanceof Container sub) {
                JTextField f = findNameField(sub);
                if (f != null) return f;
            }
        }
        return null;
    }

    /** The file name field's text as a file when its name is a pattern ({@code app.log*}), else null. */
    private File typedPattern() {
        if (nameField == null) return null;
        String text = nameField.getText().trim();
        if (text.isEmpty() || text.startsWith("\"")) return null;
        File f = new File(text);
        if (!f.isAbsolute()) f = new File(chooser.getCurrentDirectory(), text);
        return FilePattern.isPattern(f.getName()) ? f : null;
    }

    /** What Open would open now: the pattern's matches, or the selected files. */
    private List<Path> currentFiles() {
        File typed = typedPattern();
        fromPattern = typed != null;
        if (typed != null) {
            File dir = typed.getParentFile() != null ? typed.getParentFile() : chooser.getCurrentDirectory();
            try {
                return FilePattern.expand(dir.toPath(), typed.getName());
            } catch (IOException | RuntimeException ex) {
                log.warn("Could not expand {} in {}", typed.getName(), dir, ex);
                return List.of();
            }
        }
        File[] selected = chooser.getSelectedFiles();
        List<Path> list = new ArrayList<>();
        if (selected != null) for (File f : selected) if (f.isFile()) list.add(f.toPath());
        if (list.isEmpty() && chooser.getSelectedFile() != null && chooser.getSelectedFile().isFile())
            list.add(chooser.getSelectedFile().toPath());
        return list;
    }

    private void doApprove() {
        List<Path> chosen = currentFiles();
        if (chosen.isEmpty()) {
            if (fromPattern) {
                JOptionPane.showMessageDialog(this,
                        MessageFormat.format(Messages.get("filechooser.pattern.none"), typedPattern().getName()),
                        getTitle(), JOptionPane.INFORMATION_MESSAGE);
            }
            return;
        }
        Optional<LogParser> parser = selectedParserIndex == 0
                ? Optional.empty() : Optional.of(parsers.get(selectedParserIndex - 1));
        AppPrefs.MultiOpen how = logCount(chosen) >= 2
                ? (asMerged.isSelected() ? AppPrefs.MultiOpen.MERGE : AppPrefs.MultiOpen.SEPARATE) : null;
        result = new Result(chosen, parser, selectedParserIndex, selectedSourceTimezone, how);
        AppPrefs.setLastOpenDir(chooser.getCurrentDirectory());
        dispose();
    }

    private static boolean isZip(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static long logCount(List<Path> files) {
        return files.stream().filter(p -> !isZip(p)).count();
    }

    // ── Checking the selection ───────────────────────────────────────────────

    /**
     * The selection changed: lists the files, checks each in the background (which parser
     * reads it, or why it would open as plain text), and previews the first.
     */
    private void checkSelection() {
        if (checker != null) checker.cancel(true);
        files = currentFiles();
        followSavedZone();
        boolean several = files.size() > 1;
        long logs = logCount(files);

        verdicts.clear();
        files.stream().limit(CHECK_LIMIT).forEach(verdicts::addElement);
        // The section keeps its size whatever is selected: the list always shows four rows,
        // and Open as stays, enabled once two logs are selected.
        for (Component c : openAsRow.getComponents()) c.setEnabled(logs >= 2);
        openBtn.setEnabled(!files.isEmpty());
        updateReadNote();

        if (files.isEmpty()) {
            setSummary(Messages.get(fromPattern ? "filechooser.pattern.nomatch" : "filechooser.summary.none"),
                    fromPattern ? ClouseauColors.statusWarn() : ClouseauColors.dimForeground());
            showPreview(null);
            revalidate();
            return;
        }
        setSummary(several
                ? MessageFormat.format(Messages.get(fromPattern ? "filechooser.summary.pattern" : "filechooser.summary.several"),
                        files.size(), typedPattern() != null ? typedPattern().getName() : "")
                : Messages.get("filechooser.summary.checking"), ClouseauColors.dimForeground());
        if (!files.isEmpty()) fileList.setSelectedIndex(0);
        revalidate();

        Optional<LogParser> chosen = selectedParserIndex == 0
                ? Optional.empty() : Optional.of(parsers.get(selectedParserIndex - 1));
        List<Path> toCheck = files.stream().limit(CHECK_LIMIT).toList();
        boolean mergedView = asMerged.isSelected() && logs >= 2;
        checker = new SwingWorker<>() {
            @Override protected List<Verdict> doInBackground() {
                for (Path p : toCheck) {
                    if (isCancelled()) return null;
                    publish(verdict(p, chosen, mergedView));
                }
                return null;
            }

            @Override protected void process(List<Verdict> done) {
                if (isCancelled()) return;
                Path wanted = wantedPreview();
                for (Verdict v : done) {
                    int i = verdicts.indexOf(v.file());
                    if (i >= 0) verdicts.set(i, v);
                    if (v.file().equals(wanted)) showPreview(v);
                }
                updateSummary();
            }
        };
        showPreview(null);
        checker.execute();
    }

    /** The file the preview should show: the only one, or the one picked in the list. */
    private Path wantedPreview() {
        if (files.isEmpty()) return null;
        if (files.size() == 1) return files.get(0);
        Object v = fileList.getSelectedValue();
        return v instanceof Verdict verdict ? verdict.file() : v instanceof Path p ? p : files.get(0);
    }

    private Verdict verdictOf(Path p) {
        for (int i = 0; i < verdicts.size(); i++) if (verdicts.get(i) instanceof Verdict v && v.file().equals(p)) return v;
        return null;
    }

    /** What opening {@code file} will give, read off the EDT. */
    private Verdict verdict(Path file, Optional<LogParser> chosen, boolean mergedView) {
        if (isZip(file)) {
            return new Verdict(file, Messages.get("filechooser.verdict.zip"), ClouseauColors.dimForeground(), false, null, null);
        }
        LogSniffer.Sample sample;
        try {
            sample = LogSniffer.read(file, null);
        } catch (IOException e) {
            log.warn("Preview read failed for {}", file, e);
            return new Verdict(file, Messages.get("filechooser.verdict.unreadable"), ClouseauColors.statusError(), false, null, null);
        }
        if (sample.empty()) {
            return new Verdict(file, Messages.get("filechooser.verdict.empty"), ClouseauColors.dimForeground(), true, null, sample);
        }
        if (sample.binary()) {
            return new Verdict(file, Messages.get("filechooser.verdict.binary"), ClouseauColors.statusWarn(), false, null, sample);
        }
        Optional<LogParser> matched = LogSniffer.detect(sample.lines(), chosen, parsers);
        if (matched.isEmpty()) {
            String text = chosen.map(p -> MessageFormat.format(Messages.get("filechooser.verdict.chosen.plain"), p.getName()))
                    .orElse(Messages.get(mergedView ? "filechooser.verdict.plain.merged" : "filechooser.verdict.plain"));
            return new Verdict(file, text, ClouseauColors.statusWarn(), false, null, sample);
        }
        LogParser p = matched.get();
        if (mergedView && !hasTimestamps(p, sample)) {
            return new Verdict(file, MessageFormat.format(Messages.get("filechooser.verdict.no.time"), p.getName()),
                    ClouseauColors.statusWarn(), true, p, sample);
        }
        return new Verdict(file, MessageFormat.format(Messages.get("filechooser.verdict.parser"), p.getName()),
                ClouseauColors.statusOk(), true, p, sample);
    }

    private static boolean hasTimestamps(LogParser p, LogSniffer.Sample sample) {
        for (String line : sample.lines()) {
            if (line.isBlank() || !p.canParse(line)) continue;
            try {
                if (p.parse(line).timestamp() != null) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }

    /** The line above the list: one file's verdict, or how many of several are fine. */
    private void updateSummary() {
        if (files.isEmpty()) return;
        if (files.size() == 1) {
            if (verdicts.get(0) instanceof Verdict v) setSummary(v.text(), v.color());
            return;
        }
        int checked = 0, problems = 0;
        for (int i = 0; i < verdicts.size(); i++) {
            if (verdicts.get(i) instanceof Verdict v) {
                checked++;
                if (v.color().equals(ClouseauColors.statusWarn()) || v.color().equals(ClouseauColors.statusError())) problems++;
            }
        }
        String head = MessageFormat.format(Messages.get(fromPattern ? "filechooser.summary.pattern" : "filechooser.summary.several"),
                files.size(), typedPattern() != null ? typedPattern().getName() : "");
        if (checked < verdicts.size()) { setSummary(head, ClouseauColors.dimForeground()); return; }
        setSummary(head + " " + (problems == 0 ? Messages.get("filechooser.summary.all.ok")
                        : MessageFormat.format(Messages.get("filechooser.summary.problems"), problems)),
                problems == 0 ? ClouseauColors.statusOk() : ClouseauColors.statusWarn());
    }

    private void setSummary(String text, Color color) {
        summary.setText(text);
        summary.setToolTipText(text);
        summary.setForeground(color);
    }

    // ── Source time zone ─────────────────────────────────────────────────────

    /**
     * One file with a zone saved for it: that zone. Otherwise the zone the user picked for
     * this selection, or the system's. (It used to keep the last file's saved zone.)
     */
    private void followSavedZone() {
        ZoneId saved = files.size() == 1 ? AppPrefs.getSourceTimezone(files.get(0)) : null;
        if (saved != null) setZone(saved);
        else if (!zoneChosen) setZone(ZoneId.systemDefault());
    }

    private void setZone(ZoneId zone) {
        selectedSourceTimezone = zone;
        settingZone = true;
        tzCombo.setZone(zone);
        settingZone = false;
    }

    private void updateReadNote() {
        long logs = logCount(files);
        ZoneId saved = files.size() == 1 ? AppPrefs.getSourceTimezone(files.get(0)) : null;
        String note;
        if (logs >= 2) note = MessageFormat.format(Messages.get(asMerged.isSelected()
                ? "filechooser.read.all.merged" : "filechooser.read.all"), logs);
        else if (saved != null) note = Messages.get("filechooser.read.saved");
        else note = Messages.get("filechooser.read.one");
        readNote.setText(note);
    }

    // ── Preview ──────────────────────────────────────────────────────────────

    /**
     * The first lines of a file as the table will show them: time, level in its color,
     * message; lines the parser doesn't read in the dim text they'll have in the tab.
     */
    private void showPreview(Verdict v) {
        StyledDocument doc = preview.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            previewing = v != null ? v.file() : null;
            previewTitle.setText(v == null ? Messages.get("filechooser.preview.label")
                    : MessageFormat.format(Messages.get("filechooser.preview.of"), v.file().getFileName()));
            if (v == null || v.sample() == null || v.sample().empty() || v.sample().binary()) {
                add(doc, v == null ? Messages.get("filechooser.preview.empty")
                        : v.sample() == null ? v.text()
                        : v.sample().binary() ? Messages.get("filechooser.preview.binary")
                        : Messages.get("filechooser.preview.empty.file"), ClouseauColors.dimForeground(), false);
                preview.setCaretPosition(0);
                return;
            }
            DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(AppPrefs.getDisplayTimezone());
            int shown = 0;
            for (String line : v.sample().lines()) {
                if (line.isBlank()) continue;
                if (shown++ >= 40) break;
                LogEntry e = null;
                if (v.parser() != null && v.parser().canParse(line)) {
                    try { e = v.parser().parse(line); } catch (RuntimeException ignored) { }
                }
                if (e == null) {
                    add(doc, line + "\n", ClouseauColors.dimForeground(), false);
                    continue;
                }
                add(doc, (e.timestamp() != null ? time.format(e.timestamp()) : "            ") + "  ",
                        ClouseauColors.dimForeground(), false);
                add(doc, String.format("%-5s", e.level().name()) + "  ", ClouseauColors.levelColor(e.level()), true);
                add(doc, (e.message() != null ? e.message() : "") + "\n", ClouseauColors.levelColor(e.level()), false);
            }
            preview.setCaretPosition(0);
        } catch (javax.swing.text.BadLocationException ignored) { }
    }

    private static void add(StyledDocument doc, String text, Color color, boolean bold)
            throws javax.swing.text.BadLocationException {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, color);
        StyleConstants.setBold(a, bold);
        doc.insertString(doc.getLength(), text, a);
    }

    // ── The details pane ─────────────────────────────────────────────────────

    /** Files (what the selection will become), Read with (parser, time zone), Preview. */
    private JComponent buildDetails() {
        fileList.setCellRenderer(new VerdictRenderer());
        fileList.setFixedCellHeight(38);               // a name and its verdict, two lines
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            if (fileList.getSelectedValue() instanceof Verdict verdict) showPreview(verdict);
        });

        parserCombo.addActionListener(e -> {
            selectedParserIndex = parserCombo.getSelectedIndex();
            debounce.restart();
        });
        tzCombo.addActionListener(e -> {
            if (settingZone) return;
            selectedSourceTimezone = tzCombo.selectedZone();
            zoneChosen = true;
        });

        preview.setEditable(false);
        preview.setFont(ClouseauFonts.mono(Font.PLAIN, 11));
        preview.setBackground(ClouseauColors.tableBackground());
        preview.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        // No wrapping: lines keep their shape and scroll sideways, as in the table.
        JPanel noWrap = new JPanel(new BorderLayout());
        noWrap.add(preview);
        JScrollPane previewScroll = new JScrollPane(noWrap);
        previewScroll.getVerticalScrollBar().setUnitIncrement(16);
        previewScroll.setBorder(UIManager.getBorder("ScrollPane.border"));

        JPanel p = new JPanel(new MigLayout("insets 12 16 12 16, wrap 1, fillx, hidemode 3, gapy 4",
                "[grow, fill, 0::]"));
        p.add(SettingsDialog.sectionLabel(Messages.get("filechooser.section.files")), "growx");
        String in = SettingsDialog.INDENT_NARROW;        // the section's contents, under its title
        p.add(summary, "wmin 0, " + in);
        // Four rows, always: no sideways scrollbar for a long verdict (it's cut short, with
        // the full text as a tooltip), so nothing below moves as the selection changes.
        fileScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        p.add(fileScroll, "wmin 0, h " + (4 * 38 + 4) + "!, " + in);
        p.add(openAsRow, "gapleft 16");
        p.add(SettingsDialog.sectionLabel(Messages.get("filechooser.section.read")), "growx, gaptop 12");
        p.add(new JLabel(Messages.get("filechooser.parser.label")), in);
        p.add(parserCombo, "wmin 0, " + in);
        p.add(new JLabel(Messages.get("filechooser.source.timezone.label")), "gaptop 6, " + in);
        p.add(tzCombo, "wmin 0, " + in);
        p.add(readNote, "wmin 0, h 34!, " + in);      // two lines, whatever the note says
        p.add(previewSection, "growx, gaptop 12");
        p.add(previewScroll, "grow, push, h 80::, " + in);
        p.setMinimumSize(new Dimension(260, 0));
        p.setPreferredSize(new Dimension(RIGHT_WIDTH, 10));
        return p;
    }

    private JComponent buildOpenAs() {
        ButtonGroup group = new ButtonGroup();
        group.add(asMerged);
        group.add(asTabs);
        boolean merge = AppPrefs.getMultiOpen() != AppPrefs.MultiOpen.SEPARATE;
        (merge ? asMerged : asTabs).setSelected(true);
        asMerged.addActionListener(e -> { updateReadNote(); debounce.restart(); });
        asTabs.addActionListener(e -> { updateReadNote(); debounce.restart(); });
        JPanel row = new JPanel(new MigLayout("insets 0, gap 10", "[][][]", "[center]"));
        row.add(new JLabel(Messages.get("filechooser.as.label")));
        row.add(asMerged);
        row.add(asTabs);
        return row;
    }

    /** A file in the list: its name, and under it what it opens as, in its status color. */
    private static final class VerdictRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
            super.getListCellRendererComponent(l, v, i, sel, false);
            Path p = v instanceof Verdict verdict ? verdict.file() : (Path) v;
            String what = v instanceof Verdict verdict ? verdict.text() : Messages.get("filechooser.summary.checking");
            Color c = v instanceof Verdict verdict ? verdict.color() : ClouseauColors.dimForeground();
            String hex = String.format("#%06x", c.getRGB() & 0xFFFFFF);
            setText("<html>" + esc(p.getFileName().toString()) + "<br><font color='" + hex + "' size='-1'>"
                    + esc(what) + "</font></html>");
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            setToolTipText(p.getFileName() + ": " + what);
            return this;
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;");
        }
    }

    // ── Places: favorites and recent folders ─────────────────────────────────

    /** A favorite folder or file. */
    private record Favorite(Path path) {}
    /** A folder recent files came from. */
    private record Recent(Path path) {}

    /**
     * Favorites, then folders of recent files: a click opens the folder (or selects the file).
     * A favorite has a trash can on the row under the mouse; Add This Folder is below.
     */
    private JComponent buildPlaces() {
        placesList.setCellRenderer(new PlaceRenderer());
        placesList.setOpaque(false);
        placesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ToolTipManager.sharedInstance().registerComponent(placesList);
        placesList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            Object v = placesList.getSelectedValue();
            Path p = v instanceof Favorite f ? f.path() : v instanceof Recent r ? r.path() : null;
            if (p == null) return;
            if (Files.isDirectory(p)) chooser.setCurrentDirectory(p.toFile());
            else if (p.getParent() != null && Files.isDirectory(p.getParent())) {
                chooser.setCurrentDirectory(p.getParent().toFile());
                chooser.setSelectedFile(p.toFile());
            }
        });
        placesList.registerKeyboardAction(e -> {
                    if (placesList.getSelectedValue() instanceof Favorite f) removeFavorite(f.path());
                },
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), JComponent.WHEN_FOCUSED);
        rebuildPlaces();

        JScrollPane scroll = new JScrollPane(placesList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);

        JButton add = new JButton(Messages.get("filechooser.favorites.add"));
        add.setToolTipText(Messages.get("filechooser.favorites.add.tooltip"));
        add.addActionListener(e -> {
            File dir = chooser.getCurrentDirectory();
            if (dir == null) return;
            Path p = dir.toPath().toAbsolutePath();
            List<Path> favs = new ArrayList<>(AppPrefs.getFavorites());
            if (!favs.contains(p)) {
                favs.add(p);
                AppPrefs.saveFavorites(favs);
                rebuildPlaces();
            }
        });

        JPanel side = new JPanel(new MigLayout("insets 12 12 12 8, fill, gapy 8", "[grow, fill, 0::]", "[grow, fill][]"));
        side.add(scroll, "wrap");
        side.add(add, "growx 0, left");
        side.setMinimumSize(new Dimension(120, 0));
        return side;
    }

    private void rebuildPlaces() {
        Object sel = placesList.getSelectedValue();
        places.clear();
        places.addElement(Messages.get("filechooser.favorites.label"));
        List<Path> favs = AppPrefs.getFavorites();
        favs.forEach(p -> places.addElement(new Favorite(p)));
        if (favs.isEmpty()) places.addElement(Messages.get("filechooser.favorites.empty"));
        Set<Path> folders = new LinkedHashSet<>();
        for (Path f : AppPrefs.getRecentFiles()) {
            Path dir = f.toAbsolutePath().getParent();
            if (dir != null && Files.isDirectory(dir) && !favs.contains(dir)) folders.add(dir);
            if (folders.size() == 6) break;
        }
        if (!folders.isEmpty()) {
            places.addElement(Messages.get("filechooser.recent.label"));
            folders.forEach(p -> places.addElement(new Recent(p)));
        }
        placesList.setModel(places);
        // Show where the chooser is: its folder, if it's in the list.
        File cur = chooser.getCurrentDirectory();
        for (int i = 0; i < places.size(); i++) {
            Object v = places.get(i);
            Path p = v instanceof Favorite f ? f.path() : v instanceof Recent r ? r.path() : null;
            if (p != null && cur != null && p.equals(cur.toPath().toAbsolutePath())) {
                placesList.getSelectionModel().setValueIsAdjusting(true);
                placesList.setSelectedIndex(i);
                placesList.getSelectionModel().setValueIsAdjusting(false);
                return;
            }
        }
        if (sel == null) placesList.clearSelection();
    }

    private void removeFavorite(Path p) {
        List<Path> favs = new ArrayList<>(AppPrefs.getFavorites());
        if (favs.remove(p)) {
            AppPrefs.saveFavorites(favs);
            rebuildPlaces();
        }
    }

    /** Group titles small and dim, an empty favorites list's hint italic, places by name. */
    private static final class PlaceRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
            if (v instanceof String s) {
                boolean title = !s.equals(Messages.get("filechooser.favorites.empty"));
                super.getListCellRendererComponent(l, title ? s.toUpperCase(Locale.ROOT) : s, i, false, false);
                setForeground(title ? ClouseauColors.dimForeground() : ClouseauColors.mutedForeground());
                setFont(getFont().deriveFont(title ? Font.PLAIN : Font.ITALIC, title ? 11f : getFont().getSize2D()));
                setBorder(BorderFactory.createEmptyBorder(i == 0 || !title ? 2 : 12, title ? 4 : 12, 4, 4));
                setToolTipText(null);
                setOpaque(false);
                return this;
            }
            Path p = v instanceof Favorite f ? f.path() : ((Recent) v).path();
            super.getListCellRendererComponent(l, p.getFileName() != null ? p.getFileName().toString() : p.toString(), i, sel, false);
            setToolTipText(p.toString());
            setBorder(BorderFactory.createEmptyBorder(3, 12, 3, v instanceof Favorite ? 28 : 6));
            if (!Files.exists(p) && !sel) setForeground(ClouseauColors.mutedForeground());
            setOpaque(sel);                       // rows sit on the sidebar; only the selection is filled
            return this;
        }
    }

    /** The places list, with a trash can on the favorite under the mouse. */
    private final class PlacesList extends JList<Object> {
        private static final int TOOL = 24;
        private int hoverRow = -1;
        private boolean overTrash;

        PlacesList() {
            MouseAdapter hover = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e)  { track(e.getPoint()); }
                @Override public void mouseExited(MouseEvent e) { hoverRow = -1; overTrash = false; repaint(); }
            };
            addMouseListener(hover);
            addMouseMotionListener(hover);
        }

        private void track(Point p) {
            int row = locationToIndex(p);
            Rectangle r = row >= 0 ? getCellBounds(row, row) : null;
            int newRow = r != null && r.contains(p) && places.get(row) instanceof Favorite ? row : -1;
            boolean trash = newRow >= 0 && p.x >= getWidth() - TOOL;
            if (newRow != hoverRow || trash != overTrash) {
                hoverRow = newRow;
                overTrash = trash;
                repaint();
            }
        }

        @Override protected void processMouseEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(e)) {
                track(e.getPoint());
                if (overTrash && hoverRow >= 0 && places.get(hoverRow) instanceof Favorite f) {
                    e.consume();
                    SwingUtilities.invokeLater(() -> removeFavorite(f.path()));
                    return;
                }
                int row = locationToIndex(e.getPoint());
                if (row >= 0 && !(places.get(row) instanceof Favorite || places.get(row) instanceof Recent)) {
                    e.consume();                 // a title or the hint isn't a place
                    return;
                }
            }
            if (e.getID() == MouseEvent.MOUSE_CLICKED && overTrash) return;
            super.processMouseEvent(e);
        }

        @Override public String getToolTipText(MouseEvent e) {
            track(e.getPoint());
            return overTrash ? Messages.get("filechooser.favorites.remove.tooltip") : super.getToolTipText(e);
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (hoverRow < 0 || hoverRow >= places.size()) return;
            Rectangle r = getCellBounds(hoverRow, hoverRow);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = isSelectedIndex(hoverRow) ? UIManager.getColor("List.selectionForeground") : ClouseauColors.dimForeground();
            ToolIcons.paintTrash(g2, getWidth() - TOOL + 5, r.y + (r.height - 14) / 2,
                    overTrash ? ClouseauColors.levelColor(LogEntry.LogLevel.ERROR) : base);
            g2.dispose();
        }
    }

    // ── Footer ───────────────────────────────────────────────────────────────

    private JPanel buildButtonBar() {
        JButton cancelBtn = new JButton(Messages.get("filechooser.button.cancel"));
        cancelBtn.addActionListener(e -> dispose());
        openBtn = new JButton(Messages.get("filechooser.button.open"));
        openBtn.setEnabled(false);
        openBtn.addActionListener(e -> doApprove());
        getRootPane().setDefaultButton(openBtn);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        return DialogFooter.of(cancelBtn, openBtn);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Text that wraps to the width it's given; dim and a size smaller for a {@code note}. */
    private static JTextArea wrapText(String text, boolean note) {
        JTextArea t = new JTextArea(text);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setBorder(null);
        t.setFont(UIManager.getFont("Label.font").deriveFont(note ? 11.5f : UIManager.getFont("Label.font").getSize2D()));
        if (note) ClouseauColors.themedForeground(t, ClouseauColors::dimForeground);
        return t;
    }

    private static javax.swing.event.DocumentListener docListener(Runnable r) {
        return new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { r.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { r.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        };
    }
}
