package com.foggy.navigator.business.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class BusinessAgentTaskScopedTokenRuntimeStoreTest {

    private BusinessAgentTaskScopedTokenRuntimeStore store;

    @BeforeEach
    void setUp() {
        store = new BusinessAgentTaskScopedTokenRuntimeStore();
    }

    @Test
    void registerWithoutTaskId_failsClosed() {
        store.registerToken("tenant1", "session1", null, "token123", LocalDateTime.now().plusHours(1));

        String token = store.getToken("tenant1", "session1", null);
        assertNull(token);
    }

    @Test
    void registerAndGet_success_taskMatch() {
        store.registerToken("tenant1", "session1", "task1", "token123", LocalDateTime.now().plusHours(1));

        // Exact task match
        assertEquals("token123", store.getToken("tenant1", "session1", "task1"));

        // Missing task identity must never fall back to session scope.
        assertNull(store.getToken("tenant1", "session1", null));
    }

    @Test
    void registerAndGet_taskMismatch_returnsNull() {
        store.registerToken("tenant1", "session1", "task1", "token123", LocalDateTime.now().plusHours(1));

        // Since we requested a specific task, it should NOT fallback to the session key.
        // This ensures exact task binding and prevents cross-task contamination.
        assertNull(store.getToken("tenant1", "session1", "task2"));
    }

    @Test
    void structuredKey_preventsColonDelimitedIdentityCollision() {
        store.registerToken(
                "tenant1", "session:part", "task1", "token-a", LocalDateTime.now().plusHours(1));
        store.registerToken(
                "tenant1:session", "part", "task1", "token-b", LocalDateTime.now().plusHours(1));

        assertEquals("token-a", store.getToken("tenant1", "session:part", "task1"));
        assertEquals("token-b", store.getToken("tenant1:session", "part", "task1"));
    }

    @Test
    void get_tenantMismatch_returnsNull() {
        store.registerToken("tenant1", "session1", "task1", "token123", LocalDateTime.now().plusHours(1));

        String token = store.getToken("tenant2", "session1", "task1");
        assertNull(token);
    }

    @Test
    void get_sessionMismatch_returnsNull() {
        store.registerToken("tenant1", "session1", "task1", "token123", LocalDateTime.now().plusHours(1));

        String token = store.getToken("tenant1", "session2", "task1");
        assertNull(token);
    }

    @Test
    void get_expired_returnsNull() {
        store.registerToken("tenant1", "session1", "task1", "token123", LocalDateTime.now().minusMinutes(1));

        String token = store.getToken("tenant1", "session1", "task1");
        assertNull(token);
    }

    @Test
    void get_missingKeys_returnsNull() {
        assertNull(store.getToken(null, "session1", null));
        assertNull(store.getToken("tenant1", null, null));
        assertNull(store.getToken("tenant1", "session1", null));
    }

    @Test
    void removeTokenIfMatches_removesMatchingSessionAndTaskAliases() {
        String plainToken = "token123";
        store.registerToken("tenant1", "session1", "task1", plainToken, LocalDateTime.now().plusHours(1));

        store.removeTokenIfMatches("tenant1", "session1", "task1", SecretTokenSupport.sha256(plainToken));

        assertNull(store.getToken("tenant1", "session1", "task1"));
    }

    @Test
    void removeTokenIfMatches_doesNotRemoveNewerTokenThatOverwroteAliases() {
        String oldToken = "old-token";
        String newToken = "new-token";
        store.registerToken("tenant1", "session1", "task1", oldToken, LocalDateTime.now().plusHours(1));
        store.registerToken("tenant1", "session1", "task1", newToken, LocalDateTime.now().plusHours(1));

        store.removeTokenIfMatches("tenant1", "session1", "task1", SecretTokenSupport.sha256(oldToken));

        assertEquals(newToken, store.getToken("tenant1", "session1", "task1"));
    }
}
