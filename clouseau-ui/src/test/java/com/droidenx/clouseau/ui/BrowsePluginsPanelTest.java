package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrowsePluginsPanelTest {

    private static List<String> sorted(String... versions) {
        List<String> list = new ArrayList<>(List.of(versions));
        list.sort(BrowsePluginsPanel::compareVersions);
        return list;
    }

    @Test
    void semanticVersionsSortByTheirNumbers() {
        assertThat(sorted("1.10.0", "1.9.0", "1.2", "0.3.0"))
                .containsExactly("0.3.0", "1.2", "1.9.0", "1.10.0");
    }

    @Test
    void aPreReleaseComesBeforeItsRelease() {
        assertThat(sorted("2.0.0", "2.0.0-rc.2", "2.0.0-rc.10", "1.9.9"))
                .containsExactly("1.9.9", "2.0.0-rc.2", "2.0.0-rc.10", "2.0.0");
    }

    @Test
    void otherVersionsCompareTheirNumberPartsAsNumbers() {
        assertThat(sorted("1.0.10.Final", "1.0.9.Final", "20190101", "2019"))
                .containsExactly("1.0.9.Final", "1.0.10.Final", "2019", "20190101");
    }

    @Test
    void theSortIsSavedByColumnNameAndReadBack() {
        var key = new javax.swing.RowSorter.SortKey(2, javax.swing.SortOrder.DESCENDING);

        assertThat(BrowsePluginsPanel.sortText(List.of(key))).isEqualTo("date:descending");
        assertThat(BrowsePluginsPanel.parseSort("date:descending")).isEqualTo(key);
    }

    @Test
    void noSortOrAnUnknownOneReadsAsUnsorted() {
        assertThat(BrowsePluginsPanel.sortText(List.of())).isNull();
        assertThat(BrowsePluginsPanel.sortText(List.of(
                new javax.swing.RowSorter.SortKey(1, javax.swing.SortOrder.UNSORTED)))).isNull();
        assertThat(BrowsePluginsPanel.parseSort(null)).isNull();
        assertThat(BrowsePluginsPanel.parseSort("downloads:descending")).isNull();
        assertThat(BrowsePluginsPanel.parseSort("version:sideways")).isNull();
        assertThat(BrowsePluginsPanel.parseSort("2")).isNull();
    }

    @Test
    void equalVersionsAreEqual() {
        assertThat(BrowsePluginsPanel.compareVersions("1.0.0-SNAPSHOT", "1.0.0-SNAPSHOT")).isZero();
    }
}
