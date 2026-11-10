package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TabStatusBarTest {

    /** Issue #6: a merged tab's long list of files widened every tab past the window's edge. */
    @Test
    void aLongPathDoesNotWidenTheTab() throws Exception {
        AtomicInteger shortMin = new AtomicInteger();
        AtomicInteger longMin = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            TabStatusBar bar = new TabStatusBar(() -> {});
            bar.setRows(10, 10, 0, "");
            bar.setPath("a.log", null);
            shortMin.set(bar.getMinimumSize().width);
            bar.setPath("10 files: " + "some-service-6.0.0.7-SNAPSHOT.log.gz, ".repeat(10), null);
            longMin.set(bar.getMinimumSize().width);
        });
        assertThat(longMin.get()).isEqualTo(shortMin.get());
    }
}
