package com.foggy.navigator.gemini.worker.service;

import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.model.GeminiConfig;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.gemini.worker.model.entity.GeminiTaskEntity;
import com.foggy.navigator.gemini.worker.repository.GeminiTaskRepository;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GeminiTaskServiceAuthResolutionTest {

    private GeminiTaskRepository taskRepository;
    private WorkerManagementFacade workerManagementFacade;
    private ApplicationEventPublisher eventPublisher;
    private LlmModelManager llmModelManager;
    private com.foggy.navigator.agent.framework.session.SessionManager sessionManager;
    private SessionEntityRepository sessionEntityRepository;
    private SessionTaskRepository sessionTaskRepository;
    private GeminiTaskService taskService;
    private Map<String, GeminiTaskEntity> savedTasks;

    @BeforeEach
    void setUp() {
        taskRepository = mock(GeminiTaskRepository.class);
        workerManagementFacade = mock(WorkerManagementFacade.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        llmModelManager = mock(LlmModelManager.class);
        sessionManager = mock(com.foggy.navigator.agent.framework.session.SessionManager.class);
        sessionEntityRepository = mock(SessionEntityRepository.class);
        sessionTaskRepository = mock(SessionTaskRepository.class);
        taskService = new GeminiTaskService(taskRepository, workerManagementFacade, eventPublisher);
        ReflectionTestUtils.setField(taskService, "llmModelManager", llmModelManager);
        ReflectionTestUtils.setField(taskService, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(taskService, "sessionEntityRepository", sessionEntityRepository);
        ReflectionTestUtils.setField(taskService, "sessionTaskRepository", sessionTaskRepository);

        // Wire save() and findByTaskId() to a shared map so flows that round-trip via
        // findByTaskId (e.g. createTaskDirect) see what was just persisted.
        savedTasks = new HashMap<>();
        when(taskRepository.save(any())).thenAnswer(invocation -> {
            GeminiTaskEntity saved = invocation.getArgument(0);
            if (saved.getTaskId() != null) {
                savedTasks.put(saved.getTaskId(), saved);
            }
            return saved;
        });
        when(taskRepository.findByTaskId(any())).thenAnswer(invocation ->
                Optional.ofNullable(savedTasks.get((String) invocation.getArgument(0))));
        when(sessionTaskRepository.findByTaskId(any())).thenReturn(Optional.empty());
        when(sessionTaskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionEntityRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void exposesOnlySupportedTaskProviderPorts() {
        assertInstanceOf(TaskLookupProvider.class, taskService);
        assertInstanceOf(TaskCommandProvider.class, taskService);
        assertFalse(taskService instanceof TaskQueryProvider);
        assertFalse(taskService instanceof TaskListingProvider);
        assertFalse(taskService instanceof WorkerSessionQueryProvider);
    }

    @Test
    void createTaskUsesModelConfigBaseUrlInsteadOfWorkerServiceBaseUrl() {
        when(workerManagementFacade.getGeminiConfig("worker-1"))
                .thenReturn(new GeminiConfig("http://127.0.0.1:3071", null, "gemini-flash"));

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setId("cfg-gemini");
        modelConfig.setModelName("gemini-flash");
        modelConfig.setBaseUrl("https://generativelanguage.googleapis.com");
        modelConfig.setEnvVars(Map.of("GOOGLE_CLOUD_PROJECT", "foggy-dev"));
        when(llmModelManager.getModelConfig("cfg-gemini")).thenReturn(Optional.of(modelConfig));

        var form = new com.foggy.navigator.gemini.worker.model.form.CreateGeminiTaskForm();
        form.setWorkerId("worker-1");
        form.setPrompt("ping");
        form.setModelConfigId("cfg-gemini");

        taskService.createTask("user-1", "tenant-1", form);

        var captor = org.mockito.ArgumentCaptor.forClass(WorkerTaskStartEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        WorkerTaskStartEvent event = captor.getValue();

        assertEquals("https://generativelanguage.googleapis.com", event.getProviderConfigString("baseUrl"));
        @SuppressWarnings("unchecked")
        Map<String, String> extraEnvVars = (Map<String, String>) event.getProviderConfig().get("extraEnvVars");
        assertEquals("foggy-dev", extraEnvVars.get("GOOGLE_CLOUD_PROJECT"));
    }

    @Test
    void createTaskDoesNotTreatWorkerServiceBaseUrlAsGeminiApiBaseUrl() {
        when(workerManagementFacade.getGeminiConfig("worker-1"))
                .thenReturn(new GeminiConfig("http://127.0.0.1:3071", null, "gemini-flash"));
        when(sessionManager.createSession(any())).thenReturn("session-created-1");

        var form = new com.foggy.navigator.gemini.worker.model.form.CreateGeminiTaskForm();
        form.setWorkerId("worker-1");
        form.setPrompt("ping");

        taskService.createTask("user-1", "tenant-1", form);

        var captor = org.mockito.ArgumentCaptor.forClass(WorkerTaskStartEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        WorkerTaskStartEvent event = captor.getValue();

        assertNull(event.getProviderConfigString("baseUrl"));
        assertNull(event.getApiKey());
    }

    @Test
    void createTaskDirectCreatesPlatformSessionAndStoresUserPromptWhenSessionMissing() {
        when(workerManagementFacade.getGeminiConfig("worker-1"))
                .thenReturn(new GeminiConfig("http://127.0.0.1:3071", null, "gemini-flash"));
        when(sessionManager.createSession(any())).thenReturn("session-created-1");

        var result = taskService.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hi3",
                "cwd", "D:/tmp"
        ), "user-1", "tenant-1");

        assertEquals("session-created-1", result.getSessionId());
        verify(sessionManager).createSession(argThat(req ->
                "user-1".equals(req.getUserId())
                        && "tenant-1".equals(req.getTenantId())
                        && "gemini-worker".equals(req.getProviderType())));
        verify(sessionManager).addMessage(eq("session-created-1"), argThat((Message msg) ->
                msg != null
                        && MessageRole.USER.equals(msg.getRole())
                        && "hi3".equals(msg.getContent())));
    }

    @Test
    void createTaskDirectReusesExistingSessionAndAppendsUserPrompt() {
        when(workerManagementFacade.getGeminiConfig("worker-1"))
                .thenReturn(new GeminiConfig("http://127.0.0.1:3071", null, "gemini-flash"));
        Session session = Session.builder().id("session-existing-1").userId("user-1").build();
        when(sessionManager.getSession("session-existing-1")).thenReturn(session);

        var result = taskService.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hi again",
                "sessionId", "session-existing-1"
        ), "user-1", "tenant-1");

        assertEquals("session-existing-1", result.getSessionId());
        verify(sessionManager, never()).createSession(any());
        verify(sessionManager).addMessage(eq("session-existing-1"), argThat((Message msg) ->
                msg != null
                        && MessageRole.USER.equals(msg.getRole())
                        && "hi again".equals(msg.getContent())));
    }

    @Test
    void createTaskDirectWritesSchemaVersionedGeminiProviderState() {
        when(workerManagementFacade.getGeminiConfig("worker-1"))
                .thenReturn(new GeminiConfig("http://127.0.0.1:3071", null, "gemini-flash"));
        when(sessionManager.createSession(any())).thenReturn("session-created-1");
        SessionEntity session = new SessionEntity();
        session.setId("session-created-1");
        session.setProviderStateJson("{\"other\":\"keep\"}");
        when(sessionEntityRepository.findById("session-created-1")).thenReturn(Optional.of(session));
        SessionTaskEntity projection = new SessionTaskEntity();
        projection.setTaskStateJson("{\"originalTaskId\":\"task-original\"}");
        when(sessionTaskRepository.findByTaskId(any())).thenReturn(Optional.of(projection));

        var result = taskService.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hi",
                "geminiSessionId", "gemini-session-1"
        ), "user-1", "tenant-1");

        assertEquals("gemini-session-1", result.getGeminiSessionId());
        verify(sessionEntityRepository).save(argThat((SessionEntity saved) ->
                saved.getProviderStateJson() != null
                        && saved.getProviderStateJson().contains("\"schemaVersion\":1")
                        && saved.getProviderStateJson().contains("\"providerType\":\"gemini-worker\"")
                        && saved.getProviderStateJson().contains("\"geminiSessionId\":\"gemini-session-1\"")
                        && saved.getProviderStateJson().contains("\"other\":\"keep\"")
        ));
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity saved) -> {
            Map<String, Object> state = ProviderStateCodec.parseObject(saved.getTaskStateJson());
            return Integer.valueOf(ProviderStateCodec.CURRENT_SCHEMA_VERSION).equals(state.get(ProviderStateCodec.FIELD_SCHEMA_VERSION))
                    && "gemini-worker".equals(state.get(ProviderStateCodec.FIELD_PROVIDER_TYPE))
                    && "gemini-session-1".equals(state.get(ProviderStateCodec.FIELD_GEMINI_SESSION_ID))
                    && "task-original".equals(state.get("originalTaskId"));
        }));
    }

    @Test
    void resumeTaskReadsSchemaVersionedGeminiProviderState() {
        when(workerManagementFacade.getGeminiConfig("worker-1"))
                .thenReturn(new GeminiConfig("http://127.0.0.1:3071", null, "gemini-flash"));
        when(taskRepository.existsByGeminiSessionIdAndWorkerIdAndUserId(
                "gemini-session-1", "worker-1", "user-1")).thenReturn(true);
        when(taskRepository.existsByGeminiSessionIdAndWorkerIdAndUserIdAndStatus(
                "gemini-session-1", "worker-1", "user-1", "RUNNING")).thenReturn(false);
        when(sessionManager.getSession("session-existing-1")).thenReturn(
                Session.builder().id("session-existing-1").userId("user-1").build());
        SessionEntity session = new SessionEntity();
        session.setId("session-existing-1");
        session.setProviderStateJson("{\"schemaVersion\":1,\"providerType\":\"gemini-worker\",\"geminiSessionId\":\"gemini-session-1\"}");
        when(sessionEntityRepository.findById("session-existing-1")).thenReturn(Optional.of(session));

        var result = taskService.resumeTask("user-1", "tenant-1", Map.of(
                "workerId", "worker-1",
                "sessionId", "session-existing-1",
                "prompt", "continue"
        ));

        assertEquals("gemini-session-1", result.getGeminiSessionId());
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "gemini-session-1".equals(event.getProviderConfigString("geminiSessionId"))));
    }

    @Test
    void deleteTaskRemovesGeminiTaskWithoutDeletingSession() {
        GeminiTaskEntity task = new GeminiTaskEntity();
        task.setTaskId("task-1");
        task.setUserId("user-1");
        task.setSessionId("session-1");
        task.setStatus("COMPLETED");

        when(taskRepository.findByTaskIdAndUserId("task-1", "user-1")).thenReturn(Optional.of(task));

        taskService.deleteTask("user-1", "task-1");

        verify(taskRepository).delete(task);
        verifyNoInteractions(sessionManager);
        verify(sessionEntityRepository, never()).save(any());
    }

    @Test
    void deleteTaskRejectsRunningTask() {
        GeminiTaskEntity task = new GeminiTaskEntity();
        task.setTaskId("task-1");
        task.setUserId("user-1");
        task.setStatus("RUNNING");

        when(taskRepository.findByTaskIdAndUserId("task-1", "user-1")).thenReturn(Optional.of(task));

        assertThrows(IllegalStateException.class, () -> taskService.deleteTask("user-1", "task-1"));
        verify(taskRepository, never()).delete(any());
    }
}
