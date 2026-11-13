package com.droidenx.clouseau.core;

/**
 * Immutable SSH connection parameters.
 * Password auth sets {@code password}, key auth sets {@code keyFilePath}, and "No password"
 * leaves both null (see {@code SshRemote.noPasswordMethods}).
 */
public record SshConfig(
        String host,
        int    port,
        String user,
        String password,      // null when using key auth
        String keyFilePath,   // null when using password auth
        String keyPassphrase, // null or blank if key has no passphrase
        String remotePath
) {
    public static final int DEFAULT_PORT = 22;

    public String displayName() {
        return user + "@" + host + ":" + remotePath;
    }

    /**
     * The remote file's name without its folder, such as {@code app.log} for
     * {@code /var/log/app.log}. Trailing slashes are ignored; a path with no name part
     * ({@code /}, {@code ~}) is returned whole.
     */
    public String fileName() {
        String path = remotePath == null ? "" : remotePath.strip();
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') end--;
        String trimmed = path.substring(0, end);
        String name = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return name.isEmpty() ? trimmed : name;
    }
}
