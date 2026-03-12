package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.session.repository.AgentConversationContextRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AgentContextStoreImpl 单元测试 — L1
 */
@ExtendWith(MockitoExtension.class)
class AgentContextStoreImplTest {

    @Mock private AgentConversationContextRepository repository;

    @InjectMocks private AgentContextStoreImpl store;

    // ---- findSessionRef ----

    @Test
    void findSessionRef_found_withinTTL() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setAgentSessionRef("remote-session-abc");
        entity.setLastAccessedAt(LocalDateTime.now().minusHours(1)); // 1 hour ago

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        Optional<String> result = store.findSessionRef("ctx-1", "u1", 24); // 24h TTL
        assertTrue(result.isPresent());
        assertEquals("remote-session-abc", result.get());
    }

    @Test
    void findSessionRef_expired_beyondTTL() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setAgentSessionRef("old-session");
        entity.setLastAccessedAt(LocalDateTime.now().minusHours(25)); // 25 hours ago

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        Optional<String> result = store.findSessionRef("ctx-1", "u1", 24); // 24h TTL
        assertTrue(result.isEmpty());
    }

    @Test
    void findSessionRef_nullAgentSessionRef_filteredOut() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setAgentSessionRef(null); // no session ref saved yet
        entity.setLastAccessedAt(LocalDateTime.now());

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        Optional<String> result = store.findSessionRef("ctx-1", "u1", 24);
        assertTrue(result.isEmpty());
    }

    @Test
    void findSessionRef_notFound_returnsEmpty() {
        when(repository.findByContextIdAndUserId("ctx-unknown", "u1"))
                .thenReturn(Optional.empty());

        Optional<String> result = store.findSessionRef("ctx-unknown", "u1", 24);
        assertTrue(result.isEmpty());
    }

    @Test
    void findSessionRef_exactTTLBoundary_shouldExpire() {
        // At exactly the TTL boundary, the entry should still be valid
        // (isAfter is strict, so exactly at boundary is expired)
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setAgentSessionRef("session-ref");
        entity.setLastAccessedAt(LocalDateTime.now().minusHours(24).minusSeconds(1));

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        Optional<String> result = store.findSessionRef("ctx-1", "u1", 24);
        assertTrue(result.isEmpty()); // just past boundary
    }

    // ---- saveSessionRef ----

    @Test
    void saveSessionRef_newContext_creates() {
        when(repository.findById("ctx-new")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.saveSessionRef("ctx-new", "claude-worker", "remote-session-123", "u1", "agent-1");

        ArgumentCaptor<AgentConversationContextEntity> captor =
                ArgumentCaptor.forClass(AgentConversationContextEntity.class);
        verify(repository).save(captor.capture());

        AgentConversationContextEntity saved = captor.getValue();
        assertEquals("ctx-new", saved.getContextId());
        assertEquals("claude-worker", saved.getAgentType());
        assertEquals("remote-session-123", saved.getAgentSessionRef());
        assertEquals("u1", saved.getUserId());
        assertEquals("agent-1", saved.getTargetAgentId());
        assertNotNull(saved.getLastAccessedAt());
    }

    @Test
    void saveSessionRef_existingContext_updates() {
        AgentConversationContextEntity existing = new AgentConversationContextEntity();
        existing.setContextId("ctx-1");
        existing.setAgentType("old-type");
        existing.setAgentSessionRef("old-session");
        existing.setLastAccessedAt(LocalDateTime.now().minusDays(1));

        when(repository.findById("ctx-1")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.saveSessionRef("ctx-1", "claude-worker", "new-session", "u1", "agent-2");

        verify(repository).save(existing);
        assertEquals("claude-worker", existing.getAgentType());
        assertEquals("new-session", existing.getAgentSessionRef());
        // lastAccessedAt should be updated to now
        assertTrue(existing.getLastAccessedAt().isAfter(LocalDateTime.now().minusMinutes(1)));
    }

    @Test
    void saveSessionRef_preservesUserId_onNew() {
        when(repository.findById("ctx-new")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.saveSessionRef("ctx-new", "type", "ref", "user-42", "agent-x");

        ArgumentCaptor<AgentConversationContextEntity> captor =
                ArgumentCaptor.forClass(AgentConversationContextEntity.class);
        verify(repository).save(captor.capture());
        assertEquals("user-42", captor.getValue().getUserId());
        assertEquals("agent-x", captor.getValue().getTargetAgentId());
    }
}
