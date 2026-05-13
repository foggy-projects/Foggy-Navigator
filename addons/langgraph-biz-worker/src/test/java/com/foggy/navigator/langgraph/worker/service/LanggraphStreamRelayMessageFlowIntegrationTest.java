package com.foggy.navigator.langgraph.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.session.event.SessionEventListener;
import com.foggy.navigator.session.service.JpaSessionManager;
import com.foggy.navigator.session.service.OpenApiSessionQueryService;
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
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = LanggraphStreamRelayMessageFlowIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
class LanggraphStreamRelayMessageFlowIntegrationTest {

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
    private JpaSessionManager sessionManager;

    @Autowired
    private OpenApiSessionQueryService queryService;

    @Autowired
    private SessionEventListener sessionEventListener;

    @Autowired
    private ObjectMapper objectMapper;

    private LanggraphTaskService taskService;
    private LanggraphStreamRelay relay;
    private String sessionId;
    private String taskId;

    @BeforeEach
    void setUp() {
        taskService = mock(LanggraphTaskService.class);
        relay = new LanggraphStreamRelay(
                mock(LanggraphWorkerService.class),
                taskService,
                sessionEventListener,
                objectMapper
        );
        taskId = "lgt-" + UUID.randomUUID().toString().substring(0, 8);
        sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .agentId("skill-1")
                .providerType(LanggraphTaskService.PROVIDER_TYPE)
                .taskName("langgraph message flow")
                .build());
    }

    @Test
    void toolMessagesShouldBeVisibleByCursorBeforeTaskCompletes() throws Exception {
        String cursor = invokeEvent("""
                {
                  "type": "system",
                  "content": "started"
                }
                """).getId();

        invokeEvent("""
                {
                  "type": "skill_frame_open",
                  "content": "open skill",
                  "skill_frame_id": "frame-101",
                  "parent_frame_id": "frame-parent",
                  "skill_id": "skill-1"
                }
                """);
        invokeEvent("""
                {
                  "type": "tool_use",
                  "tool_name": "tms.dataset.listModels",
                  "tool_call_id": "call-101",
                  "function_id": "fn-list-models",
                  "skill_frame_id": "frame-101",
                  "parent_frame_id": "frame-parent",
                  "skill_id": "skill-1",
                  "args": {"namespace": "tms"}
                }
                """);
        invokeEvent("""
                {
                  "type": "tool_result",
                  "content": "{\\"ok\\":true,\\"count\\":2}",
                  "tool_name": "tms.dataset.listModels",
                  "tool_call_id": "call-101",
                  "function_id": "fn-list-models",
                  "skill_frame_id": "frame-101",
                  "parent_frame_id": "frame-parent",
                  "skill_id": "skill-1"
                }
                """);

        List<SessionMessageEntity> runningIncrement =
                queryService.getTaskMessages(taskId, cursor, 50);

        assertTrue(runningIncrement.stream().anyMatch(this::isToolCallStart),
                "TOOL_CALL_START should be visible from cursor before task completion");
        assertTrue(runningIncrement.stream().anyMatch(this::isToolCallResult),
                "TOOL_CALL_RESULT should be visible from cursor before task completion");
        assertTrue(runningIncrement.stream()
                        .filter(message -> isToolCallStart(message) || isToolCallResult(message))
                        .allMatch(message -> message.getMetadata().contains("\"skillFrameId\":\"frame-101\"")),
                "tool messages should retain skillFrameId for frame grouping");
        assertTrue(runningIncrement.stream()
                        .filter(message -> isToolCallStart(message) || isToolCallResult(message))
                        .allMatch(message -> message.getMetadata().contains("\"parentFrameId\":\"frame-parent\"")),
                "tool messages should retain parentFrameId for frame hierarchy");

        AtomicReference<List<SessionMessageEntity>> messagesAtComplete = new AtomicReference<>();
        doAnswer(invocation -> {
            messagesAtComplete.set(queryService.getTaskMessages(taskId, null, 50));
            return null;
        }).when(taskService).completeTask(eq(taskId), eq("done"), eq("{\"ok\":true}"), eq(123L));

        invokeEvent("""
                {
                  "type": "skill_frame_close",
                  "content": "close skill",
                  "skill_frame_id": "frame-101",
                  "parent_frame_id": "frame-parent",
                  "skill_id": "skill-1"
                }
                """);
        invokeEvent("""
                {
                  "type": "result",
                  "content": "done",
                  "duration_ms": 123,
                  "structured_output": {"ok": true}
                }
                """);

        assertNotNull(messagesAtComplete.get(), "completeTask should be called");
        assertTrue(messagesAtComplete.get().stream().anyMatch(this::isToolCallStart));
        assertTrue(messagesAtComplete.get().stream().anyMatch(this::isToolCallResult));
        assertTrue(messagesAtComplete.get().stream().anyMatch(this::isTaskCompleted),
                "TASK_COMPLETED should be persisted before task status is completed");
    }

    private SessionMessageEntity invokeEvent(String data) throws Exception {
        Method method = LanggraphStreamRelay.class.getDeclaredMethod(
                "handleEvent",
                ServerSentEvent.class,
                String.class,
                String.class
        );
        method.setAccessible(true);
        method.invoke(relay, ServerSentEvent.<String>builder().data(data).build(), taskId, sessionId);

        List<SessionMessageEntity> messages = queryService.getTaskMessages(taskId, null, 50);
        assertTrue(!messages.isEmpty());
        return messages.get(messages.size() - 1);
    }

    private boolean isToolCallStart(SessionMessageEntity message) {
        return message.getMetadata() != null
                && message.getMetadata().contains("\"type\":\"TOOL_CALL_START\"");
    }

    private boolean isToolCallResult(SessionMessageEntity message) {
        return message.getMetadata() != null
                && message.getMetadata().contains("\"type\":\"TOOL_CALL_RESULT\"");
    }

    private boolean isTaskCompleted(SessionMessageEntity message) {
        return message.getMetadata() != null
                && message.getMetadata().contains("\"type\":\"TASK_COMPLETED\"");
    }
}
