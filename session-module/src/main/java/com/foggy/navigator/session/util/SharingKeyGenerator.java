package com.foggy.navigator.session.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 共享密钥生成器 — 生成 "shk-" 前缀的安全随机密钥
 */
@Component
public class SharingKeyGenerator {

    private static final String PREFIX = "shk-";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 生成共享密钥
     * 格式: "shk-" + Base64URL(32 random bytes)
     */
    public String generate() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return PREFIX + encoded;
    }

    /**
     * 掩码显示共享密钥
     * 格式: "shk-***<last4chars>"
     */
    public String mask(String sharingKey) {
        if (sharingKey == null || sharingKey.length() < 8) {
            return "***";
        }
        String prefix = sharingKey.substring(0, 4); // "shk-"
        String suffix = sharingKey.substring(sharingKey.length() - 4);
        return prefix + "***" + suffix;
    }
}
