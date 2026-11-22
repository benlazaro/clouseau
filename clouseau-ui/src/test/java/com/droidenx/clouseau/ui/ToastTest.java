package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Where a toast goes: the middle of its area, or out of the way of what it must not cover. */
class ToastTest {

    private static final Rectangle AREA = new Rectangle(0, 100, 600, 400);   // y 100..500
    private static final Dimension SIZE = new Dimension(200, 30);

    @Test
    void middleOfTheAreaWithNothingToKeepClear() {
        assertThat(Toast.place(AREA, SIZE, null)).isEqualTo(new Rectangle(200, 285, 200, 30));
    }

    @Test
    void middleWhenItDoesNotTouchWhatToKeepClear() {
        Rectangle row = new Rectangle(0, 120, 600, 24);
        assertThat(Toast.place(AREA, SIZE, row)).isEqualTo(new Rectangle(200, 285, 200, 30));
    }

    @Test
    void movesBelowARowInTheUpperMiddle() {
        Rectangle row = new Rectangle(0, 280, 600, 24);            // the middle, a bit high
        Rectangle at = Toast.place(AREA, SIZE, row);
        assertThat(at.intersects(row)).isFalse();
        assertThat(at.y).isGreaterThan(row.y + row.height);
        assertThat(at.y + at.height).isLessThanOrEqualTo(AREA.y + AREA.height);
    }

    @Test
    void movesAboveARowInTheLowerMiddle() {
        Rectangle row = new Rectangle(0, 300, 600, 24);            // more room above than below
        Rectangle at = Toast.place(AREA, SIZE, row);
        assertThat(at.intersects(row)).isFalse();
        assertThat(at.y + at.height).isLessThan(row.y);
        assertThat(at.y).isGreaterThanOrEqualTo(AREA.y);
    }

    @Test
    void staysHorizontallyCentered() {
        Rectangle row = new Rectangle(0, 290, 600, 24);
        assertThat(Toast.place(AREA, SIZE, row).x).isEqualTo(200);
    }
}
