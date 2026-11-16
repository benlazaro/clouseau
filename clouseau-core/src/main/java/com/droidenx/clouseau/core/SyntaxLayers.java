package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogSyntaxHighlighter.Role;
import com.droidenx.clouseau.api.LogSyntaxHighlighter.Span;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Layers the spans of every highlighter that colors one message (D-26) into a single list the
 * detail panel can draw: sorted, non-overlapping, and inside the text.
 *
 * <p>Where spans overlap, the narrower one wins, so a token inside a larger region keeps its own
 * role whichever highlighter found it. Between spans of the same width, the earlier layer wins.
 * Spans that reach past the text are cut at its end; empty ones are dropped.
 */
public final class SyntaxLayers {

    private SyntaxLayers() {}

    /**
     * @param length the length of the text the spans describe
     * @param layers each highlighter's spans, in the highlighters' order
     */
    public static List<Span> resolve(int length, List<List<Span>> layers) {
        record Placed(int start, int end, Role role, int layer) {
            int width() { return end - start; }
        }
        List<Placed> placed = new ArrayList<>();
        for (int layer = 0; layer < layers.size(); layer++) {
            List<Span> spans = layers.get(layer);
            if (spans == null) continue;
            for (Span s : spans) {
                if (s == null) continue;
                int start = Math.min(s.start(), length), end = Math.min(s.end(), length);
                if (end > start) placed.add(new Placed(start, end, s.role(), layer));
            }
        }
        if (placed.isEmpty()) return List.of();

        // Paint the widest first, so narrower spans paint over them; among equal widths, the
        // later layer first, so the earlier one paints last and wins.
        placed.sort(Comparator.comparingInt(Placed::width).reversed()
                .thenComparing(Comparator.comparingInt(Placed::layer).reversed()));
        Role[] roles = new Role[length];
        for (Placed p : placed) java.util.Arrays.fill(roles, p.start, p.end, p.role);

        List<Span> out = new ArrayList<>();
        int i = 0;
        while (i < length) {
            Role r = roles[i];
            int j = i + 1;
            while (j < length && roles[j] == r) j++;
            if (r != null) out.add(new Span(i, j, r));
            i = j;
        }
        return out;
    }
}
