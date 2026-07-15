package com.foggy.navigator.session.controller;

import com.foggy.navigator.agent.framework.core.AgentInvoker;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.agent.framework.session.SessionStatus;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.session.service.SessionMetadataService;
import com.foggy.navigator.session.service.SessionTaskResourceAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionControllerTest {

    private SessionManager sessionManager;
    private AgentInvoker agentInvoker;
    private SessionRepository sessionRepository;
    private SessionMetadataService sessionMetadataService;
    private SessionTaskResourceAccessService resourceAccessService;
    private SessionController controller;

    @BeforeEach
    void setUp() {
        sessionManager = mock(SessionManager.class);
        agentInvoker = mock(AgentInvoker.class);
        sessionRepository = mock(SessionRepository.class);
        sessionMetadataService = mock(SessionMetadataService.class);
        resourceAccessService = mock(SessionTaskResourceAccessService.class);
        controller = new SessionController(
                sessionManager,
                agentInvoker,
                sessionRepository,
                sessionMetadataService,
                resourceAccessService);

        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("user-1")
                .username("alice")
                .tenantId("tenant-1")
                .build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createSession_withParent_authorizesParentBeforeCreating() {
        SessionController.CreateSessionForm form = new SessionController.CreateSessionForm();
        form.setAgentId("agent-1");
        form.setParentSessionId("parent-1");
        when(resourceAccessService.requireOwnedSession("parent-1", "user-1", "tenant-1"))
                .thenReturn(ownedSession("parent-1", "agent-1"));
        when(sessionManager.createSession(any())).thenReturn("session-1");
        when(sessionManager.getSession("session-1")).thenReturn(frameworkSession("session-1"));

        controller.createSession(form);

        InOrder order = inOrder(resourceAccessService, sessionManager);
        order.verify(resourceAccessService)
                .requireOwnedSession("parent-1", "user-1", "tenant-1");
        order.verify(sessionManager).createSession(any());
    }

    @Test
    void createSession_withUnauthorizedParent_doesNotCreate() {
        SessionController.CreateSessionForm form = new SessionController.CreateSessionForm();
        form.setAgentId("agent-1");
        form.setParentSessionId("parent-1");
        when(resourceAccessService.requireOwnedSession("parent-1", "user-1", "tenant-1"))
                .thenThrow(denied());

        assertThrows(SecurityException.class, () -> controller.createSession(form));

        verify(sessionManager, never()).createSession(any());
    }

    @Test
    void createSession_withoutParent_doesNotRunParentAuthorization() {
        SessionController.CreateSessionForm form = new SessionController.CreateSessionForm();
        form.setAgentId("agent-1");
        when(sessionManager.createSession(any())).thenReturn("session-1");
        when(sessionManager.getSession("session-1")).thenReturn(frameworkSession("session-1"));

        controller.createSession(form);

        verifyNoInteractions(resourceAccessService);
    }

    @Test
    void listSessions_withoutAgent_scopesQueryByUserAndTenant() {
        when(sessionRepository.findByUserIdAndTenantIdOrderByUpdatedAtDesc("user-1", "tenant-1"))
                .thenReturn(List.of(ownedSession("session-1", "agent-1")));

        controller.listSessions(null);

        verify(sessionRepository)
                .findByUserIdAndTenantIdOrderByUpdatedAtDesc("user-1", "tenant-1");
        verify(sessionManager, never()).findByUser(any());
    }

    @Test
    void listSessions_withAgent_scopesQueryByUserTenantAndAgent() {
        when(sessionRepository.findByUserIdAndTenantIdAndAgentIdOrderByUpdatedAtDesc(
                "user-1", "tenant-1", "agent-1"))
                .thenReturn(List.of(ownedSession("session-1", "agent-1")));

        controller.listSessions("agent-1");

        verify(sessionRepository).findByUserIdAndTenantIdAndAgentIdOrderByUpdatedAtDesc(
                "user-1", "tenant-1", "agent-1");
    }

    @Test
    void getSession_authorizesBeforeReading() {
        when(resourceAccessService.requireOwnedSession("session-1", "user-1", "tenant-1"))
                .thenReturn(ownedSession("session-1", "agent-1"));
        when(sessionManager.getSession("session-1")).thenReturn(frameworkSession("session-1"));

        controller.getSession("session-1");

        InOrder order = inOrder(resourceAccessService, sessionManager);
        order.verify(resourceAccessService)
                .requireOwnedSession("session-1", "user-1", "tenant-1");
        order.verify(sessionManager).getSession("session-1");
    }

    @Test
    void getSession_whenUnauthorized_doesNotRead() {
        when(resourceAccessService.requireOwnedSession("session-1", "user-1", "tenant-1"))
                .thenThrow(denied());

        assertThrows(SecurityException.class, () -> controller.getSession("session-1"));

        verify(sessionManager, never()).getSession("session-1");
    }

    @Test
    void deleteSession_authorizesBeforeDeleting() {
        when(resourceAccessService.requireOwnedSession("session-1", "user-1", "tenant-1"))
                .thenReturn(ownedSession("session-1", "agent-1"));

        controller.deleteSession("session-1");

        InOrder order = inOrder(resourceAccessService, sessionMetadataService);
        order.verify(resourceAccessService)
                .requireOwnedSession("session-1", "user-1", "tenant-1");
        order.verify(sessionMetadataService).deleteConversation("session-1", "user-1");
    }

    @Test
    void getMessages_authorizesBeforeReading() {
        when(resourceAccessService.requireOwnedSession("session-1", "user-1", "tenant-1"))
                .thenReturn(ownedSession("session-1", "agent-1"));
        when(sessionManager.getAllMessages("session-1")).thenReturn(List.of());

        controller.getMessages("session-1");

        InOrder order = inOrder(resourceAccessService, sessionManager);
        order.verify(resourceAccessService)
                .requireOwnedSession("session-1", "user-1", "tenant-1");
        order.verify(sessionManager).getAllMessages("session-1");
    }

    @Test
    void getLatestMessages_authorizesBeforeCountingOrReading() {
        when(resourceAccessService.requireOwnedSession("session-1", "user-1", "tenant-1"))
                .thenReturn(ownedSession("session-1", "agent-1"));
        when(sessionManager.getLatestMessages("session-1", 25, 5)).thenReturn(List.of());

        controller.getLatestMessages("session-1", 25, 5);

        InOrder order = inOrder(resourceAccessService, sessionManager);
        order.verify(resourceAccessService)
                .requireOwnedSession("session-1", "user-1", "tenant-1");
        order.verify(sessionManager).countMessages("session-1");
        order.verify(sessionManager).getLatestMessages("session-1", 25, 5);
    }

    @Test
    void sendMessage_authorizesBeforePersistingAndInvoking() {
        SessionController.SendMessageForm form = new SessionController.SendMessageForm();
        form.setContent("hello");
        when(resourceAccessService.requireOwnedSession("session-1", "user-1", "tenant-1"))
                .thenReturn(ownedSession("session-1", "agent-1"));
        when(sessionManager.addMessage(any(), any())).thenReturn("message-1");

        controller.sendMessage("session-1", form);

        InOrder order = inOrder(resourceAccessService, sessionManager, agentInvoker);
        order.verify(resourceAccessService)
                .requireOwnedSession("session-1", "user-1", "tenant-1");
        order.verify(sessionManager).addMessage(any(), any(Message.class));
        order.verify(agentInvoker).invokeAsync(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq("agent-1"),
                any(Message.class));
    }

    @Test
    void sendMessage_whenUnauthorized_hasNoSideEffects() {
        SessionController.SendMessageForm form = new SessionController.SendMessageForm();
        form.setContent("hello");
        when(resourceAccessService.requireOwnedSession("session-1", "user-1", "tenant-1"))
                .thenThrow(denied());

        assertThrows(SecurityException.class, () -> controller.sendMessage("session-1", form));

        verify(sessionManager, never()).addMessage(any(), any());
        verifyNoInteractions(agentInvoker);
    }

    private static SessionEntity ownedSession(String id, String agentId) {
        SessionEntity entity = new SessionEntity();
        entity.setId(id);
        entity.setUserId("user-1");
        entity.setTenantId("tenant-1");
        entity.setAgentId(agentId);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private static Session frameworkSession(String id) {
        return Session.builder()
                .id(id)
                .userId("user-1")
                .tenantId("tenant-1")
                .agentId("agent-1")
                .status(SessionStatus.ACTIVE)
                .build();
    }

    private static SecurityException denied() {
        return new SecurityException("Session not found or not owned");
    }
}
