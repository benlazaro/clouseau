package com.droidenx.clouseau.ui.theme;

import com.formdev.flatlaf.FlatLightLaf;

/**
 * Clouseau's Light theme. FlatLaf finds {@code ClouseauLightLaf.properties} by this class
 * name, like {@link ClouseauDarkLaf}. A class of its own, rather than additions to
 * {@code FlatLightLaf}, so Clouseau's values don't leak into themes users install on
 * {@code @baseTheme = FlatLightLaf}.
 */
public final class ClouseauLightLaf extends FlatLightLaf {

    public static final String NAME = "Clouseau Light";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Clouseau Light Look and Feel";
    }

    @Override
    public boolean isDark() {
        return false;
    }
}
