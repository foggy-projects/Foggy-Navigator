package com.foggy.navigator.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * 凭证加密器 - 使用 AES-256 GCM 对敏感信息进行可逆加密
 * 公共安全组件，供所有模块使用
 */
@Slf4j
@Component
public class CredentialEncryptor {

    private final TextEncryptor textEncryptor;

    public CredentialEncryptor(
            @Value("${navigator.security.credential-key:default-dev-key-change-in-prod}") String key,
            @Value("${navigator.security.credential-salt:abcdef0123456789}") String salt) {
        this.textEncryptor = Encryptors.text(key, salt);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        return textEncryptor.encrypt(plaintext);
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        try {
            return textEncryptor.decrypt(ciphertext);
        } catch (Exception e) {
            log.warn("Failed to decrypt credential, returning original value (may be plaintext from before encryption was enabled)");
            return ciphertext;
        }
    }
}
