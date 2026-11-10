package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.AppPrefs.ColumnLayout.ColumnEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds a saved layout's columns in a tab's table model. The fixed columns are always at
 * the same model index; a field column is found by its field name, so a layout saved on a
 * single file fits a merged view, whose Source column comes before the fields, and the
 * other way round.
 * <p>
 * Layouts saved before field names were kept have only model indices. Those are read as
 * they are, except when the layout is exactly one column short of, or over, the model and
 * the difference is the Source column: then the field indices are shifted past it.
 */
final class LayoutColumns {

    private LayoutColumns() {}

    /**
     * The layout's entries with model indices for a model whose field columns are
     * {@code fields} (after {@link LogTableModel#FIXED_COLUMNS} fixed ones). Entries the
     * model has no column for are dropped, as is a second entry for the same column.
     */
    static List<ColumnEntry> resolve(List<ColumnEntry> entries, List<String> fields) {
        int fixed = LogTableModel.FIXED_COLUMNS;
        int modelCount = fixed + fields.size();
        int sourceAt = fields.indexOf(LogTableModel.SOURCE_COLUMN);
        // Older layouts: was it saved on a model with the Source column, or without?
        int shiftFrom = Integer.MAX_VALUE;
        int shift = 0;
        boolean legacy = entries.stream().anyMatch(e -> e.field() == null && e.modelIndex() >= fixed);
        if (legacy) {
            if (sourceAt >= 0 && entries.size() == modelCount - 1) {
                shiftFrom = fixed + sourceAt;       // saved without Source: make room for it
                shift = 1;
            } else if (sourceAt < 0 && entries.size() == modelCount + 1) {
                shiftFrom = fixed;                  // saved with Source first: drop it, close the gap
                shift = -1;
            }
        }

        List<ColumnEntry> resolved = new ArrayList<>(entries.size());
        Set<Integer> seen = new HashSet<>();
        for (ColumnEntry e : entries) {
            int index;
            if (e.field() != null) {
                int at = fields.indexOf(e.field());
                index = at < 0 ? -1 : fixed + at;
            } else if (e.modelIndex() < fixed) {
                index = e.modelIndex();
            } else if (shift < 0 && e.modelIndex() == shiftFrom) {
                index = -1;                         // the Source column of a merged view
            } else {
                index = e.modelIndex() >= shiftFrom ? e.modelIndex() + shift : e.modelIndex();
            }
            if (index < 0 || index >= modelCount || !seen.add(index)) continue;
            String field = index < fixed ? null : fields.get(index - fixed);
            resolved.add(new ColumnEntry(index, e.visible(), e.width(), field));
        }
        return resolved;
    }
}
