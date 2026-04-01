package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    @Mock
    private SessionTaskRepository sessionTaskRepository;
    @Mock
    private SessionEntityRepository sessionEntityRepository;

    private CodexTaskService service;

    @BeforeEach
    void setUp() {
        service = new CodexTaskService(taskRepository, workerManagementFacade, eventPublisher);
        ReflectionTestUtils.setField(service, "llmModelManager", llmModelManager);
        ReflectionTestUtils.setField(service, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(service, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(service, "sessionEntityRepository", sessionEntityRepository);

        lenient().when(sessionTaskRepository.findByTaskId(anyString())).thenReturn(Optional.empty());
        lenient().when(sessionTaskRepository.save(any(SessionTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(sessionEntityRepository.findById(anyString())).thenAnswer(invocation -> {
            SessionEntity session = new SessionEntity();
            session.setId(invocation.getArgument(0));
            return Optional.of(session);
        });
        lenient().when(sessionEntityRepository.save(any(SessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void resolveCodexAuth_returnsEmptyWhenNoApiKey() {
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setWorkerBackend("OPENAI_CODEX");
        config.setBaseUrl(null);

        when(llmModelManager.getModelConfig("cfg-1")).thenReturn(Optional.of(config));
        when(llmModelManager.getDecryptedApiKey("cfg-1")).thenReturn(null);

        Object result = ReflectionTestUtils.invokeMethod(service, "resolveCodexAuth", "cfg-1");

        assertNotNull(result);
        // CodexAuthResult record — access via reflection
        assertNull(ReflectionTestUtils.invokeMethod(result, "apiKey"));
        assertNull(ReflectionTestUtils.invokeMethod(result, "baseUrl"));
    }

    @Test
    void resolveCodexAuth_returnsApiKeyAndBaseUrl() {
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setWorkerBackend("OPENAI_CODEX");
        config.setBaseUrl("https://api.openai.com/v1");

        when(llmModelManager.getModelConfig("cfg-2")).thenReturn(Optional.of(config));
        when(llmModelManager.getDecryptedApiKey("cfg-2")).thenReturn("sk-live");

        Object result = ReflectionTestUtils.invokeMethod(service, "resolveCodexAuth", "cfg-2");

        assertNotNull(result);
        assertEquals("sk-live", ReflectionTestUtils.invokeMethod(result, "apiKey"));
        assertEquals("https://api.openai.com/v1", ReflectionTestUtils.invokeMethod(result, "baseUrl"));
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
        // providerStateJson 中存储 codexThreadId（resume 从此恢复）
        SessionEntity sessionWithState = new SessionEntity();
        sessionWithState.setId("session-1");
        sessionWithState.setProviderStateJson("{\"codexThreadId\":\"thread-1\"}");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(sessionWithState));

        DispatchTaskDTO result = service.resumeTask("user-1", "tenant-1", Map.of(
                "workerId", "worker-1",
                "sessionId", "session-1",
                "prompt", "continue please",
                "images", "[{\"name\":\"screen.png\",\"data\":\"YmFzZTY0\",\"mime_type\":\"image/png\"}]",
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
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                "session-1".equals(entity.getSessionId())
                        && "codex-worker".equals(entity.getProviderType())
                        && entity.getTaskStateJson() != null
                        && entity.getTaskStateJson().contains("thread-1")
        ));
        verify(sessionEntityRepository).save(argThat((SessionEntity entity) ->
                "session-1".equals(entity.getId())
                        && "codex-worker".equals(entity.getProviderType())
                        && "worker-1".equals(entity.getCurrentWorkerId())
        ));
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "session-1".equals(event.getSessionId())
                        && "worker-1".equals(event.getWorkerId())
                        && "continue please".equals(event.getPrompt())
                        && "[{\"name\":\"screen.png\",\"data\":\"YmFzZTY0\",\"mime_type\":\"image/png\"}]"
                        .equals(event.getProviderConfigString("images"))
                        && "thread-1".equals(event.getProviderConfigString("codexThreadId"))
        ));
    }

    @Test
    void createTaskDirect_normalizesWindowsBackslashCwd() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        DispatchTaskDTO result = service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "cwd", "D:\\projects\\my-app",
                "directoryId", "dir-1",
                "model", "gpt-5.4"
        ), "user-1", "tenant-1");

        // cwd 反斜杠应被转为正斜杠（Codex CLI 不接受 Windows 反斜杠）
        assertEquals("D:/projects/my-app", savedTask[0].getCwd());
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "D:/projects/my-app".equals(event.getCwd())
        ));
    }

    @Test
    void createTaskDirect_forwardsImagesToProviderConfig() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "describe screenshot",
                "images", "[{\"name\":\"screen.png\",\"data\":\"YmFzZTY0\",\"mime_type\":\"image/png\"}]"
        ), "user-1", "tenant-1");

        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "[{\"name\":\"screen.png\",\"data\":\"YmFzZTY0\",\"mime_type\":\"image/png\"}]"
                        .equals(event.getProviderConfigString("images"))
        ));
    }

    @Test
    void createTaskDirect_forwardSlashCwdUnchanged() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "cwd", "D:/tmp",
                "model", "gpt-5.4"
        ), "user-1", "tenant-1");

        assertEquals("D:/tmp", savedTask[0].getCwd());
    }

    @Test
    void createTaskDirect_doesNotTreatDirectoryIdAsLogicalAgentId() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        when(sessionManager.createSession(any())).thenReturn("session-new");

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "directoryId", "dir-1"
        ), "user-1", "tenant-1");

        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                "session-new".equals(entity.getSessionId())
                        && "codex-worker".equals(entity.getProviderType())
                        && "codex-worker".equals(entity.getAgentId())
                        && !"dir-1".equals(entity.getAgentId())
        ));
        verify(sessionEntityRepository).save(argThat((SessionEntity entity) ->
                "session-new".equals(entity.getId())
                        && "codex-worker".equals(entity.getProviderType())
                        && "codex-worker".equals(entity.getAgentId())
                        && !"dir-1".equals(entity.getAgentId())
        ));
    }

    @Test
    void resumeTask_reusesLogicalAgentIdFromExistingSessionWhenRequestOmitsAgentId() {
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
        SessionEntity existingSession = new SessionEntity();
        existingSession.setId("session-1");
        existingSession.setUserId("user-1");
        existingSession.setAgentId("agent-codex-1");
        existingSession.setProviderType("codex-worker");
        existingSession.setProviderStateJson("{\"codexThreadId\":\"thread-1\"}");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(existingSession));

        DispatchTaskDTO result = service.resumeTask("user-1", "tenant-1", Map.of(
                "workerId", "worker-1",
                "sessionId", "session-1",
                "prompt", "continue"
        ));

        assertEquals("agent-codex-1", result.getAgentId());
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                "session-1".equals(entity.getSessionId())
                        && "agent-codex-1".equals(entity.getAgentId())
        ));
    }

    @Test
    void getTaskById_recoversLogicalAgentIdFromUnifiedSessionStore() {
        CodexTaskEntity entity = createTask(
                "task-1", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 3, 26, 10, 0)
        );
        entity.setResolvedAgentId(null);
        when(taskRepository.findByTaskId("task-1")).thenReturn(Optional.of(entity));

        SessionTaskEntity sessionTask = new SessionTaskEntity();
        sessionTask.setTaskId("task-1");
        sessionTask.setSessionId("session-1");
        sessionTask.setAgentId("agent-codex-1");
        when(sessionTaskRepository.findByTaskId("task-1")).thenReturn(Optional.of(sessionTask));

        DispatchTaskDTO dto = service.getTaskById("task-1").orElseThrow();

        assertEquals("agent-codex-1", dto.getAgentId());
        assertEquals("codex-worker", dto.getProviderType());
    }

    @Test
    void completeTask_publishesTaskStatusChangeEvent() {
        CodexTaskEntity entity = createTask(
                "task-1", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 3, 26, 10, 0)
        );
        when(taskRepository.findByTaskId("task-1")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.completeTask("task-1", "worker-task-1", "thread-1", "done",
                null, null, null, null, null, "gpt-5.4");

        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-1".equals(event.getTaskId())
                        && "session-1".equals(event.getSessionId())
                        && "user-1".equals(event.getUserId())
                        && "codex-worker".equals(event.getAgentId())
                        && "RUNNING".equals(event.getPreviousStatus())
                        && "COMPLETED".equals(event.getStatus())
                        && "AWAITING_REPLY".equals(event.getInteractionState())
        ));
    }

    @Test
    void failTask_publishesTaskStatusChangeEventWithError() {
        CodexTaskEntity entity = createTask(
                "task-2", "session-2", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 3, 26, 11, 0)
        );
        when(taskRepository.findByTaskId("task-2")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.failTask("task-2", "worker-task-2", "thread-2", "worker timeout");

        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-2".equals(event.getTaskId())
                        && "FAILED".equals(event.getStatus())
                        && "RUNNING".equals(event.getPreviousStatus())
                        && "worker timeout".equals(event.getErrorMessage())
                        && "AWAITING_REPLY".equals(event.getInteractionState())
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
