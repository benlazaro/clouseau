package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogSyntaxHighlighter;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in highlighter for JVM stack traces (UC-3): each exception's class and the
 * {@code Caused by:} chain stand out, the frames of the JDK and frameworks are dimmed
 * ({@link StackTrace#isFramework}), with every {@code at} and {@code ... 42 more}, and the
 * application's own frames show their {@code Class.method} as an accent, so they're easy to find.
 */
public final class StackTraceHighlighter implements LogSyntaxHighlighter {

    /** The name on the detail panel's toggle. */
    public static final String NAME = "Stack Trace";

    @Override
    public String getName() { return NAME; }

    @Override
    public boolean canHighlight(String input) {
        return StackTrace.hasFrames(input);
    }

    @Override
    public List<Span> highlight(String input) {
        List<Span> spans = new ArrayList<>();
        for (StackTrace.Line line : StackTrace.lines(input)) {
            int start = skipBlanks(input, line.start(), line.end());
            switch (line.kind()) {
                case FRAMEWORK_FRAME, OMITTED -> add(spans, start, line.end(), Role.MUTED);
                case FRAME -> {
                    add(spans, start, line.prefixEnd(), Role.MUTED);
                    add(spans, classAndMethod(input, line.classStart(), line.classEnd()), line.classEnd(), Role.ACCENT);
                    add(spans, line.suffixStart(), line.end(), Role.MUTED);
                }
                case EXCEPTION, CAUSE -> {
                    add(spans, start, trimEnd(input, start, line.prefixEnd()), Role.KEYWORD);
                    add(spans, line.classStart(), line.classEnd(), Role.ERROR);
                }
            }
        }
        return spans;
    }

    private static void add(List<Span> spans, int start, int end, Role role) {
        if (end > start) spans.add(new Span(start, end, role));
    }

    /**
     * Where a frame's {@code Class.method} starts in {@code pkg.Class.method}: at the first part
     * that starts with a capital, else at the last two parts (a lowercase class name).
     */
    private static int classAndMethod(String s, int from, int to) {
        int partStart = from;
        for (int i = from; i < to; i++) {
            if (i == partStart && Character.isUpperCase(s.charAt(i))) return i;
            if (s.charAt(i) == '.') partStart = i + 1;
        }
        int lastDot = s.lastIndexOf('.', to - 1);
        int before = lastDot > from ? s.lastIndexOf('.', lastDot - 1) : -1;
        return before >= from ? before + 1 : from;
    }

    private static int skipBlanks(String s, int from, int to) {
        while (from < to && (s.charAt(from) == ' ' || s.charAt(from) == '\t')) from++;
        return from;
    }

    private static int trimEnd(String s, int from, int to) {
        while (to > from && (s.charAt(to - 1) == ' ' || s.charAt(to - 1) == '\t')) to--;
        return to;
    }
}
