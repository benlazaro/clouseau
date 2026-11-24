package com.droidenx.clouseau.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds and lays out the {@code toString()} of Java and Kotlin objects in a log message: Lombok's
 * {@code @ToString} and Kotlin data classes ({@code Order(id=1, items=[Item(sku=A)])}), records
 * ({@code Point[x=1, y=2]}), hand-written and Guava ones ({@code User{id=1, name='Bob'}}), Apache's
 * {@code ToStringBuilder} ({@code Foo@1a2b[id=1]}) and maps ({@code {user=bob, trace=abc}}).
 *
 * <p>A structure is a class name (its simple name capitalized) right before {@code (}, {@code [} or
 * {@code {}, or a bare {@code {}, holding entries split by commas; an entry is {@code key=value} or a
 * value alone. A value is a nested structure, a list ({@code [a, b]}), a quoted string, or text up
 * to the next entry. Text values aren't quoted in these formats, so a comma ends one only where an
 * entry follows ({@code key=} after it), except in a list. A structure counts only when it closes
 * and holds at least one {@code key=value}, so {@code [main]} and {@code call(x)} are left alone,
 * and text in double quotes before it (a JSON string) is skipped.
 *
 * <p>Laying out keeps every character but whitespace as it was, and is idempotent.
 */
public final class ToStringFormat {

    private ToStringFormat() {}

    /** A structure no wider than this stays on one line. */
    private static final int WIDTH = 72;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_KEY = 120;

    /** Something parsed: a structure or a value. Positions are indexes into the text. */
    public sealed interface Node permits Struct, Scalar {}

    /**
     * A structure: the name runs {@code [start, nameEnd)}, an Apache {@code @hash} {@code [nameEnd, open)};
     * both are empty for a bare {@code {…}} or {@code […]}. {@code commas} are the separators' indexes.
     */
    public record Struct(int start, int nameEnd, int open, int close, List<Entry> entries, List<Integer> commas)
            implements Node {}

    /** One entry; {@code keyStart} is -1 for a value alone, and {@code eq} is the {@code =}'s index. */
    public record Entry(int keyStart, int keyEnd, int eq, Node value) {
        public boolean hasKey() { return keyStart >= 0; }
    }

    /** A value that isn't a structure, trimmed: {@code [start, end)}. */
    public record Scalar(int start, int end) implements Node {}

    /** Whether {@code text} holds at least one structure. */
    public static boolean contains(String text) {
        return text != null && !find(text).isEmpty();
    }

    /** The outermost structures in {@code text}, in order, none overlapping. */
    public static List<Struct> find(String text) {
        List<Struct> found = new ArrayList<>();
        if (text == null) return found;
        Parser p = new Parser(text);
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = text.indexOf('"', i + 1);
                int eol = text.indexOf('\n', i + 1);
                if (end > 0 && (eol < 0 || end < eol)) i = end;
                continue;
            }
            Struct s = null;
            int skipTo = i;
            if (c == '{') {
                s = p.tryStruct(i, i, i, 0);
            } else if (Character.isJavaIdentifierStart(c) && (i == 0 || !isNamePart(text.charAt(i - 1)))) {
                int nameEnd = p.nameEnd(i);
                skipTo = nameEnd - 1;
                s = p.tryNamed(i, nameEnd, 0);
            }
            if (p.exhausted()) break;
            if (s != null && hasKey(s)) {
                found.add(s);
                i = s.close();
            } else {
                i = Math.max(i, skipTo);
            }
        }
        return found;
    }

    /** {@code text} with each structure too wide for a line laid out over several, indented by depth. */
    public static String format(String text) {
        List<Struct> found = find(text);
        if (found.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length() + 64);
        int last = 0;
        for (Struct s : found) {
            out.append(text, last, s.start());
            layout(text, s, 0, out);
            last = s.close() + 1;
        }
        return out.append(text, last, text.length()).toString();
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private static void layout(String text, Node node, int indent, StringBuilder out) {
        if (node instanceof Scalar sc) {
            out.append(text, sc.start(), sc.end());
            return;
        }
        Struct s = (Struct) node;
        String inline = inline(text, s);
        if (s.entries().isEmpty() || (indent + inline.length() <= WIDTH && inline.indexOf('\n') < 0)) {
            out.append(inline);
            return;
        }
        out.append(text, s.start(), s.open() + 1).append('\n');
        List<Entry> entries = s.entries();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            pad(out, indent + 2);
            if (e.hasKey()) out.append(text, e.keyStart(), e.keyEnd()).append('=');
            layout(text, e.value(), indent + 2, out);
            if (i < entries.size() - 1) out.append(',');
            out.append('\n');
        }
        pad(out, indent);
        out.append(text.charAt(s.close()));
    }

    private static String inline(String text, Node node) {
        if (node instanceof Scalar sc) return text.substring(sc.start(), sc.end());
        Struct s = (Struct) node;
        StringBuilder b = new StringBuilder();
        b.append(text, s.start(), s.open() + 1);
        for (int i = 0; i < s.entries().size(); i++) {
            Entry e = s.entries().get(i);
            if (i > 0) b.append(", ");
            if (e.hasKey()) b.append(text, e.keyStart(), e.keyEnd()).append('=');
            b.append(inline(text, e.value()));
        }
        return b.append(text.charAt(s.close())).toString();
    }

    private static void pad(StringBuilder out, int n) {
        for (int i = 0; i < n; i++) out.append(' ');
    }

    private static boolean hasKey(Struct s) {
        for (Entry e : s.entries()) {
            if (e.hasKey() || (e.value() instanceof Struct inner && hasKey(inner))) return true;
        }
        return false;
    }

    static boolean isNamePart(char c) {
        return Character.isJavaIdentifierPart(c) || c == '.';
    }

    private static boolean isOpener(char c)  { return c == '(' || c == '[' || c == '{'; }
    private static boolean isCloser(char c)  { return c == ')' || c == ']' || c == '}'; }

    private static char closerOf(char open) {
        return switch (open) { case '(' -> ')'; case '[' -> ']'; default -> '}'; };
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /**
     * A recursive-descent parser with a step budget, so a long message full of brackets that never
     * close can't take long: once spent, nothing more is found.
     */
    private static final class Parser {
        private final String s;
        private final int n;
        private long budget;

        Parser(String s) {
            this.s = s;
            this.n = s.length();
            this.budget = 64L * n + 10_000;
        }

        boolean exhausted() { return budget < 0; }

        private boolean spend(int steps) {
            budget -= steps;
            return budget >= 0;
        }

        int nameEnd(int i) {
            while (i < n && isNamePart(s.charAt(i))) i++;
            while (i > 0 && s.charAt(i - 1) == '.') i--;
            return i;
        }

        /** A named structure at {@code start}, its name ending at {@code nameEnd}, or null. */
        Struct tryNamed(int start, int nameEnd, int depth) {
            if (nameEnd <= start || nameEnd >= n) return null;
            int dot = s.lastIndexOf('.', nameEnd - 1);
            int simple = dot >= start ? dot + 1 : start;
            if (simple >= nameEnd || !Character.isUpperCase(s.charAt(simple))) return null;
            int open = nameEnd;
            if (s.charAt(open) == '@') {
                int h = open + 1;
                while (h < n && Character.digit(s.charAt(h), 16) >= 0) h++;
                if (h == open + 1) return null;
                open = h;
            }
            if (open >= n || !isOpener(s.charAt(open))) return null;
            return tryStruct(start, nameEnd, open, depth);
        }

        /** The structure whose opener is at {@code open}, or null if it doesn't close. */
        Struct tryStruct(int start, int nameEnd, int open, int depth) {
            if (depth > MAX_DEPTH || !spend(1)) return null;
            char close = closerOf(s.charAt(open));
            boolean list = start == open && s.charAt(open) == '[';
            List<Entry> entries = new ArrayList<>();
            List<Integer> commas = new ArrayList<>();
            int j = skipWs(open + 1);
            if (j < n && s.charAt(j) == close) return new Struct(start, nameEnd, open, j, entries, commas);
            while (true) {
                if (!spend(1)) return null;
                j = skipWs(j);
                int keyStart = -1, keyEnd = -1, eq = -1;
                int e = keyEq(j);
                if (e >= 0) {
                    keyStart = j;
                    keyEnd = trimEnd(j, e);
                    eq = e;
                    j = e + 1;
                }
                Value v = value(j, close, list, depth);
                if (v == null) return null;
                entries.add(new Entry(keyStart, keyEnd, eq, v.node()));
                j = skipWs(v.end());
                if (j >= n) return null;
                char c = s.charAt(j);
                if (c == close) return new Struct(start, nameEnd, open, j, entries, commas);
                if (c != ',') return null;
                commas.add(j);
                j++;
            }
        }

        private record Value(Node node, int end) {}

        /** The value at {@code i} inside a structure closed by {@code close}: where it ends, or null. */
        private Value value(int i, char close, boolean list, int depth) {
            i = skipWs(i);
            if (i >= n) return null;
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                int q = quoted(i);
                if (q > 0 && ends(q, close)) return new Value(new Scalar(i, q), q);
            } else if (c == '{' || c == '[') {
                Struct st = tryStruct(i, i, i, depth + 1);
                if (st != null && ends(st.close() + 1, close)) return new Value(st, st.close() + 1);
            } else if (Character.isJavaIdentifierStart(c)) {
                Struct st = tryNamed(i, nameEnd(i), depth + 1);
                if (st != null && ends(st.close() + 1, close)) return new Value(st, st.close() + 1);
            }
            if (exhausted()) return null;
            return raw(i, close, list);
        }

        /** Text up to this structure's closer or the comma before the next entry, brackets balanced. */
        private Value raw(int i, char close, boolean list) {
            int depth = 0;
            int j = i;
            for (; j < n; j++) {
                char c = s.charAt(j);
                if (depth == 0 && c == close) break;
                if (depth == 0 && c == ',' && (list || keyEq(skipWs(j + 1)) >= 0)) break;
                if (isOpener(c)) depth++;
                else if (isCloser(c) && depth > 0) depth--;
            }
            if (!spend(j - i)) return null;
            if (j >= n) return null;
            return new Value(new Scalar(i, trimEnd(i, j)), j);
        }

        /** Whether a value ending at {@code i} is followed by a separator or the closer. */
        private boolean ends(int i, char close) {
            i = skipWs(i);
            return i < n && (s.charAt(i) == ',' || s.charAt(i) == close);
        }

        /** The {@code =} of a {@code key=} starting at {@code i}, or -1. */
        private int keyEq(int i) {
            int limit = Math.min(n, i + MAX_KEY);
            for (int k = i; k < limit; k++) {
                char c = s.charAt(k);
                if (c == '=') return k > i ? k : -1;
                if (c == ',' || c == '\n' || c == '\'' || c == '"' || isOpener(c) || isCloser(c)) return -1;
            }
            return -1;
        }

        private int quoted(int i) {
            char q = s.charAt(i);
            for (int k = i + 1; k < n; k++) {
                char c = s.charAt(k);
                if (c == '\\') k++;
                else if (c == q) return k + 1;
            }
            return -1;
        }

        private int skipWs(int i) {
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            return i;
        }

        private int trimEnd(int from, int to) {
            while (to > from && Character.isWhitespace(s.charAt(to - 1))) to--;
            return to;
        }
    }
}
