package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class SingleInstanceManagerTest {

    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private ServerSocket server;

    @AfterEach
    void close() throws IOException {
        if (server != null) server.close();
    }

    private int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private List<String> drain(int expected) throws InterruptedException {
        List<String> out = new ArrayList<>();
        while (out.size() < expected) {
            String p = received.poll(5, TimeUnit.SECONDS);
            if (p == null) break;
            out.add(p);
        }
        return out;
    }

    @Test
    void deliversEveryFileOfOneLaunchInOrder() throws Exception {
        int port = freePort();
        server = SingleInstanceManager.listen(port, received::add);
        assertThat(server).isNotNull();

        List<String> paths = List.of("C:\\logs\\a.log", "C:\\logs\\b.log", "C:\\logs\\follow.log");
        assertThat(SingleInstanceManager.send(port, paths)).isTrue();

        assertThat(drain(3)).containsExactlyElementsOf(paths);
    }

    @Test
    void deliversFilesFromManyLaunchesAtOnce() throws Exception {
        int port = freePort();
        server = SingleInstanceManager.listen(port, received::add);

        int launches = 20;
        ExecutorService pool = Executors.newFixedThreadPool(launches);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < launches; i++) {
                List<String> paths = List.of("/logs/" + i + "-1.log", "/logs/" + i + "-2.log");
                results.add(pool.submit(() -> {
                    start.await();
                    return SingleInstanceManager.send(port, paths);
                }));
            }
            start.countDown();
            for (Future<Boolean> r : results) assertThat(r.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(drain(launches * 2)).hasSize(launches * 2);
    }

    @Test
    void keepsNonAsciiPaths() throws Exception {
        int port = freePort();
        server = SingleInstanceManager.listen(port, received::add);

        assertThat(SingleInstanceManager.send(port, List.of("/logs/journal-é-日本.log"))).isTrue();

        assertThat(drain(1)).containsExactly("/logs/journal-é-日本.log");
    }

    @Test
    void secondListenerOnTheSamePortIsNotPrimary() throws Exception {
        int port = freePort();
        server = SingleInstanceManager.listen(port, received::add);

        assertThat(SingleInstanceManager.listen(port, p -> {})).isNull();
    }

    @Test
    void reportsFailureWhenNoPrimaryAnswers() throws Exception {
        int port = freePort();   // nothing listens here

        assertThat(SingleInstanceManager.send(port, List.of("/logs/a.log"))).isFalse();
    }
}
