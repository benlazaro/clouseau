package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.CompactTime;
import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.Rectangle2D;

/**
 * One end of the time range, as typed in the Time popup. Digits are formatted date first as
 * they're typed or pasted ({@code 20261123030700} → {@code 2026-11-23 03:07:00}), and what's
 * missing to the millisecond shows dimmed after the text, filled in on Tab, Enter, Apply or
 * leaving the field: the start of the range with the earliest instant, the end with the last
 * ({@link CompactTime#complete}).
 */
final class TimeField extends JTextField {

    private final boolean end;

    TimeField(String text, boolean end) {
        super(text, 16);
        this.end = end;
        ((AbstractDocument) getDocument()).setDocumentFilter(new MaskFilter());
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) {
                if (!e.isTemporary()) complete();
                repaint();
            }
        });
        addCaretListener(e -> repaint());
    }

    /** The text with its completion, as the range will take it. */
    String effectiveText() {
        String text = getText().trim();
        String full = CompactTime.complete(text, end);
        return full != null ? full : text;
    }

    /** Fills in the completion, if there is one. */
    void complete() {
        String full = CompactTime.complete(getText().trim(), end);
        if (full != null && !full.equals(getText())) setText(full);
    }

    /** The dimmed rest of the completion after the text, or null with none to show. */
    String ghost() {
        String text = getText();
        if (text.isEmpty()) return null;
        String full = CompactTime.complete(text, end);
        if (full == null || full.equals(text) || !full.startsWith(text)) return null;
        return full.substring(text.length());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!hasFocus() || getCaretPosition() != getDocument().getLength()) return;
        String rest = ghost();
        if (rest == null) return;
        try {
            Rectangle2D at = modelToView2D(getDocument().getLength());
            if (at == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                g2.setColor(ClouseauColors.dimForeground());
                FontMetrics fm = g2.getFontMetrics();
                int baseline = (int) at.getY() + fm.getAscent();
                g2.drawString(rest, (int) at.getMaxX() + 1, baseline);
            } finally {
                g2.dispose();
            }
        } catch (BadLocationException ignored) {
            // The text changed under us; the next paint has it right.
        }
    }

    /**
     * Formats the whole text after each edit, if it's a date-first value, and keeps the caret
     * after the same digits. Deleting a separator the mask put in deletes the digit next to it
     * instead, or the mask would only put it back.
     */
    private final class MaskFilter extends DocumentFilter {

        @Override
        public void insertString(FilterBypass fb, int offset, String s, AttributeSet attrs)
                throws BadLocationException {
            replace(fb, offset, 0, s, attrs);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String s, AttributeSet attrs)
                throws BadLocationException {
            String old = fb.getDocument().getText(0, fb.getDocument().getLength());
            String added = s == null ? "" : s;
            String now = old.substring(0, offset) + added + old.substring(offset + length);
            if (!apply(fb, old, now, offset + added.length(), attrs)) fb.replace(offset, length, s, attrs);
        }

        @Override
        public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
            String old = fb.getDocument().getText(0, fb.getDocument().getLength());
            int from = offset, to = offset + length;
            if (length == 1 && !Character.isDigit(old.charAt(offset)) && CompactTime.mask(old) != null) {
                // Backspace (caret after the separator) takes the digit before; Delete the one after.
                if (getCaretPosition() == offset + 1) from = Math.max(0, offset - 1);
                else to = Math.min(old.length(), to + 1);
            }
            String now = old.substring(0, from) + old.substring(to);
            if (!apply(fb, old, now, from, null)) fb.remove(from, to - from);
        }

        /** Writes {@code now} masked, caret after the digits before {@code caret}; false if it isn't masked. */
        private boolean apply(FilterBypass fb, String old, String now, int caret, AttributeSet attrs)
                throws BadLocationException {
            String masked = CompactTime.reformat(old, now);
            if (masked == null) return false;
            int digits = (int) now.substring(0, Math.min(caret, now.length())).chars()
                    .filter(Character::isDigit).count();
            fb.replace(0, fb.getDocument().getLength(), masked, attrs);
            setCaretPosition(CompactTime.caretAfterDigits(masked, digits));
            return true;
        }
    }
}
