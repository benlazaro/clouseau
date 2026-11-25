package com.droidenx.clouseau.ui;

import javax.swing.RowSorter.SortKey;
import javax.swing.SortOrder;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.util.List;

/**
 * What a click on a sortable column header does, in every table (DESIGN.md 6.17): the first
 * click sorts by that column ascending, the second descending, the third clears the sort, so
 * the table is back in its own order. A click on another column starts that one at ascending.
 * A table sorts by one column at a time.
 */
final class SortCycle {

    private SortCycle() {}

    /** The sort keys after a click on {@code column}'s header, given the current ones. */
    static List<SortKey> next(List<? extends SortKey> keys, int column) {
        SortOrder now = !keys.isEmpty() && keys.get(0).getColumn() == column
                ? keys.get(0).getSortOrder() : SortOrder.UNSORTED;
        return switch (now) {
            case UNSORTED   -> List.of(new SortKey(column, SortOrder.ASCENDING));
            case ASCENDING  -> List.of(new SortKey(column, SortOrder.DESCENDING));
            case DESCENDING -> List.of();
        };
    }

    /** Swing's table sorter, with header clicks going round {@link #next} instead of flipping for ever. */
    static final class TableSorter<M extends TableModel> extends TableRowSorter<M> {

        TableSorter(M model) { super(model); }

        @Override
        public void toggleSortOrder(int column) {
            if (column < 0 || column >= getModelWrapper().getColumnCount() || !isSortable(column)) return;
            setSortKeys(next(getSortKeys(), column));
        }
    }
}
