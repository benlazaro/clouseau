package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LogFileReaderTest {

    private static final LineParser NO_PARSERS = new LineParser(Optional.empty(), List.of(), null);

    private static BufferedReader lines(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) sb.append("line ").append(i).append('\n');
        return new BufferedReader(new StringReader(sb.toString()));
    }

    @Test
    void readsEveryNonBlankLineInOrder() throws IOException {
        List<String> got = new ArrayList<>();
        BufferedReader in = new BufferedReader(new StringReader("a\n\n  \nb\nc"));

        LogFileReader.Result r = LogFileReader.read(in, NO_PARSERS, 10, () -> false, () -> false,
                e -> got.add(e.rawLine()));

        assertThat(got).containsExactly("a", "b", "c");
        assertThat(r.partial()).isFalse();
    }

    @Test
    void stopsWhenMemoryRunsLow() throws IOException {
        List<LogEntry> got = new ArrayList<>();
        int[] checks = {0};

        LogFileReader.Result r = LogFileReader.read(lines(100), NO_PARSERS, 10,
                () -> ++checks[0] == 3, () -> false, got::add);

        assertThat(r.partial()).isTrue();
        assertThat(r.stoppedAtLine()).isEqualTo(29);
        assertThat(got).hasSize(29);
        assertThat(got.get(28).rawLine()).isEqualTo("line 29");
    }

    @Test
    void cancelEndsQuietly() throws IOException {
        List<LogEntry> got = new ArrayList<>();

        LogFileReader.Result r = LogFileReader.read(lines(100), NO_PARSERS, 10,
                () -> false, () -> got.size() >= 5, got::add);

        assertThat(got).hasSize(5);
        assertThat(r.partial()).isFalse();
    }
}
