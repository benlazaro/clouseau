package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.TextMatches;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Utilities;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * The detail pane's find strip (UC-3): finds text in the entry as the pane shows it, so a
 * formatted JSON or HL7 message is searched as you read it. Every match gets Find's light
 * mark; the current one an amber box, kept in view. The query stays as the selection moves,
 * so stepping through rows shows the same value in each entry.
 * <p>
 * Searching one entry is quick, so it runs on the EDT, as everything here must.
 */
final class DetailFindBar extends JPanel {

    private final JTextComponent text;
    private final JTextField field = new JTextField();
    private final JToggleButton caseToggle = new JToggleButton(Messages.get("detail.find.case"));
    private final JToggleButton regexToggle = new JToggleButton(Messages.get("detail.find.regex"));
    private final JLabel counter = new JLabel();
    private final WrapNotice wrapNotice = new WrapNotice(field);
    /** Where focus goes when the strip closes. */
    private final Runnable focusBack;

    private List<TextMatches.Range> matches = List.of();
    private final List<Object> tags = new ArrayList<>();
    private int cursor = -1;
    private Timer debounce;

    DetailFindBar(JTextComponent text, Runnable focusBack) {
        super(new MigLayout("insets 3 10 3 8, gap 6", "[grow][][]8[][][][]", "[center]"));
        this.text = text;
        this.focusBack = focusBack;
        setVisible(false);
        ClouseauColors.themedBackground(this, ClouseauColors::detailBackground);
        Runnable bottomLine = () -> setBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ClouseauColors.separatorColor()));
        bottomLine.run();
        ClouseauColors.onThemeChange(this, bottomLine);

        field.putClientProperty("JTextField.placeholderText", Messages.get("detail.find.placeholder"));
        field.putClientProperty("JTextField.leadingIcon", new com.formdev.flatlaf.icons.FlatSearchIcon());
        field.putClientProperty("JTextField.showClearButton", true);
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { queryChanged(); }
            public void removeUpdate(DocumentEvent e) { queryChanged(); }
            public void changedUpdate(DocumentEvent e) {}
        });
        field.addActionListener(e -> step(+1));
        field.registerKeyboardAction(e -> step(-1),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), WHEN_FOCUSED);
        // Esc anywhere in the strip closes it, the toggles and buttons included.
        registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        for (JToggleButton t : new JToggleButton[] {caseToggle, regexToggle}) {
            FilterBar.applyToggleStyle(t);
            t.setFont(t.getFont().deriveFont(11f));
            t.setMargin(new Insets(1, 5, 1, 5));
            t.setFocusable(false);
            t.addActionListener(e -> research(true));
        }
        caseToggle.setToolTipText(Messages.get("detail.find.case.tooltip"));
        regexToggle.setToolTipText(Messages.get("detail.find.regex.tooltip"));

        ClouseauColors.themedForeground(counter, ClouseauColors::dimForeground);
        counter.setFont(counter.getFont().deriveFont(11f));

        JButton prev = FindBar.arrowButton("\u25b2");
        JButton next = FindBar.arrowButton("\u25bc");
        prev.setToolTipText(Messages.get("find.prev.tooltip"));
        next.setToolTipText(Messages.get("find.next.tooltip"));
        prev.addActionListener(e -> step(-1));
        next.addActionListener(e -> step(+1));
        JButton closeBtn = new JButton("\u00d7");
        closeBtn.setFont(closeBtn.getFont().deriveFont(13f));
        closeBtn.setMargin(new Insets(1, 4, 1, 4));
        closeBtn.setToolTipText(Messages.get("detail.find.close.tooltip"));
        closeBtn.addActionListener(e -> close());
        for (JButton b : new JButton[] {prev, next, closeBtn}) b.setFocusable(false);

        add(field, "growx, wmin 120");
        add(caseToggle);
        add(regexToggle);
        add(counter, "wmin 70");
        add(wrapNotice, "hidemode 3");
        add(prev);
        add(next);
        add(closeBtn);
    }

    /**
     * Shows the strip with the cursor in its field. A non-blank {@code seed} (the selected text,
     * or the table's query) replaces the query; otherwise the last one stays, selected.
     */
    void open(String seed) {
        setVisible(true);
        revalidate();
        if (seed != null && !seed.isBlank() && !seed.equals(field.getText())) {
            field.setText(seed);
            research(true);      // now, not after the debounce: the field's text is already final
        }
        field.selectAll();
        field.requestFocusInWindow();
    }

    /** Hides the strip, drops its marks and gives focus back to the pane's text. */
    void close() {
        setVisible(false);
        if (debounce != null) debounce.stop();
        clearMarks();
        matches = List.of();
        cursor = -1;
        counter.setText("");
        wrapNotice.clear();
        focusBack.run();
    }

    /** Whether the strip is open with a query, so F3 steps through its matches. */
    boolean hasQuery() {
        return isVisible() && !field.getText().isEmpty();
    }

    /**
     * The pane drew its text again. A new entry ({@code newEntry}) starts at its first match,
     * scrolled into view; the same entry drawn again keeps the current match and the scroll.
     */
    void textChanged(boolean newEntry) {
        if (!isVisible() || field.getText().isEmpty()) return;
        research(newEntry);
    }

    /**
     * The next (+1) or previous (-1) match, wrapping around, scrolled into view. Going past the
     * last or first match says so for a moment ({@link WrapNotice}).
     */
    void step(int direction) {
        if (debounce != null && debounce.isRunning()) { debounce.stop(); research(false); }
        if (matches.isEmpty()) return;
        int from = cursor;
        cursor = cursor < 0 ? (direction > 0 ? 0 : matches.size() - 1)
                            : Math.floorMod(cursor + direction, matches.size());
        if (WrapNotice.isWrap(from, cursor, direction, matches.size())) wrapNotice.wrapped(direction > 0);
        else wrapNotice.clear();
        showCurrent();
    }

    /** The wrap note's text while it shows, else null; for tests. */
    String wrapNoteText() { return wrapNotice.isVisible() ? wrapNotice.getText() : null; }

    // For tests.
    String counterText() { return counter.getText(); }
    int matchCount()     { return matches.size(); }
    int current()        { return cursor; }
    JTextField field()   { return field; }
    JToggleButton caseToggle()  { return caseToggle; }
    JToggleButton regexToggle() { return regexToggle; }

    private void queryChanged() {
        if (debounce != null) debounce.stop();
        debounce = new Timer(150, e -> research(true));
        debounce.setRepeats(false);
        debounce.start();
    }

    /**
     * The document's own text, whose offsets are the highlights'. Not {@code getText()}: that
     * writes the platform's line separator, "\r\n" on Windows, moving every match one character
     * per line above it.
     */
    private String documentText() {
        try {
            return text.getDocument().getText(0, text.getDocument().getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    /** Finds the query again; {@code jump} goes to the first match, else the current one stays. */
    private void research(boolean jump) {
        String query = field.getText();
        if (jump) wrapNotice.clear();      // a new query or entry starts afresh
        TextMatches.Result result = TextMatches.find(documentText(), query,
                caseToggle.isSelected(), regexToggle.isSelected());
        clearMarks();
        matches = result.matches();
        Highlighter hl = text.getHighlighter();
        for (int i = 0; i < matches.size(); i++) {
            TextMatches.Range r = matches.get(i);
            try {
                tags.add(hl.addHighlight(r.start(), r.end(), new MarkPainter(i)));
            } catch (BadLocationException ignored) {}
        }
        field.putClientProperty("JComponent.outline", result.error() != null ? "error" : null);
        if (result.error() != null) {
            cursor = -1;
            showCount(Messages.get("detail.find.invalid"), true);
            counter.setToolTipText(result.error());
            return;
        }
        counter.setToolTipText(null);
        if (matches.isEmpty()) {
            cursor = -1;
            showCount(query.isEmpty() ? "" : MessageFormat.format(Messages.get("find.count"), 0), !query.isEmpty());
            return;
        }
        if (jump || cursor < 0 || cursor >= matches.size()) {
            cursor = 0;
            showCurrent();
        } else {
            showCount(position(), false);
            text.repaint();
        }
    }

    private void showCurrent() {
        showCount(position(), false);
        text.repaint();
        TextMatches.Range r = matches.get(cursor);
        // The caret goes to the match: a new entry's caret reset scrolls to the caret later on
        // (DefaultCaret does it in an invokeLater), which would otherwise undo this scroll.
        text.setCaretPosition(r.start());
        SwingUtilities.invokeLater(() -> scrollTo(r));
    }

    /** Scrolls the match into view with some room around it, once the text is laid out. */
    private void scrollTo(TextMatches.Range r) {
        if (r.end() > text.getDocument().getLength()) return;     // the text changed since
        Container scroll = SwingUtilities.getAncestorOfClass(JScrollPane.class, text);
        if (scroll != null) scroll.validate();
        try {
            Rectangle2D a = text.modelToView2D(r.start()), b = text.modelToView2D(r.end());
            if (a == null || b == null) return;
            Rectangle area = a.getBounds().union(b.getBounds());
            // Some room around it, so the match isn't pressed against the pane's edge.
            int pad = a.getBounds().height * 2;
            area.grow(40, pad);
            text.scrollRectToVisible(area);
        } catch (BadLocationException ignored) {}
    }

    private String position() {
        return MessageFormat.format(Messages.get("detail.find.position"), cursor + 1, matches.size());
    }

    private void showCount(String s, boolean problem) {
        ClouseauColors.themedForeground(counter,
                () -> problem ? ClouseauColors.statusError() : ClouseauColors.dimForeground());
        counter.setText(s);
    }

    private void clearMarks() {
        Highlighter hl = text.getHighlighter();
        for (Object tag : tags) hl.removeHighlight(tag);
        tags.clear();
    }

    /**
     * Find's mark for one match, drawn behind the text: the table's light outlined box, or the
     * amber one on the current match. One box per line the match is on, over the whole match,
     * not one per colored run, so a match across JSON's colors reads as one.
     */
    private final class MarkPainter implements Highlighter.HighlightPainter {
        private final int index;

        MarkPainter(int index) { this.index = index; }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            boolean current = index == cursor;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            try {
                int pos = p0;
                while (pos < p1) {
                    int rowEnd = Utilities.getRowEnd(c, pos);
                    if (rowEnd < pos) break;
                    int segEnd = Math.min(p1, rowEnd + 1);
                    Rectangle a = c.modelToView2D(pos).getBounds();
                    Rectangle last = c.modelToView2D(segEnd - 1).getBounds();
                    float x1 = last.x + charWidth(c, segEnd - 1);
                    RoundRectangle2D box = new RoundRectangle2D.Float(
                            a.x - 1.5f, a.y + 0.5f, x1 - a.x + 3f, Math.max(a.height, last.height) - 1f, 4, 4);
                    g2.setColor(current ? ClouseauColors.findCurrentBackground() : ClouseauColors.findMarkBackground());
                    g2.fill(box);
                    g2.setColor(current ? ClouseauColors.findCurrentBorder() : ClouseauColors.findMarkBorder());
                    g2.draw(box);
                    pos = segEnd;
                }
            } catch (BadLocationException ignored) {
            } finally {
                g2.dispose();
            }
        }

        /** The drawn width of the character at {@code pos}; a line break counts as a space. */
        private float charWidth(JTextComponent c, int pos) throws BadLocationException {
            String ch = c.getDocument().getText(pos, 1);
            Font font = c.getFont();
            if (c.getDocument() instanceof javax.swing.text.StyledDocument doc) {
                font = doc.getFont(doc.getCharacterElement(pos).getAttributes());
            }
            FontMetrics fm = c.getFontMetrics(font);
            char shown = ch.charAt(0);
            if (shown == '\n' || shown == '\r') shown = ' ';
            else if (DetailPanel.isControl(shown)) shown = DetailPanel.controlPicture(shown);
            return fm.charWidth(shown);
        }
    }
}
