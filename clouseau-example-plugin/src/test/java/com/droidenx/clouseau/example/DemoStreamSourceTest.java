package com.droidenx.clouseau.example;

import com.droidenx.clouseau.api.ConnectingLogSource;
import com.droidenx.clouseau.api.LogSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoStreamSourceTest {

    private final DemoStreamSource provider = new DemoStreamSource();

    @Test
    void writesTheRequestedNumberOfLogfmtLines() throws Exception {
        LogSource source = provider.create(Map.of("service", "api", "interval", "10", "mix", "noisy", "count", "3"));
        List<String> lines = new CopyOnWriteArrayList<>();

        source.open(lines::add);   // returns after 3 lines

        assertThat(lines).hasSize(3);
        assertThat(source.getName()).isEqualTo("demo: api");
        LogfmtParser parser = new LogfmtParser();
        assertThat(lines).allSatisfy(line -> {
            assertThat(parser.canParse(line)).isTrue();
            assertThat(parser.parse(line).logger()).isEqualTo("api");
        });
        assertThat(parser.parse(lines.get(2)).fields()).containsEntry("seq", "3");
    }

    @Test
    void reportsItsConnectionBeforeTheFirstLine() throws Exception {
        LogSource created = provider.create(Map.of("service", "api", "interval", "10", "mix", "noisy", "count", "2"));
        assertThat(created).isInstanceOf(ConnectingLogSource.class);
        ConnectingLogSource source = (ConnectingLogSource) created;
        List<String> events = new CopyOnWriteArrayList<>();

        source.open(events::add, () -> events.add("<connected>"));

        assertThat(events).hasSize(3).first().isEqualTo("<connected>");
    }

    @Test
    void closeEndsABlockedOpen() throws Exception {
        LogSource source = provider.create(Map.of("service", "api", "interval", "60000", "mix", "mostly info"));
        CountDownLatch firstLine = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try { source.open(line -> firstLine.countDown()); } catch (Exception ignored) {}
        });
        reader.start();

        assertThat(firstLine.await(5, TimeUnit.SECONDS)).isTrue();
        source.close();
        reader.join(5_000);
        assertThat(reader.isAlive()).isFalse();
    }

    @Test
    void closeBeforeOpenMeansNoLines() throws Exception {
        LogSource source = provider.create(Map.of("service", "api", "interval", "10", "mix", "mostly info"));
        List<String> lines = new CopyOnWriteArrayList<>();

        source.close();
        source.open(lines::add);

        assertThat(lines).isEmpty();
    }

    @Test
    void rejectsOutOfRangeValuesWithAMessage() {
        assertThatThrownBy(() -> provider.create(Map.of("service", "a", "interval", "1", "mix", "noisy")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 10 and 60000");
        assertThatThrownBy(() -> provider.create(Map.of("service", "a", "interval", "100", "mix", "noisy", "count", "0")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
