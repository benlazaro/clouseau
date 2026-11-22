package com.droidenx.clouseau.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Finds every occurrence of a query in one piece of text: the detail pane's find (UC-3), which
 * searches the entry as shown, formatted JSON and HL7 included. Plain text or a regular
 * expression, with or without case. Matches never overlap and never are empty.
 */
public final class TextMatches {

    /** One match, from {@code start} (inclusive) to {@code end} (exclusive). */
    public record Range(int start, int end) {}

    /**
     * What a search found: the matches in order, or {@code error} (and no matches) when the
     * regular expression doesn't compile.
     */
    public record Result(List<Range> matches, String error) {
        public static final Result NONE = new Result(List.of(), null);
    }

    /** Stops at this many matches, so a one-letter query on a huge message stays cheap. */
    public static final int MAX_MATCHES = 10_000;

    private TextMatches() {}

    public static Result find(String text, String query, boolean matchCase, boolean regex) {
        if (text == null || text.isEmpty() || query == null || query.isEmpty()) return Result.NONE;
        Pattern pattern;
        try {
            int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            pattern = Pattern.compile(regex ? query : Pattern.quote(query), flags | (regex ? Pattern.MULTILINE : 0));
        } catch (PatternSyntaxException e) {
            return new Result(List.of(), e.getDescription());
        }
        List<Range> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        int from = 0;
        while (from <= text.length() && m.find(from) && found.size() < MAX_MATCHES) {
            if (m.end() > m.start()) {
                found.add(new Range(m.start(), m.end()));
                from = m.end();
            } else {
                from = m.end() + 1;            // an empty match (^, a*): nothing to show, move on
            }
        }
        return new Result(List.copyOf(found), null);
    }
}
