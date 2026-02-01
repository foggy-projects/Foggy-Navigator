package com.foggy.navigator.agent.framework.session.impl;

import com.foggy.navigator.agent.framework.session.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemorySessionManagerTest {

    private InMemorySessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new InMemorySessionManager();
    }

    @Test
    void createSession_shouldReturnSessionId() {
        SessionCreateRequest request = SessionCreateRequest.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .agentId("agent-1")
                .taskName("Test Task")
                .build();

        String sessionId = sessionManager.createSession(request);

        assertNotNull(sessionId);
        assertFalse(sessionId.isBlank());
    }

    @Test
    void getSession_shouldReturnCreatedSession() {
        SessionCreateRequest request = SessionCreateRequest.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .agentId("agent-1")
                .taskName("Test Task")
                .build();
        String sessionId = sessionManager.createSession(request);

        Session session = sessionManager.getSession(sessionId);

        assertNotNull(session);
        assertEquals(sessionId, session.getId());
        assertEquals("user-1", session.getUserId());
        assertEquals("tenant-1", session.getTenantId());
        assertEquals("agent-1", session.getAgentId());
        assertEquals(SessionStatus.ACTIVE, session.getStatus());
        assertNotNull(session.getCreatedAt());
    }

    @Test
    void getSession_shouldReturnNullForNonExistent() {
        assertNull(sessionManager.getSession("non-existent"));
    }

    @Test
    void updateStatus_shouldChangeSessionStatus() {
        String sessionId = sessionManager.createSession(createTestRequest());

        sessionManager.updateStatus(sessionId, SessionStatus.PAUSED);

        Session session = sessionManager.getSession(sessionId);
        assertEquals(SessionStatus.PAUSED, session.getStatus());
    }

    @Test
    void addMessage_shouldAddMessageToSession() {
        String sessionId = sessionManager.createSession(createTestRequest());
        Message message = Message.builder()
                .role(MessageRole.USER)
                .content("Hello")
                .build();

        String messageId = sessionManager.addMessage(sessionId, message);

        assertNotNull(messageId);
        List<Message> messages = sessionManager.getAllMessages(sessionId);
        assertEquals(1, messages.size());
        assertEquals("Hello", messages.get(0).getContent());
        assertEquals(sessionId, messages.get(0).getSessionId());
    }

    @Test
    void addMessage_shouldReturnNullForNonExistentSession() {
        Message message = Message.builder().role(MessageRole.USER).content("Hello").build();

        String messageId = sessionManager.addMessage("non-existent", message);

        assertNull(messageId);
    }

    @Test
    void getRecentMessages_shouldReturnLimitedMessages() {
        String sessionId = sessionManager.createSession(createTestRequest());
        for (int i = 1; i <= 10; i++) {
            sessionManager.addMessage(sessionId,
                    Message.builder().role(MessageRole.USER).content("Message " + i).build());
        }

        List<Message> recent = sessionManager.getRecentMessages(sessionId, 3);

        assertEquals(3, recent.size());
        assertEquals("Message 8", recent.get(0).getContent());
        assertEquals("Message 9", recent.get(1).getContent());
        assertEquals("Message 10", recent.get(2).getContent());
    }

    @Test
    void getAllMessages_shouldReturnAllMessages() {
        String sessionId = sessionManager.createSession(createTestRequest());
        sessionManager.addMessage(sessionId,
                Message.builder().role(MessageRole.USER).content("First").build());
        sessionManager.addMessage(sessionId,
                Message.builder().role(MessageRole.ASSISTANT).content("Second").build());

        List<Message> all = sessionManager.getAllMessages(sessionId);

        assertEquals(2, all.size());
    }

    @Test
    void getAllMessages_shouldReturnEmptyForNonExistent() {
        List<Message> messages = sessionManager.getAllMessages("non-existent");
        assertTrue(messages.isEmpty());
    }

    @Test
    void closeSession_shouldSetStatusToCompleted() {
        String sessionId = sessionManager.createSession(createTestRequest());

        sessionManager.closeSession(sessionId);

        Session session = sessionManager.getSession(sessionId);
        assertEquals(SessionStatus.COMPLETED, session.getStatus());
    }

    @Test
    void findPendingByUser_shouldReturnActiveAndPausedSessions() {
        SessionCreateRequest req1 = SessionCreateRequest.builder()
                .userId("user-1").agentId("agent-1").build();
        SessionCreateRequest req2 = SessionCreateRequest.builder()
                .userId("user-1").agentId("agent-2").build();
        SessionCreateRequest req3 = SessionCreateRequest.builder()
                .userId("user-2").agentId("agent-1").build();

        String session1 = sessionManager.createSession(req1);
        String session2 = sessionManager.createSession(req2);
        String session3 = sessionManager.createSession(req3);

        sessionManager.updateStatus(session2, SessionStatus.PAUSED);
        sessionManager.closeSession(session3);

        List<Session> pending = sessionManager.findPendingByUser("user-1");

        assertEquals(2, pending.size());
    }

    @Test
    void findByUser_shouldReturnAllUserSessions() {
        SessionCreateRequest req1 = SessionCreateRequest.builder()
                .userId("user-1").agentId("agent-1").build();
        SessionCreateRequest req2 = SessionCreateRequest.builder()
                .userId("user-1").agentId("agent-2").build();
        SessionCreateRequest req3 = SessionCreateRequest.builder()
                .userId("user-2").agentId("agent-1").build();

        sessionManager.createSession(req1);
        sessionManager.createSession(req2);
        sessionManager.createSession(req3);

        List<Session> user1Sessions = sessionManager.findByUser("user-1");
        List<Session> user2Sessions = sessionManager.findByUser("user-2");

        assertEquals(2, user1Sessions.size());
        assertEquals(1, user2Sessions.size());
    }

    @Test
    void createSession_shouldSupportParentSession() {
        String parentSessionId = sessionManager.createSession(createTestRequest());
        SessionCreateRequest childRequest = SessionCreateRequest.builder()
                .userId("user-1")
                .agentId("agent-2")
                .parentSessionId(parentSessionId)
                .build();

        String childSessionId = sessionManager.createSession(childRequest);

        Session childSession = sessionManager.getSession(childSessionId);
        assertEquals(parentSessionId, childSession.getParentSessionId());
    }

    private SessionCreateRequest createTestRequest() {
        return SessionCreateRequest.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .agentId("agent-1")
                .build();
    }
}
