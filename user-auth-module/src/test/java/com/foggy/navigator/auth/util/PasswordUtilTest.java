package com.foggy.navigator.auth.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PasswordUtil 单元测试 — L1
 */
class PasswordUtilTest {

    private final PasswordUtil passwordUtil = new PasswordUtil();

    @Test
    void encode_producesNonNullHash() {
        String hash = passwordUtil.encode("secret");
        assertNotNull(hash);
        assertFalse(hash.isEmpty());
    }

    @Test
    void encode_differentFromRawPassword() {
        String hash = passwordUtil.encode("mypassword");
        assertNotEquals("mypassword", hash);
    }

    @Test
    void encode_sameInputProducesDifferentHashes() {
        // BCrypt 每次生成不同 salt
        String h1 = passwordUtil.encode("same");
        String h2 = passwordUtil.encode("same");
        assertNotEquals(h1, h2);
    }

    @Test
    void matches_correctPassword() {
        String hash = passwordUtil.encode("correct");
        assertTrue(passwordUtil.matches("correct", hash));
    }

    @Test
    void matches_wrongPassword() {
        String hash = passwordUtil.encode("correct");
        assertFalse(passwordUtil.matches("wrong", hash));
    }

    @Test
    void matches_emptyPassword() {
        String hash = passwordUtil.encode("");
        assertTrue(passwordUtil.matches("", hash));
        assertFalse(passwordUtil.matches("something", hash));
    }

    @Test
    void matches_longPassword() {
        String longPwd = "a".repeat(200);
        String hash = passwordUtil.encode(longPwd);
        // BCrypt 内部截断到 72 字节，但 matches 应一致
        assertTrue(passwordUtil.matches(longPwd, hash));
    }
}
