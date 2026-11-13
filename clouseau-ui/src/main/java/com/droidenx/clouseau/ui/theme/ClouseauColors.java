package com.droidenx.clouseau.ui.theme;

import com.droidenx.clouseau.api.LogEntry.LogLevel;

import javax.swing.*;
import java.awt.*;

/**
 * Named color accessors for the Clouseau UI.
 *
 * <p>When <em>Clouseau Dark</em> is active, values come from {@code UIManager}
 * (populated by {@code ClouseauDark.properties} via {@link ThemeManager}).
 * For all other themes the methods fall back to standard Swing/FlatLaf
 * {@code UIManager} keys so the application renders reasonably without
 * baking dark-theme colors into the component hierarchy.
 *
 * <p>Every method is a live UIManager lookup, so the correct value is always
 * returned for whichever theme is active at paint time.
 */
public final class ClouseauColors {

    private ClouseauColors() {}

    // ── Hardcoded fallback colors (used when UIManager has no entry) ──────────

    private static final Color FB_LEVEL_TRACE   = new Color(0x757575);
    private static final Color FB_LEVEL_DEBUG   = new Color(0x66BB6A);
    private static final Color FB_LEVEL_INFO    = new Color(0x29B6F6);
    private static final Color FB_LEVEL_WARN    = new Color(0xFFA726);
    private static final Color FB_LEVEL_ERROR   = new Color(0xEF5350);
    private static final Color FB_LEVEL_FATAL   = new Color(0xFF4081);

    private static final Color FB_STATUS_OK     = new Color(0x4CAF50);
    private static final Color FB_STATUS_WARN   = new Color(0xFFA726);
    private static final Color FB_STATUS_ERROR  = new Color(0xE57373);

    private static final Color[] FB_HIGHLIGHTS = {
        new Color(0x6B2020), new Color(0x6B4A20), new Color(0x5C5C1A), new Color(0x1A5C1A),
        new Color(0x1A5C5C), new Color(0x1A2E6B), new Color(0x3D1A6B), new Color(0x6B1A4A),
    };

    private static final Color[] FB_DEPTHS = {
        new Color(0xB0B7C3), new Color(0x82AAFF), new Color(0xC3E88D),
        new Color(0xFFCB6B), new Color(0xFF9580),
    };

    // ── Surface / background ──────────────────────────────────────────────────

    /** Main log table background. */
    public static Color tableBackground()    { return UIManager.getColor("Table.background"); }

    /**
     * Background of every other log row when stripes are on (Settings → Table): the theme's
     * {@code Clouseau.stripeBackground}, else the table background moved 4% toward the text color.
     */
    public static Color stripeBackground() {
        Color c = UIManager.getColor("Clouseau.stripeBackground");
        if (c != null) return c;
        Color bg = tableBackground(), fg = foreground();
        if (bg == null || fg == null) return bg;
        float t = 0.04f;
        return new Color(Math.round(bg.getRed()   + (fg.getRed()   - bg.getRed())   * t),
                         Math.round(bg.getGreen() + (fg.getGreen() - bg.getGreen()) * t),
                         Math.round(bg.getBlue()  + (fg.getBlue()  - bg.getBlue())  * t));
    }

    /** Detail text-pane background. */
    public static Color detailBackground()   { return UIManager.getColor("TextArea.background"); }

    /** Scroll-pane viewport background (slightly darker than table). */
    public static Color viewportBackground() { return get("Clouseau.viewportBackground", "Table.background"); }

    /** Full-panel loading overlay background. */
    public static Color loadingBackground()  { return get("Clouseau.loadingBackground",  "Panel.background"); }

    /** Splash window background. */
    public static Color splashBackground()   { return UIManager.getColor("Panel.background"); }

    // ── Borders & separators ──────────────────────────────────────────────────

    /** Thin separator line between panels / toolbar borders. */
    public static Color separatorColor()     { return UIManager.getColor("Separator.foreground"); }

    /** Border around panels and scroll panes. */
    public static Color borderColor()        { return UIManager.getColor("Component.borderColor"); }

    // ── Welcome panel key-badge chip ──────────────────────────────────────────

    /** Fill color of a keyboard shortcut badge. */
    public static Color keyBadgeFill()       { return get("Clouseau.keyBadgeFill",   "Button.background"); }

    /** Stroke color of a keyboard shortcut badge. */
    public static Color keyBadgeBorder()     { return get("Clouseau.keyBadgeBorder", "Component.borderColor"); }

    // ── Foreground / text ─────────────────────────────────────────────────────

    /** Primary text color used in the table and detail pane. */
    public static Color foreground()         { return UIManager.getColor("Label.foreground"); }

    /** Secondary / muted text (labels, counters, section headers). */
    public static Color dimForeground()      { return UIManager.getColor("Label.disabledForeground"); }

    /** Very muted text (regex hints, sub-labels). */
    public static Color mutedForeground()    { return get("Clouseau.mutedForeground", "Label.disabledForeground"); }

    /** Blue accent used in section headings and logger-tree depth 1. */
    public static Color accentBlue()         { return get("Clouseau.accentBlue",    "Label.foreground"); }

    /** Key label color in the detail pane. */
    public static Color detailKeyColor()     { return get("Clouseau.detailKeyColor", "Label.foreground"); }

    /**
     * Text of a picker button whose filter is on (the time range). The theme's
     * {@code Clouseau.activeFilterForeground}, else its accent blue, else the label color.
     */
    public static Color activeFilterForeground() {
        return get("Clouseau.activeFilterForeground", "Clouseau.accentBlue", "Label.foreground");
    }

    /** Foreground for a de-selected / disabled toggle button. */
    public static Color offColor()           { return get("Clouseau.offColor", "Label.disabledForeground"); }

    // ── Log-level colors ──────────────────────────────────────────────────────

    /** Foreground color for the given log level. */
    public static Color levelColor(LogLevel level) {
        if (level == null) return foreground();
        Color c = UIManager.getColor("Clouseau.level." + level.name());
        if (c != null) return c;
        return switch (level) {
            case TRACE, UNKNOWN -> FB_LEVEL_TRACE;
            case DEBUG          -> FB_LEVEL_DEBUG;
            case INFO           -> FB_LEVEL_INFO;
            case WARN           -> FB_LEVEL_WARN;
            case ERROR          -> FB_LEVEL_ERROR;
            case FATAL          -> FB_LEVEL_FATAL;
        };
    }

    // ── Status / feedback ─────────────────────────────────────────────────────

    public static Color statusOk()    { return get("Clouseau.status.ok",    null, FB_STATUS_OK); }
    public static Color statusWarn()  { return get("Clouseau.status.warn",  null, FB_STATUS_WARN); }
    public static Color statusError() { return get("Clouseau.status.error", null, FB_STATUS_ERROR); }

    // ── Selection & find-bar search ───────────────────────────────────────────

    /** Row selection background color. */
    public static Color selectionBackground()  { return get("Clouseau.selectionBackground", "Table.selectionBackground"); }

    /** Text on a selected row without a highlight. Black unless the theme sets it. */
    public static Color selectionForeground()  { return get("Clouseau.selectionForeground", null, Color.BLACK); }

    /** Ring around a selected row without a highlight: the theme's, else a brighter selection color. */
    public static Color selectionRing() {
        Color c = UIManager.getColor("Clouseau.selectionRing");
        return c != null ? c : selectionBackground().brighter();
    }

    /**
     * Ring around the selected row while the table doesn't have keyboard focus: the theme's, else
     * the component border color. The row keeps its fill; only the ring dims, as in IntelliJ.
     */
    public static Color selectionRingInactive() {
        return get("Clouseau.selectionRingInactive", "Component.borderColor", Color.GRAY);
    }

    /**
     * FlatLaf style for the scroll panes of reading areas, lists, trees and tables: no amber
     * frame when the view inside has focus. The frame would be the largest amber on screen and
     * says nothing the selection doesn't; focus shows through the selection (bright with focus,
     * dimmer without). Fields and buttons keep their focus ring.
     * <p>
     * Only for a scroll pane that keeps FlatLaf's own frame: FlatLaf hands these keys to the
     * scroll pane's border, so on one given another border (empty, a popup's hairline) they're
     * unknown and FlatLaf logs an {@code UnknownStyleException} each time it applies the style
     * (theme switches). Such a pane has no focus frame to hide anyway.
     */
    public static final String NO_FOCUS_FRAME = "focusedBorderColor: $Component.borderColor; innerFocusWidth: 0";

    /** {@link #NO_FOCUS_FRAME} on {@code scroll}, for a list, tree or table; returns it. */
    public static JScrollPane noFocusFrame(JScrollPane scroll) {
        scroll.putClientProperty("FlatLaf.style", NO_FOCUS_FRAME);
        return scroll;
    }

    /**
     * {@link #NO_FOCUS_FRAME} on every scroll pane inside {@code root}, now and as they're added:
     * for components that build their views themselves, such as a JFileChooser switching between
     * its list and details views. Scroll panes they gave another border are left alone (see
     * {@link #NO_FOCUS_FRAME}).
     */
    public static void noFocusFrames(Container root) {
        if (root instanceof JScrollPane s && s.getBorder() instanceof com.formdev.flatlaf.ui.FlatBorder) {
            noFocusFrame(s);
        }
        if (root instanceof JComponent jc) {
            if (jc.getClientProperty("Clouseau.noFocusFrames") != null) return;
            jc.putClientProperty("Clouseau.noFocusFrames", Boolean.TRUE);
        }
        for (Component c : root.getComponents()) if (c instanceof Container k) noFocusFrames(k);
        root.addContainerListener(new java.awt.event.ContainerAdapter() {
            @Override public void componentAdded(java.awt.event.ContainerEvent e) {
                if (e.getChild() instanceof Container k) noFocusFrames(k);
            }
        });
    }

    /**
     * Find's mark behind matched text: a light, translucent box with an outline, so it shows on
     * any row (plain, striped, highlighted, selected) and never reads as a highlight fill.
     */
    public static Color findMarkBackground() { return get("Clouseau.findMarkBg", "Table.background"); }
    public static Color findMarkBorder()     { return get("Clouseau.findMarkBorder", "Label.foreground"); }

    // ── Row-highlight palette ─────────────────────────────────────────────────

    /** Returns the 8-color highlight palette, reading from UIManager when available. */
    public static Color[] highlightColors() {
        Color[] result = new Color[FB_HIGHLIGHTS.length];
        for (int i = 0; i < result.length; i++) {
            Color c = UIManager.getColor("Clouseau.highlight." + i);
            result[i] = c != null ? c : FB_HIGHLIGHTS[i];
        }
        return result;
    }

    // ── Highlight navigation bar ──────────────────────────────────────────────

    /** Fill for the "All" button when it is the active filter. */
    public static Color highlightNavSelected()   { return get("Clouseau.highlightNav.selected",   null, new Color(0x4A4A4A)); }

    /** Fill for the "All" button when a specific color is the active filter. */
    public static Color highlightNavUnselected() { return get("Clouseau.highlightNav.unselected", null, new Color(0x2A2A2A)); }

    // ── Logger-tree depth colors ──────────────────────────────────────────────

    /** Returns the 5-entry depth color array, reading from UIManager when available. */
    public static Color[] loggerDepthColors() {
        Color[] result = new Color[FB_DEPTHS.length];
        for (int i = 0; i < result.length; i++) {
            Color c = UIManager.getColor("Clouseau.logger.depth." + i);
            result[i] = c != null ? c : FB_DEPTHS[i];
        }
        return result;
    }

    // ── Annotations ───────────────────────────────────────────────────────────

    /** Accent color for the annotation indicator (✎) in the table and detail panel. */
    public static Color annotationIndicatorColor() { return get("Clouseau.annotationIndicator", null, new Color(0xF5A623)); }

    // ── Plugin status ─────────────────────────────────────────────────────────

    public static Color pluginEnabled()  { return get("Clouseau.plugin.enabled",  null, FB_LEVEL_DEBUG); }
    public static Color pluginDisabled() { return get("Clouseau.plugin.disabled", null, FB_LEVEL_WARN); }
    public static Color pluginFailed()   { return get("Clouseau.plugin.failed",   null, FB_LEVEL_ERROR); }

    // ── Loading progress bar ──────────────────────────────────────────────────

    public static Color progressForeground() { return get("Clouseau.progressForeground", null, FB_LEVEL_INFO); }
    public static Color progressBackground() { return get("Clouseau.progressBackground", "Clouseau.separatorColor", "Separator.foreground"); }

    // ── Misc ──────────────────────────────────────────────────────────────────

    /** Favorites-sidebar star icon color. */
    public static Color starColor()       { return get("Clouseau.starColor", null, new Color(0xFFD700)); }

    /** Peak flash color for the detail-pane copy animation. */
    public static Color flashPeakColor()  { return get("Clouseau.flashPeakColor", "Clouseau.dimForeground", "Label.disabledForeground"); }

    // ── JSON syntax-highlight palette (returned as packed RGB int) ────────────

    // Fallbacks are the Dark theme's values. Keys orchid, strings cyan, numbers and
    // true/false/null orange: hues no level or detail label uses, so a payload never reads as one.

    public static int jsonKeyColor()      { return colorToRgb("Clouseau.json.key",     0xD896DA); }
    public static int jsonStringColor()   { return colorToRgb("Clouseau.json.string",  0x8FD3E8); }
    public static int jsonNumberColor()   { return colorToRgb("Clouseau.json.number",  0xF0B37E); }
    public static int jsonBoolNullColor() { return colorToRgb("Clouseau.json.boolNull",0xF0B37E); }
    public static int jsonPunctColor()    { return colorToRgb("Clouseau.json.punct",   0x8A8F99); }

    // ── Colors that follow theme switches ─────────────────────────────────────

    private static final String THEMED_FOREGROUND = "Clouseau.themedForeground";
    private static final String THEMED_BACKGROUND = "Clouseau.themedBackground";
    private static final String ON_THEME_CHANGE   = "Clouseau.onThemeChange";

    /**
     * Sets {@code c}'s foreground from {@code color} now, and again after every live theme
     * switch.
     *
     * <p>A plain {@code setForeground(ClouseauColors.x())} doesn't survive a switch: theme
     * colors are {@code UIResource}s, and the look and feel's {@code updateUI} replaces any
     * {@code UIResource} color with its own default. Use this for components that outlive a
     * theme switch (anything in a tab or the main window); a dialog built each time it opens
     * can keep using {@code setForeground}. For a color that depends on state, give a
     * supplier that reads the state, and call this again (or {@code setForeground}) when the
     * state changes.
     */
    public static void themedForeground(JComponent c, java.util.function.Supplier<Color> color) {
        c.putClientProperty(THEMED_FOREGROUND, color);
        c.setForeground(color.get());
    }

    /** Like {@link #themedForeground}, for the background. */
    public static void themedBackground(JComponent c, java.util.function.Supplier<Color> color) {
        c.putClientProperty(THEMED_BACKGROUND, color);
        c.setBackground(color.get());
    }

    /**
     * Runs {@code action} after every live theme switch, once the colors set with
     * {@link #themedForeground} and {@link #themedBackground} are back. For state that holds
     * theme colors itself, such as a table model's row highlights. One action per component.
     */
    public static void onThemeChange(JComponent c, Runnable action) {
        c.putClientProperty(ON_THEME_CHANGE, action);
    }

    /**
     * Re-applies the colors set with {@link #themedForeground} and {@link #themedBackground}
     * to {@code root} and everything inside it. {@link ThemeManager#updateUI()} calls this for
     * every window after the look and feel has refreshed the components.
     */
    @SuppressWarnings("unchecked")
    public static void reapplyThemed(Component root) {
        if (root instanceof JComponent jc) {
            if (jc.getClientProperty(THEMED_FOREGROUND) instanceof java.util.function.Supplier<?> fg)
                jc.setForeground(((java.util.function.Supplier<Color>) fg).get());
            if (jc.getClientProperty(THEMED_BACKGROUND) instanceof java.util.function.Supplier<?> bg)
                jc.setBackground(((java.util.function.Supplier<Color>) bg).get());
            if (jc.getClientProperty(ON_THEME_CHANGE) instanceof Runnable action) action.run();
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) reapplyThemed(child);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Tries the primary key, then the fallback key, then returns {@code Color.GRAY}. */
    private static Color get(String key, String fallbackKey) {
        Color c = UIManager.getColor(key);
        if (c != null) return c;
        if (fallbackKey != null) {
            c = UIManager.getColor(fallbackKey);
            if (c != null) return c;
        }
        return Color.GRAY;
    }

    /** Tries the primary key, then the fallback key, then {@code fallbackColor}. */
    private static Color get(String key, String fallbackKey, Color fallbackColor) {
        Color c = UIManager.getColor(key);
        if (c != null) return c;
        if (fallbackKey != null) {
            c = UIManager.getColor(fallbackKey);
            if (c != null) return c;
        }
        return fallbackColor;
    }

    /** Tries the primary key, then the fallback UIManager key, then a Color from its string name. */
    private static Color get(String key, String fallbackKey, String fallbackKeyStr) {
        Color c = UIManager.getColor(key);
        if (c != null) return c;
        if (fallbackKey != null) {
            c = UIManager.getColor(fallbackKey);
            if (c != null) return c;
        }
        if (fallbackKeyStr != null) {
            c = UIManager.getColor(fallbackKeyStr);
            if (c != null) return c;
        }
        return Color.GRAY;
    }

    private static int colorToRgb(String key, int fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? (c.getRGB() & 0xFFFFFF) : fallback;
    }
}
