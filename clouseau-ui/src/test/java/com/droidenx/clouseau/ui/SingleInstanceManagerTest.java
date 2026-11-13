package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import com.droidenx.clouseau.ui.SingleInstanceManager.Delivery;

import static org.assertj.core.api.Assertions.assertThat;

class SingleInstanceManagerTest {

    /** One element per secondary launch: the paths it sent. */
    private final BlockingQueue<List<String>> received = new LinkedBlockingQueue<>();
    private ServerSocket server;

    @AfterEach
    void close() throws IOException {
        if (server != null) server.close();
    }

    private static final String VERSION = "0.11.3-alpha";

    /** A primary of {@link #VERSION}. */
    private static ServerSocket listen(int port, java.util.function.Consumer<List<String>> onLaunch) {
        return SingleInstanceManager.listen(port, VERSION, onLaunch);
    }

    /** A launch of {@link #VERSION}. */
    private static Delivery send(int port, List<String> paths) {
        return SingleInstanceManager.send(port, VERSION, paths).delivery();
    }

    private int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private List<List<String>> drain(int launches) throws InterruptedException {
        List<List<String>> out = new ArrayList<>();
        while (out.size() < launches) {
            List<String> p = received.poll(5, TimeUnit.SECONDS);
            if (p == null) break;
            out.add(p);
        }
        return out;
    }

    @Test
    void deliversEveryFileOfOneLaunchInOrder() throws Exception {
        int port = freePort();
        server = listen(port, received::add);
        assertThat(server).isNotNull();

        List<String> paths = List.of("C:\\logs\\a.log", "C:\\logs\\b.log", "C:\\logs\\follow.log");
        assertThat(send(port, paths)).isEqualTo(Delivery.DELIVERED);

        assertThat(drain(1)).containsExactly(paths);
    }

    @Test
    void deliversFilesFromManyLaunchesAtOnce() throws Exception {
        int port = freePort();
        server = listen(port, received::add);

        int launches = 20;
        ExecutorService pool = Executors.newFixedThreadPool(launches);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Delivery>> results = new ArrayList<>();
            for (int i = 0; i < launches; i++) {
                List<String> paths = List.of("/logs/" + i + "-1.log", "/logs/" + i + "-2.log");
                results.add(pool.submit(() -> {
                    start.await();
                    return send(port, paths);
                }));
            }
            start.countDown();
            for (Future<Delivery> r : results) assertThat(r.get(10, TimeUnit.SECONDS)).isEqualTo(Delivery.DELIVERED);
        } finally {
            pool.shutdownNow();
        }

        List<List<String>> got = drain(launches);
        assertThat(got).hasSize(launches).allSatisfy(l -> assertThat(l).hasSize(2));
    }

    @Test
    void launchWithoutFilesArrivesAsAnEmptyBatch() throws Exception {
        int port = freePort();
        server = listen(port, received::add);

        assertThat(send(port, List.of())).isEqualTo(Delivery.DELIVERED);

        assertThat(drain(1)).containsExactly(List.of());
    }

    @Test
    void skipsBlankLines() throws Exception {
        int port = freePort();
        server = listen(port, received::add);

        assertThat(send(port, List.of("", "/logs/a.log", " "))).isEqualTo(Delivery.DELIVERED);

        assertThat(drain(1)).containsExactly(List.of("/logs/a.log"));
    }

    @Test
    void keepsServingAfterTheCallbackFails() throws Exception {
        int port = freePort();
        server = listen(port, paths -> {
            if (paths.contains("/logs/bad.log")) throw new IllegalStateException("boom");
            received.add(paths);
        });

        assertThat(send(port, List.of("/logs/bad.log"))).isEqualTo(Delivery.DELIVERED);
        assertThat(send(port, List.of("/logs/good.log"))).isEqualTo(Delivery.DELIVERED);

        assertThat(drain(1)).containsExactly(List.of("/logs/good.log"));
    }

    @Test
    void keepsNonAsciiPaths() throws Exception {
        int port = freePort();
        server = listen(port, received::add);

        assertThat(send(port, List.of("/logs/journal-é-日本.log"))).isEqualTo(Delivery.DELIVERED);

        assertThat(drain(1)).containsExactly(List.of("/logs/journal-é-日本.log"));
    }

    @Test
    void secondListenerOnTheSamePortIsNotPrimary() throws Exception {
        int port = freePort();
        server = listen(port, received::add);

        assertThat(listen(port, p -> {})).isNull();
    }

    @Test
    void anotherVersionHandsNothingOverAndSaysWhichRuns() throws Exception {
        int port = freePort();
        server = listen(port, received::add);

        SingleInstanceManager.Reply reply = SingleInstanceManager.send(port, "0.12.0-alpha", List.of("/logs/a.log"));

        assertThat(reply).isEqualTo(new SingleInstanceManager.Reply(Delivery.OTHER_VERSION, VERSION));
        // The cancelled launch is dropped; the next one of the same version still gets through.
        assertThat(send(port, List.of("/logs/b.log"))).isEqualTo(Delivery.DELIVERED);
        assertThat(drain(2)).containsExactly(List.of("/logs/b.log"));
    }

    /** A primary from 0.11.2 or older: reads paths until the connection ends, never greets. */
    @Test
    void anOlderPrimaryThatNeverGreetsIsAnotherVersion() throws Exception {
        int port = freePort();
        server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        CompletableFuture<String> got = CompletableFuture.supplyAsync(() -> {
            try (var client = server.accept()) {
                return new String(client.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        long start = System.nanoTime();
        SingleInstanceManager.Reply reply = SingleInstanceManager.send(port, VERSION, List.of("/logs/a.log"));

        assertThat(reply).isEqualTo(new SingleInstanceManager.Reply(Delivery.OTHER_VERSION, null));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                .isLessThan(SingleInstanceManager.GREETING_TIMEOUT_MS + 2_000L);
        // It got an empty connection, which brings its window forward, and no paths.
        assertThat(got.get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void otherVersionMessageNamesBothVersions() {
        assertThat(ClouseauApp.otherVersionMessage("0.11.2-alpha", "0.11.3-alpha"))
                .contains("Clouseau 0.11.2-alpha is already running").contains("Clouseau 0.11.3-alpha didn't start")
                .doesNotContain("''");
        assertThat(ClouseauApp.otherVersionMessage(null, "0.11.3-alpha"))
                .startsWith("An older Clouseau is already running").contains("0.11.3-alpha");
    }

    @Test
    void reportsNoInstanceWhenNothingListens() throws Exception {
        int port = freePort();   // nothing listens here

        assertThat(send(port, List.of("/logs/a.log"))).isEqualTo(Delivery.NO_INSTANCE);
    }
}
