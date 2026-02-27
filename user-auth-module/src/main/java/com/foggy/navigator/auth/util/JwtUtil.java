package com.foggy.navigator.auth.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret:foggy-navigator-secret-key-change-in-production-please}")
    private String secret;

    @Value("${jwt.expiration:86400}") // 默认24小时
    private Long expiration;

    /**
     * 生成Token
     */
    public String generateToken(String userId, String username, String tenantId, String roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("tenantId", tenantId);
        claims.put("roles", roles);

        return Jwts.builder()
                .claims(claims)
                .subject(userId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration * 1000))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 从Token中获取用户ID
     */
    public String getUserIdFromToken(String token) {
        return getClaims(token).getSubject();
    }

    /**
     * 从Token中获取用户名
     */
    public String getUsernameFromToken(String token) {
        return getClaims(token).get("username", String.class);
    }

    /**
     * 从Token中获取租户ID
     */
    public String getTenantIdFromToken(String token) {
        return getClaims(token).get("tenantId", String.class);
    }

    /**
     * 从Token中获取角色
     */
    public String getRolesFromToken(String token) {
        return getClaims(token).get("roles", String.class);
    }

    /**
     * 验证Token是否有效
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = getClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取Token的过期时间
     */
    public Date getExpirationFromToken(String token) {
        return getClaims(token).getExpiration();
    }

    /**
     * 判断Token是否需要续期（剩余有效期不足总时长的一半）
     */
    public boolean needsRenewal(String token) {
        try {
            Date exp = getExpirationFromToken(token);
            long remaining = exp.getTime() - System.currentTimeMillis();
            return remaining > 0 && remaining < (expiration * 1000 / 2);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 用原有 claims 重新签发一个新 Token（续期）
     */
    public String renewToken(String token) {
        Claims claims = getClaims(token);
        return generateToken(
                claims.getSubject(),
                claims.get("username", String.class),
                claims.get("tenantId", String.class),
                claims.get("roles", String.class)
        );
    }

    /**
     * 解析Token
     */
    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 获取签名密钥
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
