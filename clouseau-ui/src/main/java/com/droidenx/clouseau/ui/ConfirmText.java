package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;

import java.awt.Color;

/**
 * The text of a confirmation message box (DESIGN.md 6.12): a question alone, or, when there's
 * something to add (what else goes, what stays), the question in bold, the eye's first stop,
 * then each note on its own line in the theme's dimmer text, read next. The color is read
 * when the text is built, so build it each time the box opens. Pass our own messages only:
 * nothing is escaped.
 */
final class ConfirmText {

    private ConfirmText() {}

    /** {@code question}, with {@code notes} (any null or blank left out) under it. */
    static String of(String question, String... notes) {
        StringBuilder lines = new StringBuilder();
        for (String note : notes) {
            if (note != null && !note.isBlank()) lines.append("<br>").append(note);
        }
        if (lines.isEmpty()) return question;
        Color dim = ClouseauColors.dimForeground();
        return "<html><b>" + question + "</b><span style='color:"
                + String.format("#%06x", dim.getRGB() & 0xFFFFFF) + "'>" + lines + "</span></html>";
    }
}
