package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskDispatchFacadeTest {

    @Mock
    private UnifiedAgentResolver agentResolver;
    @Mock
    private SessionBindingService bindingService;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private A2aAgent agent;
    @Mock
    private TaskQueryProvider taskQueryProvider;
    @Mock
    private LlmModelManager llmModelManager;
    @Mock
    private SessionTaskRepository sessionTaskRepository;
    @Mock
    private WorkingDirectoryRepository workingDirectoryRepository;

    private TaskDispatchFacade facade;

    @BeforeEach
    void setUp() {
        facade = new TaskDispatchFacade(agentResolver, bindingService, sessionRepository,
                List.of(taskQueryProvider), llmModelManager);
    }

    @Test
    void createTask_prefersDirectoryLookupAndBindsResolvedAgentId() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("hi")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        when(agentResolver.resolveAgent(eq("dir-2"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("dir-2"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-2").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("agent-2", result.getAgentId());
        verify(agentResolver).resolveAgent(eq("dir-2"), any());
        verify(agentResolver, never()).resolveAgent(eq("worker-1"), any());
        verify(bindingService).getOrBind("session-1", "agent-2", "claude-worker", "DIRECTORY_ID");
    }

    @Test
    void createTask_usesDirectProviderRouteWhenModelConfigTargetsCodex() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("hi")
                .model("gpt-5.4")
                .modelConfigId("cfg-codex")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-1")
                .providerType("codex-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .build();

        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-1", result.getTaskId());
        verify(taskQueryProvider).createTaskDirect(any(), eq("user-1"), eq("tenant-1"));
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void listTasksPaged_aggregatesSessionsAcrossProviders() {
        TaskQueryProvider claudeProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        facade = new TaskDispatchFacade(agentResolver, bindingService, sessionRepository,
                List.of(claudeProvider, codexProvider), llmModelManager);

        DispatchTaskDTO claudeTask = DispatchTaskDTO.builder()
                .taskId("task-claude-1")
                .sessionId("session-claude-1")
                .workerId("worker-1")
                .createdAt(LocalDateTime.of(2026, 3, 24, 21, 0))
                .build();
        DispatchTaskDTO codexTask = DispatchTaskDTO.builder()
                .taskId("task-codex-1")
                .sessionId("session-codex-1")
                .workerId("worker-1")
                .createdAt(LocalDateTime.of(2026, 3, 24, 22, 0))
                .build();

        when(claudeProvider.listTasksPaged("user-1", 0, 20, null)).thenReturn(Map.of(
                "content", List.of(claudeTask),
                "totalSessions", 1L,
                "page", 0,
                "size", 20
        ));
        when(codexProvider.listTasksPaged("user-1", 0, 20, null)).thenReturn(Map.of(
                "content", List.of(codexTask),
                "totalSessions", 1L,
                "page", 0,
                "size", 20
        ));

        Object result = facade.listTasksPaged("user-1", 0, 20, null);

        Map<?, ?> page = assertInstanceOf(Map.class, result);
        assertEquals(2L, page.get("totalSessions"));
        List<?> content = assertInstanceOf(List.class, page.get("content"));
        assertEquals(2, content.size());
        assertEquals("task-codex-1", ((DispatchTaskDTO) content.get(0)).getTaskId());
        assertEquals("task-claude-1", ((DispatchTaskDTO) content.get(1)).getTaskId());
    }

    @Test
    void resumeTask_routesCodexThreadResumeToProvider() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-1")
                .prompt("continue")
                .codexThreadId("thread-1")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-codex-2")
                .sessionId("session-1")
                .codexThreadId("thread-1")
                .providerType("codex-worker")
                .build();

        when(agentResolver.getProviderType(eq("worker-1"), any())).thenReturn(Optional.of("codex-worker"));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-codex-2", result.getTaskId());
        assertEquals("thread-1", result.getCodexThreadId());
        verify(taskQueryProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "thread-1".equals(params.get("codexThreadId"))
                        && "session-1".equals(params.get("sessionId"))
                        && "continue".equals(params.get("prompt"))));
    }

    @Test
    void listTasksPaged_prefersUnifiedSessionStoreWhenAvailable() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(facade, "workingDirectoryRepository", workingDirectoryRepository);

        SessionTaskEntity claudeTask = sessionTask(
                "task-claude-1", "session-claude-1", "claude-worker", "worker-1", "dir-1",
                "RUNNING", LocalDateTime.of(2026, 3, 24, 21, 0), "{\"claudeSessionId\":\"claude-session-1\"}"
        );
        SessionTaskEntity codexTask = sessionTask(
                "task-codex-1", "session-codex-1", "codex-worker", "worker-1", "dir-2",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"codexThreadId\":\"thread-1\"}"
        );

        when(sessionTaskRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(codexTask, claudeTask));
        when(sessionRepository.findAllById(List.of("session-codex-1", "session-claude-1")))
                .thenReturn(List.of(
                        sessionEntity("session-codex-1", "user-1", "AWAITING_REPLY", LocalDateTime.of(2026, 3, 24, 22, 5)),
                        sessionEntity("session-claude-1", "user-1", "PROCESSING", LocalDateTime.of(2026, 3, 24, 21, 5))
                ));
        when(workingDirectoryRepository.findByDirectoryIdIn(List.of("dir-2", "dir-1")))
                .thenReturn(List.of(
                        directoryEntity("dir-2", "Codex Project"),
                        directoryEntity("dir-1", "Claude Project")
                ));

        Object result = facade.listTasksPaged("user-1", 0, 20, null);

        Map<?, ?> page = assertInstanceOf(Map.class, result);
        assertEquals(2L, page.get("totalSessions"));
        List<?> content = assertInstanceOf(List.class, page.get("content"));
        assertEquals(2, content.size());
        DispatchTaskDTO first = assertInstanceOf(DispatchTaskDTO.class, content.get(0));
        DispatchTaskDTO second = assertInstanceOf(DispatchTaskDTO.class, content.get(1));
        assertEquals("task-codex-1", first.getTaskId());
        assertEquals("thread-1", first.getCodexThreadId());
        assertEquals("Codex Project", first.getDirectoryName());
        assertEquals("task-claude-1", second.getTaskId());
        assertEquals("claude-session-1", second.getClaudeSessionId());
        assertEquals("Claude Project", second.getDirectoryName());
        verify(taskQueryProvider, never()).listTasksPaged(anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void searchSessions_prefersUnifiedSessionStoreWhenAvailable() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        SessionTaskEntity matchingTask = sessionTask(
                "task-claude-1", "session-claude-1", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 21, 0), "{\"claudeSessionId\":\"claude-session-1\"}"
        );
        matchingTask.setPrompt("Fix auth flow");
        matchingTask.setResultText("Auth flow fixed");
        matchingTask.setCostUsd(new BigDecimal("1.250000"));

        SessionTaskEntity otherTask = sessionTask(
                "task-codex-1", "session-codex-1", "codex-worker", "worker-2", "dir-2",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 20, 0), "{\"codexThreadId\":\"thread-1\"}"
        );
        otherTask.setPrompt("Unrelated prompt");

        when(sessionTaskRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(matchingTask, otherTask));
        when(sessionRepository.findAllById(List.of("session-claude-1", "session-codex-1")))
                .thenReturn(List.of(
                        sessionEntity("session-claude-1", "user-1", "AWAITING_REPLY", LocalDateTime.of(2026, 3, 24, 21, 10), "Auth Session", "[\"auth\",\"backend\"]"),
                        sessionEntity("session-codex-1", "user-1", "AWAITING_REPLY", LocalDateTime.of(2026, 3, 24, 20, 10), "Other", "[\"misc\"]")
                ));

        Object result = facade.searchSessions("user-1", "auth", null, null, 0, 20);

        Map<?, ?> page = assertInstanceOf(Map.class, result);
        assertEquals(1L, page.get("total"));
        List<?> results = assertInstanceOf(List.class, page.get("results"));
        Map<?, ?> first = assertInstanceOf(Map.class, results.get(0));
        assertEquals("session-claude-1", first.get("sessionId"));
        assertEquals("Auth Session", first.get("customTitle"));
        assertEquals("task-claude-1", first.get("latestTaskId"));
        assertEquals(new BigDecimal("1.250000"), first.get("totalCost"));
        verify(taskQueryProvider, never()).searchSessions(anyString(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void getTask_prefersUnifiedSessionStoreWhenAvailable() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-codex-1", "session-codex-1", "codex-worker", "worker-1", "dir-2",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"codexThreadId\":\"thread-1\"}"
        );

        when(sessionTaskRepository.findByTaskIdAndUserId("task-codex-1", "user-1"))
                .thenReturn(Optional.of(task));

        Optional<DispatchTaskDTO> result = facade.getTask("task-codex-1", AgentResolveContext.builder()
                .userId("user-1")
                .build());

        assertEquals(true, result.isPresent());
        assertEquals("thread-1", result.orElseThrow().getCodexThreadId());
        verify(taskQueryProvider, never()).getTaskByIdAndUser(anyString(), anyString());
    }

    @Test
    void respondToTask_routesViaUnifiedSessionStoreProviderType() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-codex-1", "session-codex-1", "codex-worker", "worker-1", "dir-2",
                "AWAITING_PERMISSION", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"codexThreadId\":\"thread-1\"}"
        );
        when(sessionTaskRepository.findByTaskId("task-codex-1")).thenReturn(Optional.of(task));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");

        facade.respondToTask("task-codex-1", "user-1", Map.of("decision", "approve"));

        verify(taskQueryProvider).respondToTask("task-codex-1", "user-1", Map.of("decision", "approve"));
        verify(taskQueryProvider, never()).getTaskById("task-codex-1");
    }

    @Test
    void rewindTask_routesViaUnifiedSessionStoreProviderTypeAndReturnsProviderPayload() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-claude-1", "session-claude-1", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"claudeSessionId\":\"claude-session-1\"}"
        );
        when(sessionTaskRepository.findByTaskId("task-claude-1")).thenReturn(Optional.of(task));
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.rewindTask(eq("task-claude-1"), eq("user-1"), any()))
                .thenReturn(Map.of("status", "rewound", "taskId", "task-claude-1"));

        Object result = facade.rewindTask("task-claude-1", "user-1", Map.of("mode", "conversation_fork"));

        assertEquals(Map.of("status", "rewound", "taskId", "task-claude-1"), result);
        verify(taskQueryProvider).rewindTask("task-claude-1", "user-1", Map.of("mode", "conversation_fork"));
        verify(taskQueryProvider, never()).getTaskById("task-claude-1");
    }

    private SessionTaskEntity sessionTask(String taskId, String sessionId, String providerType,
                                          String workerId, String directoryId, String status,
                                          LocalDateTime createdAt, String taskStateJson) {
        SessionTaskEntity entity = new SessionTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setProviderType(providerType);
        entity.setWorkerId(workerId);
        entity.setDirectoryId(directoryId);
        entity.setUserId("user-1");
        entity.setPrompt(taskId + " prompt");
        entity.setStatus(status);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt.plusMinutes(1));
        entity.setTaskStateJson(taskStateJson);
        return entity;
    }

    private SessionEntity sessionEntity(String sessionId, String userId, String interactionState, LocalDateTime lastActivityAt) {
        return sessionEntity(sessionId, userId, interactionState, lastActivityAt, null, null);
    }

    private SessionEntity sessionEntity(String sessionId, String userId, String interactionState,
                                        LocalDateTime lastActivityAt, String title, String tagsJson) {
        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId(userId);
        entity.setInteractionState(interactionState);
        entity.setLastActivityAt(lastActivityAt);
        entity.setUpdatedAt(lastActivityAt);
        entity.setTitle(title);
        entity.setTagsJson(tagsJson);
        return entity;
    }

    private WorkingDirectoryEntity directoryEntity(String directoryId, String projectName) {
        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(directoryId);
        entity.setProjectName(projectName);
        return entity;
    }

    // ── New tests ──

    @Test
    void createTask_agentNotFound_throwsException() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-missing")
                .prompt("hi")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        when(agentResolver.resolveAgent(eq("worker-missing"), any())).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> facade.createTask(request, context));
    }

    @Test
    void getTask_notFoundInAllSources_returnsEmpty() {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .build();

        // sessionTaskRepository is null (not injected)
        when(taskQueryProvider.getTaskByIdAndUser("task-missing", "user-1")).thenReturn(Optional.empty());

        Optional<DispatchTaskDTO> result = facade.getTask("task-missing", context);

        assertTrue(result.isEmpty());
    }

    @Test
    void cancelTask_routesViaSessionStore() {
        // cancelTask resolves agent through agentResolver, then calls agent.cancelTask
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));

        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        facade.cancelTask("task-cancel-1", "agent-1", context);

        verify(agent).cancelTask("task-cancel-1");
    }

    @Test
    void listWorkerSessions_delegatesToProvider() {
        List<Map<String, Object>> sessions = List.of(
                Map.of("sessionId", "s1", "status", "active"),
                Map.of("sessionId", "s2", "status", "completed")
        );
        when(taskQueryProvider.listWorkerSessions("worker-1", "user-1")).thenReturn(sessions);

        List<Map<String, Object>> result = facade.listWorkerSessions("worker-1", "user-1");

        assertEquals(2, result.size());
        assertEquals("s1", result.get(0).get("sessionId"));
    }

    @Test
    void syncWorkerSessions_delegatesToProvider() {
        Map<String, Object> syncResult = Map.of("synced", 5, "workerId", "worker-1");
        when(taskQueryProvider.syncWorkerSessions("worker-1", "user-1", "tenant-1")).thenReturn(syncResult);

        Map<String, Object> result = facade.syncWorkerSessions("worker-1", "user-1", "tenant-1");

        assertEquals(5, result.get("synced"));
        verify(taskQueryProvider).syncWorkerSessions("worker-1", "user-1", "tenant-1");
    }

    @Test
    void listTasksByDirectory_prefersUnifiedStore() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-dir-1", "session-1", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 21, 0), "{\"claudeSessionId\":\"cs-1\"}"
        );
        when(sessionTaskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc("dir-1", "user-1"))
                .thenReturn(List.of(task));

        List<DispatchTaskDTO> result = facade.listTasksByDirectory("user-1", "dir-1");

        assertEquals(1, result.size());
        assertEquals("task-dir-1", result.get(0).getTaskId());
        verify(taskQueryProvider, never()).listTasksByDirectory(anyString(), anyString());
    }
}
