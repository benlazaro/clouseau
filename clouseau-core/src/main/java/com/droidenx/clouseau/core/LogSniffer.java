package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Looks at the start of a log before it is opened: reads the first lines, tells whether a
 * parser recognizes them, and whether the file looks binary rather than text (UC-13).
 * Shared by the open dialog's preview and by a tab deciding between the table and the
 * plain-text view, so both reach the same verdict.
 */
public final class LogSniffer {

    /** Lines looked at, the same sample the open dialog previews. */
    public static final int SAMPLE_LINES = 50;
    /** At most this many characters are read, so a file without line breaks stays cheap. */
    static final int SAMPLE_CHARS = 64 * 1024;
    /** A sample with more than this share of control or undecodable characters is binary. */
    private static final double BINARY_SHARE = 0.05;

    /** The first lines of a file, and whether it looks binary. */
    public record Sample(List<String> lines, boolean binary) {
        /** True when there is nothing but blank lines to look at. */
        public boolean empty() {
            return lines.stream().allMatch(String::isBlank);
        }
    }

    private LogSniffer() {}

    /**
     * Reads up to {@link #SAMPLE_LINES} lines from the start of a plain, {@code .gz} or zip
     * {@code entry} log, decoded as {@link LogReaders} decodes it. A line cut off by the
     * character limit is dropped unless it is the only one.
     */
    public static Sample read(Path file, String entry) throws IOException {
        char[] buf = new char[SAMPLE_CHARS];
        int n = 0;
        try (BufferedReader reader = LogReaders.open(file, entry)) {
            int r;
            while (n < buf.length && (r = reader.read(buf, n, buf.length - n)) > 0) n += r;
        }
        return sample(new String(buf, 0, n), n == buf.length);
    }

    /** {@link #read}'s logic on text already in memory; {@code cut} if more text follows. */
    static Sample sample(String text, boolean cut) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length() && lines.size() < SAMPLE_LINES; i++) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\r') continue;
            lines.add(text.substring(start, i));
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            start = i + 1;
        }
        if (lines.size() < SAMPLE_LINES && start < text.length() && (!cut || lines.isEmpty())) {
            lines.add(text.substring(start));
        }
        return new Sample(List.copyOf(lines), looksBinary(text));
    }

    /**
     * True if more than 5% of {@code text}'s characters are control characters (other than
     * tab and line breaks) or bytes that weren't valid UTF-8.
     */
    public static boolean looksBinary(String text) {
        if (text.isEmpty()) return false;
        long odd = text.chars()
                .filter(c -> (c < 32 && c != '\n' && c != '\r' && c != '\t') || c == 0xFFFD)
                .count();
        return odd > text.length() * BINARY_SHARE;
    }

    /**
     * The parser that recognizes the first non-blank line it can: {@code chosen} if one was
     * picked (empty if it recognizes none of them), otherwise the first of {@code parsers}
     * that recognizes a line, trying lines in order.
     */
    public static Optional<LogParser> detect(List<String> lines, Optional<LogParser> chosen,
                                             List<LogParser> parsers) {
        List<LogParser> candidates = chosen.map(List::of).orElse(parsers);
        for (String line : lines) {
            if (line.isBlank()) continue;
            for (LogParser p : candidates) {
                if (p.canParse(line)) return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
