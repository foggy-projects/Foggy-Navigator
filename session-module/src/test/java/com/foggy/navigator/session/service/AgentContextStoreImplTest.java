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
    @Mock private AgentContextOwnershipClaimWriter ownershipClaimWriter;

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
    void findSessionRefForAgent_nullTargetAgentId_failsClosed() {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId("ctx-1");
        entity.setUserId("u1");
        entity.setTargetAgentId(null);
        entity.setAgentSessionRef("session-ref");
        entity.setLastAccessedAt(LocalDateTime.now());

        when(repository.findByContextIdAndUserId("ctx-1", "u1"))
                .thenReturn(Optional.of(entity));

        SecurityException error = assertThrows(SecurityException.class,
                () -> store.findSessionRefForAgent("ctx-1", "u1", "any-agent"));
        assertEquals("Resource access denied", error.getMessage());
    }

    // ---- saveSessionRef ----

    @Test
    void saveSessionRef_newContext_creates() {
        when(repository.findById("ctx-new")).thenReturn(Optional.empty());
        store.saveSessionRef("ctx-new", "claude-worker", "remote-session-123", "u1", "agent-1");

        ArgumentCaptor<AgentConversationContextEntity> captor =
                ArgumentCaptor.forClass(AgentConversationContextEntity.class);
        verify(ownershipClaimWriter).insert(captor.capture());

        AgentConversationContextEntity saved = captor.getValue();
        assertEquals("ctx-new", saved.getContextId());
        assertEquals("claude-worker", saved.getAgentType());
        assertEquals("remote-session-123", saved.getAgentSessionRef());
        assertEquals("u1", saved.getUserId());
        assertEquals("agent-1", saved.getTargetAgentId());
        assertNotNull(saved.getLastAccessedAt());
    }

    @Test
    void saveSessionRef_existingSameOwnerAndAgent_updatesIdempotently() {
        AgentConversationContextEntity existing = new AgentConversationContextEntity();
        existing.setContextId("ctx-1");
        existing.setUserId("u1");
        existing.setTargetAgentId("agent-2");
        existing.setAgentType("claude-worker");
        existing.setAgentSessionRef("same-session");
        existing.setLastAccessedAt(LocalDateTime.now().minusDays(1));

        when(repository.findById("ctx-1")).thenReturn(Optional.of(existing));
        when(repository.updateSessionRefIfOwned(
                eq("ctx-1"), eq("claude-worker"), eq("same-session"),
                eq("u1"), eq("agent-2"), any(LocalDateTime.class)))
                .thenReturn(1);

        store.saveSessionRef("ctx-1", "claude-worker", "same-session", "u1", "agent-2");

        verify(repository).updateSessionRefIfOwned(
                eq("ctx-1"), eq("claude-worker"), eq("same-session"),
                eq("u1"), eq("agent-2"), any(LocalDateTime.class));
        verifyNoInteractions(ownershipClaimWriter);
    }

    @Test
    void saveSessionRef_existingContextOwnedByDifferentUser_failsClosed() {
        AgentConversationContextEntity existing = context(
                "ctx-1", "owner-user", "agent-1");
        when(repository.findById("ctx-1")).thenReturn(Optional.of(existing));

        SecurityException error = assertThrows(SecurityException.class,
                () -> store.saveSessionRef(
                        "ctx-1", "claude-worker", "new-session", "other-user", "agent-1"));

        assertEquals("Resource access denied", error.getMessage());
        verify(repository, never()).updateSessionRefIfOwned(
                anyString(), anyString(), any(), anyString(), anyString(), any());
        verifyNoInteractions(ownershipClaimWriter);
    }

    @Test
    void saveSessionRef_existingContextBoundToDifferentAgent_failsClosed() {
        AgentConversationContextEntity existing = context(
                "ctx-1", "user-1", "agent-A");
        when(repository.findById("ctx-1")).thenReturn(Optional.of(existing));

        ContextAgentMismatchException error = assertThrows(
                ContextAgentMismatchException.class,
                () -> store.saveSessionRef(
                        "ctx-1", "claude-worker", "new-session", "user-1", "agent-B"));

        assertEquals("agent-A", error.getBoundAgentId());
        assertEquals("agent-B", error.getRequestedAgentId());
        verify(repository, never()).updateSessionRefIfOwned(
                anyString(), anyString(), any(), anyString(), anyString(), any());
        verifyNoInteractions(ownershipClaimWriter);
    }

    @Test
    void saveSessionRef_concurrentFirstCreateSameBinding_rereadsAndUpdatesWinner() {
        AgentConversationContextEntity winner = context(
                "ctx-race", "user-1", "agent-1");
        when(repository.findById("ctx-race"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new DataIntegrityViolationException("duplicate primary key"))
                .when(ownershipClaimWriter).insert(any());
        when(repository.updateSessionRefIfOwned(
                eq("ctx-race"), eq("claude-worker"), eq("session-1"),
                eq("user-1"), eq("agent-1"), any(LocalDateTime.class)))
                .thenReturn(1);

        assertDoesNotThrow(() -> store.saveSessionRef(
                "ctx-race", "claude-worker", "session-1", "user-1", "agent-1"));

        verify(repository).updateSessionRefIfOwned(
                eq("ctx-race"), eq("claude-worker"), eq("session-1"),
                eq("user-1"), eq("agent-1"), any(LocalDateTime.class));
    }

    @Test
    void saveSessionRef_concurrentFirstCreateDifferentUser_failsClosed() {
        AgentConversationContextEntity winner = context(
                "ctx-race", "winner-user", "agent-1");
        when(repository.findById("ctx-race"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new DataIntegrityViolationException("duplicate primary key"))
                .when(ownershipClaimWriter).insert(any());

        assertThrows(SecurityException.class, () -> store.saveSessionRef(
                "ctx-race", "claude-worker", "session-1", "loser-user", "agent-1"));

        verify(repository, never()).updateSessionRefIfOwned(
                anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void saveSessionRef_preservesUserId_onNew() {
        when(repository.findById("ctx-new")).thenReturn(Optional.empty());
        store.saveSessionRef("ctx-new", "type", "ref", "user-42", "agent-x");

        ArgumentCaptor<AgentConversationContextEntity> captor =
                ArgumentCaptor.forClass(AgentConversationContextEntity.class);
        verify(ownershipClaimWriter).insert(captor.capture());
        assertEquals("user-42", captor.getValue().getUserId());
        assertEquals("agent-x", captor.getValue().getTargetAgentId());
    }

    @Test
    void saveSessionRefFull_existingSameOwnerAndAgent_updatesAllMutableFields() {
        AgentConversationContextEntity existing = context(
                "ctx-1", "user-1", "agent-1");
        when(repository.findById("ctx-1")).thenReturn(Optional.of(existing));
        when(repository.updateSessionRefFullIfOwned(
                eq("ctx-1"), eq("claude-worker"), eq("remote-1"), eq("nav-1"),
                eq("user-1"), eq("agent-1"), eq("alias-1"), any(LocalDateTime.class)))
                .thenReturn(1);

        store.saveSessionRefFull(
                "ctx-1", "claude-worker", "remote-1", "nav-1",
                "user-1", "agent-1", "alias-1");

        verify(repository).updateSessionRefFullIfOwned(
                eq("ctx-1"), eq("claude-worker"), eq("remote-1"), eq("nav-1"),
                eq("user-1"), eq("agent-1"), eq("alias-1"), any(LocalDateTime.class));
        verifyNoInteractions(ownershipClaimWriter);
    }

    @Test
    void saveSessionRefFull_existingContextBoundToDifferentAgent_failsClosed() {
        AgentConversationContextEntity existing = context(
                "ctx-1", "user-1", "agent-A");
        when(repository.findById("ctx-1")).thenReturn(Optional.of(existing));

        assertThrows(ContextAgentMismatchException.class,
                () -> store.saveSessionRefFull(
                        "ctx-1", "claude-worker", "remote-1", "nav-1",
                        "user-1", "agent-B", "alias-1"));

        verify(repository, never()).updateSessionRefFullIfOwned(
                anyString(), anyString(), any(), any(), anyString(), anyString(), any(), any());
        verifyNoInteractions(ownershipClaimWriter);
    }

    @Test
    void saveSessionRefFull_aliasLookupMissWithNewContextId_bubblesUniqueConstraint() {
        when(repository.findById("ctx-new")).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException(
                "Duplicate entry 'daily-alias-user-1-agent-1' for key 'agent_conversation_contexts.idx_acc_alias_user_agent'"))
                .when(ownershipClaimWriter).insert(any());

        DataIntegrityViolationException ex = assertThrows(
                DataIntegrityViolationException.class,
                () -> store.saveSessionRefFull("ctx-new", "claude-worker",
                        "claude-session-2", "nav-session-2",
                        "user-1", "agent-1", "daily-alias"));

        assertTrue(ex.getMessage().contains("idx_acc_alias_user_agent"));
        verify(repository, times(2)).findById("ctx-new");
        verify(ownershipClaimWriter).insert(any(AgentConversationContextEntity.class));
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

    private AgentConversationContextEntity context(
            String contextId, String userId, String targetAgentId) {
        AgentConversationContextEntity entity = new AgentConversationContextEntity();
        entity.setContextId(contextId);
        entity.setUserId(userId);
        entity.setTargetAgentId(targetAgentId);
        entity.setAgentType("claude-worker");
        entity.setAgentSessionRef("existing-session");
        entity.setLastAccessedAt(LocalDateTime.now().minusHours(1));
        return entity;
    }
}
