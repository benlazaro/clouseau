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
        var key = new javax.swing.RowSorter.SortKey(4, javax.swing.SortOrder.DESCENDING);

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
    void theWidthsAreSavedByColumnNameAndReadBack() {
        int[] widths = { 240, 100, 80, 130, 90, 70 };

        String text = BrowsePluginsPanel.widthsText(widths);

        assertThat(text).isEqualTo("artifact:240,adds:100,version:80,installed:130,date:90,size:70");
        assertThat(BrowsePluginsPanel.parseWidths(text)).containsExactly(widths);
    }

    @Test
    void aColumnTheSavedWidthsDontNameKeepsItsUsualWidth() {
        assertThat(BrowsePluginsPanel.parseWidths(null)).containsOnly(0);
        assertThat(BrowsePluginsPanel.parseWidths("size:70,downloads:50,date:wide,version:-3,artifact"))
                .containsExactly(0, 0, 0, 0, 0, 70);
        assertThat(BrowsePluginsPanel.widthsText(new int[0])).isNull();
    }

    @Test
    void equalVersionsAreEqual() {
        assertThat(BrowsePluginsPanel.compareVersions("1.0.0-SNAPSHOT", "1.0.0-SNAPSHOT")).isZero();
    }

    private static PluginRepository.Asset listed(String name, String description, com.droidenx.clouseau.core.PluginCatalog.Kind... kinds) {
        var entry = new com.droidenx.clouseau.core.PluginCatalog.Entry("io.example", name + "-clouseau-plugin",
                name, description, List.of(kinds), null);
        return new PluginRepository.Asset("io.example", name + "-clouseau-plugin", "1.0", "u", -1, null, entry);
    }

    @Test
    void theFilterMatchesEveryWordAnywhere() {
        var loki = listed("Loki", "Follows a Grafana Loki stream.", com.droidenx.clouseau.core.PluginCatalog.Kind.SOURCE);

        assertThat(BrowsePluginsPanel.matches(loki, "loki")).isTrue();
        assertThat(BrowsePluginsPanel.matches(loki, "grafana stream")).isTrue();
        assertThat(BrowsePluginsPanel.matches(loki, "SOURCE")).isTrue();       // what it adds
        assertThat(BrowsePluginsPanel.matches(loki, "io.example")).isTrue();   // its coordinates
        assertThat(BrowsePluginsPanel.matches(loki, "loki parser")).isFalse();
        assertThat(BrowsePluginsPanel.matches(loki, "  ")).isTrue();
    }

    @Test
    void aSearchResultMatchesByItsCoordinates() {
        var found = new PluginRepository.Asset("com.acme", "acme-clouseau-plugin", "2.0", "u", -1, null);
        assertThat(BrowsePluginsPanel.matches(found, "acme")).isTrue();
        assertThat(BrowsePluginsPanel.matches(found, "source")).isFalse();
    }
}
