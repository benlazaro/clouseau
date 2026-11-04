package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntryIndexTest {

    private static LogEntry entry(Instant ts, LogLevel level, String logger, String thread, Map<String, String> fields) {
        return new LogEntry(ts, level, logger, thread, "msg", "raw", fields);
    }

    @Test
    void storesEachColumn() {
        EntryIndex index = new EntryIndex();
        Instant ts = Instant.parse("2026-11-04T10:00:00.123Z");

        index.add(100, 42, entry(ts, LogLevel.WARN, "com.acme.App", "main", Map.of()));
        index.add(143, 7, entry(null, LogLevel.UNKNOWN, null, null, Map.of()));

        assertThat(index.size()).isEqualTo(2);
        assertThat(index.offset(0)).isEqualTo(100);
        assertThat(index.length(0)).isEqualTo(42);
        assertThat(index.timestamp(0)).isEqualTo(ts.toEpochMilli());
        assertThat(index.level(0)).isEqualTo(LogLevel.WARN);
        assertThat(index.loggers().valueOf(index.loggerId(0))).isEqualTo("com.acme.App");
        assertThat(index.threads().valueOf(index.threadId(0))).isEqualTo("main");

        assertThat(index.timestamp(1)).isEqualTo(EntryIndex.NO_TIMESTAMP);
        assertThat(index.level(1)).isEqualTo(LogLevel.UNKNOWN);
        assertThat(index.loggerId(1)).isEqualTo(EntryIndex.NONE);
        assertThat(index.loggers().valueOf(EntryIndex.NONE)).isNull();
    }

    @Test
    void noLevelIsKeptApartFromUnknown() {
        EntryIndex index = new EntryIndex();
        index.add(0, 1, entry(Instant.EPOCH, null, null, null, Map.of()));
        index.add(2, 1, entry(Instant.EPOCH, LogLevel.UNKNOWN, null, null, Map.of()));
        index.add(4, 1, entry(null, LogLevel.INFO, null, null, Map.of()));
        index.add(6, 1, entry(Instant.EPOCH, LogLevel.INFO, null, null, Map.of()));

        assertThat(index.level(0)).isNull();
        assertThat(index.level(1)).isEqualTo(LogLevel.UNKNOWN);
        assertThat(index.isContinuation(0)).isFalse();
        assertThat(index.isContinuation(1)).isTrue();
        assertThat(index.isContinuation(2)).isTrue();
        assertThat(index.isContinuation(3)).isFalse();
    }

    @Test
    void anotherThreadCanReadWhatItWasHanded() throws Exception {
        EntryIndex index = new EntryIndex(4);
        java.util.concurrent.BlockingQueue<Integer> handed = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                for (int n; (n = handed.take()) >= 0; ) {
                    int row = n - 1;
                    assertThat(index.loggers().valueOf(index.loggerId(row))).isEqualTo("l" + row);
                    assertThat(index.fieldValues("f").valueOf(index.fieldId("f", row))).isEqualTo("v" + row % 7);
                    assertThat(index.fieldNames()).contains("f");
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        reader.start();
        for (int i = 0; i < 20_000; i++) {
            index.add(i, 1, entry(null, LogLevel.INFO, "l" + i, null, Map.of("f", "v" + i % 7)));
            handed.put(i + 1);      // the queue is the happens-before edge
        }
        handed.put(-1);
        reader.join(30_000);

        assertThat(failure.get()).isNull();
    }

    @Test
    void repeatedNamesShareOneId() {
        EntryIndex index = new EntryIndex();
        index.add(0, 1, entry(null, LogLevel.INFO, "a", "t1", Map.of()));
        index.add(2, 1, entry(null, LogLevel.INFO, "b", "t1", Map.of()));
        index.add(4, 1, entry(null, LogLevel.INFO, "a", "t2", Map.of()));

        assertThat(index.loggerId(0)).isEqualTo(index.loggerId(2)).isNotEqualTo(index.loggerId(1));
        assertThat(index.loggers().values()).containsExactly("a", "b");
        assertThat(index.loggers().idOf("b")).isEqualTo(index.loggerId(1));
        assertThat(index.loggers().idOf("never")).isEqualTo(EntryIndex.NONE);
        assertThat(index.threads().size()).isEqualTo(2);
    }

    @Test
    void fieldFirstSeenLaterIsMissingOnEarlierRows() {
        EntryIndex index = new EntryIndex();
        index.add(0, 1, entry(null, LogLevel.INFO, null, null, Map.of()));
        index.add(2, 1, entry(null, LogLevel.INFO, null, null, Map.of("pid", "42")));
        index.add(4, 1, entry(null, LogLevel.INFO, null, null, Map.of()));

        assertThat(index.fieldNames()).containsExactly("pid");
        assertThat(index.isFieldIndexed("pid")).isTrue();
        assertThat(index.fieldId("pid", 0)).isEqualTo(EntryIndex.NONE);
        assertThat(index.fieldValues("pid").valueOf(index.fieldId("pid", 1))).isEqualTo("42");
        assertThat(index.fieldId("pid", 2)).isEqualTo(EntryIndex.NONE);
        assertThat(index.fieldId("unknown", 1)).isEqualTo(EntryIndex.NONE);
    }

    @Test
    void fieldWithTooManyValuesStopsBeingIndexed() {
        EntryIndex index = new EntryIndex();
        for (int i = 0; i <= EntryIndex.MAX_FIELD_VALUES; i++) {
            index.add(i, 1, entry(null, LogLevel.INFO, null, null, Map.of("id", "v" + i, "app", "demo")));
        }

        assertThat(index.fieldNames()).containsExactlyInAnyOrder("id", "app");
        assertThat(index.isFieldIndexed("id")).isFalse();
        assertThat(index.fieldValues("id")).isNull();
        assertThat(index.fieldId("id", 0)).isEqualTo(EntryIndex.NONE);
        assertThat(index.isFieldIndexed("app")).isTrue();
        assertThat(index.fieldValues("app").values()).containsExactly("demo");
    }

    @Test
    void manyRowsAcrossChunks() {
        EntryIndex index = new EntryIndex(3);   // chunks of 8 rows
        for (int i = 0; i < 1_000; i++) {
            index.add(i * 10L, i, entry(Instant.ofEpochMilli(i), LogLevel.values()[i % 6], "l" + (i % 3), null, Map.of()));
        }

        assertThat(index.size()).isEqualTo(1_000);
        for (int i = 0; i < 1_000; i++) {
            assertThat(index.offset(i)).isEqualTo(i * 10L);
            assertThat(index.length(i)).isEqualTo(i);
            assertThat(index.timestamp(i)).isEqualTo(i);
            assertThat(index.level(i)).isEqualTo(LogLevel.values()[i % 6]);
            assertThat(index.loggers().valueOf(index.loggerId(i))).isEqualTo("l" + (i % 3));
        }
        assertThatThrownBy(() -> index.offset(1_000)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void shortLogsDontAllocateWholeChunks() {
        EntryIndex index = new EntryIndex();
        index.add(0, 1, entry(null, LogLevel.INFO, null, null, Map.of()));

        assertThat(index.estimatedBytes()).isLessThan(64 * 1024);
    }
}
