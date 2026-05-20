package com.foggy.navigator.session.service;

import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.repository.AgentConversationContextRepository;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Open API 会话查询服务测试
 * <p>
 * 覆盖：
 * - contextId → sessionId 一对一映射约束
 * - taskId + cursor 增量消息查询
 * - contextId 历史消息查询
 * - 会话列表查询
 */
@SpringBootTest(classes = OpenApiSessionQueryServiceTest.TestConfig.class)
@ActiveProfiles("test")
class OpenApiSessionQueryServiceTest {

    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.foggy.navigator.common.entity")
    @EnableJpaRepositories(basePackages = {
            "com.foggy.navigator.session.repository",
            "com.foggy.navigator.common.repository"
    })
    @ComponentScan(basePackages = "com.foggy.navigator.session")
    static class TestConfig {
        @Bean
        LlmModelManager llmModelManager() {
            return mock(LlmModelManager.class);
        }
    }

    @Autowired
    private OpenApiSessionQueryService queryService;

    @Autowired
    private JpaSessionManager sessionManager;

    @Autowired
    private AgentConversationContextRepository contextRepository;

    @Autowired
    private SessionMessageRepository messageRepository;

    @Autowired
    private SessionTaskRepository taskRepository;

    private String sessionId;
    private String contextId;
    private static final String USER_ID = "test-user";
    private static final String AGENT_ID = "test-agent";
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        // 创建一个会话
        sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .agentId(AGENT_ID)
                .taskName("Test")
                .build());

        // 创建 contextId 映射
        contextId = "ctx-" + UUID.randomUUID().toString().substring(0, 8);
        AgentConversationContextEntity ctx = new AgentConversationContextEntity();
        ctx.setContextId(contextId);
        ctx.setAgentType("claude-worker");
        ctx.setNavigatorSessionId(sessionId);
        ctx.setUserId(USER_ID);
        ctx.setTargetAgentId(AGENT_ID);
        contextRepository.save(ctx);
    }

    // ── contextId → sessionId 映射 ──

    @Test
    void resolveSessionId_shouldMapContextIdToSessionId() {
        Optional<String> result = queryService.resolveSessionId(contextId, USER_ID);
        assertTrue(result.isPresent());
        assertEquals(sessionId, result.get());
    }

    @Test
    void resolveSessionId_shouldReturnEmptyForUnknownContext() {
        Optional<String> result = queryService.resolveSessionId("unknown-ctx", USER_ID);
        assertTrue(result.isEmpty());
    }

    @Test
    void resolveContextId_shouldReverseMapSessionToContext() {
        Optional<String> result = queryService.resolveContextId(sessionId);
        assertTrue(result.isPresent());
        assertEquals(contextId, result.get());
    }

    // ── 会话列表查询 ──

    @Test
    void listSessions_shouldReturnContextsByUserAndAgent() {
        List<AgentConversationContextEntity> sessions = queryService.listSessions(
                USER_ID, AGENT_ID, 10);
        assertFalse(sessions.isEmpty());
        assertEquals(contextId, sessions.get(0).getContextId());
    }

    @Test
    void listSessions_shouldReturnEmptyForDifferentAgent() {
        List<AgentConversationContextEntity> sessions = queryService.listSessions(
                USER_ID, "other-agent", 10);
        assertTrue(sessions.isEmpty());
    }

    @Test
    void listSessions_cursorShouldReturnNextPage() {
        AgentConversationContextEntity older = new AgentConversationContextEntity();
        older.setContextId("ctx-older-" + UUID.randomUUID().toString().substring(0, 8));
        older.setAgentType("claude-worker");
        older.setNavigatorSessionId("session-older");
        older.setUserId(USER_ID);
        older.setTargetAgentId(AGENT_ID);
        older.setLastAccessedAt(LocalDateTime.now().minusDays(2));
        contextRepository.save(older);

        List<AgentConversationContextEntity> firstPage = queryService.listSessions(
                USER_ID, AGENT_ID, null, 1);
        assertFalse(firstPage.isEmpty());

        List<AgentConversationContextEntity> secondPage = queryService.listSessions(
                USER_ID, AGENT_ID, firstPage.get(0).getContextId(), 1);

        assertFalse(secondPage.isEmpty());
        assertNotEquals(firstPage.get(0).getContextId(), secondPage.get(0).getContextId());
        assertTrue(secondPage.get(0).getLastAccessedAt().isBefore(firstPage.get(0).getLastAccessedAt()));
    }

    @Test
    void updateClientContextJson_shouldPersistOpaqueJson() {
        queryService.updateClientContextJson(contextId, USER_ID, AGENT_ID,
                "{\"upstreamConversationId\":\"tms-1\"}");

        AgentConversationContextEntity updated = contextRepository.findById(contextId).orElseThrow();
        assertEquals("{\"upstreamConversationId\":\"tms-1\"}", updated.getClientContextJson());
    }

    // ── 会话消息查询 ──

    @Test
    void getSessionMessages_shouldReturnMessagesInOrder() {
        // 插入消息
        addMessage(sessionId, null, MessageRole.USER, "Hello");
        addMessage(sessionId, null, MessageRole.ASSISTANT, "Hi there");

        List<SessionMessageEntity> messages = queryService.getSessionMessages(
                sessionId, null, 50);
        assertEquals(2, messages.size());
        assertEquals("Hello", messages.get(0).getContent());
        assertEquals("Hi there", messages.get(1).getContent());
    }

    @Test
    void getSessionMessages_cursorShouldSkipPreviousMessages() {
        String id1 = addMessage(sessionId, null, MessageRole.USER, "First");
        addMessage(sessionId, null, MessageRole.ASSISTANT, "Second");

        // 用第一条消息 ID 作为 cursor
        List<SessionMessageEntity> messages = queryService.getSessionMessages(
                sessionId, id1, 50);
        assertEquals(1, messages.size());
        assertEquals("Second", messages.get(0).getContent());
    }

    @Test
    void batchFindFirstUserMessageContents_shouldReturnFirstUserMessagePerSession() {
        String otherSessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .agentId(AGENT_ID)
                .taskName("Other")
                .build());

        addMessage(sessionId, null, MessageRole.ASSISTANT, "Assistant first");
        addMessage(sessionId, null, MessageRole.USER, "First user prompt");
        addMessage(sessionId, null, MessageRole.USER, "Second user prompt");
        addMessage(otherSessionId, null, MessageRole.USER, "Other first prompt");

        Map<String, String> result = queryService.batchFindFirstUserMessageContents(
                List.of(sessionId, otherSessionId));

        assertEquals("First user prompt", result.get(sessionId));
        assertEquals("Other first prompt", result.get(otherSessionId));
    }

    // ── taskId + cursor 增量消息查询 ──

    @Test
    void getTaskMessages_shouldFilterByTaskId() {
        String taskId1 = "task-" + UUID.randomUUID().toString().substring(0, 8);
        String taskId2 = "task-" + UUID.randomUUID().toString().substring(0, 8);

        addMessage(sessionId, taskId1, MessageRole.ASSISTANT, "Task1 msg1");
        addMessage(sessionId, taskId1, MessageRole.ASSISTANT, "Task1 msg2");
        addMessage(sessionId, taskId2, MessageRole.ASSISTANT, "Task2 msg1");

        List<SessionMessageEntity> task1Messages = queryService.getTaskMessages(
                taskId1, null, 50);
        assertEquals(2, task1Messages.size());
        assertEquals("Task1 msg1", task1Messages.get(0).getContent());
        assertEquals("Task1 msg2", task1Messages.get(1).getContent());
    }

    @Test
    void getTaskMessages_shouldReturnPersistedToolMessagesByTaskId() {
        String taskId = "lgt-" + UUID.randomUUID().toString().substring(0, 8);

        addMessage(sessionId, taskId, MessageRole.ASSISTANT, "tms.dataset.listModels",
                Map.of("type", "TOOL_CALL_START", "taskId", taskId, "toolName", "tms.dataset.listModels"));
        addMessage(sessionId, taskId, MessageRole.TOOL, "{\"ok\":true}",
                Map.of("type", "TOOL_CALL_RESULT", "taskId", taskId, "toolName", "tms.dataset.listModels",
                        "success", true));

        List<SessionMessageEntity> messages = queryService.getTaskMessages(taskId, null, 50);

        assertEquals(2, messages.size());
        assertEquals(taskId, messages.get(0).getTaskId());
        assertTrue(messages.get(0).getMetadata().contains("TOOL_CALL_START"));
        assertEquals(taskId, messages.get(1).getTaskId());
        assertTrue(messages.get(1).getMetadata().contains("TOOL_CALL_RESULT"));
    }

    @Test
    void getTaskMessages_cursorShouldReturnIncrementalMessages() {
        String taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);

        String id1 = addMessage(sessionId, taskId, MessageRole.ASSISTANT, "Msg 1");
        addMessage(sessionId, taskId, MessageRole.ASSISTANT, "Msg 2");
        addMessage(sessionId, taskId, MessageRole.ASSISTANT, "Msg 3");

        // 用第一条 cursor，应返回 msg2 和 msg3
        List<SessionMessageEntity> messages = queryService.getTaskMessages(
                taskId, id1, 50);
        assertEquals(2, messages.size());
        assertEquals("Msg 2", messages.get(0).getContent());
        assertEquals("Msg 3", messages.get(1).getContent());
    }

    @Test
    void getTaskMessages_sameCursorShouldBeIdempotent() {
        String taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);

        addMessage(sessionId, taskId, MessageRole.ASSISTANT, "Msg 1");
        String id2 = addMessage(sessionId, taskId, MessageRole.ASSISTANT, "Msg 2");

        // 用 id2 作为 cursor，没有更新的消息
        List<SessionMessageEntity> messages = queryService.getTaskMessages(
                taskId, id2, 50);
        assertTrue(messages.isEmpty());
    }

    @Test
    void getTaskMessages_shouldReturnEmptyForNoMessages() {
        List<SessionMessageEntity> messages = queryService.getTaskMessages(
                "nonexistent-task", null, 50);
        assertTrue(messages.isEmpty());
    }

    // ── contextId → sessionId 一对一约束 ──

    @Test
    void contextToSession_shouldBeOneToOneStable() {
        // 第一次查询
        String sid1 = queryService.resolveSessionId(contextId, USER_ID).orElse(null);
        // 第二次查询
        String sid2 = queryService.resolveSessionId(contextId, USER_ID).orElse(null);

        assertNotNull(sid1);
        assertEquals(sid1, sid2, "contextId → sessionId 映射应保持稳定");
    }

    // ── 辅助方法 ──

    private String addMessage(String sessionId, String taskId, MessageRole role, String content) {
        return addMessage(sessionId, taskId, role, content,
                Map.of("type", role == MessageRole.USER ? "USER" : "TEXT_COMPLETE"));
    }

    private String addMessage(String sessionId, String taskId, MessageRole role, String content,
                              Map<String, Object> metadata) {
        Message msg = Message.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .taskId(taskId)
                .role(role)
                .content(content)
                .metadata(metadata)
                .build();
        return sessionManager.addMessage(sessionId, msg);
    }
}
