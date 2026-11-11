package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.ui.theme.ClouseauColors;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Built-in syntax highlighter for JSON messages (plain or prefixed).
 * Produces VS Code-inspired token colors for keys, string values,
 * numbers, booleans, nulls, and punctuation.
 */
final class JsonSyntaxHighlighter implements LogSyntaxHighlighter {

    // Palette (keys, strings, literals, punctuation) is per theme, read from UIManager via ClouseauColors at highlight time.

    @Override
    public String getName() { return "JSON"; }

    @Override
    public boolean canHighlight(String input) {
        if (input == null) return false;
        return findJsonStart(input) >= 0;
    }

    @Override
    public List<ColorSpan> highlight(String input) {
        int jsonStart = findJsonStart(input);
        if (jsonStart < 0) return List.of();
        return tokenize(input, jsonStart);
    }

    /**
     * Walks the string starting at {@code jsonStart}, emitting color spans.
     * Characters before {@code jsonStart} (the prefix) are left unspanned
     * so the caller renders them in the base style.
     */
    private static List<ColorSpan> tokenize(String text, int jsonStart) {
        List<ColorSpan> spans = new ArrayList<>();
        int i = jsonStart;
        int len = text.length();
        // true = inside object, false = inside array
        Deque<Boolean> stack = new ArrayDeque<>();
        boolean expectKey = false;

        while (i < len) {
            char c = text.charAt(i);
            switch (c) {
                case '{' -> {
                    spans.add(new ColorSpan(i, i + 1, ClouseauColors.jsonPunctColor()));
                    stack.push(true);
                    expectKey = true;
                    i++;
                }
                case '[' -> {
                    spans.add(new ColorSpan(i, i + 1, ClouseauColors.jsonPunctColor()));
                    stack.push(false);
                    expectKey = false;
                    i++;
                }
                case '}', ']' -> {
                    spans.add(new ColorSpan(i, i + 1, ClouseauColors.jsonPunctColor()));
                    if (!stack.isEmpty()) stack.pop();
                    i++;
                }
                case ',' -> {
                    spans.add(new ColorSpan(i, i + 1, ClouseauColors.jsonPunctColor()));
                    expectKey = !stack.isEmpty() && Boolean.TRUE.equals(stack.peek());
                    i++;
                }
                case ':' -> {
                    spans.add(new ColorSpan(i, i + 1, ClouseauColors.jsonPunctColor()));
                    expectKey = false;
                    i++;
                }
                case '"' -> {
                    int start = i++;
                    while (i < len) {
                        char sc = text.charAt(i);
                        if (sc == '\\') { i += 2; continue; }
                        if (sc == '"') { i++; break; }
                        i++;
                    }
                    spans.add(new ColorSpan(start, i, expectKey ? ClouseauColors.jsonKeyColor() : ClouseauColors.jsonStringColor()));
                }
                case 't', 'f', 'n' -> {
                    int start = i;
                    while (i < len && Character.isLetter(text.charAt(i))) i++;
                    spans.add(new ColorSpan(start, i, ClouseauColors.jsonBoolNullColor()));
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
                        spans.add(new ColorSpan(start, i, ClouseauColors.jsonNumberColor()));
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
