package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.repository.CodexCodingAgentRepository;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.agent.TaskSearchResult;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
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
import java.util.LinkedHashMap;
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
import static org.mockito.Mockito.never;
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
    @Mock
    private CodexCodingAgentRepository codingAgentRepository;
    @Mock
    private CodexStreamRelay streamRelay;

    private CodexTaskService service;

    @BeforeEach
    void setUp() {
        service = new CodexTaskService(taskRepository, workerManagementFacade, eventPublisher);
        ReflectionTestUtils.setField(service, "llmModelManager", llmModelManager);
        ReflectionTestUtils.setField(service, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(service, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(service, "sessionEntityRepository", sessionEntityRepository);
        ReflectionTestUtils.setField(service, "codingAgentRepository", codingAgentRepository);
        ReflectionTestUtils.setField(service, "streamRelay", streamRelay);

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
    void exposesOnlySupportedTaskProviderPorts() {
        assertInstanceOf(TaskLookupProvider.class, service);
        assertInstanceOf(TaskCommandProvider.class, service);
        assertInstanceOf(TaskListingProvider.class, service);
        assertFalse(service instanceof TaskQueryProvider);
        assertFalse(service instanceof WorkerSessionQueryProvider);
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

        TaskPageResult page = service.listTaskPage("user-1", 0, 20, "PROCESSING");
        assertEquals(1L, page.totalSessions());
        List<?> content = page.content();
        assertEquals(1, content.size());
        DispatchTaskDTO task = assertInstanceOf(DispatchTaskDTO.class, content.get(0));
        assertEquals("task-running", task.getTaskId());
        assertEquals("session-running", task.getSessionId());
    }

    @Test
    void codexBizProviderFiltersLookupListingAndSearchAwayFromPlainCodexTasks() {
        CodexTaskEntity plain = createTask(
                "task-plain", "session-plain", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 3, 24, 22, 0)
        );
        plain.setProviderType("codex-worker");
        plain.setResultText("plain result");

        CodexTaskEntity biz = createTask(
                "task-biz", "session-biz", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 3, 24, 23, 0)
        );
        biz.setProviderType("codex-biz-worker");
        biz.setPrompt("actor decision");
        biz.setResultText("biz result");

        when(taskRepository.findByTaskId("task-plain")).thenReturn(Optional.of(plain));
        when(taskRepository.findByTaskId("task-biz")).thenReturn(Optional.of(biz));
        when(taskRepository.findBySessionId("session-mixed")).thenReturn(List.of(plain, biz));
        when(taskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc("user-1",
                List.of("RUNNING", "AWAITING_PERMISSION"))).thenReturn(List.of(biz, plain));
        when(taskRepository.findByUserIdOrderByCreatedAtDesc("user-1")).thenReturn(List.of(biz, plain));
        when(taskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc("dir-1", "user-1"))
                .thenReturn(List.of(biz, plain));

        assertTrue(service.getTaskByIdForProvider("task-plain", "codex-biz-worker").isEmpty());
        DispatchTaskDTO bizLookup = service.getTaskByIdForProvider("task-biz", "codex-biz-worker").orElseThrow();
        assertEquals("codex-biz-worker", bizLookup.getProviderType());

        List<DispatchTaskDTO> sessionTasks = service.listTasksBySessionForProvider("session-mixed", "codex-biz-worker");
        assertEquals(1, sessionTasks.size());
        assertEquals("task-biz", sessionTasks.get(0).getTaskId());

        List<DispatchTaskDTO> activeTasks = service.listActiveDispatchTasksForProvider("user-1", "codex-biz-worker");
        assertEquals(1, activeTasks.size());
        assertEquals("task-biz", activeTasks.get(0).getTaskId());

        TaskPageResult page = service.listTasksPagedForProvider("user-1", 0, 20, null, "codex-biz-worker");
        assertEquals(1L, page.totalSessions());
        DispatchTaskDTO pagedTask = assertInstanceOf(DispatchTaskDTO.class, page.content().get(0));
        assertEquals("task-biz", pagedTask.getTaskId());

        TaskPageResult directoryPage = service.listTasksByDirectoryPagedForProvider(
                "user-1", "dir-1", 0, 20, null, "codex-biz-worker");
        assertEquals(1L, directoryPage.totalSessions());
        DispatchTaskDTO directoryTask = assertInstanceOf(DispatchTaskDTO.class, directoryPage.content().get(0));
        assertEquals("task-biz", directoryTask.getTaskId());

        TaskSearchResult search = service.searchSessionsForProvider(
                "user-1", "actor", null, null, 0, 20, "codex-biz-worker");
        assertEquals(1L, search.total());
        Map<?, ?> result = assertInstanceOf(Map.class, search.results().get(0));
        assertEquals("session-biz", result.get("sessionId"));
        assertEquals("task-biz", result.get("latestTaskId"));
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
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) -> {
            Map<String, Object> state = ProviderStateCodec.parseObject(entity.getTaskStateJson());
            return "session-1".equals(entity.getSessionId())
                    && "codex-worker".equals(entity.getProviderType())
                    && Integer.valueOf(ProviderStateCodec.CURRENT_SCHEMA_VERSION).equals(state.get(ProviderStateCodec.FIELD_SCHEMA_VERSION))
                    && "codex-worker".equals(state.get(ProviderStateCodec.FIELD_PROVIDER_TYPE))
                    && "thread-1".equals(state.get(ProviderStateCodec.FIELD_CODEX_THREAD_ID));
        }));
        verify(sessionEntityRepository).save(argThat((SessionEntity entity) ->
                "session-1".equals(entity.getId())
                        && "codex-worker".equals(entity.getProviderType())
                        && "worker-1".equals(entity.getCurrentWorkerId())
                        && entity.getProviderStateJson() != null
                        && entity.getProviderStateJson().contains("\"schemaVersion\":1")
                        && entity.getProviderStateJson().contains("\"providerType\":\"codex-worker\"")
                        && entity.getProviderStateJson().contains("\"codexThreadId\":\"thread-1\"")
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
        verify(workerManagementFacade).validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "D:/projects/my-app".equals(event.getCwd())
        ));
    }

    @Test
    void createTaskDirect_persistsContextIdInUnifiedTaskState() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        SessionTaskEntity[] savedSessionTask = new SessionTaskEntity[1];
        SessionTaskEntity existingProjection = new SessionTaskEntity();
        existingProjection.setTaskStateJson("{\"originalTaskId\":\"task-original\"}");
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        when(sessionTaskRepository.findByTaskId(anyString())).thenReturn(Optional.of(existingProjection));
        when(sessionManager.createSession(any())).thenReturn("session-ctx");
        when(sessionTaskRepository.save(any(SessionTaskEntity.class))).thenAnswer(invocation -> {
            savedSessionTask[0] = invocation.getArgument(0);
            return savedSessionTask[0];
        });

        DispatchTaskDTO result = service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "contextId", "bctx-1"
        ), "user-1", "tenant-1");

        assertEquals("bctx-1", result.getContextId());
        assertNotNull(savedSessionTask[0].getTaskStateJson());
        Map<String, Object> state = ProviderStateCodec.parseObject(savedSessionTask[0].getTaskStateJson());
        assertEquals(ProviderStateCodec.CURRENT_SCHEMA_VERSION, state.get(ProviderStateCodec.FIELD_SCHEMA_VERSION));
        assertEquals("codex-worker", state.get(ProviderStateCodec.FIELD_PROVIDER_TYPE));
        assertEquals("bctx-1", state.get(ProviderStateCodec.FIELD_CONTEXT_ID));
        assertEquals("task-original", state.get("originalTaskId"));
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
    void createTaskDirect_doesNotForwardCodexBizOptionsForPlainCodexWorker() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        Map<String, Object> outputSchema = Map.of("type", "object");
        Map<String, Object> codexConfig = Map.of("tool_output_token_limit", 4096);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workerId", "worker-1");
        params.put("prompt", "hello");
        params.put("codexHomeKey", "tenant/world-sim/scenario-1/actor-1");
        params.put("developerInstructions", "Return valid JSON.");
        params.put("businessRuntimeContext", Map.of("task_scoped_token", "token-1"));
        params.put("outputSchema", outputSchema);
        params.put("codexConfig", codexConfig);
        params.put("sandboxMode", "workspace-write");
        params.put("approvalPolicy", "never");
        params.put("networkAccessEnabled", false);
        params.put("webSearchMode", "disabled");
        params.put("additionalDirectories", List.of("/home/sa/workspace/shared"));
        service.createTaskDirect(params, "user-1", "tenant-1");

        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "codex-worker".equals(event.getProviderType())
                        && event.getProviderConfigString("codexHomeKey") == null
                        && event.getProviderConfigString("developerInstructions") == null
                        && event.getProviderConfigValue("businessRuntimeContext") == null
                        && event.getProviderConfigValue("outputSchema") == null
                        && event.getProviderConfigValue("codexConfig") == null
                        && event.getProviderConfigString("sandboxMode") == null
                        && event.getProviderConfigString("approvalPolicy") == null
                        && event.getProviderConfigValue("networkAccessEnabled") == null
                        && event.getProviderConfigString("webSearchMode") == null
                        && event.getProviderConfigValue("additionalDirectories") == null
        ));
    }

    @Test
    void createTaskDirect_forwardsCodexBizOptionsOnlyForCodexBizProvider() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        when(sessionManager.createSession(any())).thenReturn("session-biz-1");

        Map<String, Object> outputSchema = Map.of("type", "object");
        Map<String, Object> codexConfig = Map.of("tool_output_token_limit", 4096);
        Map<String, Object> businessRuntimeContext = Map.of("task_scoped_token", "token-1");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("providerType", "codex-biz-worker");
        params.put("workerId", "worker-1");
        params.put("prompt", "hello");
        params.put("codexHomeKey", "tenant/world-sim/scenario-1/actor-1");
        params.put("developerInstructions", "Return valid JSON.");
        params.put("businessRuntimeContext", businessRuntimeContext);
        params.put("outputSchema", outputSchema);
        params.put("codexConfig", codexConfig);
        params.put("sandboxMode", "workspace-write");
        params.put("approvalPolicy", "never");
        params.put("networkAccessEnabled", false);
        params.put("webSearchMode", "disabled");
        params.put("additionalDirectories", List.of("/home/sa/workspace/shared"));
        DispatchTaskDTO result = service.createTaskDirect(params, "user-1", "tenant-1");

        assertEquals("codex-biz-worker", result.getProviderType());
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "codex-biz-worker".equals(event.getProviderType())
                        && "tenant/world-sim/scenario-1/actor-1".equals(event.getProviderConfigString("codexHomeKey"))
                        && "Return valid JSON.".equals(event.getProviderConfigString("developerInstructions"))
                        && businessRuntimeContext.equals(event.getProviderConfigValue("businessRuntimeContext"))
                        && outputSchema.equals(event.getProviderConfigValue("outputSchema"))
                        && codexConfig.equals(event.getProviderConfigValue("codexConfig"))
                        && "workspace-write".equals(event.getProviderConfigString("sandboxMode"))
                        && "never".equals(event.getProviderConfigString("approvalPolicy"))
                        && Boolean.FALSE.equals(event.getProviderConfigValue("networkAccessEnabled"))
                        && "disabled".equals(event.getProviderConfigString("webSearchMode"))
                        && List.of("/home/sa/workspace/shared").equals(event.getProviderConfigValue("additionalDirectories"))
        ));
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                "session-biz-1".equals(entity.getSessionId())
                        && "codex-biz-worker".equals(entity.getProviderType())
        ));
        verify(sessionEntityRepository).save(argThat((SessionEntity entity) ->
        {
            Map<String, Object> state = ProviderStateCodec.parseObject(entity.getProviderStateJson());
            return "session-biz-1".equals(entity.getId())
                    && "codex-biz-worker".equals(entity.getProviderType())
                    && "worker-1".equals(entity.getCurrentWorkerId())
                    && "tenant/world-sim/scenario-1/actor-1".equals(
                    state.get(ProviderStateCodec.FIELD_CODEX_HOME_KEY))
                    && "tenant/world-sim/scenario-1/actor-1".equals(
                    state.get(ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID));
        }
        ));
    }

    @Test
    void createTaskDirect_forwardsSnakeCaseCodexBizAliasesOnlyForCodexBizProvider() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        Map<String, Object> outputSchema = Map.of("type", "object");
        Map<String, Object> codexConfig = Map.of("tool_output_token_limit", 4096);
        Map<String, Object> snakeCaseBusinessRuntimeContext = Map.of("task_scoped_token", "token-2");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("providerType", "codex-biz-worker");
        params.put("workerId", "worker-1");
        params.put("prompt", "hello");
        params.put("codex_home_key", "tenant/world-sim/scenario-1/actor-2");
        params.put("developer_instructions", "Return valid JSON.");
        params.put("business_runtime_context", snakeCaseBusinessRuntimeContext);
        params.put("output_schema", outputSchema);
        params.put("codex_config", codexConfig);
        params.put("sandbox_mode", "workspace-write");
        params.put("approval_policy", "never");
        params.put("network_access_enabled", "false");
        params.put("web_search_mode", "disabled");
        params.put("additional_directories", List.of("/home/sa/workspace/shared", " "));

        DispatchTaskDTO result = service.createTaskDirect(params, "user-1", "tenant-1");

        assertEquals("codex-biz-worker", result.getProviderType());
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "codex-biz-worker".equals(event.getProviderType())
                        && "tenant/world-sim/scenario-1/actor-2".equals(event.getProviderConfigString("codexHomeKey"))
                        && "Return valid JSON.".equals(event.getProviderConfigString("developerInstructions"))
                        && snakeCaseBusinessRuntimeContext.equals(event.getProviderConfigValue("businessRuntimeContext"))
                        && outputSchema.equals(event.getProviderConfigValue("outputSchema"))
                        && codexConfig.equals(event.getProviderConfigValue("codexConfig"))
                        && "workspace-write".equals(event.getProviderConfigString("sandboxMode"))
                        && "never".equals(event.getProviderConfigString("approvalPolicy"))
                        && Boolean.FALSE.equals(event.getProviderConfigValue("networkAccessEnabled"))
                        && "disabled".equals(event.getProviderConfigString("webSearchMode"))
                        && List.of("/home/sa/workspace/shared").equals(event.getProviderConfigValue("additionalDirectories"))
        ));
    }

    @Test
    void createTaskDirect_acceptsPrivateAccountIdAliasForCodexBizProvider() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("providerType", "codex-biz-worker");
        params.put("workerId", "worker-1");
        params.put("prompt", "hello");
        params.put("privateAccountId", "tenant/world-sim/scenario-1/actor-3");

        DispatchTaskDTO result = service.createTaskDirect(params, "user-1", "tenant-1");

        assertEquals("codex-biz-worker", result.getProviderType());
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "codex-biz-worker".equals(event.getProviderType())
                        && "tenant/world-sim/scenario-1/actor-3".equals(event.getProviderConfigString("codexHomeKey"))
        ));
    }

    @Test
    void createTask_rejectsLegacyCodexBizProviderWithoutScopedHomeKey() {
        CreateCodexTaskForm form = new CreateCodexTaskForm();
        form.setProviderType("codex-biz-worker");
        form.setWorkerId("worker-1");
        form.setPrompt("hello");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTask("user-1", "tenant-1", form));

        assertEquals("codex-biz-worker requires codexHomeKey or privateAccountId", error.getMessage());
        verify(taskRepository, never()).save(any());
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
                        && entity.getAgentId() == null
                        && !"dir-1".equals(entity.getAgentId())
        ));
        verify(sessionEntityRepository).save(argThat((SessionEntity entity) ->
                "session-new".equals(entity.getId())
                        && "codex-worker".equals(entity.getProviderType())
                        && entity.getAgentId() == null
                        && !"dir-1".equals(entity.getAgentId())
        ));
    }

    @Test
    void createTask_usesAgentDefaultModelConfigAndDefaultModelWhenRequestOmitsBoth() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        when(sessionManager.createSession(any())).thenReturn("session-agent-default");

        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setAgentId("agent-codex-1");
        agent.setDefaultModelConfigId("cfg-codex");
        agent.setDefaultModel("gpt-5.4");
        when(codingAgentRepository.findByAgentId("agent-codex-1")).thenReturn(Optional.of(agent));

        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setWorkerBackend("OPENAI_CODEX");
        config.setBaseUrl("https://api.openai.com/v1");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(config));
        when(llmModelManager.getDecryptedApiKey("cfg-codex")).thenReturn("sk-codex");

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "agentId", "agent-codex-1"
        ), "user-1", "tenant-1");

        assertEquals("gpt-5.4", savedTask[0].getModel());
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "gpt-5.4".equals(event.getModel())
                        && "sk-codex".equals(event.getApiKey())
                        && "https://api.openai.com/v1".equals(event.getProviderConfigString("baseUrl"))
        ));
    }

    @Test
    void createTaskDirect_rejectsLegacyWhitelistForGatedAliasBeforePersistence() {
        LlmModelConfigDTO config = codexModelConfig(List.of("gpt-5.4", "gpt-5.5"));
        when(llmModelManager.getModelConfig("cfg-legacy")).thenReturn(Optional.of(config));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTaskDirect(Map.of(
                        "workerId", "worker-1",
                        "prompt", "hello",
                        "model", "codex-max",
                        "modelConfigId", "cfg-legacy"
                ), "user-1", "tenant-1"));

        assertTrue(error.getMessage().contains("explicit availableModels grant"));
        verify(llmModelManager).validateModelAccessForWorker("cfg-legacy", "worker-1");
        verify(sessionManager, never()).createSession(any());
        verify(taskRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(WorkerTaskStartEvent.class));
    }

    @Test
    void createTaskDirect_allowsStableAliasGrantForKnownGpt56SolModel() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-max-alias");
        LlmModelConfigDTO config = codexModelConfig(List.of("codex-max"));
        when(llmModelManager.getModelConfig("cfg-max-alias")).thenReturn(Optional.of(config));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "gpt-5.6-sol:max",
                "modelConfigId", "cfg-max-alias"
        ), "user-1", "tenant-1");

        assertEquals("gpt-5.6-sol:max", savedTask[0].getModel());
        verify(llmModelManager).validateModelAccessForWorker("cfg-max-alias", "worker-1");
    }

    @Test
    void createTaskDirect_allowsKnownGpt56SolGrantForStableAlias() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-ultra-real-grant");
        LlmModelConfigDTO config = codexModelConfig(List.of("gpt-5.6-sol:ultra"));
        when(llmModelManager.getModelConfig("cfg-ultra-real")).thenReturn(Optional.of(config));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "codex-ultra",
                "modelConfigId", "cfg-ultra-real"
        ), "user-1", "tenant-1");

        assertEquals("codex-ultra", savedTask[0].getModel());
    }

    @Test
    void createTaskDirect_allowsStableAliasGrantForCodexLatestSuffix() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-latest-ultra");
        LlmModelConfigDTO config = codexModelConfig(List.of("codex-ultra"));
        when(llmModelManager.getModelConfig("cfg-latest-ultra")).thenReturn(Optional.of(config));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "codex-latest:ultra",
                "modelConfigId", "cfg-latest-ultra"
        ), "user-1", "tenant-1");

        assertEquals("codex-latest:ultra", savedTask[0].getModel());
    }

    @Test
    void createTaskDirect_allowsExactFutureGatedModelGrant() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-future-exact");
        LlmModelConfigDTO config = codexModelConfig(List.of("gpt-5.7-sol:ultra"));
        when(llmModelManager.getModelConfig("cfg-future-exact")).thenReturn(Optional.of(config));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "gpt-5.7-sol:ultra",
                "modelConfigId", "cfg-future-exact"
        ), "user-1", "tenant-1");

        assertEquals("gpt-5.7-sol:ultra", savedTask[0].getModel());
    }

    @Test
    void createTaskDirect_rejectsStableAliasGrantForFutureGatedModel() {
        LlmModelConfigDTO config = codexModelConfig(List.of("codex-max"));
        when(llmModelManager.getModelConfig("cfg-future-alias")).thenReturn(Optional.of(config));

        assertThrows(IllegalArgumentException.class, () -> service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "gpt-5.7-sol:max",
                "modelConfigId", "cfg-future-alias"
        ), "user-1", "tenant-1"));

        verify(sessionManager, never()).createSession(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createTaskDirect_allowsGatedModelWhenWhitelistIsUnrestricted() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-unrestricted");
        LlmModelConfigDTO config = codexModelConfig(List.of());
        when(llmModelManager.getModelConfig("cfg-unrestricted")).thenReturn(Optional.of(config));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "codex-ultra",
                "modelConfigId", "cfg-unrestricted"
        ), "user-1", "tenant-1");

        assertEquals("codex-ultra", savedTask[0].getModel());
    }

    @Test
    void createTaskDirect_keepsNonGatedModelCompatibilityWithRestrictedWhitelist() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-non-gated");
        LlmModelConfigDTO config = codexModelConfig(List.of("gpt-5.4"));
        when(llmModelManager.getModelConfig("cfg-non-gated")).thenReturn(Optional.of(config));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "gpt-5.6-sol:xhigh",
                "modelConfigId", "cfg-non-gated"
        ), "user-1", "tenant-1");

        assertEquals("gpt-5.6-sol:xhigh", savedTask[0].getModel());
    }

    @Test
    void createTaskDirect_failsClosedWhenConfiguredModelDoesNotExist() {
        when(llmModelManager.getModelConfig("cfg-missing")).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTaskDirect(Map.of(
                        "workerId", "worker-1",
                        "prompt", "hello",
                        "model", "codex-ultra",
                        "modelConfigId", "cfg-missing"
                ), "user-1", "tenant-1"));

        assertEquals("LLM model config not found: cfg-missing", error.getMessage());
        verify(llmModelManager).validateModelAccessForWorker("cfg-missing", "worker-1");
        verify(sessionManager, never()).createSession(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createTaskDirect_failsClosedWhenModelManagerIsUnavailable() {
        ReflectionTestUtils.setField(service, "llmModelManager", null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createTaskDirect(Map.of(
                        "workerId", "worker-1",
                        "prompt", "hello",
                        "model", "codex-ultra",
                        "modelConfigId", "cfg-ultra"
                ), "user-1", "tenant-1"));

        assertTrue(error.getMessage().contains("LLM model manager is unavailable"));
        verify(sessionManager, never()).createSession(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void abortTask_relaysRemoteAbortAndClosesStreamBeforeMarkingAborted() {
        CodexTaskEntity entity = createTask(
                "task-abort", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 4, 2, 10, 0)
        );
        entity.setWorkerTaskId("worker-task-1");

        when(taskRepository.findByTaskId("task-abort")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.abortTask("task-abort");

        verify(streamRelay).abortRemoteTask(entity);
        verify(streamRelay).abortStream("task-abort");
        verify(taskRepository).save(argThat((CodexTaskEntity saved) ->
                "task-abort".equals(saved.getTaskId()) && "ABORTED".equals(saved.getStatus())
        ));
        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-abort".equals(event.getTaskId())
                        && "RUNNING".equals(event.getPreviousStatus())
                        && "ABORTED".equals(event.getStatus())
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
        existingSession.setProviderStateJson("{\"schemaVersion\":1,\"providerType\":\"codex-worker\",\"codexThreadId\":\"thread-1\"}");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(existingSession));

        DispatchTaskDTO result = service.resumeTask("user-1", "tenant-1", Map.of(
                "workerId", "worker-1",
                "sessionId", "session-1",
                "prompt", "continue"
        ));

        assertEquals("agent-codex-1", result.getAgentId());
        assertEquals("thread-1", result.getCodexThreadId());
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                "session-1".equals(entity.getSessionId())
                        && "agent-codex-1".equals(entity.getAgentId())
        ));
    }

    @Test
    void rewindTask_truncatesPlatformSessionAndClearsCodexThread() {
        CodexTaskEntity entity = createTask(
                "task-rewind", "session-1", "worker-1", "dir-1", "COMPLETED",
                LocalDateTime.of(2026, 5, 10, 10, 0)
        );
        entity.setCodexThreadId("thread-1");
        when(taskRepository.findByTaskId("task-rewind")).thenReturn(Optional.of(entity));
        when(sessionManager.getAllMessages("session-1")).thenReturn(List.of(
                Message.user("session-1", "first prompt"),
                Message.assistant("session-1", "first answer"),
                Message.user("session-1", "second prompt")
        ));
        when(sessionManager.truncateMessagesFromTurn("session-1", 2)).thenReturn(2);
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setProviderStateJson("{\"codexThreadId\":\"thread-1\",\"other\":\"keep\"}");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));

        Object result = service.rewindTask("task-rewind", "user-1", Map.of(
                "mode", "conversation_fork",
                "turnIndex", 2
        ));

        Map<?, ?> payload = assertInstanceOf(Map.class, result);
        assertEquals("rewound", payload.get("status"));
        assertEquals("second prompt", payload.get("userPrompt"));
        assertEquals(2, payload.get("turnIndex"));
        assertNull(payload.get("codexThreadId"));
        verify(sessionManager).truncateMessagesFromTurn("session-1", 2);
        verify(sessionEntityRepository).save(argThat((SessionEntity saved) ->
                saved.getProviderStateJson() != null
                        && !saved.getProviderStateJson().contains("codexThreadId")
                        && saved.getProviderStateJson().contains("other")
                        && saved.getProviderStateJson().contains("\"schemaVersion\":1")
                        && saved.getProviderStateJson().contains("\"providerType\":\"codex-worker\"")
        ));
    }

    @Test
    void rewindTask_rejectsCodexFileRewind() {
        CodexTaskEntity entity = createTask(
                "task-rewind-file", "session-1", "worker-1", "dir-1", "COMPLETED",
                LocalDateTime.of(2026, 5, 10, 10, 0)
        );
        when(taskRepository.findByTaskId("task-rewind-file")).thenReturn(Optional.of(entity));

        assertThrows(UnsupportedOperationException.class, () ->
                service.rewindTask("task-rewind-file", "user-1", Map.of("mode", "file_rewind")));
    }

    @Test
    void resumeTask_startsNewCodexThreadWhenSessionThreadWasClearedByRewind() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        when(sessionManager.getSession("session-1")).thenReturn(Session.builder()
                .id("session-1")
                .userId("user-1")
                .build());
        SessionEntity existingSession = new SessionEntity();
        existingSession.setId("session-1");
        existingSession.setUserId("user-1");
        existingSession.setAgentId("agent-codex-1");
        existingSession.setProviderType("codex-worker");
        existingSession.setProviderStateJson(null);
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(existingSession));

        DispatchTaskDTO result = service.resumeTask("user-1", "tenant-1", Map.of(
                "workerId", "worker-1",
                "sessionId", "session-1",
                "prompt", "continue after rewind"
        ));

        assertNotNull(result.getTaskId());
        assertNull(result.getCodexThreadId());
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "session-1".equals(event.getSessionId())
                        && "continue after rewind".equals(event.getPrompt())
                        && event.getProviderConfigString("codexThreadId") == null
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

    private CodexTaskEntity[] stubSuccessfulTaskCreation(String sessionId) {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        when(sessionManager.createSession(any())).thenReturn(sessionId);
        return savedTask;
    }

    private LlmModelConfigDTO codexModelConfig(List<String> availableModels) {
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setWorkerBackend("OPENAI_CODEX");
        config.setAvailableModels(availableModels);
        return config;
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
