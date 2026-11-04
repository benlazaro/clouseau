package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogSource;
import com.droidenx.clouseau.core.Utf8Bom;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Streams a remote log file over SSH using {@code tail -n <lines> -f -- '<path>'}
 * (see {@link #tailCommand}).
 * Call {@link #close()} to disconnect and unblock the streaming thread.
 *
 * <p>Note: uses {@link PromiscuousVerifier} (trust all hosts). For production use,
 * replace with an OpenSSH known-hosts verifier or a GUI "trust this host?" prompt.
 */
@Slf4j
public class SshLogSource implements LogSource {

    /** Number of existing lines to fetch before streaming new ones. */
    private static final int TAIL_LINES = 1000;

    private final SshConfig config;
    private volatile SSHClient      ssh;
    private volatile Session        session;
    private volatile Session.Command cmd;

    public SshLogSource(SshConfig config) {
        this.config = config;
    }

    @Override
    public String getName() {
        return config.displayName();
    }

    /**
     * Connects to the remote host, authenticates, and streams lines from
     * {@code tail -n TAIL_LINES -f <remotePath>} to {@code lineConsumer}.
     * Blocks until the connection is closed or an error occurs.
     */
    @Override
    public void open(Consumer<String> lineConsumer) throws Exception {
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier(new PromiscuousVerifier());
        client.connect(config.host(), config.port());
        this.ssh = client;

        authenticate(client);

        Session s = client.startSession();
        this.session = s;
        Session.Command c = s.exec(tailCommand(config.remotePath()));
        this.cmd = c;

        log.info("SSH tail started: {}", config.displayName());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Utf8Bom.skip(c.getInputStream()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineConsumer.accept(line);
            }
        }
        log.info("SSH tail ended: {}", config.displayName());
    }

    /**
     * Builds the remote {@code tail} command for {@code remotePath}, quoted so the remote
     * POSIX shell treats the path as one literal argument: spaces don't split it and
     * metacharacters ({@code ; $ ` | &} …) are not executed. {@code --} stops a path
     * starting with {@code -} from being read as an option. A leading {@code ~/} is left
     * unquoted so it still expands to the remote user's home directory.
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
        return "tail -n " + TAIL_LINES + " -f -- " + target;
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
        } else {
            client.authPassword(config.user(), config.password());
        }
    }

    /** Closes the SSH session and disconnects, unblocking any active {@link #open} call. */
    @Override
    public void close() {
        try { Session.Command c = cmd;     if (c != null) c.close();          } catch (Exception ignored) {}
        try { Session        s = session;  if (s != null) s.close();          } catch (Exception ignored) {}
        try { SSHClient      cl = ssh;     if (cl != null) cl.disconnect();   } catch (Exception ignored) {}
    }
}
