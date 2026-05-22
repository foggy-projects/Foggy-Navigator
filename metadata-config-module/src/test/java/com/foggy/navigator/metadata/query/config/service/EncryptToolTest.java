package com.foggy.navigator.metadata.query.config.service;

import com.foggy.navigator.common.security.CredentialEncryptor;
import org.junit.jupiter.api.Test;

public class EncryptToolTest {

    @Test
    public void encryptToken() {
        String key = System.getenv("NAVIGATOR_CREDENTIAL_KEY");
        if (key == null) {
            key = "default-dev-key-change-in-prod";
        }
        String salt = System.getenv("NAVIGATOR_CREDENTIAL_SALT");
        if (salt == null) {
            salt = "abcdef0123456789";
        }

        System.out.println("=== ENCRYPTION KEY INFO ===");
        System.out.println("Using Key: " + key);
        System.out.println("Using Salt: " + salt);

        CredentialEncryptor encryptor = new CredentialEncryptor(key, salt);
        String plaintext = "sk-ali-only";
        String encrypted = encryptor.encrypt(plaintext);
        System.out.println("=== RESULT ===");
        System.out.println("Encrypted Value for sk-ali-only: " + encrypted);
        System.out.println("Decrypted verify: " + encryptor.decrypt(encrypted));
        try {
            System.out.println("Decrypted existing key: " + encryptor.decrypt("b80a2106c0eecbfdf7ad66b047bf1c41a1b5036b54814cc68fd835fbef657668"));
        } catch (Exception e) {
            System.out.println("Decryption failed: " + e.getMessage());
        }
        System.out.println("==============");
    }
}
