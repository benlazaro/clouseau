package com.droidenx.clouseau.ui.theme;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.formdev.flatlaf.util.FontUtils;

import java.awt.Font;

/**
 * The fonts Clouseau ships, the same pair IntelliJ uses: Inter for the interface and
 * JetBrains Mono for log text and code. Both come from FlatLaf's font modules (SIL Open
 * Font License), so they look the same on every platform.
 */
public final class ClouseauFonts {

    /** The interface font. */
    public static final String UI_FAMILY = FlatInterFont.FAMILY;

    /** Log text, the detail pane, parser patterns and other code-like text. */
    public static final String MONO_FAMILY = FlatJetBrainsMonoFont.FAMILY;

    private static boolean installed;

    private ClouseauFonts() {}

    /**
     * Registers both families and makes them FlatLaf's preferred fonts, so every theme's
     * {@code defaultFont} is Inter. Call before the first theme is set up; later calls do nothing.
     */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        FlatInterFont.installBasic();
        FlatJetBrainsMonoFont.install();
        FlatLaf.setPreferredFontFamily(UI_FAMILY);
        FlatLaf.setPreferredMonospacedFontFamily(MONO_FAMILY);
    }

    /**
     * JetBrains Mono in {@code style} at {@code size}. Not a {@code UIResource}, so a component
     * keeps it across a live theme switch.
     */
    public static Font mono(int style, int size) {
        install();
        return FontUtils.getCompositeFont(MONO_FAMILY, style, size).deriveFont(style, (float) size);
    }
}
