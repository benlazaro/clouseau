package com.droidenx.clouseau.ui.theme;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrast in the built-in themes, with the two WCAG bars: 4.5:1 for text, 3:1 for what
 * isn't text but must be seen (control borders, the off-toggle outline, focus, the active
 * tab's underline, the selection ring). Purely decorative lines (separators, panel and popup
 * hairlines, highlight swatch edges) have no bar and aren't checked.
 *
 * <p>The Clouseau design system's Colors table checks every pair it is given at 4.5:1, so its
 * notes for the non-text colors say "against" rather than "on" and leave the 3:1 check here.
 */
class ThemeContrastTest {

    private static final double TEXT = 4.5;
    private static final double NON_TEXT = 3.0;

    /** {foreground key, background key, minimum}; a key may be a FlatLaf {@code [style]} entry. */
    private static final Object[][] PAIRS = {
        // Text
        {"@foreground",                     "Table.background",             TEXT},
        {"@foreground",                     "@background",                  TEXT},
        {"@disabledForeground",             "@background",                  TEXT},
        {"Clouseau.mutedForeground",        "@background",                  TEXT},
        {"Button.foreground",               "Button.background",            TEXT},
        {"TextField.foreground",            "TextField.background",         TEXT},
        {"TextField.placeholderForeground", "TextField.background",         TEXT},
        {"Clouseau.selectionForeground",    "Clouseau.selectionBackground", TEXT},
        {"Button.default.foreground",       "Button.default.background",    TEXT},
        {"Clouseau.activeFilterForeground", "Button.background",            TEXT},
        {"Clouseau.level.TRACE",            "Table.background",             TEXT},
        {"Clouseau.level.DEBUG",            "Table.background",             TEXT},
        {"Clouseau.level.INFO",             "Table.background",             TEXT},
        {"Clouseau.level.WARN",             "Table.background",             TEXT},
        {"Clouseau.level.ERROR",            "Table.background",             TEXT},
        {"Clouseau.level.FATAL",            "Table.background",             TEXT},
        {"Clouseau.level.UNKNOWN",          "Table.background",             TEXT},
        // Not text, but must be seen
        {"[style]TextField",                "@background",                  NON_TEXT},
        {"[style]ComboBox",                 "@background",                  NON_TEXT},
        {"Button.borderColor",              "@background",                  NON_TEXT},
        {"CheckBox.icon.borderColor",       "@background",                  NON_TEXT},
        {"[style]ToggleButton",             "@background",                  NON_TEXT},
        {"Component.focusColor",            "@background",                  NON_TEXT},
        {"TabbedPane.underlineColor",       "@background",                  NON_TEXT},
        {"Clouseau.selectionRing",          "Table.background",             NON_TEXT},
        // The ring marking "already this color" (DESIGN.md 6.14), on every swatch and the menu
        {"Clouseau.highlightRing",          "@menuBackground",              NON_TEXT},
        {"Clouseau.highlightRing",          "Clouseau.highlight.0",         NON_TEXT},
        {"Clouseau.highlightRing",          "Clouseau.highlight.1",         NON_TEXT},
        {"Clouseau.highlightRing",          "Clouseau.highlight.2",         NON_TEXT},
        {"Clouseau.highlightRing",          "Clouseau.highlight.3",         NON_TEXT},
        {"Clouseau.highlightRing",          "Clouseau.highlight.4",         NON_TEXT},
        {"Clouseau.highlightRing",          "Clouseau.highlight.5",         NON_TEXT},
        {"Clouseau.highlightRing",          "Clouseau.highlight.6",         NON_TEXT},
        {"Clouseau.highlightRing",          "Clouseau.highlight.7",         NON_TEXT},
    };

    @ParameterizedTest
    @ValueSource(strings = {"ClouseauDarkLaf", "ClouseauLightLaf"})
    void everyPairMeetsItsBar(String theme) throws Exception {
        Properties p = load(theme);
        List<String> failures = new ArrayList<>();
        for (Object[] pair : PAIRS) {
            String fg = (String) pair[0], bg = (String) pair[1];
            double min = (Double) pair[2];
            double ratio = contrast(color(p, fg), color(p, bg));
            if (ratio < min) {
                failures.add(String.format("%s on %s: %.2f:1, needs %.1f:1", fg, bg, ratio, min));
            }
        }
        assertThat(failures).as(theme).isEmpty();
    }

    private static Properties load(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream in = ThemeContrastTest.class.getResourceAsStream(name + ".properties")) {
            assertThat(in).as(name + ".properties on the classpath").isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    /** A key's color: a hex value, a {@code @variable} or {@code $key} reference, or a style's borderColor. */
    private static Color color(Properties p, String key) {
        String v = p.getProperty(key);
        assertThat(v).as(key).isNotNull();
        v = v.trim();
        if (key.startsWith("[style]")) {
            int i = v.indexOf("borderColor:");
            assertThat(i).as(key + " sets a borderColor").isGreaterThanOrEqualTo(0);
            v = v.substring(i + "borderColor:".length()).split(";")[0].trim();
        }
        if (v.startsWith("@")) return color(p, v);
        if (v.startsWith("$")) return color(p, v.substring(1));
        assertThat(v).as(key).matches("#[0-9A-Fa-f]{6}");
        return new Color(Integer.parseInt(v.substring(1), 16));
    }

    static double contrast(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
