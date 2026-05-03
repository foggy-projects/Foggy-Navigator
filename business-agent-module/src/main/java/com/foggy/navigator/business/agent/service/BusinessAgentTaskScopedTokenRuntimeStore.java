package com.foggy.navigator.business.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory runtime store for plain task-scoped tokens.
 * The DB only stores hashed tokens; this store keeps the plain token accessible
 * to the Java runtime for injection into tool execution contexts.
 */
@Slf4j
@Component
public class BusinessAgentTaskScopedTokenRuntimeStore {

    private final Map<String, TokenEntry> store = new ConcurrentHashMap<>();

    /**
     * Register a new task-scoped token.
     * @param tenantId The tenant ID.
     * @param sessionId The session ID.
     * @param taskId The task ID (optional, can be null).
     * @param plainToken The generated plain token.
     * @param expiresAt The explicit expiration time matching the DB record.
     */
    public void registerToken(String tenantId, String sessionId, String taskId, String plainToken, LocalDateTime expiresAt) {
        if (tenantId == null || sessionId == null || plainToken == null || expiresAt == null) {
            log.warn("Cannot register token with null keys/values: tenantId={}, sessionId={}, expiresAt={}", tenantId, sessionId, expiresAt);
            return;
        }

        long expiresAtMillis = expiresAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        TokenEntry entry = new TokenEntry(plainToken, expiresAtMillis);

        // Register under session scope
        String sessionKey = generateSessionKey(tenantId, sessionId);
        store.put(sessionKey, entry);

        // Register under exact task scope if available
        if (taskId != null && !taskId.isBlank()) {
            String taskKey = generateTaskKey(tenantId, sessionId, taskId);
            store.put(taskKey, entry);
        }

        log.debug("Registered task-scoped token in runtime store for tenantId={}, sessionId={}, taskId={}", tenantId, sessionId, taskId);

        // simple background cleanup could be added, or done on access
        cleanupExpired();
    }

    /**
     * Retrieve a plain token. Returns null if missing or expired.
     * @param tenantId The tenant ID.
     * @param sessionId The session ID.
     * @param taskId The task ID (optional).
     */
    public String getToken(String tenantId, String sessionId, String taskId) {
        if (tenantId == null || sessionId == null) {
            return null;
        }

        TokenEntry entry = null;
        String key = null;

        // Prefer exact task match
        if (taskId != null && !taskId.isBlank()) {
            key = generateTaskKey(tenantId, sessionId, taskId);
            entry = store.get(key);
            // DO NOT fallback to session match if a specific taskId was requested
        } else {
            // Fallback to session match only if no taskId was provided
            key = generateSessionKey(tenantId, sessionId);
            entry = store.get(key);
        }

        if (entry == null) {
            return null;
        }

        if (System.currentTimeMillis() > entry.expiresAt) {
            store.remove(key);
            log.debug("Token expired in runtime store for key={}", key);
            return null;
        }

        return entry.plainToken;
    }

    private String generateSessionKey(String tenantId, String sessionId) {
        return tenantId + ":" + sessionId;
    }

    private String generateTaskKey(String tenantId, String sessionId, String taskId) {
        return tenantId + ":" + sessionId + ":" + taskId;
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(entry -> now > entry.getValue().expiresAt);
    }

    private record TokenEntry(String plainToken, long expiresAt) {}
}
