package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauDarkLaf;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RoundedFileChooserTest {

    @Test
    void lookInComboGetsTheRoundBorderAfterEachUiInstall() throws Exception {
        AtomicReference<RoundedFileChooser> chooser = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ClouseauDarkLaf.setup();
            chooser.set(new RoundedFileChooser());
        });
        SwingUtilities.invokeAndWait(() -> { });   // the border is set in a later event
        SwingUtilities.invokeAndWait(() -> assertThat(lookInCombos(chooser.get()))
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.getBorder()).isInstanceOf(RoundedFileChooser.LookInBorder.class)));

        SwingUtilities.invokeAndWait(() -> SwingUtilities.updateComponentTreeUI(chooser.get()));   // a theme switch
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> assertThat(lookInCombos(chooser.get()))
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.getBorder()).isInstanceOf(RoundedFileChooser.LookInBorder.class)));
    }

    private static List<JComboBox<?>> lookInCombos(Container c) {
        List<JComboBox<?>> found = new ArrayList<>();
        for (Component child : c.getComponents()) {
            if (child instanceof JComboBox<?> combo
                    && Boolean.TRUE.equals(combo.getClientProperty("JComboBox.isTableCellEditor"))) found.add(combo);
            else if (child instanceof Container sub) found.addAll(lookInCombos(sub));
        }
        return found;
    }
}
