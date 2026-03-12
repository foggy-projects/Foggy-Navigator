package com.foggy.navigator.auth.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 单元测试 — L1（纯 Mockito，无 Spring 上下文）
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() throws Exception {
        jwtUtil = new JwtUtil();
        setField(jwtUtil, "secret", "test-secret-key-for-unit-testing-must-be-long-enough");
        setField(jwtUtil, "expiration", 3600L); // 1小时
    }

    // ---- generateToken + 读取 claims ----

    @Test
    void generateToken_containsAllClaims() {
        String token = jwtUtil.generateToken("user-1", "alice", "tenant-A", "DEVELOPER");
        assertNotNull(token);

        assertEquals("user-1", jwtUtil.getUserIdFromToken(token));
        assertEquals("alice", jwtUtil.getUsernameFromToken(token));
        assertEquals("tenant-A", jwtUtil.getTenantIdFromToken(token));
        assertEquals("DEVELOPER", jwtUtil.getRolesFromToken(token));
    }

    @Test
    void generateToken_subjectIsUserId() {
        String token = jwtUtil.generateToken("uid-42", "bob", null, "VIEWER");
        assertEquals("uid-42", jwtUtil.getUserIdFromToken(token));
    }

    @Test
    void generateToken_nullableFields() {
        // tenantId 和 roles 允许 null
        String token = jwtUtil.generateToken("u1", "charlie", null, null);
        assertNull(jwtUtil.getTenantIdFromToken(token));
        assertNull(jwtUtil.getRolesFromToken(token));
    }

    // ---- validateToken ----

    @Test
    void validateToken_validToken() {
        String token = jwtUtil.generateToken("u1", "alice", "t1", "ADMIN");
        assertTrue(jwtUtil.validateToken(token));
    }

    @Test
    void validateToken_tamperedTokenReturnsFalse() {
        String token = jwtUtil.generateToken("u1", "alice", "t1", "ADMIN");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertFalse(jwtUtil.validateToken(tampered));
    }

    @Test
    void validateToken_garbageStringReturnsFalse() {
        assertFalse(jwtUtil.validateToken("not-a-jwt"));
    }

    @Test
    void validateToken_emptyStringReturnsFalse() {
        assertFalse(jwtUtil.validateToken(""));
    }

    @Test
    void validateToken_expiredTokenReturnsFalse() throws Exception {
        // 设置极短过期时间
        setField(jwtUtil, "expiration", 0L);
        String token = jwtUtil.generateToken("u1", "alice", "t1", "ADMIN");
        // 0秒过期 → 立即过期
        assertFalse(jwtUtil.validateToken(token));
    }

    // ---- getExpirationFromToken ----

    @Test
    void getExpiration_futureDate() {
        String token = jwtUtil.generateToken("u1", "alice", "t1", "ADMIN");
        Date exp = jwtUtil.getExpirationFromToken(token);
        assertTrue(exp.after(new Date()));
    }

    // ---- needsRenewal ----

    @Test
    void needsRenewal_freshTokenReturnsFalse() {
        // 1小时过期，刚生成 → 剩余接近1小时 > 半小时阈值
        String token = jwtUtil.generateToken("u1", "alice", "t1", "ADMIN");
        assertFalse(jwtUtil.needsRenewal(token));
    }

    @Test
    void needsRenewal_nearExpiryReturnsTrue() throws Exception {
        // 先用正常时间生成，再把 expiration 设大使阈值变大
        setField(jwtUtil, "expiration", 3600L);
        String token = jwtUtil.generateToken("u1", "alice", "t1", "ADMIN");
        // 把 expiration 改成 7200 → 阈值变成 3600 → 当前剩余 ~3600 刚好在阈值边缘
        setField(jwtUtil, "expiration", 7200L);
        assertTrue(jwtUtil.needsRenewal(token));
    }

    @Test
    void needsRenewal_invalidTokenReturnsFalse() {
        assertFalse(jwtUtil.needsRenewal("garbage"));
    }

    // ---- renewToken ----

    @Test
    void renewToken_preservesClaims() {
        String original = jwtUtil.generateToken("u1", "alice", "tenant-X", "DEVELOPER");
        String renewed = jwtUtil.renewToken(original);

        assertNotNull(renewed);
        // 续期后 claims 应保持一致
        assertEquals("u1", jwtUtil.getUserIdFromToken(renewed));
        assertEquals("alice", jwtUtil.getUsernameFromToken(renewed));
        assertEquals("tenant-X", jwtUtil.getTenantIdFromToken(renewed));
        assertEquals("DEVELOPER", jwtUtil.getRolesFromToken(renewed));
        assertTrue(jwtUtil.validateToken(renewed));
    }

    @Test
    void renewToken_extendsExpiration() {
        String original = jwtUtil.generateToken("u1", "alice", "t1", "ADMIN");
        Date originalExp = jwtUtil.getExpirationFromToken(original);

        String renewed = jwtUtil.renewToken(original);
        Date renewedExp = jwtUtil.getExpirationFromToken(renewed);

        // 续期后的过期时间应 >= 原始过期时间
        assertTrue(renewedExp.getTime() >= originalExp.getTime());
    }

    // ---- 不同 secret 产生不同签名 ----

    @Test
    void differentSecret_cannotValidate() throws Exception {
        String token = jwtUtil.generateToken("u1", "alice", "t1", "ADMIN");

        JwtUtil otherJwt = new JwtUtil();
        setField(otherJwt, "secret", "completely-different-secret-key-must-be-long-enough");
        setField(otherJwt, "expiration", 3600L);

        assertFalse(otherJwt.validateToken(token));
    }

    // ---- 辅助 ----

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
