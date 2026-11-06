package com.droidenx.clouseau.core;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.UserAuthFactory;
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link SshLogSource} against an embedded SSH server (Apache MINA SSHD). The server
 * answers any command with one line, {@code hello}, and then keeps the channel open, like
 * {@code tail -F} on a quiet log.
 */
class SshLogSourceServerTest {

    private SshServer server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) server.stop(true);
    }

    /** Starts a server that lets {@code root} in with an empty password through {@code auth}. */
    private int startServer(UserAuthFactory auth) throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setPasswordAuthenticator((user, password, session) ->
                "root".equals(user) && password.isEmpty());
        server.setUserAuthFactories(List.of(auth));
        server.setCommandFactory((channel, command) -> new HelloCommand());
        server.start();
        return server.getPort();
    }

    /** Issue #2: "No password" passed a null password to sshj, which threw. */
    @Test
    @Timeout(30)
    void noPasswordSignsInWithAnEmptyPassword() throws Exception {
        assertThat(firstLine(noPassword(startServer(UserAuthPasswordFactory.INSTANCE)))).isEqualTo("hello");
    }

    @Test
    @Timeout(30)
    void noPasswordSignsInWithKeyboardInteractive() throws Exception {
        assertThat(firstLine(noPassword(startServer(UserAuthKeyboardInteractiveFactory.INSTANCE)))).isEqualTo("hello");
    }

    private static SshConfig noPassword(int port) {
        return new SshConfig("127.0.0.1", port, "root", null, null, null, "/var/log/app.log");
    }

    /** Opens {@code config}, returns the first line streamed, then closes the source. */
    private static String firstLine(SshConfig config) throws Exception {
        SshLogSource source = new SshLogSource(config);
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch ended = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                source.open(lines::add);
            } catch (Throwable e) {
                error.set(e);
                lines.add("<error>");
            } finally {
                ended.countDown();
            }
        }, "ssh-test-open");
        t.setDaemon(true);
        t.start();
        try {
            String line = lines.poll(20, TimeUnit.SECONDS);
            assertThat(error.get()).isNull();
            return line;
        } finally {
            source.close();
            assertThat(ended.await(10, TimeUnit.SECONDS)).as("open() returns after close()").isTrue();
        }
    }

    /**
     * Issue #1: closing a source whose connection had gone silent waited for the server to
     * confirm the channel close (sshj's 30 s timeout), freezing the UI that called it.
     */
    @Test
    @Timeout(30)
    void closeReturnsAtOnceWhenTheConnectionHasGoneSilent() throws Exception {
        int port = startServer(UserAuthPasswordFactory.INSTANCE);
        try (FreezableProxy proxy = new FreezableProxy(port)) {
            SshLogSource source = new SshLogSource(noPassword(proxy.port()));
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            CountDownLatch ended = new CountDownLatch(1);
            Thread t = new Thread(() -> {
                try { source.open(lines::add); } catch (Exception ignored) {} finally { ended.countDown(); }
            }, "ssh-test-open");
            t.setDaemon(true);
            t.start();
            assertThat(lines.poll(20, TimeUnit.SECONDS)).isEqualTo("hello");

            proxy.freeze();   // the network goes quiet: nothing reaches either side any more
            long start = System.nanoTime();
            source.close();
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(millis).as("close() time in ms").isLessThan(3000);
            assertThat(ended.await(3, TimeUnit.SECONDS)).as("open() returns after close()").isTrue();
        }
    }

    /**
     * A TCP proxy to the server that can stop forwarding, like a connection a router or VPN
     * dropped without closing: both sockets stay open but no bytes get through.
     */
    static class FreezableProxy implements AutoCloseable {
        private final java.net.ServerSocket listener = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getLoopbackAddress());
        private final List<java.net.Socket> sockets = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile boolean frozen;

        FreezableProxy(int targetPort) throws java.io.IOException {
            Thread acceptor = new Thread(() -> {
                try {
                    java.net.Socket client = listener.accept();
                    java.net.Socket target = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), targetPort);
                    sockets.add(client);
                    sockets.add(target);
                    pump(client, target);
                    pump(target, client);
                } catch (java.io.IOException ignored) {}
            }, "proxy-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() { return listener.getLocalPort(); }

        void freeze() { frozen = true; }

        private void pump(java.net.Socket from, java.net.Socket to) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (frozen) continue;   // read and drop, so neither side sees a closed socket
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (java.io.IOException ignored) {}
            }, "proxy-pump");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() throws java.io.IOException {
            listener.close();
            for (java.net.Socket s : sockets) s.close();
        }
    }

    /** Writes {@code hello} and then keeps the channel open until the client closes it. */
    static class HelloCommand implements Command {
        private OutputStream out;
        private ExitCallback exit;

        @Override public void setInputStream(InputStream in) {}
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) {}
        @Override public void setExitCallback(ExitCallback callback) { this.exit = callback; }

        @Override
        public void start(ChannelSession channel, Environment env) throws java.io.IOException {
            out.write("hello\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (exit != null) exit.onExit(0);
        }
    }
}
