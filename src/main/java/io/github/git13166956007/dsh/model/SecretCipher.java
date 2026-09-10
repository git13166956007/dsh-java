package io.github.git13166956007.dsh.model;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class SecretCipher {
    private static final String PREFIX = "enc:v1:";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    SecretCipher(String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            key = null;
        } else {
            try {
                key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                        .digest(masterKey.getBytes(StandardCharsets.UTF_8)), "AES");
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("failed to initialize secret cipher", exception);
            }
        }
    }

    String encrypt(String value) {
        if (value == null || value.isBlank() || key == null || value.startsWith(PREFIX)) return value;
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("failed to encrypt secret", exception);
        }
    }

    String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX)) return value;
        if (key == null) throw new IllegalStateException("DSH_SECRET_KEY is required to read encrypted model keys");
        try {
            byte[] packed = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (packed.length <= 12) throw new IllegalArgumentException("invalid encrypted secret");
            byte[] nonce = java.util.Arrays.copyOfRange(packed, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, 12, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("failed to decrypt model key", exception);
        }
    }
}
