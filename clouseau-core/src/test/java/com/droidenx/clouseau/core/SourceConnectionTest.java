package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.ConnectingLogSource;
import com.droidenx.clouseau.api.LogSource;
import com.droidenx.clouseau.core.SourceConnection.State;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The live-source connection state behind a tab's dot (issues #3 and #4). */
class SourceConnectionTest {

    private final List<State> changes = new ArrayList<>();
    private final SourceConnection connection = new SourceConnection(c -> changes.add(c.state()));

    /** A plain {@link LogSource}: no connecting phase to report. */
    private static LogSource plainSource(List<String> lines) {
        return new LogSource() {
            @Override public String getName() { return "plain"; }
            @Override public void open(Consumer<String> lineConsumer) { lines.forEach(lineConsumer); }
            @Override public void close() {}
        };
    }

    /** A {@link ConnectingLogSource} that sends {@code lines}, reporting its connection first if {@code reports}. */
    private static ConnectingLogSource connectingSource(List<String> lines, boolean reports, List<String> log) {
        return new ConnectingLogSource() {
            @Override public String getName() { return "connecting"; }
            @Override public void open(Consumer<String> lineConsumer, Runnable onConnected) {
                if (reports) {
                    onConnected.run();
                    log.add("connected");
                }
                lines.forEach(lineConsumer);
            }
            @Override public void close() {}
        };
    }

    @Test
    void startsConnecting() {
        assertThat(connection.state()).isEqualTo(State.CONNECTING);
        assertThat(connection.error()).isNull();
        assertThat(changes).isEmpty();
    }

    @Test
    void connectingSourcesOneArgumentOpenRunsTheTwoArgumentOne() throws Exception {
        List<String> received = new ArrayList<>();
        List<String> log = new ArrayList<>();
        LogSource source = connectingSource(List.of("a", "b"), true, log);
        source.open(received::add);
        assertThat(received).containsExactly("a", "b");
        assertThat(log).containsExactly("connected");   // with a callback that does nothing
    }

    @Test
    void aPlainSourceIsConnectedAtItsFirstLine() throws Exception {
        List<State> stateAtEachLine = new ArrayList<>();
        connection.open(plainSource(List.of("a", "b")), line -> stateAtEachLine.add(connection.state()));
        assertThat(stateAtEachLine).containsExactly(State.CONNECTED, State.CONNECTED);
        assertThat(changes).containsExactly(State.CONNECTED);
    }

    @Test
    void aPlainSourceWithNoLinesStaysConnecting() throws Exception {
        connection.open(plainSource(List.of()), line -> {});
        assertThat(connection.state()).isEqualTo(State.CONNECTING);
    }

    @Test
    void aConnectingSourceIsConnectedWhenItSaysSoBeforeAnyLine() throws Exception {
        List<String> log = new ArrayList<>();
        connection.open(connectingSource(List.of(), true, log), line -> {});
        assertThat(log).containsExactly("connected");
        assertThat(connection.state()).isEqualTo(State.CONNECTED);
        assertThat(changes).containsExactly(State.CONNECTED);
    }

    @Test
    void aConnectingSourceStaysConnectingUntilItSaysSoEvenAfterALine() throws Exception {
        connection.open(connectingSource(List.of("a"), false, new ArrayList<>()), line -> {});
        assertThat(connection.state()).isEqualTo(State.CONNECTING);
        assertThat(changes).isEmpty();
    }

    @Test
    void reportingConnectedTwiceTellsTheListenerOnce() {
        connection.connected();
        connection.connected();
        assertThat(changes).containsExactly(State.CONNECTED);
    }

    @Test
    void disconnectingKeepsTheFirstErrorAndIsFinal() {
        connection.connected();
        connection.disconnected("Connection reset");
        connection.disconnected("later");
        connection.connected();
        assertThat(connection.state()).isEqualTo(State.DISCONNECTED);
        assertThat(connection.error()).isEqualTo("Connection reset");
        assertThat(changes).containsExactly(State.CONNECTED, State.DISCONNECTED);
    }

    @Test
    void canDisconnectWithoutEverConnecting() {
        connection.disconnected(null);
        assertThat(connection.state()).isEqualTo(State.DISCONNECTED);
        assertThat(connection.error()).isNull();
        assertThat(changes).containsExactly(State.DISCONNECTED);
    }

    @Test
    void openPassesTheSourcesExceptionOnWithoutDisconnecting() {
        LogSource failing = new LogSource() {
            @Override public String getName() { return "failing"; }
            @Override public void open(Consumer<String> lineConsumer) throws IOException {
                throw new IOException("Auth fail");
            }
            @Override public void close() {}
        };
        assertThatThrownBy(() -> connection.open(failing, line -> {})).hasMessage("Auth fail");
        assertThat(connection.state()).isEqualTo(State.CONNECTING);   // the caller decides the message
    }
}
