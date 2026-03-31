package com.foggy.navigator.session.service;

import com.foggy.navigator.agent.framework.session.*;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.session.repository.AgentConversationContextRepository;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = JpaSessionManagerTest.TestConfig.class)
@ActiveProfiles("test")
class JpaSessionManagerTest {

    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.foggy.navigator.common.entity")
    @EnableJpaRepositories(basePackages = "com.foggy.navigator.session.repository")
    @ComponentScan(basePackages = "com.foggy.navigator.session")
    static class TestConfig {
        @Bean
        LlmModelManager llmModelManager() {
            return mock(LlmModelManager.class);
        }
    }

    @Autowired
    private JpaSessionManager sessionManager;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionMessageRepository messageRepository;

    @Autowired
    private AgentConversationContextRepository contextRepository;

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
        assertEquals("Test Task", session.getTaskName());
        assertNotNull(session.getCreatedAt());
        assertNotNull(session.getUpdatedAt());
    }

    @Test
    void createSession_usesExplicitProviderTypeWithoutOverwritingLogicalAgentId() {
        SessionCreateRequest request = SessionCreateRequest.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .agentId("agent-1")
                .providerType("codex-worker")
                .taskName("Test Task")
                .build();

        String sessionId = sessionManager.createSession(request);

        var saved = sessionRepository.findById(sessionId).orElseThrow();
        assertEquals("agent-1", saved.getAgentId());
        assertEquals("codex-worker", saved.getProviderType());
    }

    @Test
    void createSession_doesNotInferProviderTypeFromNonProviderAgentId() {
        SessionCreateRequest request = SessionCreateRequest.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .agentId("agent-1")
                .taskName("Test Task")
                .build();

        String sessionId = sessionManager.createSession(request);

        var saved = sessionRepository.findById(sessionId).orElseThrow();
        assertEquals("agent-1", saved.getAgentId());
        assertNull(saved.getProviderType());
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
        assertEquals(MessageRole.USER, messages.get(0).getRole());
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
        assertEquals("First", all.get(0).getContent());
        assertEquals("Second", all.get(1).getContent());
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
    void deleteSession_shouldAlsoDeleteAgentConversationContextsByNavigatorSessionId() {
        String sessionId = sessionManager.createSession(createTestRequest());
        AgentConversationContextEntity context = new AgentConversationContextEntity();
        context.setContextId("ctx-1");
        context.setAgentType("codex-worker");
        context.setAgentSessionRef("thread-1");
        context.setNavigatorSessionId(sessionId);
        context.setUserId("user-1");
        context.setTargetAgentId("agent-1");
        contextRepository.save(context);

        sessionManager.deleteSession(sessionId);

        assertFalse(sessionRepository.findById(sessionId).isPresent());
        assertFalse(contextRepository.findById("ctx-1").isPresent());
    }

    @Test
    void findPendingByUser_shouldReturnActiveAndPausedSessions() {
        SessionCreateRequest req1 = SessionCreateRequest.builder()
                .userId("user-pending").agentId("agent-1").build();
        SessionCreateRequest req2 = SessionCreateRequest.builder()
                .userId("user-pending").agentId("agent-2").build();
        SessionCreateRequest req3 = SessionCreateRequest.builder()
                .userId("user-other").agentId("agent-1").build();

        String session1 = sessionManager.createSession(req1);
        String session2 = sessionManager.createSession(req2);
        String session3 = sessionManager.createSession(req3);

        sessionManager.updateStatus(session2, SessionStatus.PAUSED);
        sessionManager.closeSession(session3);

        List<Session> pending = sessionManager.findPendingByUser("user-pending");

        assertEquals(2, pending.size());
    }

    @Test
    void findByUser_shouldReturnAllUserSessions() {
        SessionCreateRequest req1 = SessionCreateRequest.builder()
                .userId("user-find").agentId("agent-1").build();
        SessionCreateRequest req2 = SessionCreateRequest.builder()
                .userId("user-find").agentId("agent-2").build();
        SessionCreateRequest req3 = SessionCreateRequest.builder()
                .userId("user-find-other").agentId("agent-1").build();

        sessionManager.createSession(req1);
        sessionManager.createSession(req2);
        sessionManager.createSession(req3);

        List<Session> user1Sessions = sessionManager.findByUser("user-find");
        List<Session> user2Sessions = sessionManager.findByUser("user-find-other");

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

    @Test
    void metadataJsonSerialization_shouldRoundTrip() {
        String sessionId = sessionManager.createSession(createTestRequest());
        Map<String, Object> metadata = Map.of(
                "type", "TEXT_COMPLETE",
                "agentId", "agent-1",
                "tokens", 42
        );
        Message message = Message.builder()
                .role(MessageRole.ASSISTANT)
                .content("Test content")
                .metadata(metadata)
                .build();

        sessionManager.addMessage(sessionId, message);

        List<Message> messages = sessionManager.getAllMessages(sessionId);
        assertEquals(1, messages.size());
        Map<String, Object> savedMetadata = messages.get(0).getMetadata();
        assertNotNull(savedMetadata);
        assertEquals("TEXT_COMPLETE", savedMetadata.get("type"));
        assertEquals("agent-1", savedMetadata.get("agentId"));
        assertEquals(42, savedMetadata.get("tokens"));
    }

    private SessionCreateRequest createTestRequest() {
        return SessionCreateRequest.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .agentId("agent-1")
                .build();
    }
}
