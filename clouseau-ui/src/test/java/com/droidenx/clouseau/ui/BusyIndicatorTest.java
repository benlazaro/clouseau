package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** The busy bar and fade over the rows: shown only after a delay, for a filter or a find. */
class BusyIndicatorTest {

    private static <T> T onEdt(Callable<T> c) throws Exception {
        Object[] out = new Object[1];
        Exception[] err = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try { out[0] = c.call(); } catch (Exception e) { err[0] = e; }
        });
        if (err[0] != null) throw err[0];
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    private static BusyIndicator indicator() throws Exception {
        return onEdt(() -> new BusyIndicator(new JScrollPane(new JTable())));
    }

    private static void waitPastDelay() throws Exception {
        Thread.sleep(BusyIndicator.DELAY_MS + 200);
        onEdt(() -> null);   // let the timer's event run
    }

    @Test
    void aFilterShowsTheBarAndFadesTheRowsOnlyAfterTheDelay() throws Exception {
        BusyIndicator busy = indicator();
        onEdt(() -> { busy.filterProgress(0); return null; });
        assertThat(onEdt(busy::showsBar)).isFalse();
        waitPastDelay();
        assertThat(onEdt(busy::showsBar)).isTrue();
        assertThat(onEdt(busy::fadesRows)).isTrue();
        onEdt(() -> { busy.filterProgress(40); return null; });
        assertThat(onEdt(busy::percent)).isEqualTo(40);
        onEdt(() -> { busy.filterProgress(-1); return null; });
        assertThat(onEdt(busy::showsBar)).isFalse();
        assertThat(onEdt(busy::fadesRows)).isFalse();
    }

    @Test
    void aFindShowsTheBarAndFadesTheRowsToo() throws Exception {
        BusyIndicator busy = indicator();
        onEdt(() -> { busy.findProgress(0); return null; });
        waitPastDelay();
        assertThat(onEdt(busy::showsBar)).isTrue();
        assertThat(onEdt(busy::fadesRows)).isTrue();
        onEdt(() -> { busy.findProgress(-1); return null; });
        assertThat(onEdt(busy::showsBar)).isFalse();
        assertThat(onEdt(busy::fadesRows)).isFalse();
    }

    @Test
    void workThatEndsBeforeTheDelayShowsNothing() throws Exception {
        BusyIndicator busy = indicator();
        onEdt(() -> { busy.filterProgress(0); busy.filterProgress(-1); return null; });
        waitPastDelay();
        assertThat(onEdt(busy::showsBar)).isFalse();
    }

    @Test
    void theBarFollowsTheFilterWhileBothRun() throws Exception {
        BusyIndicator busy = indicator();
        onEdt(() -> { busy.findProgress(80); busy.filterProgress(10); return null; });
        assertThat(onEdt(busy::percent)).isEqualTo(10);
        onEdt(() -> { busy.filterProgress(-1); return null; });
        assertThat(onEdt(busy::percent)).isEqualTo(80);
    }
}
