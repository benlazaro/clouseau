package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogSource;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.keepalive.KeepAliveProvider;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive;
import net.schmizz.sshj.userauth.method.AuthMethod;
import net.schmizz.sshj.userauth.method.AuthNone;
import net.schmizz.sshj.userauth.method.AuthPassword;
import net.schmizz.sshj.userauth.method.PasswordResponseProvider;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import net.schmizz.sshj.userauth.password.Resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Streams a remote log file over SSH using {@code tail -n <lines> -F -- '<path>'}
 * (see {@link #tailCommand}). {@code -F} follows the file by name, so a log that is
 * rotated or replaced keeps streaming. SSH keepalives detect a connection that died
 * without being closed, so {@link #open} fails instead of waiting forever.
 * Call {@link #close()} to disconnect and unblock the streaming thread.
 *
 * <p>Note: uses {@link PromiscuousVerifier} (trust all hosts). For production use,
 * replace with an OpenSSH known-hosts verifier or a GUI "trust this host?" prompt.
 */
@Slf4j
public class SshLogSource implements LogSource {

    /** Number of existing lines to fetch before streaming new ones. */
    private static final int TAIL_LINES = 1000;
    /**
     * Seconds between keepalive requests. sshj gives up after 5 unanswered ones, so a dead
     * connection is reported after about 75 seconds.
     */
    static final int KEEP_ALIVE_SECONDS = 15;
    /** Exit status of {@link #tailCommand} when the file can't be read (EX_NOINPUT). */
    static final int EXIT_UNREADABLE = 66;
    /** How many of the server's last error lines are kept for an error message. */
    private static final int STDERR_LINES_KEPT = 5;

    private final SshConfig config;
    private volatile SSHClient ssh;
    private volatile boolean   closed;

    /** The remote file doesn't exist or the user can't read it. */
    public static class UnreadableFileException extends IOException {
        private final String path;

        public UnreadableFileException(String path) {
            super("Can't read " + path + " on the server");
            this.path = path;
        }

        public String path() {
            return path;
        }
    }

    public SshLogSource(SshConfig config) {
        this.config = config;
    }

    @Override
    public String getName() {
        return config.displayName();
    }

    /**
     * Connects to the remote host, authenticates, and streams lines from
     * {@link #tailCommand} to {@code lineConsumer}.
     * Blocks until the connection is closed or an error occurs.
     *
     * @throws UnreadableFileException if the remote file can't be read
     * @throws IOException if the connection is lost or {@code tail} fails
     */
    @Override
    public void open(Consumer<String> lineConsumer) throws Exception {
        DefaultConfig sshConfig = new DefaultConfig();
        sshConfig.setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE);
        SSHClient client = new SSHClient(sshConfig);
        // Set before connect: sshj starts the keepalive thread on connect when it's enabled.
        client.getConnection().getKeepAlive().setKeepAliveInterval(KEEP_ALIVE_SECONDS);
        client.addHostKeyVerifier(new PromiscuousVerifier());
        client.connect(config.host(), config.port());
        this.ssh = client;
        if (closed) {   // close() ran while connecting, before it could see the client
            client.disconnect();
            return;
        }

        authenticate(client);

        Session s = client.startSession();
        Session.Command c = s.exec(tailCommand(config.remotePath()));

        // Drain stderr: tail -F reports replaced files there, and unread output would
        // eventually fill the channel's window and stall stdout too.
        Deque<String> stderrTail = new ArrayDeque<>();
        Thread stderrReader = new Thread(() -> drainStderr(c.getErrorStream(), stderrTail),
                "ssh-stderr-" + config.displayName());
        stderrReader.setDaemon(true);
        stderrReader.start();

        log.info("SSH tail started: {}", config.displayName());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Utf8Bom.skip(c.getInputStream()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineConsumer.accept(line);
            }
        } catch (IOException e) {
            if (closed) return;
            throw e;
        }
        if (closed) return;

        try {
            c.join(5, TimeUnit.SECONDS);
        } catch (IOException e) {
            log.debug("No exit status from tail for {}", config.displayName(), e);
        }
        stderrReader.join(1000);
        Integer status = c.getExitStatus();
        log.info("SSH tail ended: {} (exit status {})", config.displayName(), status);
        if (status != null && status == EXIT_UNREADABLE) {
            throw new UnreadableFileException(config.remotePath());
        }
        if (status == null || status != 0) {
            String stderr;
            synchronized (stderrTail) {
                stderr = String.join("\n", stderrTail);
            }
            throw new IOException(stderr.isBlank()
                    ? "tail stopped (exit status " + status + ")"
                    : stderr);
        }
    }

    private void drainStderr(InputStream err, Deque<String> kept) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(err, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("SSH tail {}: {}", config.displayName(), line);
                synchronized (kept) {
                    if (kept.size() == STDERR_LINES_KEPT) kept.removeFirst();
                    kept.addLast(line);
                }
            }
        } catch (IOException e) {
            log.debug("stderr of {} closed", config.displayName(), e);
        }
    }

    /**
     * Builds the remote command for {@code remotePath}: a small POSIX {@code sh} script
     * that exits with {@link #EXIT_UNREADABLE} if the file can't be read, and otherwise
     * runs {@code tail -n <lines> -F}. The check is needed because {@code -F} waits for a
     * missing file to appear instead of failing. Running it under {@code sh -c} makes it
     * work whatever the user's login shell is.
     *
     * <p>The path is passed to the script as {@code $1}, quoted so the login shell treats
     * it as one literal argument: spaces don't split it and metacharacters
     * ({@code ; $ ` | &} …) are not executed. {@code --} stops a path starting with
     * {@code -} from being read as an option. A leading {@code ~/} is left unquoted so it
     * still expands to the remote user's home directory.
     */
    static String tailCommand(String remotePath) {
        String target;
        if (remotePath.equals("~")) {
            target = "~";
        } else if (remotePath.startsWith("~/")) {
            target = "~/" + shellQuote(remotePath.substring(2));
        } else {
            target = shellQuote(remotePath);
        }
        String script = "test -r \"$1\" || exit " + EXIT_UNREADABLE
                + "; exec tail -n " + TAIL_LINES + " -F -- \"$1\"";
        return "sh -c " + shellQuote(script) + " sh " + target;
    }

    /** Wraps {@code s} in single quotes, encoding each embedded {@code '} as {@code '\''}. */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void authenticate(SSHClient client) throws Exception {
        if (config.keyFilePath() != null && !config.keyFilePath().isBlank()) {
            String passphrase = config.keyPassphrase();
            if (passphrase != null && !passphrase.isBlank()) {
                client.authPublickey(config.user(),
                        client.loadKeys(config.keyFilePath(), passphrase.toCharArray()));
            } else {
                client.authPublickey(config.user(), config.keyFilePath());
            }
        } else if (config.password() == null || config.password().isEmpty()) {
            client.auth(config.user(), noPasswordMethods());
        } else {
            client.authPassword(config.user(), config.password());
        }
    }

    /**
     * The sign-in methods tried, in order, for "No password" (and an empty password):
     * SSH {@code none}, then {@code password} with an empty password, then
     * {@code keyboard-interactive} answering password prompts with an empty password.
     * Servers differ in which one an account without a password is let in by: OpenSSH
     * with {@code PermitEmptyPasswords} uses {@code password} or
     * {@code keyboard-interactive}, Dropbear started with {@code -B} uses {@code password}.
     */
    static List<AuthMethod> noPasswordMethods() {
        PasswordFinder empty = new PasswordFinder() {
            @Override public char[] reqPassword(Resource<?> resource) { return new char[0]; }
            @Override public boolean shouldRetry(Resource<?> resource) { return false; }
        };
        return List.of(
                new AuthNone(),
                new AuthPassword(empty),
                new AuthKeyboardInteractive(new PasswordResponseProvider(empty)));
    }

    /** Closes the SSH session and disconnects, unblocking any active {@link #open} call. */
    /**
     * Disconnects without closing the channel first: a channel close waits for the
     * server's reply (up to sshj's 30 s timeout), which never comes on a connection that
     * went silent. Disconnecting fails the open streams at once and closes the socket;
     * the server ends {@code tail} when the connection goes away.
     */
    @Override
    public void close() {
        closed = true;
        try { SSHClient cl = ssh; if (cl != null) cl.disconnect(); } catch (Exception ignored) {}
    }
}
