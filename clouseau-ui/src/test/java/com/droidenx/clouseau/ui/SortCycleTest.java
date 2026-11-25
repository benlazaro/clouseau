package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import javax.swing.RowSorter.SortKey;
import javax.swing.SortOrder;
import javax.swing.table.DefaultTableModel;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SortCycleTest {

    @Test
    void aColumnGoesAscendingDescendingThenUnsorted() {
        List<SortKey> keys = SortCycle.next(List.of(), 2);
        assertThat(keys).containsExactly(new SortKey(2, SortOrder.ASCENDING));
        keys = SortCycle.next(keys, 2);
        assertThat(keys).containsExactly(new SortKey(2, SortOrder.DESCENDING));
        assertThat(SortCycle.next(keys, 2)).isEmpty();
    }

    @Test
    void anotherColumnStartsAtAscending() {
        assertThat(SortCycle.next(List.of(new SortKey(2, SortOrder.DESCENDING)), 0))
                .containsExactly(new SortKey(0, SortOrder.ASCENDING));
    }

    @Test
    void theTableSorterGoesRoundTheCycle() {
        DefaultTableModel model = new DefaultTableModel(new Object[][]{ { "b" }, { "a" }, { "c" } }, new Object[]{ "x" });
        SortCycle.TableSorter<DefaultTableModel> sorter = new SortCycle.TableSorter<>(model);

        sorter.toggleSortOrder(0);
        assertThat(sorter.convertRowIndexToModel(0)).isEqualTo(1);          // "a"
        sorter.toggleSortOrder(0);
        assertThat(sorter.convertRowIndexToModel(0)).isEqualTo(2);          // "c"
        sorter.toggleSortOrder(0);
        assertThat(sorter.getSortKeys()).isEmpty();
        assertThat(sorter.convertRowIndexToModel(0)).isZero();              // the model's order: "b"
    }

    @Test
    void anUnsortableColumnIgnoresTheClick() {
        DefaultTableModel model = new DefaultTableModel(new Object[][]{ { "b", 1 } }, new Object[]{ "x", "y" });
        SortCycle.TableSorter<DefaultTableModel> sorter = new SortCycle.TableSorter<>(model);
        sorter.setSortable(1, false);

        sorter.toggleSortOrder(1);

        assertThat(sorter.getSortKeys()).isEmpty();
    }
}
