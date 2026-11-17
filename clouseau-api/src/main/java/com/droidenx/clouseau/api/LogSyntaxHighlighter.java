package com.droidenx.clouseau.api;

import org.pf4j.ExtensionPoint;

import java.util.List;

/**
 * Implement to color parts of a message in the detail panel.
 * Works in tandem with {@link LogFormatter}: the formatter produces the text, then every
 * highlighter that accepts that text marks what its parts are. A highlighter names a
 * {@link Role} for each span, never a color: the theme picks the colors, so they read well
 * in Dark, Light and custom themes, and change with them.
 *
 * <p>Highlighters are layered. Each one marks only what it recognizes (a JSON payload, an HL7
 * segment, a run of hex bytes), and where two spans overlap the narrower one wins, so a token
 * inside a larger region keeps its own role. Annotate your implementation with
 * {@code @Extension} and drop the JAR in the plugins folder.
 */
public interface LogSyntaxHighlighter extends ExtensionPoint {

    /** Human-readable name, shown on the detail panel's toggle button (e.g. "JSON"). */
    String getName();

    /** Returns true if this highlighter has something to mark in the given (already formatted) input. */
    boolean canHighlight(String input);

    /**
     * The spans to color in {@code input}. Spans may come in any order; ones that overlap are
     * resolved by the detail panel (the narrower wins). Text no highlighter marks is drawn in
     * the panel's normal text color.
     */
    List<Span> highlight(String input);

    /**
     * A half-open {@code [start, end)} character range of the input and what it is. Immutable.
     * A final class rather than a record (since 0.3.0), so it can gain values later without
     * changing this constructor.
     */
    final class Span {
        private final int start;
        private final int end;
        private final Role role;

        public Span(int start, int end, Role role) {
            if (start < 0 || end < start) throw new IllegalArgumentException("bad span [" + start + ", " + end + ")");
            if (role == null) throw new IllegalArgumentException("role is required");
            this.start = start;
            this.end = end;
            this.role = role;
        }

        /** The first character's index. */
        public int start() { return start; }

        /** The index just past the last character. */
        public int end() { return end; }

        /** What the text is. */
        public Role role() { return role; }

        @Override
        public boolean equals(Object o) {
            return o instanceof Span s && start == s.start && end == s.end && role == s.role;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(start, end, role);
        }

        @Override
        public String toString() {
            return "Span[start=" + start + ", end=" + end + ", role=" + role + "]";
        }
    }

    /**
     * What a piece of text is. Each theme gives every role a color (the {@code Clouseau.syntax.*}
     * keys of a theme file), chosen so no role reads as a log level or a field label.
     */
    enum Role {
        /** The name in a name/value pair: a JSON key, an XML attribute, a {@code key=} in logfmt. */
        KEY,
        /** A string value. */
        STRING,
        /** A number. */
        NUMBER,
        /** A named constant: {@code true}, {@code false}, {@code null}, an enum value. */
        LITERAL,
        /** A word that gives the structure: an HL7 segment name, a tag, a command. */
        KEYWORD,
        /** A separator or operator with meaning: HL7's {@code ^ ~ & \}, {@code =}, {@code ->}. */
        OPERATOR,
        /** Brackets, braces, commas and field delimiters. */
        PUNCTUATION,
        /** Text that matters less than what's around it: raw bytes, padding, a comment. */
        MUTED,
        /** Something to notice among muted text: a framing byte, a checksum, a flag. */
        ACCENT,
        /** Something wrong: an invalid value, a bad checksum. */
        ERROR
    }
}
