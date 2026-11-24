package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.core.ToStringFormat.Entry;
import com.droidenx.clouseau.core.ToStringFormat.Node;
import com.droidenx.clouseau.core.ToStringFormat.Scalar;
import com.droidenx.clouseau.core.ToStringFormat.Struct;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Built-in highlighter for objects' {@code toString()} in a message ({@link ToStringFormat}): class
 * names as an accent (an Apache {@code @hash} muted), keys, {@code =}, brackets and commas, and
 * values as numbers, literals ({@code null}, {@code true}, {@code false}, enum constants) or strings.
 * Unquoted text is a string too, since these formats don't quote them. */
public final class ToStringHighlighter implements LogSyntaxHighlighter {

    /** The name on the detail panel's toggle, shared with {@link ToStringFormatter}. */
    public static final String NAME = "toString";

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?[LlFfDd]?");
    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");

    @Override
    public String getName() { return NAME; }

    @Override
    public boolean canHighlight(String input) {
        return ToStringFormat.contains(input);
    }

    @Override
    public List<Span> highlight(String input) {
        List<Span> spans = new ArrayList<>();
        for (Struct s : ToStringFormat.find(input)) mark(input, s, spans);
        return spans;
    }

    private static void mark(String text, Node node, List<Span> spans) {
        if (node instanceof Scalar sc) {
            if (sc.end() > sc.start()) spans.add(new Span(sc.start(), sc.end(), role(text.substring(sc.start(), sc.end()))));
            return;
        }
        Struct s = (Struct) node;
        // ACCENT, not KEYWORD: the keyword lavender is too close to the keys' pink, and the text
        // color lost the outermost name in the message around it.
        if (s.nameEnd() > s.start()) spans.add(new Span(s.start(), s.nameEnd(), Role.ACCENT));
        if (s.open() > s.nameEnd()) spans.add(new Span(s.nameEnd(), s.open(), Role.MUTED));
        spans.add(new Span(s.open(), s.open() + 1, Role.PUNCTUATION));
        spans.add(new Span(s.close(), s.close() + 1, Role.PUNCTUATION));
        for (int comma : s.commas()) spans.add(new Span(comma, comma + 1, Role.PUNCTUATION));
        for (Entry e : s.entries()) {
            if (e.hasKey()) {
                spans.add(new Span(e.keyStart(), e.keyEnd(), Role.KEY));
                spans.add(new Span(e.eq(), e.eq() + 1, Role.OPERATOR));
            }
            mark(text, e.value(), spans);
        }
    }

    static Role role(String value) {
        if (value.equals("null") || value.equals("true") || value.equals("false")) return Role.LITERAL;
        if (NUMBER.matcher(value).matches()) return Role.NUMBER;
        if (value.length() > 1 && CONSTANT.matcher(value).matches()) return Role.LITERAL;
        return Role.STRING;
    }
}
