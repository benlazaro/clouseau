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
 * highlight, and its message run through the formatters and syntax highlighters.
 * <p>
 * Its header belongs to the pane, not to the divider above it: it has the pane's own
 * background, a title ("Details · line 41") on the left, and on the right one toggle per
 * message format (formatter and highlighter of the same name together), Wrap, a text-size
 * stepper and a Copy split button.
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
                   List<LogEntry> continuations, long shiftMillis, Object lineNumber) {}

    private static final int MIN_FONT = 8, MAX_FONT = 36, DEFAULT_FONT = 12;

    private final List<LogFormatter> formatters;
    private final List<LogSyntaxHighlighter> syntaxHighlighters;
    /** Returns what to show now, or null when nothing is selected. */
    private final Supplier<Content> current;

    /** Formats the user switched off (by name), kept while the pane lives. */
    private final Set<String> disabledFormats = new HashSet<>();
    /** One toggle per format name, shared by the formatter and highlighter of that name. */
    private final java.util.Map<String, JToggleButton> formatToggles = new java.util.LinkedHashMap<>();
    private JToggleButton wrapToggle;
    private JLabel  titleContext;
    private JLabel  fontSizeLabel;
    private JButton resetFontBtn;
    private JLabel  sizeToast;
    private Timer   toastTimer;
    private JButton copyBtn;
    /** The body: the entry's text, or the empty state when nothing is selected. */
    private final CardLayout bodyCards = new CardLayout();
    private final JPanel     body      = new JPanel(bodyCards);
    private Content shown;
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
            // Always at least as wide and tall as the pane: with Wrap off a short entry would
            // otherwise end at its longest line, and the scroll area's own gray show beyond it.
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return wrapLines || (getParent() != null && getUI().getPreferredSize(this).width <= getParent().getWidth());
            }

            @Override
            public boolean getScrollableTracksViewportHeight() {
                return getParent() != null && getUI().getPreferredSize(this).height <= getParent().getHeight();
            }
        };
        detailArea.setEditable(false);
        ClouseauColors.themedBackground(detailArea, ClouseauColors::detailBackground);
        ClouseauColors.themedForeground(detailArea, ClouseauColors::foreground);
        detailArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        add(buildHeader(), BorderLayout.NORTH);
        installKeys();
        installContextMenu();
        JScrollPane detailScroll = new JScrollPane(detailArea);
        detailScroll.putClientProperty("FlatLaf.style", ClouseauColors.NO_FOCUS_FRAME);
        ClouseauColors.themedBackground(detailScroll.getViewport(), ClouseauColors::detailBackground);
        body.add(detailScroll, "entry");
        body.add(buildEmptyState(), "empty");
        add(body, BorderLayout.CENTER);
        render(null);
    }

    /** Redraws from the owner's current selection. */
    void refresh() {
        render(current.get());
    }

    void setWrapLines(boolean wrap) {
        this.wrapLines = wrap;
        if (wrapToggle != null && wrapToggle.isSelected() != wrap) wrapToggle.setSelected(wrap);
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

    // ── Header ────────────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel bar = new JPanel(new net.miginfocom.swing.MigLayout(
                "insets 4 10 4 8, gapx 6, hidemode 3, aligny center", "[][]push[]"));
        // The pane's own background and a hairline below, so the header reads as the top of the
        // pane; the window-gray divider above stays the only boundary between table and pane.
        ClouseauColors.themedBackground(bar, ClouseauColors::detailBackground);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ClouseauColors.separatorColor()));

        JLabel title = new JLabel(Messages.get("detail.title"));
        ClouseauColors.themedForeground(title, ClouseauColors::foreground);
        titleContext = new JLabel();
        ClouseauColors.themedForeground(titleContext, ClouseauColors::dimForeground);
        bar.add(title);
        bar.add(titleContext);

        JPanel tools = new JPanel(new net.miginfocom.swing.MigLayout("insets 0, gapx 6, hidemode 3, aligny center"));
        tools.setOpaque(false);

        // One toggle per format: pressed shows the message formatted and colored, released as logged.
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        formatters.forEach(f -> names.add(f.getName()));
        syntaxHighlighters.forEach(h -> names.add(h.getName()));
        for (String name : names) {
            JToggleButton btn = new JToggleButton(name, true);
            btn.setToolTipText(java.text.MessageFormat.format(Messages.get("detail.format.tooltip"), name));
            FilterBar.applyToggleStyle(btn);
            btn.setVisible(false);
            btn.addActionListener(e -> {
                if (btn.isSelected()) disabledFormats.remove(name); else disabledFormats.add(name);
                refresh();
            });
            formatToggles.put(name, btn);
            tools.add(btn);
        }

        wrapToggle = new JToggleButton(Messages.get("detail.wrap"), wrapLines);
        wrapToggle.setToolTipText(Messages.get("detail.wrap.tooltip"));
        FilterBar.applyToggleStyle(wrapToggle);
        wrapToggle.addActionListener(e -> {
            AppPrefs.setDetailWrapLines(wrapToggle.isSelected());
            setWrapLines(wrapToggle.isSelected());
        });
        tools.add(wrapToggle);
        tools.add(verticalSeparator(), "gapx 4 4");

        // Text size: one "Aa" button; its popup labels the stepper and offers Reset.
        tools.add(buildTextSizeButton());
        tools.add(verticalSeparator(), "gapx 4 4");

        // Copy ⌄: one button that opens the choices, like Loggers ⌄ and Time ⌄.
        copyBtn = new JButton(Messages.get("detail.copy"));
        copyBtn.setToolTipText(Messages.get("detail.copy.tooltip"));
        copyBtn.setIcon(new FilterBar.ChevronDown());
        copyBtn.setHorizontalTextPosition(SwingConstants.LEADING);
        copyBtn.setIconTextGap(8);
        copyBtn.setEnabled(false);
        long[] closedAt = {0};
        copyBtn.addActionListener(e -> {
            if (System.currentTimeMillis() - closedAt[0] < 250) return;   // this click just closed it
            JPopupMenu menu = new JPopupMenu();
            addCopyItems(menu);
            menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent ev) { closedAt[0] = System.currentTimeMillis(); }
                @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent ev) { }
                @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent ev) { }
            });
            // Right-aligned under the button: it sits at the window's right edge.
            menu.show(copyBtn, copyBtn.getWidth() - menu.getPreferredSize().width, copyBtn.getHeight());
        });
        tools.add(copyBtn);

        bar.add(tools);
        updateFontSizeLabel();
        return bar;
    }

    /**
     * "Aa", the familiar text-size symbol, opening a small popup: "Text size  − 12 +" and Reset.
     * The popup stays open while you step, and a second click on Aa closes it. It's built each
     * time it opens: a popup that isn't showing isn't in the window, so a theme switch would
     * miss it and it would open in the old theme's colors.
     */
    private JButton buildTextSizeButton() {
        JButton aa = new JButton("Aa");
        aa.setToolTipText(Messages.get("detail.font.size.tooltip"));
        long[] closedAt = {0};
        aa.addActionListener(e -> {
            // The press that closed the popup shouldn't open it again: the button toggles it.
            if (System.currentTimeMillis() - closedAt[0] < 250) return;
            JPopupMenu popup = buildTextSizePopup();
            popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent ev) { closedAt[0] = System.currentTimeMillis(); }
                @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent ev) { }
                @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent ev) { }
            });
            updateFontSizeLabel();
            popup.pack();
            popup.show(aa, aa.getWidth() - popup.getPreferredSize().width, aa.getHeight());
        });
        return aa;
    }

    private JPopupMenu buildTextSizePopup() {
        JPopupMenu popup = new JPopupMenu();
        JPanel body = new JPanel(new net.miginfocom.swing.MigLayout("insets 10 12 10 12, gapx 4", "[]12[][][]"));
        body.setOpaque(false);
        body.add(new JLabel(Messages.get("detail.font.size")));
        body.add(stepButton("\u2212", Messages.get("detail.font.smaller"), -1));
        fontSizeLabel = new JLabel();
        fontSizeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        body.add(fontSizeLabel, "w 24!");
        body.add(stepButton("+", Messages.get("detail.font.bigger"), +1), "wrap");
        resetFontBtn = new JButton(Messages.get("detail.font.reset"));
        resetFontBtn.addActionListener(e -> setTextSize(DEFAULT_FONT));
        body.add(resetFontBtn, "span, right, gaptop 6");
        popup.add(body);
        return popup;
    }

    private JButton stepButton(String text, String tooltip, int delta) {
        JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        b.setMargin(new Insets(2, 7, 2, 7));
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setFocusable(false);   // the shortcuts cover the keyboard; no focus ring the moment the popup opens
        b.addActionListener(e -> stepFont(delta));
        return b;
    }

    /** Ctrl+wheel, Ctrl+Plus and Ctrl+Minus change the text size; Ctrl+C with nothing selected copies the message. */
    private void installKeys() {
        detailArea.addMouseWheelListener(e -> {
            if (e.isControlDown()) { stepFont(e.getWheelRotation() < 0 ? 1 : -1); e.consume(); }
            else {
                // Without Ctrl, scroll as usual: hand the wheel to the scroll pane.
                Container sp = SwingUtilities.getAncestorOfClass(JScrollPane.class, detailArea);
                if (sp != null) sp.dispatchEvent(SwingUtilities.convertMouseEvent(detailArea, e, sp));
            }
        });
        InputMap in = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        int ctrl = java.awt.event.InputEvent.CTRL_DOWN_MASK;   // as elsewhere in the app; also safe headless
        in.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_EQUALS, ctrl), "detail.bigger");
        in.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ADD, ctrl), "detail.bigger");
        in.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_MINUS, ctrl), "detail.smaller");
        in.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SUBTRACT, ctrl), "detail.smaller");
        getActionMap().put("detail.bigger",  action(() -> stepFont(1)));
        getActionMap().put("detail.smaller", action(() -> stepFont(-1)));

        detailArea.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, ctrl), "detail.copy");
        detailArea.getActionMap().put("detail.copy", action(() -> {
            if (detailArea.getSelectedText() != null) detailArea.copy();
            else if (shown != null) copyMessage();
        }));
    }

    private static Action action(Runnable r) {
        return new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); } };
    }

    private void stepFont(int delta) {
        setTextSize(AppPrefs.getDetailFontSize() + delta);
    }

    private void setTextSize(int requested) {
        int size = Math.max(MIN_FONT, Math.min(MAX_FONT, requested));
        if (size == AppPrefs.getDetailFontSize()) return;
        AppPrefs.setDetailFontSize(size);
        refresh();
        showToast(java.text.MessageFormat.format(Messages.get("detail.font.toast"), size));
    }

    private void updateFontSizeLabel() {
        if (fontSizeLabel == null) return;   // the popup hasn't been opened yet
        int size = AppPrefs.getDetailFontSize();
        fontSizeLabel.setText(String.valueOf(size));
        resetFontBtn.setEnabled(size != DEFAULT_FONT);
    }

    /**
     * Shows a short note ("Text size 14", "Message copied") in the middle of the pane's body for
     * about a second, so an action has visible feedback where the eye is: not in a corner it
     * would be missed in once a menu closes.
     */
    private void showToast(String text) {
        JRootPane root = getRootPane();
        if (root == null || !isShowing()) return;
        if (sizeToast == null) {
            sizeToast = new JLabel();
            sizeToast.setOpaque(true);
            sizeToast.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UIManager.getColor("PopupMenu.borderColor") != null
                            ? UIManager.getColor("PopupMenu.borderColor") : ClouseauColors.separatorColor()),
                    BorderFactory.createEmptyBorder(8, 16, 8, 16)));
            toastTimer = new Timer(1200, e -> sizeToast.setVisible(false));
            toastTimer.setRepeats(false);
        }
        // The "on" toggle fill: a mid-gray chip that stands apart from the pane in both themes
        // (a popup's or tooltip's white would vanish on Light's white pane).
        Color bg = UIManager.getColor("ToggleButton.selectedBackground"), fg = UIManager.getColor("ToggleButton.selectedForeground");
        sizeToast.setBackground(bg != null ? bg : UIManager.getColor("PopupMenu.background"));
        sizeToast.setForeground(fg != null ? fg : ClouseauColors.foreground());
        sizeToast.setText(text);
        JLayeredPane layers = root.getLayeredPane();
        if (sizeToast.getParent() != layers) layers.add(sizeToast, JLayeredPane.POPUP_LAYER);
        Dimension d = sizeToast.getPreferredSize();
        Component body = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.CENTER);
        Rectangle area = body != null ? SwingUtilities.convertRectangle(this, body.getBounds(), layers)
                                      : SwingUtilities.convertRectangle(getParent(), getBounds(), layers);
        Point p = new Point(area.x + (area.width - d.width) / 2, area.y + (area.height - d.height) / 2);
        sizeToast.setBounds(p.x, p.y, d.width, d.height);
        sizeToast.setVisible(true);
        toastTimer.restart();
    }

    /** Message (Ctrl+C), Raw Line and Entry: the Copy menu and the pane's right-click menu. */
    private void addCopyItems(JPopupMenu menu) {
        JMenuItem msg = new JMenuItem(Messages.get("detail.copy.message"));
        msg.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        msg.addActionListener(a -> copyMessage());
        JMenuItem raw = new JMenuItem(Messages.get("detail.copy.raw"));
        raw.addActionListener(a -> copyRaw());
        JMenuItem all = new JMenuItem(Messages.get("detail.copy.entry"));
        all.addActionListener(a -> copyEntry());
        for (JMenuItem i : new JMenuItem[] {msg, raw, all}) { i.setEnabled(shown != null); menu.add(i); }
    }

    /** The message as shown: it flashes, and "Message copied" confirms it. */
    private void copyMessage() {
        if (!toClipboard(lastFormattedMessage)) return;
        flash(messageFieldStart, messageFieldEnd);
        showToast(Messages.get("detail.copied.message"));
    }

    /** The lines exactly as in the file. They aren't on screen to flash, so the note says how many. */
    private void copyRaw() {
        String raw = rawText();
        if (!toClipboard(raw)) return;
        long lines = raw.lines().count();
        showToast(lines > 1 ? java.text.MessageFormat.format(Messages.get("detail.copied.raw.lines"), lines)
                            : Messages.get("detail.copied.raw"));
    }

    /** Every field, as shown: the whole entry flashes. */
    private void copyEntry() {
        if (!toClipboard(detailArea.getText().stripTrailing())) return;
        flash(0, detailArea.getDocument().getLength());
        showToast(Messages.get("detail.copied.entry"));
    }

    private boolean toClipboard(String text) {
        if (shown == null || text == null) return false;
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text), null);
        return true;
    }

    /** Right-click in the pane: Copy for selected text, then the same three as the Copy menu. */
    private void installContextMenu() {
        detailArea.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e)  { maybeShow(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { maybeShow(e); }
            private void maybeShow(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                JPopupMenu menu = new JPopupMenu();
                JMenuItem sel = new JMenuItem(Messages.get("detail.copy.selection"));
                sel.setEnabled(detailArea.getSelectedText() != null);
                sel.addActionListener(a -> detailArea.copy());
                menu.add(sel);
                menu.addSeparator();
                addCopyItems(menu);
                menu.show(detailArea, e.getX(), e.getY());
            }
        });
    }

    /** The entry's lines as the file has them: its raw line and any continuation lines. */
    private String rawText() {
        if (shown == null) return "";
        StringBuilder sb = new StringBuilder(shown.entry().rawLine() != null ? shown.entry().rawLine() : "");
        if (shown.continuations() != null) for (LogEntry c : shown.continuations()) sb.append('\n').append(c.rawLine());
        return sb.toString();
    }

    private static JSeparator verticalSeparator() {
        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 18));
        return sep;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /** Apply all formatters that can handle this input and aren't switched off, in sequence. */
    private String applyFormatters(String input) {
        String result = input;
        for (LogFormatter formatter : formatters) {
            if (formatter.canFormat(result) && !disabledFormats.contains(formatter.getName())) {
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
        shown = entry != null ? content : null;
        copyBtn.setEnabled(entry != null);
        updateFontSizeLabel();
        titleContext.setText(entry != null && content.lineNumber() != null
                ? "\u00b7  " + java.text.MessageFormat.format(Messages.get("detail.title.line"), content.lineNumber()) : "");
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
            formatToggles.values().forEach(b -> b.setVisible(false));
            bodyCards.show(body, "empty");
            return;
        }

        bodyCards.show(body, "entry");
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

        // Fold continuation lines (HL7 segments, stack traces, wrapped lines) into the message
        // so they are included in formatting, syntax highlighting, and the copy action.
        String rawMsg = entry.message() != null ? entry.message() : "";
        if (content.continuations() != null && !content.continuations().isEmpty()) {
            StringBuilder sb = new StringBuilder(rawMsg);
            for (LogEntry c : content.continuations()) sb.append("\n").append(c.rawLine());
            rawMsg = sb.toString();
        }

        // A format's toggle shows only when this message is in that format, so the header stays
        // quiet on plain text and says at a glance that a line carries JSON.
        java.util.Set<String> applicable = new HashSet<>();
        for (LogFormatter f : formatters) if (f.canFormat(rawMsg)) applicable.add(f.getName());
        for (LogSyntaxHighlighter h : syntaxHighlighters) if (h.canHighlight(rawMsg)) applicable.add(h.getName());

        String messageText = applyFormatters(rawMsg);
        lastFormattedMessage = messageText;
        for (LogSyntaxHighlighter h : syntaxHighlighters) if (h.canHighlight(messageText)) applicable.add(h.getName());
        formatToggles.forEach((name, btn) -> {
            btn.setVisible(applicable.contains(name));
            btn.setSelected(!disabledFormats.contains(name));
        });

        LogSyntaxHighlighter colorizer = null;
        for (LogSyntaxHighlighter h : syntaxHighlighters) {
            if (h.canHighlight(messageText) && !disabledFormats.contains(h.getName())) {
                colorizer = h;
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

        spaceLines(doc);
        detailArea.setCaretPosition(0);
    }

    /**
     * What the pane says with nothing selected, centered: what to do, then what you'll get, in
     * the interface font and the dim text of hints, so it doesn't read as log data.
     */
    private JComponent buildEmptyState() {
        JPanel empty = new JPanel(new net.miginfocom.swing.MigLayout("fill, insets 16", "[center]", "push[]6[]push"));
        ClouseauColors.themedBackground(empty, ClouseauColors::detailBackground);
        JLabel title = new JLabel(Messages.get("detail.empty.title"));
        ClouseauColors.themedForeground(title, ClouseauColors::dimForeground);
        JLabel hint = new JLabel(Messages.get("detail.empty.hint"));
        ClouseauColors.themedForeground(hint, ClouseauColors::mutedForeground);
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        empty.add(title, "wrap");
        empty.add(hint);
        return empty;
    }

    /**
     * Line spacing: each line is about 1.6 times the text height (20px at the default 12), close
     * to the table's 24px rows, so the pane reads with the same rhythm as the log.
     */
    private static void spaceLines(StyledDocument doc) {
        SimpleAttributeSet spacing = new SimpleAttributeSet();
        StyleConstants.setLineSpacing(spacing, 0.35f);
        doc.setParagraphAttributes(0, doc.getLength(), spacing, false);
    }

    /** Briefly lights up the text from {@code start} to {@code end} after it's copied. */
    private void flash(int start, int end) {
        if (start < 0 || end <= start) return;
        final Color peak = ClouseauColors.flashPeakColor();
        final Color bg   = ClouseauColors.detailBackground();
        final int   steps = 25;
        final int[] step  = {0};
        final Object[] tag = {null};

        Highlighter hl = detailArea.getHighlighter();
        try {
            tag[0] = hl.addHighlight(start, end,
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
                    tag[0] = hl.addHighlight(start, end,
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
