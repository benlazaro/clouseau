package com.droidenx.clouseau.core;

/**
 * Immutable SSH connection parameters.
 * Either {@code password} or {@code keyFilePath} is non-null depending on auth type.
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
    public String displayName() {
        return user + "@" + host + ":" + remotePath;
    }
}
