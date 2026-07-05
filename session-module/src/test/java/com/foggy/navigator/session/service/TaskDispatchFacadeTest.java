package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.AgentConversationContextRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.agent.WorkerSessionMessage;
import com.foggy.navigator.spi.agent.WorkerSessionMessageCount;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import com.foggy.navigator.spi.agent.WorkerSessionSummary;
import com.foggy.navigator.spi.agent.WorkerSessionSyncResult;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @Mock
    private AgentConversationContextRepository agentConversationContextRepository;

    private TaskDispatchFacade facade;

    @BeforeEach
    void setUp() {
        facade = createFacade(List.of(taskQueryProvider));
    }

    private TaskDispatchFacade createFacade(List<? extends TaskQueryProvider> providers) {
        return new TaskDispatchFacade(
                agentResolver,
                bindingService,
                sessionRepository,
                providers,
                providers,
                providers,
                providers,
                llmModelManager);
    }

    @Test
    void createTask_reusesSessionBoundAgentWhenAgentIdOmitted() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .workerId("worker-1")
                .prompt("hi")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setAgentId("agent-2");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        when(agentResolver.resolveAgent(eq("agent-2"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-2"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-2").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("agent-2", result.getAgentId());
        verify(agentResolver).resolveAgent(eq("agent-2"), any());
        verify(bindingService).getOrBind("session-1", "agent-2", "claude-worker", "SESSION_AGENT");
    }

    @Test
    void createTask_imagesPassedAsStringNotListInA2aMessage() {
        // Bug: buildMessage() 曾直接将 List<String> 放入 metadata，
        // 导致 ClaudeWorkerA2aAgent 用 (String) 强转时 ClassCastException。
        // 修复后应转为 String（JSON）传递。
        String imageJson = "[{\"name\":\"screenshot.webp\",\"data\":\"base64...\",\"mime_type\":\"image/webp\"}]";
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("agent-1")
                .workerId("worker-1")
                .prompt("看图")
                .images(List.of(imageJson))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-1").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-img-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        facade.createTask(request, context);

        // 验证传给 agent.sendTask() 的 A2aMessage 中 images 是 String 类型
        var messageCaptor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(messageCaptor.capture());
        A2aMessage captured = messageCaptor.getValue();
        Object imagesValue = captured.getMetadata().get("images");
        assertNotNull(imagesValue, "images should be present in metadata");
        assertInstanceOf(String.class, imagesValue,
                "images in A2aMessage metadata must be String, not List, to avoid ClassCastException in downstream consumers");
        assertEquals(imageJson, imagesValue);
    }

    @Test
    void submitTask_routesThroughCreateTaskAndPreservesA2aMetadata() {
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(com.foggy.navigator.common.dto.a2a.A2aPart.text("run smoke")))
                .contextId("ctx-1")
                .metadata(Map.of(
                        "runtimeContext", Map.of("task_scoped_token", "token-1"),
                        "modelConfigId", "cfg-1"))
                .build();
        AgentTaskSubmitRequest request = AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .resolveContext(AgentResolveContext.builder()
                        .userId("user-1")
                        .tenantId("tenant-1")
                        .modelConfigId("cfg-1")
                        .requestSource("OPEN_API")
                        .build())
                .message(message)
                .prompt("run smoke")
                .workerId("worker-1")
                .directoryId("dir-1")
                .modelConfigId("cfg-1")
                .model("codex-latest")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-1"), any())).thenReturn(Optional.of("codex-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-1").build());
        when(llmModelManager.getModelConfig("cfg-1")).thenReturn(Optional.of(modelConfig));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .metadata(Map.of("sessionId", "session-1", "modelConfigId", "cfg-1"))
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        A2aTask result = facade.submitTask(request);

        assertEquals("task-1", result.getId());
        assertEquals("ctx-1", result.getContextId());
        assertEquals("session-1", result.getMetadata().get("sessionId"));
        assertEquals("cfg-1", result.getMetadata().get("modelConfigId"));
        var messageCaptor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(messageCaptor.capture());
        assertEquals("token-1",
                ((Map<?, ?>) messageCaptor.getValue().getMetadata().get("runtimeContext")).get("task_scoped_token"));
        assertEquals("worker-1", messageCaptor.getValue().getMetadata().get("workerId"));
        assertEquals("dir-1", messageCaptor.getValue().getMetadata().get("directoryId"));
    }

    @Test
    void submitTask_persistsRecoveryCorrelationMetadataToTaskState() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(com.foggy.navigator.common.dto.a2a.A2aPart.text("recover task")))
                .contextId("ctx-1")
                .metadata(Map.of(
                        "originalTaskId", "task-original",
                        "recoveryCorrelationKey", "world-run/contract-1",
                        "attemptNumber", 2,
                        "idempotencyKey", "idem-1"))
                .build();
        AgentTaskSubmitRequest request = AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .resolveContext(AgentResolveContext.builder()
                        .userId("user-1")
                        .tenantId("tenant-1")
                        .requestSource("OPEN_API")
                        .build())
                .message(message)
                .prompt("recover task")
                .contextId("ctx-1")
                .build();
        SessionTaskEntity entity = sessionTask(
                "task-recovery-1", "session-1", "claude-worker", "worker-1", "dir-1",
                "RUNNING", LocalDateTime.of(2026, 5, 27, 9, 0), "{}");

        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-1").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-recovery-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());
        when(sessionTaskRepository.findByTaskId("task-recovery-1")).thenReturn(Optional.of(entity));

        facade.submitTask(request);

        verify(sessionTaskRepository).save(entity);
        String taskStateJson = entity.getTaskStateJson();
        assertTrue(taskStateJson.contains("\"contextId\":\"ctx-1\""));
        assertTrue(taskStateJson.contains("\"originalTaskId\":\"task-original\""));
        assertTrue(taskStateJson.contains("\"recoveryCorrelationKey\":\"world-run/contract-1\""));
        assertTrue(taskStateJson.contains("\"attemptNumber\":2"));
        assertTrue(taskStateJson.contains("\"idempotencyKey\":\"idem-1\""));
        assertTrue(taskStateJson.contains("\"schemaVersion\":1"));
        assertTrue(taskStateJson.contains("\"providerType\":\"claude-worker\""));
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
    void createTask_usesExplicitCodexBizProviderWithOpenAICodexModelConfig() {
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexBizProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("run actor task")
                .model("gpt-5.4")
                .modelConfigId("cfg-codex")
                .metadata(Map.of("codexHomeKey", "tenant/world-sim/scenario-1/actor-1"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-1")
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .build();

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(codexBizProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-biz-1", result.getTaskId());
        assertEquals("codex-biz-worker", result.getProviderType());
        verify(codexBizProvider).createTaskDirect(
                argThat(params -> "codex-biz-worker".equals(params.get("providerType"))
                        && "tenant/world-sim/scenario-1/actor-1".equals(params.get("codexHomeKey"))
                        && "cfg-codex".equals(params.get("modelConfigId"))
                        && "worker-1".equals(params.get("workerId"))),
                eq("user-1"),
                eq("tenant-1"));
        verify(codexProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_directCodexBizPersistsScopedHomeBindingInSessionState() {
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexBizProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .prompt("run actor task")
                .modelConfigId("cfg-codex")
                .metadata(Map.of(
                        "codexHomeKey", "tenant/world-sim/scenario-1/home-1",
                        "privateAccountId", "tenant/world-sim/scenario-1/actor-1"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-1")
                .contextId("ctx-codex-biz-new")
                .sessionId("session-codex-biz-new")
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-biz-new");

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(codexBizProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1"))).thenReturn(directTask);
        when(agentConversationContextRepository.findById("ctx-codex-biz-new")).thenReturn(Optional.empty());
        when(sessionRepository.findById("session-codex-biz-new")).thenReturn(Optional.of(session));

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-biz-1", result.getTaskId());
        verify(codexBizProvider).createTaskDirect(
                argThat(params -> "tenant/world-sim/scenario-1/home-1".equals(params.get("codexHomeKey"))
                        && "tenant/world-sim/scenario-1/actor-1".equals(params.get("privateAccountId"))),
                eq("user-1"),
                eq("tenant-1"));
        verify(sessionRepository).save(argThat(saved -> {
            Map<String, Object> state = ProviderStateCodec.parseObject(saved.getProviderStateJson());
            return "session-codex-biz-new".equals(saved.getId())
                    && "tenant/world-sim/scenario-1/home-1".equals(
                    state.get(ProviderStateCodec.FIELD_CODEX_HOME_KEY))
                    && "tenant/world-sim/scenario-1/actor-1".equals(
                    state.get(ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID));
        }));
        verify(agentConversationContextRepository).save(argThat(saved ->
                "ctx-codex-biz-new".equals(saved.getContextId())
                        && "session-codex-biz-new".equals(saved.getNavigatorSessionId())
                        && "codex-biz-worker".equals(saved.getAgentType())));
        verify(codexProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_usesExplicitCodexBizProviderEvenWhenLogicalAgentIdIsPresent() {
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexBizProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("world-sim-agent")
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("run actor task")
                .model("gpt-5.4")
                .modelConfigId("cfg-codex")
                .metadata(Map.of("codexHomeKey", "tenant/world-sim/scenario-1/actor-1"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("OPEN_API")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-agent-1")
                .agentId("world-sim-agent")
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .build();

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(codexBizProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-biz-agent-1", result.getTaskId());
        assertEquals("world-sim-agent", result.getAgentId());
        assertEquals("codex-biz-worker", result.getProviderType());
        verify(codexBizProvider).createTaskDirect(
                argThat(params -> "codex-biz-worker".equals(params.get("providerType"))
                        && "world-sim-agent".equals(params.get("agentId"))
                        && "tenant/world-sim/scenario-1/actor-1".equals(params.get("codexHomeKey"))
                        && "cfg-codex".equals(params.get("modelConfigId"))
                        && "worker-1".equals(params.get("workerId"))),
                eq("user-1"),
                eq("tenant-1"));
        verify(codexProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_usesExplicitCodexBizProviderFromDirectoryDefaultCodexModelConfig() {
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexBizProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));
        ReflectionTestUtils.setField(facade, "workingDirectoryRepository", workingDirectoryRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("directory#dir-codex-biz")
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .prompt("run directory actor task")
                .metadata(Map.of("codexHomeKey", "tenant/world-sim/scenario-1/actor-1"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        WorkingDirectoryEntity directory = directoryEntity("dir-codex-biz", "World Sim");
        directory.setDefaultModelConfigId("cfg-codex");

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-directory-1")
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .directoryId("dir-codex-biz")
                .modelConfigId("cfg-codex")
                .build();

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(workingDirectoryRepository.findByDirectoryId("dir-codex-biz")).thenReturn(Optional.of(directory));
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(codexBizProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-biz-directory-1", result.getTaskId());
        assertEquals("codex-biz-worker", result.getProviderType());
        verify(codexBizProvider).createTaskDirect(
                argThat(params -> "codex-biz-worker".equals(params.get("providerType"))
                        && "tenant/world-sim/scenario-1/actor-1".equals(params.get("codexHomeKey"))
                        && "cfg-codex".equals(params.get("modelConfigId"))
                        && "dir-codex-biz".equals(params.get("directoryId"))),
                eq("user-1"),
                eq("tenant-1"));
        verify(codexProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_rejectsExplicitCodexBizProviderWithNonCodexModelConfig() {
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexBizProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("run actor task")
                .modelConfigId("cfg-claude")
                .metadata(Map.of("codexHomeKey", "tenant/world-sim/scenario-1/actor-1"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("CLAUDE_CODE");

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(llmModelManager.getModelConfig("cfg-claude")).thenReturn(Optional.of(modelConfig));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context));

        assertTrue(error.getMessage().contains("cfg-claude"));
        assertTrue(error.getMessage().contains("claude-worker"));
        assertTrue(error.getMessage().contains("codex-biz-worker"));
        verify(codexBizProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verify(codexProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_usesDirectProviderRouteWhenModelConfigTargetsGemini() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-gemini-1")
                .directoryId("dir-gemini-1")
                .prompt("hi gemini")
                .model("gemini-flash")
                .modelConfigId("cfg-gemini")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("GEMINI_CLI");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-gemini-1")
                .providerType("gemini-worker")
                .workerId("worker-gemini-1")
                .directoryId("dir-gemini-1")
                .model("gemini-flash")
                .build();

        when(llmModelManager.getModelConfig("cfg-gemini")).thenReturn(Optional.of(modelConfig));
        when(taskQueryProvider.getProviderType()).thenReturn("gemini-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-gemini-1", result.getTaskId());
        assertEquals("gemini-worker", result.getProviderType());
        verify(taskQueryProvider).createTaskDirect(
                argThat(params -> "worker-gemini-1".equals(params.get("workerId"))
                        && "dir-gemini-1".equals(params.get("directoryId"))
                        && "gemini-flash".equals(params.get("model"))
                        && "cfg-gemini".equals(params.get("modelConfigId"))),
                eq("user-1"),
                eq("tenant-1"));
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_usesDirectProviderRouteWhenModelConfigTargetsLangGraphBiz() {
        List<Map<String, Object>> attachments = List.of(Map.of(
                "name", "evidence.txt",
                "mimeType", "text/plain"
        ));
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-langgraph-1")
                .directoryId("dir-langgraph-1")
                .prompt("hi langgraph")
                .model("biz-default")
                .modelConfigId("cfg-langgraph")
                .context(Map.of("language", "fsscript", "script", "return 1;"))
                .attachments(attachments)
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("LANGGRAPH_BIZ");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-langgraph-1")
                .providerType("langgraph-biz-worker")
                .workerId("worker-langgraph-1")
                .directoryId("dir-langgraph-1")
                .model("biz-default")
                .build();

        when(llmModelManager.getModelConfig("cfg-langgraph")).thenReturn(Optional.of(modelConfig));
        when(taskQueryProvider.getProviderType()).thenReturn("langgraph-biz-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-langgraph-1", result.getTaskId());
        assertEquals("langgraph-biz-worker", result.getProviderType());
        verify(taskQueryProvider).createTaskDirect(
                argThat(params -> "worker-langgraph-1".equals(params.get("workerId"))
                        && "dir-langgraph-1".equals(params.get("directoryId"))
                        && "biz-default".equals(params.get("model"))
                        && "cfg-langgraph".equals(params.get("modelConfigId"))
                        && Map.of("language", "fsscript", "script", "return 1;").equals(params.get("context"))
                        && attachments.equals(params.get("attachments"))),
                eq("user-1"),
                eq("tenant-1"));
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_a2aRouteIncludesParentSessionIdFromCreatedSession() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-child-1")
                .agentId("agent-1")
                .workerId("worker-1")
                .prompt("forwarded task")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-child-1")
                .requestSource("UI")
                .build();

        SessionEntity childSession = new SessionEntity();
        childSession.setId("session-child-1");
        childSession.setUserId("user-1");
        childSession.setParentSessionId("session-parent-1");

        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-1").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-forward-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .metadata(Map.of(
                        "sessionId", "session-child-1",
                        "workerId", "worker-1"
                ))
                .build());
        when(sessionRepository.findById("session-child-1")).thenReturn(Optional.of(childSession));

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("session-child-1", result.getSessionId());
        assertEquals("session-parent-1", result.getParentSessionId());
    }

    @Test
    void createTask_a2aRoutePrefersMetadataForImmediateResponse() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .agentId("agent-1")
                .workerId("worker-1")
                .prompt("forwarded task")
                .model("legacy-model")
                .modelConfigId("cfg-legacy")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-1").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-forward-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .metadata(Map.of(
                        "sessionId", "session-1",
                        "workerId", "worker-1",
                        "workerTaskId", "worker-task-1",
                        "model", "glm4.7",
                        "modelConfigId", "cfg-new"
                ))
                .build());

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("glm4.7", result.getModel());
        assertEquals("cfg-new", result.getModelConfigId());
        assertEquals("worker-task-1", result.getWorkerTaskId());
    }

    @Test
    void createTask_usesModelConfigIdForDirectRoute() {
        LlmModelConfigDTO codexConfig = new LlmModelConfigDTO();
        codexConfig.setWorkerBackend("OPENAI_CODEX");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(codexConfig));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("hi")
                .modelConfigId("cfg-codex")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-provider-1")
                .providerType("codex-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .build();

        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-provider-1", result.getTaskId());
        verify(taskQueryProvider).createTaskDirect(
                argThat(params -> "worker-1".equals(params.get("workerId"))
                        && "dir-2".equals(params.get("directoryId"))),
                eq("user-1"),
                eq("tenant-1"));
    }

    @Test
    void createTask_rejectsModelConfigThatTargetsDifferentProviderThanResolvedAgent() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .agentId("agent-claude-1")
                .workerId("worker-1")
                .prompt("hi")
                .modelConfigId("cfg-codex")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        when(agentResolver.resolveAgent(eq("agent-claude-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-claude-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-claude-1").build());
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context));

        assertTrue(error.getMessage().contains("cfg-codex"));
        assertTrue(error.getMessage().contains("codex-worker"));
        assertTrue(error.getMessage().contains("claude-worker"));
        verify(agent, never()).sendTask(any());
        verifyNoInteractions(bindingService);
    }

    @Test
    void createTask_rejectsExplicitProviderTypeThatConflictsWithResolvedAgent() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .agentId("agent-claude-1")
                .workerId("worker-1")
                .prompt("hi")
                .providerType("codex-worker")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        when(agentResolver.resolveAgent(eq("agent-claude-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-claude-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-claude-1").build());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context));

        assertTrue(error.getMessage().contains("codex-worker"));
        assertTrue(error.getMessage().contains("claude-worker"));
        verify(agent, never()).sendTask(any());
        verifyNoInteractions(bindingService, llmModelManager);
    }

    @Test
    void listTasksPaged_aggregatesSessionsAcrossProviders() {
        TaskQueryProvider claudeProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(claudeProvider, codexProvider));

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

        when(claudeProvider.listTaskPage("user-1", 0, 20, null))
                .thenReturn(TaskPageResult.of(List.of(claudeTask), 1L, 0, 20));
        when(codexProvider.listTaskPage("user-1", 0, 20, null))
                .thenReturn(TaskPageResult.of(List.of(codexTask), 1L, 0, 20));

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
                .providerType("codex-worker")
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

        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-codex-2", result.getTaskId());
        assertEquals("thread-1", result.getCodexThreadId());
        verify(taskQueryProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "session-1".equals(params.get("sessionId"))
                        && "continue".equals(params.get("prompt"))));
        verifyNoInteractions(agentResolver);
    }

    @Test
    void resumeTask_routesGeminiSessionResumeToProvider() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-gemini-1")
                .sessionId("session-gemini-1")
                .prompt("continue gemini")
                .providerType("gemini-worker")
                .model("gemini-flash")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-gemini-1")
                .requestSource("UI")
                .build();

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-gemini-2")
                .sessionId("session-gemini-1")
                .geminiSessionId("gemini-session-1")
                .providerType("gemini-worker")
                .build();

        when(taskQueryProvider.getProviderType()).thenReturn("gemini-worker");
        when(taskQueryProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-gemini-2", result.getTaskId());
        assertEquals("gemini-session-1", result.getGeminiSessionId());
        verify(taskQueryProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "session-gemini-1".equals(params.get("sessionId"))
                        && "continue gemini".equals(params.get("prompt"))
                        && "worker-gemini-1".equals(params.get("workerId"))
                        && "gemini-flash".equals(params.get("model"))));
        verifyNoInteractions(agentResolver);
    }

    @Test
    void resumeTask_prefersSessionBoundProviderTypeOverLookupFallback() {
        TaskQueryProvider claudeProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(claudeProvider, codexProvider));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .directoryId("dir-1")
                .sessionId("session-codex-1")
                .prompt("continue")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-codex-1")
                .requestSource("UI")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-1");
        session.setUserId("user-1");
        session.setProviderType("codex-worker");
        when(sessionRepository.findById("session-codex-1")).thenReturn(Optional.of(session));
        // 显式模拟历史 bug 场景：如果错误回退到 lookup，会把同目录/worker 解析成 claude-worker。
        lenient().when(agentResolver.getProviderType(eq("dir-1"), any())).thenReturn(Optional.of("claude-worker"));
        lenient().when(agentResolver.getProviderType(eq("worker-1"), any())).thenReturn(Optional.of("claude-worker"));

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-codex-resumed")
                .sessionId("session-codex-1")
                .providerType("codex-worker")
                .build();

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(claudeProvider.getProviderType()).thenReturn("claude-worker");
        when(codexProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-codex-resumed", result.getTaskId());
        verify(codexProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "session-codex-1".equals(params.get("sessionId"))
                        && "continue".equals(params.get("prompt"))));
        verifyNoInteractions(agentResolver);
        verify(claudeProvider, never()).resumeTask(anyString(), anyString(), any());
    }

    @Test
    void resumeTask_prefersSessionBoundGeminiProviderTypeOverLegacyModelConfigLookup() {
        TaskQueryProvider geminiProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(geminiProvider, codexProvider));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-gemini-1")
                .sessionId("session-gemini-legacy")
                .prompt("continue gemini")
                .modelConfigId("cfg-codex")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-gemini-legacy")
                .requestSource("UI")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-gemini-legacy");
        session.setUserId("user-1");
        session.setProviderType("gemini-worker");
        when(sessionRepository.findById("session-gemini-legacy")).thenReturn(Optional.of(session));

        LlmModelConfigDTO codexConfig = new LlmModelConfigDTO();
        codexConfig.setWorkerBackend("OPENAI_CODEX");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(codexConfig));

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-gemini-bound")
                .providerType("gemini-worker")
                .sessionId("session-gemini-legacy")
                .geminiSessionId("gemini-session-bound")
                .build();

        when(geminiProvider.getProviderType()).thenReturn("gemini-worker");
        when(geminiProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-gemini-bound", result.getTaskId());
        assertEquals("gemini-worker", result.getProviderType());
        verify(geminiProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "session-gemini-legacy".equals(params.get("sessionId"))
                        && !params.containsKey("modelConfigId")));
        verify(codexProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void resumeTask_silentlyClearsModelConfigThatConflictsWithSessionBoundProvider() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-codex-1")
                .prompt("continue")
                .modelConfigId("cfg-claude")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-codex-1")
                .requestSource("UI")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-1");
        session.setUserId("user-1");
        session.setProviderType("codex-worker");

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("CLAUDE_CODE");

        when(sessionRepository.findById("session-codex-1")).thenReturn(Optional.of(session));
        when(llmModelManager.getModelConfig("cfg-claude")).thenReturn(Optional.of(modelConfig));

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-codex-resumed").providerType("codex-worker").build();
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        // normalizeResumeRequest 静默清除冲突的 modelConfigId，不再抛异常
        DispatchTaskDTO result = facade.resumeTask(request, context);
        assertEquals("task-codex-resumed", result.getTaskId());
        verify(taskQueryProvider).resumeTask(eq("user-1"), eq("tenant-1"), any());
    }

    @Test
    void resumeTask_rejectsExplicitProviderTypeThatConflictsWithSessionBoundProvider() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-codex-1")
                .prompt("continue")
                .providerType("claude-worker")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-codex-1")
                .requestSource("UI")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-1");
        session.setUserId("user-1");
        session.setProviderType("codex-worker");

        when(sessionRepository.findById("session-codex-1")).thenReturn(Optional.of(session));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.resumeTask(request, context));
        assertTrue(error.getMessage().contains("CONTEXT_WORKER_MISMATCH"));
        verify(taskQueryProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver, llmModelManager);
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
        when(workingDirectoryRepository.findByDirectoryIdIn(List.of("dir-2")))
                .thenReturn(List.of(directoryEntity("dir-2", "Codex Project")));
        when(workingDirectoryRepository.findByDirectoryIdIn(List.of("dir-1")))
                .thenReturn(List.of(directoryEntity("dir-1", "Claude Project")));

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
        verify(taskQueryProvider, never()).listTaskPage(anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void listTasksPaged_returnsOneSummaryTaskPerSession() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        SessionTaskEntity latestTask = sessionTask(
                "task-latest", "session-1", "codex-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"codexThreadId\":\"thread-1\"}"
        );
        latestTask.setPrompt("latest prompt");
        latestTask.setCostUsd(new BigDecimal("2.000000"));
        latestTask.setInputTokens(20L);
        latestTask.setOutputTokens(30L);
        SessionTaskEntity firstTask = sessionTask(
                "task-first", "session-1", "codex-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 21, 0), "{\"codexThreadId\":\"thread-1\"}"
        );
        firstTask.setPrompt("first prompt");
        firstTask.setCostUsd(new BigDecimal("1.250000"));
        firstTask.setInputTokens(10L);
        firstTask.setOutputTokens(15L);

        when(sessionTaskRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(latestTask, firstTask));
        when(sessionRepository.findAllById(List.of("session-1")))
                .thenReturn(List.of(
                        sessionEntity("session-1", "user-1", "AWAITING_REPLY",
                                LocalDateTime.of(2026, 3, 24, 22, 5))
                ));

        Object result = facade.listTasksPaged("user-1", 0, 1, "AWAITING_REPLY");

        Map<?, ?> page = assertInstanceOf(Map.class, result);
        assertEquals(1L, page.get("totalSessions"));
        List<?> content = assertInstanceOf(List.class, page.get("content"));
        assertEquals(1, content.size());
        DispatchTaskDTO summary = assertInstanceOf(DispatchTaskDTO.class, content.get(0));
        assertEquals("task-latest", summary.getTaskId());
        assertEquals(2, summary.getSessionTaskCount());
        assertEquals(new BigDecimal("3.250000"), summary.getSessionTotalCostUsd());
        assertEquals(30L, summary.getSessionInputTokens());
        assertEquals(45L, summary.getSessionOutputTokens());
        assertEquals("first prompt", summary.getSessionFirstPrompt());
    }

    @Test
    void listTasksPaged_compactOmitsHeavyFields() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        SessionTaskEntity latestTask = sessionTask(
                "task-latest", "session-1", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 22, 0),
                "{\"claudeSessionId\":\"claude-session-1\",\"checkpoints\":[{\"id\":\"ckpt-1\"}]}"
        );
        latestTask.setPrompt("latest prompt");
        latestTask.setResultText("large result payload");

        when(sessionTaskRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(latestTask));
        when(sessionRepository.findAllById(List.of("session-1")))
                .thenReturn(List.of(
                        sessionEntity("session-1", "user-1", "AWAITING_REPLY",
                                LocalDateTime.of(2026, 3, 24, 22, 5))
                ));

        Object result = facade.listTasksPaged("user-1", 0, 1, "AWAITING_REPLY", true);

        Map<?, ?> page = assertInstanceOf(Map.class, result);
        List<?> content = assertInstanceOf(List.class, page.get("content"));
        Map<?, ?> summary = assertInstanceOf(Map.class, content.get(0));
        assertEquals("task-latest", summary.get("taskId"));
        assertEquals("claude-session-1", summary.get("claudeSessionId"));
        assertEquals("latest prompt", summary.get("sessionFirstPrompt"));
        assertFalse(summary.containsKey("resultText"));
        assertFalse(summary.containsKey("errorMessage"));
        assertFalse(summary.containsKey("checkpoints"));
    }

    @Test
    void listActiveTasks_allowsNullDirectoryId() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(facade, "workingDirectoryRepository", workingDirectoryRepository);

        SessionTaskEntity task = sessionTask(
                "task-claude-1", "session-claude-1", "claude-worker", "worker-1", null,
                "RUNNING", LocalDateTime.of(2026, 3, 24, 21, 0), "{\"claudeSessionId\":\"claude-session-1\"}"
        );

        when(sessionTaskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                "user-1", List.of("RUNNING", "AWAITING_PERMISSION")))
                .thenReturn(List.of(task));
        when(sessionRepository.findAllById(List.of("session-claude-1")))
                .thenReturn(List.of(
                        sessionEntity("session-claude-1", "user-1", "PROCESSING",
                                LocalDateTime.of(2026, 3, 24, 21, 5))
                ));

        List<DispatchTaskDTO> tasks = facade.listActiveTasks("user-1");

        assertEquals(1, tasks.size());
        assertNull(tasks.get(0).getDirectoryId());
        assertNull(tasks.get(0).getDirectoryName());
        assertEquals("claude-session-1", tasks.get(0).getClaudeSessionId());
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
        verify(taskQueryProvider, never()).searchSessionPage(anyString(), any(), any(), any(), anyInt(), anyInt());
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

    @Test
    void reconnectTask_routesViaUnifiedSessionStoreProviderType() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-codex-reconnect", "session-codex-1", "codex-worker", "worker-1", "dir-2",
                "RUNNING", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"codexThreadId\":\"thread-1\"}"
        );
        when(sessionTaskRepository.findByTaskId("task-codex-reconnect")).thenReturn(Optional.of(task));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");

        facade.reconnectTask("task-codex-reconnect", "user-1");

        verify(taskQueryProvider).reconnectTask("task-codex-reconnect", "user-1");
        verify(taskQueryProvider, never()).getTaskById("task-codex-reconnect");
    }

    @Test
    void resyncTask_routesViaUnifiedSessionStoreProviderTypeAndReturnsProviderPayload() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-claude-resync", "session-claude-1", "claude-worker", "worker-1", "dir-1",
                "RUNNING", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"claudeSessionId\":\"claude-session-1\"}"
        );
        when(sessionTaskRepository.findByTaskId("task-claude-resync")).thenReturn(Optional.of(task));
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.resyncTask("task-claude-resync", "user-1"))
                .thenReturn(Map.of("status", "synced", "taskId", "task-claude-resync"));

        Object result = facade.resyncTask("task-claude-resync", "user-1");

        assertEquals(Map.of("status", "synced", "taskId", "task-claude-resync"), result);
        verify(taskQueryProvider).resyncTask("task-claude-resync", "user-1");
        verify(taskQueryProvider, never()).getTaskById("task-claude-resync");
    }

    @Test
    void scanCheckpoints_routesViaUnifiedSessionStoreProviderTypeAndReturnsProviderPayload() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-claude-checkpoints", "session-claude-1", "claude-worker", "worker-1", "dir-1",
                "RUNNING", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"claudeSessionId\":\"claude-session-1\"}"
        );
        when(sessionTaskRepository.findByTaskId("task-claude-checkpoints")).thenReturn(Optional.of(task));
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.scanCheckpoints("task-claude-checkpoints", "user-1"))
                .thenReturn(Map.of("checkpoints", List.of(Map.of("id", "ckpt-1"))));

        Object result = facade.scanCheckpoints("task-claude-checkpoints", "user-1");

        assertEquals(Map.of("checkpoints", List.of(Map.of("id", "ckpt-1"))), result);
        verify(taskQueryProvider).scanCheckpoints("task-claude-checkpoints", "user-1");
        verify(taskQueryProvider, never()).getTaskById("task-claude-checkpoints");
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
    void createTask_withoutAgentOrProviderContext_throwsException() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-missing")
                .prompt("hi")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        assertThrows(IllegalArgumentException.class, () -> facade.createTask(request, context));
        verifyNoInteractions(agentResolver);
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
    void cancelTask_routesViaProviderWhenAgentIdIsProviderConstant() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-claude-direct-1")
                .agentId("claude-worker")
                .providerType("claude-worker")
                .status("RUNNING")
                .build();
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.getTaskByIdAndUser("task-claude-direct-1", "user-1"))
                .thenReturn(Optional.of(task));

        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        facade.cancelTask("task-claude-direct-1", "claude-worker", context);

        verify(taskQueryProvider).cancelTaskDirect("task-claude-direct-1", "user-1");
        verify(agentResolver, never()).resolveAgent(eq("claude-worker"), any());
        verify(agent, never()).cancelTask(anyString());
    }

    @Test
    void cancelTask_routesViaProviderWhenUnifiedTaskHasLogicalAgentId() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-codex-logical-agent", "session-codex-1", "codex-worker", "worker-1", "dir-1",
                "RUNNING", LocalDateTime.of(2026, 3, 27, 10, 0), "{\"codexThreadId\":\"thread-1\"}"
        );
        task.setAgentId("agent-codex-prod-1");

        when(sessionTaskRepository.findByTaskIdAndUserId("task-codex-logical-agent", "user-1"))
                .thenReturn(Optional.of(task));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");

        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        facade.cancelTask("task-codex-logical-agent", task.getAgentId(), context);

        verify(taskQueryProvider).cancelTaskDirect("task-codex-logical-agent", "user-1");
        verify(agentResolver, never()).resolveAgent(eq("agent-codex-prod-1"), any());
        verify(agent, never()).cancelTask(anyString());
    }

    @Test
    void cancelTask_routesViaProviderWhenLogicalAgentIsMissing() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-direct-no-agent")
                .providerType("claude-worker")
                .status("RUNNING")
                .build();
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.getTaskByIdAndUser("task-direct-no-agent", "user-1"))
                .thenReturn(Optional.of(task));

        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        facade.cancelTask("task-direct-no-agent", null, context);

        verify(taskQueryProvider).cancelTaskDirect("task-direct-no-agent", "user-1");
        verifyNoInteractions(agentResolver, agent);
    }

    @Test
    void createTask_codexDirectRouteNotSkippedBySessionId() {
        // Codex 任务即使带 sessionId 也应走 direct route（非 claude-worker 不需要 A2a 解析）
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .directoryId("dir-1")
                .sessionId("session-1") // 关键：带 sessionId
                .prompt("codex task")
                .model("gpt-5.4")
                .modelConfigId("cfg-codex")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1").tenantId("tenant-1").requestSource("UI").build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-direct").providerType("codex-worker").build();

        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1"))).thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-direct", result.getTaskId());
        // 不应走 agentResolver（那是 A2a 路径）
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void cancelTask_failsFastWhenAgentNotResolvable() {
        // agentResolver 找不到 "codex-worker"（不是有效的 agent 实体 ID）
        when(agentResolver.resolveAgent(eq("codex-worker"), any())).thenReturn(Optional.empty());

        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1").requestSource("UI").build();

        // 不再 fallback 到 Provider，直接 fail-fast
        assertThrows(IllegalArgumentException.class,
                () -> facade.cancelTask("task-codex-1", "codex-worker", context));

        verify(agent, never()).cancelTask(anyString());
    }

    @Test
    void listWorkerSessions_delegatesToProvider() {
        List<WorkerSessionSummary> sessions = WorkerSessionSummary.fromList(List.of(
                Map.of("sessionId", "s1", "status", "active"),
                Map.of("sessionId", "s2", "status", "completed")
        ));
        when(taskQueryProvider.listWorkerSessionSummaries("worker-1", "user-1")).thenReturn(sessions);

        List<Map<String, Object>> result = facade.listWorkerSessions("worker-1", "user-1");

        assertEquals(2, result.size());
        assertEquals("s1", result.get(0).get("sessionId"));
        verify(taskQueryProvider).listWorkerSessionSummaries("worker-1", "user-1");
        verify(taskQueryProvider, never()).listWorkerSessions("worker-1", "user-1");
    }

    @Test
    void listWorkerSessions_usesDedicatedWorkerSessionProviderList() {
        facade = new TaskDispatchFacade(
                agentResolver,
                bindingService,
                sessionRepository,
                List.of(),
                List.of(),
                List.of(),
                List.of(new WorkerSessionOnlyProvider()),
                llmModelManager);

        List<Map<String, Object>> result = facade.listWorkerSessions("worker-1", "user-1");

        assertEquals(1, result.size());
        assertEquals("worker-only-session", result.get(0).get("sessionId"));
        verifyNoInteractions(taskQueryProvider);
    }

    @Test
    void listWorkerSessions_skipsProviderWhenWorkerBelongsToAnotherBackend() {
        TaskQueryProvider claudeProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider langgraphProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(claudeProvider, langgraphProvider));

        List<Map<String, Object>> sessions = List.of(Map.of("session_id", "lg-session-1"));
        when(claudeProvider.listWorkerSessionSummaries("lg-worker-1", "user-1"))
                .thenThrow(new IllegalArgumentException("Worker not found: lg-worker-1"));
        when(langgraphProvider.listWorkerSessionSummaries("lg-worker-1", "user-1"))
                .thenReturn(WorkerSessionSummary.fromList(sessions));

        List<Map<String, Object>> result = facade.listWorkerSessions("lg-worker-1", "user-1");

        assertEquals(1, result.size());
        assertEquals("lg-session-1", result.get(0).get("session_id"));
    }

    @Test
    void getWorkerSessionMessageCount_skipsProviderWhenWorkerBelongsToAnotherBackend() {
        TaskQueryProvider claudeProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider langgraphProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(claudeProvider, langgraphProvider));

        Map<String, Object> count = Map.of("user_count", 1, "assistant_count", 1, "total", 2);
        when(claudeProvider.getWorkerSessionMessageCountResult("lg-worker-1", "session-1", "user-1"))
                .thenThrow(new IllegalArgumentException("Worker not found"));
        when(langgraphProvider.getWorkerSessionMessageCountResult("lg-worker-1", "session-1", "user-1"))
                .thenReturn(WorkerSessionMessageCount.from(count));

        Map<String, Object> result = facade.getWorkerSessionMessageCount("lg-worker-1", "session-1", "user-1");

        assertEquals(count, result);
    }

    @Test
    void getWorkerSessionMessages_skipsProviderWhenWorkerBelongsToAnotherBackend() {
        TaskQueryProvider claudeProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider langgraphProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(claudeProvider, langgraphProvider));

        List<Map<String, Object>> messages = List.of(Map.of("role", "assistant", "content", "ok"));
        when(claudeProvider.listWorkerSessionMessages("lg-worker-1", "session-1", "user-1", 0, 50))
                .thenThrow(new IllegalArgumentException("Worker not found"));
        when(langgraphProvider.listWorkerSessionMessages("lg-worker-1", "session-1", "user-1", 0, 50))
                .thenReturn(WorkerSessionMessage.fromList(messages));

        List<Map<String, Object>> result =
                facade.getWorkerSessionMessages("lg-worker-1", "session-1", "user-1", 0, 50);

        assertEquals(messages, result);
    }

    @Test
    void syncWorkerSessions_delegatesToProvider() {
        Map<String, Object> syncResult = Map.of("synced", 5, "workerId", "worker-1");
        when(taskQueryProvider.syncWorkerSessionState("worker-1", "user-1", "tenant-1"))
                .thenReturn(WorkerSessionSyncResult.from(syncResult));

        Map<String, Object> result = facade.syncWorkerSessions("worker-1", "user-1", "tenant-1");

        assertEquals(5, result.get("synced"));
        verify(taskQueryProvider).syncWorkerSessionState("worker-1", "user-1", "tenant-1");
        verify(taskQueryProvider, never()).syncWorkerSessions("worker-1", "user-1", "tenant-1");
    }

    @Test
    void syncWorkerSessions_skipsProviderWhenWorkerBelongsToAnotherBackend() {
        TaskQueryProvider claudeProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider langgraphProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(claudeProvider, langgraphProvider));

        Map<String, Object> syncResult = Map.of("synced", 0, "total", 1);
        when(claudeProvider.syncWorkerSessionState("lg-worker-1", "user-1", "tenant-1"))
                .thenThrow(new IllegalArgumentException("Worker not found"));
        when(langgraphProvider.syncWorkerSessionState("lg-worker-1", "user-1", "tenant-1"))
                .thenReturn(WorkerSessionSyncResult.from(syncResult));

        Map<String, Object> result = facade.syncWorkerSessions("lg-worker-1", "user-1", "tenant-1");

        assertEquals(syncResult, result);
    }

    @Test
    void workerSessionTypedDefaultsAdaptLegacyMaps() {
        LegacyWorkerSessionProvider provider = new LegacyWorkerSessionProvider();

        assertEquals("legacy-session", provider.listWorkerSessionSummaries("worker-1", "user-1")
                .get(0).sessionId());
        assertEquals(2L, provider.getWorkerSessionMessageCountResult("worker-1", "legacy-session", "user-1")
                .total());
        assertEquals("assistant", provider.listWorkerSessionMessages("worker-1", "legacy-session", "user-1", 0, 10)
                .get(0).role());
        assertEquals(1L, provider.syncWorkerSessionState("worker-1", "user-1", "tenant-1").total());
    }

    @Test
    void resumeTask_usesExplicitCodexBizProviderWithOpenAICodexModelConfigWhenSessionIsUnbound() {
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexBizProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .sessionId("session-legacy-codex-biz")
                .prompt("continue actor task")
                .modelConfigId("cfg-codex")
                .metadata(Map.of("codexHomeKey", "tenant/world-sim/scenario-1/actor-1"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-legacy-codex-biz")
                .requestSource("WORLD_SIM")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-legacy-codex-biz");
        session.setUserId("user-1");
        when(sessionRepository.findById("session-legacy-codex-biz")).thenReturn(Optional.of(session));

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-resumed")
                .providerType("codex-biz-worker")
                .sessionId("session-legacy-codex-biz")
                .build();

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(codexBizProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-codex-biz-resumed", result.getTaskId());
        assertEquals("codex-biz-worker", result.getProviderType());
        verify(codexBizProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "codex-biz-worker".equals(params.get("providerType"))
                        && "tenant/world-sim/scenario-1/actor-1".equals(params.get("codexHomeKey"))
                        && "cfg-codex".equals(params.get("modelConfigId"))
                        && "session-legacy-codex-biz".equals(params.get("sessionId"))));
        verify(codexProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void resumeTask_keepsCodexBizMetadataWhenSessionIsBoundToCodexBizProvider() {
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexBizProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("codex_home_key", "tenant/world-sim/scenario-1/actor-2");
        metadata.put("developer_instructions", "Return only valid JSON.");
        metadata.put("codex_policy", Map.of(
                "sandbox_mode", "workspace-write",
                "approval_policy", "never",
                "network_access_enabled", false,
                "web_search_mode", "disabled"));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-codex-biz-bound")
                .prompt("continue actor task")
                .modelConfigId("cfg-codex")
                .metadata(metadata)
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-codex-biz-bound")
                .requestSource("WORLD_SIM")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-biz-bound");
        session.setUserId("user-1");
        session.setProviderType("codex-biz-worker");
        when(sessionRepository.findById("session-codex-biz-bound")).thenReturn(Optional.of(session));

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-bound")
                .providerType("codex-biz-worker")
                .sessionId("session-codex-biz-bound")
                .build();

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(codexBizProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-codex-biz-bound", result.getTaskId());
        assertEquals("codex-biz-worker", result.getProviderType());
        verify(codexBizProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "codex-biz-worker".equals(params.get("providerType"))
                        && "tenant/world-sim/scenario-1/actor-2".equals(params.get("codex_home_key"))
                        && "Return only valid JSON.".equals(params.get("developer_instructions"))
                        && metadata.get("codex_policy").equals(params.get("codex_policy"))
                        && "cfg-codex".equals(params.get("modelConfigId"))));
        verify(codexProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void createTask_withKnownContextIdAndResumeFlagContinuesBoundCodexBizSessionWithoutProviderType() {
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexBizProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("ctx-codex-biz-1")
                .prompt("continue actor task")
                .resume(true)
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        AgentConversationContextEntity boundContext = new AgentConversationContextEntity();
        boundContext.setContextId("ctx-codex-biz-1");
        boundContext.setUserId("user-1");
        boundContext.setTargetAgentId("world-sim-agent");
        boundContext.setAgentType("codex-biz-worker");
        boundContext.setNavigatorSessionId("session-codex-biz-bound");

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-biz-bound");
        session.setUserId("user-1");
        session.setAgentId("world-sim-agent");
        session.setProviderType("codex-biz-worker");
        session.setCurrentWorkerId("worker-1");
        session.setCurrentDirectoryId("dir-1");
        session.setProviderStateJson(ProviderStateCodec.mergeSessionValues(
                null,
                "codex-biz-worker",
                Map.of(
                        ProviderStateCodec.FIELD_CODEX_HOME_KEY, "tenant/world-sim/scenario-1/home-1",
                        ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID, "tenant/world-sim/scenario-1/actor-1")));

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-context")
                .providerType("codex-biz-worker")
                .sessionId("session-codex-biz-bound")
                .agentId("world-sim-agent")
                .contextId("ctx-codex-biz-1")
                .build();

        when(agentConversationContextRepository.findByContextIdAndUserId("ctx-codex-biz-1", "user-1"))
                .thenReturn(Optional.of(boundContext));
        when(agentConversationContextRepository.findById("ctx-codex-biz-1")).thenReturn(Optional.of(boundContext));
        when(sessionRepository.findById("session-codex-biz-bound")).thenReturn(Optional.of(session));
        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(codexBizProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-biz-context", result.getTaskId());
        verify(codexBizProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "codex-biz-worker".equals(params.get("providerType"))
                        && "session-codex-biz-bound".equals(params.get("sessionId"))
                        && "worker-1".equals(params.get("workerId"))
                        && "dir-1".equals(params.get("directoryId"))
                        && "ctx-codex-biz-1".equals(params.get("contextId"))
                        && "tenant/world-sim/scenario-1/home-1".equals(params.get("codexHomeKey"))
                        && "tenant/world-sim/scenario-1/actor-1".equals(params.get("privateAccountId"))));
        verify(codexProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void createTask_withKnownLangGraphBizContextCreatesDirectTaskWhenNotExplicitResume() {
        TaskQueryProvider langgraphBizProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(langgraphBizProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28")
                .prompt("BUG-148 smoke second 20260705-200741")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("agent-owner-1")
                .tenantId("tenant-1")
                .requestSource("OPEN_API")
                .build();

        AgentConversationContextEntity boundContext = new AgentConversationContextEntity();
        boundContext.setContextId("bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28");
        boundContext.setUserId("agent-owner-1");
        boundContext.setTargetAgentId("tms-tenant-88800-root-agent");
        boundContext.setAgentType("langgraph-biz-worker");
        boundContext.setNavigatorSessionId("session-langgraph-biz-bound");

        SessionEntity session = new SessionEntity();
        session.setId("session-langgraph-biz-bound");
        session.setUserId("agent-owner-1");
        session.setAgentId("tms-tenant-88800-root-agent");
        session.setProviderType("langgraph-biz-worker");
        session.setCurrentWorkerId("worker-langgraph-1");
        session.setCurrentDirectoryId("dir-langgraph-1");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("lgt_second")
                .providerType("langgraph-biz-worker")
                .sessionId("session-langgraph-biz-bound")
                .agentId("tms-tenant-88800-root-agent")
                .contextId("bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28")
                .workerId("worker-langgraph-1")
                .directoryId("dir-langgraph-1")
                .build();

        when(agentConversationContextRepository.findById("bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28"))
                .thenReturn(Optional.of(boundContext));
        when(agentConversationContextRepository.findByContextIdAndUserId(
                "bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28", "agent-owner-1"))
                .thenReturn(Optional.of(boundContext));
        when(sessionRepository.findById("session-langgraph-biz-bound")).thenReturn(Optional.of(session));
        when(langgraphBizProvider.getProviderType()).thenReturn("langgraph-biz-worker");
        when(langgraphBizProvider.createTaskDirect(any(), eq("agent-owner-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("lgt_second", result.getTaskId());
        verify(langgraphBizProvider).createTaskDirect(
                argThat(params -> "langgraph-biz-worker".equals(params.get("providerType"))
                        && "session-langgraph-biz-bound".equals(params.get("sessionId"))
                        && "worker-langgraph-1".equals(params.get("workerId"))
                        && "dir-langgraph-1".equals(params.get("directoryId"))
                        && "tms-tenant-88800-root-agent".equals(params.get("agentId"))
                        && "bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28".equals(params.get("contextId"))
                        && "BUG-148 smoke second 20260705-200741".equals(params.get("prompt"))),
                eq("agent-owner-1"),
                eq("tenant-1"));
        verify(langgraphBizProvider, never()).resumeTask(anyString(), anyString(), any());
        verify(agentConversationContextRepository).save(argThat(saved ->
                "bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28".equals(saved.getContextId())
                        && "session-langgraph-biz-bound".equals(saved.getNavigatorSessionId())
                        && "tms-tenant-88800-root-agent".equals(saved.getTargetAgentId())
                        && "langgraph-biz-worker".equals(saved.getAgentType())));
        verifyNoInteractions(agentResolver);
    }

    @Test
    void createTask_withKnownContextIdRejectsConflictingProviderType() {
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexBizProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("ctx-codex-biz-1")
                .providerType("codex-worker")
                .prompt("continue actor task")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        AgentConversationContextEntity boundContext = new AgentConversationContextEntity();
        boundContext.setContextId("ctx-codex-biz-1");
        boundContext.setUserId("user-1");
        boundContext.setTargetAgentId("world-sim-agent");
        boundContext.setAgentType("codex-biz-worker");
        boundContext.setNavigatorSessionId("session-codex-biz-bound");

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-biz-bound");
        session.setProviderType("codex-biz-worker");

        when(agentConversationContextRepository.findByContextIdAndUserId("ctx-codex-biz-1", "user-1"))
                .thenReturn(Optional.of(boundContext));
        when(sessionRepository.findById("session-codex-biz-bound")).thenReturn(Optional.of(session));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context));
        assertTrue(error.getMessage().contains("CONTEXT_WORKER_MISMATCH"));
        verify(codexBizProvider, never()).resumeTask(anyString(), anyString(), any());
        verify(codexProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void createTask_withKnownContextIdRejectsConflictingCodexBizScopedHome() {
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexBizProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("ctx-codex-biz-1")
                .prompt("continue actor task")
                .metadata(Map.of("privateAccountId", "tenant/world-sim/scenario-1/actor-other"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        AgentConversationContextEntity boundContext = new AgentConversationContextEntity();
        boundContext.setContextId("ctx-codex-biz-1");
        boundContext.setUserId("user-1");
        boundContext.setTargetAgentId("world-sim-agent");
        boundContext.setAgentType("codex-biz-worker");
        boundContext.setNavigatorSessionId("session-codex-biz-bound");

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-biz-bound");
        session.setProviderType("codex-biz-worker");
        session.setProviderStateJson(ProviderStateCodec.mergeSessionValues(
                null,
                "codex-biz-worker",
                Map.of(
                        ProviderStateCodec.FIELD_CODEX_HOME_KEY, "tenant/world-sim/scenario-1/home-1",
                        ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID, "tenant/world-sim/scenario-1/actor-1")));

        when(agentConversationContextRepository.findByContextIdAndUserId("ctx-codex-biz-1", "user-1"))
                .thenReturn(Optional.of(boundContext));
        when(sessionRepository.findById("session-codex-biz-bound")).thenReturn(Optional.of(session));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context));
        assertTrue(error.getMessage().contains("CONTEXT_WORKER_MISMATCH"));
        assertTrue(error.getMessage().contains("privateAccountId"));
        verify(codexBizProvider, never()).resumeTask(anyString(), anyString(), any());
        verify(codexProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void createTask_withContextIdBoundToAnotherUserRejectsBeforeDispatch() {
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        facade = createFacade(List.of(codexProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("ctx-owned-by-other")
                .agentId("agent-1")
                .prompt("continue")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("OPEN_API")
                .build();

        AgentConversationContextEntity existing = new AgentConversationContextEntity();
        existing.setContextId("ctx-owned-by-other");
        existing.setUserId("user-2");
        existing.setTargetAgentId("agent-1");
        existing.setNavigatorSessionId("session-other");

        when(agentConversationContextRepository.findById("ctx-owned-by-other"))
                .thenReturn(Optional.of(existing));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context));
        assertTrue(error.getMessage().contains("CONTEXT_WORKER_MISMATCH"));
        verifyNoInteractions(codexProvider, agentResolver);
    }

    @Test
    void resumeTask_usesSessionAgentIdWhenLegacySessionHasNoProviderType() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-1")
                .prompt("continue")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-resumed")
                .providerType("claude-worker")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setAgentId("agent-claude-1");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        when(agentResolver.getProviderType(eq("agent-claude-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-resumed", result.getTaskId());
        verify(agentResolver).getProviderType(eq("agent-claude-1"), any());
    }

    @Test
    void resumeTask_withoutBoundProviderOrExplicitContext_throwsException() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .prompt("continue")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(new SessionEntity()));

        assertThrows(IllegalArgumentException.class, () -> facade.resumeTask(request, context));
        verifyNoInteractions(agentResolver);
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

    @Test
    void deleteTask_removesUnifiedSessionStoreRecordSoHistoryPageNoLongerShowsDeletedConversation() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        SessionTaskEntity task = sessionTask(
                "task-delete-1", "session-delete-1", "codex-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 26, 10, 0), "{\"codexThreadId\":\"thread-delete-1\"}"
        );

        Map<String, SessionTaskEntity> store = new LinkedHashMap<>();
        store.put(task.getTaskId(), task);

        when(sessionTaskRepository.findByTaskId("task-delete-1"))
                .thenAnswer(invocation -> Optional.ofNullable(store.get("task-delete-1")));
        when(sessionTaskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc("dir-1", "user-1"))
                .thenAnswer(invocation -> store.values().stream()
                        .filter(entity -> "dir-1".equals(entity.getDirectoryId()) && "user-1".equals(entity.getUserId()))
                        .sorted(Comparator.comparing(SessionTaskEntity::getCreatedAt).reversed())
                        .toList());
        doAnswer(invocation -> {
            store.remove(invocation.getArgument(0));
            return null;
        }).when(sessionTaskRepository).deleteByTaskId(anyString());
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");

        Map<?, ?> beforeDelete = assertInstanceOf(Map.class, facade.listTasksByDirectoryPaged("user-1", "dir-1", 0, 20, null));
        assertEquals(1L, beforeDelete.get("totalSessions"));

        facade.deleteTask("task-delete-1", "user-1");

        Map<?, ?> afterDelete = assertInstanceOf(Map.class, facade.listTasksByDirectoryPaged("user-1", "dir-1", 0, 20, null));
        assertEquals(0L, afterDelete.get("totalSessions"));
        assertEquals(List.of(), afterDelete.get("content"));
        verify(taskQueryProvider).deleteTask("user-1", "task-delete-1");
        verify(sessionTaskRepository).deleteByTaskId("task-delete-1");
    }

    @Test
    void toDispatchTaskDTO_preservesNullAgentIdInsteadOfFallingBackToProviderType() {
        // 设计规范 §5.1: 禁止用 providerType 常量覆盖真实 logicalAgentId
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(facade, "workingDirectoryRepository", workingDirectoryRepository);

        // 构造一个 agentId 为 null 的 SessionTaskEntity（模拟旧数据或未绑定 Agent 的任务）
        SessionTaskEntity taskWithNullAgent = sessionTask(
                "task-null-agent", "session-null-agent", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 27, 10, 0), "{\"claudeSessionId\":\"cs-1\"}"
        );
        // agentId 未设置，保持 null

        when(sessionTaskRepository.findByTaskIdAndUserId("task-null-agent", "user-1"))
                .thenReturn(Optional.of(taskWithNullAgent));
        when(workingDirectoryRepository.findByDirectoryIdIn(List.of("dir-1")))
                .thenReturn(List.of(directoryEntity("dir-1", "Test Project")));

        Optional<DispatchTaskDTO> result = facade.getTask("task-null-agent",
                AgentResolveContext.builder().userId("user-1").build());

        assertTrue(result.isPresent());
        DispatchTaskDTO dto = result.orElseThrow();
        // 关键断言：agentId 应为 null，不应回退到 "claude-worker"
        assertNull(dto.getAgentId(),
                "agentId should be null when entity has no logical agent, not fall back to providerType");
        assertEquals("claude-worker", dto.getProviderType());
    }

    @Test
    void toDispatchTaskDTO_readsSchemaVersionedTaskStateProviderFields() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(facade, "workingDirectoryRepository", workingDirectoryRepository);

        Map<String, Object> providerState = new LinkedHashMap<>();
        providerState.put(ProviderStateCodec.FIELD_CODEX_THREAD_ID, "thread-v1");
        providerState.put(ProviderStateCodec.FIELD_CONTEXT_ID, "ctx-v1");
        providerState.put(ProviderStateCodec.FIELD_CHECKPOINTS, List.of(Map.of("id", "ckpt-v1")));
        providerState.put("fileCheckpointingEnabled", true);
        String taskStateJson = ProviderStateCodec.mergeTaskValues(null, "codex-worker", providerState);
        SessionTaskEntity task = sessionTask(
                "task-schema-v1", "session-schema-v1", "codex-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 27, 10, 0), taskStateJson
        );

        when(sessionTaskRepository.findByTaskIdAndUserId("task-schema-v1", "user-1"))
                .thenReturn(Optional.of(task));
        when(workingDirectoryRepository.findByDirectoryIdIn(List.of("dir-1")))
                .thenReturn(List.of(directoryEntity("dir-1", "Codex Project")));

        Optional<DispatchTaskDTO> result = facade.getTask("task-schema-v1",
                AgentResolveContext.builder().userId("user-1").build());

        assertTrue(result.isPresent());
        DispatchTaskDTO dto = result.orElseThrow();
        assertEquals("codex-worker", dto.getProviderType());
        assertEquals("thread-v1", dto.getCodexThreadId());
        assertEquals("ctx-v1", dto.getContextId());
        assertEquals(Boolean.TRUE, dto.getFileCheckpointingEnabled());
        assertNotNull(dto.getCheckpoints());
        assertTrue(dto.getCheckpoints().contains("ckpt-v1"));
        assertEquals("Codex Project", dto.getDirectoryName());
    }

    @Test
    void toDispatchTaskDTO_preservesRealLogicalAgentId() {
        // 设计规范 §11.4: session.agentId 必须保存真实逻辑 Agent，而不是 provider 常量
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        SessionTaskEntity taskWithAgent = sessionTask(
                "task-real-agent", "session-real-agent", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 27, 10, 0), "{\"claudeSessionId\":\"cs-1\"}"
        );
        taskWithAgent.setAgentId("agent-claude-prod-1");

        when(sessionTaskRepository.findByTaskIdAndUserId("task-real-agent", "user-1"))
                .thenReturn(Optional.of(taskWithAgent));

        Optional<DispatchTaskDTO> result = facade.getTask("task-real-agent",
                AgentResolveContext.builder().userId("user-1").build());

        assertTrue(result.isPresent());
        DispatchTaskDTO dto = result.orElseThrow();
        assertEquals("agent-claude-prod-1", dto.getAgentId(),
                "agentId must be the real logical agent, not the provider constant");
        assertEquals("claude-worker", dto.getProviderType());
    }

    @Test
    void deleteTask_cleansUnifiedSessionStoreWhenProviderTaskIsAlreadyMissing() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        SessionTaskEntity staleTask = sessionTask(
                "task-stale-1", "session-stale-1", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 26, 11, 0), "{\"claudeSessionId\":\"session-worker-1\"}"
        );

        Map<String, SessionTaskEntity> store = new LinkedHashMap<>();
        store.put(staleTask.getTaskId(), staleTask);

        when(sessionTaskRepository.findByTaskId("task-stale-1"))
                .thenAnswer(invocation -> Optional.ofNullable(store.get("task-stale-1")));
        when(sessionTaskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc("dir-1", "user-1"))
                .thenAnswer(invocation -> store.values().stream()
                        .filter(entity -> "dir-1".equals(entity.getDirectoryId()) && "user-1".equals(entity.getUserId()))
                        .sorted(Comparator.comparing(SessionTaskEntity::getCreatedAt).reversed())
                        .toList());
        doAnswer(invocation -> {
            store.remove(invocation.getArgument(0));
            return null;
        }).when(sessionTaskRepository).deleteByTaskId(anyString());
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        doThrow(new IllegalArgumentException("Task not found: task-stale-1"))
                .when(taskQueryProvider).deleteTask("user-1", "task-stale-1");

        Map<?, ?> beforeDelete = assertInstanceOf(Map.class, facade.listTasksByDirectoryPaged("user-1", "dir-1", 0, 20, null));
        assertEquals(1L, beforeDelete.get("totalSessions"));

        assertDoesNotThrow(() -> facade.deleteTask("task-stale-1", "user-1"));

        Map<?, ?> afterDelete = assertInstanceOf(Map.class, facade.listTasksByDirectoryPaged("user-1", "dir-1", 0, 20, null));
        assertEquals(0L, afterDelete.get("totalSessions"));
        assertEquals(List.of(), afterDelete.get("content"));
        verify(taskQueryProvider).deleteTask("user-1", "task-stale-1");
        verify(sessionTaskRepository).deleteByTaskId("task-stale-1");
    }

    private static final class WorkerSessionOnlyProvider implements WorkerSessionQueryProvider {

        @Override
        public String getProviderType() {
            return "worker-session-only";
        }

        @Override
        public Set<TaskQueryCapability> getCapabilities() {
            return Set.of(TaskQueryCapability.LIST_WORKER_SESSIONS);
        }

        @Override
        public List<Map<String, Object>> listWorkerSessions(String workerId, String userId) {
            return List.of(Map.of("sessionId", "worker-only-session"));
        }
    }

    private static final class LegacyWorkerSessionProvider implements WorkerSessionQueryProvider {

        @Override
        public String getProviderType() {
            return "legacy-worker-session";
        }

        @Override
        public Set<TaskQueryCapability> getCapabilities() {
            return Set.of(
                    TaskQueryCapability.LIST_WORKER_SESSIONS,
                    TaskQueryCapability.GET_WORKER_SESSION_MESSAGE_COUNT,
                    TaskQueryCapability.GET_WORKER_SESSION_MESSAGES,
                    TaskQueryCapability.SYNC_WORKER_SESSIONS);
        }

        @Override
        public List<Map<String, Object>> listWorkerSessions(String workerId, String userId) {
            return List.of(Map.of("session_id", "legacy-session", "status", "RUNNING"));
        }

        @Override
        public Map<String, Object> getWorkerSessionMessageCount(String workerId, String sessionId, String userId) {
            return Map.of("user_count", 1, "assistant_count", 1, "total", 2);
        }

        @Override
        public List<Map<String, Object>> getWorkerSessionMessages(String workerId, String sessionId,
                                                                  String userId, Integer offset, Integer limit) {
            return List.of(Map.of("role", "assistant", "content", "ok"));
        }

        @Override
        public Map<String, Object> syncWorkerSessions(String workerId, String userId, String tenantId) {
            return Map.of("synced", 0, "total", 1);
        }
    }
}
