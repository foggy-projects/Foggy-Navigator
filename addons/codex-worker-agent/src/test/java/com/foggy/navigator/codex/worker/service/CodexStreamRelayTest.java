package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.lifecycle.CodexLifecycleBindingDigest;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskAcceptanceDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.dto.NativeSubtaskSnapshotDTO;
import com.foggy.navigator.common.dto.NativeSubtaskUpdatePayload;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.session.event.SessionEventListener;
import com.foggy.navigator.session.lifecycle.LifecycleActivationDeniedException;
import com.foggy.navigator.session.lifecycle.LifecycleProductionAdmissionService;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryBounds;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryCapability;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryCapabilityDeclaration;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryPolicy;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryPolicyResolver;
import com.foggy.navigator.spi.recovery.ResolvedBackgroundRecoveryPolicy;
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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CodexStreamRelayTest {

    private static final Instant NOW = Instant.parse("2026-08-03T08:00:00Z");

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
    private BackgroundRecoveryPolicyResolver recoveryPolicyResolver;
    private CodexBackgroundRecoveryPolicy backgroundRecoveryPolicy;
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
        recoveryPolicyResolver = mock(BackgroundRecoveryPolicyResolver.class);
        when(recoveryPolicyResolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> new ResolvedBackgroundRecoveryPolicy(
                        invocation.getArgument(0), recoveryPolicy(true, 100, 4)));
        backgroundRecoveryPolicy = new CodexBackgroundRecoveryPolicy(
                recoveryPolicyResolver, Clock.fixed(NOW, ZoneOffset.UTC));

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
                sessionEventListener,
                backgroundRecoveryPolicy
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
        CodexTaskEntity entity = legacyTask();
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
    void manualReconnectReconcilesRemoteAbortBeforeResubscribing() {
        CodexTaskEntity entity = stubAppServerTask("SUBSCRIBED");
        entity.setWorkerTaskId("worker-task-9");
        entity.setCodexThreadId("thread-9");
        when(client.getTaskStatus("worker-task-9")).thenReturn(Mono.just(Map.of(
                "task_id", "worker-task-9",
                "status", "terminal",
                "outcome", "aborted",
                "thread_id", "thread-9")));

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client).getTaskStatus("worker-task-9");
        verify(taskService).reconcileAbortedTask("local-task-1", "worker-task-9", "thread-9");
        verify(client, never()).subscribeToTask(any(), anyInt());
    }

    @Test
    void manualReconnectKeepsCompletedStatusRecoverableUntilFinalSse() {
        CodexTaskEntity entity = stubAppServerTask("SUBSCRIBED");
        entity.setWorkerTaskId("worker-task-9");
        when(client.getTaskStatus("worker-task-9")).thenReturn(Mono.just(Map.of(
                "task_id", "worker-task-9",
                "status", "terminal",
                "outcome", "completed",
                "thread_id", "thread-9")));
        when(client.subscribeToTask("worker-task-9", 0)).thenReturn(Flux.never());

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client).getTaskStatus("worker-task-9");
        verify(client).subscribeToTask("worker-task-9", 0);
        verify(taskService, never()).completeTask(
                eq("local-task-1"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(taskService, never()).failTask(eq("local-task-1"), any(), any(), any());
    }

    @Test
    void userInputRequestPublishesDurableConfirmationRequest() throws Exception {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setStatus("RUNNING");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        Map<String, Object> confirmation = Map.of(
                "requestId", "request-1",
                "interactionType", "user_input",
                "questions", List.of(Map.of(
                        "id", "choice", "question", "Select one", "multiSelect", false)));
        when(taskService.registerPendingUserInput(eq("local-task-1"), any()))
                .thenReturn(new CodexTaskService.UserInputRegistration(
                        true, "request-1", confirmation));
        String eventJson = new ObjectMapper().writeValueAsString(Map.of(
                "type", "user_input_request",
                "subtype", "request_user_input",
                "task_id", "worker-task-1",
                "seq", 1,
                "data", Map.of("request_id", "request-1")));

        ReflectionTestUtils.invokeMethod(
                relay, "handleSseEvent", ServerSentEvent.builder(eventJson).build(),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>());

        ArgumentCaptor<AgentMessage> message = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener).handleMessageDurably(message.capture());
        assertEquals(MessageType.CONFIRMATION_REQUEST, message.getValue().getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getValue().getPayload();
        assertEquals("request-1", payload.get("permissionId"));
        assertEquals("user_input", payload.get("interactionType"));
        assertEquals(CodexStreamRelay.userInputMessageId(
                "cx-ui-req-", "local-task-1", "request-1"), message.getValue().getMessageId());
        assertTrue(message.getValue().getMessageId().length() <= 64);
        verify(taskService).recordWorkerProgress(
                "local-task-1", "worker-task-1", null, null, 1, false, true);
    }

    @Test
    void answeredResolutionPublishesAllowWhenHttpResponseDidNotCommit() throws Exception {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setStatus("AWAITING_INPUT");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(taskService.resolvePendingUserInput(eq("local-task-1"), any()))
                .thenReturn(new CodexTaskService.UserInputResolution(
                        true, "task:local-task-1:string:request-1", "answered"));
        String eventJson = new ObjectMapper().writeValueAsString(Map.of(
                "type", "user_input_resolved",
                "subtype", "request_user_input_resolved",
                "task_id", "worker-task-1",
                "seq", 1,
                "data", Map.of("request_id", "request-1", "reason", "answered")));

        ReflectionTestUtils.invokeMethod(
                relay, "handleSseEvent", ServerSentEvent.builder(eventJson).build(),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>());

        ArgumentCaptor<AgentMessage> message = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener).handleMessageDurably(message.capture());
        assertEquals(MessageType.CONFIRMATION_RESPONSE, message.getValue().getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getValue().getPayload();
        assertEquals("task:local-task-1:string:request-1", payload.get("permissionId"));
        assertEquals("allow", payload.get("decision"));
        assertFalse(payload.containsKey("answers"));
        assertEquals(CodexStreamRelay.userInputMessageId(
                "cx-ui-res-", "local-task-1", "task:local-task-1:string:request-1"),
                message.getValue().getMessageId());
    }

    @Test
    void streamQueryErrorBeforeWorkerTaskIdFailsLocalTaskWithoutReconnect() {
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
    void threadActiveConflictKeepsStableWorkerCodeAndDoesNotReconnect() {
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
                .thenReturn(Flux.error(new CodexWorkerClient.ThreadActiveException(
                        409, "CODEX_THREAD_ACTIVE", "thread-1",
                        "worker-task-old", 4321, "process_scan")));

        relay.onTaskStart(WorkerTaskStartEvent.builder()
                .taskId("local-task-1")
                .sessionId("session-1")
                .workerId("worker-1")
                .prompt("continue")
                .cwd("D:/repo")
                .model("gpt-5.5")
                .providerType("codex-worker")
                .providerConfig(Map.of("codexThreadId", "thread-1"))
                .build());

        verify(taskService).failTask(eq("local-task-1"), isNull(), eq("thread-1"),
                eq("CODEX_THREAD_ACTIVE"));
        verify(client, never()).subscribeToTask(any(), anyInt());
    }

    @Test
    void onTaskStartForCodexBizWorkerForwardsCodexBizRuntimeOptions() {
        CodexTaskEntity entity = legacyTask();
        entity.setProviderType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
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
    void activationAdmissionCommitsBeforeFrozenWorkerV1Call() {
        CodexTaskEntity entity = legacyTask();
        entity.setProviderType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
        when(taskRepository.findByTaskId("local-task-1"))
                .thenReturn(Optional.of(entity));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:13051")
                        .authToken("fixture-worker-token")
                        .build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://localhost:13051",
                "fixture-worker-token"))
                .thenReturn(client);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("prompt", "static synthetic prompt");
        requestBody.put("cwd", "/tmp/arch001-act-run/workdir");
        requestBody.put("model", "gpt-5.6-sol");
        stubBuiltRequest(requestBody);
        String dispatchId = CodexStreamRelay.stableLifecycleDispatchId(
                "TASK_CREATE", "local-task-1");
        Map<String, Object> lifecycleContext = new LinkedHashMap<>();
        lifecycleContext.put("schema", "NAVIGATOR_WORKER_LIFECYCLE_V1");
        lifecycleContext.put("ownership_mode", "ENFORCED");
        lifecycleContext.put("command_kind", "TASK_CREATE");
        lifecycleContext.put("navigator_task_id", "local-task-1");
        lifecycleContext.put("dispatch_id", dispatchId);
        lifecycleContext.put("delivery_attempt", 1);
        lifecycleContext.put("expected_physical_worker_id", "worker-1");
        lifecycleContext.put("expected_state_generation", "generation-1");
        lifecycleContext.put("termination_operation_id", null);
        WorkerLifecycleIdentity identity = new WorkerLifecycleIdentity(
                "worker-1", "generation-1", "epoch-1");
        when(client.lifecycleContextEvidence(
                "worker-1", "ENFORCED", "TASK_CREATE", "local-task-1",
                dispatchId, 1, null))
                .thenReturn(Mono.just(
                        new CodexWorkerClient.LifecycleContextEvidence(
                                lifecycleContext, identity,
                                "NAVIGATOR_WORKER_LIFECYCLE_V1", 1,
                                java.util.Set.of(), "source-candidate",
                                true, true, true)));
        LifecycleProductionAdmissionService admission =
                mock(LifecycleProductionAdmissionService.class);
        when(admission.ownershipModeForTask("local-task-1"))
                .thenReturn(LifecycleOwnershipMode.ENFORCED);
        when(admission.admitAndAuthorizeProviderEffect(any()))
                .thenAnswer(invocation -> {
                    LifecycleProductionAdmissionService.ProviderEffectCommand
                            command = invocation.getArgument(0);
                    return new LifecycleProductionAdmissionService
                            .ProviderEffectAuthorization(
                            true, true, false, "effect-1",
                            command.dispatchId(), command.bindingDigestVersion(),
                            command.bindingDigest(), "EFFECT_AUTHORIZED");
                });
        ReflectionTestUtils.setField(
                relay, "lifecycleProductionAdmission", admission);
        ReflectionTestUtils.setField(
                relay, "lifecycleBindingDigest",
                new CodexLifecycleBindingDigest(new ObjectMapper()));
        when(client.streamQuery(any(Map.class))).thenReturn(Flux.never());

        relay.onTaskStart(WorkerTaskStartEvent.builder()
                .taskId("local-task-1")
                .sessionId("session-1")
                .workerId("worker-1")
                .prompt("static synthetic prompt")
                .cwd("/tmp/arch001-act-run/workdir")
                .model("gpt-5.6-sol")
                .providerType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE)
                .providerConfig(Map.of(
                        "codexHomeKey", "synthetic/arch001/canary",
                        "networkAccessEnabled", false,
                        "webSearchMode", "disabled"))
                .build());

        ArgumentCaptor<LifecycleProductionAdmissionService
                .ProviderEffectCommand> authorization =
                ArgumentCaptor.forClass(LifecycleProductionAdmissionService
                        .ProviderEffectCommand.class);
        var order = inOrder(admission, client);
        order.verify(admission).admitAndAuthorizeProviderEffect(
                authorization.capture());
        order.verify(client).streamQuery(any(Map.class));
        assertEquals(identity, authorization.getValue().workerIdentity());
        assertEquals(dispatchId, authorization.getValue().dispatchId());
        assertEquals("JCS_SHA256_V1",
                authorization.getValue().bindingDigestVersion());
        assertTrue(requestBody.containsKey("lifecycle_context"));
    }

    @Test
    void enforcedAdmissionDenialFailsBeforeProviderEffectAndCommitsServerFence() {
        CodexTaskEntity entity = legacyTask();
        entity.setProviderType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
        when(taskRepository.findByTaskId("local-task-1"))
                .thenReturn(Optional.of(entity));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:13051")
                        .authToken("fixture-worker-token")
                        .build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://localhost:13051",
                "fixture-worker-token"))
                .thenReturn(client);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("prompt", "static synthetic prompt");
        requestBody.put("cwd", "/tmp/arch001-act-run/workdir");
        requestBody.put("model", "gpt-5.6-sol");
        stubBuiltRequest(requestBody);
        String dispatchId = CodexStreamRelay.stableLifecycleDispatchId(
                "TASK_CREATE", "local-task-1");
        Map<String, Object> lifecycleContext = new LinkedHashMap<>();
        lifecycleContext.put("schema", "NAVIGATOR_WORKER_LIFECYCLE_V1");
        lifecycleContext.put("ownership_mode", "ENFORCED");
        lifecycleContext.put("command_kind", "TASK_CREATE");
        lifecycleContext.put("navigator_task_id", "local-task-1");
        lifecycleContext.put("dispatch_id", dispatchId);
        lifecycleContext.put("delivery_attempt", 1);
        lifecycleContext.put("expected_physical_worker_id", "worker-1");
        lifecycleContext.put("expected_state_generation", "generation-1");
        lifecycleContext.put("termination_operation_id", null);
        WorkerLifecycleIdentity identity = new WorkerLifecycleIdentity(
                "worker-1", "generation-1", "epoch-1");
        when(client.lifecycleContextEvidence(
                "worker-1", "ENFORCED", "TASK_CREATE", "local-task-1",
                dispatchId, 1, null))
                .thenReturn(Mono.just(
                        new CodexWorkerClient.LifecycleContextEvidence(
                                lifecycleContext, identity,
                                "NAVIGATOR_WORKER_LIFECYCLE_V1", 1,
                                java.util.Set.of(), "source-candidate",
                                true, true, true)));
        LifecycleProductionAdmissionService admission =
                mock(LifecycleProductionAdmissionService.class);
        when(admission.ownershipModeForTask("local-task-1"))
                .thenReturn(LifecycleOwnershipMode.ENFORCED);
        when(admission.admitAndAuthorizeProviderEffect(any()))
                .thenThrow(new LifecycleActivationDeniedException(
                        "ADMISSION_BINDING_MISMATCH"));
        when(admission.supportsDeterministicPreEffectClosure(
                "ADMISSION_BINDING_MISMATCH")).thenReturn(true);
        ReflectionTestUtils.setField(
                relay, "lifecycleProductionAdmission", admission);
        ReflectionTestUtils.setField(
                relay, "lifecycleBindingDigest",
                new CodexLifecycleBindingDigest(new ObjectMapper()));

        relay.onTaskStart(WorkerTaskStartEvent.builder()
                .taskId("local-task-1")
                .sessionId("session-1")
                .workerId("worker-1")
                .prompt("static synthetic prompt")
                .cwd("/tmp/arch001-act-run/workdir")
                .model("gpt-5.6-sol")
                .providerType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE)
                .providerConfig(Map.of(
                        "codexHomeKey", "synthetic/arch001/canary",
                        "networkAccessEnabled", false,
                        "webSearchMode", "disabled"))
                .build());

        ArgumentCaptor<LifecycleProductionAdmissionService
                .ProviderEffectCommand> authorization =
                ArgumentCaptor.forClass(LifecycleProductionAdmissionService
                        .ProviderEffectCommand.class);
        ArgumentCaptor<LifecycleProductionAdmissionService
                .ProviderEffectCommand> closure =
                ArgumentCaptor.forClass(LifecycleProductionAdmissionService
                        .ProviderEffectCommand.class);
        var order = inOrder(admission, taskService, client);
        order.verify(admission).admitAndAuthorizeProviderEffect(
                authorization.capture());
        order.verify(taskService).failTask("local-task-1", null, null,
                "ADMISSION_BINDING_MISMATCH");
        order.verify(admission).supportsDeterministicPreEffectClosure(
                "ADMISSION_BINDING_MISMATCH");
        order.verify(admission).closeDeterministicPreEffectAdmissionFailure(
                closure.capture(), eq("ADMISSION_BINDING_MISMATCH"));
        verify(client, never()).streamQuery(any(Map.class));
        assertEquals(authorization.getValue(), closure.getValue());
        assertEquals(dispatchId, closure.getValue().dispatchId());
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
                409, "Conflict", null,
                "{\"error\":\"IDEMPOTENCY_KEY_CONFLICT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8);
        when(client.createTask("local-task-1", request)).thenReturn(Mono.error(conflict));

        relay.onTaskStart(startEvent("codex-ultra"));

        verify(client).createTask("local-task-1", request);
        verify(taskService).failTask(eq("local-task-1"), isNull(), isNull(),
                contains("CODEX_RUNTIME_IDEMPOTENCY_CONFLICT"));
        verify(taskRuntimeStateService, never()).markAcceptanceUnknown(any());
    }

    @Test
    void appServerWorkingDirectoryRejectionSurfacesContractCodeOnAsyncStart() {
        stubAppServerTask("PREPARED");
        Map<String, Object> request = Map.of("prompt", "hello");
        stubBuiltRequest(request);
        WebClientResponseException forbidden = WebClientResponseException.create(
                403, "Forbidden", null,
                "{\"error\":\"WORKING_DIRECTORY_NOT_ALLOWED\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8);
        when(client.createTask("local-task-1", request)).thenReturn(Mono.error(forbidden));

        relay.onTaskStart(startEvent("codex-ultra"));

        verify(client).createTask("local-task-1", request);
        verify(taskService).failTask("local-task-1", null, null,
                "WORKING_DIRECTORY_NOT_ALLOWED");
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
        when(taskRepository.findByProviderTypeAndStatusIn(
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED")))
                .thenReturn(List.of(entity));
        Map<String, Object> request = Map.of("prompt", "hello");
        when(taskRuntimeStateService.loadPreparedRequest("local-task-1")).thenReturn(request);
        when(client.createTask("local-task-1", request)).thenReturn(Mono.just(acceptance("local-task-1")));
        doAnswer(invocation -> {
            entity.setWorkerTaskId(invocation.getArgument(1));
            return null;
        }).when(taskRuntimeStateService).recordAccepted("local-task-1", "local-task-1");
        when(client.subscribeToTask(eq("local-task-1"), eq(0), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return Flux.never();
                });

        relay.reconnectActiveTasks();

        verify(taskRuntimeStateService).loadPreparedRequest("local-task-1");
        verify(client).createTask("local-task-1", request);
        verify(taskRuntimeStateService).recordAccepted("local-task-1", "local-task-1");
        verify(client).subscribeToTask(eq("local-task-1"), eq(0), any(Runnable.class));
    }

    @Test
    void applicationReadyFailsPreparedTaskThatNeverStartedRemoteAcceptance() {
        CodexTaskEntity entity = stubAppServerTask("PREPARED");
        when(taskRepository.findByProviderTypeAndStatusIn(
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED")))
                .thenReturn(List.of(entity));
        when(taskService.failTaskIfAcceptanceNotStarted(
                "local-task-1", "CODEX_RUNTIME_NOT_ACCEPTED")).thenReturn(true);

        relay.reconnectActiveTasks();

        verify(taskService).failTaskIfAcceptanceNotStarted(
                "local-task-1", "CODEX_RUNTIME_NOT_ACCEPTED");
        verify(taskRuntimeStateService, never()).loadPreparedRequest(any());
        verify(client, never()).createTask(any(), any());
    }

    @Test
    void applicationReadyReturnsBeforeSleepOrScanWhenBothCodexProvidersAreDisabled() {
        when(recoveryPolicyResolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> new ResolvedBackgroundRecoveryPolicy(
                        invocation.getArgument(0), recoveryPolicy(false, 3, 1)));

        assertTimeoutPreemptively(Duration.ofSeconds(1), relay::onApplicationReady);

        verify(taskRepository, never()).findByProviderTypeAndStatusIn(any(), any());
        verifyNoInteractions(client);
    }

    @Test
    void manualReconnectRemainsAvailableWhenAutomaticRecoveryIsDisabled() {
        when(recoveryPolicyResolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> new ResolvedBackgroundRecoveryPolicy(
                        invocation.getArgument(0), recoveryPolicy(false, 3, 1)));
        CodexTaskEntity entity = legacyTask();
        entity.setWorkerTaskId("worker-task-9");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:3051")
                        .authToken("worker-token")
                        .build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://localhost:3051", "worker-token"))
                .thenReturn(client);
        when(client.subscribeToTask("worker-task-9", 0)).thenReturn(Flux.never());

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client).subscribeToTask("worker-task-9", 0);
    }

    @Test
    void automaticConcurrencyPermitWaitsForSseConnectionSettlement() {
        when(recoveryPolicyResolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> new ResolvedBackgroundRecoveryPolicy(
                        invocation.getArgument(0), recoveryPolicy(true, 3, 1)));
        CodexTaskEntity first = legacyTask();
        first.setWorkerTaskId("worker-task-1");
        CodexTaskEntity second = legacyTask();
        second.setTaskId("local-task-2");
        second.setSessionId("session-2");
        second.setWorkerTaskId("worker-task-2");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(first));
        when(taskRepository.findByTaskId("local-task-2")).thenReturn(Optional.of(second));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:3051")
                        .authToken("worker-token")
                        .build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://localhost:3051", "worker-token"))
                .thenReturn(client);
        java.util.concurrent.atomic.AtomicReference<Runnable> firstConnectionSettled =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(client.subscribeToTask(eq("worker-task-1"), eq(0), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    firstConnectionSettled.set(invocation.getArgument(2, Runnable.class));
                    return Flux.never();
                });
        when(client.subscribeToTask(eq("worker-task-2"), eq(0), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return Flux.never();
                });

        try {
            relay.recoverAutomatically(first, BackgroundRecoveryCapability.STARTUP_SCAN);
            assertTrue(firstConnectionSettled.get() != null);

            relay.recoverAutomatically(second, BackgroundRecoveryCapability.STARTUP_SCAN);

            verify(client, never()).subscribeToTask(
                    eq("worker-task-2"), eq(0), any(Runnable.class));
            verify(taskService).markLifecycleAttention(
                    "local-task-2", "CODEX_BACKGROUND_RECOVERY_CONCURRENCY_EXHAUSTED");
            assertEquals(0, backgroundRecoveryPolicy.attempts("local-task-2"));

            firstConnectionSettled.get().run();
            relay.recoverAutomatically(second, BackgroundRecoveryCapability.STARTUP_SCAN);

            verify(client).subscribeToTask(
                    eq("worker-task-2"), eq(0), any(Runnable.class));
            assertEquals(1, backgroundRecoveryPolicy.attempts("local-task-2"));
        } finally {
            relay.abortStream("local-task-1");
            relay.abortStream("local-task-2");
        }
    }

    @Test
    void explicitAbortCancelsTimerWithoutResettingAutomaticAttemptBudget() {
        CodexTaskEntity entity = legacyTask();
        entity.setWorkerTaskId("worker-task-9");
        var attempt = backgroundRecoveryPolicy.tryAcquire(
                entity, BackgroundRecoveryCapability.DELAYED_RETRY);
        assertTrue(attempt.permitted());
        attempt.lease().close();

        relay.abortStream("local-task-1");

        assertEquals(1, backgroundRecoveryPolicy.attempts("local-task-1"));
    }

    @Test
    void committedDeletionClearsAutomaticTimerAndAttemptState() {
        CodexTaskEntity entity = legacyTask();
        entity.setWorkerTaskId("worker-task-9");
        var attempt = backgroundRecoveryPolicy.tryAcquire(
                entity, BackgroundRecoveryCapability.DELAYED_RETRY);
        assertTrue(attempt.permitted());
        attempt.lease().close();
        ReflectionTestUtils.invokeMethod(
                relay, "scheduleReconnect", entity, Duration.ofHours(1));

        relay.clearDeletedTask("local-task-1");

        assertEquals(0, backgroundRecoveryPolicy.attempts("local-task-1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scheduled = (Map<String, Object>)
                ReflectionTestUtils.getField(relay, "scheduledRecoveries");
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void timerThatFiresWhileBeingRegisteredLeavesNoResidualEntry() throws Exception {
        CodexTaskEntity entity = legacyTask();
        RegistrationRaceScheduler scheduler = new RegistrationRaceScheduler();
        ReflectionTestUtils.setField(relay, "recoveryScheduler", scheduler);
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(
                relay, "scheduleReconnect", entity, Duration.ofNanos(1));

        assertTrue(scheduler.awaitCompletion());
        @SuppressWarnings("unchecked")
        Map<String, Object> scheduled = (Map<String, Object>)
                ReflectionTestUtils.getField(relay, "scheduledRecoveries");
        assertTrue(scheduled.isEmpty());
        verify(taskRepository).findByTaskId("local-task-1");
    }

    @Test
    void nonTerminalStreamCompletionDoesNotResetAutomaticAttemptBudget() {
        CodexTaskEntity entity = legacyTask();
        entity.setWorkerTaskId("worker-task-9");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        var attempt = backgroundRecoveryPolicy.tryAcquire(
                entity, BackgroundRecoveryCapability.DELAYED_RETRY);
        assertTrue(attempt.permitted());
        attempt.lease().close();

        ReflectionTestUtils.invokeMethod(
                relay,
                "handleSseCompletion",
                "local-task-1",
                "session-1",
                "worker-1",
                CodexTaskService.CODEX_PROVIDER_TYPE,
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                1,
                null);

        assertEquals(1, backgroundRecoveryPolicy.attempts("local-task-1"));
    }

    @Test
    void disabledSdkProviderIsNotScannedWhileEnabledAppServerStillIs() {
        when(recoveryPolicyResolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> {
                    BackgroundRecoveryCapabilityDeclaration declaration = invocation.getArgument(0);
                    boolean enabled = CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE.equals(
                            declaration.providerId().value());
                    return new ResolvedBackgroundRecoveryPolicy(
                            declaration, recoveryPolicy(enabled, 3, 1));
                });

        relay.reconnectActiveTasks();

        verify(taskRepository, never()).findByProviderTypeAndStatusIn(
                eq(CodexTaskService.CODEX_PROVIDER_TYPE), any());
        verify(taskRepository).findByProviderTypeAndStatusIn(
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"));
    }

    @Test
    void legacyNullEpochStartupTaskFailsClosedWithoutWritesOrProviderEffect() {
        CodexTaskEntity entity = legacyTask();
        entity.setCreatedAtEpochMs(null);
        entity.setStatus("RUNNING");
        entity.setWorkerTaskId("worker-task-9");
        when(taskRepository.findByProviderTypeAndStatusIn(
                CodexTaskService.CODEX_PROVIDER_TYPE,
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED")))
                .thenReturn(List.of(entity));
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));

        relay.reconnectActiveTasks();

        verifyNoInteractions(client);
        verifyNoInteractions(taskService);
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(sessionEventListener);
    }

    @Test
    void codexBizHasNoAutomaticRecoveryCapabilityOrSideEffect() {
        CodexTaskEntity entity = legacyTask();
        entity.setProviderType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
        entity.setStatus("RUNNING");
        entity.setWorkerTaskId("worker-task-9");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));

        ReflectionTestUtils.invokeMethod(relay, "handleSseError",
                "local-task-1", "session-1", "worker-1", CodexTaskService.CODEX_BIZ_PROVIDER_TYPE,
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                0, null, new IllegalStateException("transport unavailable"));

        verifyNoInteractions(client);
        assertEquals(0, backgroundRecoveryPolicy.attempts("local-task-1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scheduled = (Map<String, Object>)
                ReflectionTestUtils.getField(relay, "scheduledRecoveries");
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void exhaustedAttemptBudgetStopsSchedulingAndPublishesNonTerminalAttention() {
        when(recoveryPolicyResolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> new ResolvedBackgroundRecoveryPolicy(
                        invocation.getArgument(0), recoveryPolicy(true, 1, 1)));
        CodexTaskEntity entity = legacyTask();
        entity.setWorkerTaskId("worker-task-9");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:3051")
                        .authToken("worker-token")
                        .build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://localhost:3051", "worker-token"))
                .thenReturn(client);
        when(client.subscribeToTask(eq("worker-task-9"), eq(0), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return Flux.error(new IllegalStateException("transport unavailable"));
                });

        relay.recoverAutomatically(entity, BackgroundRecoveryCapability.STARTUP_SCAN);

        verify(taskService).markLifecycleAttention(
                "local-task-1", "CODEX_BACKGROUND_RECOVERY_ATTEMPTS_EXHAUSTED");
        verify(taskService, never()).failTask(eq("local-task-1"), any(), any(), any());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        AgentMessage attention = assertInstanceOf(AgentMessage.class, event.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) attention.getPayload();
        assertEquals("lifecycle_attention", payload.get("subtype"));
        assertEquals(false, payload.get("terminal"));
        assertEquals(1, backgroundRecoveryPolicy.attempts("local-task-1"));
    }

    @Test
    void failedAttentionWriteLeavesTheLatchOpenForASuccessfulRetry() {
        CodexTaskEntity entity = legacyTask();
        String attentionCode = "CODEX_BACKGROUND_RECOVERY_ATTEMPTS_EXHAUSTED";
        doThrow(new IllegalStateException("attention store unavailable"))
                .doNothing()
                .when(taskService).markLifecycleAttention("local-task-1", attentionCode);

        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                relay, "publishBackgroundRecoveryAttention", entity, attentionCode));
        ReflectionTestUtils.invokeMethod(
                relay, "publishBackgroundRecoveryAttention", entity, attentionCode);

        verify(taskService, times(2)).markLifecycleAttention("local-task-1", attentionCode);
        verify(eventPublisher, times(1)).publishEvent(any(AgentMessage.class));
    }

    @Test
    void repeatedAttentionDenialIsPublishedOnlyOnceAfterSuccess() {
        CodexTaskEntity entity = legacyTask();
        String attentionCode = "CODEX_BACKGROUND_RECOVERY_WINDOW_EXHAUSTED";

        ReflectionTestUtils.invokeMethod(
                relay, "publishBackgroundRecoveryAttention", entity, attentionCode);
        ReflectionTestUtils.invokeMethod(
                relay, "publishBackgroundRecoveryAttention", entity, attentionCode);

        verify(taskService, times(1)).markLifecycleAttention("local-task-1", attentionCode);
        verify(eventPublisher, times(1)).publishEvent(any(AgentMessage.class));
    }

    @Test
    void expiredWindowStopsBeforeProviderCallAndPublishesAttention() {
        CodexTaskEntity entity = legacyTask();
        entity.setCreatedAtEpochMs(NOW.minus(Duration.ofHours(2)).toEpochMilli());
        entity.setWorkerTaskId("worker-task-9");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));

        relay.recoverAutomatically(entity, BackgroundRecoveryCapability.STARTUP_SCAN);

        verifyNoInteractions(client);
        verify(taskService).markLifecycleAttention(
                "local-task-1", "CODEX_BACKGROUND_RECOVERY_WINDOW_EXHAUSTED");
        assertEquals(0, backgroundRecoveryPolicy.attempts("local-task-1"));
    }

    @Test
    void providerConcurrencyBoundaryStopsWithoutQueueAndPublishesAttention() {
        when(recoveryPolicyResolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> new ResolvedBackgroundRecoveryPolicy(
                        invocation.getArgument(0), recoveryPolicy(true, 3, 1)));
        CodexTaskEntity held = legacyTask();
        held.setTaskId("held-task");
        held.setWorkerTaskId("held-worker-task");
        var heldAttempt = backgroundRecoveryPolicy.tryAcquire(
                held, BackgroundRecoveryCapability.STARTUP_SCAN);
        assertTrue(heldAttempt.permitted());
        CodexTaskEntity entity = legacyTask();
        entity.setWorkerTaskId("worker-task-9");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));

        try {
            relay.recoverAutomatically(entity, BackgroundRecoveryCapability.STARTUP_SCAN);
        } finally {
            heldAttempt.lease().close();
        }

        verifyNoInteractions(client);
        verify(taskService).markLifecycleAttention(
                "local-task-1", "CODEX_BACKGROUND_RECOVERY_CONCURRENCY_EXHAUSTED");
        assertEquals(0, backgroundRecoveryPolicy.attempts("local-task-1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scheduled = (Map<String, Object>)
                ReflectionTestUtils.getField(relay, "scheduledRecoveries");
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void abortRemoteTaskRejectsImplicitTerminationWithoutCapability() {
        CodexTaskEntity entity = stubAppServerTask("UNKNOWN");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> relay.abortRemoteTask(entity));

        assertTrue(error.getMessage().contains("TERMINATION_CAPABILITY_REQUIRED"));
        verifyNoInteractions(client);
    }

    @Test
    void abortAndReconcileTaskOnlyMarksAttentionAndRetainsRemoteOwnership() {
        CodexTaskEntity entity = stubAppServerTask("ACCEPTED");
        entity.setWorkerTaskId("worker-task-9");

        relay.abortAndReconcileTask(entity);

        verify(taskService).markLifecycleAttention("local-task-1",
                "TERMINATION_REQUIRES_EXPLICIT_OPERATION");
        verifyNoInteractions(client);
        verify(taskService, never()).reconcileAbortedTask(any(), any(), any());
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
        assertEquals(MessageType.STATE_SYNC, message.getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("CODEX_RUNTIME_RESULT_UNKNOWN", payload.get("content"));
        assertEquals("reconnect_pending", payload.get("subtype"));
        assertEquals(true, payload.get("reconnectable"));
    }

    @Test
    void acceptedLegacyStreamExhaustionKeepsLocalTaskRunningAndSchedulesRecovery() {
        CodexTaskEntity entity = legacyTask();
        entity.setWorkerTaskId("worker-task-9");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));

        ReflectionTestUtils.invokeMethod(
                relay,
                "subscribeSseFlux",
                Flux.error(new RuntimeException("transport disconnected")),
                "local-task-1",
                "session-1",
                "worker-1",
                "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                3,
                0);

        verify(taskService, never()).failTask(eq("local-task-1"), any(), any(), any());
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AgentMessage message = assertInstanceOf(AgentMessage.class, eventCaptor.getValue());
        assertEquals(MessageType.STATE_SYNC, message.getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("CODEX_WORKER_STREAM_DISCONNECTED", payload.get("content"));
        assertEquals("reconnect_pending", payload.get("subtype"));
        assertEquals(true, payload.get("reconnectable"));
    }

    @Test
    void userInputMessageIdsAreBoundedDeterministicAndTypeDistinct() {
        String longId = "x".repeat(256);
        String numeric = CodexStreamRelay.userInputMessageId(
                "cx-ui-req-", "task-" + "t".repeat(60), "task:task-1:number:1");
        String numericAgain = CodexStreamRelay.userInputMessageId(
                "cx-ui-req-", "task-" + "t".repeat(60), "task:task-1:number:1");
        String string = CodexStreamRelay.userInputMessageId(
                "cx-ui-req-", "task-" + "t".repeat(60), "task:task-1:string:1");
        String maximum = CodexStreamRelay.userInputMessageId(
                "cx-ui-res-", "task-1", "task:task-1:string:" + longId);

        assertEquals(numeric, numericAgain);
        assertFalse(numeric.equals(string));
        assertTrue(numeric.length() <= 64);
        assertTrue(maximum.length() <= 64);
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
                isNull(), isNull(), isNull(), isNull(), isNull(), eq("gpt-5.6-sol"), eq(9));

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client).subscribeToTask("worker-task-9", 8);
        verify(taskService).completeTask(
                "local-task-1", "worker-task-9", "thread-9", "done",
                null, null, null, null, null, "gpt-5.6-sol", 9);
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
                eq("local-task-1"), eq("session-1"),
                eq(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE), eq(12),
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
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
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
    void warningBeforeResultDoesNotFailTaskAndResultStillCompletes() {
        var detectedThread = new java.util.concurrent.atomic.AtomicReference<String>();
        var detectedModel = new java.util.concurrent.atomic.AtomicReference<String>();
        String warningJson = """
                {
                  "type":"warning",
                  "subtype":"sdk_diagnostic",
                  "task_id":"worker-task-1",
                  "session_id":"thread-1",
                  "stream_id":"item-1",
                  "seq":1,
                  "content":"This session was recorded with a different model; token=super-secret-value"
                }
                """;
        String resultJson = """
                {
                  "type":"result",
                  "task_id":"worker-task-1",
                  "session_id":"thread-1",
                  "stream_id":"item-1",
                  "seq":2,
                  "content":"received",
                  "model":"gpt-5.6-sol"
                }
                """;

        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(warningJson).build(),
                "local-task-1", "session-1", "codex-worker",
                detectedModel, detectedThread);
        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(resultJson).build(),
                "local-task-1", "session-1", "codex-worker",
                detectedModel, detectedThread);

        ArgumentCaptor<AgentMessage> messages = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener, times(2)).handleMessageDurably(messages.capture());
        assertEquals(List.of(MessageType.STATE_SYNC, MessageType.SESSION_END),
                messages.getAllValues().stream().map(AgentMessage::getType).toList());
        @SuppressWarnings("unchecked")
        Map<String, Object> warningPayload = (Map<String, Object>) messages.getAllValues().get(0).getPayload();
        assertEquals("This session was recorded with a different model; [credential]", warningPayload.get("content"));
        verify(taskService, never()).failTask(any(), any(), any(), any());
        verify(taskService).completeTask("local-task-1", "worker-task-1", "thread-1",
                "received", null, null, null, null, null, "gpt-5.6-sol", 2);
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
    void appServerTextDeltaPublishesTransientChunkWhileCompletedItemPersistsOnce() {
        ArgumentCaptor<AgentMessage> messages = ArgumentCaptor.forClass(AgentMessage.class);
        String deltaJson = """
                {
                  "type":"assistant_text",
                  "subtype":"text_delta",
                  "task_id":"worker-task-1",
                  "session_id":"thread-1",
                  "stream_id":"item-1",
                  "seq":1,
                  "content":"B_FULL"
                }
                """;
        String completeJson = """
                {
                  "type":"assistant_text",
                  "task_id":"worker-task-1",
                  "session_id":"thread-1",
                  "stream_id":"item-1",
                  "seq":2,
                  "content":"B_FULL_CHAIN_OK"
                }
                """;

        var detectedThread = new java.util.concurrent.atomic.AtomicReference<String>();
        var detectedModel = new java.util.concurrent.atomic.AtomicReference<String>();
        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(deltaJson).build(),
                "local-task-1", "session-1", "codex-worker", detectedThread, detectedModel);
        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(completeJson).build(),
                "local-task-1", "session-1", "codex-worker", detectedThread, detectedModel);

        verify(sessionEventListener, times(2)).handleMessageDurably(messages.capture());
        assertEquals(List.of(MessageType.TEXT_CHUNK, MessageType.TEXT_COMPLETE),
                messages.getAllValues().stream().map(AgentMessage::getType).toList());
        for (AgentMessage message : messages.getAllValues()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) message.getPayload();
            assertEquals("local-task-1", payload.get("taskId"));
            assertEquals("item-1", payload.get("streamId"));
        }
        verify(taskService).recordWorkerProgress(
                "local-task-1", "worker-task-1", "thread-1", null, 2, false, true);
    }

    @Test
    void sdkCommentaryPublishesNonTerminalStateInsteadOfCompletedAssistantMessage() {
        ArgumentCaptor<AgentMessage> messages = ArgumentCaptor.forClass(AgentMessage.class);
        String commentaryJson = """
                {
                  "type":"assistant_text",
                  "subtype":"commentary",
                  "task_id":"worker-task-1",
                  "session_id":"thread-1",
                  "seq":1,
                  "content":"I will inspect the process now."
                }
                """;

        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(commentaryJson).build(),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>());

        verify(sessionEventListener).handleMessageDurably(messages.capture());
        AgentMessage message = messages.getValue();
        assertEquals(MessageType.STATE_SYNC, message.getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("commentary", payload.get("subtype"));
        assertEquals("I will inspect the process now.", payload.get("content"));
    }

    @Test
    void oversizedToolResultReachesSessionDurableBoundaryWithFullBytesAndAcknowledged() throws Exception {
        CodexTaskEntity entity = legacyTask();
        entity.setStatus("RUNNING");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));

        ObjectMapper mapper = new ObjectMapper();
        String output = "HEAD_OF_TOOL_OUTPUT\n"
                + "compile error 🚀 quoted=\" slash=\\\\ newline=\n".repeat(80_000)
                + "TAIL_OF_TOOL_OUTPUT";
        String eventJson = mapper.writeValueAsString(Map.of(
                "type", "tool_result",
                "task_id", "worker-task-1",
                "session_id", "thread-1",
                "seq", 1,
                "tool_use_id", "tool-1",
                "tool", "command_execution",
                "output", output,
                "is_error", true));

        doAnswer(invocation -> {
            AgentMessage message = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> originalPayload = (Map<String, Object>) message.getPayload();
            assertEquals(output, originalPayload.get("data"),
                    "the relay must not truncate before the generic session payload router sees full bytes");

            Map<String, Object> payload = new LinkedHashMap<>(originalPayload);
            payload.put("data", "HEAD_OF_TOOL_OUTPUT\n[bounded by session payload router]\nTAIL_OF_TOOL_OUTPUT");
            payload.put("dataTruncated", true);
            payload.put("originalDataBytes", output.getBytes(StandardCharsets.UTF_8).length);
            payload.put("truncationReason", "session_message_payload_store");
            payload.put("payloadDescriptor", Map.of(
                    "payloadId", "payload-public-id",
                    "status", "READY",
                    "contentType", "text/plain; charset=utf-8",
                    "contentEncoding", "gzip",
                    "originalBytes", output.getBytes(StandardCharsets.UTF_8).length,
                    "storedBytes", 1024,
                    "sha256", "a".repeat(64),
                    "version", 1));
            message.setPayload(payload);
            Map<String, Object> metadata = new LinkedHashMap<>(payload);
            metadata.put("type", message.getType().name());
            metadata.put("agentId", message.getAgentId());
            int serializedBytes = mapper.writeValueAsBytes(metadata).length;
            if (serializedBytes > CodexStreamRelay.MAX_DURABLE_TOOL_RESULT_METADATA_BYTES) {
                throw new IllegalStateException("simulated session_messages.metadata overflow");
            }
            return null;
        }).when(sessionEventListener).handleMessageDurably(any(AgentMessage.class));

        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(eventJson).build(),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>());

        ArgumentCaptor<AgentMessage> messages = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener).handleMessageDurably(messages.capture());
        AgentMessage message = messages.getValue();
        assertEquals(MessageType.TOOL_CALL_RESULT, message.getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals(true, payload.get("dataTruncated"));
        assertEquals(output.getBytes(StandardCharsets.UTF_8).length,
                ((Number) payload.get("originalDataBytes")).intValue());
        assertEquals("session_message_payload_store", payload.get("truncationReason"));
        assertEquals("READY", ((Map<?, ?>) payload.get("payloadDescriptor")).get("status"));
        assertFalse(((Map<?, ?>) payload.get("payloadDescriptor")).containsKey("storageKey"));
        String persistedData = (String) payload.get("data");
        assertTrue(persistedData.startsWith("HEAD_OF_TOOL_OUTPUT"));
        assertTrue(persistedData.endsWith("TAIL_OF_TOOL_OUTPUT"));
        verify(taskService).recordWorkerProgress(
                "local-task-1", "worker-task-1", "thread-1", null, 1, false, true);
    }

    @Test
    void generatedImagePublishesNavigatorUrlWithoutLeakingWorkerPath() {
        CodexTaskEntity entity = stubAppServerTask("ACCEPTED");
        entity.setStatus("RUNNING");
        String artifactId = "0123456789abcdef0123456789abcdef";
        String eventJson = """
                {
                  "type":"image_generation",
                  "task_id":"worker-task-1",
                  "session_id":"thread-1",
                  "seq":1,
                  "tool_use_id":"image-item-1",
                  "data":{
                    "contract_version":1,
                    "artifact_id":"0123456789abcdef0123456789abcdef",
                    "file_name":"generated.png",
                    "local_path":"/home/sa/.codex-app-server-worker/generated-images/private.png",
                    "mime_type":"image/png",
                    "size_bytes":4,
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }
                }
                """;

        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(eventJson).build(),
                "local-task-1", "session-1", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>());

        ArgumentCaptor<AgentMessage> messages = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener).handleMessageDurably(messages.capture());
        AgentMessage message = messages.getValue();
        assertEquals(MessageType.TOOL_CALL_RESULT, message.getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("image_generation", payload.get("toolName"));
        assertEquals(true, payload.get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> image = (Map<String, Object>) payload.get("data");
        assertEquals(artifactId, image.get("artifact_id"));
        assertEquals("/api/v1/tasks/local-task-1/generated-images/" + artifactId,
                image.get("url"));
        assertFalse(image.containsKey("local_path"));
        verify(taskService).recordWorkerProgress(
                "local-task-1", "worker-task-1", "thread-1", null, 1, false, true);
    }

    @Test
    void unavailablePayloadPreviewStillAcknowledgesAndAllowsLaterEvents() throws Exception {
        CodexTaskEntity entity = legacyTask();
        entity.setStatus("RUNNING");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        ObjectMapper mapper = new ObjectMapper();
        String output = "store-unavailable-🔧".repeat(8_000);
        String toolEvent = mapper.writeValueAsString(Map.of(
                "type", "tool_result",
                "task_id", "worker-task-1",
                "session_id", "thread-1",
                "seq", 1,
                "tool_use_id", "tool-1",
                "tool", "command_execution",
                "output", output));
        doAnswer(invocation -> {
            AgentMessage message = invocation.getArgument(0);
            if (message.getType() == MessageType.TOOL_CALL_RESULT) {
                @SuppressWarnings("unchecked")
                Map<String, Object> originalPayload = (Map<String, Object>) message.getPayload();
                assertEquals(output, originalPayload.get("data"));
                Map<String, Object> unavailable = new LinkedHashMap<>(originalPayload);
                unavailable.put("data", "[bounded preview; full output unavailable]");
                unavailable.put("dataTruncated", true);
                unavailable.put("originalDataBytes", output.getBytes(StandardCharsets.UTF_8).length);
                unavailable.put("truncationReason", "session_message_payload_unavailable");
                unavailable.put("payloadDescriptor", Map.of(
                        "payloadId", "payload-public-id",
                        "status", "UNAVAILABLE",
                        "contentType", "text/plain; charset=utf-8",
                        "contentEncoding", "gzip",
                        "originalBytes", output.getBytes(StandardCharsets.UTF_8).length,
                        "sha256", "b".repeat(64),
                        "version", 1));
                message.setPayload(unavailable);
            }
            return null;
        }).when(sessionEventListener).handleMessageDurably(any(AgentMessage.class));

        var detectedThread = new java.util.concurrent.atomic.AtomicReference<String>();
        var detectedModel = new java.util.concurrent.atomic.AtomicReference<String>();
        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(toolEvent).build(),
                "local-task-1", "session-1", "codex-worker", detectedThread, detectedModel);
        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent", workerEvent("assistant_text", 2, "later"),
                "local-task-1", "session-1", "codex-worker", detectedThread, detectedModel);

        verify(taskService).recordWorkerProgress(
                "local-task-1", "worker-task-1", "thread-1", null, 1, false, true);
        verify(taskService).recordWorkerProgress(
                "local-task-1", "worker-task-1", "thread-1", null, 2, false, true);
        verify(sessionEventListener, times(2)).handleMessageDurably(any(AgentMessage.class));
    }

    @Test
    void unsequencedLegacyToolResultsUseStableDistinctToolIdentities() throws Exception {
        CodexTaskEntity entity = legacyTask();
        entity.setStatus("RUNNING");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));

        ObjectMapper mapper = new ObjectMapper();
        String first = mapper.writeValueAsString(Map.of(
                "type", "tool_result",
                "task_id", "worker-task-1",
                "session_id", "thread-1",
                "tool_use_id", "tool-1",
                "tool", "shell",
                "output", "first legacy output"));
        String second = mapper.writeValueAsString(Map.of(
                "type", "tool_result",
                "task_id", "worker-task-1",
                "session_id", "thread-1",
                "tool_use_id", "tool-2",
                "tool", "shell",
                "output", "second legacy output"));

        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(first).build(),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>());
        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(second).build(),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>());

        ArgumentCaptor<AgentMessage> messages = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener, times(2)).handleMessageDurably(messages.capture());
        List<AgentMessage> values = messages.getAllValues();
        assertNotEquals(values.get(0).getMessageId(), values.get(1).getMessageId());
        assertEquals("cx-lt:local-task-1:tool-1", values.get(0).getMessageId());
        assertEquals("cx-lt:local-task-1:tool-2", values.get(1).getMessageId());
    }

    @Test
    void replayedUnsequencedLegacyToolResultKeepsTheSamePayloadIdentity() throws Exception {
        CodexTaskEntity entity = legacyTask();
        entity.setStatus("RUNNING");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));

        String replayed = new ObjectMapper().writeValueAsString(Map.of(
                "type", "tool_result",
                "task_id", "worker-task-1",
                "session_id", "thread-1",
                "tool_use_id", "tool-replayed",
                "tool", "shell",
                "output", "same legacy output"));

        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(replayed).build(),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>());
        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(replayed).build(),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>());

        ArgumentCaptor<AgentMessage> messages = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener, times(2)).handleMessageDurably(messages.capture());
        assertEquals(messages.getAllValues().get(0).getMessageId(), messages.getAllValues().get(1).getMessageId());
    }

    @Test
    void largeFinalAssistantReplyIsNotRoutedThroughToolPreviewAndCompletesInFull() throws Exception {
        CodexTaskEntity entity = legacyTask();
        entity.setStatus("RUNNING");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        String fullReply = "final reply \"\\多字节🔧\n".repeat(10_000);
        assertTrue(fullReply.getBytes(StandardCharsets.UTF_8).length > 64 * 1024);
        String resultEvent = new ObjectMapper().writeValueAsString(Map.of(
                "type", "result",
                "task_id", "worker-task-1",
                "session_id", "thread-1",
                "seq", 1,
                "content", fullReply));

        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(resultEvent).build(),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicInteger(0));

        ArgumentCaptor<AgentMessage> messageCaptor = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener).handleMessageDurably(messageCaptor.capture());
        AgentMessage resultMessage = messageCaptor.getValue();
        assertEquals(MessageType.SESSION_END, resultMessage.getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> resultPayload = (Map<String, Object>) resultMessage.getPayload();
        assertEquals(fullReply, resultPayload.get("content"));
        assertFalse(resultPayload.containsKey("dataTruncated"));

        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService).completeTask(eq("local-task-1"), eq("worker-task-1"), eq("thread-1"),
                resultCaptor.capture(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(1));
        assertEquals(fullReply, resultCaptor.getValue());
    }

    @Test
    void sequencedResultDoesNotAdvanceReplayCursorWhenAtomicTerminalAckFails() throws Exception {
        CodexTaskEntity entity = legacyTask();
        entity.setStatus("RUNNING");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        String result = "final result";
        String eventJson = new ObjectMapper().writeValueAsString(Map.of(
                "type", "result",
                "task_id", "worker-task-1",
                "session_id", "thread-1",
                "seq", 1,
                "content", result));
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(taskService).completeTask("local-task-1", "worker-task-1", "thread-1", result,
                        null, null, null, null, null, null, 1);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                        ServerSentEvent.builder(eventJson).build(),
                        "local-task-1", "session-1", "codex-worker",
                        new java.util.concurrent.atomic.AtomicReference<String>(),
                        new java.util.concurrent.atomic.AtomicReference<String>(),
                        new java.util.concurrent.atomic.AtomicInteger(0)));

        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        verify(taskService).completeTask("local-task-1", "worker-task-1", "thread-1", result,
                null, null, null, null, null, null, 1);
        verify(taskService, never()).recordWorkerProgress(
                eq("local-task-1"), any(), any(), any(), eq(1), anyBoolean(), anyBoolean());
        assertEquals(null, acknowledgedSequences().get("local-task-1"));
    }

    @Test
    void sequencedErrorDoesNotAdvanceReplayCursorWhenAtomicTerminalAckFails() throws Exception {
        CodexTaskEntity entity = legacyTask();
        entity.setStatus("RUNNING");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        String failure = "CODEX_WORKER_REMOTE_ERROR";
        String eventJson = new ObjectMapper().writeValueAsString(Map.of(
                "type", "error",
                "task_id", "worker-task-1",
                "session_id", "thread-1",
                "seq", 1,
                "error", failure,
                "terminal_observed", true,
                "terminal_status", "FAILED"));
        when(taskService.attachDiagnostic(eq("local-task-1"), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(taskService).failTask("local-task-1", "worker-task-1", "thread-1", failure, 1);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                        ServerSentEvent.builder(eventJson).build(),
                        "local-task-1", "session-1", "codex-worker",
                        new java.util.concurrent.atomic.AtomicReference<String>(),
                        new java.util.concurrent.atomic.AtomicReference<String>(),
                        new java.util.concurrent.atomic.AtomicInteger(0)));

        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        verify(taskService).failTask("local-task-1", "worker-task-1", "thread-1", failure, 1);
        verify(taskService, never()).recordWorkerProgress(
                eq("local-task-1"), any(), any(), any(), eq(1), anyBoolean(), anyBoolean());
        assertEquals(null, acknowledgedSequences().get("local-task-1"));
    }

    @Test
    void unverifiedErrorOnlyMarksAttentionAndKeepsCancellationPending() throws Exception {
        CodexTaskEntity entity = legacyTask();
        entity.setStatus("CANCEL_REQUESTED");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(taskService.attachDiagnostic(eq("local-task-1"), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        String eventJson = new ObjectMapper().writeValueAsString(Map.of(
                "type", "error",
                "task_id", "worker-task-1",
                "session_id", "thread-1",
                "seq", 1,
                "error", "CODEX_WORKER_REMOTE_ERROR"));

        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(eventJson).build(),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicInteger(0));

        verify(taskService).markLifecycleAttention("local-task-1", "PROCESS_UNVERIFIED");
        verify(taskService, never()).failTask(any(), any(), any(), any());
        verify(taskService, never()).reconcileAbortedTask(any(), any(), any());
        verify(taskService).recordWorkerProgress(
                "local-task-1", "worker-task-1", "thread-1", null, 1, false, true);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AgentMessage recovery = assertInstanceOf(AgentMessage.class, eventCaptor.getValue());
        assertEquals(MessageType.STATE_SYNC, recovery.getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) recovery.getPayload();
        assertEquals("CODEX_RUNTIME_RESULT_UNKNOWN", payload.get("content"));
        assertEquals("reconnect_pending", payload.get("subtype"));
        assertEquals(true, payload.get("reconnectable"));
    }

    @Test
    void processUnverifiedLifecycleWarningUsesRecoverableStatusInsteadOfGenericWarning() throws Exception {
        CodexTaskEntity entity = legacyTask();
        entity.setStatus("RUNNING");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        String eventJson = new ObjectMapper().writeValueAsString(Map.of(
                "type", "warning",
                "subtype", "lifecycle_attention",
                "attention_status", "PROCESS_UNVERIFIED",
                "task_id", "worker-task-1",
                "session_id", "thread-1",
                "seq", 1,
                "content", "Codex SDK stream ended without a provider terminal observation"));

        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(eventJson).build(),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicInteger(0));

        verify(taskService).markLifecycleAttention("local-task-1", "PROCESS_UNVERIFIED");
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AgentMessage recovery = assertInstanceOf(AgentMessage.class, eventCaptor.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) recovery.getPayload();
        assertEquals("CODEX_RUNTIME_RESULT_UNKNOWN", payload.get("content"));
        assertEquals("reconnect_pending", payload.get("subtype"));
        verifyNoInteractions(sessionEventListener);
    }

    @Test
    void verifiedAbortedErrorTransitionsThroughAbortReconciliation() throws Exception {
        CodexTaskEntity entity = legacyTask();
        entity.setStatus("CANCEL_REQUESTED");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(taskService.attachDiagnostic(eq("local-task-1"), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        String eventJson = new ObjectMapper().writeValueAsString(Map.of(
                "type", "error",
                "task_id", "worker-task-1",
                "session_id", "thread-1",
                "seq", 1,
                "error", "TASK_ABORTED",
                "terminal_observed", true,
                "terminal_status", "ABORTED"));

        ReflectionTestUtils.invokeMethod(relay, "handleSseEvent",
                ServerSentEvent.builder(eventJson).build(),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicInteger(0));

        verify(taskService).reconcileAbortedTask("local-task-1", "worker-task-1", "thread-1");
        verify(taskService, never()).failTask(any(), any(), any(), any());
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
    void lateTerminalEventIsIgnoredWithoutAdvancingCursorOrPublishingContradictoryOutcome() {
        CodexTaskEntity entity = stubAppServerTask("TERMINAL");
        entity.setWorkerTaskId("worker-task-1");
        entity.setStatus("COMPLETED");

        ReflectionTestUtils.invokeMethod(
                relay, "handleSseEvent", workerEvent("error", 1, "late failure"),
                "local-task-1", "session-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicInteger(0));

        verifyNoInteractions(taskService);
        verifyNoInteractions(sessionEventListener);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void terminalTaskSseErrorIsIgnoredWithoutSchedulingRecovery() {
        CodexTaskEntity entity = legacyTask();
        entity.setStatus("COMPLETED");
        entity.setWorkerTaskId("worker-task-1");
        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));

        ReflectionTestUtils.invokeMethod(relay, "handleSseError",
                "local-task-1", "session-1", "worker-1", "codex-worker",
                new java.util.concurrent.atomic.AtomicReference<String>(),
                new java.util.concurrent.atomic.AtomicReference<String>(),
                0, null, new IllegalStateException("post-terminal callback failure"));

        verifyNoInteractions(taskService);
        verifyNoInteractions(eventPublisher);
        @SuppressWarnings("unchecked")
        Map<String, Object> recoveries = (Map<String, Object>)
                ReflectionTestUtils.getField(relay, "scheduledRecoveries");
        assertTrue(recoveries.isEmpty());
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
        entity.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
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
                .providerType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE)
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
        entity.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        entity.setStatus("RUNNING");
        entity.setCreatedAtEpochMs(NOW.minusSeconds(30).toEpochMilli());
        return entity;
    }

    private BackgroundRecoveryPolicy recoveryPolicy(
            boolean enabled, int maxAttempts, int maxConcurrentRecoveries) {
        return new BackgroundRecoveryPolicy(enabled, new BackgroundRecoveryBounds(
                maxAttempts,
                Duration.ofHours(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                maxConcurrentRecoveries,
                Duration.ofMinutes(1)));
    }

    private static final class RegistrationRaceScheduler implements Scheduler {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicBoolean disposed = new AtomicBoolean(false);

        @Override
        public Disposable schedule(Runnable task) {
            AtomicBoolean taskDisposed = new AtomicBoolean(false);
            Disposable handle = new Disposable() {
                @Override
                public void dispose() {
                    taskDisposed.set(true);
                }

                @Override
                public boolean isDisposed() {
                    return taskDisposed.get();
                }
            };
            Thread callback = new Thread(() -> {
                started.countDown();
                try {
                    if (!disposed.get() && !taskDisposed.get()) task.run();
                } finally {
                    completed.countDown();
                }
            }, "codex-recovery-registration-race");
            callback.setDaemon(true);
            callback.start();
            try {
                if (!started.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timer callback did not start");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("timer registration interrupted", interrupted);
            }
            return handle;
        }

        @Override
        public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
            return schedule(task);
        }

        @Override
        public Worker createWorker() {
            throw new UnsupportedOperationException("worker scheduling is not used by this test");
        }

        @Override
        public void dispose() {
            disposed.set(true);
        }

        @Override
        public boolean isDisposed() {
            return disposed.get();
        }

        private boolean awaitCompletion() throws InterruptedException {
            return completed.await(2, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, java.util.concurrent.atomic.AtomicInteger> acknowledgedSequences() {
        return (Map<String, java.util.concurrent.atomic.AtomicInteger>)
                ReflectionTestUtils.getField(relay, "lastAckedSeq");
    }
}
