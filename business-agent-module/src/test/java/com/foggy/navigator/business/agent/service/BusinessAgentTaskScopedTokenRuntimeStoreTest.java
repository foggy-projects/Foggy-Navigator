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
    void registerAndGet_success_sessionMatch() {
        store.registerToken("tenant1", "session1", null, "token123", LocalDateTime.now().plusHours(1));

        String token = store.getToken("tenant1", "session1", null);
        assertEquals("token123", token);
    }

    @Test
    void registerAndGet_success_taskMatch() {
        store.registerToken("tenant1", "session1", "task1", "token123", LocalDateTime.now().plusHours(1));

        // Exact task match
        assertEquals("token123", store.getToken("tenant1", "session1", "task1"));

        // Fallback to session match only when task is not provided
        assertEquals("token123", store.getToken("tenant1", "session1", null));
    }

    @Test
    void registerAndGet_taskMismatch_returnsNull() {
        store.registerToken("tenant1", "session1", "task1", "token123", LocalDateTime.now().plusHours(1));

        // Since we requested a specific task, it should NOT fallback to the session key.
        // This ensures exact task binding and prevents cross-task contamination.
        assertNull(store.getToken("tenant1", "session1", "task2"));
    }

    @Test
    void get_tenantMismatch_returnsNull() {
        store.registerToken("tenant1", "session1", null, "token123", LocalDateTime.now().plusHours(1));

        String token = store.getToken("tenant2", "session1", null);
        assertNull(token);
    }

    @Test
    void get_sessionMismatch_returnsNull() {
        store.registerToken("tenant1", "session1", null, "token123", LocalDateTime.now().plusHours(1));

        String token = store.getToken("tenant1", "session2", null);
        assertNull(token);
    }

    @Test
    void get_expired_returnsNull() {
        store.registerToken("tenant1", "session1", null, "token123", LocalDateTime.now().minusMinutes(1));

        String token = store.getToken("tenant1", "session1", null);
        assertNull(token);
    }

    @Test
    void get_missingKeys_returnsNull() {
        assertNull(store.getToken(null, "session1", null));
        assertNull(store.getToken("tenant1", null, null));
    }
}
