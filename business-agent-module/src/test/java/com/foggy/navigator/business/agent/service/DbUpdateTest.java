package com.foggy.navigator.business.agent.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

public class DbUpdateTest {
    @Test
    public void testEncrypt() {
        TextEncryptor encryptor = Encryptors.text("default-dev-key-change-in-prod", "abcdef0123456789");
        String encrypted = encryptor.encrypt("sk-ali-only");
        System.out.println("ENCRYPTED_API_KEY_START:" + encrypted + ":ENCRYPTED_API_KEY_END");
        
        String decrypted = encryptor.decrypt(encrypted);
        System.out.println("DECRYPTED:" + decrypted);
    }
}
