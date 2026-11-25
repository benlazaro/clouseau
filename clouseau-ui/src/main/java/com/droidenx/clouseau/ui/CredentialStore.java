package com.droidenx.clouseau.ui;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Encrypts and decrypts credential strings using AES-256-GCM.
 *
 * <p>The AES key is stored in a PKCS12 keystore at {@code <data>/keystore.p12}.
 * The keystore password is derived from a stable machine ID ({@code <data>/machine-id})
 * combined with the OS username and home directory, so the keystore is tied to this
 * user/machine and cannot be moved elsewhere to extract credentials.
 *
 * <p>A portable data folder (D-31) started on another computer or account can't open the
 * keystore: it is moved aside to {@code keystore.p12.bad} and a new one is made, so the
 * passwords saved elsewhere read as missing ({@code null}) and the user enters them again.
 * Nothing is ever stored in plain text: with no key, {@link #encrypt} returns {@code null}
 * and the password isn't saved.
 *
 * <p>Encrypted values are prefixed with {@value #ENC_PREFIX} so that legacy plaintext
 * values stored before this feature was introduced are returned as-is on read.
 */
@Slf4j
final class CredentialStore {

    static final String ENC_PREFIX = "enc:";

    private static final String KEYSTORE_TYPE    = "PKCS12";
    private static final String KEY_ALIAS        = "clouseau-cred-key";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LENGTH    = 12;  // bytes
    private static final int    GCM_TAG_LENGTH   = 128; // bits
    private static final int    KEY_SIZE         = 256; // bits

    private static final Path KEYSTORE_PATH   = AppPrefs.PREFS_DIR.resolve("keystore.p12");
    private static final Path MACHINE_ID_PATH = AppPrefs.PREFS_DIR.resolve("machine-id");

    private static final SecretKey SECRET_KEY = loadOrCreateKey();

    private CredentialStore() {}

    /**
     * Encrypts {@code plaintext} and returns an {@value #ENC_PREFIX}-prefixed Base64 string.
     * Returns {@code null} if input is {@code null}, or if it can't be encrypted: a password is
     * then not saved, never saved in plain text.
     */
    static String encrypt(String plaintext) {
        return encrypt(plaintext, SECRET_KEY);
    }

    /**
     * Decrypts a value produced by {@link #encrypt}. If the value does not start with
     * {@value #ENC_PREFIX} it is treated as legacy plaintext and returned unchanged.
     * Returns {@code null} if input is {@code null}, or if it can't be decrypted (saved on
     * another computer or account): the password reads as never saved.
     */
    static String decrypt(String stored) {
        return decrypt(stored, SECRET_KEY);
    }

    static String encrypt(String plaintext, SecretKey key) {
        if (plaintext == null) return null;
        if (key == null) {
            log.warn("No credential key; a password is not saved");
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // Prepend IV so decrypt can extract it without separate storage
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.warn("Failed to encrypt credential; it is not saved", e);
            return null;
        }
    }

    static String decrypt(String stored, SecretKey key) {
        if (stored == null) return null;
        if (!stored.startsWith(ENC_PREFIX)) return stored; // legacy plaintext — pass through
        if (key == null) return null;
        try {
            byte[] combined  = Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length()));
            byte[] iv         = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0,             iv,         0, iv.length);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("A saved password can't be decrypted here (saved on another computer or account?); it reads as not saved");
            return null;
        }
    }

    // ── Key management ────────────────────────────────────────────────────────

    private static SecretKey loadOrCreateKey() {
        try {
            Files.createDirectories(AppPrefs.PREFS_DIR);
            return loadOrCreateKey(KEYSTORE_PATH, deriveKeystorePassword());
        } catch (Exception e) {
            log.error("Could not initialise credential store; passwords will not be saved", e);
            return null;
        }
    }

    /**
     * The key in {@code keystore}, opened with {@code password}, or a new one saved there. A
     * keystore this password can't open (made on another computer or account) is moved aside
     * to {@code <name>.bad}, replacing an older one, and a new keystore takes its place.
     */
    static SecretKey loadOrCreateKey(Path keystore, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        if (Files.exists(keystore)) {
            try (InputStream is = Files.newInputStream(keystore)) {
                ks.load(is, password);
                KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry)
                        ks.getEntry(KEY_ALIAS, new KeyStore.PasswordProtection(password));
                if (entry != null) return entry.getSecretKey();
            } catch (IOException | java.security.GeneralSecurityException e) {
                Path bad = keystore.resolveSibling(keystore.getFileName() + ".bad");
                log.warn("Can't open {} (made on another computer or account?); moved to {}, saved passwords must be entered again",
                        keystore, bad.getFileName());
                Files.move(keystore, bad, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // No key yet — generate one and persist it
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(KEY_SIZE, new SecureRandom());
        SecretKey key = kg.generateKey();

        ks.load(null, password);
        ks.setEntry(KEY_ALIAS,
                new KeyStore.SecretKeyEntry(key),
                new KeyStore.PasswordProtection(password));
        try (OutputStream os = Files.newOutputStream(keystore)) {
            ks.store(os, password);
        }
        return key;
    }

    /**
     * Derives a keystore password from stable, machine-specific properties.
     * Uses a persistent machine ID rather than hostname so the password survives
     * machine renames, DHCP changes, etc.
     */
    private static char[] deriveKeystorePassword() throws Exception {
        String machineId = loadOrCreateMachineId();
        String user      = System.getProperty("user.name", "");
        String home      = System.getProperty("user.home", "");
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest((user + ":" + home + ":" + machineId).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash).toCharArray();
    }

    private static String loadOrCreateMachineId() throws IOException {
        if (Files.exists(MACHINE_ID_PATH)) {
            return Files.readString(MACHINE_ID_PATH, StandardCharsets.UTF_8).strip();
        }
        String id = UUID.randomUUID().toString();
        Files.writeString(MACHINE_ID_PATH, id, StandardCharsets.UTF_8);
        return id;
    }
}
