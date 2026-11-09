package com.droidenx.clouseau.ui.theme;

import com.formdev.flatlaf.FlatDarkLaf;

/**
 * Proposed revision of Clouseau Dark, offered as its own built-in theme ("Dark (revised)")
 * so it can be compared with the current one before replacing it.
 *
 * <p>FlatLaf finds {@code ClouseauDarkRevisedLaf.properties} by this class name, like
 * {@link ClouseauDarkLaf}. The values come from the Clouseau design system's
 * {@code revised} theme: one neutral gray ramp, a dark selection with light text, the
 * logo's amber as the only accent (tab underline and keyboard focus), and level colors
 * readable on every row background.
 */
public final class ClouseauDarkRevisedLaf extends FlatDarkLaf {

    public static final String NAME = "Clouseau Dark (revised)";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Clouseau Dark (revised) Look and Feel";
    }

    @Override
    public boolean isDark() {
        return true;
    }
}
