package com.foggy.navigator.gemini.worker.service;

import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.model.GeminiConfig;
import com.foggy.navigator.gemini.worker.model.entity.GeminiTaskEntity;
import com.foggy.navigator.gemini.worker.repository.GeminiTaskRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiTaskServiceAuthResolutionTest {

    private GeminiTaskRepository taskRepository;
    private WorkerManagementFacade workerManagementFacade;
    private ApplicationEventPublisher eventPublisher;
    private LlmModelManager llmModelManager;
    private com.foggy.navigator.agent.framework.session.SessionManager sessionManager;
    private GeminiTaskService taskService;
    private Map<String, GeminiTaskEntity> savedTasks;

    @BeforeEach
    void setUp() {
        taskRepository = mock(GeminiTaskRepository.class);
        workerManagementFacade = mock(WorkerManagementFacade.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        llmModelManager = mock(LlmModelManager.class);
        sessionManager = mock(com.foggy.navigator.agent.framework.session.SessionManager.class);
        taskService = new GeminiTaskService(taskRepository, workerManagementFacade, eventPublisher);
        ReflectionTestUtils.setField(taskService, "llmModelManager", llmModelManager);
        ReflectionTestUtils.setField(taskService, "sessionManager", sessionManager);

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
}
