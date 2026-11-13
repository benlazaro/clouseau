package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TabDragSlotTest {

    /** One row of tabs with these widths, laid out from x = 0. */
    private static List<Rectangle> row(int... widths) {
        List<Rectangle> r = new ArrayList<>();
        int x = 0;
        for (int w : widths) {
            r.add(new Rectangle(x, 0, w, 30));
            x += w;
        }
        return r;
    }

    @Test
    void staysPutUntilItsEdgeCrossesTheNeighboursMiddle() {
        List<Rectangle> tabs = row(100, 100, 100);
        assertThat(TabDragHandler.slotFor(tabs, 0, 49)).isEqualTo(0);   // right edge 149, middle 150
        assertThat(TabDragHandler.slotFor(tabs, 0, 51)).isEqualTo(1);
        assertThat(TabDragHandler.slotFor(tabs, 2, 151)).isEqualTo(2);  // left edge 151, middle 150
        assertThat(TabDragHandler.slotFor(tabs, 2, 149)).isEqualTo(1);
    }

    @Test
    void passesSeveralTabsInOneFastMove() {
        assertThat(TabDragHandler.slotFor(row(100, 100, 100, 100), 0, 260)).isEqualTo(3);
        assertThat(TabDragHandler.slotFor(row(100, 100, 100, 100), 3, 10)).isEqualTo(0);
    }

    @Test
    void aNarrowTabPassingAWideOneDoesNotFlipBack() {
        // The old rule swapped whenever the mouse was over another tab: a 50px tab dragged
        // onto a 200px one swapped, landed with the mouse over the wide tab, and swapped back.
        int left = 101;   // right edge 151 passes the wide tab's middle (150)
        assertThat(TabDragHandler.slotFor(row(50, 200), 0, left)).isEqualTo(1);
        // Laid out again after the swap, the same position keeps it there.
        assertThat(TabDragHandler.slotFor(row(200, 50), 1, left)).isEqualTo(1);
    }

    @Test
    void aWideTabPassingANarrowOneDoesNotFlipBack() {
        int left = 26;    // right edge 226 passes the narrow tab's middle (225)
        assertThat(TabDragHandler.slotFor(row(200, 50), 0, left)).isEqualTo(1);
        assertThat(TabDragHandler.slotFor(row(50, 200), 1, left)).isEqualTo(1);
    }

    @Test
    void onlyTabsInTheSameRowCount() {
        List<Rectangle> tabs = row(100, 100);
        tabs.add(new Rectangle(0, 30, 100, 30));   // wrapped onto a second row
        assertThat(TabDragHandler.slotFor(tabs, 1, 400)).isEqualTo(1);
    }
}
