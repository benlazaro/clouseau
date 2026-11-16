package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogSyntaxHighlighter.Span;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.droidenx.clouseau.api.LogSyntaxHighlighter.Role.*;
import static org.assertj.core.api.Assertions.assertThat;

class SyntaxLayersTest {

    @Test
    void oneLayerComesBackSorted() {
        List<Span> spans = SyntaxLayers.resolve(10, List.of(List.of(new Span(5, 7, STRING), new Span(0, 2, KEY))));
        assertThat(spans).containsExactly(new Span(0, 2, KEY), new Span(5, 7, STRING));
    }

    @Test
    void layersThatDontOverlapAreBothKept() {
        // hex bytes at the start, an HL7 segment name later on
        List<Span> spans = SyntaxLayers.resolve(20, List.of(
                List.of(new Span(0, 4, MUTED)),
                List.of(new Span(10, 13, KEYWORD))));
        assertThat(spans).containsExactly(new Span(0, 4, MUTED), new Span(10, 13, KEYWORD));
    }

    @Test
    void theNarrowerSpanWinsWhereTheyOverlap() {
        List<Span> spans = SyntaxLayers.resolve(10, List.of(
                List.of(new Span(2, 4, ACCENT)),
                List.of(new Span(0, 10, STRING))));
        assertThat(spans).containsExactly(new Span(0, 2, STRING), new Span(2, 4, ACCENT), new Span(4, 10, STRING));
    }

    @Test
    void amongEqualWidthsTheEarlierLayerWins() {
        List<Span> spans = SyntaxLayers.resolve(5, List.of(
                List.of(new Span(0, 3, KEY)),
                List.of(new Span(0, 3, ERROR))));
        assertThat(spans).containsExactly(new Span(0, 3, KEY));
    }

    @Test
    void neighboursWithTheSameRoleAreJoined() {
        List<Span> spans = SyntaxLayers.resolve(6, List.of(List.of(new Span(0, 2, MUTED), new Span(2, 5, MUTED))));
        assertThat(spans).containsExactly(new Span(0, 5, MUTED));
    }

    @Test
    void spansPastTheTextAreCutAndEmptyOnesDropped() {
        List<Span> spans = SyntaxLayers.resolve(4, List.of(List.of(new Span(2, 9, STRING), new Span(6, 8, KEY), new Span(1, 1, KEY))));
        assertThat(spans).containsExactly(new Span(2, 4, STRING));
    }

    @Test
    void noLayersOrNullLayersGiveNothing() {
        assertThat(SyntaxLayers.resolve(5, List.of())).isEmpty();
        assertThat(SyntaxLayers.resolve(5, Arrays.asList(null, List.of()))).isEmpty();
    }
}
