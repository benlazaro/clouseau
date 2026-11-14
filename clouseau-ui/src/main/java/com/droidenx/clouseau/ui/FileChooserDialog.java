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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File → Open…. Favorites on the left (the star in the chooser's toolbar adds the folder
 * shown), the files in the middle, and on the right what the selection will become: each
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
    /**
     * Without the look and feel's places bar (Recent Items, Desktop, …): the favorites on the
     * left take its place. FlatLaf reads the flag when it installs the UI, which a theme switch
     * does again, so it's set around each install, for this chooser only. Each install also
     * builds a new toolbar and Look In combo, so the star and the combo's corners are redone.
     */
    private final JFileChooser chooser = new JFileChooser() {
        @Override public void updateUI() {
            Object old = UIManager.get("FileChooser.noPlacesBar");
            UIManager.put("FileChooser.noPlacesBar", Boolean.TRUE);
            try {
                super.updateUI();
            } finally {
                UIManager.put("FileChooser.noPlacesBar", old);
            }
            SwingUtilities.invokeLater(() -> roundLookIn(this));   // after a theme switch reaches the children
            if (starBtn != null) installStar();          // null while JFileChooser's constructor runs
        }
    };
    /** In the chooser's toolbar, after New Folder: adds the folder shown to Favorites, or removes it. */
    private JButton starBtn;
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
    private final DefaultListModel<Favorite> places = new DefaultListModel<>();
    private final PlacesList placesList = new PlacesList();
    private JScrollPane placesScroll;
    private JTextArea noFavorites;

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
        // Focus starts in the first thing to fill: the file name field, else the file list.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                JComponent first = nameField != null ? nameField : find(chooser, JTable.class);
                if (first == null) first = find(chooser, JList.class);
                if (first != null) first.requestFocusInWindow();
            }
        });
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
        starBtn = new JButton(new StarIcon());
        starBtn.addActionListener(e -> toggleFavorite());
        installStar();

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
     * The star goes in the chooser's own toolbar, right after New Folder, with a separator on
     * each side. FlatLaf makes the toolbar's buttons toolbar buttons when it installs the UI,
     * before the star is here, so the star is given the same look itself.
     */
    private void installStar() {
        AbstractButton newFolder = findButton(chooser, UIManager.getIcon("FileChooser.newFolderIcon"));
        if (newFolder == null || newFolder.getParent() == null) {
            log.warn("No New Folder button in the file chooser's toolbar; the favorites star is left out");
            return;
        }
        Container bar = newFolder.getParent();
        int after = Arrays.asList(bar.getComponents()).indexOf(newFolder);
        starBtn.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE,
                com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        starBtn.setMargin(UIManager.getInsets("Button.margin"));
        starBtn.setFocusable(false);
        bar.add(toolbarSeparator(), after + 1);
        bar.add(starBtn, after + 2);
        bar.add(toolbarSeparator(), after + 3);
        updateStar();
    }

    private static AbstractButton findButton(Container c, Icon icon) {
        for (Component child : c.getComponents()) {
            if (child instanceof AbstractButton b && b.getIcon() == icon) return b;
            if (child instanceof Container sub) {
                AbstractButton b = findButton(sub, icon);
                if (b != null) return b;
            }
        }
        return null;
    }

    /** A thin vertical line between toolbar buttons, with room on both sides, centered on their height. */
    private static JComponent toolbarSeparator() {
        JSeparator line = new JSeparator(SwingConstants.VERTICAL);
        Dimension size = new Dimension(9, 18);       // FlatLaf draws the line in the middle of its width
        line.setPreferredSize(size);
        line.setMinimumSize(size);
        line.setMaximumSize(size);
        line.setAlignmentY(Component.CENTER_ALIGNMENT);
        return line;
    }

    /** The folder the chooser shows, as favorites are saved. */
    private Path shownFolder() {
        File dir = chooser.getCurrentDirectory();
        return dir != null ? dir.toPath().toAbsolutePath() : null;
    }

    private boolean shownIsFavorite() {
        Path p = shownFolder();
        return p != null && AppPrefs.getFavorites().contains(p);
    }

    private void toggleFavorite() {
        Path p = shownFolder();
        if (p == null) return;
        List<Path> favs = new ArrayList<>(AppPrefs.getFavorites());
        if (!favs.remove(p)) favs.add(p);
        saveFavorites(favs);
    }

    /** The star filled and "Remove from Favorites" when the folder shown is a favorite. */
    private void updateStar() {
        if (starBtn == null) return;
        boolean fav = shownIsFavorite();
        starBtn.putClientProperty(StarIcon.FILLED, fav);
        starBtn.setToolTipText(Messages.get(fav ? "filechooser.favorites.remove" : "filechooser.favorites.add"));
        starBtn.repaint();
    }

    /** Toolbar icon: filled in the star color for a favorite, else outlined in the text color. */
    private static final class StarIcon implements Icon {
        static final String FILLED = "Clouseau.starFilled";

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            boolean filled = c instanceof JComponent jc && Boolean.TRUE.equals(jc.getClientProperty(FILLED));
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            ToolIcons.paintStar(g2, x, y, filled ? ClouseauColors.starColor() : UIManager.getColor("Label.foreground"), filled);
            g2.dispose();
        }
        @Override public int getIconWidth()  { return 16; }
        @Override public int getIconHeight() { return 16; }
    }

    /**
     * The Look In combo with the same 4px corners as the dialog's other drop-downs. Swing's
     * file chooser marks it {@code JComboBox.isTableCellEditor} (so the arrow keys move through
     * the list without opening each folder), and FlatLaf takes that flag to mean a table cell,
     * which it draws square and without a focus ring. The flag stays, for the keys; the combo
     * gets a border that doesn't treat it as a cell.
     * <p>
     * FlatLaf's borders are UI resources, so the combo's own {@code updateUI()} puts the theme's
     * border back. A theme switch updates the chooser first and its children after, so the
     * border is set once that's done ({@code invokeLater} from the chooser's {@code updateUI}).
     */
    private static void roundLookIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JComboBox<?> combo
                    && Boolean.TRUE.equals(combo.getClientProperty("JComboBox.isTableCellEditor"))) {
                if (!(combo.getBorder() instanceof LookInBorder)) combo.setBorder(new LookInBorder());
            } else if (child instanceof Container sub) {
                roundLookIn(sub);
            }
        }
    }

    /** The theme's combo border, except that it never takes the combo for a table cell. */
    private static final class LookInBorder extends com.formdev.flatlaf.ui.FlatRoundBorder {
        @Override protected boolean isCellEditor(Component c) { return false; }
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
        if (chosen.isEmpty()) return;      // a pattern with no matches is answered by the summary line
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
            setSummary(fromPattern
                            ? MessageFormat.format(Messages.get("filechooser.pattern.none"), typedPattern().getName())
                            : Messages.get("filechooser.summary.none"),
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
        // As the destination draws it: the log table's font and size (mono-log, JetBrains Mono 13).
        preview.setFont(ClouseauFonts.mono(Font.PLAIN, 13));
        preview.setBackground(ClouseauColors.tableBackground());
        preview.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        // No wrapping: lines keep their shape and scroll sideways, as in the table.
        JPanel noWrap = new JPanel(new BorderLayout());
        noWrap.add(preview);
        JScrollPane previewScroll = ClouseauColors.noFocusFrame(new JScrollPane(noWrap));
        previewScroll.getVerticalScrollBar().setUnitIncrement(16);

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

    /**
     * A file in the list: its name, and under it what it opens as, in its status color. Two
     * plain labels, so a long name or verdict is cut short with … (the full text is the tooltip).
     */
    private static final class VerdictRenderer extends JPanel implements ListCellRenderer<Object> {
        private final JLabel name = new JLabel();
        private final JLabel what = new JLabel();

        VerdictRenderer() {
            super(new MigLayout("insets 2 6 2 6, gap 0, wrap 1", "[grow, fill, 0::]"));
            add(name, "wmin 0");
            add(what, "wmin 0");
        }

        @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
            Path p = v instanceof Verdict verdict ? verdict.file() : (Path) v;
            String text = v instanceof Verdict verdict ? verdict.text() : Messages.get("filechooser.summary.checking");
            Color c = v instanceof Verdict verdict ? verdict.color() : ClouseauColors.dimForeground();
            name.setText(p.getFileName().toString());
            what.setText(text);
            name.setFont(l.getFont());
            what.setFont(UIManager.getFont("Label.font").deriveFont(11f));
            name.setForeground(sel ? l.getSelectionForeground() : l.getForeground());
            what.setForeground(c);
            setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            setOpaque(true);
            setToolTipText(p.getFileName() + ": " + text);
            return this;
        }
    }

    // ── Favorites ────────────────────────────────────────────────────────────

    /** A favorite folder or file. */
    private record Favorite(Path path) {}

    /**
     * Favorites, under a section title like the details pane's: a click opens the folder (or
     * selects the file). The favorite under the mouse has a trash can, and a right-click offers
     * Remove from Favorites and Copy Path. With none, a hint points to the toolbar's star.
     */
    private JComponent buildPlaces() {
        placesList.setCellRenderer(new PlaceRenderer());
        placesList.setOpaque(false);
        placesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ToolTipManager.sharedInstance().registerComponent(placesList);
        placesList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            Favorite f = placesList.getSelectedValue();
            if (f == null) return;
            Path p = f.path();
            if (Files.isDirectory(p)) chooser.setCurrentDirectory(p.toFile());
            else if (p.getParent() != null && Files.isDirectory(p.getParent())) {
                chooser.setCurrentDirectory(p.getParent().toFile());
                chooser.setSelectedFile(p.toFile());
            }
        });
        placesList.registerKeyboardAction(e -> {
                    Favorite f = placesList.getSelectedValue();
                    if (f != null) removeFavorite(f.path());
                },
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), JComponent.WHEN_FOCUSED);

        placesScroll = new JScrollPane(placesList);
        placesScroll.setBorder(BorderFactory.createEmptyBorder());
        placesScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        placesScroll.setOpaque(false);
        placesScroll.getViewport().setOpaque(false);

        noFavorites = wrapText(Messages.get("filechooser.favorites.empty"), false);
        noFavorites.setFont(UIManager.getFont("Label.font").deriveFont(Font.ITALIC, 11f));
        ClouseauColors.themedForeground(noFavorites, ClouseauColors::mutedForeground);
        rebuildPlaces();

        String in = SettingsDialog.INDENT_NARROW;
        JPanel side = new JPanel(new MigLayout("insets 12 12 12 8, fill, wrap 1, gapy 4, hidemode 3",
                "[grow, fill, 0::]", "[][][grow, fill]"));
        side.add(SettingsDialog.sectionLabel(Messages.get("filechooser.favorites.label")), "growx");
        side.add(noFavorites, "wmin 0, " + in);
        side.add(placesScroll, "wmin 0, " + in);
        side.setMinimumSize(new Dimension(120, 0));
        return side;
    }

    /** Lists the favorites, selects the folder the chooser shows if it's one, and updates the star. */
    private void rebuildPlaces() {
        places.clear();
        List<Path> favs = AppPrefs.getFavorites();
        favs.forEach(p -> places.addElement(new Favorite(p)));
        placesList.setModel(places);
        if (noFavorites != null && noFavorites.isVisible() != favs.isEmpty()) {
            noFavorites.setVisible(favs.isEmpty());
            placesScroll.setVisible(!favs.isEmpty());
            if (placesScroll.getParent() != null) placesScroll.getParent().revalidate();
        }
        updateStar();
        Path cur = shownFolder();
        for (int i = 0; i < places.size(); i++) {
            if (places.get(i).path().equals(cur)) {
                placesList.getSelectionModel().setValueIsAdjusting(true);
                placesList.setSelectedIndex(i);
                placesList.getSelectionModel().setValueIsAdjusting(false);
                return;
            }
        }
        placesList.clearSelection();
    }

    /** Saves the favorites, then shows them: the list and the star. Every change comes through here. */
    private void saveFavorites(List<Path> favs) {
        AppPrefs.saveFavorites(favs);
        rebuildPlaces();
    }

    private void removeFavorite(Path p) {
        List<Path> favs = new ArrayList<>(AppPrefs.getFavorites());
        if (favs.remove(p)) saveFavorites(favs);
    }

    /** Remove from Favorites and Copy Path, for the favorite right-clicked. */
    private void showFavoriteMenu(Favorite f, Point at) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem remove = new JMenuItem(Messages.get("filechooser.favorites.remove"));
        remove.addActionListener(e -> removeFavorite(f.path()));
        JMenuItem copy = new JMenuItem(Messages.get("filechooser.favorites.copy"));
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(f.path().toString()), null));
        menu.add(remove);
        menu.add(copy);
        menu.show(placesList, at.x, at.y);
    }

    /**
     * A favorite on two lines: its name, and its full path under it, small and dim. Room on the
     * right is kept for the trash can, so it never covers the text. Only the selection is filled.
     */
    private static final class PlaceRenderer extends JPanel implements ListCellRenderer<Favorite> {
        private final JLabel name = new JLabel();
        private final JLabel path = new JLabel();

        PlaceRenderer() {
            super(new MigLayout("insets 4 6 4 " + PlacesList.TOOL + ", gap 0, wrap 1", "[grow, fill, 0::]"));
            add(name, "wmin 0");
            add(path, "wmin 0");
        }

        @Override public Component getListCellRendererComponent(JList<? extends Favorite> l, Favorite f, int i,
                                                                boolean sel, boolean foc) {
            Path p = f.path();
            name.setText(p.getFileName() != null ? p.getFileName().toString() : p.toString());
            path.setText(p.toString());
            name.setFont(l.getFont());
            path.setFont(UIManager.getFont("Label.font").deriveFont(11f));
            boolean missing = !Files.exists(p);
            Color fg = sel ? UIManager.getColor("List.selectionForeground") : l.getForeground();
            name.setForeground(missing && !sel ? ClouseauColors.mutedForeground() : fg);
            path.setForeground(sel ? fg : missing ? ClouseauColors.mutedForeground() : ClouseauColors.dimForeground());
            setBackground(UIManager.getColor("List.selectionBackground"));
            setOpaque(sel);                       // rows sit on the sidebar; only the selection is filled
            setToolTipText(p.toString());
            return this;
        }
    }

    /** The favorites list, with a trash can on the favorite under the mouse and a right-click menu. */
    private final class PlacesList extends JList<Favorite> {
        static final int TOOL = 24;
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

        /** As wide as the sidebar: long names and paths are cut short with …, never scrolled sideways. */
        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        private int rowAt(Point p) {
            int row = locationToIndex(p);
            Rectangle r = row >= 0 ? getCellBounds(row, row) : null;
            return r != null && r.contains(p) ? row : -1;
        }

        private void track(Point p) {
            int newRow = rowAt(p);
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
                if (overTrash && hoverRow >= 0) {
                    Favorite f = places.get(hoverRow);
                    e.consume();
                    SwingUtilities.invokeLater(() -> removeFavorite(f.path()));
                    return;
                }
                if (rowAt(e.getPoint()) < 0) {   // below the last row: not a place
                    e.consume();
                    return;
                }
            }
            if (e.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isRightMouseButton(e)) {
                // The menu acts on the row clicked; selecting it would open its folder.
                int row = rowAt(e.getPoint());
                e.consume();
                if (row >= 0) showFavoriteMenu(places.get(row), e.getPoint());
                return;
            }
            if ((e.getID() == MouseEvent.MOUSE_CLICKED || e.getID() == MouseEvent.MOUSE_RELEASED)
                    && (overTrash || SwingUtilities.isRightMouseButton(e))) return;
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
        t.setFont(UIManager.getFont("Label.font").deriveFont(note ? 11f : UIManager.getFont("Label.font").getSize2D()));
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
