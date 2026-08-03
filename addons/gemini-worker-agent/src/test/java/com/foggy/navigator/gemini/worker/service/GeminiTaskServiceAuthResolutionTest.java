package com.foggy.navigator.gemini.worker.service;

import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
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
import com.foggy.navigator.gemini.worker.client.GeminiWorkerClient;
import com.foggy.navigator.gemini.worker.model.entity.GeminiTaskEntity;
import com.foggy.navigator.gemini.worker.repository.GeminiTaskRepository;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
    private GeminiStreamRelay streamRelay;
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
        streamRelay = mock(GeminiStreamRelay.class);
        taskService = new GeminiTaskService(taskRepository, workerManagementFacade, eventPublisher);
        ReflectionTestUtils.setField(taskService, "llmModelManager", llmModelManager);
        ReflectionTestUtils.setField(taskService, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(taskService, "sessionEntityRepository", sessionEntityRepository);
        ReflectionTestUtils.setField(taskService, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(taskService, "streamRelay", streamRelay);

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
        assertFalse(taskService instanceof TaskListingProvider);
        assertFalse(taskService instanceof WorkerSessionQueryProvider);
    }

    @Test
    void completeTaskPublishesTenantScopedDefinitiveTerminalEvent() {
        GeminiTaskEntity task = terminalCandidate("task-complete");
        savedTasks.put(task.getTaskId(), task);

        taskService.completeTask(task.getTaskId(), "worker-task-1", "gemini-session-1",
                "done", null, null, null, null, null, "gemini-flash");

        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-complete".equals(event.getTaskId())
                        && "tenant-1".equals(event.getTenantId())
                        && "RUNNING".equals(event.getPreviousStatus())
                        && "COMPLETED".equals(event.getStatus())
                        && Boolean.FALSE.equals(event.getRecoverable())));
    }

    @Test
    void failTaskPublishesTenantScopedDefinitiveTerminalEvent() {
        GeminiTaskEntity task = terminalCandidate("task-fail");
        savedTasks.put(task.getTaskId(), task);

        taskService.failTask(task.getTaskId(), "worker-task-2", "gemini-session-2", "failed");

        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-fail".equals(event.getTaskId())
                        && "tenant-1".equals(event.getTenantId())
                        && "RUNNING".equals(event.getPreviousStatus())
                        && "FAILED".equals(event.getStatus())
                        && Boolean.FALSE.equals(event.getRecoverable())));
    }

    @Test
    void cancelTaskDirectOwnerQualifiedReceiptCommitsAborted() {
        GeminiTaskEntity task = terminalCandidate("task-abort-direct");
        task.setWorkerTaskId("worker-task-abort-direct");
        savedTasks.put(task.getTaskId(), task);
        when(taskRepository.findByTaskIdAndUserId(task.getTaskId(), "user-1"))
                .thenReturn(Optional.of(task));
        when(streamRelay.abortRemoteTask(
                task.getTaskId(), task.getWorkerId(), task.getWorkerTaskId()))
                .thenReturn(new GeminiWorkerClient.AbortReceipt(
                        task.getWorkerTaskId(), "aborted"));

        taskService.cancelTaskDirect(task.getTaskId(), "user-1");

        assertEquals("ABORTED", task.getStatus());
        InOrder order = inOrder(streamRelay, taskRepository, eventPublisher);
        order.verify(streamRelay).abortRemoteTask(
                task.getTaskId(), task.getWorkerId(), task.getWorkerTaskId());
        order.verify(streamRelay).abortStream(task.getTaskId());
        order.verify(taskRepository).save(task);
        order.verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                task.getTaskId().equals(event.getTaskId())
                        && "RUNNING".equals(event.getPreviousStatus())
                        && "ABORTED".equals(event.getStatus())
                        && Boolean.FALSE.equals(event.getRecoverable())));
        verify(taskRepository, never()).findByTaskId(task.getTaskId());
    }

    @Test
    void cancelTaskDirectTerminalNoOpSkipsAllEffects() {
        for (String terminalStatus : new String[]{"COMPLETED", "FAILED", "ABORTED"}) {
            GeminiTaskEntity task = terminalCandidate(
                    "task-abort-terminal-" + terminalStatus.toLowerCase());
            task.setStatus(terminalStatus);
            task.setWorkerTaskId(null);
            when(taskRepository.findByTaskIdAndUserId(task.getTaskId(), "user-1"))
                    .thenReturn(Optional.of(task));

            taskService.cancelTaskDirect(task.getTaskId(), "user-1");

            assertEquals(terminalStatus, task.getStatus());
        }
        verifyNoInteractions(streamRelay);
        verify(taskRepository, never()).save(any());
        verify(sessionTaskRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelTaskDirectRemoteFailureDoesNotMutate() {
        GeminiTaskEntity task = terminalCandidate("task-abort-unconfirmed");
        task.setWorkerTaskId("worker-task-abort-unconfirmed");
        when(taskRepository.findByTaskIdAndUserId(task.getTaskId(), "user-1"))
                .thenReturn(Optional.of(task));
        doThrow(new IllegalStateException(
                GeminiStreamRelay.TERMINATION_GEMINI_UNCONFIRMED))
                .when(streamRelay).abortRemoteTask(
                        task.getTaskId(), task.getWorkerId(), task.getWorkerTaskId());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> taskService.cancelTaskDirect(task.getTaskId(), "user-1"));

        assertEquals(GeminiStreamRelay.TERMINATION_GEMINI_UNCONFIRMED,
                failure.getMessage());
        assertEquals("RUNNING", task.getStatus());
        verify(streamRelay, never()).abortStream(any());
        verify(taskRepository, never()).save(any());
        verify(sessionTaskRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());

        org.mockito.Mockito.reset(streamRelay);
        when(streamRelay.abortRemoteTask(
                task.getTaskId(), task.getWorkerId(), task.getWorkerTaskId()))
                .thenReturn(new GeminiWorkerClient.AbortReceipt(
                        "worker-task-other", "aborted"));

        IllegalStateException receiptMismatch = assertThrows(IllegalStateException.class,
                () -> taskService.cancelTaskDirect(task.getTaskId(), "user-1"));
        assertEquals(GeminiStreamRelay.TERMINATION_GEMINI_UNCONFIRMED,
                receiptMismatch.getMessage());
        assertEquals("RUNNING", task.getStatus());
        verify(streamRelay, never()).abortStream(any());
        verify(taskRepository, never()).save(any());
        verify(sessionTaskRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelTaskDirectRejectsForeignOwnerWithZeroMutation() {
        when(taskRepository.findByTaskIdAndUserId("task-foreign", "user-1"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> taskService.cancelTaskDirect("task-foreign", "user-1"));

        verifyNoInteractions(streamRelay);
        verify(taskRepository, never()).save(any());
        verify(sessionTaskRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void doAbortWorkerTaskRejectsCallerRemoteIdDrift() {
        GeminiTaskEntity task = terminalCandidate("task-abort-a2a-mismatch");
        task.setWorkerTaskId("worker-task-persisted");
        savedTasks.put(task.getTaskId(), task);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> taskService.doAbortWorkerTask(
                        task.getTaskId(), "worker-task-caller"));

        assertEquals("TERMINATION_REMOTE_TASK_MISMATCH", failure.getMessage());
        assertEquals("worker-task-persisted", task.getWorkerTaskId());
        assertEquals("RUNNING", task.getStatus());
        verifyNoInteractions(streamRelay);
        verify(taskRepository, never()).save(any());
        verify(sessionTaskRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());

        task.setStatus("COMPLETED");
        task.setWorkerTaskId(null);
        taskService.doAbortWorkerTask(task.getTaskId(), "worker-task-caller");
        assertEquals("COMPLETED", task.getStatus());
        assertEquals(null, task.getWorkerTaskId());
        verifyNoInteractions(streamRelay);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void doAbortWorkerTaskExactReceiptCommitsAborted() {
        GeminiTaskEntity task = terminalCandidate("task-abort-a2a");
        task.setWorkerTaskId("worker-task-a2a");
        savedTasks.put(task.getTaskId(), task);
        when(streamRelay.abortRemoteTask(
                task.getTaskId(), task.getWorkerId(), task.getWorkerTaskId()))
                .thenReturn(new GeminiWorkerClient.AbortReceipt(
                        task.getWorkerTaskId(), "aborted"));

        taskService.doAbortWorkerTask(task.getTaskId(), task.getWorkerTaskId());

        assertEquals("ABORTED", task.getStatus());
        verify(streamRelay).abortStream(task.getTaskId());
        verify(taskRepository).save(task);
        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                task.getTaskId().equals(event.getTaskId())
                        && "ABORTED".equals(event.getStatus())));
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

    private GeminiTaskEntity terminalCandidate(String taskId) {
        GeminiTaskEntity task = new GeminiTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId("session-1");
        task.setWorkerId("worker-1");
        task.setUserId("user-1");
        task.setTenantId("tenant-1");
        task.setPrompt("prompt");
        task.setStatus("RUNNING");
        return task;
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
