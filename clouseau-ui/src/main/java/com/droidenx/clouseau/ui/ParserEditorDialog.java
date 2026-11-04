package com.droidenx.clouseau.ui;

import com.google.gson.*;
import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.core.RegexLogParser;
import com.droidenx.clouseau.core.LogReaders;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    // Status and dim colors are read from UIManager via ClouseauColors at use time.

    private final Runnable onSave;
    private final DefaultListModel<ParserDef> listModel  = new DefaultListModel<>();
    private final JList<ParserDef>            parserList;
    private final JButton                     deleteBtn      = new JButton("\u2212");
    private final JButton                     duplicateBtn   = new JButton("\u29c9");

    // ── Form ─────────────────────────────────────────────────────────────────
    private final JTextField        nameField    = new JTextField();
    private final JComboBox<String> typeCombo    = new JComboBox<>(new String[]{
            Messages.get("parsereditor.type.template"),
            Messages.get("parsereditor.type.pattern"),
            Messages.get("parsereditor.type.regex") });
    private final JLabel    sourceLabel  = dimLabel(Messages.get("parsereditor.field.source"));
    private final JTextArea sourceArea   = new JTextArea(3, 1) {
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(ClouseauColors.dimForeground());
                g2.setFont(getFont().deriveFont(Font.ITALIC));
                Insets ins = getInsets();
                g2.drawString(Messages.get("parsereditor.source.placeholder"), ins.left + 2, ins.top + g2.getFontMetrics().getAscent());
                g2.dispose();
            }
        }
    };
    private       JScrollPane sourceScroll;
    private       JButton     helpBtn;
    private final JLabel    patternLabel = dimLabel(Messages.get("parsereditor.field.pattern"));
    private final JTextArea patternArea  = new JTextArea(4, 1);
    private final JLabel    regexWarning = new JLabel(Messages.get("parsereditor.type.regex.warning"));
    private final JLabel    formatLabel  = dimLabel(Messages.get("parsereditor.field.format"));
    private final JTextField formatField  = new JTextField();

    // ── Test ─────────────────────────────────────────────────────────────────
    private final JTextField sampleField   = new JTextField();
    private final JLabel     matchLabel    = new JLabel(" ");
    private final JPanel     fieldsPanel   = new JPanel(new MigLayout("insets 0, wrap 2, gapy 2", "[right 90!][left, grow]"));
    private final JLabel     fileTestLabel = new JLabel(" ");
    private       JButton    fileBtn;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean updatingForm       = false;
    private int     previousIndex      = -1;
    private String  conversionErrorMsg = null;

    public ParserEditorDialog(Frame owner, Runnable onSave) {
        super(owner, Messages.get("parsereditor.title"), true);
        this.onSave = onSave;
        loadDefs();
        parserList = buildParserJList();

        setLayout(new MigLayout("fill, insets 12, gap 10", "[200!][grow]", "[grow][]"));
        add(buildListPanel(), "grow");
        add(buildEditPanel(), "grow, wrap");
        add(buildButtonBar(), "span 2, growx");

        setSize(900, 680);
        setMinimumSize(new Dimension(720, 540));
        setLocationRelativeTo(owner);

        // List selection
        parserList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                flushFormAt(previousIndex);
                previousIndex = parserList.getSelectedIndex();
                populateForm(parserList.getSelectedValue());
            }
        });

        // Type selector
        typeCombo.addActionListener(e -> {
            if (!updatingForm) {
                flushForm();
                String type = selectedType();
                updateTypeUI(type);
                patternArea.setEditable(TYPE_REGEX.equals(type));
                convertAndUpdate();
            }
        });

        // Document listeners
        sourceArea.getDocument().addDocumentListener(docListener(() -> {
            if (!updatingForm) { convertAndUpdate(); flushForm(); }
        }));
        patternArea.getDocument().addDocumentListener(docListener(() -> {
            if (!updatingForm && TYPE_REGEX.equals(selectedType())) { flushForm(); runTest(); }
        }));
        formatField.getDocument().addDocumentListener(docListener(() -> {
            if (!updatingForm) { flushForm(); runTest(); }
        }));
        sampleField.getDocument().addDocumentListener(docListener(() -> {
            if (!updatingForm) runTest();
        }));
        nameField.getDocument().addDocumentListener(docListener(() -> {
            if (!updatingForm) flushForm();
        }));

        if (!listModel.isEmpty()) parserList.setSelectedIndex(0);
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadDefs() {
        listModel.clear();
        loadArray(readResource(), true);
        loadArray(readUserFile(), false);
    }

    private void loadArray(JsonArray arr, boolean builtin) {
        if (arr == null) return;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String name = str(o, "name"), pat = str(o, "pattern"), fmt = str(o, "timestampFormat");
            if (name == null || pat == null || fmt == null) continue;
            String type   = firstNonNull(str(o, "type"),   TYPE_REGEX);
            String source = firstNonNull(str(o, "source"), "");
            listModel.addElement(new ParserDef(name, type, source, pat, fmt, builtin));
        }
    }

    private JsonArray readResource() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(DEFAULT_RESOURCE)) {
            if (is == null) return null;
            JsonElement el = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            return el.isJsonArray() ? el.getAsJsonArray() : null;
        } catch (IOException e) { return null; }
    }

    private JsonArray readUserFile() {
        if (!Files.exists(USER_FILE)) return null;
        try (Reader r = Files.newBufferedReader(USER_FILE)) {
            JsonElement el = JsonParser.parseReader(r);
            return el.isJsonArray() ? el.getAsJsonArray() : null;
        } catch (IOException e) { return null; }
    }

    // ── List panel ────────────────────────────────────────────────────────────

    private JList<ParserDef> buildParserJList() {
        JList<ParserDef> list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, foc);
                ParserDef def = (ParserDef) v;
                setText(def.builtin() ? def.name() + "  ·" : def.name());
                if (def.builtin() && !sel) setForeground(ClouseauColors.dimForeground());
                return this;
            }
        });
        return list;
    }

    private JPanel buildListPanel() {
        JPanel panel = new JPanel(new MigLayout("fill, insets 0, wrap 1", "[grow]", "[grow][]"));
        panel.add(new JScrollPane(parserList), "grow");

        JButton addBtn = new JButton("+");
        addBtn.setFont(addBtn.getFont().deriveFont(Font.BOLD));
        addBtn.setToolTipText(Messages.get("parsereditor.add.tooltip"));
        addBtn.addActionListener(e -> addParser());
        deleteBtn.setFont(deleteBtn.getFont().deriveFont(Font.BOLD));
        deleteBtn.setEnabled(false);
        deleteBtn.setToolTipText(Messages.get("parsereditor.delete.tooltip"));
        deleteBtn.addActionListener(e -> deleteSelected());
        duplicateBtn.setFont(duplicateBtn.getFont().deriveFont(Font.BOLD));
        duplicateBtn.setEnabled(false);
        duplicateBtn.setToolTipText(Messages.get("parsereditor.duplicate.tooltip"));
        duplicateBtn.addActionListener(e -> duplicateParser());

        JPanel btns = new JPanel(new MigLayout("insets 0", "[][]push[]"));
        btns.add(addBtn);
        btns.add(duplicateBtn);
        btns.add(deleteBtn);
        panel.add(btns, "growx");
        return panel;
    }

    // ── Edit + test panel ─────────────────────────────────────────────────────

    private JPanel buildEditPanel() {
        JPanel panel = new JPanel(new MigLayout("fill, insets 0, wrap 1, gapy 8", "[grow]", "[][][grow]"));

        // Form
        JPanel form = new JPanel(new MigLayout("insets 0, wrap 2, gapy 4", "[right][grow, fill]"));

        form.add(dimLabel(Messages.get("parsereditor.field.name")));
        form.add(nameField);

        form.add(dimLabel(Messages.get("parsereditor.field.type")));
        helpBtn = new JButton("?");
        helpBtn.setFont(helpBtn.getFont().deriveFont(11f));
        helpBtn.setToolTipText(Messages.get("parsereditor.template.help.tooltip"));
        helpBtn.addActionListener(e -> showTemplateHelp());
        JPanel typeRow = new JPanel(new MigLayout("insets 0, gap 4", "[grow][]"));
        typeRow.add(typeCombo, "grow");
        typeRow.add(helpBtn, "hidemode 3");
        form.add(typeRow);

        regexWarning.setForeground(ClouseauColors.mutedForeground());
        regexWarning.setFont(regexWarning.getFont().deriveFont(Font.ITALIC, 11f));
        form.add(regexWarning, "span 2, hidemode 3");

        sourceArea.setLineWrap(true);
        sourceArea.setWrapStyleWord(false);
        sourceArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        sourceScroll = new JScrollPane(sourceArea);
        form.add(sourceLabel, "hidemode 3");
        form.add(sourceScroll, "hmin 60, grow, hidemode 3");

        patternArea.setLineWrap(true);
        patternArea.setWrapStyleWord(false);
        patternArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        form.add(patternLabel);
        form.add(new JScrollPane(patternArea), "hmin 80, grow");

        form.add(formatLabel, "hidemode 3");
        form.add(formatField, "hidemode 3");

        panel.add(form, "growx");

        // Test panel
        JPanel testPanel = new JPanel(new MigLayout("insets 8, wrap 1, gapy 6", "[grow]"));
        testPanel.setBorder(BorderFactory.createTitledBorder(Messages.get("parsereditor.test.title")));

        JPanel sampleRow = new JPanel(new MigLayout("insets 0", "[right]8[grow, fill]"));
        sampleRow.add(dimLabel(Messages.get("parsereditor.test.sample")));
        sampleRow.add(sampleField);
        testPanel.add(sampleRow, "growx");

        matchLabel.setFont(matchLabel.getFont().deriveFont(Font.BOLD, 12f));
        fieldsPanel.setOpaque(false);
        testPanel.add(matchLabel);
        testPanel.add(fieldsPanel, "growx");

        fileBtn = new JButton(Messages.get("parsereditor.test.file"));
        fileBtn.addActionListener(e -> testWithFile());
        fileTestLabel.setFont(fileTestLabel.getFont().deriveFont(12f));
        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        fileRow.setOpaque(false);
        fileRow.add(fileBtn);
        fileRow.add(fileTestLabel);
        testPanel.add(new JSeparator(), "growx");
        testPanel.add(fileRow, "growx");

        panel.add(testPanel, "grow");
        return panel;
    }

    // ── Type UI ───────────────────────────────────────────────────────────────

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

    private void updateTypeUI(String type) {
        boolean isSourceBased = !TYPE_REGEX.equals(type);
        sourceLabel.setVisible(isSourceBased);
        sourceScroll.setVisible(isSourceBased);
        helpBtn.setVisible(TYPE_SIMPLIFIED.equals(type));
        patternLabel.setText(Messages.get(isSourceBased
                ? "parsereditor.field.generated" : "parsereditor.field.pattern"));
        regexWarning.setVisible(TYPE_REGEX.equals(type));
        formatLabel.setVisible(!isSourceBased);
        formatField.setVisible(!isSourceBased);
        getContentPane().revalidate();
        getContentPane().repaint();
    }

    // ── Form population ───────────────────────────────────────────────────────

    private void populateForm(ParserDef def) {
        updatingForm = true;
        boolean has      = def != null;
        boolean editable = has && !def.builtin();
        String  type     = has ? def.type() : TYPE_SIMPLIFIED;
        boolean isSourceBased = !TYPE_REGEX.equals(type);

        nameField.setText(has ? def.name() : "");
        if (has) setTypeCombo(def.type());
        sourceArea.setText(has ? def.source() : "");
        patternArea.setText(has ? def.pattern() : "");
        formatField.setText(has ? def.timestampFormat() : "");

        updateTypeUI(type);

        duplicateBtn.setEnabled(has);

        if (!editable) {
            nameField.setEnabled(false);
            typeCombo.setEnabled(false);
            sourceArea.setEnabled(false);
            patternArea.setEnabled(false);
            formatField.setEnabled(false);
            sampleField.setEnabled(false);
            fileBtn.setEnabled(false);
            deleteBtn.setEnabled(false);
        } else {
            nameField.setEnabled(true);
            typeCombo.setEnabled(true);
            sourceArea.setEnabled(true);
            patternArea.setEnabled(true);
            patternArea.setEditable(!isSourceBased);
            formatField.setEnabled(!isSourceBased);
            sampleField.setEnabled(true);
            fileBtn.setEnabled(true);
            deleteBtn.setEnabled(true);
        }

        clearTestResults();
        conversionErrorMsg = null;
        updatingForm = false;
        if (editable && !sampleField.getText().isBlank()) runTest();
    }

    private void flushForm() {
        flushFormAt(parserList.getSelectedIndex());
    }

    private void flushFormAt(int idx) {
        if (idx < 0 || idx >= listModel.size() || listModel.get(idx).builtin()) return;
        updatingForm = true;
        listModel.set(idx, new ParserDef(
                nameField.getText().strip(),
                selectedType(),
                sourceArea.getText().strip(),
                patternArea.getText().strip(),
                formatField.getText().strip(),
                false));
        updatingForm = false;
    }

    /**
     * Opens the editor on a new parser for a file no parser recognizes (UC-13), with its
     * first non-blank line as the sample to test against.
     */
    public ParserEditorDialog(Frame owner, Runnable onSave, java.util.List<String> samples) {
        this(owner, onSave);
        addParser();
        samples.stream().filter(s -> !s.isBlank()).findFirst().ifPresent(sampleField::setText);
    }

    // ── Parser CRUD ───────────────────────────────────────────────────────────

    private void addParser() {
        ParserDef def = new ParserDef(
                Messages.get("parsereditor.new.name"),
                TYPE_SIMPLIFIED, "", "", "", false);
        listModel.addElement(def);
        parserList.setSelectedIndex(listModel.size() - 1);
        parserList.ensureIndexIsVisible(listModel.size() - 1);
        nameField.requestFocusInWindow();
        nameField.selectAll();
    }

    private void duplicateParser() {
        ParserDef src = parserList.getSelectedValue();
        if (src == null) return;
        flushForm();
        ParserDef copy = new ParserDef(
                src.name() + " " + Messages.get("parsereditor.duplicate.suffix"),
                src.type(), src.source(), src.pattern(), src.timestampFormat(), false);
        listModel.addElement(copy);
        parserList.setSelectedIndex(listModel.size() - 1);
        parserList.ensureIndexIsVisible(listModel.size() - 1);
        nameField.requestFocusInWindow();
        nameField.selectAll();
    }

    private void deleteSelected() {
        int idx = parserList.getSelectedIndex();
        if (idx < 0 || listModel.get(idx).builtin()) return;
        String name = listModel.get(idx).name();
        int confirm = JOptionPane.showConfirmDialog(this,
                Messages.get("parsereditor.delete.confirm.message").formatted(name),
                Messages.get("parsereditor.delete.confirm.title"),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        listModel.remove(idx);
        int next = Math.min(idx, listModel.size() - 1);
        if (next >= 0) parserList.setSelectedIndex(next);
        else populateForm(null);
    }

    // ── Template help ─────────────────────────────────────────────────────────

    private void showTemplateHelp() {
        String content =
            "Template keywords — case-sensitive, must be uppercase\n\n" +
            "  TIMESTAMP{format}   Timestamp. The format uses DateTimeFormatter syntax\n" +
            "                      and is required — it tells the parser how to read\n" +
            "                      the timestamp.\n" +
            "                      e.g. TIMESTAMP{yyyy-MM-dd HH:mm:ss.SSS}\n\n" +
            "  LEVEL               Log level: TRACE, DEBUG, INFO, WARN, ERROR, FATAL\n\n" +
            "  LOGGER              Logger name (no whitespace)\n\n" +
            "  THREAD              Thread name (no whitespace)\n\n" +
            "  MESSAGE             Log message — captures the rest of the line\n\n" +
            "  PID                 Process ID (digits only)\n\n" +
            "  {field_name}        Custom field — captures \\S+ and appears as an\n" +
            "                      extra column in the log table.\n" +
            "                      e.g. {instance_id}, {request_id}\n\n" +
            "Everything between keywords is matched as a literal.\n\n" +
            "Example:\n" +
            "  TIMESTAMP{yyyy-MM-dd HH:mm:ss.SSS} [{instance_id}] [THREAD] LEVEL LOGGER - MESSAGE\n\n" +
            "Matches:\n" +
            "  2024-01-15 10:23:45.123 [550e8400-e29b] [main] INFO  com.example.App - Server started";

        JTextArea area = new JTextArea(content);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setOpaque(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(area),
                Messages.get("parsereditor.template.help.title"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    private void convertAndUpdate() {
        conversionErrorMsg = null;
        String type = selectedType();
        if (TYPE_REGEX.equals(type)) { runTest(); return; }
        String source = sourceArea.getText().strip();
        if (source.isBlank()) { runTest(); return; }
        try {
            String regex, fmt;
            if (TYPE_SIMPLIFIED.equals(type)) {
                TemplatePatternConverter.Result r = TemplatePatternConverter.convert(source);
                regex = r.regex();
                fmt   = r.timestampFormat();
            } else {
                LogbackPatternConverter.Result r = LogbackPatternConverter.convert(source);
                regex = r.regex();
                fmt   = r.timestampFormat();
            }
            updatingForm = true;
            patternArea.setText(regex);
            if (fmt != null) formatField.setText(fmt);
            updatingForm = false;
        } catch (IllegalArgumentException e) {
            updatingForm = true;
            patternArea.setText("");
            updatingForm = false;
            conversionErrorMsg = e.getMessage();
        }
        runTest();
    }

    // ── Testing ───────────────────────────────────────────────────────────────

    private void runTest() {
        clearTestResults();
        if (conversionErrorMsg != null) {
            matchLabel.setText(conversionErrorMsg);
            matchLabel.setForeground(ClouseauColors.statusError());
            return;
        }
        String line = sampleField.getText().strip();
        if (line.isBlank()) return;
        RegexLogParser parser = buildPreviewParser();
        if (parser == null) {
            matchLabel.setText(Messages.get("parsereditor.test.invalid.pattern"));
            matchLabel.setForeground(ClouseauColors.statusError());
            return;
        }
        if (!parser.canParse(line)) {
            matchLabel.setText(Messages.get("parsereditor.test.no.match"));
            matchLabel.setForeground(ClouseauColors.statusError());
            return;
        }
        matchLabel.setText(Messages.get("parsereditor.test.matched"));
        matchLabel.setForeground(ClouseauColors.statusOk());
        LogEntry entry = parser.parse(line);
        addField("timestamp", entry.timestamp() != null ? entry.timestamp().toString() : "(not parsed)");
        addField("level",     entry.level().name());
        addField("thread",    entry.thread());
        addField("logger",    entry.logger());
        addField("message",   entry.message());
        entry.fields().forEach(this::addField);
        fieldsPanel.revalidate();
        fieldsPanel.repaint();
    }

    private void testWithFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(Messages.get("parsereditor.test.file.title"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        RegexLogParser parser = buildPreviewParser();
        if (parser == null) {
            fileTestLabel.setText(Messages.get("parsereditor.test.invalid.pattern"));
            fileTestLabel.setForeground(ClouseauColors.statusError());
            return;
        }
        try (var reader = LogReaders.open(chooser.getSelectedFile().toPath(), null)) {
            int total = 0, matched = 0;
            String line;
            while ((line = reader.readLine()) != null && total < 200) {
                if (line.isBlank()) continue;
                total++;
                if (parser.canParse(line)) matched++;
            }
            if (total == 0) {
                fileTestLabel.setText(Messages.get("parsereditor.test.file.empty"));
                fileTestLabel.setForeground(ClouseauColors.statusWarn());
                return;
            }
            int pct = matched * 100 / total;
            fileTestLabel.setText(matched + " / " + total + " lines matched (" + pct + "%)");
            fileTestLabel.setForeground(pct >= 80 ? ClouseauColors.statusOk() : pct >= 20 ? ClouseauColors.statusWarn() : ClouseauColors.statusError());
        } catch (IOException e) {
            fileTestLabel.setText(Messages.get("parsereditor.test.file.error") + e.getMessage());
            fileTestLabel.setForeground(ClouseauColors.statusError());
        }
    }

    private RegexLogParser buildPreviewParser() {
        String pattern = patternArea.getText().strip();
        String format  = formatField.getText().strip();
        if (pattern.isBlank() || format.isBlank()) return null;
        try {
            return new RegexLogParser("preview", Pattern.compile(pattern), format);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private void addField(String key, String value) {
        JLabel k = new JLabel(key + ":");
        k.setForeground(ClouseauColors.dimForeground());
        k.setFont(k.getFont().deriveFont(11f));
        JLabel v = new JLabel(value != null ? value : "");
        v.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        fieldsPanel.add(k);
        fieldsPanel.add(v);
    }

    private void clearTestResults() {
        matchLabel.setText(" ");
        fieldsPanel.removeAll();
        fieldsPanel.revalidate();
        fieldsPanel.repaint();
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private JPanel buildButtonBar() {
        JPanel bar = new JPanel(new MigLayout("insets 0", "push[][]"));
        JButton saveBtn  = new JButton(Messages.get("settings.button.ok"));
        JButton closeBtn = new JButton(Messages.get("settings.button.cancel"));
        saveBtn.addActionListener(e -> save());
        closeBtn.addActionListener(e -> dispose());
        bar.add(saveBtn);
        bar.add(closeBtn);
        return bar;
    }

    private void save() {
        flushForm();
        JsonArray arr = new JsonArray();
        for (int i = 0; i < listModel.size(); i++) {
            ParserDef def = listModel.get(i);
            if (def.builtin()) continue;
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
                    Messages.get("parsereditor.save.error") + e.getMessage(),
                    Messages.get("parsereditor.save.error.title"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JLabel dimLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(ClouseauColors.dimForeground());
        l.setFont(l.getFont().deriveFont(12f));
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
