package com.foggy.navigator.auth.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiKeyGenerator 单元测试 — L1
 */
class ApiKeyGeneratorTest {

    private final ApiKeyGenerator generator = new ApiKeyGenerator();

    // ---- generate ----

    @Test
    void generate_startsWithPrefix() {
        String key = generator.generate();
        assertTrue(key.startsWith("sk-"));
    }

    @Test
    void generate_hasReasonableLength() {
        String key = generator.generate();
        // sk- (3) + Base64URL(32 bytes) = 3 + 43 = 46
        assertTrue(key.length() >= 40, "Key too short: " + key);
    }

    @Test
    void generate_uniqueKeys() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            keys.add(generator.generate());
        }
        assertEquals(100, keys.size(), "Should generate unique keys");
    }

    @Test
    void generate_base64UrlSafe() {
        String key = generator.generate();
        String body = key.substring(3); // 去掉 sk-
        // Base64URL 字符集: A-Z a-z 0-9 - _
        assertTrue(body.matches("[A-Za-z0-9_-]+"));
    }

    // ---- mask ----

    @Test
    void mask_normalKey() {
        String key = "sk-abcdefghijklmnop1234";
        String masked = generator.mask(key);
        assertEquals("sk-***1234", masked);
    }

    @Test
    void mask_preservesPrefixAndLast4() {
        String key = generator.generate();
        String masked = generator.mask(key);
        assertTrue(masked.startsWith("sk-***"));
        assertEquals(key.substring(key.length() - 4), masked.substring(masked.length() - 4));
    }

    @Test
    void mask_nullKey() {
        assertEquals("***", generator.mask(null));
    }

    @Test
    void mask_tooShortKey() {
        assertEquals("***", generator.mask("sk-abc"));
        assertEquals("***", generator.mask("1234567"));
    }

    @Test
    void mask_exactlyLength8() {
        String masked = generator.mask("sk-abcde");
        assertEquals("sk-***bcde", masked);
    }
}
