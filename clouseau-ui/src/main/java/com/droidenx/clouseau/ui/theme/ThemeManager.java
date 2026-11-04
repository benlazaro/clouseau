package com.droidenx.clouseau.ui.theme;

import com.droidenx.clouseau.ui.AppPrefs;
import com.formdev.flatlaf.*;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Manages built-in and user-installed FlatLaf themes.
 *
 * <p>Built-in themes are baked into the application. User themes are
 * {@code .properties} files placed in {@code ~/.clouseau/themes/}.
 * A minimal user theme looks like:
 * <pre>
 *   # required — tells FlatLaf which base LAF to extend
 *   @baseTheme = FlatDarkLaf
 *   @background = #1e1e2e
 *   @foreground = #cdd6f4
 * </pre>
 */
@Slf4j
public final class ThemeManager {

    public static final String DEFAULT_THEME = "Dark";

    /** Ordered map of builtin-name → setup runnable. */
    private static final LinkedHashMap<String, Runnable> BUILTINS = new LinkedHashMap<>();

    static {
        BUILTINS.put("Dark", () -> setup(new ClouseauDarkLaf()));
//        BUILTINS.put("FlatLaf Dark",  () -> setup(new FlatDarkLaf()));
        BUILTINS.put("Light", () -> setup(new FlatLightLaf()));
//        for (UIManager.LookAndFeelInfo info : FlatAllIJThemes.INFOS) {
//            String name      = info.getName();
//            String className = info.getClassName();
//            BUILTINS.put(name, () -> setupFromClassName(className));
//        }
    }

    /** Represents one entry in the theme list (builtin or user-installed). */
    public record ThemeEntry(String name, boolean builtin, Path file) {}

    private ThemeManager() {}

    // ── Discovery ─────────────────────────────────────────────────────────────

    /** All themes: built-ins first, then user-installed sorted by name. */
    public static List<ThemeEntry> getAllThemes() {
        List<ThemeEntry> list = new ArrayList<>();
        BUILTINS.keySet().forEach(name -> list.add(new ThemeEntry(name, true, null)));
        Path dir = userThemesDir();
        if (Files.isDirectory(dir)) {
            try {
                Files.list(dir)
                        .filter(p -> p.getFileName().toString().endsWith(".properties"))
                        .sorted()
                        .forEach(p -> {
                            String name = p.getFileName().toString().replaceAll("\\.properties$", "");
                            list.add(new ThemeEntry(name, false, p));
                        });
            } catch (IOException e) {
                log.warn("Could not list user themes", e);
            }
        }
        return list;
    }

    /** Only user-installed (non-builtin) themes. */
    public static List<ThemeEntry> getUserThemes() {
        return getAllThemes().stream().filter(t -> !t.builtin()).toList();
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    /**
     * Applies the named theme. Looks up built-ins first, then user themes.
     * Falls back to {@link #DEFAULT_THEME} if the name is unknown.
     * Must be called on the EDT or before the UI is shown.
     */
    public static void applyTheme(String name) {
        Runnable builtin = BUILTINS.get(name);
        if (builtin != null) {
            builtin.run();
            return;
        }
        Path themeFile = userThemesDir().resolve(name + ".properties");
        if (Files.exists(themeFile)) {
            try {
                FlatLaf.setGlobalExtraDefaults(null);
                FlatLaf.setup(new FlatPropertiesLaf(name, themeFile.toFile()));
                applyNeutralOverrides();
                return;
            } catch (Exception e) {
                log.warn("Failed to apply user theme '{}', falling back to default", name, e);
            }
        }
        log.warn("Unknown theme '{}', applying default", name);
        setup(new ClouseauDarkLaf());
    }

    /** Refreshes all open windows after a theme switch. Call on EDT only. */
    public static void updateUI() {
        FlatLaf.updateUI();
        // updateUI resets UIResource colors to the look and feel's defaults; put back the
        // ones components asked to keep (ClouseauColors.themedForeground/Background).
        for (java.awt.Window w : java.awt.Window.getWindows()) ClouseauColors.reapplyThemed(w);
    }

    // ── Install / uninstall ───────────────────────────────────────────────────

    /**
     * Copies {@code source} into {@code ~/.clouseau/themes/} and returns the
     * resulting theme name (filename without {@code .properties}).
     */
    public static String installTheme(Path source) throws IOException {
        Path dir = userThemesDir();
        Files.createDirectories(dir);
        Path dest = dir.resolve(source.getFileName());
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        return dest.getFileName().toString().replaceAll("\\.properties$", "");
    }

    /** Deletes a user theme's file from disk. No-op for built-in themes. */
    public static void uninstallTheme(ThemeEntry theme) throws IOException {
        if (theme.builtin() || theme.file() == null) return;
        Files.deleteIfExists(theme.file());
    }

    static Path userThemesDir() {
        return AppPrefs.PREFS_DIR.resolve("themes");
    }

    // ── Private setup helpers ─────────────────────────────────────────────────

    private static void setup(LookAndFeel laf) {
        FlatLaf.setGlobalExtraDefaults(null);
        FlatLaf.setup(laf);
        applyNeutralOverrides();
    }

    /** Structural/layout overrides that apply regardless of which theme is active. */
    private static void applyNeutralOverrides() {
        UIManager.put("defaultFont",                  new FontUIResource(new Font(Font.SANS_SERIF, Font.PLAIN, 13)));
    }
}
