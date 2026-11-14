package com.droidenx.clouseau.ui;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Converts a Logback / Log4j2 pattern string into a Java regex with named
 * capture groups suitable for use in {@link com.droidenx.clouseau.core.RegexLogParser}.
 *
 * <p>Supported conversion words:
 * <pre>
 *   %d / %date     → (?&lt;timestamp&gt;...)  — format taken from {option}
 *   %t / %thread   → (?&lt;thread&gt;...)
 *   %p / %le / %level / %levelFull → (?&lt;level&gt;...)
 *   %c / %lo / %logger             → (?&lt;logger&gt;...)
 *   %m / %msg / %message           → (?&lt;message&gt;...)
 *   %pid / %processId              → (?&lt;pid&gt;...)
 *   %n / %wEx / %ex / %nopex      → (skipped — stack traces are continuation lines)
 *   %clr(inner){color}             → inner is converted, color is ignored
 * </pre>
 *
 * <p>Format modifiers (padding, truncation: {@code %-5.15}) are accepted and ignored.
 * Unknown conversion words cause a {@link PatternConversionException}, whose message names
 * the Pattern field.
 */
public final class LogbackPatternConverter {

    private LogbackPatternConverter() {}

    public record Result(String regex, String timestampFormat) {}

    public static Result convert(String pattern) {
        Parser p = new Parser(pattern.trim());
        p.parse();
        String regex = "^" + p.sb.toString() + "$";
        // Validate the generated regex
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw PatternConversionException.invalidRegex("pattern", e);
        }
        return new Result(regex, p.timestampFormat);
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    private static final class Parser {
        final String in;
        int pos = 0;
        final StringBuilder sb = new StringBuilder();
        String timestampFormat = null;

        Parser(String in) { this.in = in; }

        void parse() {
            while (pos < in.length()) {
                char c = in.charAt(pos);
                if (c == '%') {
                    pos++;
                    parseConversion();
                } else {
                    appendLiteral(c);
                    pos++;
                }
            }
        }

        // ── Conversion word ───────────────────────────────────────────────────

        void parseConversion() {
            if (pos >= in.length()) return;

            // Skip optional format modifier: [-][min][.max]
            if (pos < in.length() && in.charAt(pos) == '-') pos++;
            while (pos < in.length() && Character.isDigit(in.charAt(pos))) pos++;
            if (pos < in.length() && in.charAt(pos) == '.') {
                pos++;
                while (pos < in.length() && Character.isDigit(in.charAt(pos))) pos++;
            }

            if (pos >= in.length()) return;

            // Read conversion word (letters + digits)
            int wStart = pos;
            while (pos < in.length() && (Character.isLetter(in.charAt(pos)) || in.charAt(pos) == '_')) pos++;
            String word = in.substring(wStart, pos);

            // %clr(inner){color} — strip color wrapper, convert inner
            if ("clr".equalsIgnoreCase(word) || "color".equalsIgnoreCase(word)
                    || "highlight".equalsIgnoreCase(word)) {
                String inner = readParenBlock();
                readBraceOption(); // skip {color}
                if (inner != null) {
                    Parser innerParser = new Parser(inner);
                    innerParser.parse();
                    sb.append(innerParser.sb);
                    if (innerParser.timestampFormat != null) timestampFormat = innerParser.timestampFormat;
                }
                return;
            }

            // Read {option}
            String option = readBraceOption();

            convertWord(word, option);
        }

        void convertWord(String word, String option) {
            switch (word.toLowerCase()) {

                // ── Timestamp ─────────────────────────────────────────────────
                case "d", "date" -> {
                    String fmt = (option != null && !option.isBlank()) ? option : "ISO8601";
                    if ("ISO8601".equalsIgnoreCase(fmt) || "DEFAULT".equalsIgnoreCase(fmt)) {
                        fmt = "yyyy-MM-dd HH:mm:ss,SSS";
                        sb.append("(?<timestamp>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[.,]\\d{3})");
                    } else {
                        sb.append("(?<timestamp>").append(dateFormatToRegex(fmt)).append(")");
                    }
                    timestampFormat = fmt;
                }

                // ── Thread ────────────────────────────────────────────────────
                case "t", "thread", "threadname" ->
                    sb.append("(?<thread>[^\\[\\]]+?)");

                // ── Level ─────────────────────────────────────────────────────
                case "p", "le", "level", "levelfull" ->
                    sb.append("(?<level>TRACE|DEBUG|INFO\\s*|WARN\\s*|ERROR\\s*|FATAL\\s*)");

                // ── Logger ────────────────────────────────────────────────────
                case "c", "lo", "logger" ->
                    sb.append("(?<logger>\\S+)");

                // ── Message ───────────────────────────────────────────────────
                case "m", "msg", "message" ->
                    sb.append("(?<message>.*)");

                // ── PID ───────────────────────────────────────────────────────
                case "pid", "processid" ->
                    sb.append("(?<pid>\\d+)");

                // ── Relative time ─────────────────────────────────────────────
                case "r", "relative" ->
                    sb.append("\\d+");

                // ── Skipped (stack traces / newlines) ─────────────────────────
                case "n", "ex", "exception", "throwable", "xthrowable",
                        "nopex", "wex", "wexfull", "rEx", "rootException" -> { /* skip */ }

                // ── Unknown ───────────────────────────────────────────────────
                default -> {
                    if (!word.isEmpty()) throw new PatternConversionException(
                            "parsereditor.error.pattern.unknown", word);
                }
            }
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        /** Reads a {@code (…)} block and returns its content, or null if none. */
        String readParenBlock() {
            if (pos >= in.length() || in.charAt(pos) != '(') return null;
            pos++; // skip (
            int depth = 1, start = pos;
            while (pos < in.length() && depth > 0) {
                char c = in.charAt(pos++);
                if (c == '(') depth++;
                else if (c == ')') depth--;
            }
            return in.substring(start, pos - 1);
        }

        /** Reads a {@code {…}} option block and returns its content, or null if none. */
        String readBraceOption() {
            if (pos >= in.length() || in.charAt(pos) != '{') return null;
            pos++; // skip {
            int depth = 1, start = pos;
            while (pos < in.length() && depth > 0) {
                char c = in.charAt(pos++);
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            return in.substring(start, pos - 1);
        }

        void appendLiteral(char c) {
            String special = ".[]{}()*+?^$|\\";
            if (special.indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
    }

    // ── DateTimeFormatter pattern → regex ─────────────────────────────────────

    /**
     * Converts a {@link java.time.format.DateTimeFormatter} pattern string into
     * a regex fragment. Handles the most common pattern letters and quoted literals.
     */
    static String dateFormatToRegex(String fmt) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < fmt.length()) {
            char c = fmt.charAt(i);
            if (c == '\'') {
                // Quoted literal: '' → literal quote; 'T' → T
                i++;
                if (i < fmt.length() && fmt.charAt(i) == '\'') {
                    sb.append("'");
                    i++;
                } else {
                    while (i < fmt.length() && fmt.charAt(i) != '\'') {
                        appendEscaped(sb, fmt.charAt(i));
                        i++;
                    }
                    if (i < fmt.length()) i++; // skip closing '
                }
            } else if (isPatternLetter(c)) {
                int j = i + 1;
                while (j < fmt.length() && fmt.charAt(j) == c) j++;
                sb.append(letterToRegex(c, j - i));
                i = j;
            } else {
                appendEscaped(sb, c);
                i++;
            }
        }
        return sb.toString();
    }

    private static void appendEscaped(StringBuilder sb, char c) {
        if (".[]{}()*+?^$|\\".indexOf(c) >= 0) sb.append('\\');
        sb.append(c);
    }

    private static boolean isPatternLetter(char c) {
        return Character.isLetter(c);
    }

    private static String letterToRegex(char c, int count) {
        return switch (c) {
            case 'G'       -> "(?:AD|BC)";
            case 'y', 'Y', 'u' -> "\\d{" + count + "}";
            case 'M', 'L' -> count <= 2 ? "\\d{1,2}" : "[A-Za-z]+";
            case 'd'       -> "\\d{1,2}";
            case 'H', 'h', 'K', 'k' -> "\\d{1,2}";
            case 'm'       -> "\\d{1,2}";
            case 's'       -> "\\d{1,2}";
            case 'S'       -> count <= 3 ? "\\d{" + count + "}" : "\\d{1," + count + "}";
            case 'n'       -> "\\d+";
            case 'a'       -> "(?:AM|PM)";
            case 'z'       -> "[A-Za-z]{2,5}";
            case 'Z'       -> "[+-]\\d{4}";
            case 'X'       -> count == 1 ? "(?:[+-]\\d{4}|Z)" : "(?:[+-]\\d{2}:?\\d{2}|Z)";
            case 'x'       -> count == 1 ? "[+-]\\d{4}" : "[+-]\\d{2}:?\\d{2}";
            case 'V'       -> "[A-Za-z/_]+";
            default        -> ".+";
        };
    }
}
