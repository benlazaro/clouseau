package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.core.AnnotationStore;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.ui.theme.ClouseauFonts;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The detail pane under a tab's table (UC-3): the selected entry's fields, its note and
 * highlight, and its message run through the formatters and syntax highlighters, with a
 * toolbar to toggle them, change the font size and copy the message.
 * <p>
 * It asks its owner what to show through a {@link Supplier}, so its own toolbar buttons can
 * redraw it. EDT only.
 */
@Slf4j
final class DetailPanel extends JPanel {

    /**
     * What to show.
     *
     * @param highlight     the entry's row highlight, or null
     * @param annotation    the entry's note, or null
     * @param continuations the lines after the entry that no parser accepted (stack traces,
     *                      wrapped lines), folded into the message
     * @param shiftMillis   the time offset of the entry's file in a merged view (D-17), already
     *                      added to its timestamp; 0 if none
     */
    record Content(LogEntry entry, Color highlight, AnnotationStore.Annotation annotation,
                   List<LogEntry> continuations, long shiftMillis) {}

    private final List<LogFormatter> formatters;
    private final List<LogSyntaxHighlighter> syntaxHighlighters;
    /** Returns what to show now, or null when nothing is selected. */
    private final Supplier<Content> current;

    private final Set<String> disabledFormatters = new HashSet<>();
    private final List<JToggleButton> formatterBtnList       = new ArrayList<>();
    private final List<JToggleButton> syntaxHighlightBtnList = new ArrayList<>();
    private JPanel formatterPanel;
    private JPanel syntaxHighlightPanel;
    private JButton copyBtn;
    private final JTextPane detailArea;

    private String  lastFormattedMessage = "";
    private int     messageFieldStart    = -1;
    private int     messageFieldEnd      = -1;
    private boolean wrapLines            = AppPrefs.isDetailWrapLines();
    private DateTimeFormatter tsFmt      = timestampFormatter();

    DetailPanel(List<LogFormatter> formatters, List<LogSyntaxHighlighter> syntaxHighlighters,
                Supplier<Content> current) {
        super(new BorderLayout());
        this.formatters = formatters;
        this.syntaxHighlighters = syntaxHighlighters;
        this.current = current;

        detailArea = new JTextPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return wrapLines;
            }
        };
        detailArea.setEditable(false);
        ClouseauColors.themedBackground(detailArea, ClouseauColors::detailBackground);
        ClouseauColors.themedForeground(detailArea, ClouseauColors::foreground);
        detailArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        add(buildToolbar(), BorderLayout.NORTH);
        JScrollPane detailScroll = new JScrollPane(detailArea);
        detailScroll.putClientProperty("FlatLaf.style", ClouseauColors.NO_FOCUS_FRAME);
        add(detailScroll, BorderLayout.CENTER);
        render(null);
    }

    /** Redraws from the owner's current selection. */
    void refresh() {
        render(current.get());
    }

    void setWrapLines(boolean wrap) {
        this.wrapLines = wrap;
        detailArea.revalidate();
        refresh();
    }

    /** Re-reads the display timezone preference and redraws. */
    void refreshTimezone() {
        tsFmt = timestampFormatter();
        refresh();
    }

    private static DateTimeFormatter timestampFormatter() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(AppPrefs.getDisplayTimezone());
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ClouseauColors.separatorColor()));

        // Left: formatter / syntax highlight toggles
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        controls.setOpaque(false);

        formatterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        formatterPanel.setOpaque(false);
        formatterPanel.setVisible(false);
        if (!formatters.isEmpty()) {
            formatterPanel.add(toolbarLabel(Messages.get("detail.toolbar.format")));
            for (LogFormatter formatter : formatters) {
                JToggleButton btn = toggle(formatter.getName());
                btn.addActionListener(e -> {
                    if (btn.isSelected()) disabledFormatters.remove(formatter.getName());
                    else                  disabledFormatters.add(formatter.getName());
                    refresh();
                });
                formatterPanel.add(btn);
                formatterBtnList.add(btn);
            }
        }
        controls.add(formatterPanel);

        syntaxHighlightPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        syntaxHighlightPanel.setOpaque(false);
        syntaxHighlightPanel.setVisible(false);
        if (!syntaxHighlighters.isEmpty()) {
            if (!formatters.isEmpty()) syntaxHighlightPanel.add(verticalSeparator());
            syntaxHighlightPanel.add(toolbarLabel(Messages.get("detail.toolbar.color")));
            for (LogSyntaxHighlighter hl : syntaxHighlighters) {
                JToggleButton btn = toggle(hl.getName());
                btn.addActionListener(e -> refresh());
                syntaxHighlightPanel.add(btn);
                syntaxHighlightBtnList.add(btn);
            }
        }
        controls.add(syntaxHighlightPanel);

        bar.add(controls, BorderLayout.CENTER);

        // Right: font size + copy button
        copyBtn = new JButton(Messages.get("detail.toolbar.copy"));
        copyBtn.setFont(copyBtn.getFont().deriveFont(11f));
        copyBtn.setMargin(new Insets(2, 6, 2, 6));
        copyBtn.setEnabled(false);
        copyBtn.addActionListener(e -> {
            if (lastFormattedMessage.isEmpty()) return;
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(lastFormattedMessage), null);
            flashMessageField();
        });

        JButton fontDecBtn = new JButton("A⁻");
        JButton fontIncBtn = new JButton("A⁺");
        for (JButton btn : new JButton[]{fontDecBtn, fontIncBtn}) {
            btn.setFont(btn.getFont().deriveFont(11f));
            btn.setMargin(new Insets(2, 5, 2, 5));
        }
        fontDecBtn.setToolTipText("Decrease font size");
        fontIncBtn.setToolTipText("Increase font size");
        fontDecBtn.addActionListener(e -> {
            AppPrefs.setDetailFontSize(Math.max(8, AppPrefs.getDetailFontSize() - 1));
            refresh();
        });
        fontIncBtn.addActionListener(e -> {
            AppPrefs.setDetailFontSize(Math.min(32, AppPrefs.getDetailFontSize() + 1));
            refresh();
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 3));
        right.setOpaque(false);
        right.add(fontDecBtn);
        right.add(fontIncBtn);
        right.add(verticalSeparator());
        right.add(copyBtn);
        bar.add(right, BorderLayout.EAST);

        return bar;
    }

    private static JLabel toolbarLabel(String text) {
        JLabel label = new JLabel(text);
        ClouseauColors.themedForeground(label, ClouseauColors::dimForeground);
        label.setFont(label.getFont().deriveFont(11f));
        return label;
    }

    private static JToggleButton toggle(String name) {
        JToggleButton btn = new JToggleButton(name);
        btn.setSelected(true);
        btn.setEnabled(false);
        btn.setFont(btn.getFont().deriveFont(11f));
        FilterBar.applyToggleStyle(btn);
        return btn;
    }

    private static JSeparator verticalSeparator() {
        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 18));
        return sep;
    }

    private static void disableDetailBtn(JToggleButton btn) {
        if (btn.isEnabled()) {
            btn.putClientProperty("savedSelected", btn.isSelected());
        }
        btn.setSelected(false);
        btn.setEnabled(false);
    }

    private static void enableDetailBtn(JToggleButton btn) {
        if (!btn.isEnabled()) {
            btn.setEnabled(true);
            Boolean saved = (Boolean) btn.getClientProperty("savedSelected");
            btn.setSelected(saved != null ? saved : true);
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /** Apply all formatters that can handle this input, in sequence. */
    private String applyFormatters(String input) {
        String result = input;
        for (LogFormatter formatter : formatters) {
            if (formatter.canFormat(result) && !disabledFormatters.contains(formatter.getName())) {
                try {
                    result = formatter.format(result);
                } catch (Exception e) {
                    log.warn("Formatter {} failed", formatter.getName(), e);
                }
            }
        }
        return result;
    }

    private void render(Content content) {
        LogEntry entry = content != null ? content.entry() : null;
        copyBtn.setEnabled(entry != null);
        StyledDocument doc = detailArea.getStyledDocument();
        try { doc.remove(0, doc.getLength()); } catch (BadLocationException ignored) {}

        int fontSize = AppPrefs.getDetailFontSize();

        SimpleAttributeSet key = new SimpleAttributeSet();
        StyleConstants.setFontFamily(key, ClouseauFonts.MONO_FAMILY);
        StyleConstants.setFontSize(key, fontSize);
        StyleConstants.setBold(key, true);
        StyleConstants.setForeground(key, ClouseauColors.detailKeyColor());

        SimpleAttributeSet val = new SimpleAttributeSet();
        StyleConstants.setFontFamily(val, ClouseauFonts.MONO_FAMILY);
        StyleConstants.setFontSize(val, fontSize);
        StyleConstants.setForeground(val, ClouseauColors.foreground());

        if (entry == null) {
            lastFormattedMessage = "";
            messageFieldStart = messageFieldEnd = -1;
            formatterPanel.setVisible(false);
            syntaxHighlightPanel.setVisible(false);
            insertText(doc, Messages.get("detail.placeholder"), val);
            detailArea.setCaretPosition(0);
            return;
        }

        SimpleAttributeSet levelVal = new SimpleAttributeSet(val);
        StyleConstants.setForeground(levelVal, ClouseauColors.levelColor(entry.level()));
        StyleConstants.setBold(levelVal, true);

        String ts     = entry.timestamp() != null ? tsFmt.format(entry.timestamp()) : "";
        String level  = entry.level()     != null ? entry.level().name()  : "";
        String thread = entry.thread()    != null ? entry.thread()        : "";
        String logger = entry.logger()    != null ? entry.logger()        : "";

        if (content.highlight() != null) {
            SimpleAttributeSet swatchStyle = new SimpleAttributeSet();
            StyleConstants.setFontFamily(swatchStyle, ClouseauFonts.MONO_FAMILY);
            StyleConstants.setFontSize(swatchStyle, fontSize);
            StyleConstants.setForeground(swatchStyle, content.highlight());
            appendField(doc, key, swatchStyle, Messages.get("detail.highlight.label"), "██████");
        }

        AnnotationStore.Annotation annotation = content.annotation();
        if (annotation != null) {
            SimpleAttributeSet noteKey = new SimpleAttributeSet(key);
            StyleConstants.setForeground(noteKey, ClouseauColors.annotationIndicatorColor());
            SimpleAttributeSet noteVal = new SimpleAttributeSet(val);
            StyleConstants.setForeground(noteVal, ClouseauColors.annotationIndicatorColor());
            SimpleAttributeSet noteMeta = new SimpleAttributeSet(val);
            StyleConstants.setFontSize(noteMeta, Math.max(10, fontSize - 1));
            StyleConstants.setForeground(noteMeta, ClouseauColors.dimForeground());
            appendField(doc, noteKey, noteVal, "✎ " + Messages.get("annotation.detail.label"), annotation.note());
            String byLine = "  " + Messages.get("annotation.detail.by") + " " + annotation.author()
                    + (annotation.createdAt() != null ? "  ·  " + annotation.createdAt().replace("T", " ").replaceFirst("\\.\\d+Z$", " UTC") : "");
            insertText(doc, byLine + "\n", noteMeta);
        }

        appendField(doc, key, val,      Messages.get("table.col.timestamp"), ts);
        if (content.shiftMillis() != 0 && entry.timestamp() != null) {
            // The table shows the corrected time; say what the file itself wrote.
            SimpleAttributeSet dim = new SimpleAttributeSet(val);
            StyleConstants.setForeground(dim, ClouseauColors.dimForeground());
            appendField(doc, key, dim, Messages.get("detail.shifted.label"), java.text.MessageFormat.format(
                    Messages.get("detail.shifted"), com.droidenx.clouseau.core.TimeOffset.format(content.shiftMillis()),
                    tsFmt.format(entry.timestamp().minusMillis(content.shiftMillis()))));
        }
        appendField(doc, key, levelVal, Messages.get("table.col.level"),     level);
        appendField(doc, key, val,      Messages.get("table.col.thread"),    thread);
        appendField(doc, key, val,      Messages.get("table.col.logger"),    logger);

        if (entry.fields() != null) {
            entry.fields().forEach((k, v) -> appendField(doc, key, val, k, v));
        }

        formatterPanel.setVisible(!formatters.isEmpty());
        syntaxHighlightPanel.setVisible(!syntaxHighlighters.isEmpty());

        // Fold continuation lines (HL7 segments, stack traces, wrapped lines) into the message
        // so they are included in formatting, syntax highlighting, and the copy action.
        String rawMsg = entry.message() != null ? entry.message() : "";
        if (content.continuations() != null && !content.continuations().isEmpty()) {
            StringBuilder sb = new StringBuilder(rawMsg);
            for (LogEntry c : content.continuations()) sb.append("\n").append(c.rawLine());
            rawMsg = sb.toString();
        }

        for (int i = 0; i < formatters.size(); i++) {
            if (formatters.get(i).canFormat(rawMsg)) enableDetailBtn(formatterBtnList.get(i));
            else                                     disableDetailBtn(formatterBtnList.get(i));
        }

        String messageText = applyFormatters(rawMsg);
        lastFormattedMessage = messageText;

        for (int i = 0; i < syntaxHighlighters.size(); i++) {
            if (syntaxHighlighters.get(i).canHighlight(messageText)) enableDetailBtn(syntaxHighlightBtnList.get(i));
            else                                                      disableDetailBtn(syntaxHighlightBtnList.get(i));
        }

        LogSyntaxHighlighter colorizer = null;
        for (int i = 0; i < syntaxHighlighters.size(); i++) {
            if (syntaxHighlighters.get(i).canHighlight(messageText) && syntaxHighlightBtnList.get(i).isSelected()) {
                colorizer = syntaxHighlighters.get(i);
                break;
            }
        }
        String msgLabel = String.format("%-12s ", Messages.get("table.col.message") + ":");
        messageFieldStart = doc.getLength() + msgLabel.length();
        if (colorizer != null) {
            renderSyntaxHighlightedField(doc, key, val, Messages.get("table.col.message"), messageText, colorizer);
        } else {
            appendField(doc, key, val, Messages.get("table.col.message"), messageText);
        }
        messageFieldEnd = doc.getLength() - 1; // exclude trailing newline

        detailArea.setCaretPosition(0);
    }

    /** Briefly lights up the message after it's copied. */
    private void flashMessageField() {
        if (messageFieldStart < 0 || messageFieldEnd <= messageFieldStart) return;
        final Color peak = ClouseauColors.flashPeakColor();
        final Color bg   = ClouseauColors.detailBackground();
        final int   steps = 25;
        final int[] step  = {0};
        final Object[] tag = {null};

        Highlighter hl = detailArea.getHighlighter();
        try {
            tag[0] = hl.addHighlight(messageFieldStart, messageFieldEnd,
                    new DefaultHighlighter.DefaultHighlightPainter(peak));
        } catch (BadLocationException ignored) { return; }

        Timer timer = new Timer(16, null);
        timer.addActionListener(e -> {
            step[0]++;
            hl.removeHighlight(tag[0]);
            if (step[0] >= steps) {
                ((Timer) e.getSource()).stop();
            } else {
                float t = 1f - (float) step[0] / steps;
                try {
                    tag[0] = hl.addHighlight(messageFieldStart, messageFieldEnd,
                            new DefaultHighlighter.DefaultHighlightPainter(blend(peak, bg, t)));
                } catch (BadLocationException ignored) {}
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(
                (int)(a.getRed()   * t + b.getRed()   * (1 - t)),
                (int)(a.getGreen() * t + b.getGreen() * (1 - t)),
                (int)(a.getBlue()  * t + b.getBlue()  * (1 - t))
        );
    }

    private static void appendField(StyledDocument doc, SimpleAttributeSet keyStyle,
                                    SimpleAttributeSet valStyle, String label, String value) {
        insertText(doc, String.format("%-12s ", label + ":"), keyStyle);
        insertText(doc, value + "\n", valStyle);
    }

    private static void renderSyntaxHighlightedField(StyledDocument doc, SimpleAttributeSet keyStyle,
                                                     SimpleAttributeSet baseStyle, String label,
                                                     String text, LogSyntaxHighlighter colorizer) {
        insertText(doc, String.format("%-12s ", label + ":"), keyStyle);
        List<LogSyntaxHighlighter.ColorSpan> spans;
        try {
            spans = colorizer.highlight(text);
        } catch (Exception e) {
            insertText(doc, text + "\n", baseStyle);
            return;
        }
        int pos = 0;
        for (LogSyntaxHighlighter.ColorSpan span : spans) {
            if (span.start() > pos) {
                insertText(doc, text.substring(pos, span.start()), baseStyle);
            }
            SimpleAttributeSet spanStyle = new SimpleAttributeSet(baseStyle);
            StyleConstants.setForeground(spanStyle, new Color(span.rgb()));
            insertText(doc, text.substring(span.start(), span.end()), spanStyle);
            pos = span.end();
        }
        if (pos < text.length()) {
            insertText(doc, text.substring(pos), baseStyle);
        }
        insertText(doc, "\n", baseStyle);
    }

    private static void insertText(StyledDocument doc, String text, SimpleAttributeSet style) {
        try { doc.insertString(doc.getLength(), text, style); } catch (BadLocationException ignored) {}
    }
}
