package com.foggy.navigator.session.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SharingKeyGenerator 单元测试 — L1
 */
class SharingKeyGeneratorTest {

    private final SharingKeyGenerator generator = new SharingKeyGenerator();

    // ---- generate ----

    @Test
    void generate_startsWithPrefix() {
        String key = generator.generate();
        assertTrue(key.startsWith("shk-"));
    }

    @Test
    void generate_hasExpectedLength() {
        String key = generator.generate();
        // "shk-" (4) + Base64URL(32 bytes) ≈ 4 + 43 = 47 chars
        assertTrue(key.length() >= 44, "Key too short: " + key);
        assertTrue(key.length() <= 50, "Key too long: " + key);
    }

    @Test
    void generate_base64UrlSafe() {
        String key = generator.generate();
        String encoded = key.substring(4); // strip "shk-"
        // Base64URL: alphanumeric + '-' + '_', no '+', '/', '='
        assertTrue(encoded.matches("[A-Za-z0-9_-]+"), "Not Base64URL safe: " + encoded);
        assertFalse(encoded.contains("+"));
        assertFalse(encoded.contains("/"));
        assertFalse(encoded.contains("="));
    }

    @Test
    void generate_uniqueEachCall() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            keys.add(generator.generate());
        }
        assertEquals(100, keys.size(), "Expected 100 unique keys");
    }

    // ---- mask ----

    @Test
    void mask_normalKey() {
        String key = "shk-A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8";
        String masked = generator.mask(key);
        // prefix (4) + *** + last 4
        assertTrue(masked.startsWith("shk-"));
        assertTrue(masked.contains("***"));
        assertTrue(masked.endsWith("Q7r8"));
        assertEquals("shk-***Q7r8", masked);
    }

    @Test
    void mask_null_returnsStars() {
        assertEquals("***", generator.mask(null));
    }

    @Test
    void mask_tooShort_returnsStars() {
        assertEquals("***", generator.mask("shk-ab")); // length 6 < 8
        assertEquals("***", generator.mask("short"));  // length 5 < 8
    }

    @Test
    void mask_exactBoundary_works() {
        // length exactly 8
        String key = "shk-abcd";
        String masked = generator.mask(key);
        assertEquals("shk-***abcd", masked);
    }

    @Test
    void mask_roundTrip() {
        String key = generator.generate();
        String masked = generator.mask(key);
        assertTrue(masked.startsWith("shk-"));
        assertTrue(masked.contains("***"));
        // Last 4 chars preserved
        String last4 = key.substring(key.length() - 4);
        assertTrue(masked.endsWith(last4));
    }
}
