package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSource;
import com.droidenx.clouseau.core.LineParser;
import com.droidenx.clouseau.core.NetworkLogSource;
import com.droidenx.clouseau.core.SourceConnection;
import com.droidenx.clouseau.core.SshLogSource;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/**
 * A live source (SSH, or one from a plugin) streaming into a tab (UC-6, D-21): one run of
 * it, with its connection state (D-20). {@code source.open} runs on a background worker;
 * each line is parsed with the chosen parser (or detected) and appended as a live entry,
 * subject to the entry cap. Reconnect makes a new {@code LiveContent} from the factory,
 * since a source is used once. EDT only.
 */
@Slf4j
final class LiveContent implements TabContent {

    private final TabContext tab;
    private final LogSource source;
    /** Creates a fresh source for Reconnect; null if the tab can't reconnect. */
    private final Callable<LogSource> factory;
    private final Optional<LogParser> parser;
    /** True on Reconnect: the logger and field filters and the column layout are kept. */
    private final boolean reconnecting;
    private final String name;
    private final SourceConnection connection;
    private SwingWorker<Void, LogEntry> worker;
    private boolean closed;
    /** Whether a line has arrived: until then a receiver shows what it listens on. EDT only. */
    private boolean gotLines;

    LiveContent(TabContext tab, LogSource source, Callable<LogSource> factory,
                Optional<LogParser> parser, boolean reconnecting) {
        this.tab = tab;
        this.source = source;
        this.factory = factory;
        this.parser = parser;
        this.reconnecting = reconnecting;
        this.name = sourceName(source);
        this.connection = new SourceConnection(changed -> SwingUtilities.invokeLater(this::connectionChanged));
    }

    @Override
    public void start() {
        tab.reset(reconnecting);
        LogTableModel model = tab.model();
        model.setCustomFields(parser.map(LogParser::customFields).orElse(List.of()));
        model.beginBatchLoad();
        tab.showLoading();
        tab.connectionChanged();   // connecting

        List<LogParser> parsers = tab.parsers();
        worker = new SwingWorker<>() {
            boolean firstBatch = true;

            @Override
            protected Void doInBackground() throws Exception {
                LineParser lineParser = new LineParser(parser, parsers, null);
                connection.open(source, line -> {
                    if (line == null || line.isBlank() || isCancelled()) return;
                    publish(lineParser.parse(line));
                });
                return null;
            }

            @Override
            protected void process(List<LogEntry> chunk) {
                if (isCancelled()) return;
                LogTableModel model = tab.model();
                LogTableView view = tab.tableView();
                int columnsBefore = model.getColumnCount();
                model.appendLive(chunk);
                gotLines = true;
                if (firstBatch) {
                    // Size columns once the first rows (and their fields) are in, so the
                    // fit uses real content and field columns aren't pushed off-screen.
                    firstBatch = false;
                    model.endBatchLoad();
                    tab.showRows();
                    if (!reconnecting) {
                        view.applyDefaultLayoutIfSet();
                        view.autoResizeColumns();
                    } else if (model.getColumnCount() != columnsBefore) {
                        view.autoResizeColumns();
                    }
                }
                // A later line that brings a new field column: the table keeps the user's
                // widths and sizes only the new column (LogTableView.keepColumnsThroughRebuild).
                tab.filterBar().addBatch(chunk);
                if (tab.follow()) view.followNewest();
            }

            @Override
            protected void done() {
                tab.showRows();
                if (isCancelled()) return;
                try {
                    get();
                    connection.disconnected(null);   // the stream ended
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log.error("Source error for {}", name, cause);
                    String message = errorMessage(cause);
                    connection.disconnected(message);
                    if (firstBatch) {
                        // Nothing arrived: say why in place of the empty table, with a way to try again.
                        // A taken port isn't a connection to check: its card says what to do instead.
                        String card = cause instanceof NetworkLogSource.PortInUseException
                                ? "receive.failed.card.in.use" : "source.failed.card";
                        tab.showMessage(MessageFormat.format(Messages.get(card), name, message),
                                canReconnect() ? Messages.get("source.failed.reconnect") : null,
                                canReconnect() ? tab::reconnect : null);
                    } else {
                        JOptionPane.showMessageDialog(tab.window(), message,
                                Messages.get("source.error.title"), JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    connection.disconnected(null);
                }
            }
        };
        FollowThreads.start(worker);
    }

    /**
     * The connection changed: the tab's dot follows it. A receiver that is listening with
     * nothing received yet says where it listens in place of the empty table (UC-20), until
     * the first line replaces the card with rows.
     */
    private void connectionChanged() {
        tab.connectionChanged();
        if (connection.state() == SourceConnection.State.CONNECTED && !gotLines && !closed
                && source instanceof NetworkLogSource n) {
            String where = Messages.get(n.listen() == NetworkLogSource.Listen.ALL
                    ? "receive.waiting.all" : "receive.waiting.local");
            tab.showMessage(MessageFormat.format(where, n.protocol().name(), String.valueOf(n.port())), null, null);
        }
    }

    /** What went wrong, in words for the user. */
    private static String errorMessage(Throwable cause) {
        if (cause instanceof SshLogSource.UnreadableFileException u) {
            return MessageFormat.format(Messages.get("ssh.error.unreadable"), u.path());
        }
        if (cause instanceof NetworkLogSource.PortInUseException p) {
            return MessageFormat.format(Messages.get("receive.error.in.use"), p.protocol().name(), String.valueOf(p.port()));
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    /** Rows stream in whatever follow mode says; the tab scrolls to them when following. */
    @Override
    public void setFollow(boolean on) {}

    /** The stream goes on into the emptied table. */
    @Override
    public void cleared() {}

    @Override
    public void dispose() {
        stop(null);
    }

    /**
     * Stops streaming: cancels the worker, closes the source, and marks the connection
     * disconnected with {@code error} (null if none). The content stays the tab's, so the
     * window still hears the change.
     */
    void stop(String error) {
        if (worker != null) worker.cancel(true);
        if (!closed) {
            closed = true;
            closeInBackground(source);
        }
        connection.disconnected(error);
    }

    @Override
    public String path() { return name; }

    @Override
    public String pathTooltip() { return name; }

    /** Whether Reconnect can open this source again. */
    boolean canReconnect() { return factory != null; }

    /** Creates the source for Reconnect; null if the tab can't reconnect. */
    Callable<LogSource> factory() { return factory; }

    Optional<LogParser> parser() { return parser; }

    SourceConnection.State state() { return connection.state(); }

    /** Why the source failed, or null. */
    String error() { return connection.error(); }

    /**
     * Closes a live source on its own daemon thread. Closing can wait on the network (an
     * SSH connection that went silent) or on a plugin, and that must never freeze the
     * window (issue #1). The tab doesn't need the source once its worker is cancelled.
     */
    private static void closeInBackground(LogSource s) {
        Thread t = new Thread(() -> {
            try {
                s.close();
            } catch (Exception | LinkageError e) {
                log.warn("Error closing source {}", s.getClass().getName(), e);
            }
        }, "source-close");
        t.setDaemon(true);
        t.start();
    }

    /** The source's name, or its class name if a plugin's {@code getName} fails. */
    static String sourceName(LogSource source) {
        try {
            String name = source.getName();
            if (name != null && !name.isBlank()) return name;
        } catch (RuntimeException e) {
            log.warn("getName() failed for source {}", source.getClass().getName(), e);
        }
        return source.getClass().getSimpleName();
    }
}
