package com.droidenx.clouseau.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * The parts of a formatted message the detail pane can collapse (D-34): a JSON object or array, or
 * an object's {@code toString()}, laid out over several lines. A built-in highlighter that knows a
 * format says where its structures are ({@link Source}); the pane joins what every highlighter
 * says ({@link #merge}) and draws each one with a chevron to collapse it into one line.
 *
 * <p>Internal for now: plugins can't report folds yet. The shape is meant to move into the plugin
 * API ({@code LogSyntaxHighlighter.folds}) once a release has shown it's right.
 */
public final class Folds {

    private Folds() {}

    /** What a fold holds, for its summary when collapsed ("3 keys", "2 items", "5 fields"). */
    public enum Kind { KEYS, ITEMS, FIELDS }

    /**
     * A structure from its opening bracket at {@code open} to its closing one at {@code close},
     * holding {@code count} entries. Collapsed, what lies between the two brackets is hidden.
     */
    public record Fold(int open, int close, int count, Kind kind) {
        /** The first hidden character: the one after the opening bracket. */
        public int hiddenStart() { return open + 1; }

        /** Just past the last hidden character: the closing bracket stays. */
        public int hiddenEnd() { return close; }

        /** Whether {@code other} lies inside this fold's hidden text. */
        public boolean contains(Fold other) {
            return other.open > open && other.close < close;
        }
    }

    /** A highlighter that can say where its structures are in the text it colors. */
    public interface Source {
        /** The structures in {@code text} (already formatted), in any order. */
        List<Fold> folds(String text);
    }

    /**
     * The JSON objects and arrays in {@code text} from {@code from} on (where the JSON starts).
     * Strings are skipped, so brackets in them don't count.
     */
    public static List<Fold> json(String text, int from) {
        List<Fold> folds = new ArrayList<>();
        if (text == null || from < 0) return folds;
        // Per open bracket: where it is, whether an object, its commas, whether anything is in it.
        record Frame(int open, boolean object, int[] commas, boolean[] content) {}
        Deque<Frame> stack = new ArrayDeque<>();
        int n = text.length();
        for (int i = from; i < n; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (!stack.isEmpty()) stack.peek().content()[0] = true;
                for (i++; i < n; i++) {
                    char s = text.charAt(i);
                    if (s == '\\') i++;
                    else if (s == '"') break;
                }
            } else if (c == '{' || c == '[') {
                if (!stack.isEmpty()) stack.peek().content()[0] = true;
                stack.push(new Frame(i, c == '{', new int[1], new boolean[1]));
            } else if (c == '}' || c == ']') {
                if (stack.isEmpty()) break;
                Frame f = stack.pop();
                int count = f.content()[0] ? f.commas()[0] + 1 : 0;
                folds.add(new Fold(f.open(), i, count, f.object() ? Kind.KEYS : Kind.ITEMS));
                if (stack.isEmpty()) break;    // the JSON ends with its outermost bracket
            } else if (c == ',') {
                if (!stack.isEmpty()) stack.peek().commas()[0]++;
            } else if (!Character.isWhitespace(c) && !stack.isEmpty()) {
                stack.peek().content()[0] = true;
            }
        }
        return folds;
    }

    /** The structures {@link ToStringFormat} finds in {@code text}, nested ones included. */
    public static List<Fold> toStrings(String text) {
        List<Fold> folds = new ArrayList<>();
        for (ToStringFormat.Struct s : ToStringFormat.find(text)) addStruct(text, s, folds);
        return folds;
    }

    private static void addStruct(String text, ToStringFormat.Struct s, List<Fold> folds) {
        boolean keyed = s.entries().stream().anyMatch(ToStringFormat.Entry::hasKey);
        Kind kind = keyed ? (s.start() == s.open() && text.charAt(s.open()) == '{' ? Kind.KEYS : Kind.FIELDS) : Kind.ITEMS;
        folds.add(new Fold(s.open(), s.close(), s.entries().size(), kind));
        for (ToStringFormat.Entry e : s.entries()) {
            if (e.value() instanceof ToStringFormat.Struct inner) addStruct(text, inner, folds);
        }
    }

    /**
     * What the pane offers to collapse, from every highlighter's folds: only those over more than
     * one line (a one-line structure has nothing to hide), each opening bracket once, none that
     * crosses another (the earlier-reported wins), in order of their opening brackets.
     */
    public static List<Fold> merge(String text, List<List<Fold>> layers) {
        List<Fold> all = new ArrayList<>();
        for (List<Fold> layer : layers) {
            for (Fold f : layer) {
                if (f.open() < 0 || f.close() >= text.length() || f.close() <= f.open()) continue;
                if (text.indexOf('\n', f.open()) < 0 || text.indexOf('\n', f.open()) > f.close()) continue;
                all.add(f);
            }
        }
        List<Fold> kept = new ArrayList<>();
        for (Fold f : all) {
            boolean clash = false;
            for (Fold k : kept) {
                boolean same = k.open() == f.open();
                boolean crosses = (f.open() < k.open() && f.close() > k.open() && f.close() < k.close())
                               || (k.open() < f.open() && k.close() > f.open() && k.close() < f.close());
                if (same || crosses || k.close() == f.close()) { clash = true; break; }
            }
            if (!clash) kept.add(f);
        }
        kept.sort(Comparator.comparingInt(Fold::open));
        return List.copyOf(kept);
    }

    /**
     * The folds to hide when those opening at {@code collapsed} are collapsed: the outermost ones
     * only (one inside a collapsed fold is hidden with it), in order.
     */
    public static List<Fold> hidden(List<Fold> folds, java.util.Set<Integer> collapsed) {
        List<Fold> hidden = new ArrayList<>();
        for (Fold f : folds) {     // in order of their opening brackets, so an outer one comes first
            if (!collapsed.contains(f.open())) continue;
            if (!hidden.isEmpty() && hidden.get(hidden.size() - 1).contains(f)) continue;
            hidden.add(f);
        }
        return hidden;
    }
}
