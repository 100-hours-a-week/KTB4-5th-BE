package com.dameokja.backend.global.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PushAuthEncryptor {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec secretKey;
    private final String keyVersion;
    private final SecureRandom secureRandom = new SecureRandom();

    public PushAuthEncryptor(@Value("${push.auth-encryption.key}") String base64Key,
            @Value("${push.auth-encryption.key-version}") String keyVersion) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        validateKeyLength(keyBytes);
        validateKeyVersion(keyVersion);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        this.keyVersion = keyVersion;
    }

    private void validateKeyLength(byte[] keyBytes) {
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("암호화 키는 Base64로 인코딩된 256비트(32바이트) 값이어야 합니다.");
        }
    }

    private void validateKeyVersion(String keyVersion) {
        if (keyVersion == null || keyVersion.isBlank()) {
            throw new IllegalArgumentException("암호화 키 버전은 비어 있을 수 없습니다.");
        }
    }

    public String getCurrentKeyVersion() {
        return keyVersion;
    }

    // 반환값 구조: nonce(12바이트) + 암호문 + 인증 태그(16바이트)를 하나의 BLOB로 이어 붙인다.
    public byte[] encrypt(String plainText) {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        byte[] cipherTextWithTag = process(Cipher.ENCRYPT_MODE, iv,
                plainText.getBytes(StandardCharsets.UTF_8));
        byte[] result = new byte[IV_LENGTH_BYTES + cipherTextWithTag.length];
        System.arraycopy(iv, 0, result, 0, IV_LENGTH_BYTES);
        System.arraycopy(cipherTextWithTag, 0, result, IV_LENGTH_BYTES, cipherTextWithTag.length);
        return result;
    }

    public String decrypt(byte[] encrypted) {
        byte[] iv = Arrays.copyOfRange(encrypted, 0, IV_LENGTH_BYTES);
        byte[] cipherTextWithTag = Arrays.copyOfRange(encrypted, IV_LENGTH_BYTES, encrypted.length);
        byte[] plainBytes = process(Cipher.DECRYPT_MODE, iv, cipherTextWithTag);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    private byte[] process(int mode, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(mode, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("구독 인증 비밀값 암복호화에 실패했습니다.", exception);
        }
    }
}
