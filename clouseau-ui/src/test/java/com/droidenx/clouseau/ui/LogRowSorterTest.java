package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.RowSorter.SortKey;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class LogRowSorterTest {

    private LogTableModel model;
    private LogRowSorter sorter;
    private final List<Boolean> busy = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            model = new LogTableModel();
            sorter = new LogRowSorter(model);
            sorter.setBusyListener(busy::add);
            model.addTableModelListener(e -> {
                // What JTable does for its sorter.
                if (e.getFirstRow() == javax.swing.event.TableModelEvent.HEADER_ROW) sorter.modelStructureChanged();
                else if (e.getLastRow() == Integer.MAX_VALUE) sorter.allRowsChanged();
                else if (e.getType() == javax.swing.event.TableModelEvent.INSERT) sorter.rowsInserted(e.getFirstRow(), e.getLastRow());
                else if (e.getType() == javax.swing.event.TableModelEvent.DELETE) sorter.rowsDeleted(e.getFirstRow(), e.getLastRow());
            });
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> model.dispose());
    }

    private static LogEntry at(long millis) {
        return new LogEntry(Instant.ofEpochMilli(millis), LogLevel.INFO, null, null, "m" + millis, "m" + millis, Map.of());
    }

    private static LogEntry continuation() {
        return new LogEntry(null, LogLevel.UNKNOWN, null, null, "  at x", "  at x", Map.of());
    }

    private void append(LogEntry... entries) throws Exception {
        SwingUtilities.invokeAndWait(() -> model.appendLive(List.of(entries)));
    }

    private void sort(int column, SortOrder order) throws Exception {
        SwingUtilities.invokeAndWait(() -> sorter.setSortKeys(List.of(new SortKey(column, order))));
    }

    private int[] viewOrder() throws Exception {
        int[][] out = new int[1][];
        SwingUtilities.invokeAndWait(() -> {
            int n = sorter.getViewRowCount();
            out[0] = new int[n];
            for (int v = 0; v < n; v++) out[0][v] = sorter.convertRowIndexToModel(v);
            for (int v = 0; v < n; v++) assertThat(sorter.convertRowIndexToView(out[0][v])).isEqualTo(v);
        });
        return out[0];
    }

    /** Waits for background sorts to land: the busy listener ends on false. */
    private void settle() throws Exception {
        for (int i = 0; i < 250; i++) {
            boolean[] idle = {false};
            SwingUtilities.invokeAndWait(() -> idle[0] = busy.isEmpty() || !busy.get(busy.size() - 1));
            if (idle[0]) return;
            Thread.sleep(10);
        }
        throw new AssertionError("sort never finished");
    }

    @Test
    void numberColumnIsTheModelOrderOrItsReverse() throws Exception {
        append(at(30), at(10), at(20));
        sort(0, SortOrder.ASCENDING);
        assertThat(viewOrder()).containsExactly(0, 1, 2);
        sort(0, SortOrder.DESCENDING);
        assertThat(viewOrder()).containsExactly(2, 1, 0);
        assertThat(busy).isEmpty();
    }

    @Test
    void toggleStartsAscendingThenFlips() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            sorter.toggleSortOrder(1);
            assertThat(sorter.getSortKeys().get(0).getSortOrder()).isEqualTo(SortOrder.ASCENDING);
            sorter.toggleSortOrder(1);
            assertThat(sorter.getSortKeys().get(0).getSortOrder()).isEqualTo(SortOrder.DESCENDING);
            sorter.toggleSortOrder(3);   // Level isn't sortable
            assertThat(sorter.getSortKeys().get(0).getColumn()).isEqualTo(1);
        });
    }

    @Test
    void timeSortOrdersOutOfOrderRowsAndKeepsContinuationsUnderTheirEntry() throws Exception {
        append(at(30), continuation(), at(10), at(20), continuation());
        sort(1, SortOrder.ASCENDING);
        settle();
        assertThat(viewOrder()).containsExactly(2, 3, 4, 0, 1);
        sort(1, SortOrder.DESCENDING);
        settle();
        assertThat(viewOrder()).containsExactly(1, 0, 4, 3, 2);
    }

    @Test
    void timeSortOfRowsInOrderIsTheModelOrder() throws Exception {
        append(at(10), at(10), at(20), continuation(), at(30));
        sort(1, SortOrder.DESCENDING);
        settle();
        assertThat(viewOrder()).containsExactly(4, 3, 2, 1, 0);
    }

    @Test
    void followedRowsArePlacedWithoutAnotherSortWhenTheyComeLater() throws Exception {
        append(at(30), at(10), at(20));
        sort(1, SortOrder.DESCENDING);
        settle();
        int sorts = busy.size();
        append(at(40), continuation());
        assertThat(busy).hasSize(sorts);
        assertThat(viewOrder()).containsExactly(4, 3, 0, 2, 1);

        append(at(5));                       // earlier than what's shown: sorted again
        settle();
        assertThat(viewOrder()).containsExactly(4, 3, 0, 2, 1, 5);
    }

    @Test
    void trimmedRowsLeaveTheOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> model.setMaxEntries(3));
        append(at(30), at(10), at(20));
        sort(1, SortOrder.ASCENDING);
        settle();
        assertThat(viewOrder()).containsExactly(1, 2, 0);
        append(at(50));                      // drops the 30: model rows are now 10, 20, 50
        settle();
        assertThat(viewOrder()).containsExactly(0, 1, 2);
    }

    @Test
    void ascendingOrderIsStableAndNullWhenSorted() {
        BooleanSupplier never = () -> false;
        assertThat(LogRowSorter.ascendingOrder(new long[]{1, 1, 2, 5}, never)).isNull();
        assertThat(LogRowSorter.ascendingOrder(new long[0], never)).isNull();
        assertThat(LogRowSorter.ascendingOrder(new long[]{3, 1, 2, 1, 3}, never)).containsExactly(1, 3, 2, 0, 4);

        Random random = new Random(7);
        long[] keys = new long[10_000];
        for (int i = 0; i < keys.length; i++) keys[i] = random.nextInt(500);
        int[] order = LogRowSorter.ascendingOrder(keys, never);
        for (int i = 1; i < order.length; i++) {
            assertThat(keys[order[i - 1]] < keys[order[i]]
                    || keys[order[i - 1]] == keys[order[i]] && order[i - 1] < order[i]).isTrue();
        }
    }
}
