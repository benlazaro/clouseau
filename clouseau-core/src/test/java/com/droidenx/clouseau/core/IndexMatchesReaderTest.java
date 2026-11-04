package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-16 step 1: indexing a file must give the same entries, with the same entry numbers,
 * as the reader that loads files today ({@link LogReaders} + {@link LogFileReader}).
 */
class IndexMatchesReaderTest {

    private static final Path SAMPLE = Path.of("../samples/sample.log");
    private static final Path BUILT_IN_PARSERS =
            Path.of("../clouseau-ui/src/main/resources/com/droidenx/clouseau/ui/default-parsers.json");

    @TempDir Path dir;

    /** The parsers Clouseau ships, read the way UserParsersLoader reads them. */
    private static List<LogParser> builtInParsers() throws IOException {
        List<LogParser> parsers = new ArrayList<>();
        for (JsonElement el : JsonParser.parseString(Files.readString(BUILT_IN_PARSERS)).getAsJsonArray()) {
            JsonObject o = el.getAsJsonObject();
            parsers.add(new RegexLogParser(o.get("name").getAsString(),
                    Pattern.compile(o.get("pattern").getAsString()), o.get("timestampFormat").getAsString()));
        }
        assertThat(parsers).isNotEmpty();
        return parsers;
    }

    /** Returns the entries, so callers can check the file exercised what it should. */
    private static List<LogEntry> assertIndexMatchesReader(Path file) throws IOException {
        LineParser parser = new LineParser(Optional.empty(), builtInParsers(), null);

        List<LogEntry> expected = new ArrayList<>();
        try (BufferedReader reader = LogReaders.open(file, null)) {
            LogFileReader.read(reader, parser, LogFileReader.DEFAULT_CHECK_INTERVAL,
                    () -> false, () -> false, expected::add);
        }

        EntryIndex index = new EntryIndex(4);   // small chunks, so chunk edges get crossed
        List<String> texts = new ArrayList<>();
        long end;
        try (InputStream in = Files.newInputStream(file)) {
            end = LineScanner.scan(in, (offset, length, text) -> {
                index.add(offset, length, parser.parse(text));
                texts.add(text);
            }, () -> false);
        }

        assertThat(end).isEqualTo(Files.size(file));
        assertThat(index.size()).isEqualTo(expected.size());
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            for (int i = 0; i < expected.size(); i++) {
                LogEntry e = expected.get(i);
                String at = "entry " + (i + 1);

                assertThat(texts.get(i)).as(at).isEqualTo(e.rawLine());
                byte[] bytes = new byte[index.length(i)];
                raf.seek(index.offset(i));
                raf.readFully(bytes);
                assertThat(new String(bytes, StandardCharsets.UTF_8)).as(at).isEqualTo(e.rawLine());

                assertThat(index.timestamp(i)).as(at).isEqualTo(
                        e.timestamp() != null ? e.timestamp().toEpochMilli() : EntryIndex.NO_TIMESTAMP);
                assertThat(index.level(i)).as(at).isEqualTo(e.level());
                assertThat(index.loggers().valueOf(index.loggerId(i))).as(at).isEqualTo(e.logger());
                assertThat(index.threads().valueOf(index.threadId(i))).as(at).isEqualTo(e.thread());

                Map<String, String> fields = new HashMap<>();
                for (String name : index.fieldNames()) {
                    String v = index.fieldValues(name).valueOf(index.fieldId(name, i));
                    if (v != null) fields.put(name, v);
                }
                assertThat(fields).as(at).isEqualTo(e.fields() != null ? e.fields() : Map.of());
            }
        }
        return expected;
    }

    @Test
    void sampleLog() throws IOException {
        List<LogEntry> entries = assertIndexMatchesReader(SAMPLE);

        assertThat(entries).hasSize(106);
        assertThat(entries).filteredOn(e -> e.timestamp() != null).hasSize(57);
    }

    @Test
    void awkwardFile() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        String[] endings = {"\n", "\r\n", "\r"};
        for (int i = 0; i < 300; i++) {
            String ending = endings[i % 3];
            out.writeBytes(("2026-11-04 10:00:%02d.%03d [worker-%d] %s com.acme.Svc%d - café #%d%s"
                    .formatted(i % 60, i, i % 4, i % 7 == 0 ? "ERROR" : "INFO", i % 5, i, ending))
                    .getBytes(StandardCharsets.UTF_8));
            if (i % 7 == 0) {
                out.writeBytes(("java.lang.IllegalStateException: boom" + ending
                        + "\tat com.acme.Svc.run(Svc.java:" + i + ")" + ending).getBytes(StandardCharsets.UTF_8));
            }
            if (i % 11 == 0) out.writeBytes(("   " + ending + ending).getBytes(StandardCharsets.UTF_8));
            if (i % 13 == 0) out.writeBytes(new byte[] {'b', 'a', 'd', (byte) 0xFE, (byte) 0xC3, '\n'});
            if (i == 150) out.writeBytes(("x".repeat(200_000) + "\n").getBytes(StandardCharsets.UTF_8));
            if (i == 160) {   // the Spring Boot format, with custom fields
                out.writeBytes(("2026-11-04T10:00:00.123+01:00  INFO 4242 --- [demo] [main] com.acme.Boot : Started"
                        + ending).getBytes(StandardCharsets.UTF_8));
            }
        }
        out.writeBytes("2026-11-04 11:00:00.000 [main] INFO  com.acme.End - no newline at the end"
                .getBytes(StandardCharsets.UTF_8));
        Path file = Files.write(dir.resolve("awkward.log"), out.toByteArray());

        List<LogEntry> entries = assertIndexMatchesReader(file);

        assertThat(entries).filteredOn(e -> e.timestamp() != null).hasSize(302);
        assertThat(entries).filteredOn(e -> e.level() == LogEntry.LogLevel.UNKNOWN).hasSizeGreaterThan(80);
        assertThat(entries).anyMatch(e -> "4242".equals(e.fields().get("pid")));
    }
}
