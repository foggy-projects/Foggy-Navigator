package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskAcceptanceDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.dto.NativeSubtaskSnapshotDTO;
import com.foggy.navigator.common.dto.NativeSubtaskUpdatePayload;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.session.event.SessionEventListener;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexStreamRelayTest {

    private CodexTaskRepository taskRepository;
    private CodexWorkerClientFactory clientFactory;
    private WorkerManagementFacade workerManagementFacade;
    private CodexTaskService taskService;
    private CodexRuntimeRegistryService runtimeRegistryService;
    private CodexTaskRuntimeStateService taskRuntimeStateService;
    private CodexAppServerAcceptanceService appServerAcceptanceService;
    private CodexNativeSubtaskService nativeSubtaskService;
    private CodexWorkerClient client;
    private ApplicationEventPublisher eventPublisher;
    private SessionEventListener sessionEventListener;
    private CodexStreamRelay relay;

    @BeforeEach
    void setUp() {
        taskRepository = mock(CodexTaskRepository.class);
        clientFactory = mock(CodexWorkerClientFactory.class);
        workerManagementFacade = mock(WorkerManagementFacade.class);
        taskService = mock(CodexTaskService.class);
        runtimeRegistryService = mock(CodexRuntimeRegistryService.class);
        taskRuntimeStateService = mock(CodexTaskRuntimeStateService.class);
        appServerAcceptanceService = new CodexAppServerAcceptanceService(taskRuntimeStateService);
        nativeSubtaskService = mock(CodexNativeSubtaskService.class);
        client = mock(CodexWorkerClient.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        sessionEventListener = mock(SessionEventListener.class);

        relay = new CodexStreamRelay(
                workerManagementFacade,
                clientFactory,
                taskService,
                runtimeRegistryService,
                taskRuntimeStateService,
                appServerAcceptanceService,
                nativeSubtaskService,
                taskRepository,
                eventPublisher,
                new ObjectMapper(),
                sessionEventListener
        );
        org.mockito.Mockito.lenient().when(runtimeRegistryService.resolveBoundRuntime(any(), any(), any(), any()))
                .thenAnswer(invocation -> CodexRuntimeBinding.legacySdk(invocation.getArgument(2)));
        org.mockito.Mockito.lenient().when(taskRuntimeStateService.markSubscribed(any()))
                .thenReturn(true);
        org.mockito.Mockito.lenient().when(taskRuntimeStateService.claimAbort(any()))
                .thenReturn(CodexTaskRuntimeStateService.AbortClaim.REMOTE_REQUIRED);
    }

    @AfterEach
    void tearDown() {
        relay.abortStream("local-task-1");
    }

    @Test
    void reconnectTaskUsesPersistedWorkerTaskIdAndAckSeq() {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setWorkerId("worker-1");
        entity.setSessionId("session-1");
        entity.setWorkerTaskId("worker-task-9");
        entity.setCodexThreadId("thread-1");
        entity.setLastAckedSeq(7);

        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:3051")
                        .authToken("worker-token")
                        .build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://localhost:3051", "worker-token"))
                .thenReturn(client);
        when(client.subscribeToTask("worker-task-9", 7)).thenReturn(Flux.never());

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client).subscribeToTask("worker-task-9", 7);
    }

    @Test
    void streamQueryErrorBeforeWorkerTaskIdFailsLocalTaskWithoutReconnect() {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setWorkerId("worker-1");
        entity.setSessionId("session-1");

        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:3051")
                        .authToken("worker-token")
                        .build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://localhost:3051", "worker-token"))
                .thenReturn(client);
        when(client.streamQuery(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("401 Unauthorized")));

        relay.onTaskStart(WorkerTaskStartEvent.builder()
                .taskId("local-task-1")
                .sessionId("session-1")
                .workerId("worker-1")
                .prompt("hello")
                .cwd("D:/repo")
                .model("gpt-5.5")
                .providerType("codex-worker")
                .build());

        verify(taskService).failTask(eq("local-task-1"), isNull(), isNull(),
                eq("CODEX_WORKER_STREAM_FAILED_BEFORE_ACCEPTANCE"));
        verify(client, never()).subscribeToTask(any(), anyInt());
    }

    @Test
    void onTaskStartForCodexBizWorkerForwardsCodexBizRuntimeOptions() {
        CodexTaskEntity entity = legacyTask();
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:3051")
                        .authToken("worker-token")
                        .build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://localhost:3051", "worker-token"))
                .thenReturn(client);
        when(client.streamQuery(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.never());

        relay.onTaskStart(WorkerTaskStartEvent.builder()
                .taskId("local-task-1")
                .sessionId("session-1")
                .workerId("worker-1")
                .prompt("hello")
                .cwd("D:/repo")
                .model("gpt-5.5")
                .apiKey("sk-test")
                .providerType("codex-biz-worker")
                .providerConfig(Map.of(
                        "codexHomeKey", "tenant/world-sim/scenario-1/actor-1",
                        "developerInstructions", "Return ActorDecisionResult JSON.",
                        "sandboxMode", "workspace-write",
                        "approvalPolicy", "never",
                        "networkAccessEnabled", false,
                        "webSearchMode", "disabled",
                        "businessRuntimeContext", Map.of("task_scoped_token", "token-1"),
                        "additionalDirectories", List.of("/home/sa/workspace/shared")
                ))
                .build());

        verify(client).streamQuery(
                eq("hello"),
                eq("D:/repo"),
                isNull(),
                eq("gpt-5.5"),
                isNull(),
                isNull(),
                isNull(),
                eq("sk-test"),
                isNull(),
                isNull(),
                eq("tenant/world-sim/scenario-1/actor-1"),
                eq("Return ActorDecisionResult JSON."),
                isNull(),
                isNull(),
                eq("workspace-write"),
                eq("never"),
                eq(false),
                eq("disabled"),
                eq(Map.of("task_scoped_token", "token-1")),
                eq(List.of("/home/sa/workspace/shared")));
    }

    @Test
    void appServerPersistsAcceptanceBeforeSubscribe() {
        CodexTaskEntity entity = stubAppServerTask("PREPARED");
        Map<String, Object> request = Map.of("prompt", "hello", "model", "codex-ultra");
        stubBuiltRequest(request);
        CodexTaskAcceptanceDTO acceptance = acceptance("local-task-1");
        when(client.createTask("local-task-1", request)).thenReturn(Mono.just(acceptance));
        when(client.subscribeToTask("local-task-1", 0)).thenReturn(Flux.never());

        relay.onTaskStart(startEvent("codex-ultra"));

        var ordered = inOrder(taskRuntimeStateService, client);
        ordered.verify(taskRuntimeStateService).prepareAcceptance("local-task-1", request);
        ordered.verify(client).createTask("local-task-1", request);
        ordered.verify(taskRuntimeStateService).recordAccepted("local-task-1", "local-task-1");
        ordered.verify(taskRuntimeStateService).markSubscribed("local-task-1");
        ordered.verify(client).subscribeToTask("local-task-1", 0);
        verify(client, never()).streamQuery(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertEquals("app-main", entity.getRuntimeId());
    }

    @Test
    void appServerUnknownAcceptanceRetriesOnlySameIdempotencyKeyAndRuntime() {
        stubAppServerTask("PREPARED");
        Map<String, Object> request = Map.of("prompt", "hello");
        stubBuiltRequest(request);
        when(client.createTask("local-task-1", request))
                .thenReturn(Mono.error(new RuntimeException("connection reset")));

        relay.onTaskStart(startEvent("codex-ultra"));

        verify(client, times(3)).createTask("local-task-1", request);
        verify(taskRuntimeStateService).markAcceptanceUnknown("local-task-1");
        verify(client, never()).streamQuery(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void appServerIdempotencyConflictIsStableFailureWithoutRetry() {
        stubAppServerTask("PREPARED");
        Map<String, Object> request = Map.of("prompt", "hello");
        stubBuiltRequest(request);
        WebClientResponseException conflict = WebClientResponseException.create(
                409, "Conflict", null, new byte[0], null);
        when(client.createTask("local-task-1", request)).thenReturn(Mono.error(conflict));

        relay.onTaskStart(startEvent("codex-ultra"));

        verify(client).createTask("local-task-1", request);
        verify(taskService).failTask(eq("local-task-1"), isNull(), isNull(),
                contains("CODEX_RUNTIME_IDEMPOTENCY_CONFLICT"));
        verify(taskRuntimeStateService, never()).markAcceptanceUnknown(any());
    }

    @Test
    void failureAfterAcceptanceDoesNotMarkPotentiallyRunningTaskFailed() {
        stubAppServerTask("PREPARED");
        Map<String, Object> request = Map.of("prompt", "hello");
        stubBuiltRequest(request);
        when(client.createTask("local-task-1", request))
                .thenReturn(Mono.just(acceptance("local-task-1")));
        doThrow(new IllegalStateException("database unavailable"))
                .when(taskRuntimeStateService).markSubscribed("local-task-1");

        relay.onTaskStart(startEvent("codex-ultra"));

        verify(taskRuntimeStateService).recordAccepted("local-task-1", "local-task-1");
        verify(taskService, never()).failTask(eq("local-task-1"), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTING", "ACCEPTED"})
    void applicationReadyRecoveryRecreatesMissingWorkerTaskIdWithEncryptedEnvelope(String state) {
        CodexTaskEntity entity = stubAppServerTask(state);
        when(taskRepository.findByStatusIn(List.of("RUNNING"))).thenReturn(List.of(entity));
        Map<String, Object> request = Map.of("prompt", "hello");
        when(taskRuntimeStateService.loadPreparedRequest("local-task-1")).thenReturn(request);
        when(client.createTask("local-task-1", request)).thenReturn(Mono.just(acceptance("local-task-1")));
        doAnswer(invocation -> {
            entity.setWorkerTaskId(invocation.getArgument(1));
            return null;
        }).when(taskRuntimeStateService).recordAccepted("local-task-1", "local-task-1");
        when(client.subscribeToTask("local-task-1", 0)).thenReturn(Flux.never());

        relay.reconnectActiveTasks();

        verify(taskRuntimeStateService).loadPreparedRequest("local-task-1");
        verify(client).createTask("local-task-1", request);
        verify(taskRuntimeStateService).recordAccepted("local-task-1", "local-task-1");
        verify(client).subscribeToTask("local-task-1", 0);
    }

    @Test
    void applicationReadyFailsPreparedTaskThatNeverStartedRemoteAcceptance() {
        CodexTaskEntity entity = stubAppServerTask("PREPARED");
        when(taskRepository.findByStatusIn(List.of("RUNNING"))).thenReturn(List.of(entity));
        when(taskService.failTaskIfAcceptanceNotStarted(
                "local-task-1", "CODEX_RUNTIME_NOT_ACCEPTED")).thenReturn(true);

        relay.reconnectActiveTasks();

        verify(taskService).failTaskIfAcceptanceNotStarted(
                "local-task-1", "CODEX_RUNTIME_NOT_ACCEPTED");
        verify(taskRuntimeStateService, never()).loadPreparedRequest(any());
        verify(client, never()).createTask(any(), any());
    }

    @Test
    void abortRecoversUnknownAcceptanceBeforeConfirmingLocalAbort() {
        CodexTaskEntity entity = stubAppServerTask("UNKNOWN");
        Map<String, Object> request = Map.of("prompt", "hello");
        when(taskRuntimeStateService.loadPreparedRequest("local-task-1")).thenReturn(request);
        when(client.createTask("local-task-1", request))
                .thenReturn(Mono.just(acceptance("local-task-1")));
        when(client.abortTask("local-task-1")).thenReturn(Mono.just(Map.of(
                "task_id", "local-task-1", "status", "terminal", "outcome", "aborted")));
        when(client.getTaskStatus("local-task-1")).thenReturn(Mono.just(Map.of(
                "task_id", "local-task-1", "status", "terminal", "outcome", "aborted")));

        var resolution = relay.abortRemoteTask(entity);

        assertEquals("aborted", resolution.outcome());
        verify(client).createTask("local-task-1", request);
        verify(taskRuntimeStateService).recordAccepted("local-task-1", "local-task-1");
        verify(client).abortTask("local-task-1");
        verify(client).getTaskStatus("local-task-1");
    }

    @Test
    void unconfirmedAppServerAbortDoesNotReturnSuccess() {
        CodexTaskEntity entity = stubAppServerTask("ACCEPTED");
        entity.setWorkerTaskId("worker-task-9");
        when(client.abortTask("worker-task-9"))
                .thenReturn(Mono.error(new RuntimeException("connection reset")));

        assertThrows(IllegalStateException.class, () -> relay.abortRemoteTask(entity));
    }

    @Test
    void appServerAbortDoesNotConfirmWhileRemoteTaskIsStillRunning() {
        CodexTaskEntity entity = stubAppServerTask("ACCEPTED");
        entity.setWorkerTaskId("worker-task-9");
        when(client.abortTask("worker-task-9")).thenReturn(Mono.just(Map.of(
                "task_id", "worker-task-9", "status", "accepted")));
        when(client.getTaskStatus("worker-task-9")).thenReturn(Mono.just(Map.of(
                "task_id", "worker-task-9", "status", "running")));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> relay.abortRemoteTask(entity));

        assertTrue(error.getMessage().startsWith("CODEX_RUNTIME_ABORT_UNKNOWN"));
        verify(client, times(5)).getTaskStatus("worker-task-9");
        verify(taskService, never()).reconcileAbortedTask(any(), any(), any());
    }

    @Test
    void appServerAbortReturnsRemoteCompletedOutcomeInsteadOfAborted() {
        CodexTaskEntity entity = stubAppServerTask("COMMITTED");
        entity.setWorkerTaskId("worker-task-9");
        when(client.abortTask("worker-task-9")).thenReturn(Mono.just(Map.of(
                "task_id", "worker-task-9", "status", "accepted")));
        when(client.getTaskStatus("worker-task-9")).thenReturn(Mono.just(Map.of(
                "task_id", "worker-task-9",
                "status", "terminal",
                "outcome", "completed",
                "thread_id", "thread-9",
                "model", "gpt-5.6-sol")));

        var resolution = relay.abortRemoteTask(entity);

        assertEquals("completed", resolution.outcome());
        assertEquals("thread-9", resolution.codexThreadId());
        assertEquals("gpt-5.6-sol", resolution.model());
    }

    @Test
    void sseCompletionWithoutTerminalEventDoesNotPersistNullCompletion() {
        CodexTaskEntity entity = stubAppServerTask("SUBSCRIBED");
        entity.setWorkerTaskId("worker-task-9");
        when(client.getTaskStatus("worker-task-9")).thenReturn(Mono.just(Map.of(
                "task_id", "worker-task-9",
                "status", "terminal",
                "outcome", "completed",
                "thread_id", "thread-9",
                "model", "gpt-5.6-sol")));

        ReflectionTestUtils.invokeMethod(
                relay,
                "subscribeSseFlux",
                Flux.empty(),
                "local-task-1",
                "session-1",
                "worker-1",
                "codex-worker",
                 new java.util.concurrent.atomic.AtomicReference<String>(),
                 new java.util.concurrent.atomic.AtomicReference<String>(),
                 0,
                 0);

        verify(taskService, never()).completeTask(
                eq("local-task-1"), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(client).getTaskStatus("worker-task-9");
        verify(taskService, never()).failTask(eq("local-task-1"), any(), any(), any());
    }

    @Test
    void acceptedAppServerStreamExhaustionKeepsLocalTaskRunningWhenRemoteIsNonTerminal() {
        CodexTaskEntity entity = stubAppServerTask("SUBSCRIBED");
        entity.setWorkerTaskId("worker-task-9");
        when(client.getTaskStatus("worker-task-9")).thenReturn(Mono.just(Map.of(
                "task_id", "worker-task-9", "status", "running")));

        ReflectionTestUtils.invokeMethod(
                relay,
                "subscribeSseFlux",
                Flux.error(new RuntimeException("sentinel endpoint detail")),
                "local-task-1",
                "session-1",
                "worker-1",
                "codex-worker",
                 new java.util.concurrent.atomic.AtomicReference<String>(),
                 new java.util.concurrent.atomic.AtomicReference<String>(),
                 3,
                 0);

        verify(taskService, never()).failTask(eq("local-task-1"), any(), any(), any());
        verify(client).getTaskStatus("worker-task-9");
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AgentMessage message = assertInstanceOf(AgentMessage.class, eventCaptor.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("CODEX_RUNTIME_RESULT_UNKNOWN", payload.get("content"));
    }

    @Test
    void reconnectingAcceptedTaskConsumesFinalRemoteResultWithoutRecreatingTask() {
        CodexTaskEntity entity = stubAppServerTask("SUBSCRIBED");
        entity.setWorkerTaskId("worker-task-9");
        entity.setLastAckedSeq(8);
        when(client.subscribeToTask("worker-task-9", 8)).thenReturn(Flux.just(
                ServerSentEvent.builder("""
                        {
                          "type":"result",
                          "task_id":"worker-task-9",
                          "session_id":"thread-9",
                          "seq":9,
                          "result":"done",
                          "model":"gpt-5.6-sol"
                        }
                        """).build()));
        doAnswer(invocation -> {
            entity.setStatus("COMPLETED");
            return null;
        }).when(taskService).completeTask(
                eq("local-task-1"), eq("worker-task-9"), eq("thread-9"), eq("done"),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq("gpt-5.6-sol"));

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client).subscribeToTask("worker-task-9", 8);
        verify(taskService).completeTask(
                "local-task-1", "worker-task-9", "thread-9", "done",
                null, null, null, null, null, "gpt-5.6-sol");
        verify(client, never()).createTask(any(), any());
    }

    @Test
    void nativeSubtaskUpdatePersistsSnapshotThenPublishesUnifiedEvent() {
        NativeSubtaskSnapshotDTO snapshot = NativeSubtaskSnapshotDTO.builder()
                .subtaskId("child-thread-1")
                .depth(1)
                .label("reviewer")
                .role("review")
                .status("running")
                .activity("working")
                .startedAt(Instant.parse("2026-07-10T01:00:00Z"))
                .updatedAt(Instant.parse("2026-07-10T01:00:01Z"))
                .lastEventSeq(12)
                .build();
        when(nativeSubtaskService.applyUpdate(
                eq("local-task-1"), eq("session-1"), eq("codex-worker"), eq(12),
                any(NativeSubtaskUpdatePayload.class)))
                .thenReturn(Optional.of(snapshot));

        String workerJson = """
                {
                  "type":"native_subtask_update",
                  "task_id":"worker-task-1",
                  "session_id":"root-thread-1",
                  "seq":12,
                  "data":{
                    "contract_version":1,
                    "subtask_id":"child-thread-1",
                    "depth":1,
                    "label":"reviewer",
                    "role":"review",
                    "status":"running",
                    "activity":"working",
                    "started_at":"2026-07-10T01:00:00Z",
                    "updated_at":"2026-07-10T01:00:01Z"
                  }
                }
                """;

        ReflectionTestUtils.invokeMethod(
                relay,
                "handleSseEvent",
                ServerSentEvent.builder(workerJson).build(),
                "local-task-1",
                "session-1",
                "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicInteger(11));

        verify(taskService).recordWorkerProgress(
                "local-task-1", "worker-task-1", "root-thread-1", null, 12, false, true);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AgentMessage message = assertInstanceOf(AgentMessage.class, eventCaptor.getValue());
        assertEquals(MessageType.NATIVE_SUBTASK_UPDATE, message.getType());
        assertEquals("native-subtask:local-task-1:12", message.getMessageId());
        assertEquals("local-task-1", message.getTaskId());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals(12, payload.get("lastEventSeq"));
        @SuppressWarnings("unchecked")
        Map<String, Object> subtask = (Map<String, Object>) payload.get("subtask");
        assertEquals("child-thread-1", subtask.get("subtaskId"));
        assertEquals("running", subtask.get("status"));
        assertEquals(12, subtask.get("lastEventSeq"));
    }

    @Test
    void syncCheckpointAdvancesAckWithoutConfirmingExecutionOrPublishingUi() {
        String workerJson = """
                {
                  "type":"assistant_text",
                  "subtype":"sync_checkpoint",
                  "task_id":"worker-task-1",
                  "session_id":"thread-1",
                  "seq":7,
                  "content":"checkpoint"
                }
                """;

        ReflectionTestUtils.invokeMethod(
                relay,
                "handleSseEvent",
                ServerSentEvent.builder(workerJson).build(),
                "local-task-1",
                "session-1",
                "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicInteger(6));

        verify(taskService).recordWorkerProgress(
                "local-task-1", "worker-task-1", "thread-1", null, 7, false, false);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void executionCommittedControlEventOnlyPersistsCommitState() {
        String workerJson = """
                {
                  "type":"system",
                  "subtype":"execution_committed",
                  "task_id":"worker-task-1",
                  "session_id":"thread-1",
                  "seq":8
                }
                """;

        ReflectionTestUtils.invokeMethod(
                relay,
                "handleSseEvent",
                ServerSentEvent.builder(workerJson).build(),
                "local-task-1",
                "session-1",
                "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicInteger(7));

        verify(taskService).recordWorkerProgress(
                "local-task-1", "worker-task-1", "thread-1", null, 8, false, true);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void durableMessageFailureTerminatesStreamBeforeHigherSequenceCanAck() {
        CodexTaskEntity entity = stubAppServerTask("SUBSCRIBED");
        entity.setWorkerTaskId("worker-task-1");
        when(client.getTaskStatus("worker-task-1"))
                .thenReturn(Mono.just(Map.of("task_id", "worker-task-1", "status", "running")));
        doThrow(new IllegalStateException("database unavailable"))
                .when(sessionEventListener).handleMessageDurably(any(AgentMessage.class));

        Flux<ServerSentEvent<String>> events = Flux.just(
                workerEvent("assistant_text", 1, "first"),
                workerEvent("assistant_text", 2, "second"));

        ReflectionTestUtils.invokeMethod(
                relay, "subscribeSseFlux", events,
                "local-task-1", "session-1", "worker-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(), 0, 0);

        verify(sessionEventListener, times(1)).handleMessageDurably(any(AgentMessage.class));
        verify(taskService, never()).recordWorkerProgress(
                eq("local-task-1"), any(), any(), any(), eq(1), anyBoolean(), anyBoolean());
        verify(taskService, never()).recordWorkerProgress(
                eq("local-task-1"), any(), any(), any(), eq(2), anyBoolean(), anyBoolean());
    }

    @Test
    void sequenceGapTerminatesStreamWithoutPublishingOrAdvancingAck() {
        CodexTaskEntity entity = stubAppServerTask("SUBSCRIBED");
        entity.setWorkerTaskId("worker-task-1");
        when(client.getTaskStatus("worker-task-1"))
                .thenReturn(Mono.just(Map.of("task_id", "worker-task-1", "status", "running")));

        ReflectionTestUtils.invokeMethod(
                relay, "subscribeSseFlux", Flux.just(workerEvent("assistant_text", 2, "gap")),
                "local-task-1", "session-1", "worker-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(), 0, 0);

        verifyNoInteractions(sessionEventListener);
        verifyNoInteractions(taskService);
    }

    @Test
    void duplicateSequenceIsSkippedBeforeNextContiguousEvent() {
        CodexTaskEntity entity = stubAppServerTask("SUBSCRIBED");
        entity.setWorkerTaskId("worker-task-1");

        Flux<ServerSentEvent<String>> events = Flux.concat(
                Flux.just(
                        workerEvent("assistant_text", 1, "duplicate"),
                        workerEvent("assistant_text", 2, "next")),
                Flux.never());
        ReflectionTestUtils.invokeMethod(
                relay, "subscribeSseFlux", events,
                "local-task-1", "session-1", "worker-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(), 0, 1);

        verify(sessionEventListener, times(1)).handleMessageDurably(any(AgentMessage.class));
        verify(taskService).recordWorkerProgress(
                "local-task-1", "worker-task-1", "thread-1", null, 2, false, true);
    }

    @Test
    void lateTerminalEventOnlyAdvancesCursorWithoutPublishingContradictoryOutcome() {
        CodexTaskEntity entity = stubAppServerTask("TERMINAL");
        entity.setWorkerTaskId("worker-task-1");
        entity.setStatus("COMPLETED");

        ReflectionTestUtils.invokeMethod(
                relay, "handleSseEvent", workerEvent("error", 1, "late failure"),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicInteger(0));

        verify(taskService).recordWorkerProgress(
                "local-task-1", "worker-task-1", "thread-1", null, 1, false, true);
        verify(taskService, never()).failTask(any(), any(), any(), any());
        verifyNoInteractions(sessionEventListener);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reconnectWaitsForInitialAcceptanceAndDoesNotOpenSecondStream() throws Exception {
        stubAppServerTask("PREPARED");
        Map<String, Object> request = Map.of("prompt", "hello");
        stubBuiltRequest(request);
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        when(client.createTask("local-task-1", request)).thenReturn(Mono.fromCallable(() -> {
            createEntered.countDown();
            if (!releaseCreate.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timed out");
            }
            return acceptance("local-task-1");
        }));
        when(client.subscribeToTask("local-task-1", 0)).thenReturn(Flux.never());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = executor.submit(() -> relay.onTaskStart(startEvent("gpt-5.6-sol:ultra")));
            assertTrue(createEntered.await(2, TimeUnit.SECONDS));
            var reconnect = executor.submit(
                    () -> relay.reconnectTask("local-task-1", "session-1", "worker-1"));
            Thread.sleep(100);
            assertFalse(reconnect.isDone());
            releaseCreate.countDown();
            start.get(5, TimeUnit.SECONDS);
            reconnect.get(5, TimeUnit.SECONDS);
        } finally {
            releaseCreate.countDown();
            executor.shutdownNow();
        }

        verify(client, times(1)).createTask("local-task-1", request);
        verify(client, times(1)).subscribeToTask("local-task-1", 0);
    }

    private ServerSentEvent<String> workerEvent(String type, int seq, String content) {
        String extra = "error".equals(type)
                ? "\"error\":\"" + content + "\""
                : "\"content\":\"" + content + "\"";
        return ServerSentEvent.builder("{\"type\":\"" + type
                + "\",\"task_id\":\"worker-task-1\",\"session_id\":\"thread-1\",\"seq\":"
                + seq + "," + extra + "}").build();
    }

    private CodexTaskEntity stubAppServerTask(String acceptanceState) {
        CodexTaskEntity entity = legacyTask();
        entity.setRuntimeId("app-main");
        entity.setRuntimeRevision(1);
        entity.setRuntimeType("APP_SERVER");
        entity.setRuntimeInstanceId("instance-a");
        entity.setRoutingEpoch(1L);
        entity.setRuntimeAcceptanceState(acceptanceState);
        entity.setStatus("RUNNING");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeRevision(1)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .endpointUrl("http://127.0.0.1:3062")
                .authToken("runtime-token")
                .instanceId("instance-a")
                .routingEpoch(1L)
                .build();
        when(runtimeRegistryService.resolveBoundRuntime("app-main", 1, "worker-1", "instance-a"))
                .thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:1", "http://127.0.0.1:3062", "runtime-token", "instance-a"))
                .thenReturn(client);
        return entity;
    }

    private void stubBuiltRequest(Map<String, Object> request) {
        when(client.buildTaskRequest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
    }

    private WorkerTaskStartEvent startEvent(String model) {
        return WorkerTaskStartEvent.builder()
                .taskId("local-task-1")
                .sessionId("session-1")
                .workerId("worker-1")
                .prompt("hello")
                .cwd("D:/repo")
                .model(model)
                .providerType("codex-worker")
                .build();
    }

    private CodexTaskAcceptanceDTO acceptance(String workerTaskId) {
        CodexTaskAcceptanceDTO acceptance = new CodexTaskAcceptanceDTO();
        acceptance.setTaskId(workerTaskId);
        acceptance.setStatus("accepted");
        return acceptance;
    }

    private CodexTaskEntity legacyTask() {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setWorkerId("worker-1");
        entity.setSessionId("session-1");
        entity.setRuntimeId("legacy-sdk:worker-1");
        entity.setRuntimeRevision(1);
        entity.setRuntimeType("SDK_EXEC");
        entity.setRoutingEpoch(0L);
        return entity;
    }
}
