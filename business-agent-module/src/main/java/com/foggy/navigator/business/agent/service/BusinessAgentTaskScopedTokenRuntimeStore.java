package com.foggy.navigator.business.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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

    private final Map<TokenKey, TokenEntry> store = new ConcurrentHashMap<>();

    /**
     * Register a new task-scoped token.
     * @param tenantId The tenant ID.
     * @param sessionId The session ID.
     * @param taskId The required task ID.
     * @param plainToken The generated plain token.
     * @param expiresAt The explicit expiration time matching the DB record.
     */
    public void registerToken(String tenantId, String sessionId, String taskId, String plainToken, LocalDateTime expiresAt) {
        if (tenantId == null || sessionId == null || taskId == null || taskId.isBlank() ||
                plainToken == null || expiresAt == null) {
            log.warn("Cannot register task-scoped token with incomplete keys/values: " +
                            "tenantId={}, sessionId={}, taskId={}, expiresAt={}",
                    tenantId, sessionId, taskId, expiresAt);
            return;
        }

        TokenEntry entry = new TokenEntry(plainToken, expiresAt);

        TokenKey taskKey = new TokenKey(tenantId, sessionId, taskId);
        store.put(taskKey, entry);

        log.debug("Registered task-scoped token in runtime store for tenantId={}, sessionId={}, taskId={}", tenantId, sessionId, taskId);

        // simple background cleanup could be added, or done on access
        cleanupExpired();
    }

    /**
     * Retrieve a plain token. Returns null if missing or expired.
     * @param tenantId The tenant ID.
     * @param sessionId The session ID.
     * @param taskId The required task ID. Missing task identity fails closed.
     */
    public String getToken(String tenantId, String sessionId, String taskId) {
        if (tenantId == null || sessionId == null || taskId == null || taskId.isBlank()) {
            return null;
        }

        TokenKey key = new TokenKey(tenantId, sessionId, taskId);
        TokenEntry entry = store.get(key);

        if (entry == null) {
            return null;
        }

        if (!entry.expiresAt.isAfter(LocalDateTime.now())) {
            store.remove(key);
            log.debug("Token expired in runtime store for key={}", key);
            return null;
        }

        return entry.plainToken;
    }

    /**
     * Remove the exact task alias only when it still points to the
     * token represented by {@code tokenHash}. This prevents revoking an older
     * token from accidentally deleting a newer task alias.
     */
    public void removeTokenIfMatches(String tenantId, String sessionId, String taskId, String tokenHash) {
        if (tenantId == null || sessionId == null || taskId == null || taskId.isBlank() || tokenHash == null) {
            return;
        }
        removeIfHashMatches(new TokenKey(tenantId, sessionId, taskId), tokenHash);
    }

    private void removeIfHashMatches(TokenKey key, String tokenHash) {
        store.computeIfPresent(key, (ignored, entry) ->
                tokenHash.equals(SecretTokenSupport.sha256(entry.plainToken)) ? null : entry);
    }

    private void cleanupExpired() {
        LocalDateTime now = LocalDateTime.now();
        store.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
    }

    private record TokenKey(String tenantId, String sessionId, String taskId) {}

    private record TokenEntry(String plainToken, LocalDateTime expiresAt) {}
}
