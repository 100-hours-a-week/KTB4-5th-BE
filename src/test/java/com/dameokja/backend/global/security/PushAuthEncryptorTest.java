package com.dameokja.backend.global.security;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushAuthEncryptorTest {
    private static final String KEY = Base64.getEncoder().encodeToString(
            "test-only-secret-32-bytes-long!!".getBytes());
    private static final String KEY_VERSION = "v1";
    private final PushAuthEncryptor encryptor = new PushAuthEncryptor(KEY, KEY_VERSION);

    @Test
    void roundTripsPlainText() {
        byte[] encrypted = encryptor.encrypt("BASE64URL_AUTH_SECRET");
        assertThat(encryptor.decrypt(encrypted)).isEqualTo("BASE64URL_AUTH_SECRET");
    }

    @Test
    void exposesCurrentKeyVersion() {
        assertThat(encryptor.getCurrentKeyVersion()).isEqualTo(KEY_VERSION);
    }

    @Test
    void producesDifferentCiphertextEachCall() {
        Set<String> ciphertexts = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            ciphertexts.add(Base64.getEncoder().encodeToString(encryptor.encrypt("same-secret")));
        }
        assertThat(ciphertexts).hasSize(5);
    }

    @Test
    void rejectsTamperedCiphertext() {
        byte[] encrypted = encryptor.encrypt("BASE64URL_AUTH_SECRET");
        byte[] tampered = Arrays.copyOf(encrypted, encrypted.length);
        tampered[tampered.length - 1] ^= 0x01;
        assertThatThrownBy(() -> encryptor.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsKeyWithWrongLength() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());
        assertThatThrownBy(() -> new PushAuthEncryptor(shortKey, KEY_VERSION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankKeyVersion() {
        assertThatThrownBy(() -> new PushAuthEncryptor(KEY, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
