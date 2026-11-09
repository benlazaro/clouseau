package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry.LogLevel;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Colors set through ClouseauColors.themedForeground/Background survive a live theme switch. */
class ThemedColorsTest {

    private static final Color RED  = new ColorUIResource(0xEF5350);
    private static final Color BLUE = new ColorUIResource(0x2196F3);

    @AfterEach
    void cleanUp() {
        UIManager.put("Test.themedColor", null);
        UIManager.put("Clouseau.level.ERROR", null);
        UIManager.put("Clouseau.selectionForeground", null);
        UIManager.put("Clouseau.selectionRing", null);
        UIManager.put("Clouseau.stripeBackground", null);
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    @Test
    void plainSetForegroundIsLostOnUpdateUiButThemedIsReapplied() throws Exception {
        onEdt(() -> {
            UIManager.put("Test.themedColor", RED);
            JLabel plain = new JLabel("plain");
            plain.setForeground(UIManager.getColor("Test.themedColor"));
            JLabel themed = new JLabel("themed");
            ClouseauColors.themedForeground(themed, () -> UIManager.getColor("Test.themedColor"));
            JPanel window = new JPanel();
            window.add(plain);
            window.add(themed);

            // A theme switch: new values, then the look and feel refreshes every component.
            UIManager.put("Test.themedColor", BLUE);
            SwingUtilities.updateComponentTreeUI(window);
            ClouseauColors.reapplyThemed(window);

            assertThat(plain.getForeground()).isEqualTo(UIManager.getColor("Label.foreground"));  // the bug
            assertThat(themed.getForeground()).isEqualTo(BLUE);
        });
    }

    @Test
    void themedBackgroundIsReapplied() throws Exception {
        onEdt(() -> {
            UIManager.put("Test.themedColor", RED);
            JScrollPane scroll = new JScrollPane(new JTable());
            ClouseauColors.themedBackground(scroll.getViewport(), () -> UIManager.getColor("Test.themedColor"));

            UIManager.put("Test.themedColor", BLUE);
            SwingUtilities.updateComponentTreeUI(scroll);
            ClouseauColors.reapplyThemed(scroll);

            assertThat(scroll.getViewport().getBackground()).isEqualTo(BLUE);
        });
    }

    @Test
    void levelButtonsKeepLevelColorsAcrossThemeSwitch() throws Exception {
        onEdt(() -> {
            UIManager.put("Clouseau.level.ERROR", RED);
            FilterBar bar = new FilterBar(() -> {});
            JToggleButton error = bar.levelButton(LogLevel.ERROR);
            assertThat(error.getForeground()).isEqualTo(RED);

            UIManager.put("Clouseau.level.ERROR", BLUE);
            SwingUtilities.updateComponentTreeUI(bar);
            ClouseauColors.reapplyThemed(bar);
            assertThat(error.getForeground()).isEqualTo(BLUE);

            error.setSelected(false);
            assertThat(error.getForeground()).isEqualTo(ClouseauColors.offColor());
            error.setSelected(true);
            assertThat(error.getForeground()).isEqualTo(BLUE);
        });
    }
    @Test
    void onThemeChangeRunsAfterThemedColorsAreBack() throws Exception {
        onEdt(() -> {
            UIManager.put("Test.themedColor", RED);
            JTable table = new JTable();
            ClouseauColors.themedBackground(table, () -> UIManager.getColor("Test.themedColor"));
            Color[] seen = new Color[1];
            ClouseauColors.onThemeChange(table, () -> seen[0] = table.getBackground());
            JScrollPane scroll = new JScrollPane(table);

            UIManager.put("Test.themedColor", BLUE);
            SwingUtilities.updateComponentTreeUI(scroll);
            ClouseauColors.reapplyThemed(scroll);

            assertThat(seen[0]).isEqualTo(BLUE);
        });
    }

    @Test
    void selectionTextIsBlackUnlessTheThemeSetsIt() {
        assertThat(ClouseauColors.selectionForeground()).isEqualTo(Color.BLACK);
        UIManager.put("Clouseau.selectionForeground", BLUE);
        assertThat(ClouseauColors.selectionForeground()).isEqualTo(BLUE);
    }

    @Test
    void selectionRingFallsBackToABrighterSelection() {
        assertThat(ClouseauColors.selectionRing()).isEqualTo(ClouseauColors.selectionBackground().brighter());
        UIManager.put("Clouseau.selectionRing", RED);
        assertThat(ClouseauColors.selectionRing()).isEqualTo(RED);
    }
    @Test
    void stripesUseTheThemeColorElseAFaintStepTowardTheText() {
        UIManager.put("Clouseau.stripeBackground", RED);
        assertThat(ClouseauColors.stripeBackground()).isEqualTo(RED);
        UIManager.put("Clouseau.stripeBackground", null);
        Color bg = ClouseauColors.tableBackground();
        Color stripe = ClouseauColors.stripeBackground();
        assertThat(stripe).isNotNull();
        int step = Math.abs(stripe.getRed() - bg.getRed()) + Math.abs(stripe.getGreen() - bg.getGreen())
                 + Math.abs(stripe.getBlue() - bg.getBlue());
        assertThat(step).isBetween(1, 40);
    }
}
