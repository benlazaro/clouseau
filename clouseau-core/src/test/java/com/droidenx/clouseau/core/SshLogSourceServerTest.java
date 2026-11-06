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
