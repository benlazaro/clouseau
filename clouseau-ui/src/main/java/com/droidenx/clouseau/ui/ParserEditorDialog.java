package com.droidenx.clouseau.ui;

import com.google.gson.*;
import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.ui.theme.ClouseauFonts;
import com.droidenx.clouseau.core.RegexLogParser;
import com.droidenx.clouseau.core.LogReaders;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.MessageFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tools → Parser Editor…: the user's own parsers, made from a template, a Logback / Log4j
 * pattern or a regex, and tested on a line or a file as they're edited.
 * <p>
 * Laid out like Settings and the SSH dialog: the parsers in a sidebar (yours, in the order
 * auto-detection tries them, then the built-in ones, read-only), sections on the right, and
 * a footer with Cancel and OK. OK saves every parser at once; Cancel or Esc asks first when
 * something changed. Problems show on the field they're about.
 */
@Slf4j
public final class ParserEditorDialog extends JDialog {

    private static final String TYPE_SIMPLIFIED = "template";
    private static final String TYPE_PATTERN    = "pattern";
    private static final String TYPE_REGEX      = "regex";

    private record ParserDef(String name, String type, String source,
                             String pattern, String timestampFormat, boolean builtin) {}

    private static final Path   USER_FILE        = AppPrefs.PREFS_DIR.resolve("parsers.json");
    private static final String DEFAULT_RESOURCE = "com/droidenx/clouseau/ui/default-parsers.json";
    private static final Gson   GSON             = new GsonBuilder().setPrettyPrinting().create();
    /** How many lines of a file "Test with file…" reads. */
    private static final int    FILE_TEST_LINES  = 500;

    private final Runnable onSave;

    // ── Parsers ──────────────────────────────────────────────────────────────
    /** Yours, in the order auto-detection tries them; what OK saves. */
    private final List<ParserDef> mine = new ArrayList<>();
    private final List<ParserDef> builtins = new ArrayList<>();
    /** Yours as loaded, to tell whether anything changed. */
    private List<ParserDef> loaded = List.of();
    /** The sidebar rows: a group title (String) or a parser. */
    private final DefaultListModel<Object> listModel = new DefaultListModel<>();
    private final ParserList parserList = new ParserList();
    /** The parser the form shows: an index into {@link #mine}, or -1 for a built-in or none. */
    private int editing = -1;
    private ParserDef shown;

    // ── Form ─────────────────────────────────────────────────────────────────
    private final JTextField        nameField   = new JTextField();
    private final JLabel            nameError   = errorLabel();
    private final JComboBox<String> typeCombo   = new JComboBox<>(new String[]{
            Messages.get("parsereditor.type.template"),
            Messages.get("parsereditor.type.pattern"),
            Messages.get("parsereditor.type.regex") });
    private final JPanel            builtinNote = new JPanel(new MigLayout("insets 0, gap 12", "[grow, fill, 0::][]", "[center]"));

    private final JLabel    sourceTitle  = new JLabel();
    private final JComponent sourceSection;
    private final JTextArea sourceArea   = new JTextArea(3, 1);
    private final JScrollPane sourceScroll = new JScrollPane(sourceArea);
    private final JTextArea sourceHint   = wrapText("");
    private final JPanel    keywordRow   = new JPanel(new MigLayout("insets 0, gap 4", "", "[center]"));
    private final JLabel    sourceError  = errorLabel();

    private final JLabel    patternLabel = new JLabel(Messages.get("parsereditor.field.pattern"));
    private final JTextArea patternArea  = new JTextArea(4, 1);
    private final JScrollPane patternScroll = new JScrollPane(patternArea);
    private final JButton   copyRegexBtn = new JButton(Messages.get("parsereditor.copy.regex"));
    private final JTextArea regexWarning = wrapText(Messages.get("parsereditor.type.regex.warning"));
    private final JLabel    patternError = errorLabel();
    private final JLabel    formatLabel  = new JLabel(Messages.get("parsereditor.field.format"));
    private final JTextField formatField = new JTextField();
    private final JLabel    formatError  = errorLabel();

    // ── Test ─────────────────────────────────────────────────────────────────
    private final JTextField sampleField   = new JTextField();
    private final JLabel     matchLabel    = new JLabel(" ");
    private final JPanel     fieldsPanel   = new JPanel(new MigLayout("insets 0, wrap 2, gapy 2", "[]12[grow, 0::]"));
    private final JButton    fileBtn       = new JButton(Messages.get("parsereditor.test.file"));
    private final JLabel     fileTestLabel = new JLabel(" ");
    private final JLabel     unmatchedLine = new JLabel();
    private final JButton    useAsSample   = new JButton(Messages.get("parsereditor.test.use.sample"));
    /** Lines of the log the editor was opened for (Create a Parser…), tested without asking. */
    private List<String> openedLog = List.of();
    private String firstUnmatched;

    // ── State ────────────────────────────────────────────────────────────────
    private boolean updatingForm = false;

    public ParserEditorDialog(Frame owner, Runnable onSave) {
        super(owner, Messages.get("parsereditor.title"), true);
        this.onSave = onSave;
        loadDefs();
        loaded = List.copyOf(mine);
        sourceSection = SettingsDialog.sectionLabel("");

        JPanel root = new JPanel(new BorderLayout());
        root.add(buildSidebar(), BorderLayout.WEST);
        JScrollPane formScroll = new JScrollPane(buildForm());
        formScroll.setBorder(BorderFactory.createEmptyBorder());
        formScroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(formScroll, BorderLayout.CENTER);
        root.add(buildButtonBar(), BorderLayout.SOUTH);
        setContentPane(root);

        setSize(980, 720);
        setMinimumSize(new Dimension(760, 560));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { cancel(); }
        });

        wireListeners();
        rebuildList(mine.isEmpty() ? (builtins.isEmpty() ? null : builtins.get(0)) : mine.get(0));
    }

    /**
     * Opens the editor on a new parser for a file no parser recognizes (UC-13): its first
     * non-blank line is the sample, and the new parser is tested against all the lines given
     * as it's edited, without asking for the file.
     */
    public ParserEditorDialog(Frame owner, Runnable onSave, List<String> samples) {
        this(owner, onSave);
        openedLog = samples.stream().filter(s -> !s.isBlank()).toList();
        addParser();
        openedLog.stream().findFirst().ifPresent(sampleField::setText);
        runFileTest();
    }

    // ── Data loading ─────────────────────────────────────────────────────────

    private void loadDefs() {
        loadArray(readResource(), true, builtins);
        loadArray(readUserFile(), false, mine);
    }

    private static void loadArray(JsonArray arr, boolean builtin, List<ParserDef> into) {
        if (arr == null) return;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String name = str(o, "name"), pat = str(o, "pattern"), fmt = str(o, "timestampFormat");
            if (name == null || pat == null || fmt == null) continue;
            String type   = firstNonNull(str(o, "type"),   TYPE_REGEX);
            String source = firstNonNull(str(o, "source"), "");
            into.add(new ParserDef(name, type, source, pat, fmt, builtin));
        }
    }

    private JsonArray readResource() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(DEFAULT_RESOURCE)) {
            if (is == null) return null;
            JsonElement el = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            return el.isJsonArray() ? el.getAsJsonArray() : null;
        } catch (IOException e) { return null; }
    }

    private static JsonArray readUserFile() {
        if (!Files.exists(USER_FILE)) return null;
        try (Reader r = Files.newBufferedReader(USER_FILE)) {
            JsonElement el = JsonParser.parseReader(r);
            return el.isJsonArray() ? el.getAsJsonArray() : null;
        } catch (IOException | RuntimeException e) { return null; }
    }

    // ── Sidebar ──────────────────────────────────────────────────────────────

    /**
     * The parsers, as the SSH dialog lists saved connections: yours first, in the order
     * auto-detection tries them (drag, or Alt+↑ / Alt+↓, to reorder), then the built-in ones.
     * Duplicate and the trash can are on the row under the mouse; New Parser is below.
     */
    private JComponent buildSidebar() {
        parserList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        parserList.setCellRenderer(new ParserRenderer());
        parserList.setOpaque(false);
        ToolTipManager.sharedInstance().registerComponent(parserList);
        JScrollPane scroll = new JScrollPane(parserList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);

        JButton newBtn = new JButton(Messages.get("parsereditor.new"));
        newBtn.addActionListener(e -> addParser());

        JTextArea order = wrapText(Messages.get("parsereditor.order.hint"));

        JPanel side = new JPanel(new MigLayout("insets 16 16 12 12, fill, gapy 8", "[grow, fill]", "[grow, fill][][]"));
        side.add(scroll, "wrap, h 100:100%:");
        side.add(order, "wrap, wmin 0");
        side.add(newBtn, "growx 0, left");
        side.setPreferredSize(new Dimension(240, 10));
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Separator.foreground")));

        parserList.registerKeyboardAction(e -> deleteSelected(),
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), JComponent.WHEN_FOCUSED);
        parserList.registerKeyboardAction(e -> duplicate(selectedDef()),
                KeyStroke.getKeyStroke(KeyEvent.VK_D, java.awt.event.InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_FOCUSED);
        parserList.registerKeyboardAction(e -> move(-1),
                KeyStroke.getKeyStroke(KeyEvent.VK_UP, java.awt.event.InputEvent.ALT_DOWN_MASK), JComponent.WHEN_FOCUSED);
        parserList.registerKeyboardAction(e -> move(+1),
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, java.awt.event.InputEvent.ALT_DOWN_MASK), JComponent.WHEN_FOCUSED);
        return side;
    }

    /** Refills the sidebar from {@link #mine} and the built-ins, selecting {@code select}. */
    private void rebuildList(ParserDef select) {
        updatingForm = true;
        listModel.clear();
        listModel.addElement(Messages.get("parsereditor.group.mine"));
        mine.forEach(listModel::addElement);
        if (mine.isEmpty()) listModel.addElement(new EmptyMine());
        listModel.addElement(Messages.get("parsereditor.group.builtin"));
        builtins.forEach(listModel::addElement);
        parserList.setModel(listModel);
        updatingForm = false;
        int row = select != null ? rowOf(select) : -1;
        if (row >= 0) {
            parserList.setSelectedIndex(row);
            parserList.ensureIndexIsVisible(row);
        } else {
            parserList.clearSelection();
            showDef(null);
        }
    }

    /** The row "None yet" under Yours: not a parser, not selectable. */
    private record EmptyMine() {}

    private int rowOf(ParserDef def) {
        for (int i = 0; i < listModel.size(); i++) if (listModel.get(i) == def) return i;
        return -1;
    }

    private ParserDef selectedDef() {
        return parserList.getSelectedValue() instanceof ParserDef d ? d : null;
    }

    // ── Form ─────────────────────────────────────────────────────────────────

    /** Parser, then Template / Pattern / Regex, then Test, as Settings lays out sections. */
    private JComponent buildForm() {
        JPanel form = new WidthTrackingPanel(new MigLayout("insets 16 20 16 20, wrap 2, fillx, hidemode 3",
                "[150!][grow, fill, 0::]"));

        // ── Parser
        form.add(SettingsDialog.sectionLabel(Messages.get("parsereditor.section.parser")), "span 2, growx");
        form.add(new JLabel(Messages.get("parsereditor.field.name")), "gapleft 16");
        form.add(nameField);
        form.add(nameError, "skip 1");
        form.add(new JLabel(Messages.get("parsereditor.field.type")), "gapleft 16");
        form.add(typeCombo, "growx 0");
        JTextArea noteText = wrapText(Messages.get("parsereditor.builtin.note"));
        JButton dupBuiltin = new JButton(Messages.get("parsereditor.duplicate"));
        dupBuiltin.addActionListener(e -> duplicate(shown));
        builtinNote.add(noteText, "growx, wmin 0");
        builtinNote.add(dupBuiltin);
        form.add(builtinNote, "span 2, gapleft 16, gaptop 4, growx");

        // ── Template / Pattern: the source, and the regex made from it
        form.add(sourceSection, "span 2, growx, gaptop 16");
        sourceArea.setLineWrap(true);
        sourceArea.setFont(ClouseauFonts.mono(Font.PLAIN, 12));
        form.add(sourceTitle, "gapleft 16, top");
        form.add(sourceScroll, "hmin 64");
        form.add(sourceError, "skip 1");
        keywordRow.setOpaque(false);
        buildKeywords();
        form.add(keywordRow, "skip 1");
        form.add(sourceHint, "skip 1, wmin 0");

        patternArea.setLineWrap(true);
        patternArea.setFont(ClouseauFonts.mono(Font.PLAIN, 12));
        form.add(patternLabel, "gapleft 16, top, gaptop 8");
        form.add(patternScroll, "hmin 84, gaptop 8");
        form.add(regexWarning, "skip 1");
        form.add(patternError, "skip 1");
        copyRegexBtn.setToolTipText(Messages.get("parsereditor.copy.regex.tooltip"));
        copyRegexBtn.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(patternArea.getText()), null));
        form.add(copyRegexBtn, "skip 1, growx 0");
        form.add(formatLabel, "gapleft 16");
        form.add(formatField);
        form.add(formatError, "skip 1");

        // ── Test
        form.add(SettingsDialog.sectionLabel(Messages.get("parsereditor.test.title")), "span 2, growx, gaptop 16");
        form.add(new JLabel(Messages.get("parsereditor.test.sample")), "gapleft 16");
        sampleField.setFont(ClouseauFonts.mono(Font.PLAIN, 12));
        form.add(sampleField);
        matchLabel.setFont(matchLabel.getFont().deriveFont(Font.BOLD));
        form.add(matchLabel, "skip 1");
        fieldsPanel.setOpaque(false);
        form.add(fieldsPanel, "skip 1, growx");

        form.add(new JLabel(Messages.get("parsereditor.test.file.label")), "gapleft 16, gaptop 12");
        JPanel fileRow = new JPanel(new MigLayout("insets 0, gap 10", "[][grow, 0::]", "[center]"));
        fileRow.setOpaque(false);
        fileBtn.addActionListener(e -> testWithFile());
        fileRow.add(fileBtn);
        fileRow.add(fileTestLabel, "wmin 0");
        form.add(fileRow, "gaptop 12");
        JPanel unmatchedRow = new JPanel(new MigLayout("insets 0, gap 10", "[grow, 0::][]", "[center]"));
        unmatchedRow.setOpaque(false);
        unmatchedLine.setFont(ClouseauFonts.mono(Font.PLAIN, 12));
        useAsSample.addActionListener(e -> { if (firstUnmatched != null) sampleField.setText(firstUnmatched); });
        unmatchedRow.add(unmatchedLine, "wmin 0");
        unmatchedRow.add(useAsSample);
        form.add(unmatchedRow, "skip 1, growx");
        unmatchedRow.setVisible(false);
        unmatchedLine.putClientProperty("row", unmatchedRow);
        return form;
    }

    /** The template's keywords, as buttons that type one at the cursor. */
    private void buildKeywords() {
        String[][] keywords = {
                { "TIMESTAMP{yyyy-MM-dd HH:mm:ss.SSS}", "parsereditor.kw.timestamp" },
                { "LEVEL", "parsereditor.kw.level" }, { "THREAD", "parsereditor.kw.thread" },
                { "LOGGER", "parsereditor.kw.logger" }, { "MESSAGE", "parsereditor.kw.message" },
                { "PID", "parsereditor.kw.pid" }, { "{field}", "parsereditor.kw.field" } };
        for (String[] k : keywords) {
            String insert = k[0];
            JButton b = new JButton(insert.startsWith("TIMESTAMP") ? "TIMESTAMP{…}" : insert);
            b.setFont(ClouseauFonts.mono(Font.PLAIN, 11));
            b.setMargin(new Insets(1, 6, 1, 6));
            b.setFocusable(false);                       // the cursor stays in the template
            b.setToolTipText(Messages.get(k[1]));
            b.addActionListener(e -> {
                if (!sourceArea.isEditable()) return;
                sourceArea.replaceSelection(insert);
                sourceArea.requestFocusInWindow();
            });
            keywordRow.add(b);
        }
    }

    private void wireListeners() {
        parserList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updatingForm) return;
            Object v = parserList.getSelectedValue();
            if (v != null && !(v instanceof ParserDef)) {
                // A group title or "None yet": not a row you can pick.
                parserList.setSelectedValue(shown, false);
                return;
            }
            showDef((ParserDef) v);
        });
        typeCombo.addActionListener(e -> {
            if (updatingForm) return;
            updateTypeUI(selectedType());
            convertAndUpdate();
        });
        sourceArea.getDocument().addDocumentListener(docListener(() -> { if (!updatingForm) convertAndUpdate(); }));
        patternArea.getDocument().addDocumentListener(docListener(() -> {
            if (!updatingForm && TYPE_REGEX.equals(selectedType())) changed();
        }));
        formatField.getDocument().addDocumentListener(docListener(() -> { if (!updatingForm) changed(); }));
        nameField.getDocument().addDocumentListener(docListener(() -> {
            if (updatingForm) return;
            changed();
            parserList.repaint();
        }));
        sampleField.getDocument().addDocumentListener(docListener(() -> { if (!updatingForm) runTest(); }));
    }

    /** Shows a parser in the form: editable if it's yours, read-only with a note if built in. */
    private void showDef(ParserDef def) {
        updatingForm = true;
        shown = def;
        editing = def != null && !def.builtin() ? indexInMine(def) : -1;
        boolean has = def != null, editable = editing >= 0;
        String type = has ? def.type() : TYPE_SIMPLIFIED;

        nameField.setText(has ? def.name() : "");
        setTypeCombo(type);
        sourceArea.setText(has ? def.source() : "");
        patternArea.setText(has ? def.pattern() : "");
        formatField.setText(has ? def.timestampFormat() : "");
        updateTypeUI(type);

        nameField.setEditable(editable);
        typeCombo.setEnabled(editable);
        sourceArea.setEditable(editable);
        formatField.setEditable(editable && TYPE_REGEX.equals(type));
        patternArea.setEditable(editable && TYPE_REGEX.equals(type));
        for (Component c : keywordRow.getComponents()) c.setEnabled(editable);
        builtinNote.setVisible(has && def.builtin());
        sampleField.setEnabled(has);
        fileBtn.setEnabled(has);
        updatingForm = false;
        validateForm();
        runTest();
        fileTestLabel.setText(" ");
        showUnmatched(null);
        if (has && !openedLog.isEmpty()) runFileTest();
    }

    private int indexInMine(ParserDef def) {
        for (int i = 0; i < mine.size(); i++) if (mine.get(i) == def) return i;
        return -1;
    }

    /** The form changed: write it to the parser being edited, check it, test it. */
    private void changed() {
        if (editing >= 0) {
            ParserDef def = new ParserDef(nameField.getText().strip(), selectedType(),
                    sourceArea.getText().strip(), patternArea.getText().strip(),
                    formatField.getText().strip(), false);
            mine.set(editing, def);
            listModel.set(rowOf(shown), def);
            shown = def;
        }
        validateForm();
        runTest();
        if (!openedLog.isEmpty()) runFileTest();
    }

    // ── Type ─────────────────────────────────────────────────────────────────

    private String selectedType() {
        return switch (typeCombo.getSelectedIndex()) {
            case 0 -> TYPE_SIMPLIFIED;
            case 1 -> TYPE_PATTERN;
            default -> TYPE_REGEX;
        };
    }

    private void setTypeCombo(String type) {
        typeCombo.setSelectedIndex(switch (type) {
            case TYPE_SIMPLIFIED -> 0;
            case TYPE_PATTERN    -> 1;
            default              -> 2;
        });
    }

    /**
     * Template and Pattern show their source, and the regex made from it read-only (with
     * Copy, to start a Regex parser from it); Regex shows the regex and the timestamp format
     * to edit.
     */
    private void updateTypeUI(String type) {
        boolean regex = TYPE_REGEX.equals(type), template = TYPE_SIMPLIFIED.equals(type);
        ((JLabel) ((JPanel) sourceSection).getComponent(0)).setText(Messages.get(
                regex ? "parsereditor.section.regex" : template ? "parsereditor.section.template" : "parsereditor.section.pattern"));
        sourceTitle.setText(Messages.get(template ? "parsereditor.field.template" : "parsereditor.field.source"));
        sourceTitle.setVisible(!regex);
        sourceScroll.setVisible(!regex);
        keywordRow.setVisible(template);
        sourceHint.setText(Messages.get(template ? "parsereditor.template.hint" : "parsereditor.pattern.hint"));
        sourceHint.setVisible(!regex);
        patternLabel.setText(Messages.get(regex ? "parsereditor.field.pattern" : "parsereditor.field.generated"));
        copyRegexBtn.setVisible(!regex);
        regexWarning.setVisible(regex && editing >= 0);
        formatLabel.setVisible(regex);
        formatField.setVisible(regex);
        // The generated regex reads as output, not a field to type in.
        Color readOnly = UIManager.getColor("TextField.inactiveBackground");
        boolean editable = editing >= 0;
        patternArea.setBackground(regex && editable ? UIManager.getColor("TextArea.background") : readOnly);
        patternArea.setEditable(editable && regex);
        formatField.setEditable(editable && regex);
        revalidate();
        repaint();
    }

    /** Template / Pattern: makes the regex and the timestamp format from the source. */
    private void convertAndUpdate() {
        String type = selectedType();
        if (!TYPE_REGEX.equals(type)) {
            String source = sourceArea.getText().strip();
            String regex = "", fmt = "";
            if (!source.isBlank()) {
                try {
                    if (TYPE_SIMPLIFIED.equals(type)) {
                        TemplatePatternConverter.Result r = TemplatePatternConverter.convert(source);
                        regex = r.regex();
                        fmt = r.timestampFormat() != null ? r.timestampFormat() : "";
                    } else {
                        LogbackPatternConverter.Result r = LogbackPatternConverter.convert(source);
                        regex = r.regex();
                        fmt = r.timestampFormat() != null ? r.timestampFormat() : "";
                    }
                } catch (IllegalArgumentException e) {
                    regex = "";
                }
            }
            updatingForm = true;
            patternArea.setText(regex);
            formatField.setText(fmt);
            updatingForm = false;
        }
        changed();
    }

    // ── Checking ─────────────────────────────────────────────────────────────

    /** What's wrong with the parser shown, field by field; empty when nothing is. */
    private record Problems(String name, String source, String pattern, String format) {
        boolean any() { return name != null || source != null || pattern != null || format != null; }
        String first() {
            return name != null ? name : source != null ? source : pattern != null ? pattern : format;
        }
    }

    private Problems problems(ParserDef def) {
        if (def == null || def.builtin()) return new Problems(null, null, null, null);
        String name = null, source = null, pattern = null, format = null;

        String n = def.name().strip();
        if (n.isEmpty()) name = Messages.get("parsereditor.error.name.empty");
        else {
            for (ParserDef other : allDefs()) {
                if (other != def && other.name().strip().equalsIgnoreCase(n)) {
                    name = MessageFormat.format(Messages.get(other.builtin()
                            ? "parsereditor.error.name.builtin" : "parsereditor.error.name.taken"), other.name());
                    break;
                }
            }
        }

        boolean regex = TYPE_REGEX.equals(def.type());
        if (!regex) {
            if (def.source().isBlank()) {
                source = Messages.get(TYPE_SIMPLIFIED.equals(def.type())
                        ? "parsereditor.error.template.empty" : "parsereditor.error.pattern.empty");
            } else {
                try {
                    if (TYPE_SIMPLIFIED.equals(def.type())) TemplatePatternConverter.convert(def.source());
                    else LogbackPatternConverter.convert(def.source());
                    if (def.timestampFormat().isBlank()) source = Messages.get(TYPE_SIMPLIFIED.equals(def.type())
                            ? "parsereditor.error.template.no.time" : "parsereditor.error.pattern.no.time");
                } catch (IllegalArgumentException e) {
                    source = e.getMessage();
                }
            }
        } else {
            if (def.pattern().isBlank()) pattern = Messages.get("parsereditor.error.regex.empty");
            else {
                try {
                    Pattern.compile(def.pattern());
                } catch (PatternSyntaxException e) {
                    pattern = MessageFormat.format(Messages.get("parsereditor.error.regex"),
                            e.getDescription(), e.getIndex() >= 0 ? e.getIndex() + 1 : 1);
                }
            }
        }
        if (source == null && !def.timestampFormat().isBlank()) {
            String f = def.timestampFormat();
            if (!"ISO_OFFSET_DATE_TIME".equals(f)) {
                try {
                    DateTimeFormatter.ofPattern(f.replace(',', '.'));
                } catch (IllegalArgumentException e) {
                    String msg = MessageFormat.format(Messages.get("parsereditor.error.format"), e.getMessage());
                    if (regex) format = msg; else source = msg;
                }
            }
        } else if (regex && def.timestampFormat().isBlank()) {
            format = Messages.get("parsereditor.error.format.empty");
        }
        return new Problems(name, source, pattern, format);
    }

    /**
     * The problems worth showing while editing: an empty template or regex is a parser not
     * written yet, not a mistake, so it waits until OK.
     */
    private Problems visibleProblems(ParserDef def) {
        Problems p = problems(def);
        if (def == null) return p;
        boolean regex = TYPE_REGEX.equals(def.type());
        boolean unwritten = regex ? def.pattern().isBlank() : def.source().isBlank();
        return unwritten ? new Problems(p.name(), null, null, null) : p;
    }

    /** Shows each problem under its field, with the field outlined in red. */
    private void validateForm() {
        Problems p = visibleProblems(shown);
        mark(nameField, nameError, p.name());
        mark(sourceScroll, sourceError, p.source());
        mark(patternScroll, patternError, p.pattern());
        mark(formatField, formatError, p.format());
        parserList.repaint();
    }

    private static void mark(JComponent field, JLabel error, String problem) {
        field.putClientProperty("JComponent.outline", problem != null ? "error" : null);
        error.setText(problem != null ? problem : "");
        error.setVisible(problem != null);
        field.repaint();
    }

    private List<ParserDef> allDefs() {
        List<ParserDef> all = new ArrayList<>(mine);
        all.addAll(builtins);
        return all;
    }

    // ── Testing ──────────────────────────────────────────────────────────────

    /** The parser the form describes, or null while it can't be built. */
    private RegexLogParser previewParser() {
        if (shown == null) return null;
        Problems p = problems(shown);                     // a name problem doesn't stop a test
        if (p.source() != null || p.pattern() != null || p.format() != null) return null;
        String pattern = patternArea.getText().strip();
        String format  = formatField.getText().strip();
        if (pattern.isBlank() || format.isBlank()) return null;
        try {
            return new RegexLogParser("preview", Pattern.compile(pattern), format);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Tests the sample line and shows what it becomes, as the detail pane would show the
     * entry: the time in the display time zone and format, the level in its color, and any
     * custom fields, which become table columns.
     */
    private void runTest() {
        fieldsPanel.removeAll();
        matchLabel.setText(" ");
        String line = sampleField.getText().strip();
        if (shown != null && !line.isBlank()) {
            RegexLogParser parser = previewParser();
            if (parser == null) {
                status(matchLabel, notReady(), ClouseauColors.statusWarn());
            } else if (!parser.canParse(line)) {
                status(matchLabel, Messages.get("parsereditor.test.no.match"), ClouseauColors.statusError());
            } else {
                status(matchLabel, Messages.get("parsereditor.test.matched"), ClouseauColors.statusOk());
                LogEntry entry = parser.parse(line);
                DateTimeFormatter shownAs = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                        .withZone(AppPrefs.getDisplayTimezone());
                if (entry.timestamp() != null) addField(Messages.get("table.col.timestamp"), shownAs.format(entry.timestamp()), null);
                else addField(Messages.get("table.col.timestamp"), MessageFormat.format(
                        Messages.get("parsereditor.test.no.time"), formatField.getText().strip()), ClouseauColors.statusError());
                addField(Messages.get("table.col.level"), entry.level().name(), ClouseauColors.levelColor(entry.level()));
                addField(Messages.get("table.col.thread"), entry.thread(), null);
                addField(Messages.get("table.col.logger"), entry.logger(), null);
                addField(Messages.get("table.col.message"), entry.message(), null);
                entry.fields().forEach((k, v) -> addField(k, v, null));
            }
        }
        fieldsPanel.revalidate();
        fieldsPanel.repaint();
    }

    /** Why there's nothing to test yet: a problem shown above, or a parser not written yet. */
    private String notReady() {
        if (visibleProblems(shown).any()) return Messages.get("parsereditor.test.fix.first");
        return Messages.get(switch (shown.type()) {
            case TYPE_SIMPLIFIED -> "parsereditor.test.write.template";
            case TYPE_PATTERN    -> "parsereditor.test.write.pattern";
            default              -> "parsereditor.test.write.regex";
        });
    }

    private void addField(String key, String value, Color color) {
        JLabel k = new JLabel(key + ":");
        k.setForeground(ClouseauColors.detailKeyColor());
        k.setFont(ClouseauFonts.mono(Font.BOLD, 12));
        JLabel v = new JLabel(value != null && !value.isEmpty() ? value : "–");
        v.setFont(ClouseauFonts.mono(color != null && color != ClouseauColors.statusError() ? Font.BOLD : Font.PLAIN, 12));
        if (color != null) v.setForeground(color);
        fieldsPanel.add(k);
        fieldsPanel.add(v, "wmin 0");
    }

    private void testWithFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(Messages.get("parsereditor.test.file.title"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        List<String> lines = new ArrayList<>();
        try (var reader = LogReaders.open(chooser.getSelectedFile().toPath(), null)) {
            String line;
            while ((line = reader.readLine()) != null && lines.size() < FILE_TEST_LINES) {
                if (!line.isBlank()) lines.add(line);
            }
        } catch (IOException e) {
            status(fileTestLabel, MessageFormat.format(Messages.get("parsereditor.test.file.error"), e.getMessage()),
                    ClouseauColors.statusError());
            showUnmatched(null);
            return;
        }
        openedLog = lines;
        runFileTest();
    }

    /**
     * Tests the parser on the lines of the log (opened with the editor, or picked with "Test
     * with file…"). Lines that continue an entry (stack traces, wrapped messages) aren't
     * expected to match, and are counted apart. Shows the first line that should have matched and didn't,
     * which one click makes the sample.
     */
    private void runFileTest() {
        if (openedLog.isEmpty() || shown == null) return;
        RegexLogParser parser = previewParser();
        if (parser == null) {
            status(fileTestLabel, notReady(), ClouseauColors.statusWarn());
            showUnmatched(null);
            return;
        }
        int entries = 0, matched = 0, indented = 0;
        String unmatched = null;
        for (String line : openedLog) {
            if (isContinuation(line)) { indented++; continue; }
            entries++;
            if (parser.canParse(line)) matched++;
            else if (unmatched == null) unmatched = line;
        }
        if (entries == 0) {
            status(fileTestLabel, Messages.get("parsereditor.test.file.empty"), ClouseauColors.statusWarn());
            showUnmatched(null);
            return;
        }
        int pct = matched * 100 / entries;
        String text = MessageFormat.format(Messages.get("parsereditor.test.file.result"), matched, entries, pct)
                + (indented > 0 ? MessageFormat.format(Messages.get("parsereditor.test.file.indented"), indented) : "");
        status(fileTestLabel, text, pct == 100 ? ClouseauColors.statusOk()
                : pct >= 80 ? ClouseauColors.statusWarn() : ClouseauColors.statusError());
        showUnmatched(unmatched);
    }

    /** A stack trace's exception line. */
    private static final Pattern EXCEPTION_LINE =
            Pattern.compile("^[\\w$.]+(Exception|Error|Throwable)(:.*)?$");

    /**
     * Whether a line continues the entry above rather than starting one: indented (stack
     * frames, wrapped text), an exception's own line, "Caused by:" or "... 12 more".
     */
    static boolean isContinuation(String line) {
        if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) return true;
        return line.startsWith("Caused by:") || line.startsWith("...") || EXCEPTION_LINE.matcher(line).matches();
    }

    private void showUnmatched(String line) {
        firstUnmatched = line;
        JComponent row = (JComponent) unmatchedLine.getClientProperty("row");
        if (row == null) return;
        row.setVisible(line != null);
        if (line != null) {
            unmatchedLine.setText(MessageFormat.format(Messages.get("parsereditor.test.first.unmatched"), line));
            unmatchedLine.setToolTipText(line);
        }
        row.revalidate();
    }

    private static void status(JLabel label, String text, Color color) {
        label.setText(text);
        label.setForeground(color);
    }

    // ── Parser CRUD ──────────────────────────────────────────────────────────

    private void addParser() {
        ParserDef def = new ParserDef(uniqueName(Messages.get("parsereditor.new.name")),
                TYPE_SIMPLIFIED, "", "", "", false);
        mine.add(def);
        rebuildList(def);
        nameField.requestFocusInWindow();
        nameField.selectAll();
    }

    /** Copies a parser (yours or built in) into a new one of yours, right after it or at the end. */
    private void duplicate(ParserDef src) {
        if (src == null) return;
        ParserDef copy = new ParserDef(uniqueName(src.name() + " " + Messages.get("parsereditor.duplicate.suffix")),
                src.type(), src.source(), src.pattern(), src.timestampFormat(), false);
        int at = src.builtin() ? mine.size() : indexInMine(src) + 1;
        mine.add(at, copy);
        rebuildList(copy);
        nameField.requestFocusInWindow();
        nameField.selectAll();
    }

    private String uniqueName(String base) {
        String name = base;
        for (int n = 2; nameTaken(name); n++) name = base + " " + n;
        return name;
    }

    private boolean nameTaken(String name) {
        return allDefs().stream().anyMatch(d -> d.name().strip().equalsIgnoreCase(name.strip()));
    }

    private void deleteSelected() {
        ParserDef def = selectedDef();
        if (def != null) delete(def);
    }

    private void delete(ParserDef def) {
        int idx = indexInMine(def);
        if (idx < 0) return;
        int confirm = JOptionPane.showConfirmDialog(this,
                MessageFormat.format(Messages.get("parsereditor.delete.confirm.message"), def.name()),
                Messages.get("parsereditor.delete.confirm.title"),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        mine.remove(idx);
        rebuildList(mine.isEmpty() ? null : mine.get(Math.min(idx, mine.size() - 1)));
    }

    /** Alt+↑ / Alt+↓: moves the selected parser of yours earlier or later in the order. */
    private void move(int by) {
        ParserDef def = selectedDef();
        int from = def != null ? indexInMine(def) : -1;
        if (from < 0) return;
        moveTo(def, from + by);
    }

    private void moveTo(ParserDef def, int to) {
        int from = indexInMine(def);
        if (from < 0) return;
        to = Math.max(0, Math.min(to, mine.size() - 1));
        if (to == from) return;
        mine.remove(from);
        mine.add(to, def);
        rebuildList(def);
    }

    // ── Footer ───────────────────────────────────────────────────────────────

    private JPanel buildButtonBar() {
        JButton saveBtn   = new JButton(Messages.get("settings.button.ok"));
        JButton cancelBtn = new JButton(Messages.get("settings.button.cancel"));
        saveBtn.addActionListener(e -> save());
        cancelBtn.addActionListener(e -> cancel());
        getRootPane().setDefaultButton(saveBtn);     // amber, like OK in every dialog
        getRootPane().registerKeyboardAction(e -> cancel(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        return DialogFooter.of(cancelBtn, saveBtn);  // Cancel before the default button
    }

    /** Cancel, Esc or the window's close box: asks before dropping changes. */
    private void cancel() {
        if (mine.equals(loaded)) { dispose(); return; }
        Object[] options = { Messages.get("parsereditor.discard.save"), Messages.get("parsereditor.discard.discard"),
                Messages.get("parsereditor.discard.keep") };
        int answer = JOptionPane.showOptionDialog(this, Messages.get("parsereditor.discard.message"),
                Messages.get("parsereditor.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (answer == 0) save();
        else if (answer == 1) dispose();
    }

    /**
     * Saves your parsers, in order, and closes. A parser with a problem (no name, a name
     * already used, a template or regex that doesn't work) would be skipped when parsers
     * load, so it's shown instead, with its problem, and nothing is saved.
     */
    private void save() {
        for (ParserDef def : mine) {
            Problems p = problems(def);
            if (p.any()) {
                parserList.setSelectedValue(def, true);
                JOptionPane.showMessageDialog(this,
                        MessageFormat.format(Messages.get("parsereditor.save.problem"), def.name().isBlank() ? "–" : def.name(), p.first()),
                        Messages.get("parsereditor.title"), JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        JsonArray arr = new JsonArray();
        for (ParserDef def : mine) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name",           def.name());
            obj.addProperty("type",           def.type());
            if (!def.source().isBlank())
                obj.addProperty("source",     def.source());
            obj.addProperty("pattern",        def.pattern());
            obj.addProperty("timestampFormat", def.timestampFormat());
            arr.add(obj);
        }
        try {
            Files.createDirectories(USER_FILE.getParent());
            try (Writer w = Files.newBufferedWriter(USER_FILE)) {
                GSON.toJson(arr, w);
            }
            if (onSave != null) onSave.run();
            dispose();
        } catch (IOException e) {
            log.error("Could not save parsers.json", e);
            JOptionPane.showMessageDialog(this,
                    MessageFormat.format(Messages.get("parsereditor.save.error"), e.getMessage()),
                    Messages.get("parsereditor.save.error.title"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── The parser list ──────────────────────────────────────────────────────

    /**
     * The sidebar list: group titles, your parsers (draggable to reorder, with duplicate and
     * trash can on the row under the mouse) and the built-in ones (duplicate only).
     */
    private final class ParserList extends JList<Object> {
        private static final int TOOL = 24;
        private enum Tool { NONE, DUPLICATE, TRASH }
        private int hoverRow = -1;
        private Tool overTool = Tool.NONE;
        private ParserDef dragging;
        /** The parser of yours pressed on, and where: it starts dragging once the mouse moves a little. */
        private ParserDef pressed;
        private Point pressedAt;
        private int dropRow = -1;            // the row of yours the dragged one would go before; size = after the last

        ParserList() {
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e)  { track(e.getPoint()); }
                @Override public void mouseExited(MouseEvent e) { hoverRow = -1; overTool = Tool.NONE; repaint(); }
                @Override public void mouseReleased(MouseEvent e) {
                    ParserDef moved = dragging;
                    int to = dropRow;
                    dragging = null;
                    pressed = null;
                    dropRow = -1;
                    if (moved != null && to >= 0) {
                        int from = indexInMine(moved);
                        moveTo(moved, to > from ? to - 1 : to);
                    }
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private void track(Point p) {
            int row = locationToIndex(p);
            Rectangle r = row >= 0 ? getCellBounds(row, row) : null;
            int newRow = r != null && r.contains(p) && listModel.get(row) instanceof ParserDef ? row : -1;
            Tool tool = Tool.NONE;
            if (newRow >= 0) {
                boolean builtin = ((ParserDef) listModel.get(newRow)).builtin();
                if (!builtin && p.x >= getWidth() - TOOL) tool = Tool.TRASH;
                else if (p.x >= getWidth() - (builtin ? 1 : 2) * TOOL) tool = Tool.DUPLICATE;
            }
            if (newRow != hoverRow || tool != overTool) {
                hoverRow = newRow;
                overTool = tool;
                repaint();
            }
        }

        @Override protected void processMouseEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(e)) {
                track(e.getPoint());
                if (overTool != Tool.NONE && hoverRow >= 0) {
                    ParserDef def = (ParserDef) listModel.get(hoverRow);
                    Tool tool = overTool;
                    e.consume();
                    SwingUtilities.invokeLater(() -> { if (tool == Tool.TRASH) delete(def); else duplicate(def); });
                    return;
                }
                int row = locationToIndex(e.getPoint());
                pressed = row >= 0 && listModel.get(row) instanceof ParserDef d && !d.builtin() ? d : null;
                pressedAt = e.getPoint();
            }
            if (e.getID() == MouseEvent.MOUSE_CLICKED && overTool != Tool.NONE) return;
            super.processMouseEvent(e);
        }

        /**
         * Dragging one of yours reorders; the list's own handling (which would move the
         * selection along) doesn't see that drag.
         */
        @Override protected void processMouseMotionEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_DRAGGED && pressed != null) {
                if (dragging == null && pressedAt.distance(e.getPoint()) > 4) dragging = pressed;
                if (dragging != null) {
                    int row = locationToIndex(e.getPoint());
                    Rectangle r = row >= 0 ? getCellBounds(row, row) : null;
                    int target;
                    if (r == null || e.getY() > r.y + r.height) target = mine.size();
                    else {
                        Object v = listModel.get(row);
                        int i = v instanceof ParserDef d ? indexInMine(d) : -1;
                        if (i < 0) target = row <= 0 ? 0 : mine.size();
                        else target = e.getY() < r.y + r.height / 2 ? i : i + 1;
                    }
                    if (target != dropRow) { dropRow = target; repaint(); }
                }
                return;
            }
            super.processMouseMotionEvent(e);
        }

        @Override public String getToolTipText(MouseEvent e) {
            track(e.getPoint());
            if (overTool == Tool.DUPLICATE) return Messages.get("parsereditor.duplicate.tooltip");
            if (overTool == Tool.TRASH) return Messages.get("parsereditor.delete.tooltip");
            int row = locationToIndex(e.getPoint());
            if (row >= 0 && listModel.get(row) instanceof ParserDef d) {
                Problems p = visibleProblems(d);
                if (p.any()) return p.first();
                if (!d.builtin()) return Messages.get("parsereditor.drag.tooltip");
            }
            return null;
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (hoverRow >= 0 && hoverRow < listModel.size() && dragging == null) {
                Rectangle r = getCellBounds(hoverRow, hoverRow);
                boolean builtin = ((ParserDef) listModel.get(hoverRow)).builtin();
                Color base = isSelectedIndex(hoverRow) ? UIManager.getColor("List.selectionForeground") : ClouseauColors.dimForeground();
                int y = r.y + (r.height - 14) / 2;
                if (!builtin) ToolIcons.paintTrash(g2, getWidth() - TOOL + 5, y, overTool == Tool.TRASH
                        ? ClouseauColors.levelColor(LogEntry.LogLevel.ERROR) : base);
                ToolIcons.paintDuplicate(g2, getWidth() - (builtin ? 1 : 2) * TOOL + 5, y,
                        overTool == Tool.DUPLICATE ? ClouseauColors.foreground() : base);
            }
            if (dragging != null && dropRow >= 0) {
                // Where the dragged parser would land: a line between two of yours.
                int y;
                if (mine.isEmpty()) y = 0;
                else if (dropRow < mine.size()) y = getCellBounds(rowOf(mine.get(dropRow)), rowOf(mine.get(dropRow))).y;
                else { Rectangle last = getCellBounds(rowOf(mine.get(mine.size() - 1)), rowOf(mine.get(mine.size() - 1))); y = last.y + last.height; }
                g2.setColor(UIManager.getColor("Component.focusColor"));
                g2.fillRect(4, y - 1, getWidth() - 8, 2);
            }
            g2.dispose();
        }
    }

    /** Group titles small and dim; parsers by name, a problem marked with a red dot. */
    private final class ParserRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
            if (v instanceof String title) {
                super.getListCellRendererComponent(l, title, i, false, false);
                setFont(getFont().deriveFont(Font.PLAIN, 11f));
                setForeground(ClouseauColors.dimForeground());
                setBorder(BorderFactory.createEmptyBorder(i == 0 ? 2 : 12, 4, 4, 4));
                return this;
            }
            if (v instanceof EmptyMine) {
                super.getListCellRendererComponent(l, Messages.get("parsereditor.group.mine.empty"), i, false, false);
                setForeground(ClouseauColors.mutedForeground());
                setFont(getFont().deriveFont(Font.ITALIC));
                setBorder(BorderFactory.createEmptyBorder(3, 12, 3, 4));
                return this;
            }
            ParserDef d = (ParserDef) v;
            String name = d == shown && !d.builtin() ? nameField.getText().strip() : d.name();
            super.getListCellRendererComponent(l, name.isEmpty() ? "–" : name, i, sel, false);
            setBorder(BorderFactory.createEmptyBorder(3, 12, 3, 52));
            if (!sel) setForeground(d.builtin() ? ClouseauColors.dimForeground() : l.getForeground());
            setIcon(!d.builtin() && visibleProblems(d).any() ? PROBLEM_DOT : null);
            setHorizontalTextPosition(LEADING);
            setIconTextGap(6);
            return this;
        }
    }

    /** A small red dot after the name of a parser with a problem. */
    private static final Icon PROBLEM_DOT = new Icon() {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(ClouseauColors.statusError());
            g2.fillOval(x, y + 1, 6, 6);
            g2.dispose();
        }
        @Override public int getIconWidth()  { return 6; }
        @Override public int getIconHeight() { return 8; }
    };

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static JLabel dimLabel(String text) {
        JLabel l = new JLabel(text);
        ClouseauColors.themedForeground(l, ClouseauColors::dimForeground);
        return l;
    }

    /** Dim text that wraps to the width it's given, for hints. */
    private static JTextArea wrapText(String text) {
        JTextArea t = new JTextArea(text);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setBorder(null);
        t.setFont(UIManager.getFont("Label.font").deriveFont(11.5f));
        ClouseauColors.themedForeground(t, ClouseauColors::dimForeground);
        return t;
    }

    /** The form: as wide as the window, so hints wrap instead of scrolling sideways. */
    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel(LayoutManager layout) { super(layout); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(16, r.height - 16); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport v && v.getHeight() >= getPreferredSize().height;
        }
    }

    private static JLabel errorLabel() {
        JLabel l = new JLabel();
        ClouseauColors.themedForeground(l, ClouseauColors::statusError);
        l.setFont(l.getFont().deriveFont(12f));
        l.setVisible(false);
        return l;
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) if (v != null) return v;
        return null;
    }

    private static DocumentListener docListener(Runnable r) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { r.run(); }
            public void removeUpdate(DocumentEvent e)  { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }
}
