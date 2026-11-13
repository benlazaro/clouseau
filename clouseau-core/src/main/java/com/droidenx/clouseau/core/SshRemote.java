package com.droidenx.clouseau.core;

import lombok.extern.slf4j.Slf4j;
import net.schmizz.keepalive.KeepAliveProvider;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.xfer.FilePermission;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive;
import net.schmizz.sshj.userauth.method.AuthMethod;
import net.schmizz.sshj.userauth.method.AuthNone;
import net.schmizz.sshj.userauth.method.AuthPassword;
import net.schmizz.sshj.userauth.method.PasswordResponseProvider;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import net.schmizz.sshj.userauth.password.Resource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * One SSH sign-in for the SSH dialog's checks (G-13, G-14): {@link #connect} signs in,
 * {@link #check} tells whether the log can be read, and {@link #list} lists a folder over
 * SFTP. {@link SshLogSource} signs in the same way ({@link #newClient}, {@link #authenticate}).
 *
 * <p>Every step is bounded: the TCP connect and each reply wait at most
 * {@link #TIMEOUT_SECONDS}, and keepalives fail a connection that goes silent, as they do for
 * the source. {@link #close()} may be called from any thread, at any time, and disconnects at
 * once; a {@link #connect} still running then ends with an exception or returns early.
 *
 * <p>Like the source, it trusts every host key ({@link PromiscuousVerifier}).
 */
@Slf4j
public final class SshRemote implements AutoCloseable {

    /** Longest wait for the TCP connect and for each reply from the server. */
    public static final int TIMEOUT_SECONDS = 15;

    /** Exit statuses of {@link #checkCommand}. */
    static final int EXIT_MISSING = 2, EXIT_UNREADABLE = 3, EXIT_FOLDER = 4;

    /** The step a {@link Failure} happened in. */
    public enum Step { CONNECT, SIGN_IN, FILE }

    /** Why it failed, as far as can be told. */
    public enum Reason {
        /** The host name doesn't resolve. */
        UNKNOWN_HOST,
        /** Nothing listens on that port, or no route to the host. */
        REFUSED,
        /** No answer within {@link #TIMEOUT_SECONDS}. */
        TIMED_OUT,
        /** The server turned the user, password or key down. */
        REJECTED,
        /** The key file can't be read, or its passphrase is wrong. */
        KEY_FILE,
        /** The remote file doesn't exist. */
        MISSING,
        /** The remote file exists but this user can't read it. */
        UNREADABLE,
        /** The remote path is a folder. */
        FOLDER,
        /**
         * The server has no SFTP (it turned the {@code sftp} subsystem down, or closed it at once:
         * Dropbear without {@code sftp-server}, a wrong {@code Subsystem} line) and its shell
         * couldn't list folders instead. Test and following need neither.
         */
        NO_SFTP,
        /** Anything else; {@link Failure#getMessage()} says what. */
        OTHER
    }

    /** A step that failed, with the reason and the underlying message. */
    public static final class Failure extends IOException {
        private final Step step;
        private final Reason reason;

        public Failure(Step step, Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.step = step;
            this.reason = reason;
        }

        public Step step()     { return step; }
        public Reason reason() { return reason; }
    }

    /**
     * A folder's contents: its absolute path, its sub-folders and the files this user can
     * read (both by name, A–Z, case-insensitive), and how many files were left out because
     * they can't be read.
     */
    public record Listing(String path, List<Entry> folders, List<Entry> files, int unreadable) {}

    /** A folder or file in a {@link Listing}; {@code size} and {@code modified} are those of the file a link points to. */
    public record Entry(String name, String path, boolean folder, long size, Instant modified) {}

    private final SshConfig config;
    private volatile SSHClient client;
    private volatile boolean closed;
    private SFTPClient sftp;
    /** The server turned SFTP down; folders are listed through its shell ({@link #shellOnly}). */
    private boolean noSftp;
    /** The signed-in user's id and groups, from {@code id}; null if {@code id} couldn't be run. */
    private Identity identity;
    private boolean identityRead;

    record Identity(int uid, Set<Integer> groups) {}

    public SshRemote(SshConfig config) {
        this.config = config;
    }

    /**
     * Connects and signs in. Blocks for at most about {@link #TIMEOUT_SECONDS} per step.
     *
     * @throws Failure for the step that failed and why
     */
    public void connect() throws Failure {
        SSHClient c = newClient();
        c.setConnectTimeout(TIMEOUT_SECONDS * 1000);
        c.setTimeout(TIMEOUT_SECONDS * 1000);
        c.getTransport().setTimeoutMs(TIMEOUT_SECONDS * 1000);
        this.client = c;
        if (closed) throw closedFailure(Step.CONNECT);
        try {
            c.connect(config.host(), config.port());
        } catch (IOException e) {
            throw connectFailure(e);
        }
        if (closed) {
            close();
            throw closedFailure(Step.CONNECT);
        }
        try {
            authenticate(c, config);
        } catch (Failure f) {
            throw f;
        } catch (UserAuthException e) {
            throw new Failure(Step.SIGN_IN, Reason.REJECTED, message(e), e);
        } catch (Exception e) {
            if (closed) throw closedFailure(Step.SIGN_IN);
            throw new Failure(Step.SIGN_IN, timedOut(e) ? Reason.TIMED_OUT : Reason.OTHER, message(e), e);
        }
        if (closed) throw closedFailure(Step.SIGN_IN);
        log.info("SSH signed in: {}@{}:{}", config.user(), config.host(), config.port());
    }

    /**
     * Checks that {@code path} is a file this user can read, with {@code test} on the server.
     *
     * @throws Failure {@link Reason#MISSING}, {@link Reason#UNREADABLE} or {@link Reason#FOLDER},
     *                 or {@link Reason#OTHER} when the command couldn't be run
     */
    public void check(String path) throws Failure {
        Exec r;
        try {
            r = exec(checkCommand(path));
        } catch (IOException e) {
            throw new Failure(Step.FILE, timedOut(e) ? Reason.TIMED_OUT : Reason.OTHER, message(e), e);
        }
        Integer s = r.status();
        if (s != null && s == 0) return;
        Reason reason = s == null ? Reason.OTHER : switch (s) {
            case EXIT_MISSING    -> Reason.MISSING;
            case EXIT_UNREADABLE -> Reason.UNREADABLE;
            case EXIT_FOLDER     -> Reason.FOLDER;
            default              -> Reason.OTHER;
        };
        String detail = r.stderr().isBlank() ? "exit status " + s : r.stderr().strip();
        throw new Failure(Step.FILE, reason, detail, null);
    }

    /**
     * The command {@link #check} runs: exits 0 for a readable file, {@link #EXIT_MISSING},
     * {@link #EXIT_UNREADABLE} or {@link #EXIT_FOLDER} otherwise. The path is passed as
     * {@code $1}, quoted as {@link SshLogSource#tailCommand} quotes it.
     */
    static String checkCommand(String path) {
        String script = "test -e \"$1\" || exit " + EXIT_MISSING
                + "; test -d \"$1\" && exit " + EXIT_FOLDER
                + "; test -r \"$1\" || exit " + EXIT_UNREADABLE
                + "; exit 0";
        return "sh -c " + SshLogSource.shellQuote(script) + " sh " + SshLogSource.remoteArg(path);
    }

    /**
     * The folder to start browsing in: the folder of {@code remotePath} when it has one that
     * exists, else {@code /var/log}, else the user's home folder. Needs {@link #connect} first.
     *
     * @throws Failure {@link Reason#NO_SFTP} if the server has no SFTP and its shell can't list folders either
     */
    public String startFolder(String remotePath) throws IOException {
        if (remotePath != null && !remotePath.isBlank()) {
            String p = remotePath.strip();
            String folder = p.endsWith("/") ? p : p.contains("/") ? p.substring(0, p.lastIndexOf('/') + 1) : "";
            if (!folder.isEmpty()) {
                String resolved = folderOrNull(folder);
                if (resolved != null) return resolved;
            }
        }
        String varLog = folderOrNull("/var/log");
        if (varLog != null) return varLog;
        return resolve("~");
    }

    /**
     * {@code path} made absolute: a leading {@code ~} or a relative path is taken from the home folder.
     * Without SFTP, {@code path} must be a folder that can be opened.
     */
    public String resolve(String path) throws IOException {
        String p = path.strip();
        if (p.isEmpty()) p = "~";
        if (shellOnly()) {
            String dir = shellFolder(p);
            if (dir == null) throw new IOException("no such folder, or no permission to open it");
            return dir;
        }
        SFTPClient s = sftp();
        if (p.equals("~")) return s.canonicalize(".");
        if (p.startsWith("~/")) p = p.substring(2);
        return s.canonicalize(p);
    }

    /** {@code path} resolved, if it's a folder; null if it isn't, or doesn't exist. */
    private String folderOrNull(String path) throws IOException {
        if (shellOnly()) return shellFolder(path);
        try {
            String resolved = resolve(path);
            return isFolder(sftp(), resolved) ? resolved : null;
        } catch (Failure f) {
            throw f;
        } catch (IOException e) {
            return null;
        }
    }

    /** Lists {@code folder} (absolute, or resolved as {@link #resolve} does). Needs {@link #connect} first. */
    public Listing list(String folder) throws IOException {
        if (shellOnly()) return shellList(folder);
        SFTPClient s = sftp();
        String dir = resolve(folder);
        Identity id = identity();
        List<Entry> folders = new ArrayList<>(), files = new ArrayList<>();
        int unreadable = 0;
        for (RemoteResourceInfo info : s.ls(dir)) {
            String name = info.getName();
            if (name.equals(".") || name.equals("..")) continue;
            FileAttributes a = info.getAttributes();
            if (a.getType() == FileMode.Type.SYMLINK) {
                try {
                    a = s.stat(info.getPath());
                } catch (IOException e) {
                    continue;   // a dangling link
                }
            }
            Entry e = new Entry(name, info.getPath(), a.getType() == FileMode.Type.DIRECTORY,
                    a.getSize(), Instant.ofEpochSecond(a.getMtime()));
            if (e.folder()) {
                folders.add(e);
            } else if (a.getType() == FileMode.Type.REGULAR) {
                if (readable(id, a.getUID(), a.getGID(), a.getPermissions())) files.add(e);
                else unreadable++;
            }
        }
        return listing(dir, folders, files, unreadable);
    }

    private static Listing listing(String dir, List<Entry> folders, List<Entry> files, int unreadable) {
        Comparator<Entry> byName = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
        folders.sort(byName);
        files.sort(byName);
        return new Listing(dir, List.copyOf(folders), List.copyOf(files), unreadable);
    }

    // ── Without SFTP: folders listed through the server's shell ──────────────

    /** Exit status of {@link #folderCommand} and {@link #listCommand} when the folder can't be opened. */
    static final int EXIT_NO_FOLDER = 5;
    /** Written before the output proper, so text a login script prints first is skipped. */
    static final String MARK = "@@clouseau-list@@";

    /**
     * Whether folders are listed through the shell: true once the server has turned SFTP down
     * ({@link Reason#NO_SFTP}), as WinSCP's SCP mode does it. Asks for SFTP the first time.
     */
    private boolean shellOnly() throws IOException {
        if (!noSftp) {
            try {
                sftp();
            } catch (Failure f) {
                if (f.reason() != Reason.NO_SFTP) throw f;
                noSftp = true;
                log.info("No SFTP on {} ({}); listing folders through its shell", config.host(), f.getMessage());
            }
        }
        return noSftp;
    }

    /** {@code path} as an absolute folder, from {@code cd} and {@code pwd}; null if it can't be opened. */
    private String shellFolder(String path) throws IOException {
        Exec r = shell(folderCommand(path));
        String out = afterMark(r.stdout());
        if (out == null) {
            if (r.status() != null && r.status() == EXIT_NO_FOLDER) return null;
            throw shellFailure(r);
        }
        String dir = out.split("\0", 2)[0];
        return dir.isEmpty() ? null : dir;
    }

    private Listing shellList(String folder) throws IOException {
        String p = folder.strip().isEmpty() ? "~" : folder.strip();
        Exec r = shell(listCommand(p));
        Listing l = parseListing(r.stdout());
        if (l != null) return l;
        if (r.status() != null && r.status() == EXIT_NO_FOLDER) {
            throw new IOException("no such folder, or no permission to open it");
        }
        throw shellFailure(r);
    }

    private Exec shell(String command) throws IOException {
        try {
            return exec(command);
        } catch (IOException e) {
            if (closed) throw closedFailure(Step.FILE);
            if (timedOut(e)) throw new Failure(Step.FILE, Reason.TIMED_OUT, message(e), e);
            throw e;
        }
    }

    /** The shell didn't run the command as a POSIX shell would: no SFTP, and no listing through the shell. */
    private Failure shellFailure(Exec r) {
        String detail = !r.stderr().isBlank() ? r.stderr().strip()
                : r.status() != null ? "exit status " + r.status() : "no answer from the shell";
        log.info("Can't list folders through {}'s shell: {}", config.host(), detail);
        return new Failure(Step.FILE, Reason.NO_SFTP, detail, null);
    }

    /**
     * The command that opens {@code path} and prints {@link #MARK}, NUL, the folder's absolute
     * path, NUL; it exits {@link #EXIT_NO_FOLDER} if the folder can't be opened.
     */
    static String folderCommand(String path) {
        String script = "cd -- \"$1\" 2>/dev/null || exit " + EXIT_NO_FOLDER
                + "; printf '%s\\0%s\\0' " + MARK + " \"$(pwd)\"";
        return "sh -c " + SshLogSource.shellQuote(script) + " sh " + SshLogSource.remoteArg(path);
    }

    /**
     * The command that lists a folder: {@link #folderCommand}'s output, then for each folder
     * and regular file (links followed, dangling ones left out) {@code "d|f r|- size mtime"},
     * NUL, the name, NUL. Readability is {@code test -r}'s; size and time come from GNU or
     * BusyBox {@code stat -c}, else BSD {@code stat -f}, else are unknown ({@code -1 0}).
     * Separating with NUL keeps any name intact, newlines included.
     */
    static String listCommand(String path) {
        String script = "cd -- \"$1\" 2>/dev/null || exit " + EXIT_NO_FOLDER
                + "; printf '%s\\0%s\\0' " + MARK + " \"$(pwd)\""
                + "; for f in .* *; do"
                + " case $f in .|..) continue;; esac;"
                + " if [ -d \"$f\" ]; then t=d; elif [ -f \"$f\" ]; then t=f; else continue; fi;"
                + " if [ -r \"$f\" ]; then r=r; else r=-; fi;"
                + " m=$(stat -L -c '%s %Y' -- \"$f\" 2>/dev/null || stat -L -f '%z %m' -- \"$f\" 2>/dev/null);"
                + " printf '%s %s %s\\0%s\\0' \"$t\" \"$r\" \"${m:--1 0}\" \"$f\";"
                + " done";
        return "sh -c " + SshLogSource.shellQuote(script) + " sh " + SshLogSource.remoteArg(path);
    }

    /** What follows {@link #MARK} in {@code out}; null if it isn't there. */
    private static String afterMark(String out) {
        int at = out.indexOf(MARK + "\0");
        return at < 0 ? null : out.substring(at + MARK.length() + 1);
    }

    /**
     * Reads {@link #listCommand}'s output; null if it has no {@link #MARK}. Files {@code test -r}
     * turned down are counted, not listed; an unknown size is -1, an unknown time the epoch.
     */
    static Listing parseListing(String out) {
        String body = afterMark(out);
        if (body == null) return null;
        String[] parts = body.split("\0", -1);
        String dir = parts[0];
        List<Entry> folders = new ArrayList<>(), files = new ArrayList<>();
        int unreadable = 0;
        for (int i = 1; i + 1 < parts.length; i += 2) {
            String[] meta = parts[i].strip().split("\\s+");
            String name = parts[i + 1];
            if (meta.length < 2 || name.isEmpty()) continue;
            boolean folder = meta[0].equals("d");
            long size = meta.length > 2 ? number(meta[2], -1) : -1;
            long mtime = meta.length > 3 ? number(meta[3], 0) : 0;
            String path = dir.endsWith("/") ? dir + name : dir + "/" + name;
            Entry e = new Entry(name, path, folder, size, Instant.ofEpochSecond(Math.max(mtime, 0)));
            if (folder) folders.add(e);
            else if (meta[1].equals("r")) files.add(e);
            else unreadable++;
        }
        return listing(dir, folders, files, unreadable);
    }

    private static long number(String s, long otherwise) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    /**
     * Whether a file with these owner, group and permissions can be read by {@code id}, as
     * Unix permissions decide it: root reads everything; otherwise the owner's bits apply to
     * the owner, the group's to a member, the others' to everyone else. With no identity
     * (the server wouldn't run {@code id}), every file counts as readable.
     */
    static boolean readable(Identity id, int uid, int gid, Set<FilePermission> perms) {
        if (id == null || id.uid() == 0) return true;
        if (uid == id.uid()) return perms.contains(FilePermission.USR_R);
        if (id.groups().contains(gid)) return perms.contains(FilePermission.GRP_R);
        return perms.contains(FilePermission.OTH_R);
    }

    /** Parses the output of {@code id -u; id -G}: the user id, then the group ids on one line. */
    static Identity parseIdentity(String out) {
        String[] lines = out.strip().split("\\R");
        if (lines.length < 2) return null;
        try {
            int uid = Integer.parseInt(lines[0].strip());
            Set<Integer> groups = new HashSet<>();
            for (String g : lines[1].strip().split("\\s+")) if (!g.isEmpty()) groups.add(Integer.parseInt(g));
            return new Identity(uid, Set.copyOf(groups));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Identity identity() {
        if (!identityRead) {
            identityRead = true;
            try {
                Exec r = exec("id -u; id -G");
                if (r.status() != null && r.status() == 0) identity = parseIdentity(r.stdout());
            } catch (IOException e) {
                log.debug("Couldn't run id on {}", config.host(), e);
            }
            if (identity == null) log.info("No user id from {}; listing every file", config.host());
        }
        return identity;
    }

    private static boolean isFolder(SFTPClient s, String path) {
        try {
            return s.stat(path).getType() == FileMode.Type.DIRECTORY;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The SFTP client, started on first use.
     *
     * @throws Failure {@link Reason#NO_SFTP} when the server has no SFTP: the subsystem is turned
     *                 down, or its channel ends before SFTP's first reply (sshj's "EOF while reading
     *                 packet") while the connection itself is still up
     */
    private SFTPClient sftp() throws IOException {
        if (sftp == null) {
            SSHClient c = signedIn();
            SFTPClient s;
            try {
                s = c.newSFTPClient();
            } catch (IOException e) {
                if (closed) throw closedFailure(Step.FILE);
                if (timedOut(e)) throw new Failure(Step.FILE, Reason.TIMED_OUT, message(e), e);
                if (c.isConnected()) throw new Failure(Step.FILE, Reason.NO_SFTP, message(e), e);
                throw e;
            }
            s.getSFTPEngine().setTimeoutMs(TIMEOUT_SECONDS * 1000);
            sftp = s;
        }
        return sftp;
    }

    private SSHClient signedIn() throws IOException {
        SSHClient c = client;
        if (c == null || closed || !c.isAuthenticated()) throw new IOException("Not signed in");
        return c;
    }

    private record Exec(Integer status, String stdout, String stderr) {}

    private Exec exec(String command) throws IOException {
        try (Session s = signedIn().startSession()) {
            Session.Command cmd = s.exec(command);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread errReader = new Thread(() -> copy(cmd.getErrorStream(), err), "ssh-remote-stderr");
            errReader.setDaemon(true);
            errReader.start();
            byte[] out = cmd.getInputStream().readAllBytes();
            cmd.join(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try {
                errReader.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String stderr;
            synchronized (err) {
                stderr = err.toString(StandardCharsets.UTF_8);
            }
            return new Exec(cmd.getExitStatus(), new String(out, StandardCharsets.UTF_8), stderr);
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[1024];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (out) {
                    out.write(buf, 0, n);
                }
            }
        } catch (IOException ignored) {
            // the channel closed
        }
    }

    /** Disconnects at once; safe from any thread, and more than once. */
    @Override
    public void close() {
        closed = true;
        SSHClient c = client;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
                // already gone
            }
        }
    }

    private Failure closedFailure(Step step) {
        return new Failure(step, Reason.OTHER, "Cancelled", null);
    }

    private Failure connectFailure(IOException e) {
        Reason reason;
        if (e instanceof UnknownHostException) reason = Reason.UNKNOWN_HOST;
        else if (e instanceof ConnectException || e instanceof NoRouteToHostException) reason = Reason.REFUSED;
        else if (timedOut(e)) reason = Reason.TIMED_OUT;
        else reason = Reason.OTHER;
        return new Failure(Step.CONNECT, reason, message(e), e);
    }

    private static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof java.util.concurrent.TimeoutException) return true;
            if (t.getMessage() != null && t.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timeout")) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static String message(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t && (t.getMessage() == null || t.getMessage().isBlank())) {
            t = t.getCause();
        }
        return t.getMessage() != null && !t.getMessage().isBlank() ? t.getMessage() : t.getClass().getSimpleName();
    }

    // ── Shared with SshLogSource ──────────────────────────────────────────────

    /**
     * A client set up as every SSH connection here is: keepalives every
     * {@link SshLogSource#KEEP_ALIVE_SECONDS} and every host key trusted. Not yet connected.
     */
    static SSHClient newClient() {
        DefaultConfig sshConfig = new DefaultConfig();
        sshConfig.setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE);
        SSHClient client = new SSHClient(sshConfig);
        // Set before connect: sshj starts the keepalive thread on connect when it's enabled.
        client.getConnection().getKeepAlive().setKeepAliveInterval(SshLogSource.KEEP_ALIVE_SECONDS);
        client.addHostKeyVerifier(new PromiscuousVerifier());
        return client;
    }

    /**
     * Signs in with the key file, the password, or "No password" ({@link #noPasswordMethods}).
     * The key is loaded first, so a key file that can't be used fails with
     * {@link Reason#KEY_FILE} before the server is asked.
     *
     * @throws Failure ({@link Reason#KEY_FILE}) if the key file can't be read or decrypted
     * @throws UserAuthException if the server turns the sign-in down
     */
    static void authenticate(SSHClient client, SshConfig config) throws IOException {
        if (config.keyFilePath() != null && !config.keyFilePath().isBlank()) {
            String keyFile = config.keyFilePath();
            if (!Files.isReadable(Path.of(keyFile))) {
                throw new Failure(Step.SIGN_IN, Reason.KEY_FILE, "Can't read " + keyFile, null);
            }
            String passphrase = config.keyPassphrase();
            KeyProvider keys;
            try {
                keys = passphrase != null && !passphrase.isBlank()
                        ? client.loadKeys(keyFile, passphrase.toCharArray())
                        : client.loadKeys(keyFile);
                keys.getPrivate();   // decrypts now, so a wrong passphrase shows here
            } catch (IOException | RuntimeException e) {
                throw new Failure(Step.SIGN_IN, Reason.KEY_FILE, message(e), e);
            }
            client.authPublickey(config.user(), keys);
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
}
