package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CodexTaskServiceTest {

    @Mock
    private CodexTaskRepository taskRepository;
    @Mock
    private WorkerManagementFacade workerManagementFacade;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private LlmModelManager llmModelManager;
    @Mock
    private SessionManager sessionManager;

    private CodexTaskService service;

    @BeforeEach
    void setUp() {
        service = new CodexTaskService(taskRepository, workerManagementFacade, eventPublisher);
        ReflectionTestUtils.setField(service, "llmModelManager", llmModelManager);
        ReflectionTestUtils.setField(service, "sessionManager", sessionManager);
    }

    @Test
    void resolveApiKey_returnsNullForCodexSubscriptionMode() {
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setWorkerBackend("OPENAI_CODEX");
        config.setBaseUrl(null);

        when(llmModelManager.getModelConfig("cfg-1")).thenReturn(Optional.of(config));

        Object result = ReflectionTestUtils.invokeMethod(service, "resolveApiKey", "cfg-1");

        assertNull(result);
    }

    @Test
    void resolveApiKey_returnsDecryptedKeyForNonSubscriptionConfig() {
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setWorkerBackend("OPENAI_CODEX");
        config.setBaseUrl("https://api.openai.com/v1");

        when(llmModelManager.getModelConfig("cfg-2")).thenReturn(Optional.of(config));
        when(llmModelManager.getDecryptedApiKey("cfg-2")).thenReturn("sk-live");

        Object result = ReflectionTestUtils.invokeMethod(service, "resolveApiKey", "cfg-2");

        assertEquals("sk-live", result);
    }

    @Test
    void listTasksPaged_groupsCodexTasksBySessionAndSupportsInteractionStateFilter() {
        CodexTaskEntity running = createTask(
                "task-running", "session-running", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 3, 24, 22, 0)
        );
        CodexTaskEntity completed = createTask(
                "task-completed", "session-completed", "worker-1", "dir-1", "COMPLETED",
                LocalDateTime.of(2026, 3, 24, 21, 0)
        );

        when(taskRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(running, completed));

        Object result = service.listTasksPaged("user-1", 0, 20, "PROCESSING");

        Map<?, ?> page = assertInstanceOf(Map.class, result);
        assertEquals(1L, page.get("totalSessions"));
        List<?> content = assertInstanceOf(List.class, page.get("content"));
        assertEquals(1, content.size());
        DispatchTaskDTO task = assertInstanceOf(DispatchTaskDTO.class, content.get(0));
        assertEquals("task-running", task.getTaskId());
        assertEquals("session-running", task.getSessionId());
    }

    @Test
    void resumeTask_reusesExistingPlatformSessionAndCodexThread() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        when(taskRepository.existsByCodexThreadIdAndWorkerIdAndUserId("thread-1", "worker-1", "user-1"))
                .thenReturn(true);
        when(taskRepository.existsByCodexThreadIdAndWorkerIdAndUserIdAndStatus("thread-1", "worker-1", "user-1", "RUNNING"))
                .thenReturn(false);
        when(sessionManager.getSession("session-1")).thenReturn(Session.builder()
                .id("session-1")
                .userId("user-1")
                .build());

        DispatchTaskDTO result = service.resumeTask("user-1", "tenant-1", Map.of(
                "workerId", "worker-1",
                "sessionId", "session-1",
                "prompt", "continue please",
                "codexThreadId", "thread-1",
                "directoryId", "dir-1",
                "cwd", "/repo"
        ));

        assertEquals("session-1", result.getSessionId());
        assertEquals("thread-1", result.getCodexThreadId());
        assertEquals("RUNNING", result.getStatus());

        verify(sessionManager).addMessage(eq("session-1"), any(Message.class));
        verify(taskRepository).save(argThat((CodexTaskEntity entity) ->
                "session-1".equals(entity.getSessionId())
                        && "thread-1".equals(entity.getCodexThreadId())
                        && "worker-1".equals(entity.getWorkerId())
                        && "continue please".equals(entity.getPrompt())
        ));
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "session-1".equals(event.getSessionId())
                        && "worker-1".equals(event.getWorkerId())
                        && "continue please".equals(event.getPrompt())
                        && "thread-1".equals(event.getProviderConfigString("codexThreadId"))
        ));
    }

    private CodexTaskEntity createTask(String taskId, String sessionId, String workerId,
                                       String directoryId, String status, LocalDateTime createdAt) {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setWorkerId(workerId);
        entity.setDirectoryId(directoryId);
        entity.setUserId("user-1");
        entity.setPrompt(taskId + " prompt");
        entity.setStatus(status);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt.plusMinutes(1));
        return entity;
    }
}
