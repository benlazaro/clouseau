package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.core.AnnotationStore;
import com.droidenx.clouseau.core.Folds;
import com.droidenx.clouseau.core.SourceRefs;
import com.droidenx.clouseau.core.StackTrace;
import com.droidenx.clouseau.core.StackTraceHighlighter;
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
 * background, a title on the left in the column headers' style ("Log entry · line 41", with
 * "· db.log" in a merged view), and on the right one toggle per
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
     * @param where         where the entry is, after "Log entry ·" in the title: "file line 13",
     *                      "db.log, line 13" or "#1042 since connecting"; null for nothing
     */
    /** {@code sourceZone}: the timezone the entry's log is read in, or null if unknown. */
    record Content(LogEntry entry, Color highlight, AnnotationStore.Annotation annotation,
                   List<LogEntry> continuations, long shiftMillis, String where, java.time.ZoneId sourceZone) {}

    private static final int MIN_FONT = 8, MAX_FONT = 36, DEFAULT_FONT = 12;

    /** Copy Raw Line and Copy Entry; the tab binds them so they work from the table too. */
    static final KeyStroke COPY_RAW_KEY = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C,
            Keys.MENU | java.awt.event.InputEvent.ALT_DOWN_MASK);
    static final KeyStroke COPY_ENTRY_KEY = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C,
            Keys.MENU | java.awt.event.InputEvent.SHIFT_DOWN_MASK);

    /**
     * The app's formatters and highlighters now. Read again before each entry is drawn, never
     * kept: a plugin removed or turned off in the Plugin Manager is unloaded, and calling its
     * code afterwards throws {@code NoClassDefFoundError}.
     */
    private final Supplier<List<LogFormatter>> formattersNow;
    private final Supplier<List<LogSyntaxHighlighter>> highlightersNow;
    /** The lists the format toggles were made for; replaced when the app's lists change. */
    private List<LogFormatter> formatters = List.of();
    private List<LogSyntaxHighlighter> syntaxHighlighters = List.of();
    /** The header's tools; the format toggles come first. */
    private JPanel tools;
    /** Returns what to show now, or null when nothing is selected. */
    private final Supplier<Content> current;

    /** Formats the user switched off (by name), kept while the pane lives. */
    private final Set<String> disabledFormats = new HashSet<>();
    /** One toggle per format name, shared by the formatter and highlighter of that name. */
    private final java.util.Map<String, JToggleButton> formatToggles = new java.util.LinkedHashMap<>();
    private JToggleButton wrapToggle;
    /**
     * "Log entry · line 41" ("Log entry" with nothing selected, "· db.log" added in a merged
     * view). The number comes from the table's data, so it shows even with # hidden.
     */
    private JLabel  title;
    private JLabel  fontSizeLabel;
    private JButton resetFontBtn;
    /** The pane's notes ("Text size 14", "Message copied", its find's wraps), over its body. */
    private final Toast toast = new Toast();
    /** The body: the entry's text, or the empty state when nothing is selected. */
    private final CardLayout bodyCards = new CardLayout();
    private final JPanel     body      = new JPanel(bodyCards);
    private Content shown;
    /** True while the tab shows a message instead of rows: nothing selected shows nothing. */
    private boolean blank;
    private final JTextPane detailArea;
    private final JScrollPane detailScroll;
    /** Find in this entry: Ctrl+F with focus in the pane, or the header's search button. */
    private final DetailFindBar findBar;

    /**
     * The stack frames in the message shown, as links (Open in IDE): each one's span in the
     * document, which is its {@code Foo.java:42}.
     */
    private List<SourceRefs.Found> sourceLinks = List.of();

    /** The message's collapsible structures (D-34), as offsets in {@link #lastFormattedMessage}. */
    private List<Folds.Fold> folds = List.of();
    /** The opening brackets (offsets in the message) of the structures collapsed; kept while the entry is. */
    private final Set<Integer> collapsed = new HashSet<>();
    /** A chevron: the fold it opens or closes, and the document offset where its first line starts. */
    private record FoldMark(Folds.Fold fold, int lineStart, boolean collapsed) {}
    private List<FoldMark> foldMarks = List.of();
    /** A collapsed structure's "… 3 keys": its span in the document, and the text it stands for. */
    private record Placeholder(Folds.Fold fold, int start, int end, String hidden) {}
    private List<Placeholder> placeholders = List.of();
    private final List<Object> placeholderTags = new ArrayList<>();
    /** The chevron under the mouse, drawn brighter; null for none. */
    private FoldMark hoverMark;
    /** True while a find opens collapsed text and draws the entry again: that drawing doesn't search again. */
    private boolean revealing;

    private String  lastFormattedMessage = "";
    private int     messageFieldStart    = -1;
    /** Where values start, in pixels at the current text size: the hanging indent of every field. */
    private float   valueIndent;
    private int     messageFieldEnd      = -1;
    private boolean wrapLines            = AppPrefs.isDetailWrapLines();
    private DateTimeFormatter tsFmt      = timestampFormatter();

    /** A pane with fixed formatters and highlighters (tests). */
    DetailPanel(List<LogFormatter> formatters, List<LogSyntaxHighlighter> syntaxHighlighters,
                Supplier<Content> current) {
        this(() -> formatters, () -> syntaxHighlighters, current);
    }

    /** A pane that uses the app's formatters and highlighters as they are when it draws an entry. */
    DetailPanel(Supplier<List<LogFormatter>> formatters, Supplier<List<LogSyntaxHighlighter>> syntaxHighlighters,
                Supplier<Content> current) {
        super(new BorderLayout());
        this.formattersNow = formatters;
        this.highlightersNow = syntaxHighlighters;
        this.current = current;
        // The text is colored when an entry is drawn (labels, values, level, JSON), so a theme
        // switch must draw it again, or it keeps the old theme's colors until the next selection.
        ClouseauColors.onThemeChange(this, this::refresh);

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

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintFoldMarks((Graphics2D) g);
            }

            /** A selection over a collapsed structure copies what it stands for, not "… 3 keys". */
            @Override
            public void copy() {
                String text = expandedText(getSelectionStart(), getSelectionEnd());
                if (text == null) super.copy();
                else Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(text), null);
            }

            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                FoldMark mark = markAt(e.getPoint());
                if (mark != null) return Messages.get(mark.collapsed() ? "detail.fold.expand.tooltip" : "detail.fold.collapse.tooltip");
                return placeholderAt(e.getPoint()) != null ? Messages.get("detail.fold.expand.tooltip") : null;
            }
        };
        ToolTipManager.sharedInstance().registerComponent(detailArea);
        detailArea.setEditorKit(new WrapAnywhereKit());
        detailArea.setEditable(false);
        ClouseauColors.themedBackground(detailArea, ClouseauColors::detailBackground);
        ClouseauColors.themedForeground(detailArea, ClouseauColors::foreground);
        detailArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        findBar = new DetailFindBar(detailArea, toast, detailArea::requestFocusInWindow);
        findBar.setFolding(findFolding);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(buildHeader(), BorderLayout.NORTH);
        top.add(findBar, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        installKeys();
        installContextMenu();
        detailScroll = new JScrollPane(detailArea);
        ClouseauColors.themedBackground(detailScroll.getViewport(), ClouseauColors::detailBackground);
        // No frame of its own: the header's hairline is the only line between header and text
        // (a frame doubled it into a bright 2px band), as the main toolbar has one line.
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        body.add(detailScroll, "entry");
        body.add(buildEmptyState(), "empty");
        JPanel blankCard = new JPanel();
        ClouseauColors.themedBackground(blankCard, ClouseauColors::detailBackground);
        body.add(blankCard, "blank");
        add(body, BorderLayout.CENTER);
        render(null);
    }

    /** Redraws from the owner's current selection. */
    void refresh() {
        render(current.get());
    }

    /**
     * Blank (true) while the tab shows a message instead of rows (nothing loaded): with no
     * lines, the pane drops its "Select a line" hint and shows nothing.
     */
    void setBlank(boolean blank) {
        if (this.blank == blank) return;
        this.blank = blank;
        render(current.get());
    }

    /**
     * The table's selection or rows changed: redraws only if what to show did. A live tab's
     * appends fire selection events for the same row, and redrawing on each would throw away
     * where the user had scrolled to in a long message.
     */
    void selectionChanged() {
        Content next = current.get();
        if (next != null && next.equals(shown)) return;
        render(next);
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
                "insets 4 10 4 8, gapx 6, hidemode 3, aligny center", "[]push[]"));
        // The pane's own background and a hairline below, so the header reads as the top of the
        // pane; the window-gray divider above stays the only boundary between table and pane.
        ClouseauColors.themedBackground(bar, ClouseauColors::detailBackground);
        Runnable bottomLine = () -> bar.setBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ClouseauColors.separatorColor()));
        bottomLine.run();
        ClouseauColors.onThemeChange(bar, bottomLine);

        // Styled like the table's column headers (12px, dim): a label for what's below, so the
        // bright text stays the data's.
        title = new JLabel(Messages.get("detail.title.none"));
        ClouseauColors.themedForeground(title, ClouseauColors::dimForeground);
        title.setFont(title.getFont().deriveFont(Font.PLAIN, 12f));
        bar.add(title);

        tools = new JPanel(new net.miginfocom.swing.MigLayout("insets 0, gapx 6, hidemode 3, aligny center"));
        tools.setOpaque(false);
        syncFormats();

        wrapToggle = new JToggleButton(Messages.get("detail.wrap"), wrapLines);
        wrapToggle.setToolTipText(Messages.get("detail.wrap.tooltip"));
        FilterBar.applyToggleStyle(wrapToggle);
        wrapToggle.addActionListener(e -> {
            AppPrefs.setDetailWrapLines(wrapToggle.isSelected());
            setWrapLines(wrapToggle.isSelected());
        });
        tools.add(wrapToggle);
        tools.add(verticalSeparator(), "gapx 4 4");

        JButton findBtn = new JButton(new com.formdev.flatlaf.icons.FlatSearchIcon());
        findBtn.setToolTipText(Messages.get("detail.find.tooltip"));
        findBtn.putClientProperty("JButton.buttonType", "borderless");
        findBtn.setFocusable(false);
        findBtn.addActionListener(e -> openFind(null));
        tools.add(findBtn);

        // Text size: one "Aa" button; its popup labels the stepper and offers Reset.
        tools.add(buildTextSizeButton());
        // Copying is in the right-click menus, the Edit menu and the shortcuts, not a button here.

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
        JButton aa = new JButton(Messages.get("detail.font.size.button"));
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
        resetFontBtn = new JButton(java.text.MessageFormat.format(Messages.get("detail.font.reset"), DEFAULT_FONT));
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
            if (Keys.menuDown(e.getModifiersEx())) { stepFont(e.getWheelRotation() < 0 ? 1 : -1); e.consume(); }
            else {
                // Without Ctrl, scroll as usual: hand the wheel to the scroll pane.
                Container sp = SwingUtilities.getAncestorOfClass(JScrollPane.class, detailArea);
                if (sp != null) sp.dispatchEvent(SwingUtilities.convertMouseEvent(detailArea, e, sp));
            }
        });
        InputMap in = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        int ctrl = Keys.MENU;   // Cmd on macOS; Ctrl elsewhere and headless
        in.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_EQUALS, ctrl), "detail.bigger");
        in.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ADD, ctrl), "detail.bigger");
        in.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_MINUS, ctrl), "detail.smaller");
        in.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SUBTRACT, ctrl), "detail.smaller");
        getActionMap().put("detail.bigger",  action(() -> stepFont(1)));
        getActionMap().put("detail.smaller", action(() -> stepFont(-1)));
        // Collapse All and Expand All: the text-size keys with Shift, as in JetBrains IDEs.
        int shift = java.awt.event.InputEvent.SHIFT_DOWN_MASK;
        for (int vk : new int[] {java.awt.event.KeyEvent.VK_MINUS, java.awt.event.KeyEvent.VK_SUBTRACT})
            in.put(KeyStroke.getKeyStroke(vk, ctrl | shift), "detail.collapseAll");
        for (int vk : new int[] {java.awt.event.KeyEvent.VK_EQUALS, java.awt.event.KeyEvent.VK_ADD, java.awt.event.KeyEvent.VK_PLUS})
            in.put(KeyStroke.getKeyStroke(vk, ctrl | shift), "detail.expandAll");
        getActionMap().put("detail.collapseAll", action(this::collapseAll));
        getActionMap().put("detail.expandAll",   action(this::expandAll));

        detailArea.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, ctrl), "detail.copy");
        detailArea.getActionMap().put("detail.copy", action(() -> {
            if (detailArea.getSelectedText() != null) detailArea.copy();
            else if (shown != null) copyMessage();
        }));
    }

    private static Action action(Runnable r) {
        return new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); } };
    }

    /** Whether an entry is shown, so the copy commands have something to copy. */
    boolean hasEntry() { return shown != null; }

    boolean wrapLines() { return wrapLines; }

    /** Whether keyboard focus is somewhere in the pane: Ctrl+F and F3 then find in the entry. */
    boolean hasFocusInside() {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return owner != null && SwingUtilities.isDescendingFrom(owner, this);
    }

    /**
     * Opens the find strip. Its query becomes the selected text (one line of it), else
     * {@code fallback} (the table's query) when the strip has none yet, else stays.
     */
    void openFind(String fallback) {
        String selected = detailArea.getSelectedText();
        String seed = selected != null && !selected.isBlank() && selected.indexOf('\n') < 0 ? selected
                    : findBar.hasQuery() ? null : fallback;
        findBar.open(seed);
    }

    /** F3 / Shift+F3 in the pane: the next or previous match, opening the strip if it has no query. */
    void findStep(int direction) {
        if (findBar.hasQuery()) findBar.step(direction);
        else openFind(null);
    }

    DetailFindBar findBar() { return findBar; }

    /** View → Wrap Lines: the same as the header's Wrap toggle. */
    void toggleWrap() {
        boolean wrap = !wrapLines;
        AppPrefs.setDetailWrapLines(wrap);
        setWrapLines(wrap);
    }

    /** View → Text Size: one step bigger (+1) or smaller (-1), or 0 for the default. */
    void changeTextSize(int step) {
        if (step == 0) setTextSize(DEFAULT_FONT);
        else stepFont(step);
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
        toast.show(body, text);
    }

    /** Copy Message (Ctrl+C), Copy Raw Line and Copy Entry, named and keyed as in the table's row menu. */
    private void addCopyItems(JPopupMenu menu) {
        String keys = "menu.edit.copy.";
        JMenuItem msg = new JMenuItem(Messages.get(keys + "message"));
        msg.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, Keys.MENU));
        msg.addActionListener(a -> copyMessage());
        JMenuItem raw = new JMenuItem(Messages.get(keys + "raw"));
        raw.setAccelerator(COPY_RAW_KEY);
        raw.addActionListener(a -> copyRaw());
        JMenuItem all = new JMenuItem(Messages.get(keys + "entry"));
        all.setAccelerator(COPY_ENTRY_KEY);
        all.addActionListener(a -> copyEntry());
        for (JMenuItem i : new JMenuItem[] {msg, raw, all}) { i.setEnabled(shown != null); menu.add(i); }
    }

    /** The message as shown: it flashes, and "Message copied" confirms it. */
    void copyMessage() {
        if (!toClipboard(lastFormattedMessage)) return;
        flash(messageFieldStart, messageFieldEnd);
        showToast(Messages.get("detail.copied.message"));
    }

    /** The lines exactly as in the file. They aren't on screen to flash, so the note says how many. */
    void copyRaw() {
        String raw = rawText();
        if (!toClipboard(raw)) return;
        long lines = raw.lines().count();
        showToast(lines > 1 ? java.text.MessageFormat.format(Messages.get("detail.copied.raw.lines"), lines)
                            : Messages.get("detail.copied.raw"));
    }

    /** Every field, as shown: the whole entry flashes. */
    void copyEntry() {
        String expanded = expandedText(0, detailArea.getDocument().getLength());
        if (!toClipboard((expanded != null ? expanded : detailArea.getText()).stripTrailing())) return;
        flash(0, detailArea.getDocument().getLength());
        showToast(Messages.get("detail.copied.entry"));
    }

    private boolean toClipboard(String text) {
        if (shown == null || text == null) return false;
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text), null);
        return true;
    }

    /**
     * Right-click in the pane: Open in IDE for the stack frame under the mouse, or the logger's
     * class; Copy for selected text (only when there is some); then the table menu's three
     * Copy items. A click on a stack frame's link opens it; the pointer is a hand over one.
     */
    private void installContextMenu() {
        java.awt.event.MouseAdapter mouse = new java.awt.event.MouseAdapter() {
            /** Where the button went down, so a drag that selects text isn't taken for a click. */
            private int pressedAt = -1;

            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                pressedAt = detailArea.viewToModel2D(e.getPoint());
                maybeShow(e);
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (maybeShow(e)) return;
                if (!SwingUtilities.isLeftMouseButton(e) || e.getModifiersEx() != 0 || e.getClickCount() != 1) return;
                if (detailArea.viewToModel2D(e.getPoint()) != pressedAt) return;
                FoldMark mark = markAt(e.getPoint());
                if (mark != null) { toggleFold(mark.fold()); return; }
                Placeholder placeholder = placeholderAt(e.getPoint());
                if (placeholder != null) { toggleFold(placeholder.fold()); return; }
                SourceRefs.Found link = linkAt(e.getPoint());
                if (link != null) openSource(List.of(link.ref()));
            }
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                FoldMark mark = markAt(e.getPoint());
                boolean clickable = mark != null || placeholderAt(e.getPoint()) != null || linkAt(e.getPoint()) != null;
                detailArea.setCursor(Cursor.getPredefinedCursor(clickable ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
                setHoverMark(markOnRow(e.getPoint()));
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                setHoverMark(null);
            }
            private boolean maybeShow(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return false;
                JPopupMenu menu = new JPopupMenu();
                SourceRefs.Found link = linkAt(e.getPoint());
                if (link != null) {
                    JMenuItem open = new JMenuItem(java.text.MessageFormat.format(
                            Messages.get("source.open.frame"), link.ref().label()));
                    open.addActionListener(a -> openSource(List.of(link.ref())));
                    menu.add(open);
                }
                List<SourceRefs.SourceRef> loggerClass = shown != null
                        ? SourceRefs.loggerClass(shown.entry().logger()) : List.of();
                if (!loggerClass.isEmpty()) {
                    JMenuItem open = new JMenuItem(java.text.MessageFormat.format(
                            Messages.get("table.context.source.open"), RowMenu.shortLogger(shown.entry().logger())));
                    open.addActionListener(a -> openSource(loggerClass));
                    menu.add(open);
                }
                if (menu.getComponentCount() > 0) menu.addSeparator();
                if (detailArea.getSelectedText() != null) {
                    JMenuItem sel = new JMenuItem(Messages.get("detail.copy.selection"));
                    sel.addActionListener(a -> detailArea.copy());
                    menu.add(sel);
                    menu.addSeparator();
                }
                addCopyItems(menu);
                if (!folds.isEmpty()) {
                    menu.addSeparator();
                    JMenuItem expand = new JMenuItem(Messages.get("detail.fold.expand.all"));
                    expand.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_EQUALS,
                            Keys.MENU | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
                    expand.setEnabled(!collapsed.isEmpty());
                    expand.addActionListener(a -> expandAll());
                    JMenuItem collapse = new JMenuItem(Messages.get("detail.fold.collapse.all"));
                    collapse.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_MINUS,
                            Keys.MENU | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
                    collapse.setEnabled(collapsed.size() < folds.size());
                    collapse.addActionListener(a -> collapseAll());
                    menu.add(expand);
                    menu.add(collapse);
                }
                menu.show(detailArea, e.getX(), e.getY());
                return true;
            }
        };
        detailArea.addMouseListener(mouse);
        detailArea.addMouseMotionListener(mouse);
    }

    /** For tests: the stack frame links in the entry shown, as spans of {@link #textPane()}. */
    List<SourceRefs.Found> sourceLinks() {
        return sourceLinks;
    }

    /** For tests: the pane's text. */
    JTextPane textPane() {
        return detailArea;
    }

    /** Opens a stack frame or a logger's class in the editor chosen in Settings (Open in IDE). */
    private void openSource(List<SourceRefs.SourceRef> candidates) {
        SourceOpener.open(body, toast, candidates);
    }

    /**
     * Marks the message's stack frames as links: their {@code Foo.java:42} underlined in the
     * theme's link color, over whatever color the syntax highlighters gave them. With the stack
     * trace highlighter on, a framework frame's link is only underlined, so it stays dimmed.
     */
    private void markSourceLinks(StyledDocument doc, boolean dimFramework) {
        if (messageFieldStart < 0 || messageFieldEnd <= messageFieldStart) return;
        String message;
        try {
            message = doc.getText(messageFieldStart, messageFieldEnd - messageFieldStart);
        } catch (BadLocationException e) {
            return;
        }
        List<SourceRefs.Found> found = SourceRefs.frames(message);
        if (found.isEmpty()) return;
        SimpleAttributeSet link = new SimpleAttributeSet();
        StyleConstants.setForeground(link, ClouseauColors.linkColor());
        StyleConstants.setUnderline(link, true);
        SimpleAttributeSet underline = new SimpleAttributeSet();
        StyleConstants.setUnderline(underline, true);
        List<SourceRefs.Found> inDocument = new ArrayList<>(found.size());
        for (SourceRefs.Found f : found) {
            SourceRefs.Found at = new SourceRefs.Found(messageFieldStart + f.start(), messageFieldStart + f.end(), f.ref());
            boolean framework = dimFramework && StackTrace.isFramework(f.ref().path().replace('/', '.'));
            doc.setCharacterAttributes(at.start(), at.end() - at.start(), framework ? underline : link, false);
            inDocument.add(at);
        }
        sourceLinks = List.copyOf(inDocument);
        SourceOpener.prepare();   // index the source folders now, so a click opens at once
    }

    /** The stack frame link under {@code p}, or null: on its text, not just nearest to it. */
    private SourceRefs.Found linkAt(Point p) {
        if (sourceLinks.isEmpty()) return null;
        int pos = detailArea.viewToModel2D(p);
        for (SourceRefs.Found link : sourceLinks) {
            if (pos < link.start() || pos > link.end()) continue;
            try {
                java.awt.geom.Rectangle2D from = detailArea.modelToView2D(link.start());
                java.awt.geom.Rectangle2D to = detailArea.modelToView2D(link.end());
                // On one line, the point must be between the link's ends; a wrapped link is taken by position.
                if (from.getY() != to.getY()) return pos < link.end() ? link : null;
                if (p.x >= from.getX() && p.x < to.getX() && p.y >= from.getY() && p.y < from.getMaxY()) return link;
            } catch (BadLocationException e) {
                return null;
            }
        }
        return null;
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

    /**
     * Takes the app's formatters and highlighters as they are now and, when they changed (a
     * plugin installed, removed, turned on or off), makes the header's toggles again: one per
     * format, pressed to show the message formatted and colored, released to show it as logged.
     * A format the user switched off stays off under its name.
     */
    private void syncFormats() {
        List<LogFormatter> f = formattersNow.get();
        List<LogSyntaxHighlighter> h = highlightersNow.get();
        if (f == formatters && h == syntaxHighlighters) return;
        formatters = f;
        syntaxHighlighters = h;
        formatToggles.values().forEach(tools::remove);
        formatToggles.clear();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        formatters.forEach(x -> names.add(x.getName()));
        syntaxHighlighters.forEach(x -> names.add(x.getName()));
        int at = 0;
        for (String name : names) {
            JToggleButton btn = new JToggleButton(name, !disabledFormats.contains(name));
            btn.setToolTipText(java.text.MessageFormat.format(Messages.get("detail.format.tooltip"), name));
            FilterBar.applyToggleStyle(btn);
            btn.setVisible(false);
            btn.addActionListener(e -> {
                if (btn.isSelected()) disabledFormats.remove(name); else disabledFormats.add(name);
                refresh();
            });
            formatToggles.put(name, btn);
            tools.add(btn, "", at++);
        }
        tools.revalidate();
        tools.repaint();
    }

    private void render(Content content) {
        syncFormats();
        LogEntry entry = content != null ? content.entry() : null;
        // Drawing the same entry again (a note, a theme, a continuation line that arrived)
        // keeps where the user had scrolled to and what they had selected.
        boolean sameEntry = entry != null && shown != null && entry.equals(shown.entry())
                && java.util.Objects.equals(content.where(), shown.where());
        Point keepView = sameEntry ? detailScroll.getViewport().getViewPosition() : null;
        int keepStart = detailArea.getSelectionStart(), keepEnd = detailArea.getSelectionEnd();
        if (!sameEntry) collapsed.clear();     // a new entry opens with everything expanded
        for (Object tag : placeholderTags) detailArea.getHighlighter().removeHighlight(tag);
        placeholderTags.clear();
        folds = List.of();
        foldMarks = List.of();
        placeholders = List.of();
        hoverMark = null;
        shown = entry != null ? content : null;
        updateFontSizeLabel();
        // "Log entry", then where it is: "· file line 41", "· db.log, line 41" or "· #1042 since connecting".
        StringBuilder heading = new StringBuilder(Messages.get("detail.title.none"));
        if (entry != null && content.where() != null) heading.append("  \u00b7  ").append(content.where());
        title.setText(heading.toString());
        StyledDocument doc = detailArea.getStyledDocument();
        try { doc.remove(0, doc.getLength()); } catch (BadLocationException ignored) {}

        int fontSize = AppPrefs.getDetailFontSize();

        SimpleAttributeSet key = new SimpleAttributeSet();
        StyleConstants.setFontFamily(key, ClouseauFonts.MONO_FAMILY);
        StyleConstants.setFontSize(key, fontSize);
        StyleConstants.setBold(key, true);
        StyleConstants.setForeground(key, ClouseauColors.detailKeyColor());
        key.addAttribute(WrapAnywhereKit.NO_BREAK, Boolean.TRUE);

        SimpleAttributeSet val = new SimpleAttributeSet();
        StyleConstants.setFontFamily(val, ClouseauFonts.MONO_FAMILY);
        StyleConstants.setFontSize(val, fontSize);
        StyleConstants.setForeground(val, ClouseauColors.foreground());
        valueIndent = valueIndent(fontSize);

        sourceLinks = List.of();
        if (entry == null) {
            lastFormattedMessage = "";
            messageFieldStart = messageFieldEnd = -1;
            formatToggles.values().forEach(b -> b.setVisible(false));
            bodyCards.show(body, blank ? "blank" : "empty");
            findBar.entryShown(false);     // an open strip waits, hidden, for the next entry
            findBar.textChanged(true);
            return;
        }

        bodyCards.show(body, "entry");
        findBar.entryShown(true);
        SimpleAttributeSet levelVal = new SimpleAttributeSet(val);
        StyleConstants.setForeground(levelVal, ClouseauColors.levelColor(entry.level()));
        StyleConstants.setBold(levelVal, true);

        String ts     = entry.timestamp() != null ? tsFmt.format(entry.timestamp()) : "";
        String level  = entry.level()     != null ? entry.level().name()  : "";
        String thread = entry.thread()    != null ? entry.thread()        : "";
        String logger = entry.logger()    != null ? entry.logger()        : "";

        if (content.highlight() != null) {
            // One drawn swatch, not "██████": block characters leave hairline gaps between
            // them in the bundled monospace font.
            SimpleAttributeSet swatchStyle = new SimpleAttributeSet();
            StyleConstants.setIcon(swatchStyle, new HighlightSwatch(content.highlight(), fontSize));
            appendField(doc, key, swatchStyle, Messages.get("detail.highlight.label"), " ");
        }

        AnnotationStore.Annotation annotation = content.annotation();
        if (annotation != null) {
            // A field like the others: what the user wrote, then who and when under it in dim
            // text, aligned with the value. The pencil in the # column already marks it as a note.
            SimpleAttributeSet noteMeta = new SimpleAttributeSet(val);
            StyleConstants.setFontSize(noteMeta, Math.max(10, fontSize - 1));
            StyleConstants.setForeground(noteMeta, ClouseauColors.dimForeground());
            appendField(doc, key, val, Messages.get("annotation.detail.label"), annotation.note());
            String when = noteTime(annotation.createdAt());
            String byLine = Messages.get("annotation.detail.by") + " " + annotation.author()
                    + (when != null ? "  \u00b7  " + when : "");
            int start = doc.getLength();
            insertText(doc, byLine + "\n", noteMeta);
            SimpleAttributeSet atValue = new SimpleAttributeSet();
            StyleConstants.setLeftIndent(atValue, valueIndent);
            StyleConstants.setFirstLineIndent(atValue, 0f);
            doc.setParagraphAttributes(start, 0, atValue, false);
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
        java.time.ZoneId written = content.sourceZone();
        if (written != null && entry.timestamp() != null) {
            java.time.Instant at = entry.timestamp().minusMillis(content.shiftMillis());
            if (!written.getRules().getOffset(at).equals(tsFmt.getZone().getRules().getOffset(at))) {
                // Read in one zone, shown in another: the time as the file has it, to check
                // the source timezone against the raw line.
                SimpleAttributeSet dim = new SimpleAttributeSet(val);
                StyleConstants.setForeground(dim, ClouseauColors.dimForeground());
                appendField(doc, key, dim, Messages.get("detail.written.label"), java.text.MessageFormat.format(
                        Messages.get("detail.written"), tsFmt.withZone(written).format(at), written.getId()));
            }
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
        for (LogSyntaxHighlighter h : syntaxHighlighters) if (canHighlight(h, rawMsg)) applicable.add(h.getName());

        String messageText = applyFormatters(rawMsg);
        lastFormattedMessage = messageText;
        // Every enabled highlighter that has something to mark colors the message (D-26).
        List<LogSyntaxHighlighter> colorizers = new ArrayList<>();
        for (LogSyntaxHighlighter h : syntaxHighlighters) {
            if (!canHighlight(h, messageText)) continue;
            applicable.add(h.getName());
            if (!disabledFormats.contains(h.getName())) colorizers.add(h);
        }
        formatToggles.forEach((name, btn) -> {
            btn.setVisible(applicable.contains(name));
            btn.setSelected(!disabledFormats.contains(name));
        });

        String msgLabel = String.format("%-12s ", Messages.get("table.col.message") + ":");
        messageFieldStart = doc.getLength() + msgLabel.length();
        if (!colorizers.isEmpty()) {
            // What can collapse comes from the highlighters that know their format's structures.
            List<List<Folds.Fold>> foldLayers = new ArrayList<>();
            for (LogSyntaxHighlighter h : colorizers) {
                if (!(h instanceof Folds.Source source)) continue;
                try {
                    foldLayers.add(source.folds(messageText));
                } catch (RuntimeException e) {
                    warnOnce(h, e);
                }
            }
            folds = Folds.merge(messageText, foldLayers);
            Set<Integer> opens = new HashSet<>();
            for (Folds.Fold f : folds) opens.add(f.open());
            collapsed.retainAll(opens);
            renderSyntaxHighlightedField(doc, key, val, Messages.get("table.col.message"), messageText, colorizers);
        } else {
            appendField(doc, key, val, Messages.get("table.col.message"), messageText);
        }
        messageFieldEnd = doc.getLength() - 1; // exclude trailing newline

        spaceLines(doc);
        markSourceLinks(doc, colorizers.stream().anyMatch(h -> h instanceof StackTraceHighlighter));
        if (keepView == null) {
            detailArea.setCaretPosition(0);
        } else {
            int length = doc.getLength();
            detailArea.setCaretPosition(Math.min(keepStart, length));
            detailArea.moveCaretPosition(Math.min(keepEnd, length));
            // Lay the new text out now, so the old position can be restored before painting.
            detailScroll.validate();
            JViewport viewport = detailScroll.getViewport();
            Dimension view = viewport.getViewSize(), extent = viewport.getExtentSize();
            viewport.setViewPosition(new Point(
                    Math.max(0, Math.min(keepView.x, view.width - extent.width)),
                    Math.max(0, Math.min(keepView.y, view.height - extent.height))));
        }
        // The find strip's marks were on the old text: find again, from the first match on a new entry.
        // Not while a find is opening collapsed text: that find goes on with the new text itself.
        if (!revealing) findBar.textChanged(keepView == null);
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

    /** When a note was written, in the display time zone and the Timestamp field's format, to the second. */
    private String noteTime(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) return null;
        try {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(tsFmt.getZone())
                    .format(java.time.Instant.parse(createdAt));
        } catch (java.time.DateTimeException e) {
            return createdAt;
        }
    }

    /** The width of a padded label ("Message:" and spaces to 12 columns, then a space). */
    private float valueIndent(int fontSize) {
        // ClouseauFonts.mono, never new Font(MONO_FAMILY): see ClouseauFonts.MONO_FAMILY.
        Font bold  = ClouseauFonts.mono(Font.BOLD, fontSize);
        Font plain = ClouseauFonts.mono(Font.PLAIN, fontSize);
        return detailArea.getFontMetrics(bold).stringWidth("000000000000")
             + detailArea.getFontMetrics(plain).charWidth(' ');
    }

    /**
     * A hanging indent for the field from {@code start} to the end of the document: its first
     * line reaches back to the label, and every other line (wrapped, continuation, formatted
     * JSON) starts where the value does. Layout only, so copying yields the text as logged.
     */
    private void hang(StyledDocument doc, int start) {
        int end = Math.max(start, doc.getLength() - 1);   // up to the field's own newline
        SimpleAttributeSet rest = new SimpleAttributeSet();
        StyleConstants.setLeftIndent(rest, valueIndent);
        StyleConstants.setFirstLineIndent(rest, 0f);
        doc.setParagraphAttributes(start, end - start, rest, false);
        SimpleAttributeSet first = new SimpleAttributeSet();
        StyleConstants.setFirstLineIndent(first, -valueIndent);
        doc.setParagraphAttributes(start, 0, first, false);
    }

    /**
     * Lets Wrap break inside a word when the word alone is wider than the pane. Swing wraps only
     * at spaces otherwise, so a long URL, token or Base64 run went past the right edge with no
     * way to scroll to it. A text run with no minimum width can be split anywhere; it's still
     * split at a space whenever one fits.
     *
     * <p>Text carrying {@link #NO_BREAK} (the field labels) is never a break point, so a long
     * value starts beside its label and breaks inside, instead of leaving the label alone on
     * its line because the space after it was the only space to break at.
     *
     * <p>Text carrying {@link #CONTROL} is control characters, drawn as their Unicode control
     * pictures ({@code ␀}, {@code ␛}, {@code ␡}). The document keeps the real characters, so a
     * selection copies what was logged.
     */
    private static final class WrapAnywhereKit extends javax.swing.text.StyledEditorKit {
        static final Object NO_BREAK = new Object() {
            @Override public String toString() { return "noBreak"; }
        };
        static final Object CONTROL = new Object() {
            @Override public String toString() { return "control"; }
        };

        private final javax.swing.text.ViewFactory factory = elem -> {
            if (javax.swing.text.AbstractDocument.ContentElementName.equals(elem.getName())) {
                boolean noBreak = elem.getAttributes().getAttribute(NO_BREAK) != null;
                boolean control = elem.getAttributes().getAttribute(CONTROL) != null;
                return new javax.swing.text.LabelView(elem) {
                    @Override
                    public javax.swing.text.Segment getText(int p0, int p1) {
                        javax.swing.text.Segment text = super.getText(p0, p1);
                        if (!control) return text;
                        char[] shown = new char[text.count];
                        for (int i = 0; i < text.count; i++) shown[i] = controlPicture(text.array[text.offset + i]);
                        return new javax.swing.text.Segment(shown, 0, shown.length);
                    }

                    @Override
                    public float getMinimumSpan(int axis) {
                        return axis == X_AXIS ? 0 : super.getMinimumSpan(axis);
                    }

                    @Override
                    public int getBreakWeight(int axis, float pos, float len) {
                        return noBreak && axis == X_AXIS ? BadBreakWeight : super.getBreakWeight(axis, pos, len);
                    }
                };
            }
            return super.getViewFactory().create(elem);
        };

        @Override
        public javax.swing.text.ViewFactory getViewFactory() {
            return factory;
        }
    }

    /**
     * The Highlight field's value: a solid rounded block in the row's color, about six
     * characters wide and a capital letter high at the pane's text size.
     */
    private static final class HighlightSwatch implements Icon {
        private final Color color;
        private final int width, height;

        HighlightSwatch(Color color, int fontSize) {
            this.color = color;
            this.width = Math.round(fontSize * 3.6f);
            this.height = Math.round(fontSize * 0.85f);
        }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(x, y, width, height, 4, 4);
            ClouseauColors.paintHighlightEdge(g2, color, x, y, width, height, 4);
            g2.dispose();
        }

        @Override public int getIconWidth()  { return width; }
        @Override public int getIconHeight() { return height; }
    }

    /** Highlighters that have failed once, so a broken plugin is logged once, not on every row. */
    private final java.util.Set<String> failedHighlighters = new HashSet<>();

    private boolean canHighlight(LogSyntaxHighlighter h, String text) {
        try {
            return h.canHighlight(text);
        } catch (Exception | LinkageError e) {
            warnOnce(h, e);
            return false;
        }
    }

    private void warnOnce(LogSyntaxHighlighter h, Throwable e) {
        if (failedHighlighters.add(h.getClass().getName())) {
            log.warn("Highlighter {} failed; is it built for an older Clouseau API than {}?",
                    h.getName(), com.droidenx.clouseau.api.ClouseauApi.VERSION, e);
        }
    }

    private void appendField(StyledDocument doc, SimpleAttributeSet keyStyle,
                             SimpleAttributeSet valStyle, String label, String value) {
        int start = doc.getLength();
        insertText(doc, String.format("%-12s ", label + ":"), keyStyle);
        insertText(doc, value + "\n", valStyle);
        hang(doc, start);
    }

    private void renderSyntaxHighlightedField(StyledDocument doc, SimpleAttributeSet keyStyle,
                                              SimpleAttributeSet baseStyle, String label,
                                              String text, List<LogSyntaxHighlighter> colorizers) {
        int start = doc.getLength();
        insertText(doc, String.format("%-12s ", label + ":"), keyStyle);
        List<List<LogSyntaxHighlighter.Span>> layers = new ArrayList<>();
        for (LogSyntaxHighlighter h : colorizers) {
            try {
                layers.add(h.highlight(text));
            } catch (Exception | LinkageError e) {
                // LinkageError: a plugin built for an older API (one that returned RGB colors).
                warnOnce(h, e);
            }
        }
        Folded out = new Folded(doc, text, baseStyle);
        int pos = 0;
        for (LogSyntaxHighlighter.Span span : com.droidenx.clouseau.core.SyntaxLayers.resolve(text.length(), layers)) {
            if (span.start() > pos) {
                out.insert(pos, span.start(), baseStyle);
            }
            SimpleAttributeSet spanStyle = new SimpleAttributeSet(baseStyle);
            StyleConstants.setForeground(spanStyle, ClouseauColors.syntaxColor(span.role()));
            // ACCENT is something to find among the rest (a class name, the app's own frame): bold
            // as well as colored, so it stands out a little more than its color alone.
            if (span.role() == LogSyntaxHighlighter.Role.ACCENT) StyleConstants.setBold(spanStyle, true);
            out.insert(span.start(), span.end(), spanStyle);
            pos = span.end();
        }
        if (pos < text.length()) {
            out.insert(pos, text.length(), baseStyle);
        }
        insertText(doc, "\n", baseStyle);
        hang(doc, start);
        out.finish();
    }

    /**
     * Writes the message into the document with its collapsed structures (D-34) hidden: what's
     * between a collapsed structure's brackets becomes one "… 3 keys" placeholder. Text is given
     * as ranges of the message, in order; it notes where each fold's chevron and placeholder land.
     */
    private final class Folded {
        private final StyledDocument doc;
        private final String text;
        private final SimpleAttributeSet baseStyle;
        private final List<Folds.Fold> hidden;
        private int nextHidden;
        /** Each fold's opening bracket in the document, or -1 while not written (or hidden). */
        private final int[] openAt;
        private final List<Placeholder> written = new ArrayList<>();

        Folded(StyledDocument doc, String text, SimpleAttributeSet baseStyle) {
            this.doc = doc;
            this.text = text;
            this.baseStyle = baseStyle;
            this.hidden = Folds.hidden(folds, collapsed);
            this.openAt = new int[folds.size()];
            java.util.Arrays.fill(openAt, -1);
        }

        /** Writes the message's {@code [from, to)} in {@code style}, leaving out what's collapsed. */
        void insert(int from, int to, SimpleAttributeSet style) {
            int pos = from;
            while (pos < to) {
                Folds.Fold h = nextHidden < hidden.size() ? hidden.get(nextHidden) : null;
                if (h != null && pos >= h.hiddenEnd()) { nextHidden++; continue; }
                if (h != null && pos >= h.hiddenStart()) {
                    if (pos == h.hiddenStart()) placeholder(h);
                    pos = Math.min(to, h.hiddenEnd());
                    if (pos == h.hiddenEnd()) nextHidden++;
                    continue;
                }
                int end = h != null ? Math.min(to, h.hiddenStart()) : to;
                int at = doc.getLength();
                for (int i = 0; i < folds.size(); i++) {
                    int open = folds.get(i).open();
                    if (open >= pos && open < end) openAt[i] = at + open - pos;
                }
                insertText(doc, text.substring(pos, end), style);
                pos = end;
            }
        }

        private void placeholder(Folds.Fold f) {
            String key = switch (f.kind()) {
                case KEYS -> "detail.fold.keys";
                case FIELDS -> "detail.fold.fields";
                case ITEMS -> "detail.fold.items";
            };
            String label = " … " + java.text.MessageFormat.format(Messages.get(key), f.count()) + " ";
            SimpleAttributeSet style = new SimpleAttributeSet(baseStyle);
            StyleConstants.setForeground(style, ClouseauColors.dimForeground());
            style.addAttribute(WrapAnywhereKit.NO_BREAK, Boolean.TRUE);
            int at = doc.getLength();
            insertText(doc, label, style);
            written.add(new Placeholder(f, at, at + label.length(), text.substring(f.hiddenStart(), f.hiddenEnd())));
        }

        /** Keeps the chevrons (one per line, the outermost) and placeholders, and outlines the placeholders. */
        void finish() {
            String docText;
            try {
                docText = doc.getText(0, doc.getLength());
            } catch (BadLocationException e) {
                return;
            }
            List<FoldMark> marks = new ArrayList<>();
            Set<Integer> lines = new HashSet<>();
            for (int i = 0; i < folds.size(); i++) {
                if (openAt[i] < 0) continue;
                int lineStart = docText.lastIndexOf('\n', openAt[i] - 1) + 1;
                if (!lines.add(lineStart)) continue;   // in order, so the outermost on the line has it
                Folds.Fold f = folds.get(i);
                marks.add(new FoldMark(f, lineStart, collapsed.contains(f.open())));
            }
            foldMarks = List.copyOf(marks);
            placeholders = List.copyOf(written);
            for (Placeholder p : placeholders) {
                try {
                    placeholderTags.add(detailArea.getHighlighter().addHighlight(p.start(), p.end(), PLACEHOLDER_PAINTER));
                } catch (BadLocationException ignored) {}
            }
        }
    }

    // ── Folds (D-34) ─────────────────────────────────────────────────────────

    /** A rounded outline around a placeholder, in the key badges' colors, so it reads as something to press. */
    private static final Highlighter.HighlightPainter PLACEHOLDER_PAINTER = (g, p0, p1, bounds, c) -> {
        try {
            java.awt.geom.Rectangle2D a = c.modelToView2D(p0), b = c.modelToView2D(p1);
            if (a == null || b == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // On one line from a to b; wrapped, from a to the line's end.
            double right = a.getY() == b.getY() ? b.getX() : c.getWidth() - c.getInsets().right;
            java.awt.geom.RoundRectangle2D r = new java.awt.geom.RoundRectangle2D.Double(
                    a.getX(), a.getY() + 1, right - a.getX(), a.getHeight() - 2, 6, 6);
            g2.setColor(ClouseauColors.keyBadgeFill());
            g2.fill(r);
            g2.setColor(ClouseauColors.keyBadgeBorder());
            g2.draw(r);
            g2.dispose();
        } catch (BadLocationException ignored) {}
    };

    /** Collapses the fold if it's open, else opens it, and draws the entry again where it was. */
    private void toggleFold(Folds.Fold fold) {
        if (!collapsed.remove(fold.open())) collapsed.add(fold.open());
        refresh();
    }

    /** Collapses every structure in the message (opening one then shows its parts collapsed). */
    void collapseAll() {
        if (folds.isEmpty()) return;
        for (Folds.Fold f : folds) collapsed.add(f.open());
        refresh();
    }

    /** Opens every collapsed structure in the message. */
    void expandAll() {
        if (collapsed.isEmpty()) return;
        collapsed.clear();
        refresh();
    }

    /**
     * What find searches and where it lands (D-34): the whole text, every placeholder replaced by
     * what it stands for. Only the message has placeholders, and nothing before it is hidden, so
     * the whole text and the document agree up to the message's start.
     */
    private final DetailFindBar.Folding findFolding = new DetailFindBar.Folding() {
        @Override
        public String fullText() {
            javax.swing.text.Document doc = detailArea.getDocument();
            String expanded = expandedText(0, doc.getLength());
            if (expanded != null) return expanded;
            try {
                return doc.getText(0, doc.getLength());
            } catch (BadLocationException e) {
                return "";
            }
        }

        @Override
        public com.droidenx.clouseau.core.TextMatches.Range toDocument(int start, int end) {
            int extra = 0;     // how much longer the whole text is than the document, so far
            for (Placeholder p : placeholders) {
                int from = p.start() + extra, to = from + p.hidden().length();
                if (end <= from) break;
                if (start < to) return null;      // over hidden text
                extra += p.hidden().length() - (p.end() - p.start());
            }
            return new com.droidenx.clouseau.core.TextMatches.Range(start - extra, end - extra);
        }

        @Override
        public void open(int start, int end) {
            if (messageFieldStart < 0) return;
            int from = start - messageFieldStart, to = end - messageFieldStart;
            boolean opened = false;
            for (Folds.Fold f : folds) {
                if (collapsed.contains(f.open()) && from < f.hiddenEnd() && to > f.hiddenStart()) {
                    collapsed.remove(f.open());
                    opened = true;
                }
            }
            if (!opened) return;
            revealing = true;      // the find goes on with the new text itself
            try {
                refresh();
            } finally {
                revealing = false;
            }
        }
    };

    /**
     * The text from {@code start} to {@code end} with every placeholder in it replaced by what it
     * stands for, or null when no placeholder is in the range (the caller copies as usual).
     */
    String expandedText(int start, int end) {
        if (end <= start) return null;
        StringBuilder sb = null;
        int pos = start;
        try {
            for (Placeholder p : placeholders) {
                if (p.end() <= start || p.start() >= end) continue;
                if (sb == null) sb = new StringBuilder();
                if (p.start() > pos) sb.append(detailArea.getDocument().getText(pos, p.start() - pos));
                sb.append(p.hidden());
                pos = Math.max(pos, p.end());
            }
            if (sb == null) return null;
            if (pos < end) sb.append(detailArea.getDocument().getText(pos, end - pos));
        } catch (BadLocationException e) {
            return null;
        }
        return sb.toString();
    }

    /** Where chevrons are drawn: the column just left of where the message's text starts. */
    private java.awt.geom.Rectangle2D chevronBox(FoldMark mark) {
        try {
            java.awt.geom.Rectangle2D row = detailArea.modelToView2D(mark.lineStart());
            java.awt.geom.Rectangle2D value = detailArea.modelToView2D(messageFieldStart);
            if (row == null || value == null) return null;
            int fontSize = AppPrefs.getDetailFontSize();
            double cw = detailArea.getFontMetrics(ClouseauFonts.mono(Font.PLAIN, fontSize)).charWidth(' ');
            double size = Math.max(cw * 1.6, 12);
            // The row's text line, without the line spacing below it.
            double textHeight = detailArea.getFontMetrics(ClouseauFonts.mono(Font.PLAIN, fontSize)).getHeight();
            return new java.awt.geom.Rectangle2D.Double(value.getX() - size - cw * 0.3,
                    row.getY() + (Math.min(row.getHeight(), textHeight) - size) / 2, size, size);
        } catch (BadLocationException e) {
            return null;
        }
    }

    private void paintFoldMarks(Graphics2D g) {
        if (foldMarks.isEmpty()) return;
        Rectangle clip = g.getClipBounds();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (FoldMark mark : foldMarks) {
            java.awt.geom.Rectangle2D box = chevronBox(mark);
            if (box == null || (clip != null && !box.intersects(clip))) continue;
            // Collapsed ones always stand out a little; open ones stay faint until pointed at.
            boolean hover = mark.equals(hoverMark);
            g2.setColor(hover ? ClouseauColors.foreground()
                      : mark.collapsed() ? ClouseauColors.dimForeground() : ClouseauColors.mutedForeground());
            double s = box.getWidth() * 0.42, cx = box.getCenterX(), cy = box.getCenterY();
            java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
            if (mark.collapsed()) {     // ›
                path.moveTo(cx - s / 3, cy - s / 1.5);
                path.lineTo(cx + s / 3, cy);
                path.lineTo(cx - s / 3, cy + s / 1.5);
            } else {                    // ⌄
                path.moveTo(cx - s / 1.5, cy - s / 3);
                path.lineTo(cx, cy + s / 3);
                path.lineTo(cx + s / 1.5, cy - s / 3);
            }
            g2.setStroke(new BasicStroke((float) Math.max(1.2, box.getWidth() / 9),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(path);
        }
        g2.dispose();
    }

    /** The chevron under {@code p}, with some room around it to make it easy to hit; or null. */
    private FoldMark markAt(Point p) {
        for (FoldMark mark : foldMarks) {
            java.awt.geom.Rectangle2D box = chevronBox(mark);
            if (box == null) continue;
            double pad = box.getWidth() * 0.3;
            if (p.x >= box.getX() - pad && p.x < box.getMaxX() + pad
                    && p.y >= box.getY() - pad && p.y < box.getMaxY() + pad) return mark;
        }
        return null;
    }

    /** The chevron on the line under {@code p}, wherever on the line; or null. */
    private FoldMark markOnRow(Point p) {
        for (FoldMark mark : foldMarks) {
            try {
                java.awt.geom.Rectangle2D row = detailArea.modelToView2D(mark.lineStart());
                if (row != null && p.y >= row.getY() && p.y < row.getMaxY()) return mark;
            } catch (BadLocationException ignored) {}
        }
        return null;
    }

    private void setHoverMark(FoldMark mark) {
        if (java.util.Objects.equals(mark, hoverMark)) return;
        hoverMark = mark;
        detailArea.repaint();
    }

    /** The placeholder under {@code p}, on its text; or null. */
    private Placeholder placeholderAt(Point p) {
        for (Placeholder ph : placeholders) {
            if (onSpan(p, ph.start(), ph.end())) return ph;
        }
        return null;
    }

    /** Whether {@code p} is on the text from {@code start} to {@code end}, not just nearest to it. */
    private boolean onSpan(Point p, int start, int end) {
        int pos = detailArea.viewToModel2D(p);
        if (pos < start || pos > end) return false;
        try {
            java.awt.geom.Rectangle2D from = detailArea.modelToView2D(start);
            java.awt.geom.Rectangle2D to = detailArea.modelToView2D(end);
            // On one line, the point must be between the ends; a wrapped span is taken by position.
            if (from.getY() != to.getY()) return pos < end;
            return p.x >= from.getX() && p.x < to.getX() && p.y >= from.getY() && p.y < from.getMaxY();
        } catch (BadLocationException e) {
            return false;
        }
    }

    /** For tests: the openings (in the formatted message) of the structures that can collapse, and those collapsed. */
    List<Integer> foldOpens() { return folds.stream().map(Folds.Fold::open).toList(); }
    Set<Integer> collapsedOpens() { return Set.copyOf(collapsed); }
    void toggleFoldAt(int open) {
        for (Folds.Fold f : folds) if (f.open() == open) { toggleFold(f); return; }
    }

    /**
     * Appends {@code text} in {@code style}. Control characters (a NUL, an escape) go in as runs
     * of their own, dimmed and marked {@link WrapAnywhereKit#CONTROL}, so they show as control
     * pictures instead of the font's empty box, or nothing at all.
     */
    private static void insertText(StyledDocument doc, String text, SimpleAttributeSet style) {
        try {
            int i = 0;
            while (i < text.length()) {
                boolean control = isControl(text.charAt(i));
                int end = i + 1;
                while (end < text.length() && isControl(text.charAt(end)) == control) end++;
                doc.insertString(doc.getLength(), text.substring(i, end), control ? controlStyle(style) : style);
                i = end;
            }
        } catch (BadLocationException ignored) {}
    }

    private static SimpleAttributeSet controlStyle(SimpleAttributeSet style) {
        SimpleAttributeSet control = new SimpleAttributeSet(style);
        StyleConstants.setForeground(control, ClouseauColors.dimForeground());
        control.addAttribute(WrapAnywhereKit.CONTROL, Boolean.TRUE);
        return control;
    }

    /** A C0 control character other than tab and line breaks, or DEL. */
    static boolean isControl(char c) {
        return (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F;
    }

    /** The Unicode control picture for a control character (U+2400–U+2421); others as they are. */
    static char controlPicture(char c) {
        if (c < 0x20) return (char) (0x2400 + c);
        if (c == 0x7F) return '␡';
        return c;
    }
}
