package com.foggy.navigator.auth.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 生成工具
 */
@Component
public class ApiKeyGenerator {

    private static final String PREFIX = "sk-";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 生成API Key
     */
    public String generate() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return PREFIX + encoded;
    }

    /**
     * 脱敏API Key（显示前缀和后4位）
     */
    public String mask(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "***";
        }
        String prefix = apiKey.substring(0, 3);  // sk-
        String suffix = apiKey.substring(apiKey.length() - 4);
        return prefix + "***" + suffix;
    }
}
