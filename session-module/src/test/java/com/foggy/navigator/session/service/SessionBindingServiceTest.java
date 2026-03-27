package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.session.exception.SessionAgentBoundMismatchException;
import com.foggy.navigator.session.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionBindingServiceTest {

    @Mock
    private SessionRepository sessionRepository;

    private SessionBindingService service;

    @BeforeEach
    void setUp() {
        service = new SessionBindingService(sessionRepository);
    }

    // ── getOrBind ──

    @Test
    void getOrBind_sessionNotYetCreated_returnsTargetAgentId() {
        when(sessionRepository.findById("session-new")).thenReturn(Optional.empty());

        String result = service.getOrBind("session-new", "agent-1", "claude-worker", "EXPLICIT_AGENT");

        assertEquals("agent-1", result);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void getOrBind_newBinding_setsAllFieldsAndReturnsAgentId() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        String result = service.getOrBind("session-1", "agent-1", "claude-worker", "EXPLICIT_AGENT");

        assertEquals("agent-1", result);
        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        SessionEntity saved = captor.getValue();
        assertEquals("agent-1", saved.getAgentId());
        assertEquals("claude-worker", saved.getProviderType());
        assertEquals("EXPLICIT_AGENT", saved.getBindingSource());
    }

    @Test
    void getOrBind_existingCompleteBinding_sameAgent_returnsExisting() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setAgentId("agent-1");
        session.setProviderType("claude-worker");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        String result = service.getOrBind("session-1", "agent-1", "claude-worker", "EXPLICIT_AGENT");

        assertEquals("agent-1", result);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void getOrBind_existingCompleteBinding_differentAgent_throwsMismatch() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setAgentId("agent-1");
        session.setProviderType("claude-worker");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        SessionAgentBoundMismatchException ex = assertThrows(
                SessionAgentBoundMismatchException.class,
                () -> service.getOrBind("session-1", "agent-2", "claude-worker", "EXPLICIT_AGENT"));

        assertEquals("session-1", ex.getSessionId());
        assertEquals("agent-1", ex.getBoundAgentId());
        assertEquals("agent-2", ex.getRequestedAgentId());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void getOrBind_legacySession_agentIdSetButProviderTypeNull_backfillsProviderType() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setAgentId("agent-1");
        // providerType is null — legacy session
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        String result = service.getOrBind("session-1", "agent-1", "codex-worker", "EXPLICIT_AGENT");

        assertEquals("agent-1", result);
        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        SessionEntity saved = captor.getValue();
        assertEquals("codex-worker", saved.getProviderType());
        assertEquals("RESTORED", saved.getBindingSource());
    }

    @Test
    void getOrBind_legacySession_differentAgent_throwsMismatch() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setAgentId("agent-1");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        assertThrows(SessionAgentBoundMismatchException.class,
                () -> service.getOrBind("session-1", "agent-2", "claude-worker", "EXPLICIT_AGENT"));
        verify(sessionRepository, never()).save(any());
    }

    // ── validateBinding ──

    @Test
    void validateBinding_sessionNotFound_noop() {
        when(sessionRepository.findById("session-missing")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.validateBinding("session-missing", "agent-1"));
    }

    @Test
    void validateBinding_noExistingAgentId_noop() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        assertDoesNotThrow(() -> service.validateBinding("session-1", "agent-1"));
    }

    @Test
    void validateBinding_sameAgent_passes() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setAgentId("agent-1");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        assertDoesNotThrow(() -> service.validateBinding("session-1", "agent-1"));
    }

    @Test
    void validateBinding_differentAgent_throwsMismatch() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setAgentId("agent-1");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        SessionAgentBoundMismatchException ex = assertThrows(
                SessionAgentBoundMismatchException.class,
                () -> service.validateBinding("session-1", "agent-2"));

        assertEquals("agent-1", ex.getBoundAgentId());
        assertEquals("agent-2", ex.getRequestedAgentId());
    }
}
