package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Placing rows the sources gained after the merge (D-17 step 5, following). */
class MergeOrderExtendTest {

    private static final long NONE = EntryIndex.NO_TIMESTAMP;

    private record Rows(long... ts) implements MergeOrder.Timeline {
        @Override public long timestamp(int row) { return ts[row]; }
        @Override public boolean isContinuation(int row) { return ts[row] == NONE; }
        MergeOrder.Input first(int count) { return new MergeOrder.Input(this, count, 0); }
        MergeOrder.Input all() { return first(ts.length); }
    }

    private static List<String> order(MergeOrder m) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < m.size(); i++) out.add(m.source(i) + ":" + m.row(i));
        return out;
    }

    @Test
    void newerRowsAreAppendedInPlace() {
        Rows a = new Rows(10, 30, 50, 70), b = new Rows(20, 40, 60);
        MergeOrder m = MergeOrder.build(List.of(a.first(2), b.first(2)));
        List<MergeOrder.Input> more = List.of(a.all(), b.all());
        assertThat(m.placeFrom(more)).isEqualTo(4);
        MergeOrder after = m.extend(more);
        assertThat(after).isSameAs(m);
        assertThat(order(after)).containsExactly("0:0", "1:0", "0:1", "1:1", "0:2", "1:2", "0:3");
        assertThat(order(after)).isEqualTo(order(MergeOrder.build(more)));
    }

    @Test
    void aLateRowReMergesTheTailIntoANewOrder() {
        Rows a = new Rows(10, 20, 30, 40), b = new Rows(25, 35);
        MergeOrder m = MergeOrder.build(List.of(a.all(), b.first(1)));
        List<String> before = order(m);                       // 0:0 0:1 1:0 0:2 0:3
        List<MergeOrder.Input> more = List.of(a.all(), b.all());
        assertThat(m.placeFrom(more)).isEqualTo(4);            // 35 goes before a's 40
        MergeOrder after = m.extend(more);
        assertThat(after).isNotSameAs(m);
        assertThat(order(m)).isEqualTo(before);                // left as it was, for its readers
        assertThat(order(after)).containsExactly("0:0", "0:1", "1:0", "0:2", "1:1", "0:3");
        assertThat(after.ordinalOf(0, 3)).isEqualTo(5);
        assertThat(after.rowCount(1)).isEqualTo(2);
    }

    @Test
    void aLateContinuationJoinsItsEntry() {
        // a's entry at 10 gets its stack trace after b's 20 was already placed.
        Rows a = new Rows(10, NONE, NONE), b = new Rows(20);
        MergeOrder m = MergeOrder.build(List.of(a.first(1), b.all()));
        List<MergeOrder.Input> more = List.of(a.all(), b.all());
        assertThat(m.placeFrom(more)).isEqualTo(1);
        assertThat(order(m.extend(more))).containsExactly("0:0", "0:1", "0:2", "1:0");
    }

    @Test
    void aContinuationOfTheLastGroupIsAppended() {
        Rows a = new Rows(10, 30, NONE), b = new Rows(20);
        MergeOrder m = MergeOrder.build(List.of(a.first(2), b.all()));
        List<MergeOrder.Input> more = List.of(a.all(), b.all());
        assertThat(m.placeFrom(more)).isEqualTo(3);
        assertThat(order(m.extend(more))).containsExactly("0:0", "1:0", "0:1", "0:2");
    }

    @Test
    void aRowIsNeverPlacedBeforeEarlierRowsOfItsFile() {
        // a's clock went back: 5 still comes after a's own 30.
        Rows a = new Rows(10, 30, 5), b = new Rows(20, 40);
        MergeOrder m = MergeOrder.build(List.of(a.first(2), b.all()));
        List<MergeOrder.Input> more = List.of(a.all(), b.all());
        assertThat(m.placeFrom(more)).isEqualTo(3);
        assertThat(order(m.extend(more))).containsExactly("0:0", "1:0", "0:1", "0:2", "1:1");
    }

    @Test
    void equalTimestampsKeepTheSourceOrder() {
        Rows a = new Rows(10, 20), b = new Rows(20);
        MergeOrder m = MergeOrder.build(List.of(a.first(1), b.all()));
        List<MergeOrder.Input> more = List.of(a.all(), b.all());
        assertThat(order(m.extend(more))).containsExactly("0:0", "0:1", "1:0");
    }

    @Test
    void oneSourceJustGrows() {
        Rows a = new Rows(10, 5, 30);
        MergeOrder m = MergeOrder.build(List.of(a.first(1)));
        assertThat(m.extend(List.of(a.all()))).isSameAs(m);
        assertThat(m.size()).isEqualTo(3);
    }

    @Test
    void rejectsFewerRowsThanPlaced() {
        Rows a = new Rows(10, 20), b = new Rows(15);
        MergeOrder m = MergeOrder.build(List.of(a.all(), b.all()));
        assertThatThrownBy(() -> m.extend(List.of(a.first(1), b.all())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extendingInStepsGivesTheSameOrderAsOneMerge() {
        // Random files, each in time order with stack traces, gaining rows in random steps.
        Random rnd = new Random(17);
        for (int round = 0; round < 200; round++) {
            int k = 2 + rnd.nextInt(3);
            Rows[] files = new Rows[k];
            for (int s = 0; s < k; s++) {
                long[] ts = new long[1 + rnd.nextInt(40)];
                long t = rnd.nextInt(20);
                for (int r = 0; r < ts.length; r++) {
                    if (r > 0 && rnd.nextInt(4) == 0) ts[r] = NONE;
                    else ts[r] = t += rnd.nextInt(5);
                }
                files[s] = new Rows(ts);
            }
            int[] counts = new int[k];
            for (int s = 0; s < k; s++) counts[s] = 1 + rnd.nextInt(files[s].ts().length);
            MergeOrder m = MergeOrder.build(inputs(files, counts));
            while (true) {
                boolean grew = false;
                for (int s = 0; s < k; s++) {
                    int left = files[s].ts().length - counts[s];
                    if (left > 0) { counts[s] += 1 + rnd.nextInt(left); grew = true; }
                }
                if (!grew) break;
                List<MergeOrder.Input> in = inputs(files, counts);
                m = m.extend(in);
                assertThat(order(m)).as("round %d", round).isEqualTo(order(MergeOrder.build(in)));
            }
        }
    }

    @Test
    void aReMergeAcrossChunksSharesThePrefix() {
        // Over 1M ordinals, so the new order shares full chunks and copies the partial one.
        int n = 600_000;
        long[] a = new long[n], b = new long[n];
        for (int i = 0; i < n; i++) { a[i] = 2L * i; b[i] = 2L * i + 1; }
        b[n - 1] = 2L * n - 3;                  // late: belongs before a's last row
        Rows ra = new Rows(a), rb = new Rows(b);
        MergeOrder m = MergeOrder.build(List.of(ra.all(), rb.first(n - 1)));
        List<MergeOrder.Input> more = List.of(ra.all(), rb.all());
        MergeOrder after = m.extend(more);
        assertThat(after).isNotSameAs(m);
        MergeOrder fresh = MergeOrder.build(more);
        assertThat(after.size()).isEqualTo(fresh.size());
        for (int i = 0; i < fresh.size(); i++) {
            if (after.source(i) != fresh.source(i) || after.row(i) != fresh.row(i)) {
                throw new AssertionError("differs at " + i);
            }
        }
        assertThat(m.size()).isEqualTo(2 * n - 1);  // the old order is untouched
        assertThat(m.row(2 * n - 2)).isEqualTo(n - 1);
    }

    private static List<MergeOrder.Input> inputs(Rows[] files, int[] counts) {
        List<MergeOrder.Input> in = new ArrayList<>();
        for (int s = 0; s < files.length; s++) in.add(files[s].first(counts[s]));
        return in;
    }
}
