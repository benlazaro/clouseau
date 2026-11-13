package com.droidenx.clouseau.core;

import net.schmizz.sshj.xfer.FilePermission;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.subsystem.SubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link SshRemote} against an embedded SSH server (Apache MINA SSHD) that lets {@code ops}
 * in with the password {@code secret}, serves {@code root} over SFTP, and answers the check
 * command with {@link #checkStatus}.
 */
class SshRemoteTest {

    @TempDir Path root;
    private SshServer server;
    /** The exit status the server gives the check command. */
    private volatile int checkStatus;
    /** Run commands with the local sh, as a real server would, instead of answering with {@link #checkStatus}. */
    private volatile boolean realShell;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) server.stop(true);
    }

    private int startServer() throws Exception {
        return startServer(List.of(new SftpSubsystemFactory()));
    }

    private int startServer(List<? extends SubsystemFactory> subsystems) throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setPasswordAuthenticator((user, password, session) -> "ops".equals(user) && "secret".equals(password));
        server.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE));
        // id isn't answered, so every file counts as readable (as on a server without id).
        server.setCommandFactory((channel, command) -> realShell ? new ShellCommand(command)
                : new ExitCommand(command.startsWith("sh -c") ? checkStatus : 127));
        server.setSubsystemFactories(List.copyOf(subsystems));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.start();
        return server.getPort();
    }

    private SshConfig config(int port, String password, String path) {
        return new SshConfig("127.0.0.1", port, "ops", password, null, null, path);
    }

    @Test
    @Timeout(30)
    void signsInAndFindsTheFileReadable() throws Exception {
        try (SshRemote r = new SshRemote(config(startServer(), "secret", "/var/log/app.log"))) {
            r.connect();
            checkStatus = 0;
            r.check("/var/log/app.log");
        }
    }

    @Test
    @Timeout(30)
    void reportsEachWayTheFileCanFail() throws Exception {
        try (SshRemote r = new SshRemote(config(startServer(), "secret", "/x"))) {
            r.connect();
            checkStatus = SshRemote.EXIT_MISSING;
            assertFailure(() -> r.check("/x"), SshRemote.Step.FILE, SshRemote.Reason.MISSING);
            checkStatus = SshRemote.EXIT_UNREADABLE;
            assertFailure(() -> r.check("/x"), SshRemote.Step.FILE, SshRemote.Reason.UNREADABLE);
            checkStatus = SshRemote.EXIT_FOLDER;
            assertFailure(() -> r.check("/x"), SshRemote.Step.FILE, SshRemote.Reason.FOLDER);
        }
    }

    @Test
    @Timeout(30)
    void wrongPasswordIsRejectedAtSignIn() throws Exception {
        try (SshRemote r = new SshRemote(config(startServer(), "wrong", "/x"))) {
            assertFailure(r::connect, SshRemote.Step.SIGN_IN, SshRemote.Reason.REJECTED);
        }
    }

    @Test
    @Timeout(30)
    void closedPortIsRefused() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        try (SshRemote r = new SshRemote(config(port, "secret", "/x"))) {
            assertFailure(r::connect, SshRemote.Step.CONNECT, SshRemote.Reason.REFUSED);
        }
    }

    @Test
    @Timeout(30)
    void missingKeyFileFailsBeforeAskingTheServer() throws Exception {
        SshConfig c = new SshConfig("127.0.0.1", startServer(), "ops", null,
                root.resolve("no-such-key").toString(), null, "/x");
        try (SshRemote r = new SshRemote(c)) {
            assertFailure(r::connect, SshRemote.Step.SIGN_IN, SshRemote.Reason.KEY_FILE);
        }
    }

    @Test
    @Timeout(30)
    void closeFromAnotherThreadEndsAConnect() throws Exception {
        // A listener that accepts and never speaks SSH, like a server that hung.
        try (ServerSocket silent = new ServerSocket(0)) {
            SshRemote r = new SshRemote(config(silent.getLocalPort(), "secret", "/x"));
            Thread closer = new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                r.close();
            });
            closer.start();
            long start = System.nanoTime();
            assertThatThrownBy(r::connect).isInstanceOf(SshRemote.Failure.class);
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(SshRemote.TIMEOUT_SECONDS);
        }
    }

    @Test
    @Timeout(30)
    void listsFoldersThenFilesByName() throws Exception {
        Files.createDirectories(root.resolve("logs/archive"));
        Files.createDirectories(root.resolve("logs/Nginx"));
        Files.writeString(root.resolve("logs/b.log"), "x");
        Files.writeString(root.resolve("logs/A.log"), "xyz");
        try (SshRemote r = new SshRemote(config(startServer(), "secret", "/x"))) {
            r.connect();
            SshRemote.Listing l = r.list("/logs");
            assertThat(l.path()).isEqualTo("/logs");
            assertThat(l.folders()).extracting(SshRemote.Entry::name).containsExactly("archive", "Nginx");
            assertThat(l.files()).extracting(SshRemote.Entry::name).containsExactly("A.log", "b.log");
            assertThat(l.files().get(0).size()).isEqualTo(3);
            assertThat(l.files().get(0).path()).isEqualTo("/logs/A.log");
            assertThat(l.unreadable()).isZero();
        }
    }

    @Test
    @Timeout(30)
    void startsInTheRemotePathsFolderElseHome() throws Exception {
        Files.createDirectories(root.resolve("logs"));
        try (SshRemote r = new SshRemote(config(startServer(), "secret", "/x"))) {
            r.connect();
            assertThat(r.startFolder("/logs/app.log")).isEqualTo("/logs");
            assertThat(r.startFolder("/gone/app.log")).isEqualTo("/");   // no /var/log here: home
            assertThat(r.startFolder("")).isEqualTo("/");
        }
    }

    /** As Dropbear without sftp-server: the subsystem is accepted, then its channel just ends. */
    private static final SubsystemFactory SFTP_ENDS_AT_ONCE = new SubsystemFactory() {
        @Override public String getName() { return "sftp"; }
        @Override public Command createSubsystem(ChannelSession channel) { return new ExitCommand(127); }
    };

    @Test
    @Timeout(30)
    void withoutSftpListsFoldersThroughTheShell() throws Exception {
        assumeTrue(hasSh(), "no POSIX sh on PATH");
        realShell = true;
        Files.createDirectories(root.resolve("logs/archive"));
        Files.writeString(root.resolve("logs/b.log"), "x");
        Files.writeString(root.resolve("logs/A.log"), "xyz");
        String logs = root.resolve("logs").toString().replace('\\', '/');
        try (SshRemote r = new SshRemote(config(startServer(List.of(SFTP_ENDS_AT_ONCE)), "secret", "/x"))) {
            r.connect();
            String start = r.startFolder(logs + "/app.log");
            assertThat(start).endsWith("/logs");
            SshRemote.Listing l = r.list(start);
            assertThat(l.path()).isEqualTo(start);
            assertThat(l.folders()).extracting(SshRemote.Entry::name).containsExactly("archive");
            assertThat(l.files()).extracting(SshRemote.Entry::name).containsExactly("A.log", "b.log");
            assertThat(l.files().get(0).path()).isEqualTo(start + "/A.log");
            assertThat(l.files().get(0).size()).isIn(3L, -1L);   // -1 where there's no stat
            assertThat(r.list(start + "/archive").path()).isEqualTo(start + "/archive");
            assertThatThrownBy(() -> r.list(logs + "/none"))
                    .isInstanceOf(IOException.class).isNotInstanceOf(SshRemote.Failure.class);
        }
    }

    @Test
    @Timeout(30)
    void sftpThatEndsAtOnceAndNoShellIsNoSftp() throws Exception {
        try (SshRemote r = new SshRemote(config(startServer(List.of(SFTP_ENDS_AT_ONCE)), "secret", "/x"))) {
            r.connect();
            assertFailure(() -> r.startFolder("/x"), SshRemote.Step.FILE, SshRemote.Reason.NO_SFTP);
            assertFailure(() -> r.list("/"), SshRemote.Step.FILE, SshRemote.Reason.NO_SFTP);
        }
    }

    @Test
    @Timeout(30)
    void serverWithoutSftpOrShellIsNoSftp() throws Exception {
        try (SshRemote r = new SshRemote(config(startServer(List.of()), "secret", "/x"))) {
            r.connect();
            assertFailure(() -> r.startFolder("/x"), SshRemote.Step.FILE, SshRemote.Reason.NO_SFTP);
        }
    }

    // ── Pure parts ───────────────────────────────────────────────────────────

    @Test
    void readableFollowsOwnerGroupAndOthersBits() {
        SshRemote.Identity ops = new SshRemote.Identity(1000, Set.of(1000, 4));
        Set<FilePermission> ownerOnly = EnumSet.of(FilePermission.USR_R, FilePermission.USR_W);
        Set<FilePermission> groupRead = EnumSet.of(FilePermission.USR_R, FilePermission.GRP_R);
        Set<FilePermission> worldRead = EnumSet.of(FilePermission.USR_R, FilePermission.OTH_R);

        assertThat(SshRemote.readable(ops, 1000, 0, ownerOnly)).isTrue();
        assertThat(SshRemote.readable(ops, 0, 0, ownerOnly)).isFalse();
        assertThat(SshRemote.readable(ops, 0, 4, groupRead)).isTrue();     // adm group
        assertThat(SshRemote.readable(ops, 0, 9, groupRead)).isFalse();
        assertThat(SshRemote.readable(ops, 0, 9, worldRead)).isTrue();
        // The owner's bits decide for the owner, even if others may read.
        assertThat(SshRemote.readable(ops, 1000, 0, EnumSet.of(FilePermission.OTH_R))).isFalse();
        assertThat(SshRemote.readable(new SshRemote.Identity(0, Set.of(0)), 5, 5, Set.of())).isTrue();
        assertThat(SshRemote.readable(null, 5, 5, Set.of())).isTrue();
    }

    @Test
    void parsesIdOutput() {
        assertThat(SshRemote.parseIdentity("1000\n1000 4 27\n"))
                .isEqualTo(new SshRemote.Identity(1000, Set.of(1000, 4, 27)));
        assertThat(SshRemote.parseIdentity("1000\r\n1000\r\n"))
                .isEqualTo(new SshRemote.Identity(1000, Set.of(1000)));
        assertThat(SshRemote.parseIdentity("sh: id: not found")).isNull();
        assertThat(SshRemote.parseIdentity("x\ny")).isNull();
    }

    /** Runs the real check command through a local sh, if there is one. */
    @Test
    @Timeout(10)
    void checkCommandTellsMissingFolderAndReadable(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("my app's $x.log");
        Files.writeString(file, "x");
        String f = file.toString().replace('\\', '/');
        String d = dir.toString().replace('\\', '/');
        assertThat(status(SshRemote.checkCommand(f))).isEqualTo(0);
        assertThat(status(SshRemote.checkCommand(d))).isEqualTo(SshRemote.EXIT_FOLDER);
        assertThat(status(SshRemote.checkCommand(d + "/none.log"))).isEqualTo(SshRemote.EXIT_MISSING);
    }

    @Test
    void parsesAListingAfterLoginNoise() {
        String out = "Welcome to box!\n" + SshRemote.MARK + "\0/var/log\0"
                + "f r 120 1700000000\0b.log\0"
                + "d r 4096 1700000000\0nginx\0"
                + "f - 10 1700000000\0secret.log\0"
                + "f r -1 0\0two\nlines.log\0"
                + "d - 4096 1700000000\0Apps\0";
        SshRemote.Listing l = SshRemote.parseListing(out);
        assertThat(l.path()).isEqualTo("/var/log");
        assertThat(l.folders()).extracting(SshRemote.Entry::name).containsExactly("Apps", "nginx");
        assertThat(l.files()).extracting(SshRemote.Entry::name).containsExactly("b.log", "two\nlines.log");
        assertThat(l.files().get(0).path()).isEqualTo("/var/log/b.log");
        assertThat(l.files().get(0).size()).isEqualTo(120);
        assertThat(l.files().get(0).modified().getEpochSecond()).isEqualTo(1700000000L);
        assertThat(l.files().get(1).size()).isEqualTo(-1);
        assertThat(l.files().get(1).modified().getEpochSecond()).isZero();
        assertThat(l.unreadable()).isEqualTo(1);

        assertThat(SshRemote.parseListing(SshRemote.MARK + "\0/\0f r 1 1\0x\0").files().get(0).path()).isEqualTo("/x");
        assertThat(SshRemote.parseListing("sh: syntax error")).isNull();
    }

    /** Runs the real list command through a local sh, if there is one. */
    @Test
    @Timeout(10)
    void listCommandListsFoldersAndFiles(@TempDir Path dir) throws Exception {
        assumeTrue(hasSh(), "no POSIX sh on PATH");
        Files.createDirectories(dir.resolve("sub folder"));
        Files.writeString(dir.resolve("my app's $x.log"), "abc");
        Files.writeString(dir.resolve(".hidden"), "x");
        String d = dir.toString().replace('\\', '/');
        SshRemote.Listing l = SshRemote.parseListing(output(SshRemote.listCommand(d)));
        assertThat(l).isNotNull();
        assertThat(l.folders()).extracting(SshRemote.Entry::name).containsExactly("sub folder");
        assertThat(l.files()).extracting(SshRemote.Entry::name).containsExactly(".hidden", "my app's $x.log");
        assertThat(l.files().get(1).size()).isIn(3L, -1L);
        assertThat(status(SshRemote.folderCommand(d + "/none"))).isEqualTo(SshRemote.EXIT_NO_FOLDER);
        assertThat(status(SshRemote.listCommand(d + "/none"))).isEqualTo(SshRemote.EXIT_NO_FOLDER);
    }

    private static boolean hasSh() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "exit 0").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** The command's output, run by a local sh. Sent on stdin: Windows would mangle its quotes as an argument. */
    private static String output(String command) throws Exception {
        Process p = new ProcessBuilder("sh").start();
        try (var stdin = p.getOutputStream()) {
            stdin.write(command.getBytes(StandardCharsets.UTF_8));
        }
        byte[] out = p.getInputStream().readAllBytes();
        assumeTrue(p.waitFor(10, TimeUnit.SECONDS), "sh timed out");
        return new String(out, StandardCharsets.UTF_8);
    }

    private static int status(String command) throws Exception {
        Process p;
        try {
            p = new ProcessBuilder("sh").redirectErrorStream(true).start();
        } catch (IOException e) {
            assumeTrue(false, "no POSIX sh on PATH");
            throw e;
        }
        try (var stdin = p.getOutputStream()) {
            stdin.write((command + "; echo status=$?").getBytes(StandardCharsets.UTF_8));
        }
        assumeTrue(p.waitFor(10, TimeUnit.SECONDS), "sh timed out");
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return Integer.parseInt(out.substring(out.lastIndexOf('=') + 1));
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                      SshRemote.Step step, SshRemote.Reason reason) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(SshRemote.Failure.class, f -> {
                    assertThat(f.step()).isEqualTo(step);
                    assertThat(f.reason()).isEqualTo(reason);
                });
    }

    /** Exits at once with a fixed status. */
    static class ExitCommand implements Command {
        private final int status;
        private ExitCallback exit;
        private OutputStream out;

        ExitCommand(int status) { this.status = status; }

        @Override public void setInputStream(InputStream in) {}
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) {}
        @Override public void setExitCallback(ExitCallback callback) { this.exit = callback; }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            out.flush();
            exit.onExit(status);
        }

        @Override public void destroy(ChannelSession channel) {}
    }

    /** Runs the command with the local sh and passes on its output and exit status. */
    static class ShellCommand implements Command {
        private final String command;
        private ExitCallback exit;
        private OutputStream out, err;

        ShellCommand(String command) { this.command = command; }

        @Override public void setInputStream(InputStream in) {}
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.exit = callback; }

        @Override
        public void start(ChannelSession channel, Environment env) {
            Thread t = new Thread(() -> {
                int status = 127;
                try {
                    Process p = new ProcessBuilder("sh").start();   // command on stdin, as in output()
                    try (var stdin = p.getOutputStream()) {
                        stdin.write(command.getBytes(StandardCharsets.UTF_8));
                    }
                    byte[] stdout = p.getInputStream().readAllBytes();
                    byte[] stderr = p.getErrorStream().readAllBytes();
                    status = p.waitFor();
                    out.write(stdout);
                    out.flush();
                    err.write(stderr);
                    err.flush();
                } catch (Exception e) {
                    // status stays 127, as for a missing shell
                }
                exit.onExit(status);
            }, "test-shell");
            t.setDaemon(true);
            t.start();
        }

        @Override public void destroy(ChannelSession channel) {}
    }
}
