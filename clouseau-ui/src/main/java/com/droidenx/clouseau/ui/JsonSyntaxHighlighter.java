package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.core.Folds;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Built-in syntax highlighter for JSON messages (plain or prefixed).
 * Produces VS Code-inspired token colors for keys, string values,
 * numbers, booleans, nulls, and punctuation.
 */
final class JsonSyntaxHighlighter implements LogSyntaxHighlighter, Folds.Source {

    // Names roles only; the theme colors them (ClouseauColors.syntaxColor).

    @Override
    public String getName() { return "JSON"; }

    @Override
    public boolean canHighlight(String input) {
        if (input == null) return false;
        return findJsonStart(input) >= 0;
    }

    @Override
    public List<Span> highlight(String input) {
        int jsonStart = findJsonStart(input);
        if (jsonStart < 0) return List.of();
        return tokenize(input, jsonStart);
    }

    /** Its objects and arrays, for the detail pane to collapse (D-34). */
    @Override
    public List<Folds.Fold> folds(String input) {
        return input == null ? List.of() : Folds.json(input, findJsonStart(input));
    }

    /**
     * Walks the string starting at {@code jsonStart}, emitting color spans.
     * Characters before {@code jsonStart} (the prefix) are left unspanned
     * so the caller renders them in the base style.
     */
    private static List<Span> tokenize(String text, int jsonStart) {
        List<Span> spans = new ArrayList<>();
        int i = jsonStart;
        int len = text.length();
        // true = inside object, false = inside array
        Deque<Boolean> stack = new ArrayDeque<>();
        boolean expectKey = false;

        while (i < len) {
            char c = text.charAt(i);
            switch (c) {
                case '{' -> {
                    spans.add(new Span(i, i + 1, Role.PUNCTUATION));
                    stack.push(true);
                    expectKey = true;
                    i++;
                }
                case '[' -> {
                    spans.add(new Span(i, i + 1, Role.PUNCTUATION));
                    stack.push(false);
                    expectKey = false;
                    i++;
                }
                case '}', ']' -> {
                    spans.add(new Span(i, i + 1, Role.PUNCTUATION));
                    if (!stack.isEmpty()) stack.pop();
                    i++;
                }
                case ',' -> {
                    spans.add(new Span(i, i + 1, Role.PUNCTUATION));
                    expectKey = !stack.isEmpty() && Boolean.TRUE.equals(stack.peek());
                    i++;
                }
                case ':' -> {
                    spans.add(new Span(i, i + 1, Role.PUNCTUATION));
                    expectKey = false;
                    i++;
                }
                case '"' -> {
                    int start = i++;
                    while (i < len) {
                        char sc = text.charAt(i);
                        if (sc == '\\') { i = Math.min(len, i + 2); continue; }
                        if (sc == '"') { i++; break; }
                        i++;
                    }
                    spans.add(new Span(start, i, expectKey ? Role.KEY : Role.STRING));
                }
                case 't', 'f', 'n' -> {
                    int start = i;
                    while (i < len && Character.isLetter(text.charAt(i))) i++;
                    spans.add(new Span(start, i, Role.LITERAL));
                }
                default -> {
                    if (Character.isDigit(c) || c == '-') {
                        int start = i;
                        while (i < len) {
                            char nc = text.charAt(i);
                            if (Character.isDigit(nc) || nc == '.' || nc == 'e'
                                    || nc == 'E' || nc == '+' || nc == '-') i++;
                            else break;
                        }
                        spans.add(new Span(start, i, Role.NUMBER));
                    } else {
                        i++; // whitespace, newlines
                    }
                }
            }
        }
        return spans;
    }

    private static int findJsonStart(String input) {
        return JsonDetection.findJsonStart(input);
    }
}
