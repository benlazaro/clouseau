package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.AppPrefs.ColumnLayout.ColumnEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.droidenx.clouseau.ui.LogTableModel.SOURCE_COLUMN;
import static org.assertj.core.api.Assertions.assertThat;

class LayoutColumnsTest {

    private static final List<String> SINGLE = List.of("DeviceName", "InstanceId");
    private static final List<String> MERGED = List.of(SOURCE_COLUMN, "DeviceName", "InstanceId");

    /** The six fixed columns, visible, each 50 wide. */
    private static List<ColumnEntry> fixed() {
        List<ColumnEntry> l = new ArrayList<>();
        for (int i = 0; i < LogTableModel.FIXED_COLUMNS; i++) l.add(new ColumnEntry(i, true, 50));
        return l;
    }

    @Test
    void namedFieldsAreFoundInAMergedView() {
        List<ColumnEntry> saved = fixed();
        saved.add(new ColumnEntry(6, true, 120, "DeviceName"));
        saved.add(new ColumnEntry(7, false, 90, "InstanceId"));

        List<ColumnEntry> r = LayoutColumns.resolve(saved, MERGED);

        assertThat(r).contains(new ColumnEntry(7, true, 120, "DeviceName"),
                               new ColumnEntry(8, false, 90, "InstanceId"));
        assertThat(r).noneMatch(e -> e.modelIndex() == 6);   // Source: not in the layout, left as it is
    }

    @Test
    void namedFieldsTheModelLacksAreDropped() {
        List<ColumnEntry> saved = fixed();
        saved.add(new ColumnEntry(6, true, 120, "Other"));

        assertThat(LayoutColumns.resolve(saved, SINGLE)).hasSize(LogTableModel.FIXED_COLUMNS);
    }

    @Test
    void anOlderSingleFileLayoutMakesRoomForSource() {
        List<ColumnEntry> saved = fixed();
        saved.add(new ColumnEntry(6, true, 120));   // DeviceName
        saved.add(new ColumnEntry(7, false, 90));   // InstanceId

        List<ColumnEntry> r = LayoutColumns.resolve(saved, MERGED);

        assertThat(r).contains(new ColumnEntry(7, true, 120, "DeviceName"),
                               new ColumnEntry(8, false, 90, "InstanceId"));
    }

    @Test
    void anOlderMergedLayoutDropsSourceOnASingleFile() {
        List<ColumnEntry> saved = fixed();
        saved.add(new ColumnEntry(6, true, 400));   // Source
        saved.add(new ColumnEntry(7, true, 120));   // DeviceName
        saved.add(new ColumnEntry(8, false, 90));   // InstanceId

        List<ColumnEntry> r = LayoutColumns.resolve(saved, SINGLE);

        assertThat(r).hasSize(LogTableModel.FIXED_COLUMNS + 2)
                     .contains(new ColumnEntry(6, true, 120, "DeviceName"),
                               new ColumnEntry(7, false, 90, "InstanceId"));
    }

    @Test
    void anOlderLayoutOfTheSameShapeIsReadAsItIs() {
        List<ColumnEntry> saved = fixed();
        saved.add(new ColumnEntry(6, true, 120));
        saved.add(new ColumnEntry(7, false, 90));

        assertThat(LayoutColumns.resolve(saved, SINGLE))
                .contains(new ColumnEntry(6, true, 120, "DeviceName"),
                          new ColumnEntry(7, false, 90, "InstanceId"));
    }

    @Test
    void fixedColumnsKeepTheirIndexAndOrder() {
        List<ColumnEntry> saved = new ArrayList<>(fixed());
        java.util.Collections.reverse(saved);

        assertThat(LayoutColumns.resolve(saved, MERGED)).extracting(ColumnEntry::modelIndex)
                .containsExactly(5, 4, 3, 2, 1, 0);
    }
}
