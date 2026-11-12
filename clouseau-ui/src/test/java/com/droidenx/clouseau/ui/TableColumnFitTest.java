package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The log table's columns fill the window, and keep the user's widths. */
class TableColumnFitTest {

    private LogTableModel model;
    private LogTableView view;

    @BeforeEach
    void setUp() throws Exception {
        model = new LogTableModel();
        onEdt(() -> {
            view = new LogTableView(model, mock(LogTableView.Host.class));
            JScrollPane scroll = view.scrollPane();
            scroll.setSize(1200, 400);
            scroll.doLayout();
            model.appendLive(List.of(entry("one", Map.of()), entry("two", Map.of())));
            view.autoResizeColumns();
        });
    }

    @AfterEach
    void tearDown() {
        model.dispose();
    }

    private static LogEntry entry(String message, Map<String, String> fields) {
        return new LogEntry(Instant.parse("2026-11-12T10:00:00Z"), LogEntry.LogLevel.INFO, "com.example.App",
                "main", message, message, fields);
    }

    private static void onEdt(ThrowingRunnable r) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try { r.run(); } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] instanceof Exception e) throw e;
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private int viewportWidth() { return view.scrollPane().getViewport().getWidth(); }

    private TableColumn column(String title) {
        TableColumnModel cm = view.table().getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            if (title.equals(cm.getColumn(i).getHeaderValue())) return cm.getColumn(i);
        }
        throw new AssertionError("no column " + title);
    }

    /** What the header does while the user drags {@code column}'s right border. */
    private void drag(TableColumn column, int width) {
        JTable table = view.table();
        table.getTableHeader().setResizingColumn(column);
        column.setWidth(width);
        table.getTableHeader().setResizingColumn(null);
    }

    @Test
    void theColumnsFillTheWindow() throws Exception {
        onEdt(() -> assertThat(view.table().getColumnModel().getTotalColumnWidth()).isEqualTo(viewportWidth()));
    }

    @Test
    void draggingAColumnWiderTakesTheSpaceFromMessage() throws Exception {
        onEdt(() -> {
            TableColumn thread = column("Thread");
            int message = column("Message").getWidth();
            drag(thread, thread.getWidth() + 100);

            assertThat(column("Message").getWidth()).isEqualTo(message - 100);
            assertThat(view.table().getColumnModel().getTotalColumnWidth()).isEqualTo(viewportWidth());
            assertThat(thread.getPreferredWidth()).isEqualTo(thread.getWidth());   // nothing to scale back from
        });
    }

    @Test
    void draggingAColumnNarrowerGivesTheSpaceToMessage() throws Exception {
        onEdt(() -> {
            TableColumn logger = column("Logger");
            int message = column("Message").getWidth();
            drag(logger, logger.getWidth() - 40);

            assertThat(column("Message").getWidth()).isEqualTo(message + 40);
            assertThat(view.table().getColumnModel().getTotalColumnWidth()).isEqualTo(viewportWidth());
        });
    }

    @Test
    void draggingMessagesBorderTradesWidthWithTheColumnAfterIt() throws Exception {
        onEdt(() -> {
            model.appendLive(List.of(entry("three", Map.of("user", "alice"))));   // a field column after Message
        });
        onEdt(() -> {
            TableColumn message = column("Message");
            int user = column("user").getWidth();
            drag(message, message.getWidth() - 50);

            assertThat(column("user").getWidth()).isEqualTo(user + 50);
            assertThat(view.table().getColumnModel().getTotalColumnWidth()).isEqualTo(viewportWidth());
        });
    }

    @Test
    void aNewFieldColumnKeepsTheOtherColumnsAsTheUserLeftThem() throws Exception {
        onEdt(() -> {
            TableColumnModel cm = view.table().getColumnModel();
            drag(column("Thread"), 222);
            cm.moveColumn(cm.getColumnIndex(column("Logger").getIdentifier()), 1);   // Logger second
            model.appendLive(List.of(entry("three", Map.of("user", "alice"))));     // Swing rebuilds the columns
        });
        onEdt(() -> {
            TableColumnModel cm = view.table().getColumnModel();
            assertThat(column("Thread").getWidth()).isEqualTo(222);
            assertThat(cm.getColumn(1).getHeaderValue()).isEqualTo("Logger");
            assertThat(column("user").getWidth()).isLessThan(200);   // sized to fit, not left at Swing's default stretch
            assertThat(cm.getTotalColumnWidth()).isEqualTo(viewportWidth());
        });
    }
}
