package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The highlight swatch row shared by the row menu and the find bar (DESIGN.md 6.14). */
class SwatchRowTest {

    @Test
    void markedSlotSaysWhatEveryRowAlreadyIs() {
        Color[] palette = ClouseauColors.highlightColors();
        Map<Integer, Color> colors = new HashMap<>();
        assertThat(RowMenu.markedSlot(List.of(1, 2), colors::get)).isEqualTo(RowMenu.NONE);
        colors.put(1, palette[4]);
        assertThat(RowMenu.markedSlot(List.of(1, 2), colors::get)).isEqualTo(RowMenu.NO_MARK);
        colors.put(2, palette[4]);
        assertThat(RowMenu.markedSlot(List.of(1, 2), colors::get)).isEqualTo(4);
        assertThat(RowMenu.markedSlot(List.of(), colors::get)).isEqualTo(RowMenu.NO_MARK);
    }

    @Test
    void noneComesFirstThenTheColorsInSlotOrderEachNamed() throws Exception {
        RowMenu.SwatchRow row = onEdt(() -> RowMenu.swatchRow("Highlight:", 3, slot -> { }));
        List<RowMenu.Swatch> swatches = row.swatches();
        assertThat(swatches).hasSize(1 + ClouseauColors.highlightColors().length);
        assertThat(swatches.get(0).slot).isEqualTo(RowMenu.NONE);
        assertThat(swatches.get(0).getToolTipText()).isEqualTo("None");
        assertThat(swatches.get(1).getToolTipText()).isEqualTo("Red");
        assertThat(swatches.stream().filter(s -> s.marked).map(s -> s.slot)).containsExactly(3);
    }

    @Test
    void theKeysStartOnTheRingedSwatchMoveAlongTheRowAndApplyIt() throws Exception {
        List<Integer> picked = new ArrayList<>();
        RowMenu.SwatchRow row = onEdt(() -> RowMenu.swatchRow("Highlight:", 3, picked::add));
        onEdt(() -> { row.menuSelectionChanged(true); return null; });
        assertThat(row.current()).isEqualTo(4);   // slot 3, after None
        assertThat(row.swatches().get(4).isCurrent()).isTrue();

        press(row, KeyEvent.VK_RIGHT);
        press(row, KeyEvent.VK_RIGHT);
        press(row, KeyEvent.VK_LEFT);
        assertThat(row.current()).isEqualTo(5);
        press(row, KeyEvent.VK_ENTER);
        assertThat(picked).containsExactly(4);
    }

    @Test
    void withNothingRingedTheKeysStartOnNoneAndLeavingTheRowClearsIt() throws Exception {
        List<Integer> picked = new ArrayList<>();
        RowMenu.SwatchRow row = onEdt(() -> RowMenu.swatchRow("Highlight 3 rows:", RowMenu.NO_MARK, picked::add));
        onEdt(() -> { row.menuSelectionChanged(true); return null; });
        assertThat(row.current()).isZero();
        press(row, KeyEvent.VK_LEFT);   // wraps, as Up and Down do in a menu
        assertThat(row.current()).isEqualTo(row.swatches().size() - 1);
        press(row, KeyEvent.VK_RIGHT);
        press(row, KeyEvent.VK_SPACE);
        assertThat(picked).containsExactly(RowMenu.NONE);

        onEdt(() -> { row.menuSelectionChanged(false); return null; });
        assertThat(row.current()).isEqualTo(-1);
        assertThat(row.swatches()).noneMatch(RowMenu.Swatch::isCurrent);
    }

    @Test
    void keysDoNothingUntilTheRowIsSelected() throws Exception {
        List<Integer> picked = new ArrayList<>();
        RowMenu.SwatchRow row = onEdt(() -> RowMenu.swatchRow("Highlight:", RowMenu.NONE, picked::add));
        press(row, KeyEvent.VK_ENTER);
        assertThat(picked).isEmpty();
        assertThat(row.current()).isEqualTo(-1);
    }

    private static void press(RowMenu.SwatchRow row, int key) throws Exception {
        onEdt(() -> {
            KeyEvent e = new KeyEvent(row, KeyEvent.KEY_PRESSED, 0L, 0, key, KeyEvent.CHAR_UNDEFINED);
            row.processKeyEvent(e, new MenuElement[]{row}, MenuSelectionManager.defaultManager());
            return null;
        });
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> task) throws Exception {
        Object[] out = new Object[1];
        Exception[] error = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try { out[0] = task.call(); } catch (Exception e) { error[0] = e; }
        });
        if (error[0] != null) throw error[0];
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }
}
