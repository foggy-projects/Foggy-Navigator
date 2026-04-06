package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.exception.ContextAgentMismatchException;
import com.foggy.navigator.session.repository.AgentConversationContextRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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
    void findSessionRef_found() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setAgentSessionRef("remote-session-abc");
        entity.setLastAccessedAt(LocalDateTime.now().minusHours(1));

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        Optional<String> result = store.findSessionRef("ctx-1", "u1");
        assertTrue(result.isPresent());
        assertEquals("remote-session-abc", result.get());
    }

    @Test
    void findSessionRef_oldRecord_stillReturnsWithoutTTL() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setAgentSessionRef("old-session");
        entity.setLastAccessedAt(LocalDateTime.now().minusHours(25));

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        Optional<String> result = store.findSessionRef("ctx-1", "u1");
        assertTrue(result.isPresent());
        assertEquals("old-session", result.get());
    }

    @Test
    void findSessionRef_nullAgentSessionRef_filteredOut() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setAgentSessionRef(null);
        entity.setLastAccessedAt(LocalDateTime.now());

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        Optional<String> result = store.findSessionRef("ctx-1", "u1");
        assertTrue(result.isEmpty());
    }

    @Test
    void findSessionRef_notFound_returnsEmpty() {
        when(repository.findByContextIdAndUserId("ctx-unknown", "u1"))
                .thenReturn(Optional.empty());

        Optional<String> result = store.findSessionRef("ctx-unknown", "u1");
        assertTrue(result.isEmpty());
    }

    @Test
    void findSessionRef_exactlyOneDayOld_stillReturnsWithoutTTL() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setAgentSessionRef("session-ref");
        entity.setLastAccessedAt(LocalDateTime.now().minusHours(24).minusSeconds(1));

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        Optional<String> result = store.findSessionRef("ctx-1", "u1");
        assertTrue(result.isPresent());
        assertEquals("session-ref", result.get());
    }

    // ---- findSessionRefForAgent ----

    @Test
    void findSessionRefForAgent_notFound_returnsEmpty() {
        when(repository.findByContextIdAndUserId("ctx-new", "u1"))
                .thenReturn(Optional.empty());

        Optional<String> result = store.findSessionRefForAgent("ctx-new", "u1", "agent-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void findSessionRefForAgent_sameAgent_returnsRef() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setTargetAgentId("agent-1");
        entity.setAgentSessionRef("claude-session-abc");
        entity.setLastAccessedAt(LocalDateTime.now().minusHours(1));

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        Optional<String> result = store.findSessionRefForAgent("ctx-1", "u1", "agent-1");
        assertTrue(result.isPresent());
        assertEquals("claude-session-abc", result.get());
    }

    @Test
    void findContextForAgent_sameAgent_returnsEntityWithNavigatorSessionId() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setTargetAgentId("agent-1");
        entity.setAgentSessionRef("claude-session-abc");
        entity.setNavigatorSessionId("nav-session-1");
        entity.setLastAccessedAt(LocalDateTime.now().minusHours(1));

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        Optional<AgentConversationContextEntity> result =
                store.findContextForAgent("ctx-1", "u1", "agent-1");
        assertTrue(result.isPresent());
        assertEquals("claude-session-abc", result.get().getAgentSessionRef());
        assertEquals("nav-session-1", result.get().getNavigatorSessionId());
    }

    @Test
    void findSessionRefForAgent_differentAgent_throwsMismatch() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setTargetAgentId("agent-A");
        entity.setAgentSessionRef("session-ref");
        entity.setLastAccessedAt(LocalDateTime.now().minusHours(1));

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        ContextAgentMismatchException ex = assertThrows(
                ContextAgentMismatchException.class,
                () -> store.findSessionRefForAgent("ctx-1", "u1", "agent-B"));

        assertEquals("ctx-1", ex.getContextId());
        assertEquals("agent-A", ex.getBoundAgentId());
        assertEquals("agent-B", ex.getRequestedAgentId());
    }

    @Test
    void findSessionRefForAgent_oldRecordStillChecksAgentMismatch() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setTargetAgentId("agent-A");
        entity.setAgentSessionRef("session-ref");
        entity.setLastAccessedAt(LocalDateTime.now().minusHours(25));

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        ContextAgentMismatchException ex = assertThrows(
                ContextAgentMismatchException.class,
                () -> store.findSessionRefForAgent("ctx-1", "u1", "agent-B"));
        assertEquals("agent-A", ex.getBoundAgentId());
    }

    @Test
    void findSessionRefForAgent_nullTargetAgentId_noException() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setTargetAgentId(null);
        entity.setAgentSessionRef("session-ref");
        entity.setLastAccessedAt(LocalDateTime.now());

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        Optional<String> result = store.findSessionRefForAgent("ctx-1", "u1", "any-agent");
        assertTrue(result.isPresent());
        assertEquals("session-ref", result.get());
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

    @Test
    void saveSessionRefFull_aliasLookupMissWithNewContextId_bubblesUniqueConstraint() {
        when(repository.findById("ctx-new")).thenReturn(Optional.empty());
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry 'daily-alias-user-1-agent-1' for key 'agent_conversation_contexts.idx_acc_alias_user_agent'"));

        DataIntegrityViolationException ex = assertThrows(
                DataIntegrityViolationException.class,
                () -> store.saveSessionRefFull("ctx-new", "claude-worker",
                        "claude-session-2", "nav-session-2",
                        "user-1", "agent-1", "daily-alias"));

        assertTrue(ex.getMessage().contains("idx_acc_alias_user_agent"));
        verify(repository).findById("ctx-new");
        verify(repository).save(any(AgentConversationContextEntity.class));
    }

    @Test
    void deleteByNavigatorSessionId_delegatesToRepository() {
        store.deleteByNavigatorSessionId("session-1");

        verify(repository).deleteByNavigatorSessionId("session-1");
    }

    @Test
    void deleteByNavigatorSessionId_blank_isIgnored() {
        store.deleteByNavigatorSessionId("  ");

        verify(repository, never()).deleteByNavigatorSessionId(any());
    }
}
