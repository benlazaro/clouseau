package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MergeOrderTest {

    private static final long NONE = EntryIndex.NO_TIMESTAMP;

    /** A source whose rows have these timestamps; {@link EntryIndex#NO_TIMESTAMP} marks a continuation. */
    private record Rows(long... ts) implements MergeOrder.Timeline {
        @Override public long timestamp(int row) { return ts[row]; }
        @Override public boolean isContinuation(int row) { return ts[row] == NONE; }
        MergeOrder.Input in() { return new MergeOrder.Input(this, ts.length, 0); }
        MergeOrder.Input in(long offset) { return new MergeOrder.Input(this, ts.length, offset); }
    }

    /** The merge as "source:row" strings, in timeline order. */
    private static List<String> order(MergeOrder m) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < m.size(); i++) out.add(m.source(i) + ":" + m.row(i));
        return out;
    }

    @Test
    void interleavesByTimestamp() {
        MergeOrder m = MergeOrder.build(List.of(new Rows(10, 30, 50).in(), new Rows(20, 40).in()));
        assertThat(order(m)).containsExactly("0:0", "1:0", "0:1", "1:1", "0:2");
    }

    @Test
    void continuationLinesStayWithTheirEntry() {
        // A stack trace at 10 must not be split by the entry at 15 from the other file.
        MergeOrder m = MergeOrder.build(List.of(new Rows(10, NONE, NONE, 20).in(), new Rows(15).in()));
        assertThat(order(m)).containsExactly("0:0", "0:1", "0:2", "1:0", "0:3");
    }

    @Test
    void equalTimestampsKeepSourceThenRowOrder() {
        MergeOrder m = MergeOrder.build(List.of(new Rows(5, 5).in(), new Rows(5, 5).in(), new Rows(1, 5).in()));
        assertThat(order(m)).containsExactly("2:0", "0:0", "0:1", "1:0", "1:1", "2:1");
    }

    @Test
    void outOfOrderLinesInOneFileKeepTheirFileOrder() {
        // A merge never reorders one file; a group out of order just waits for its turn.
        MergeOrder m = MergeOrder.build(List.of(new Rows(10, 5, 30).in(), new Rows(20).in()));
        assertThat(order(m)).containsExactly("0:0", "0:1", "1:0", "0:2");
    }

    @Test
    void leadingLinesWithoutTimestampStayAtTheStartOfTheirFile() {
        MergeOrder m = MergeOrder.build(List.of(new Rows(10, 40).in(), new Rows(NONE, NONE, 30).in()));
        assertThat(order(m)).containsExactly("0:0", "1:0", "1:1", "1:2", "0:1");
    }

    @Test
    void aSourceWithNoEntriesGoesFirst() {
        MergeOrder m = MergeOrder.build(List.of(new Rows(10).in(), new Rows(NONE, NONE).in()));
        assertThat(order(m)).containsExactly("1:0", "1:1", "0:0");
    }

    @Test
    void offsetMovesASource() {
        // device.log's clock is 25 ms slow: shifted, its 10 lands at 35.
        MergeOrder m = MergeOrder.build(List.of(new Rows(20, 30, 40).in(), new Rows(10).in(25)));
        assertThat(order(m)).containsExactly("0:0", "0:1", "1:0", "0:2");
        MergeOrder back = MergeOrder.build(List.of(new Rows(20, 30, 40).in(-15), new Rows(10).in()));
        assertThat(order(back)).containsExactly("0:0", "1:0", "0:1", "0:2");
    }

    @Test
    void anOffsetNearTheEndOfTimeDoesNotWrap() {
        MergeOrder m = MergeOrder.build(List.of(new Rows(Long.MAX_VALUE - 1).in(10), new Rows(0).in()));
        assertThat(order(m)).containsExactly("1:0", "0:0");
    }

    @Test
    void emptySourcesAreSkipped() {
        MergeOrder m = MergeOrder.build(List.of(new Rows().in(), new Rows(1, 2).in(), new Rows().in()));
        assertThat(order(m)).containsExactly("1:0", "1:1");
        assertThat(m.rowCount(0)).isZero();
    }

    @Test
    void mapsBothWays() {
        MergeOrder m = MergeOrder.build(List.of(new Rows(10, 30, NONE).in(), new Rows(20, 40).in()));
        for (int i = 0; i < m.size(); i++) assertThat(m.ordinalOf(m.source(i), m.row(i))).isEqualTo(i);
        assertThat(m.ordinalOf(1, 1)).isEqualTo(4);
        assertThat(m.rowCount(0)).isEqualTo(3);
    }

    @Test
    void oneSourceIsTheIdentityWithNoArrays() {
        MergeOrder m = MergeOrder.build(List.of(new Rows(30, 10, NONE).in()));
        assertThat(order(m)).containsExactly("0:0", "0:1", "0:2");
        assertThat(m.ordinalOf(0, 2)).isEqualTo(2);
        assertThat(m.estimatedBytes()).isZero();
        m.append(0, 3);
        assertThat(m.size()).isEqualTo(4);
        assertThatThrownBy(() -> m.row(4)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void appendsRowsAtTheEnd() {
        MergeOrder m = MergeOrder.build(List.of(new Rows(10).in(), new Rows(20).in()));
        m.append(1, 1);
        m.append(0, 1);
        assertThat(order(m)).containsExactly("0:0", "1:0", "1:1", "0:1");
        assertThat(m.ordinalOf(0, 1)).isEqualTo(3);
        assertThatThrownBy(() -> m.append(0, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergesRealIndexes() {
        EntryIndex a = new EntryIndex(), b = new EntryIndex();
        a.add(0, 1, entry(100, LogLevel.INFO));
        a.add(2, 1, entry(-1, LogLevel.UNKNOWN));   // a stack trace line
        a.add(4, 1, entry(300, LogLevel.WARN));
        b.add(0, 1, entry(200, LogLevel.ERROR));
        MergeOrder m = MergeOrder.build(List.of(
                new MergeOrder.Input(a, a.size(), 0), new MergeOrder.Input(b, b.size(), 0)));
        assertThat(order(m)).containsExactly("0:0", "0:1", "1:0", "0:2");
    }

    @Test
    void stopsWhenCancelled() {
        long[] ts = new long[200_000];
        for (int i = 0; i < ts.length; i++) ts[i] = i;
        int[] reported = {0};
        MergeOrder m = MergeOrder.build(List.of(new Rows(ts).in(), new Rows(ts).in()),
                () -> reported[0] > 0, done -> reported[0] = done);
        assertThat(m).isNull();
        assertThat(reported[0]).isPositive();
    }

    @Test
    void rejectsTooManySources() {
        List<MergeOrder.Input> many = new ArrayList<>();
        for (int i = 0; i <= MergeOrder.MAX_SOURCES; i++) many.add(new Rows(1).in());
        assertThatThrownBy(() -> MergeOrder.build(many)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MergeOrder.build(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Timing (D-17 step 1): 1M entries in four interleaved files, one in five a
     * continuation line, and the same split as four rotated files that don't overlap.
     */
    @Test
    void mergesAMillionEntriesQuickly() {
        int perFile = 250_000;
        List<MergeOrder.Input> interleaved = new ArrayList<>(), rotated = new ArrayList<>();
        for (int f = 0; f < 4; f++) {
            long[] a = new long[perFile], b = new long[perFile];
            for (int i = 0; i < perFile; i++) {
                boolean cont = i % 5 == 4;
                a[i] = cont ? NONE : (long) i * 4 + f;                    // f, f+4, f+8, …
                b[i] = cont ? NONE : (long) f * perFile * 4 + i * 4L;     // file f after file f-1
            }
            interleaved.add(new Rows(a).in());
            rotated.add(new Rows(b).in());
        }
        MergeOrder.build(interleaved);   // warm up
        long t0 = System.nanoTime();
        MergeOrder m = MergeOrder.build(interleaved);
        long t1 = System.nanoTime();
        MergeOrder r = MergeOrder.build(rotated);
        long t2 = System.nanoTime();
        assertThat((t2 - t1) / 1_000_000).isLessThan(2_000);

        assertThat(m.size()).isEqualTo(4 * perFile);
        assertThat(order(m).subList(0, 6)).containsExactly("0:0", "1:0", "2:0", "3:0", "0:1", "1:1");
        assertThat(r.source(perFile)).isEqualTo(1);
        assertThat((t1 - t0) / 1_000_000).isLessThan(2_000);
    }

    private static LogEntry entry(long millis, LogLevel level) {
        return new LogEntry(millis < 0 ? null : Instant.ofEpochMilli(millis), level, null, null, "m", "m", Map.of());
    }
}
