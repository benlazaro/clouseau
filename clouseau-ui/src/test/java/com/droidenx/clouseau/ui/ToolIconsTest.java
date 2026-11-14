package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauDarkLaf;
import com.droidenx.clouseau.ui.theme.ClouseauLightLaf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

class ToolIconsTest {

    /** The look and feel before the test, put back after it so later tests in this JVM don't see this theme. */
    private LookAndFeel before;

    @BeforeEach
    void rememberLookAndFeel() {
        before = UIManager.getLookAndFeel();
    }

    @AfterEach
    void restoreLookAndFeel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(before);
            } catch (UnsupportedLookAndFeelException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void blendMixesTheTwoColors() {
        assertThat(ToolIcons.blend(Color.WHITE, Color.BLACK, 0.6f)).isEqualTo(new Color(153, 153, 153));
        assertThat(ToolIcons.blend(Color.RED, Color.BLUE, 1f)).isEqualTo(Color.RED);
    }

    @Test
    void hoveredToolStandsOutOnASelectedRowInBothThemes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ClouseauDarkLaf.setup();
            assertHoverStandsOut();
            ClouseauLightLaf.setup();
            assertHoverStandsOut();
        });
    }

    private static void assertHoverStandsOut() {
        for (String ui : new String[] {"List", "Tree"}) {
            for (boolean selected : new boolean[] {false, true}) {
                Color idle = ToolIcons.idleColor(selected, ui);
                Color lit = ToolIcons.litColor(selected, ui);
                assertThat(distance(idle, lit)).as("%s, selected=%s", ui, selected).isGreaterThan(40);
            }
        }
    }

    private static int distance(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen())
                + Math.abs(a.getBlue() - b.getBlue());
    }
}
