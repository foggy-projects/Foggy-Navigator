package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.diagnostic.ErrorDiagnosticInput;
import com.foggy.navigator.agent.framework.diagnostic.ErrorDiagnosticSanitizer;
import com.foggy.navigator.agent.framework.diagnostic.ErrorEnvelope;
import com.foggy.navigator.agent.framework.diagnostic.ErrorRuntimePhase;
import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.event.TaskStartedEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.AgentMessageBuilder;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.lifecycle.CodexLifecycleBindingDigest;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.dto.NativeSubtaskSnapshotDTO;
import com.foggy.navigator.common.dto.NativeSubtaskUpdatePayload;
import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.session.event.SessionEventListener;
import com.foggy.navigator.session.lifecycle.LifecycleActivationDeniedException;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.LifecycleProductionAdmissionService;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

/**
 * Codex Worker SSE → AgentMessage 桥接
 *
 * 监听 CodexTaskStartEvent，通过 WebClient 消费 Worker SSE 流，
 * 将每个 Worker 事件转为 AgentMessage 并 publishEvent，
 * 后续由现有 SessionEventListener 处理持久化和 SSE 推送。
 *
 * 复用 Claude Worker 的 WorkerEvent 格式（Codex Worker 输出兼容格式）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodexStreamRelay {

    private static final String AGENT_ID = CodexTaskService.CODEX_PROVIDER_TYPE;
    private static final String CODEX_APP_SERVER_AGENT_ID =
            CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE;
    private static final String CODEX_BIZ_AGENT_ID = CodexTaskService.CODEX_BIZ_PROVIDER_TYPE;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long RECONNECT_BASE_DELAY_MS = 2000;
    private static final long BACKGROUND_RECOVERY_DELAY_MS = 30_000;
    /**
     * BUG-021 compatibility contract. Enforcement moved to the generic session
     * payload router so every provider is bounded after optional externalization.
     */
    static final int MAX_DURABLE_TOOL_RESULT_METADATA_BYTES = 48 * 1024;

    private final WorkerManagementFacade workerManagementFacade;
    private final CodexWorkerClientFactory clientFactory;
    private final CodexTaskService taskService;
    private final CodexRuntimeRegistryService runtimeRegistryService;
    private final CodexTaskRuntimeStateService taskRuntimeStateService;
    private final CodexAppServerAcceptanceService appServerAcceptanceService;
    private final CodexNativeSubtaskService nativeSubtaskService;
    private final CodexTaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final SessionEventListener sessionEventListener;

    @Autowired(required = false)
    @Nullable
    private TaskLifecycleSnapshotRepository lifecycleSnapshots;

    @Autowired(required = false)
    @Nullable
    private LifecycleProductionAdmissionService lifecycleProductionAdmission;

    @Autowired(required = false)
    @Nullable
    private CodexLifecycleBindingDigest lifecycleBindingDigest;

    /** Fixed stripes avoid an unbounded per-task lock registry. */
    private final ReentrantLock[] streamOperationLocks = createStreamOperationLocks(1024);

    /** 活跃的流订阅，用于 abort */
    private final ConcurrentHashMap<String, Disposable> activeStreams = new ConcurrentHashMap<>();

    /** 每个任务已确认接收的最新事件序列号（ESN） */
    private final ConcurrentHashMap<String, AtomicInteger> lastAckedSeq = new ConcurrentHashMap<>();

    /** 重连互斥锁 */
    private final ConcurrentHashMap<String, AtomicBoolean> reconnecting = new ConcurrentHashMap<>();

    /** Avoids flooding one session with the same recoverable-result warning. */
    private final ConcurrentHashMap<String, Boolean> recoveryNotified = new ConcurrentHashMap<>();

    /** At most one delayed recovery may be pending for a task. */
    private final ConcurrentHashMap<String, Disposable> scheduledRecoveries = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Async("sessionEventExecutor")
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true,
            condition = "#event.providerType == 'codex-worker'"
                    + " || #event.providerType == 'codex-app-server-worker'"
                    + " || #event.providerType == 'codex-biz-worker'")
    public void onTaskStart(WorkerTaskStartEvent event) {
        ReentrantLock operationLock = streamOperationLock(event.getTaskId());
        operationLock.lock();
        try {
            onTaskStartLocked(event);
        } finally {
            operationLock.unlock();
        }
    }

    private void onTaskStartLocked(WorkerTaskStartEvent event) {
        String taskId = event.getTaskId();
        String sessionId = event.getSessionId();
        String workerId = event.getWorkerId();
        String providerType = providerType(event.getProviderType());

        if (activeStreams.containsKey(taskId)) {
            log.debug("Ignoring duplicate Codex task start because a stream is active: taskId={}", taskId);
            return;
        }

        log.info("Starting Codex stream relay: taskId={}, providerType={}, sessionId={}, workerId={}",
                taskId, providerType, sessionId, workerId);

        boolean appServerAccepted = false;
        LifecycleProductionAdmissionService.ProviderEffectCommand
                preEffectAdmissionCommand = null;
        boolean providerEffectAdmissionAttempted = false;
        try {
            CodexTaskEntity task = taskRepository.findByTaskId(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            String persistedProviderType = providerType(task.getProviderType());
            if (!persistedProviderType.equals(providerType)) {
                throw new IllegalStateException("CODEX_TASK_PROVIDER_MISMATCH: event routes to "
                        + providerType + " but task belongs to " + persistedProviderType);
            }
            providerType = persistedProviderType;
            if (!java.util.Objects.equals(sessionId, task.getSessionId())
                    || !java.util.Objects.equals(workerId, task.getWorkerId())) {
                log.warn("Ignoring transient start affinity in favor of persisted task binding: taskId={}", taskId);
            }
            sessionId = task.getSessionId();
            workerId = task.getWorkerId();

            Map<String, Object> sessionStartPayload = new LinkedHashMap<>();
            sessionStartPayload.put("content", "Connecting to Codex worker...");
            sessionStartPayload.put("taskId", taskId);
            if (event.getProviderConfigString("codexThreadId") != null) {
                sessionStartPayload.put("codexThreadId", event.getProviderConfigString("codexThreadId"));
            }
            publishMessage(sessionId, providerType, MessageType.SESSION_START, sessionStartPayload);

            CodexRuntimeBinding runtime = resolveRuntimeBinding(task);
            CodexWorkerClient client = getCodexClient(task, runtime);

            String codexThreadId = event.getProviderConfigString("codexThreadId");
            String images = blankToNull(event.getProviderConfigString("images"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments = event.getProviderConfigValue("attachments");
            String baseUrl = blankToNull(event.getProviderConfigString("baseUrl"));
            @SuppressWarnings("unchecked")
            Map<String, String> extraEnvVars = event.getProviderConfigValue("extraEnvVars");
            String codexHomeKey = blankToNull(event.getProviderConfigString("codexHomeKey"));
            String developerInstructions = blankToNull(event.getProviderConfigString("developerInstructions"));
            @SuppressWarnings("unchecked")
            Map<String, Object> businessRuntimeContext = event.getProviderConfigValue("businessRuntimeContext");
            @SuppressWarnings("unchecked")
            Map<String, Object> outputSchema = event.getProviderConfigValue("outputSchema");
            @SuppressWarnings("unchecked")
            Map<String, Object> codexConfig = event.getProviderConfigValue("codexConfig");
            String sandboxMode = blankToNull(event.getProviderConfigString("sandboxMode"));
            String approvalPolicy = blankToNull(event.getProviderConfigString("approvalPolicy"));
            Boolean networkAccessEnabled = event.getProviderConfigValue("networkAccessEnabled");
            String webSearchMode = blankToNull(event.getProviderConfigString("webSearchMode"));
            @SuppressWarnings("unchecked")
            List<String> additionalDirectories = event.getProviderConfigValue("additionalDirectories");
            log.info(
                    "Dispatching Codex worker query: taskId={}, workerId={}, model={}, hasApiKey={}, hasBaseUrl={}, envVarKeys={}, hasImages={}, resumeThread={}, hasCodexHomeKey={}, sandboxMode={}, approvalPolicy={}",
                    taskId,
                    workerId,
                    event.getModel(),
                    event.getApiKey() != null && !event.getApiKey().isBlank(),
                    baseUrl != null && !baseUrl.isBlank(),
                    extraEnvVars != null ? extraEnvVars.keySet() : List.of(),
                    images != null && !images.isBlank(),
                    codexThreadId != null && !codexThreadId.isBlank(),
                    codexHomeKey != null,
                    sandboxMode,
                    approvalPolicy
            );
            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedCodexThreadId = new AtomicReference<>(codexThreadId);

            Flux<ServerSentEvent<String>> sseFlux;
            int initialAckSeq = 0;
            if (runtime.getRuntimeType() == CodexRuntimeType.APP_SERVER) {
                Map<String, Object> requestBody = client.buildTaskRequest(
                        event.getPrompt(), event.getCwd(), codexThreadId, event.getModel(),
                        event.getMaxTurns(), images, attachments, event.getApiKey(), baseUrl, extraEnvVars,
                        codexHomeKey, developerInstructions, outputSchema, codexConfig,
                        sandboxMode, approvalPolicy, networkAccessEnabled, webSearchMode,
                        businessRuntimeContext, additionalDirectories);
                taskRuntimeStateService.prepareAcceptance(taskId, requestBody);
                String workerTaskId = appServerAcceptanceService.accept(client, taskId, requestBody);
                appServerAccepted = true;
                if (!taskRuntimeStateService.markSubscribed(taskId)) {
                    abortAndReconcileTask(taskRepository.findByTaskId(taskId).orElse(task));
                    return;
                }
                initialAckSeq = task.getLastAckedSeq() != null ? task.getLastAckedSeq() : 0;
                sseFlux = client.subscribeToTask(workerTaskId, initialAckSeq);
            } else {
                Map<String, Object> requestBody = client.buildTaskRequest(
                        event.getPrompt(), event.getCwd(),
                        codexThreadId, event.getModel(),
                        event.getMaxTurns(), images, attachments, event.getApiKey(),
                        baseUrl, extraEnvVars, codexHomeKey, developerInstructions,
                        outputSchema, codexConfig, sandboxMode, approvalPolicy,
                        networkAccessEnabled, webSearchMode,
                        businessRuntimeContext, additionalDirectories);
                String ownershipMode = lifecycleSnapshots == null
                        ? "SHADOW"
                        : lifecycleSnapshots.findById(taskId)
                        .map(value -> value.getOwnershipMode())
                        .orElse("SHADOW");
                if (lifecycleProductionAdmission != null
                        && lifecycleProductionAdmission
                        .ownershipModeForTask(taskId)
                        == LifecycleOwnershipMode.ENFORCED) {
                    ownershipMode = LifecycleOwnershipMode.ENFORCED.name();
                }
                String initialDispatchId = stableLifecycleDispatchId(
                        codexThreadId == null
                                ? "TASK_CREATE" : "TASK_RESUME",
                        taskId);
                Mono<CodexWorkerClient.LifecycleContextEvidence>
                        lifecycleContextRequest =
                        client.lifecycleContextEvidence(
                                workerId,
                                ownershipMode,
                                codexThreadId == null
                                        ? "TASK_CREATE" : "TASK_RESUME",
                                taskId,
                                initialDispatchId,
                                1,
                                null);
                CodexWorkerClient.LifecycleContextEvidence lifecycleEvidence =
                        lifecycleContextRequest == null
                                ? null
                                : lifecycleContextRequest
                                .onErrorResume(error -> Mono.empty())
                                .block(Duration.ofSeconds(12));
                Map<String, Object> lifecycleContext = lifecycleEvidence == null
                        ? null : lifecycleEvidence.wireContext();
                if (lifecycleContext == null && "ENFORCED".equals(ownershipMode)) {
                    throw new IllegalStateException(
                            "ENFORCED_LIFECYCLE_CONTEXT_UNAVAILABLE");
                }
                if (lifecycleContext != null) {
                    if ("ENFORCED".equals(ownershipMode)) {
                        if (lifecycleProductionAdmission == null
                                || lifecycleBindingDigest == null
                                || lifecycleEvidence == null) {
                            throw new IllegalStateException(
                                    "LIFECYCLE_ACTIVATION_AUTHORITY_UNAVAILABLE");
                        }
                        String bindingDigest = lifecycleBindingDigest.task(
                                requestBody, lifecycleContext);
                        preEffectAdmissionCommand =
                                new LifecycleProductionAdmissionService
                                        .ProviderEffectCommand(
                                        taskId, sessionId, workerId,
                                        lifecycleEvidence.identity(),
                                        initialDispatchId,
                                        "JCS_SHA256_V1",
                                        bindingDigest);
                        providerEffectAdmissionAttempted = true;
                        var authorization = lifecycleProductionAdmission
                                .admitAndAuthorizeProviderEffect(
                                        preEffectAdmissionCommand);
                        if (!authorization.providerCallAuthorized()) {
                            throw new IllegalStateException(
                                    authorization.safeReasonCode());
                        }
                    }
                    requestBody.put("lifecycle_context", lifecycleContext);
                    sseFlux = client.streamQuery(requestBody);
                } else {
                    sseFlux = client.streamQuery(
                            event.getPrompt(), event.getCwd(),
                            codexThreadId, event.getModel(),
                            event.getMaxTurns(), images, attachments,
                            event.getApiKey(), baseUrl, extraEnvVars,
                            codexHomeKey, developerInstructions,
                            outputSchema, codexConfig,
                            sandboxMode, approvalPolicy,
                            networkAccessEnabled, webSearchMode,
                            businessRuntimeContext, additionalDirectories);
                }
            }

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId, providerType,
                    detectedModel, detectedCodexThreadId, 0, initialAckSeq);

            registerActiveStream(taskId, subscription);

            // 发布跨 Agent 任务开始事件
            eventPublisher.publishEvent(TaskStartedEvent.builder()
                    .externalTaskId(taskId)
                    .parentSessionId(sessionId)
                    .targetAgentId(providerType)
                    .prompt(truncateResult(event.getPrompt()))
                    .build());

        } catch (CodexTaskRuntimeStateService.AcceptanceCancelledException e) {
            log.info("Codex app-server acceptance cancelled before subscription: taskId={}", taskId);
        } catch (CodexAppServerAcceptanceService.RejectedException e) {
            String failureCode = stableAcceptanceRejection(e);
            log.error("Codex app-server rejected task acceptance: taskId={}, error={}", taskId, failureCode);
            taskService.failTask(taskId, null, null, failureCode);
            publishMessage(sessionId, providerType, MessageType.ERROR,
                    Map.of("content", failureCode, "taskId", taskId));
        } catch (CodexAppServerAcceptanceService.UnknownException e) {
            log.error("Codex app-server acceptance is unknown: taskId={}", taskId);
            taskRuntimeStateService.markAcceptanceUnknown(taskId);
            publishResultUnknown(sessionId, providerType, taskId);
            scheduleReconnect(taskId, sessionId, workerId, 0, RECONNECT_BASE_DELAY_MS);
        } catch (Exception e) {
            String failureCode = stableFailureCode(e, "CODEX_WORKER_START_FAILED");
            log.error("Failed to start Codex stream relay: taskId={}, code={}, type={}",
                    taskId, failureCode, exceptionType(e));
            if (appServerAccepted) {
                publishResultUnknown(sessionId, providerType, taskId);
                scheduleReconnect(taskId, sessionId, workerId, 0, RECONNECT_BASE_DELAY_MS);
            } else {
                taskService.failTask(taskId, null, null, failureCode);
                if (providerEffectAdmissionAttempted
                        && preEffectAdmissionCommand != null
                        && e instanceof LifecycleActivationDeniedException denied
                        && lifecycleProductionAdmission != null
                        && lifecycleProductionAdmission
                        .supportsDeterministicPreEffectClosure(
                                denied.getMessage())) {
                    try {
                        lifecycleProductionAdmission
                                .closeDeterministicPreEffectAdmissionFailure(
                                        preEffectAdmissionCommand,
                                        denied.getMessage());
                    } catch (RuntimeException closureFailure) {
                        log.warn("Failed to commit server pre-effect terminal fence: taskId={}, reason={}, type={}",
                                taskId, denied.getMessage(),
                                exceptionType(closureFailure));
                    }
                }
                publishMessage(sessionId, providerType, MessageType.ERROR,
                        Map.of("content", failureCode, "taskId", taskId));
            }
        }
    }

    /**
     * 重连已在 Worker 上运行的任务
     */
    public void reconnectTask(String taskId, String sessionId, String workerId) {
        reconnectTask(taskId, sessionId, workerId, 0);
    }

    private void reconnectTask(String taskId, String sessionId, String workerId, int reconnectAttempt) {
        ReentrantLock operationLock = streamOperationLock(taskId);
        operationLock.lock();
        try {
            reconnectTaskLocked(taskId, sessionId, workerId, reconnectAttempt);
        } finally {
            operationLock.unlock();
        }
    }

    private void reconnectTaskLocked(String taskId, String sessionId, String workerId, int reconnectAttempt) {
        if (activeStreams.containsKey(taskId)) {
            log.debug("reconnectTask: task {} already has active stream, skipping", taskId);
            return;
        }

        AtomicBoolean guard = reconnecting.computeIfAbsent(taskId, k -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            log.debug("reconnectTask: task {} reconnection already in progress, skipping", taskId);
            return;
        }

        log.info("Reconnecting Codex stream: taskId={}, sessionId={}, workerId={}", taskId, sessionId, workerId);

        try {
            CodexTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
            if (entity == null) {
                log.warn("reconnectTask: task {} not found in repository", taskId);
                return;
            }
            if (isLocalTerminal(entity)) {
                clearStreamTracking(taskId);
                return;
            }
            sessionId = entity.getSessionId();
            workerId = entity.getWorkerId();
            CodexRuntimeBinding runtime = resolveRuntimeBinding(entity);
            CodexWorkerClient client = getCodexClient(entity, runtime);
            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedCodexThreadId = new AtomicReference<>(entity.getCodexThreadId());
            if (entity.getWorkerTaskId() == null || entity.getWorkerTaskId().isBlank()) {
                if (runtime.getRuntimeType() == CodexRuntimeType.APP_SERVER
                        && "ABORTED_BEFORE_ACCEPT".equals(entity.getRuntimeAcceptanceState())) {
                    taskService.reconcileAbortedTask(taskId, null, entity.getCodexThreadId());
                    return;
                }
                if (runtime.getRuntimeType() == CodexRuntimeType.APP_SERVER
                        && "PREPARED".equals(entity.getRuntimeAcceptanceState())) {
                    if (taskService.failTaskIfAcceptanceNotStarted(
                            taskId, "CODEX_RUNTIME_NOT_ACCEPTED")) {
                        return;
                    }
                    entity = taskRepository.findByTaskId(taskId)
                            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
                    if ("ABORTED_BEFORE_ACCEPT".equals(entity.getRuntimeAcceptanceState())) {
                        taskService.reconcileAbortedTask(taskId, null, entity.getCodexThreadId());
                        return;
                    }
                }
                if (runtime.getRuntimeType() != CodexRuntimeType.APP_SERVER
                        || !isRecoverableAcceptanceState(entity.getRuntimeAcceptanceState())) {
                    log.warn("reconnectTask: task {} has no recoverable upstream acceptance", taskId);
                    return;
                }
                Map<String, Object> requestBody = taskRuntimeStateService.loadPreparedRequest(taskId);
                appServerAcceptanceService.accept(client, taskId, requestBody);
                entity = taskRepository.findByTaskId(taskId)
                        .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            }

            // A user-triggered reconnect is also a chance to obtain explicit remote
            // terminal evidence. Do not infer an outcome from a missing stream: only
            // failed/aborted status responses are reconciled here; completed still
            // needs its durable final SSE payload.
            if (runtime.getRuntimeType() == CodexRuntimeType.APP_SERVER
                    && reconcileAppServerTerminal(entity, sessionId, resolveTaskProviderType(taskId),
                    detectedModel, detectedCodexThreadId)) {
                clearStreamTracking(taskId);
                return;
            }

            String providerType = resolveTaskProviderType(taskId);

            AtomicInteger seqTracker = lastAckedSeq.get(taskId);
            int memoryAckSeq = seqTracker != null ? seqTracker.get() : 0;
            int persistedAckSeq = entity.getLastAckedSeq() != null ? entity.getLastAckedSeq() : 0;
            int ackSeq = Math.max(memoryAckSeq, persistedAckSeq);

            if (runtime.getRuntimeType() == CodexRuntimeType.APP_SERVER
                    && !taskRuntimeStateService.markSubscribed(taskId)) {
                abortAndReconcileTask(taskRepository.findByTaskId(taskId).orElse(entity));
                return;
            }

            Flux<ServerSentEvent<String>> sseFlux = client.subscribeToTask(entity.getWorkerTaskId(), ackSeq);

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId, providerType,
                    detectedModel, detectedCodexThreadId, reconnectAttempt, ackSeq);

            registerActiveStream(taskId, subscription);

        } catch (CodexTaskRuntimeStateService.AcceptanceCancelledException e) {
            log.info("Recovered acceptance was cancelled before subscription: taskId={}", taskId);
        } catch (CodexAppServerAcceptanceService.RejectedException e) {
            String failureCode = stableAcceptanceRejection(e);
            taskService.failTask(taskId, null, null, failureCode);
            log.warn("App-server rejected recovered acceptance for task {}: {}", taskId, failureCode);
        } catch (CodexAppServerAcceptanceService.UnknownException e) {
            taskRuntimeStateService.markAcceptanceUnknown(taskId);
            log.warn("App-server acceptance remains unknown for task {}", taskId);
            scheduleRecoveryRound(taskId, sessionId, workerId, reconnectAttempt);
        } catch (Exception e) {
            log.warn("Failed to reconnect Codex task: taskId={}, code={}, type={}",
                    taskId, stableFailureCode(e, "CODEX_RUNTIME_RECONNECT_FAILED"), exceptionType(e));
            scheduleRecoveryRound(taskId, sessionId, workerId, reconnectAttempt);
        } finally {
            guard.set(false);
        }
    }

    /**
     * 启动时重连所有活跃任务
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            Thread.sleep(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        reconnectActiveTasks();
    }

    void reconnectActiveTasks() {
        List<CodexTaskEntity> activeTasks = taskRepository.findByStatusIn(
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"));
        if (activeTasks.isEmpty()) {
            log.info("No active Codex tasks to reconnect on startup");
            return;
        }

        log.info("Attempting to reconnect {} active Codex task(s) on startup", activeTasks.size());
        for (CodexTaskEntity task : activeTasks) {
            try {
                reconnectTask(task.getTaskId(), task.getSessionId(), task.getWorkerId());
            } catch (Exception e) {
                log.warn("Failed to reconnect Codex task on startup: taskId={}, code={}, type={}",
                        task.getTaskId(), stableFailureCode(e, "CODEX_RUNTIME_RECONNECT_FAILED"), exceptionType(e));
                scheduleReconnect(task.getTaskId(), task.getSessionId(), task.getWorkerId(),
                        0, BACKGROUND_RECOVERY_DELAY_MS);
            }
        }
    }

    /**
     * 中止流
     */
    public void abortStream(String taskId) {
        ReentrantLock operationLock = streamOperationLock(taskId);
        operationLock.lock();
        try {
            abortStreamLocked(taskId);
        } finally {
            operationLock.unlock();
        }
    }

    private void abortStreamLocked(String taskId) {
        Disposable subscription = activeStreams.remove(taskId);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            log.info("Aborted Codex stream: taskId={}", taskId);
        }
        lastAckedSeq.remove(taskId);
        reconnecting.remove(taskId);
        cancelScheduledRecovery(taskId);
        recoveryNotified.remove(taskId);
    }

    /**
     * 尝试远程中止 worker 侧任务；若尚未拿到 upstream task_id，仅记录日志。
     */
    public RemoteAbortResolution abortRemoteTask(CodexTaskEntity task) {
        throw new IllegalStateException(
                "TERMINATION_CAPABILITY_REQUIRED: use CodexTaskService explicit cancellation flow");
    }

    /**
     * Legacy recovery hook. It deliberately never issues a remote abort: all
     * callers here are lifecycle/recovery paths rather than an explicit user
     * cancellation operation. The original task/stream/reservation remains
     * owned until terminal evidence arrives.
     */
    public void abortAndReconcileTask(CodexTaskEntity task) {
        if (task == null) return;
        taskService.markLifecycleAttention(task.getTaskId(), "TERMINATION_REQUIRES_EXPLICIT_OPERATION");
        log.warn("Suppressed automatic Codex termination: taskId={}, runtimeState={}",
                task.getTaskId(), task.getRuntimeAcceptanceState());
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private Disposable subscribeSseFlux(Flux<ServerSentEvent<String>> sseFlux,
                                         String taskId, String sessionId, String workerId, String providerType,
                                         AtomicReference<String> detectedModel,
                                         AtomicReference<String> detectedCodexThreadId,
                                         int reconnectAttempt,
                                         int initialAckSeq) {
        AtomicInteger seqTracker = lastAckedSeq.compute(taskId, (ignored, current) -> {
            if (current == null) return new AtomicInteger(initialAckSeq);
            current.updateAndGet(value -> Math.max(value, initialAckSeq));
            return current;
        });
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        Disposable subscription = sseFlux
                .concatMap(sse -> Mono.fromRunnable(() -> handleSseEvent(
                        sse, taskId, sessionId, providerType,
                        detectedModel, detectedCodexThreadId, seqTracker)))
                .subscribe(
                        ignored -> { },
                        error -> handleSseError(taskId, sessionId, workerId, providerType,
                                detectedModel, detectedCodexThreadId, reconnectAttempt,
                                subscriptionRef.get(), error),
                        () -> handleSseCompletion(taskId, sessionId, workerId, providerType,
                                detectedModel, detectedCodexThreadId, reconnectAttempt,
                                subscriptionRef.get())
                );
        subscriptionRef.set(subscription);
        return subscription;
    }

    private void registerActiveStream(String taskId, Disposable subscription) {
        if (subscription != null && !subscription.isDisposed()) {
            cancelScheduledRecovery(taskId);
            Disposable previous = activeStreams.put(taskId, subscription);
            if (previous != null && previous != subscription && !previous.isDisposed()) {
                previous.dispose();
            }
        }
    }

    private void handleSseError(String taskId, String sessionId, String workerId, String providerType,
                                 AtomicReference<String> detectedModel,
                                 AtomicReference<String> detectedCodexThreadId,
                                 int reconnectAttempt, Disposable failedSubscription, Throwable error) {
        log.warn("Codex SSE stream error: taskId={}, attempt={}, type={}",
                taskId, reconnectAttempt, error != null ? error.getClass().getSimpleName() : "UnknownException");
        if (failedSubscription != null) {
            activeStreams.remove(taskId, failedSubscription);
        }

        CodexWorkerClient.WorkerQueryRejectedException rejected = findCause(
                error, CodexWorkerClient.WorkerQueryRejectedException.class);
        if (rejected != null) {
            if (rejected instanceof CodexWorkerClient.ThreadActiveException threadActive) {
                log.warn("Codex thread already has a live execution: taskId={}, sessionId={}, activeTaskId={}, "
                                + "activePid={}, source={}",
                        taskId, threadActive.getSessionId(), threadActive.getActiveTaskId(),
                        threadActive.getActivePid(), threadActive.getConflictSource());
            } else {
                log.warn("Codex Worker rejected query: taskId={}, status={}, code={}",
                        taskId, rejected.getStatusCode(), rejected.getCode());
            }
            failStreamTask(taskId, sessionId, providerType, detectedCodexThreadId,
                    rejected.getCode());
            return;
        }

        CodexTaskEntity task;
        try {
            task = taskRepository.findByTaskId(taskId).orElse(null);
        } catch (Exception repositoryError) {
            log.warn("Cannot inspect task after Codex stream error; recovery remains scheduled: taskId={}, type={}",
                    taskId, repositoryError.getClass().getSimpleName());
            scheduleRecoveryRound(taskId, sessionId, workerId, reconnectAttempt);
            return;
        }
        if (isLocalTerminal(task)) {
            log.info("Ignoring Codex SSE error after terminal task: taskId={}, status={}, type={}",
                    taskId, task.getStatus(), exceptionType(error));
            clearStreamTracking(taskId);
            return;
        }
        if (task == null || task.getWorkerTaskId() == null || task.getWorkerTaskId().isBlank()) {
            failStreamTask(taskId, sessionId, providerType, detectedCodexThreadId,
                    "CODEX_WORKER_STREAM_FAILED_BEFORE_ACCEPTANCE");
            return;
        }

        boolean appServer = CodexRuntimeType.APP_SERVER.name().equals(task.getRuntimeType());
        if (appServer && reconcileAppServerTerminal(
                task, sessionId, providerType, detectedModel, detectedCodexThreadId)) {
            clearStreamTracking(taskId);
            return;
        }

        if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
            scheduleRecoveryRound(taskId, sessionId, workerId, reconnectAttempt);
            return;
        }

        // Once the Worker has accepted the task, losing its SSE transport does
        // not mean the remote Codex execution stopped. Keep the local task
        // recoverable and replay the durable event stream in a later round.
        log.warn("Codex stream retry round exhausted; continuing background recovery: taskId={}, runtime={}",
                taskId, appServer ? CodexRuntimeType.APP_SERVER.name() : "LEGACY_SDK");
        if (appServer) {
            publishResultUnknown(sessionId, providerType, taskId);
        } else {
            publishStreamDisconnected(sessionId, providerType, taskId);
        }
        scheduleRecoveryRound(taskId, sessionId, workerId, reconnectAttempt);
    }

    private void handleSseCompletion(String taskId, String sessionId, String workerId, String providerType,
                                      AtomicReference<String> detectedModel,
                                      AtomicReference<String> detectedCodexThreadId,
                                      int reconnectAttempt, Disposable completedSubscription) {
        log.info("Codex SSE stream completed: taskId={}", taskId);
        if (completedSubscription != null) {
            activeStreams.remove(taskId, completedSubscription);
        }

        CodexTaskEntity task;
        try {
            task = taskRepository.findByTaskId(taskId).orElse(null);
        } catch (Exception repositoryError) {
            log.warn("Cannot inspect task after Codex stream completion; recovery remains scheduled: taskId={}, type={}",
                    taskId, repositoryError.getClass().getSimpleName());
            scheduleRecoveryRound(taskId, sessionId, workerId, reconnectAttempt);
            return;
        }
        if (task == null || isLocalTerminal(task)) {
            clearStreamTracking(taskId);
            return;
        }

        if (CodexRuntimeType.APP_SERVER.name().equals(task.getRuntimeType())) {
            if (reconcileAppServerTerminal(
                    task, sessionId, providerType, detectedModel, detectedCodexThreadId)) {
                clearStreamTracking(taskId);
            } else {
                log.warn("App-server SSE completed before a local or remote terminal outcome: taskId={}", taskId);
                publishResultUnknown(sessionId, providerType, taskId);
                scheduleRecoveryRound(taskId, sessionId, workerId, reconnectAttempt);
            }
            return;
        }

        // Preserve the legacy lane behavior; a later startup/manual reconnect can recover it.
        clearStreamTracking(taskId);
    }

    private boolean reconcileAppServerTerminal(CodexTaskEntity task, String sessionId, String providerType,
                                                AtomicReference<String> detectedModel,
                                                AtomicReference<String> detectedCodexThreadId) {
        if (isLocalTerminal(task)) return true;
        String workerTaskId = task.getWorkerTaskId();
        if (workerTaskId == null || workerTaskId.isBlank()) return false;

        try {
            CodexRuntimeBinding runtime = resolveRuntimeBinding(task);
            CodexWorkerClient client = getCodexClient(task, runtime);
            RemoteTaskStatus status = fetchAppServerTaskStatus(task, client, workerTaskId);
            if (status == null) return false;

            if (status.pendingInteraction() != null && !status.pendingInteraction().isEmpty()) {
                CodexTaskService.UserInputRegistration registration =
                        taskService.registerPendingUserInput(task.getTaskId(), status.pendingInteraction());
                if (registration.shouldPublish()) {
                    publishUserInputRequest(sessionId, providerType, task.getTaskId(), registration,
                            userInputMessageId("cx-ui-req-", task.getTaskId(), registration.requestId()));
                }
            }
            if (!status.isTerminal()) return false;

            if (status.threadId() != null) detectedCodexThreadId.set(status.threadId());
            if (status.model() != null) detectedModel.set(status.model());

            String outcome = status.outcome();
            if ("completed".equals(outcome)) {
                // Status does not carry the result body/metrics. Keep the local task
                // recoverable and replay the durable terminal SSE instead of storing
                // a false successful completion with a null result.
                return false;
            }
            if ("failed".equals(outcome)) {
                closePendingUserInputBeforeTerminal(task.getTaskId(), sessionId, providerType);
                String failure = "CODEX_RUNTIME_REMOTE_FAILED: "
                        + stableRemoteErrorCode(status.errorCode());
                taskService.failTask(task.getTaskId(), workerTaskId, detectedCodexThreadId.get(), failure);
                publishMessageIfSession(sessionId, providerType, MessageType.ERROR,
                        Map.of("content", failure, "taskId", task.getTaskId()));
                publishCompletion(task.getTaskId(), sessionId, providerType, failure, "FAILED");
                return true;
            }
            if ("aborted".equals(outcome)) {
                closePendingUserInputBeforeTerminal(task.getTaskId(), sessionId, providerType);
                taskService.reconcileAbortedTask(task.getTaskId(), workerTaskId, detectedCodexThreadId.get());
                publishCompletion(task.getTaskId(), sessionId, providerType,
                        "CODEX_RUNTIME_REMOTE_ABORTED", "ABORTED");
                return true;
            }
            log.warn("Ignoring unsupported terminal app-server outcome: taskId={}", task.getTaskId());
            return false;
        } catch (Exception e) {
            log.warn("App-server terminal reconciliation unavailable: taskId={}, type={}",
                    task.getTaskId(), e.getClass().getSimpleName());
            return false;
        }
    }

    private RemoteTaskStatus fetchAppServerTaskStatus(CodexTaskEntity task, CodexWorkerClient client,
                                                       String workerTaskId) {
        var statusMono = client.getTaskStatus(workerTaskId);
        if (statusMono == null) return null;
        Map<String, Object> body = statusMono.block(Duration.ofSeconds(10));
        if (body == null) return null;
        String returnedTaskId = stringField(body, "task_id");
        if (!workerTaskId.equals(returnedTaskId)) {
            throw new IllegalStateException("CODEX_RUNTIME_STATUS_TASK_MISMATCH");
        }
        // resolveRuntimeBinding(task) already enforces immutable Worker runtime instance affinity.
        return new RemoteTaskStatus(
                stringField(body, "status"),
                stringField(body, "outcome"),
                stringField(body, "thread_id"),
                stringField(body, "model"),
                stringField(body, "error_code"),
                objectMap(body.get("pending_interaction")));
    }

    private void publishResultUnknown(String sessionId, String providerType, String taskId) {
        publishRecoveryPending(sessionId, providerType, taskId, "CODEX_RUNTIME_RESULT_UNKNOWN");
    }

    private void publishStreamDisconnected(String sessionId, String providerType, String taskId) {
        publishRecoveryPending(sessionId, providerType, taskId, "CODEX_WORKER_STREAM_DISCONNECTED");
    }

    private void publishRecoveryPending(String sessionId, String providerType, String taskId, String content) {
        if (recoveryNotified.putIfAbsent(taskId, Boolean.TRUE) == null) {
            publishMessageIfSession(sessionId, providerType, MessageType.STATE_SYNC,
                    Map.of(
                            "content", content,
                            "subtype", "reconnect_pending",
                            "reconnectable", true,
                            "taskId", taskId));
        }
    }

    private void publishMessageIfSession(String sessionId, String providerType, MessageType type,
                                         Map<String, Object> payload) {
        if (sessionId != null && !sessionId.isBlank()) {
            publishMessage(sessionId, providerType, type, payload);
        }
    }

    private void publishCompletion(String taskId, String sessionId, String providerType,
                                   String summary, String status) {
        eventPublisher.publishEvent(TaskCompletionEvent.builder()
                .externalTaskId(taskId)
                .parentSessionId(sessionId)
                .targetAgentId(providerType)
                .resultSummary(truncateResult(summary))
                .status(status)
                .build());
    }

    private void scheduleRecoveryRound(String taskId, String sessionId, String workerId,
                                       int reconnectAttempt) {
        if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
            long delay = (long) Math.pow(2, reconnectAttempt) * RECONNECT_BASE_DELAY_MS;
            scheduleReconnect(taskId, sessionId, workerId, reconnectAttempt + 1, delay);
        } else {
            scheduleReconnect(taskId, sessionId, workerId, 0, BACKGROUND_RECOVERY_DELAY_MS);
        }
    }

    private void scheduleReconnect(String taskId, String sessionId, String workerId,
                                   int reconnectAttempt, long delayMs) {
        if (shuttingDown.get()) return;
        log.info("Scheduling Codex stream recovery in {}ms: taskId={}, attempt={}",
                delayMs, taskId, reconnectAttempt);
        AtomicReference<Disposable> scheduledRef = new AtomicReference<>();
        Disposable scheduled = Schedulers.boundedElastic().schedule(() -> {
            scheduledRecoveries.remove(taskId, scheduledRef.get());
            if (shuttingDown.get()) return;
            try {
                CodexTaskEntity current = taskRepository.findByTaskId(taskId).orElse(null);
                if (current == null || isLocalTerminal(current)) {
                    clearStreamTracking(taskId);
                    return;
                }
                reconnectTask(taskId, current.getSessionId(), current.getWorkerId(), reconnectAttempt);
            } catch (Exception recoveryError) {
                log.warn("Scheduled Codex recovery failed before reconnect: taskId={}, type={}",
                        taskId, recoveryError.getClass().getSimpleName());
                scheduleRecoveryRound(taskId, sessionId, workerId, reconnectAttempt);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        scheduledRef.set(scheduled);
        Disposable previous = scheduledRecoveries.put(taskId, scheduled);
        if (previous != null && previous != scheduled && !previous.isDisposed()) {
            previous.dispose();
        }
        if (shuttingDown.get() && scheduledRecoveries.remove(taskId, scheduled)
                && !scheduled.isDisposed()) {
            scheduled.dispose();
        }
    }

    private void clearStreamTracking(String taskId) {
        activeStreams.remove(taskId);
        lastAckedSeq.remove(taskId);
        reconnecting.remove(taskId);
        recoveryNotified.remove(taskId);
        cancelScheduledRecovery(taskId);
    }

    private void cancelScheduledRecovery(String taskId) {
        Disposable scheduled = scheduledRecoveries.remove(taskId);
        if (scheduled != null && !scheduled.isDisposed()) {
            scheduled.dispose();
        }
    }

    private boolean isLocalTerminal(CodexTaskEntity task) {
        return task != null && ("COMPLETED".equals(task.getStatus())
                || "FAILED".equals(task.getStatus())
                || "ABORTED".equals(task.getStatus()));
    }

    private String stableRemoteErrorCode(String value) {
        if (value != null && value.matches("[A-Z][A-Z0-9_]{0,127}")) return value;
        return "CODEX_RUNTIME_REMOTE_FAILED";
    }

    private String stringField(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value != null ? value.toString() : null;
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, entryValue) -> {
            if (key != null) {
                result.put(key.toString(), entryValue);
            }
        });
        return result;
    }

    private record RemoteTaskStatus(String status, String outcome, String threadId,
                                    String model, String errorCode,
                                    Map<String, Object> pendingInteraction) {
        private boolean isTerminal() {
            return "terminal".equals(status);
        }
    }

    public record RemoteAbortResolution(String outcome, String workerTaskId, String codexThreadId,
                                        String model, String errorCode) {
    }

    private void failStreamTask(String taskId, String sessionId, String providerType,
                                AtomicReference<String> detectedCodexThreadId,
                                String errorMessage) {
        taskService.failTask(taskId, null, detectedCodexThreadId.get(), errorMessage);
        publishMessage(sessionId, providerType, MessageType.ERROR,
                Map.of("content", errorMessage, "taskId", taskId));
        lastAckedSeq.remove(taskId);
        reconnecting.remove(taskId);
        eventPublisher.publishEvent(TaskCompletionEvent.builder()
                .externalTaskId(taskId)
                .parentSessionId(sessionId)
                .targetAgentId(providerType)
                .resultSummary(truncateResult(errorMessage))
                .status("FAILED")
                .build());
    }

    private void handleSseEvent(ServerSentEvent<String> sse, String taskId, String sessionId, String providerType,
                                 AtomicReference<String> detectedModel,
                                 AtomicReference<String> detectedCodexThreadId) {
        AtomicInteger seqTracker = lastAckedSeq.computeIfAbsent(taskId, ignored -> new AtomicInteger(0));
        handleSseEvent(sse, taskId, sessionId, providerType, detectedModel, detectedCodexThreadId, seqTracker);
    }

    private void handleSseEvent(ServerSentEvent<String> sse, String taskId, String sessionId, String providerType,
                                AtomicReference<String> detectedModel,
                                AtomicReference<String> detectedCodexThreadId,
                                AtomicInteger seqTracker) {
        ReentrantLock operationLock = streamOperationLock(taskId);
        operationLock.lock();
        try {
            handleSseEventLocked(sse, taskId, sessionId, providerType,
                    detectedModel, detectedCodexThreadId, seqTracker);
        } finally {
            operationLock.unlock();
        }
    }

    private void handleSseEventLocked(ServerSentEvent<String> sse, String taskId,
                                      String sessionId, String providerType,
                                      AtomicReference<String> detectedModel,
                                      AtomicReference<String> detectedCodexThreadId,
                                      AtomicInteger seqTracker) {
        String data = sse.data();
        if (data == null || data.isEmpty()) return;

        try {
            if ("lifecycle_disposition".equals(sse.event())) {
                persistLifecycleDisposition(taskId, data);
                return;
            }
            WorkerEvent event = objectMapper.readValue(data, WorkerEvent.class);

            CodexTaskEntity currentTask = taskRepository.findByTaskId(taskId).orElse(null);
            if (isLocalTerminal(currentTask)) {
                log.info("Ignoring Codex SSE event after terminal task: taskId={}, status={}, type={}, seq={}",
                        taskId, currentTask.getStatus(), event.getType(), event.getSeq());
                clearStreamTracking(taskId);
                return;
            }

            if (!isNextWorkerEvent(event, taskId, seqTracker)) {
                return;
            }

            // 更新 codexThreadId
            if (event.getSessionId() != null) {
                detectedCodexThreadId.set(event.getSessionId());
            }

            // 更新 model
            if (event.getModel() != null) {
                detectedModel.set(event.getModel());
            }

            String type = event.getType();
            if (type == null) return;

            if ("execution_committed".equals(event.getSubtype())) {
                acknowledgeWorkerEvent(taskId, event, false);
                return;
            }

            if ("sync_checkpoint".equals(event.getSubtype())) {
                acknowledgeWorkerEvent(taskId, event, false);
                log.debug("Ignoring sync checkpoint for task {}", taskId);
                return;
            }

            if ("native_subtask_update".equals(type)) {
                handleNativeSubtaskUpdate(event, taskId, sessionId, providerType,
                        detectedCodexThreadId.get());
                return;
            }

            boolean userVisibleOutput = isUserVisibleOutputEvent(event);
            // Persist identity/commit metadata first, but do not advance ack until
            // every durable/user-visible side effect below has succeeded.
            taskService.recordWorkerProgress(taskId, event.getTaskId(), event.getSessionId(),
                    event.getModel(), null, userVisibleOutput, isExecutionCommittedEvent(event));

            // 使用 AgentMessageBuilder 标准化 payload 字段名
            AgentMessageBuilder mb = AgentMessageBuilder.create(sessionId, providerType)
                    .taskId(taskId)
                    .put("codexThreadId", detectedCodexThreadId.get())
                    .put("streamId", event.getStreamId());

            switch (type) {
                case "assistant_text" -> {
                    String content = event.getContent() != null ? event.getContent() : "";
                    publishBuilt("text_delta".equals(event.getSubtype())
                                    ? mb.textChunk(content)
                                    : "commentary".equals(event.getSubtype())
                                            ? mb.stateSync(content, "commentary")
                                            : mb.textComplete(content),
                            workerMessageId(taskId, event));
                }
                case "tool_use" -> {
                    // 标准化: Codex 原字段 tool/input → 统一 toolName/arguments
                    publishBuilt(mb.toolCallStart(event.getToolUseId(), event.getTool(), event.getInput()),
                            workerMessageId(taskId, event));
                }
                case "tool_result" -> {
                    // 标准化: Codex 原字段 tool/output/isError → 统一 toolName/data/success
                    boolean success = event.getIsError() == null || !event.getIsError();
                    publishToolResult(mb, event, success, workerMessageId(taskId, event));
                }
                case "image_generation" -> {
                    Map<String, Object> image = generatedImagePayload(event, taskId);
                    publishBuilt(mb.toolCallResult(
                                    event.getToolUseId(), "image_generation", image, true),
                            workerMessageId(taskId, event));
                }
                case "warning" -> {
                    // Worker diagnostics may contain command lines, workspace paths, or
                    // credentials.  Keep a warning non-terminal, but never relay its
                    // raw diagnostic text into durable session state/SSE.
                    if (isProcessUnverifiedAttention(event)) {
                        taskService.markLifecycleAttention(taskId, "PROCESS_UNVERIFIED");
                        publishResultUnknown(sessionId, providerType, taskId);
                        break;
                    }
                    String warning = ErrorDiagnosticSanitizer.sanitize(event.getContent());
                    publishBuilt(mb.stateSync(warning != null ? warning : "CODEX_WORKER_WARNING", "warning"),
                            workerMessageId(taskId, event));
                }
                case "user_input_request" -> {
                    CodexTaskService.UserInputRegistration registration =
                            taskService.registerPendingUserInput(taskId, event.getData());
                    if (registration.shouldPublish()) {
                        publishUserInputRequest(sessionId, providerType, taskId, registration,
                                userInputMessageId("cx-ui-req-", taskId, registration.requestId()));
                    }
                }
                case "user_input_resolved" -> {
                    CodexTaskService.UserInputResolution resolution =
                            taskService.resolvePendingUserInput(taskId, event.getData());
                    publishUserInputResolution(sessionId, providerType, taskId, resolution);
                }
                case "result" -> {
                    closePendingUserInputBeforeTerminal(taskId, sessionId, providerType);
                    String resultText = event.getContent() != null ? event.getContent() : event.getResult();
                    mb.result(resultText)
                            .metrics(event.getCostUsd(), event.getDurationMs(),
                                    event.getInputTokens(), event.getOutputTokens(),
                                    event.getNumTurns(), event.getModel());
                    // result 事件用 SESSION_END 类型（Codex 特有语义）
                    AgentMessage resultMessage = mb.build(MessageType.SESSION_END);
                    String resultMessageId = workerMessageId(taskId, event);
                    if (resultMessageId != null) {
                        resultMessage.setMessageId(resultMessageId);
                    }
                    publishEvent(resultMessage);

                    // 完成任务记录
                    if (event.getSeq() != null) {
                        taskService.completeTask(taskId, event.getTaskId(),
                                detectedCodexThreadId.get(), resultText, event.getCostUsd(),
                                event.getInputTokens(), event.getOutputTokens(), event.getDurationMs(),
                                event.getNumTurns(), event.getModel(), event.getSeq());
                    } else {
                        taskService.completeTask(taskId, event.getTaskId(),
                                detectedCodexThreadId.get(), resultText, event.getCostUsd(),
                                event.getInputTokens(), event.getOutputTokens(), event.getDurationMs(),
                                event.getNumTurns(), event.getModel());
                    }
                    // For sequenced terminal events the task service commits
                    // result and ACK atomically. Clear the volatile cursor only
                    // after that call succeeds; a failure leaves it untouched
                    // for replay.
                    lastAckedSeq.remove(taskId);

                    // 发布任务完成事件
                    eventPublisher.publishEvent(TaskCompletionEvent.builder()
                            .externalTaskId(taskId)
                            .parentSessionId(sessionId)
                            .targetAgentId(providerType)
                            .resultSummary(truncateResult(resultText))
                            .status("COMPLETED")
                            .build());
                }
                case "error" -> {
                    ErrorEnvelope error = taskService.attachDiagnostic(
                            taskId, workerErrorEnvelope(event, taskId), workerDiagnosticInput(event));
                    String failure = error.getErrorCode();
                    if (!isVerifiedTerminalError(event)) {
                        // An `error` event can describe a transport failure,
                        // cancellation request, or diagnostic emitted before
                        // the CLI has exited.  It never proves a terminal task
                        // outcome unless the Worker supplies explicit evidence.
                        taskService.markLifecycleAttention(taskId, "PROCESS_UNVERIFIED");
                        log.info("Codex task awaits verified terminal outcome: taskId={}, errorCode={}, seq={}",
                                taskId, failure, event.getSeq());
                        publishResultUnknown(sessionId, providerType, taskId);
                        break;
                    }

                    closePendingUserInputBeforeTerminal(taskId, sessionId, providerType);
                    if ("ABORTED".equals(event.getTerminalStatus())) {
                        publishBuilt(mb.stateSync("CODEX_RUNTIME_REMOTE_ABORTED", "aborted"),
                                workerMessageId(taskId, event));
                        taskService.reconcileAbortedTask(taskId, event.getTaskId(),
                                detectedCodexThreadId.get());
                        lastAckedSeq.remove(taskId);
                        eventPublisher.publishEvent(TaskCompletionEvent.builder()
                                .externalTaskId(taskId)
                                .parentSessionId(sessionId)
                                .targetAgentId(providerType)
                                .resultSummary("CODEX_RUNTIME_REMOTE_ABORTED")
                                .status("ABORTED")
                                .build());
                        break;
                    }

                    publishBuilt(mb.error(error), workerMessageId(taskId, event));
                    if (event.getSeq() != null) {
                        taskService.failTask(taskId, event.getTaskId(), detectedCodexThreadId.get(),
                                failure, event.getSeq());
                    } else {
                        taskService.failTask(taskId, event.getTaskId(), detectedCodexThreadId.get(),
                                failure);
                    }
                    // See the result branch: terminal ACK is durable with the
                    // task transition, so cleanup follows a successful commit.
                    lastAckedSeq.remove(taskId);

                    eventPublisher.publishEvent(TaskCompletionEvent.builder()
                            .externalTaskId(taskId)
                            .parentSessionId(sessionId)
                            .targetAgentId(providerType)
                            .resultSummary(failure)
                            .status("FAILED")
                            .build());
                }
                default -> log.debug("Unhandled Codex event type: {}", type);
            }

            if (!isTerminalWorkerEvent(event)) {
                acknowledgeWorkerEvent(taskId, event, false);
            }

        } catch (Exception e) {
            log.warn("Failed to process Codex SSE event: taskId={}, code={}, type={}",
                    taskId, stableFailureCode(e, "CODEX_WORKER_EVENT_PROCESSING_FAILED"), exceptionType(e));
            throw new WorkerEventProcessingException(taskId, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void persistLifecycleDisposition(String taskId, String data)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Object> disposition = objectMapper.readValue(data, Map.class);
        if (!"NAVIGATOR_WORKER_LIFECYCLE_V1".equals(
                stringField(disposition, "schema"))
                || !taskId.equals(stringField(
                disposition, "navigator_task_id"))
                || !"ACCEPTED".equals(stringField(
                disposition, "acceptance_disposition"))) {
            throw new IllegalStateException(
                    "CODEX_LIFECYCLE_DISPOSITION_BINDING_MISMATCH");
        }
        String providerTaskId = stringField(disposition, "provider_task_id");
        String commandKind = stringField(disposition, "command_kind");
        String dispatchId = stringField(disposition, "dispatch_id");
        if (providerTaskId == null
                || !stableLifecycleDispatchId(commandKind, taskId)
                .equals(dispatchId)) {
            throw new IllegalStateException(
                    "CODEX_LIFECYCLE_DISPOSITION_BINDING_MISMATCH");
        }
        if ("ENFORCED".equals(stringField(
                disposition, "ownership_mode"))
                && !"EFFECT_STARTED".equals(stringField(
                disposition, "effect_phase"))) {
            throw new IllegalStateException(
                    "CODEX_LIFECYCLE_EFFECT_NOT_AUTHORIZED");
        }
        if ("ENFORCED".equals(stringField(
                disposition, "ownership_mode"))) {
            if (lifecycleProductionAdmission == null) {
                throw new IllegalStateException(
                        "LIFECYCLE_ACTIVATION_AUTHORITY_UNAVAILABLE");
            }
            CodexTaskEntity task = taskRepository.findByTaskId(taskId)
                    .orElseThrow(() -> new IllegalStateException(
                            "CODEX_TASK_NOT_FOUND"));
            Object versionValue = disposition.get("disposition_version");
            long dispositionVersion = versionValue instanceof Number number
                    ? number.longValue() : -1;
            lifecycleProductionAdmission.observeAcceptedDisposition(
                    new LifecycleProductionAdmissionService
                            .AcceptedDisposition(
                            taskId,
                            task.getSessionId(),
                            new WorkerLifecycleIdentity(
                                    stringField(disposition,
                                            "physical_worker_id"),
                                    stringField(disposition,
                                            "state_generation"),
                                    stringField(disposition,
                                            "instance_epoch")),
                            providerTaskId,
                            dispatchId,
                            stringField(disposition,
                                    "safe_binding_digest_version"),
                            stringField(disposition,
                                    "safe_binding_digest"),
                            dispositionVersion));
        }
        taskService.recordWorkerProgress(
                taskId, providerTaskId, null, null,
                null, false, false);
    }

    static String stableLifecycleDispatchId(
            String commandKind, String taskOrOperationId) {
        if (!List.of("TASK_CREATE", "TASK_RESUME", "TERMINATION_CANCEL")
                .contains(commandKind)
                || taskOrOperationId == null
                || taskOrOperationId.isBlank()) {
            throw new IllegalArgumentException(
                    "CODEX_LIFECYCLE_DISPATCH_INPUT_INVALID");
        }
        return UUID.nameUUIDFromBytes(
                ("codex-lifecycle:" + commandKind + ":" + taskOrOperationId)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void handleNativeSubtaskUpdate(WorkerEvent event, String taskId, String sessionId,
                                           String providerType, String codexThreadId) {
        Map<String, Object> source = event.getData();
        if (source == null) {
            throw new IllegalArgumentException("native_subtask_update requires data");
        }

        Map<String, Object> snapshotSource = source;
        Object nested = source.get("subtask");
        if (nested instanceof Map<?, ?> nestedMap) {
            Map<String, Object> nestedSnapshot = new LinkedHashMap<>();
            nestedMap.forEach((key, value) -> nestedSnapshot.put(String.valueOf(key), value));
            if (!nestedSnapshot.containsKey("contract_version") && source.containsKey("contract_version")) {
                nestedSnapshot.put("contract_version", source.get("contract_version"));
            }
            snapshotSource = nestedSnapshot;
        }

        NativeSubtaskUpdatePayload update = objectMapper.convertValue(snapshotSource, NativeSubtaskUpdatePayload.class);
        var applied = nativeSubtaskService.applyUpdate(
                taskId, sessionId, providerType, event.getSeq(), update);

        if (applied.isEmpty()) {
            log.debug("Ignoring stale native subtask update: taskId={}, subtaskId={}, seq={}",
                    taskId, update.getSubtaskId(), event.getSeq());
            acknowledgeWorkerEvent(taskId, event, false);
            return;
        }

        NativeSubtaskSnapshotDTO subtask = applied.get();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("lastEventSeq", event.getSeq());
        payload.put("subtask", nativeSubtaskPayload(subtask));
        if (codexThreadId != null) {
            payload.put("codexThreadId", codexThreadId);
        }

        AgentMessage message = AgentMessage.of(
                sessionId, providerType, MessageType.NATIVE_SUBTASK_UPDATE, payload);
        message.setTaskId(taskId);
        message.setMessageId("native-subtask:" + taskId + ":" + event.getSeq());
        publishEvent(message);
        acknowledgeWorkerEvent(taskId, event, false);
    }

    private void acknowledgeWorkerEvent(String taskId, WorkerEvent event, boolean userVisibleOutput) {
        taskService.recordWorkerProgress(taskId, event.getTaskId(), event.getSessionId(),
                event.getModel(), event.getSeq(), userVisibleOutput, isExecutionCommittedEvent(event));
        rememberAcknowledgedWorkerEvent(taskId, event);
    }

    private void rememberAcknowledgedWorkerEvent(String taskId, WorkerEvent event) {
        if (event.getSeq() != null) {
            lastAckedSeq.computeIfAbsent(taskId, k -> new AtomicInteger(0))
                    .updateAndGet(current -> Math.max(current, event.getSeq()));
        }
    }

    private boolean isTerminalWorkerEvent(WorkerEvent event) {
        return event != null && ("result".equals(event.getType()) || isVerifiedTerminalError(event));
    }

    private boolean isProcessUnverifiedAttention(WorkerEvent event) {
        return event != null
                && "lifecycle_attention".equals(event.getSubtype())
                && "PROCESS_UNVERIFIED".equals(event.getAttentionStatus());
    }

    /**
     * Error payloads are diagnostic by default.  A Worker must explicitly
     * attest that it observed a provider terminal event or verified process
     * exit before Java releases ownership or writes a terminal task state.
     */
    private boolean isVerifiedTerminalError(WorkerEvent event) {
        if (event == null || !"error".equals(event.getType())
                || !Boolean.TRUE.equals(event.getTerminalObserved())) {
            return false;
        }
        return "FAILED".equals(event.getTerminalStatus())
                || "ABORTED".equals(event.getTerminalStatus());
    }

    private boolean isNextWorkerEvent(WorkerEvent event, String taskId, AtomicInteger seqTracker) {
        Integer seq = event.getSeq();
        if (seq == null) {
            return true;
        }
        int current = seqTracker.get();
        if (seq <= current) {
            log.debug("Ignoring already acknowledged Codex event: taskId={}, seq={}, ackSeq={}",
                    taskId, seq, current);
            return false;
        }
        if (current == Integer.MAX_VALUE || seq != current + 1) {
            throw new IllegalStateException("CODEX_WORKER_EVENT_SEQUENCE_GAP: expected "
                    + (current + 1L) + " but received " + seq);
        }
        return true;
    }

    private boolean isExecutionCommittedEvent(WorkerEvent event) {
        if (event == null) return false;
        if ("execution_committed".equals(event.getSubtype())) return true;
        if ("sync_checkpoint".equals(event.getSubtype())) return false;
        return switch (event.getType() != null ? event.getType() : "") {
            case "assistant_text", "tool_use", "tool_result", "image_generation", "result", "error", "native_subtask_update",
                    "user_input_request", "user_input_resolved" -> true;
            default -> false;
        };
    }

    private Map<String, Object> nativeSubtaskPayload(NativeSubtaskSnapshotDTO snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subtaskId", snapshot.getSubtaskId());
        putIfNotNull(payload, "parentSubtaskId", snapshot.getParentSubtaskId());
        payload.put("depth", snapshot.getDepth());
        putIfNotNull(payload, "label", snapshot.getLabel());
        putIfNotNull(payload, "role", snapshot.getRole());
        payload.put("status", snapshot.getStatus());
        putIfNotNull(payload, "activity", snapshot.getActivity());
        putIfNotNull(payload, "message", snapshot.getMessage());
        putIfNotNull(payload, "startedAt", instantString(snapshot.getStartedAt()));
        putIfNotNull(payload, "updatedAt", instantString(snapshot.getUpdatedAt()));
        putIfNotNull(payload, "completedAt", instantString(snapshot.getCompletedAt()));
        putIfNotNull(payload, "durationMs", snapshot.getDurationMs());
        payload.put("lastEventSeq", snapshot.getLastEventSeq());
        return payload;
    }

    private String instantString(java.time.Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private void publishMessage(String sessionId, String providerType, MessageType type, Map<String, Object> payload) {
        AgentMessage message = AgentMessage.of(sessionId, providerType, type, payload);
        eventPublisher.publishEvent(message);
    }

    private String resolveTaskProviderType(String taskId) {
        try {
            return taskRepository.findByTaskId(taskId)
                    .map(CodexTaskEntity::getProviderType)
                    .map(this::providerType)
                    .orElse(AGENT_ID);
        } catch (Exception e) {
            log.debug("Failed to resolve Codex task providerType: taskId={}, type={}",
                    taskId, exceptionType(e));
        }
        return AGENT_ID;
    }

    private String providerType(String providerType) {
        if (providerType == null || providerType.isBlank() || AGENT_ID.equals(providerType)) {
            return AGENT_ID;
        }
        if (CODEX_APP_SERVER_AGENT_ID.equals(providerType)
                || CODEX_BIZ_AGENT_ID.equals(providerType)) {
            return providerType;
        }
        throw new IllegalArgumentException("Unsupported Codex providerType: " + providerType);
    }

    private void publishBuilt(AgentMessageBuilder builder, String messageId) {
        AgentMessage message = builder.build();
        if (messageId != null) {
            message.setMessageId(messageId);
        }
        publishDurableAgentMessage(message);
    }

    private void publishToolResult(AgentMessageBuilder builder, WorkerEvent event,
                                   boolean success, String messageId) {
        AgentMessage message = builder.toolCallResult(
                event.getToolUseId(), event.getTool(), event.getOutput(), success).build();
        if (messageId != null) {
            message.setMessageId(messageId);
        }
        publishDurableAgentMessage(message);
    }

    private void publishEvent(AgentMessage message) {
        publishDurableAgentMessage(message);
    }

    private void publishDurableAgentMessage(AgentMessage message) {
        sessionEventListener.handleMessageDurably(message);
        eventPublisher.publishEvent(message);
    }

    private void publishUserInputRequest(String sessionId, String providerType, String taskId,
                                         CodexTaskService.UserInputRegistration registration,
                                         String messageId) {
        AgentMessageBuilder builder = AgentMessageBuilder.create(sessionId, providerType)
                .taskId(taskId)
                .confirmationRequest(registration.requestId());
        registration.confirmationPayload().forEach(builder::put);
        publishBuilt(builder, messageId);
    }

    public void publishUserInputResponse(String sessionId, String providerType, String taskId,
                                         String requestId, String decision,
                                         Map<String, String> answers) {
        if (sessionId == null || sessionId.isBlank() || requestId == null || requestId.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("permissionId", requestId);
        payload.put("requestId", requestId);
        payload.put("decision", decision);
        payload.put("taskId", taskId);
        if (answers != null && !answers.isEmpty()) {
            payload.put("answers", new LinkedHashMap<>(answers));
        }
        AgentMessage message = AgentMessage.of(
                sessionId, providerType, MessageType.CONFIRMATION_RESPONSE, payload);
        message.setTaskId(taskId);
        message.setMessageId(userInputMessageId("cx-ui-res-", taskId, requestId));
        publishDurableAgentMessage(message);
    }

    static String userInputMessageId(String prefix, String taskId, String requestToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((taskId + "\u0000" + requestToken)
                    .getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(hash, 0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void closePendingUserInputBeforeTerminal(String taskId, String sessionId, String providerType) {
        CodexTaskService.UserInputResolution resolution =
                taskService.resolvePendingUserInputForTerminal(taskId);
        publishUserInputResolution(sessionId, providerType, taskId, resolution);
    }

    private void publishUserInputResolution(String sessionId, String providerType, String taskId,
                                            CodexTaskService.UserInputResolution resolution) {
        if (resolution != null && resolution.shouldPublish()) {
            publishUserInputResponse(sessionId, providerType, taskId,
                    resolution.requestId(), resolution.decision(), null);
        }
    }

    private boolean isUserVisibleOutputEvent(WorkerEvent event) {
        if (event == null || event.getType() == null) {
            return false;
        }
        return switch (event.getType()) {
            case "assistant_text" -> !"sync_checkpoint".equals(event.getSubtype());
            case "tool_use", "tool_result", "image_generation", "result", "warning", "error", "user_input_request" -> true;
            case "system", "progress" -> isVisibleStatusEvent(event);
            default -> false;
        };
    }

    private boolean isVisibleStatusEvent(WorkerEvent event) {
        String subtype = event.getSubtype();
        if (subtype == null || subtype.isBlank()) {
            return event.getContent() != null && !event.getContent().isBlank();
        }
        String normalized = subtype.toLowerCase(Locale.ROOT);
        if ("waiting".equals(normalized)
                || normalized.contains("heartbeat")
                || normalized.contains("keepalive")
                || "sync_checkpoint".equals(normalized)) {
            return false;
        }
        return event.getContent() != null && !event.getContent().isBlank();
    }

    private Map<String, Object> generatedImagePayload(WorkerEvent event, String taskId) {
        if (event.getData() == null) {
            throw new IllegalArgumentException("image_generation requires data");
        }
        Map<String, Object> result = new LinkedHashMap<>(event.getData());
        Object artifactValue = result.get("artifact_id");
        String artifactId = artifactValue != null ? artifactValue.toString() : "";
        if (!artifactId.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("image_generation artifact_id is invalid");
        }
        // The local path is useful only inside the Worker/Java bridge and must not leak to clients.
        result.remove("local_path");
        result.put("url", "/api/v1/tasks/" + taskId
                + "/generated-images/" + artifactId);
        return result;
    }

    private String truncateResult(String text) {
        if (text == null) return null;
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private boolean isRecoverableAcceptanceState(String state) {
        return "ACCEPTING".equals(state) || "ACCEPTED".equals(state)
                || "UNKNOWN".equals(state) || "ABORT_REQUESTED".equals(state);
    }

    private String workerMessageId(String taskId, WorkerEvent event) {
        if (event.getSeq() != null) {
            return "codex-event:" + taskId + ":" + event.getSeq();
        }

        // Older Workers do not supply an ESN. A tool-use id is nevertheless
        // stable for the lifetime of one tool execution, so preserve it as an
        // idempotency key for large payload externalization. Do not fabricate
        // a type-only key: independent tool results must remain distinct.
        if (!"tool_result".equals(event.getType())) {
            return null;
        }
        if (event.getToolUseId() != null && !event.getToolUseId().isBlank()) {
            return "cx-lt:" + taskId + ":" + event.getToolUseId();
        }
        // Some legacy tool adapters omit tool_use_id. Use the immutable tool
        // result content as the narrow fallback identity so a replay does not
        // create another descriptor/file, while different outputs remain
        // visible as separate messages.
        return userInputMessageId("cx-lt-", taskId,
                String.valueOf(event.getTool()) + "\u0000" + String.valueOf(event.getOutput())
                        + "\u0000" + String.valueOf(event.getIsError()));
    }

    private String stableWorkerEventError(String error) {
        if (error != null && error.matches("[A-Z][A-Z0-9_]{0,127}")) {
            return error;
        }
        return "CODEX_WORKER_REMOTE_ERROR";
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private ErrorEnvelope workerErrorEnvelope(WorkerEvent event, String taskId) {
        String code = stableWorkerEventError(firstNonBlank(event.getErrorCode(), event.getError()));
        String message = ErrorDiagnosticSanitizer.sanitize(event.getErrorMessage());
        if (message == null || message.isBlank()) {
            message = "Codex 执行失败，可查看诊断信息后重试。";
        }
        return ErrorEnvelope.builder()
                .errorCode(code)
                .message(message)
                .category(event.getErrorCategory() != null
                        ? event.getErrorCategory() : ErrorDiagnosticSanitizer.classify(code))
                .runtimePhase(event.getRuntimePhase() != null
                        ? event.getRuntimePhase() : ErrorRuntimePhase.TURN_EXECUTION)
                .recoverable(event.getRecoverable() != null ? event.getRecoverable() : Boolean.TRUE)
                .occurredAt(event.getOccurredAt() != null ? event.getOccurredAt() : Instant.now())
                .taskId(taskId)
                .build();
    }

    private ErrorDiagnosticInput workerDiagnosticInput(WorkerEvent event) {
        return ErrorDiagnosticInput.builder()
                .exceptionType(event.getExceptionType())
                .diagnosticText(event.getDiagnosticText())
                .providerStatus(event.getProviderStatus())
                .httpStatus(event.getHttpStatus())
                .retryCount(event.getRetryCount())
                .build();
    }

    private String stableFailureCode(Throwable error, String fallback) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CodexAppServerAcceptanceService.RejectedException rejected
                    && rejected.getWorkerErrorCode() != null) {
                return rejected.getWorkerErrorCode();
            }
            if (current instanceof WebClientResponseException response) {
                return fallback + "_HTTP_" + response.getStatusCode().value();
            }
            if (current instanceof WebClientRequestException) {
                return fallback + "_UNREACHABLE";
            }
            if (current instanceof TimeoutException
                    || current.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout")) {
                return fallback + "_TIMEOUT";
            }
            String message = current.getMessage();
            if (message != null && message.matches("[A-Z][A-Z0-9_]{0,127}")) {
                return message;
            }
            current = current.getCause();
        }
        return fallback;
    }

    private <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private String stableAcceptanceRejection(CodexAppServerAcceptanceService.RejectedException error) {
        return error.getWorkerErrorCode() != null ? error.getWorkerErrorCode() : error.getMessage();
    }

    private String exceptionType(Throwable error) {
        return error != null ? error.getClass().getSimpleName() : "UnknownException";
    }

    private static ReentrantLock[] createStreamOperationLocks(int count) {
        ReentrantLock[] locks = new ReentrantLock[count];
        for (int i = 0; i < count; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    private ReentrantLock streamOperationLock(String taskId) {
        int hash = taskId != null ? taskId.hashCode() : 0;
        return streamOperationLocks[Math.floorMod(hash, streamOperationLocks.length)];
    }

    @PreDestroy
    void shutdown() {
        shuttingDown.set(true);
        activeStreams.values().forEach(subscription -> {
            if (subscription != null && !subscription.isDisposed()) subscription.dispose();
        });
        scheduledRecoveries.values().forEach(scheduled -> {
            if (scheduled != null && !scheduled.isDisposed()) scheduled.dispose();
        });
        activeStreams.clear();
        scheduledRecoveries.clear();
        lastAckedSeq.clear();
        reconnecting.clear();
        recoveryNotified.clear();
    }

    private static final class WorkerEventProcessingException extends RuntimeException {
        private WorkerEventProcessingException(String taskId, Throwable cause) {
            super("CODEX_WORKER_EVENT_PROCESSING_FAILED: " + taskId, cause);
        }
    }

    private CodexRuntimeBinding resolveRuntimeBinding(CodexTaskEntity task) {
        String providerType = providerType(task.getProviderType());
        if (CODEX_APP_SERVER_AGENT_ID.equals(providerType)) {
            if (!CodexRuntimeType.APP_SERVER.name().equals(task.getRuntimeType())) {
                throw new IllegalStateException("CODEX_PROVIDER_RUNTIME_MISMATCH: app-server provider "
                        + "requires APP_SERVER affinity");
            }
            return runtimeRegistryService.resolveBoundRuntime(
                    task.getRuntimeId(), task.getRuntimeRevision(), task.getWorkerId(),
                    task.getRuntimeInstanceId());
        }
        if (!CodexRuntimeType.SDK_EXEC.name().equals(task.getRuntimeType())
                || task.getRuntimeId() == null
                || !task.getRuntimeId().startsWith("legacy-sdk:")) {
            throw new IllegalStateException("CODEX_PROVIDER_RUNTIME_MISMATCH: provider "
                    + providerType + " requires SDK_EXEC affinity");
        }
        return CodexRuntimeBinding.legacySdk(task.getWorkerId());
    }

    private CodexWorkerClient getCodexClient(CodexTaskEntity task, CodexRuntimeBinding runtime) {
        String providerType = providerType(task.getProviderType());
        if (CODEX_APP_SERVER_AGENT_ID.equals(providerType)) {
            if (runtime.getRuntimeType() != CodexRuntimeType.APP_SERVER) {
                throw new IllegalStateException("CODEX_PROVIDER_RUNTIME_MISMATCH");
            }
            return clientFactory.getOrCreate(
                    "runtime:" + runtime.getRuntimeId() + ":" + runtime.getRuntimeRevision(),
                    runtime.getEndpointUrl(), runtime.getAuthToken(), runtime.getInstanceId());
        }
        if (runtime.getRuntimeType() != CodexRuntimeType.SDK_EXEC) {
            throw new IllegalStateException("CODEX_PROVIDER_RUNTIME_MISMATCH");
        }
        CodexConfig config = workerManagementFacade.getCodexConfig(task.getWorkerId());
        if (config == null || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalStateException("Codex not configured for worker: " + task.getWorkerId());
        }
        return clientFactory.getOrCreate(task.getWorkerId() + ":codex",
                config.getBaseUrl(), config.getAuthToken());
    }

}
