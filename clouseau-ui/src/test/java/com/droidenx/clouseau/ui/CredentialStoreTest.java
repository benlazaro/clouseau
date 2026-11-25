package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialStoreTest {

    @TempDir Path tmp;

    @Test
    void aPasswordComesBackAsSaved() throws Exception {
        SecretKey key = CredentialStore.loadOrCreateKey(tmp.resolve("keystore.p12"), "here".toCharArray());

        String stored = CredentialStore.encrypt("s3cret", key);

        assertThat(stored).startsWith(CredentialStore.ENC_PREFIX).doesNotContain("s3cret");
        assertThat(CredentialStore.decrypt(stored, key)).isEqualTo("s3cret");
    }

    @Test
    void theSameKeystoreOpensAgain() throws Exception {
        Path keystore = tmp.resolve("keystore.p12");
        SecretKey first = CredentialStore.loadOrCreateKey(keystore, "here".toCharArray());

        SecretKey again = CredentialStore.loadOrCreateKey(keystore, "here".toCharArray());

        assertThat(again.getEncoded()).isEqualTo(first.getEncoded());
    }

    @Test
    void anotherComputersKeystoreIsMovedAsideAndItsPasswordsReadAsNotSaved() throws Exception {
        Path keystore = tmp.resolve("keystore.p12");
        SecretKey there = CredentialStore.loadOrCreateKey(keystore, "there".toCharArray());
        String savedThere = CredentialStore.encrypt("s3cret", there);

        SecretKey here = CredentialStore.loadOrCreateKey(keystore, "here".toCharArray());

        assertThat(tmp.resolve("keystore.p12.bad")).exists();
        assertThat(keystore).exists();
        assertThat(CredentialStore.decrypt(savedThere, here)).isNull();
        // and a password saved here works from now on
        assertThat(CredentialStore.decrypt(CredentialStore.encrypt("new", here), here)).isEqualTo("new");
    }

    @Test
    void aDamagedKeystoreIsMovedAside() throws Exception {
        Path keystore = tmp.resolve("keystore.p12");
        Files.writeString(keystore, "not a keystore");

        SecretKey key = CredentialStore.loadOrCreateKey(keystore, "here".toCharArray());

        assertThat(key).isNotNull();
        assertThat(tmp.resolve("keystore.p12.bad")).hasContent("not a keystore");
    }

    @Test
    void withNoKeyNothingIsSavedInPlainText() {
        assertThat(CredentialStore.encrypt("s3cret", null)).isNull();
        assertThat(CredentialStore.decrypt("enc:AAAA", null)).isNull();
    }

    @Test
    void damagedOrLegacyValues() throws Exception {
        SecretKey key = CredentialStore.loadOrCreateKey(tmp.resolve("keystore.p12"), "here".toCharArray());

        assertThat(CredentialStore.decrypt("enc:not base64!", key)).isNull();
        assertThat(CredentialStore.decrypt("plain-from-before-encryption", key)).isEqualTo("plain-from-before-encryption");
        assertThat(CredentialStore.decrypt(null, key)).isNull();
        assertThat(CredentialStore.encrypt(null, key)).isNull();
    }
}
