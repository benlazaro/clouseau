package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.ConnectingLogSource;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSource;
import com.droidenx.clouseau.core.RegexLogParser;
import com.droidenx.clouseau.core.SourceConnection.State;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** A live-source tab's connection state (issue #3) and Reconnect (issue #4). */
class LiveSourceTabTest {

    private static final LogParser PARSER = new RegexLogParser("test",
            Pattern.compile("(?<timestamp>\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d) (?<level>[A-Z]+) (?<message>.*)"),
            "yyyy-MM-dd HH:mm:ss");

    private static final List<String> LINES = List.of(
            "2026-11-06 10:00:01 INFO one",
            "2026-11-06 10:00:02 WARN two");

    /**
     * Waits for {@link #go}, reports its connection (if {@code reports}), sends {@link #LINES},
     * then either returns ({@code ends}) or blocks until closed, like {@code tail -F}.
     */
    private static final class FakeSource implements ConnectingLogSource {
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        /** Counted down when {@code open} is called. */
        final CountDownLatch entered = new CountDownLatch(1);
        /** Counted down when {@code open} returns or throws. */
        final CountDownLatch exited = new CountDownLatch(1);
        final boolean reports;
        final boolean ends;

        FakeSource(boolean reports, boolean ends) {
            this.reports = reports;
            this.ends = ends;
        }

        @Override public String getName() { return "fake"; }

        @Override public void open(Consumer<String> lines, Runnable onConnected) throws Exception {
            entered.countDown();
            try {
                go.await();
                if (reports) onConnected.run();
                LINES.forEach(lines);
                if (!ends) closed.await();
            } finally {
                exited.countDown();
            }
        }

        @Override public void close() { closed.countDown(); }
    }

    private static <T> T onEdt(Callable<T> c) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { out.set(c.call()); } catch (Exception e) { throw new RuntimeException(e); }
        });
        return out.get();
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 5_000;
        while (!onEdt(condition)) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }

    @Test
    void connectingThenConnectedThenReconnectStreamsAFreshSourceIntoTheEmptiedTab() throws Exception {
        FakeSource first = new FakeSource(true, false);
        FakeSource second = new FakeSource(true, true);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger notified = new AtomicInteger();
        TabPanel panel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
        LogTableModel model = (LogTableModel) panel.getLogTable().getModel();
        try {
            onEdt(() -> {
                panel.setConnectionListener(notified::incrementAndGet);
                panel.loadSource(first, () -> { created.incrementAndGet(); return second; }, Optional.of(PARSER));
                return null;
            });
            assertThat(onEdt(panel::canReconnect)).isTrue();
            assertThat(onEdt(panel::connectionState)).isEqualTo(State.CONNECTING);
            assertThat(notified.get()).isPositive();

            first.go.countDown();
            await(() -> panel.connectionState() == State.CONNECTED && model.getTotalCount() == 2);

            onEdt(() -> { panel.reconnect(); return null; });
            assertThat(first.closed.await(5, TimeUnit.SECONDS)).as("the old source is closed").isTrue();
            assertThat(created.get()).isEqualTo(1);
            assertThat(onEdt(panel::connectionState)).isEqualTo(State.CONNECTING);
            assertThat(onEdt(model::getTotalCount)).as("the tab is emptied").isZero();

            // The new source sends the same tail again, then its stream ends.
            second.go.countDown();
            await(() -> panel.connectionState() == State.DISCONNECTED);
            assertThat(onEdt(model::getTotalCount)).as("no duplicated rows").isEqualTo(2);
            assertThat(onEdt(panel::connectionError)).isNull();
        } finally {
            onEdt(() -> { panel.dispose(); return null; });
        }
    }

    /**
     * D-21: Reconnect while the old source is still connecting. However the old run ends,
     * it must not touch the new run's tab: show the rows before the new source sent any,
     * add rows, or change the connection state.
     */
    @Test
    void reconnectingWhileConnectingIgnoresTheOldRun() throws Exception {
        FakeSource first = new FakeSource(true, true);
        FakeSource second = new FakeSource(true, false);
        TabPanel panel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
        LogTableModel model = (LogTableModel) panel.getLogTable().getModel();
        try {
            onEdt(() -> { panel.loadSource(first, () -> second, Optional.of(PARSER)); return null; });
            assertThat(onEdt(() -> loadingCardShown(panel))).isTrue();
            // Reconnect only once the old run is inside open(): a worker cancelled before it
            // starts never calls open(), so it would never count down exited.
            assertThat(first.entered.await(5, TimeUnit.SECONDS)).as("the old run starts").isTrue();

            onEdt(() -> { panel.reconnect(); return null; });
            assertThat(first.exited.await(5, TimeUnit.SECONDS)).as("the old run ends").isTrue();
            onEdt(() -> null);   // let the old worker's done() run
            onEdt(() -> null);

            assertThat(onEdt(() -> loadingCardShown(panel))).as("still loading the new run").isTrue();
            assertThat(onEdt(panel::connectionState)).isEqualTo(State.CONNECTING);
            assertThat(onEdt(model::getTotalCount)).isZero();

            second.go.countDown();
            await(() -> panel.connectionState() == State.CONNECTED && model.getTotalCount() == 2);
            assertThat(onEdt(() -> loadingCardShown(panel))).isFalse();
        } finally {
            onEdt(() -> { panel.dispose(); return null; });
        }
    }

    /** Whether the tab shows its loading card: the card holding the progress bar is the visible one. */
    private static boolean loadingCardShown(TabPanel panel) {
        JProgressBar bar = find(panel, JProgressBar.class);
        java.awt.Component card = bar;
        while (!(card.getParent().getLayout() instanceof java.awt.CardLayout)) card = card.getParent();
        return card.isVisible();
    }

    private static <T> T find(java.awt.Container root, Class<T> type) {
        for (java.awt.Component c : root.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof java.awt.Container inner) {
                T found = find(inner, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void aPlainSourceIsConnectedAtItsFirstLine() throws Exception {
        FakeSource quiet = new FakeSource(false, false);
        LogSource plain = new LogSource() {   // not a ConnectingLogSource
            @Override public String getName() { return "plain"; }
            @Override public void open(Consumer<String> lines) throws Exception { quiet.open(lines); }
            @Override public void close() { quiet.close(); }
        };
        TabPanel panel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
        try {
            onEdt(() -> { panel.loadSource(plain, null, Optional.of(PARSER)); return null; });
            assertThat(onEdt(panel::canReconnect)).isFalse();
            assertThat(onEdt(panel::connectionState)).isEqualTo(State.CONNECTING);
            quiet.go.countDown();
            await(() -> panel.connectionState() == State.CONNECTED);
        } finally {
            onEdt(() -> { panel.dispose(); return null; });
        }
        assertThat(onEdt(panel::connectionState)).as("closing the tab disconnects").isEqualTo(State.DISCONNECTED);
    }

    @Test
    void aTabWithoutALiveSourceHasNoConnectionState() throws Exception {
        TabPanel panel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
        assertThat(onEdt(panel::canReconnect)).isFalse();
        assertThat(onEdt(panel::connectionState)).isNull();
        onEdt(() -> { panel.dispose(); return null; });
    }

    @Test
    void ledTooltipNamesTheStateAndTheError() {
        assertThat(SourceLed.tooltip(State.CONNECTED, null)).isEqualTo(Messages.get("source.state.connected"));
        assertThat(SourceLed.tooltip(State.DISCONNECTED, null)).isEqualTo(Messages.get("source.state.disconnected"));
        assertThat(SourceLed.tooltip(State.DISCONNECTED, "Auth fail")).contains("Auth fail");
        assertThat(SourceLed.color(State.CONNECTED)).isNotEqualTo(SourceLed.color(State.CONNECTING));
    }
}
