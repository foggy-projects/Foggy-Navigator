package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.agent.framework.diagnostic.ErrorDiagnosticInput;
import com.foggy.navigator.agent.framework.diagnostic.ErrorEnvelope;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.command.CodexTaskCreateCommand;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.codex.worker.repository.CodexCodingAgentRepository;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.TerminationOperationEntity;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.NativeSubtaskStateRepository;
import com.foggy.navigator.common.termination.TerminationOperationCapability;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.common.util.TaskResponseTimeoutSupport;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.InternalTaskDispatchMarkers;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.TaskSearchResult;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.session.service.ErrorDiagnosticService;
import com.foggy.navigator.session.service.TerminationOperationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Codex 任务生命周期管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodexTaskService implements TaskLookupProvider, TaskCommandProvider, TaskListingProvider {

    public static final String CODEX_PROVIDER_TYPE = "codex-worker";
    public static final String CODEX_APP_SERVER_PROVIDER_TYPE = "codex-app-server-worker";
    public static final String CODEX_BIZ_PROVIDER_TYPE = "codex-biz-worker";
    private static final String AGENT_ID = CODEX_PROVIDER_TYPE;
    private static final String USER_INPUT_STATE_KEY = "codexPendingInteraction";
    private static final String USER_INPUT_METHOD = "item/tool/requestUserInput";
    private static final List<String> ACTIVE_RESUME_STATUSES =
            List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED");
    private static final Duration RESUME_RECONCILIATION_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_USER_INPUT_QUESTIONS = 3;
    private static final String STALE_TURN_INTERRUPT_KIND = "STALE_TURN_INTERRUPT";
    private static final String STALE_TURN_CLEANUP_ORIGIN = "UPSTREAM_USER";
    private static final String STALE_TURN_CLEANUP_ACTOR_TYPE = "TASK_OWNER_STALE_TURN_CLEANUP";
    private static final String STALE_TURN_CLEANUP_REASON = "STALE_TURN_CLEANUP";
    private static final Set<String> STALE_TURN_CLEANUP_SUCCESS_STATUSES =
            Set.of("cleaned", "already_terminal");
    private static final Set<String> CODEX_CATALOG_EFFORTS = Set.of(
            "low", "medium", "high", "xhigh", "max", "ultra");
    private static final Map<String, String> CODEX_LEGACY_MODEL_VALUES = Map.ofEntries(
            Map.entry("codex-latest", "codex-latest:medium"),
            Map.entry("codex-fast", "codex-latest:low"),
            Map.entry("codex-deep", "codex-latest:high"),
            Map.entry("codex-xhigh", "codex-latest:xhigh"),
            Map.entry("codex-max", "codex-latest:max"),
            Map.entry("codex-ultra", "codex-latest:ultra"),
            Map.entry("codex-terra", "codex-terra:medium"),
            Map.entry("codex-luna", "codex-luna:medium"));
    private static final Map<String, String> CODEX_REAL_MODEL_FAMILIES = Map.of(
            "gpt-5.6-sol", "codex-latest",
            "gpt-5.6-terra", "codex-terra",
            "gpt-5.6-luna", "codex-luna");
    private static final Set<TaskQueryCapability> CAPABILITIES = Set.of(
            TaskQueryCapability.CREATE_TASK_DIRECT,
            TaskQueryCapability.RESUME_TASK,
            TaskQueryCapability.RESPOND_TO_TASK,
            TaskQueryCapability.RECONNECT_TASK,
            TaskQueryCapability.CANCEL_TASK,
            TaskQueryCapability.DELETE_TASK,
            TaskQueryCapability.RESYNC_TASK,
            TaskQueryCapability.REWIND_TASK,
            TaskQueryCapability.LIST_TASKS_PAGED,
            TaskQueryCapability.SEARCH_SESSIONS,
            TaskQueryCapability.LIST_TASKS_BY_DIRECTORY_PAGED);

    private final CodexTaskRepository taskRepository;
    private final WorkerManagementFacade workerManagementFacade;
    private final ApplicationEventPublisher eventPublisher;
    private final CodexWorkerClientFactory clientFactory;
    private final CodexTaskRuntimeStateService taskRuntimeStateService;

    @Autowired(required = false)
    @Nullable
    private SessionManager sessionManager;

    @Autowired(required = false)
    @Nullable
    private LlmModelManager llmModelManager;

    @Autowired(required = false)
    @Nullable
    private SessionTaskRepository sessionTaskRepository;

    @Autowired(required = false)
    @Nullable
    private SessionEntityRepository sessionEntityRepository;

    @PersistenceContext
    @Nullable
    private EntityManager entityManager;

    @Autowired(required = false)
    @Nullable
    private NativeSubtaskStateRepository nativeSubtaskStateRepository;

    @Autowired(required = false)
    @Nullable
    private CodexCodingAgentRepository codingAgentRepository;

    @Autowired(required = false)
    @Nullable
    private ErrorDiagnosticService errorDiagnosticService;

    /** Optional only for legacy unit wiring; production must fail closed if absent. */
    @Autowired(required = false)
    @Nullable
    private TerminationOperationService terminationOperationService;

    /**
     * Used only to reserve a termination operation under the task-row lock.
     * HTTP dispatch deliberately happens after this transaction commits.
     */
    @Autowired(required = false)
    @Nullable
    private PlatformTransactionManager terminationTransactionManager;

    @Autowired
    @Lazy
    private CodexStreamRelay streamRelay;

    @Autowired(required = false)
    @Nullable
    private CodexRuntimeRegistryService runtimeRegistryService;

    /**
     * 创建并启动 Codex 任务
     */
    @Transactional
    public DispatchTaskDTO createTask(String userId, String tenantId, CodexTaskCreateCommand form) {
        // 如果 form 携带 sessionId（由 ContextResolvingA2aAgent 传入），则复用已有会话
        String existingSessionId = form.getSessionId();
        if (existingSessionId != null && existingSessionId.isBlank()) {
            existingSessionId = null;
        }
        return createAndStartTask(userId, tenantId, form, existingSessionId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED,
            noRollbackFor = CodexStaleTaskRepairedException.class)
    public DispatchTaskDTO resumeTask(String userId, String tenantId, java.util.Map<String, Object> params) {
        return resumeTaskForProvider(CODEX_PROVIDER_TYPE, userId, tenantId, params);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED,
            noRollbackFor = CodexStaleTaskRepairedException.class)
    public DispatchTaskDTO resumeTaskForProvider(String expectedProviderType,
                                                  String userId,
                                                  String tenantId,
                                                  java.util.Map<String, Object> params) {
        String effectiveProviderType = requireProviderParams(expectedProviderType, params);
        CodexTaskCreateCommand form = new CodexTaskCreateCommand();
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setContextId((String) params.get("contextId"));
        form.setImages((String) params.get("images"));
        form.setAttachments(attachmentsParam(params.get("attachments")));
        form.setProviderType(effectiveProviderType);
        if (isCodexBizProvider(form.getProviderType())) {
            applyCodexBizParams(form, params);
        }
        if (params.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }

        // codexThreadId 从 SessionEntity.providerStateJson 恢复，不再从 request 透传
        String sessionId = (String) params.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("resume 操作必须指定 sessionId");
        }
        if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
            throw new IllegalArgumentException("resume 操作必须指定 workerId");
        }

        workerManagementFacade.validateWorkerAccess(userId, tenantId, form.getWorkerId());
        ResumeSessionState observedSession = observeResumeState(userId, sessionId);
        form.setCodexThreadId(observedSession.codexThreadId());
        ResumeTaskFence taskFence = lockResumeTaskFence(
                sessionId, observedSession.codexThreadId(), form.getWorkerId(), userId,
                effectiveProviderType);
        String activeTaskSessionId = taskFence.activeTask()
                .map(CodexTaskEntity::getSessionId)
                .map(this::stringValue)
                .orElse(null);
        ResumeSessionState lockedSession = lockResumeSessions(
                userId, sessionId, activeTaskSessionId);
        if (!Objects.equals(observedSession.codexThreadId(), lockedSession.codexThreadId())
                || !Objects.equals(observedSession.latestTaskId(), lockedSession.latestTaskId())) {
            throw resumeStateChanged();
        }
        form.setCodexThreadId(lockedSession.codexThreadId());

        if (taskFence.activeTask().isPresent()) {
            if (repairStaleResumeTaskIfVerifiedAbsent(taskFence.activeTask().get())) {
                throw new CodexStaleTaskRepairedException();
            }
            throw new IllegalStateException("该会话正在运行任务，请等待完成或终止后再继续");
        }

        if (form.getCodexThreadId() == null || form.getCodexThreadId().isBlank()) {
            // Platform-only rewind clears the native Codex thread. Continue by starting
            // a new Codex thread while reusing the Navigator session.
            DispatchTaskDTO task = createAndStartTask(userId, tenantId, form, sessionId);
            return getTaskByIdForProvider(task.getTaskId(), form.getProviderType()).orElseThrow();
        }

        if (!taskFence.hasHistoricalTask()) {
            throw new IllegalArgumentException("Codex 会话不存在或不属于该 Worker: " + form.getCodexThreadId());
        }

        DispatchTaskDTO task = createAndStartTask(userId, tenantId, form, sessionId);
        return getTaskByIdForProvider(task.getTaskId(), form.getProviderType()).orElseThrow();
    }

    private ResumeTaskFence lockResumeTaskFence(String sessionId,
                                                String codexThreadId,
                                                String workerId,
                                                String userId,
                                                String providerType) {
        String activeTaskId = firstResumeTaskId(taskRepository.findActiveResumeTaskIds(
                sessionId, codexThreadId, workerId, userId, providerType,
                ACTIVE_RESUME_STATUSES, PageRequest.of(0, 1)));
        if (activeTaskId != null) {
            return new ResumeTaskFence(Optional.of(lockActiveResumeTask(
                    activeTaskId, sessionId, codexThreadId, workerId, userId, providerType)), true);
        }

        String observedAnchorTaskId = observeResumeAnchorTaskId(
                sessionId, codexThreadId, workerId, userId, providerType);
        if (observedAnchorTaskId == null) {
            return new ResumeTaskFence(Optional.empty(), false);
        }
        CodexTaskEntity lockedAnchor = taskRepository.findByTaskIdForUpdate(observedAnchorTaskId)
                .orElseThrow(this::resumeStateChanged);
        if (!matchesResumeAnchor(
                lockedAnchor, sessionId, codexThreadId, workerId, userId, providerType)) {
            throw resumeStateChanged();
        }

        String currentAnchorTaskId = observeResumeAnchorTaskId(
                sessionId, codexThreadId, workerId, userId, providerType);
        if (!Objects.equals(observedAnchorTaskId, currentAnchorTaskId)) {
            throw resumeStateChanged();
        }
        String currentActiveTaskId = firstResumeTaskId(taskRepository.findActiveResumeTaskIds(
                sessionId, codexThreadId, workerId, userId, providerType,
                ACTIVE_RESUME_STATUSES, PageRequest.of(0, 1)));
        if (currentActiveTaskId == null) {
            return new ResumeTaskFence(Optional.empty(), true);
        }
        if (!Objects.equals(currentActiveTaskId, observedAnchorTaskId)
                || !ACTIVE_RESUME_STATUSES.contains(lockedAnchor.getStatus())
                || !matchesActiveResumeScope(
                        lockedAnchor, sessionId, codexThreadId, workerId, userId, providerType)) {
            throw resumeStateChanged();
        }
        return new ResumeTaskFence(Optional.of(lockedAnchor), true);
    }

    private CodexTaskEntity lockActiveResumeTask(String taskId,
                                                  String sessionId,
                                                  String codexThreadId,
                                                  String workerId,
                                                  String userId,
                                                  String providerType) {
        CodexTaskEntity lockedTask = taskRepository.findByTaskIdForUpdate(taskId)
                .orElseThrow(this::resumeStateChanged);
        throwIfStaleRepairAlreadyCommitted(lockedTask);
        if (!ACTIVE_RESUME_STATUSES.contains(lockedTask.getStatus())
                || !matchesActiveResumeScope(
                        lockedTask, sessionId, codexThreadId, workerId, userId, providerType)) {
            throw resumeStateChanged();
        }
        return lockedTask;
    }

    private void throwIfStaleRepairAlreadyCommitted(CodexTaskEntity task) {
        if ("FAILED".equals(task.getStatus())
                && CodexStaleTaskRepairedException.CODE.equals(task.getErrorMessage())) {
            throw new CodexStaleTaskRepairedException();
        }
    }

    private boolean matchesActiveResumeScope(CodexTaskEntity task,
                                             String sessionId,
                                             String codexThreadId,
                                             String workerId,
                                             String userId,
                                             String providerType) {
        boolean sameSession = Objects.equals(sessionId, task.getSessionId())
                && Objects.equals(userId, task.getUserId());
        boolean sameThread = codexThreadId != null
                && Objects.equals(codexThreadId, task.getCodexThreadId())
                && matchesResumeOwner(task, workerId, userId, providerType);
        return sameSession || sameThread;
    }

    private boolean matchesResumeAnchor(CodexTaskEntity task,
                                        String sessionId,
                                        String codexThreadId,
                                        String workerId,
                                        String userId,
                                        String providerType) {
        if (codexThreadId == null) {
            return Objects.equals(sessionId, task.getSessionId())
                    && Objects.equals(userId, task.getUserId());
        }
        return Objects.equals(codexThreadId, task.getCodexThreadId())
                && matchesResumeOwner(task, workerId, userId, providerType);
    }

    private boolean matchesResumeOwner(CodexTaskEntity task,
                                       String workerId,
                                       String userId,
                                       String providerType) {
        return Objects.equals(workerId, task.getWorkerId())
                && Objects.equals(userId, task.getUserId())
                && Objects.equals(providerType, resolveProviderType(task));
    }

    private String observeResumeAnchorTaskId(String sessionId,
                                             String codexThreadId,
                                             String workerId,
                                             String userId,
                                             String providerType) {
        List<String> taskIds = codexThreadId != null
                ? taskRepository.findLatestResumeThreadTaskIds(
                        codexThreadId, workerId, userId, providerType, PageRequest.of(0, 1))
                : taskRepository.findLatestResumeSessionTaskIds(
                        sessionId, userId, PageRequest.of(0, 1));
        return firstResumeTaskId(taskIds);
    }

    private String firstResumeTaskId(List<String> taskIds) {
        return taskIds == null || taskIds.isEmpty() ? null : stringValue(taskIds.get(0));
    }

    private ResumeSessionState observeResumeState(String userId, String sessionId) {
        if (sessionEntityRepository == null) {
            validateExistingSession(userId, sessionId);
            return new ResumeSessionState(null, null);
        }
        SessionEntityRepository.ResumeStateView state = sessionEntityRepository
                .findResumeStateByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session not found or access denied: " + sessionId));
        return resumeSessionState(state);
    }

    private ResumeSessionState lockResumeState(String userId, String sessionId) {
        if (sessionEntityRepository == null) {
            validateExistingSession(userId, sessionId);
            return new ResumeSessionState(null, null);
        }
        SessionEntityRepository.ResumeStateView state = sessionEntityRepository
                .findResumeStateByIdAndUserIdForUpdate(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session not found or access denied: " + sessionId));
        refreshManagedResumeSession(sessionId);
        return resumeSessionState(state);
    }

    private ResumeSessionState lockResumeSessions(String userId,
                                                   String requestedSessionId,
                                                   String activeTaskSessionId) {
        if (activeTaskSessionId == null || activeTaskSessionId.equals(requestedSessionId)) {
            return lockResumeState(userId, requestedSessionId);
        }
        // A same-thread active task may belong to another Navigator Session. Repairing it
        // updates that owner projection, so lock both rows in a stable order after the Task row.
        if (requestedSessionId.compareTo(activeTaskSessionId) < 0) {
            ResumeSessionState requestedState = lockResumeState(userId, requestedSessionId);
            lockResumeState(userId, activeTaskSessionId);
            return requestedState;
        }
        lockResumeState(userId, activeTaskSessionId);
        return lockResumeState(userId, requestedSessionId);
    }

    private void refreshManagedResumeSession(String sessionId) {
        if (entityManager == null) {
            return;
        }
        SessionEntity managedSession = entityManager.find(SessionEntity.class, sessionId);
        if (managedSession != null) {
            entityManager.refresh(managedSession);
        }
    }

    private ResumeSessionState resumeSessionState(SessionEntityRepository.ResumeStateView state) {
        return new ResumeSessionState(
                ProviderStateCodec.readStringOrNull(
                        state.getProviderStateJson(), ProviderStateCodec.FIELD_CODEX_THREAD_ID),
                stringValue(state.getLatestTaskId()));
    }

    private IllegalStateException resumeStateChanged() {
        return new IllegalStateException(
                "CODEX_RESUME_STATE_CHANGED: 会话或任务运行状态在恢复期间发生变化，请重新尝试");
    }

    private record ResumeTaskFence(Optional<CodexTaskEntity> activeTask, boolean hasHistoricalTask) {}

    private record ResumeSessionState(String codexThreadId, String latestTaskId) {}

    private boolean repairStaleResumeTaskIfVerifiedAbsent(CodexTaskEntity activeTask) {
        if (!supportsSdkResumeReconciliation(activeTask)
                || sessionTaskRepository == null || sessionEntityRepository == null) {
            return false;
        }
        String workerTaskId = stringValue(activeTask.getWorkerTaskId());
        if (workerTaskId == null) {
            return false;
        }

        CodexWorkerClient client;
        try {
            client = terminationClient(activeTask);
        } catch (RuntimeException error) {
            log.warn(
                    "Keeping Codex resume guard because the bound Worker client is unavailable: taskId={}, workerId={}, errorType={}",
                    activeTask.getTaskId(), activeTask.getWorkerId(), error.getClass().getSimpleName());
            return false;
        }
        try {
            client.getTaskStatus(workerTaskId).block(RESUME_RECONCILIATION_TIMEOUT);
            return false;
        } catch (RuntimeException error) {
            WebClientResponseException responseError = findWorkerResponseError(error);
            if (responseError == null || responseError.getStatusCode().value() != 404) {
                log.warn(
                        "Keeping Codex resume guard after inconclusive Worker status probe: taskId={}, workerId={}, errorType={}, httpStatus={}",
                        activeTask.getTaskId(), activeTask.getWorkerId(), error.getClass().getSimpleName(),
                        responseError != null ? responseError.getStatusCode().value() : null);
                return false;
            }
        }

        Map<String, Object> processSnapshot;
        try {
            processSnapshot = client.listCliProcesses().block(RESUME_RECONCILIATION_TIMEOUT);
        } catch (RuntimeException error) {
            WebClientResponseException responseError = findWorkerResponseError(error);
            log.warn(
                    "Keeping Codex resume guard after inconclusive Worker process probe: taskId={}, workerId={}, errorType={}, httpStatus={}",
                    activeTask.getTaskId(), activeTask.getWorkerId(), error.getClass().getSimpleName(),
                    responseError != null ? responseError.getStatusCode().value() : null);
            return false;
        }
        if (!processSnapshotConfirmsTaskAbsent(
                processSnapshot, workerTaskId, stringValue(activeTask.getCodexThreadId()))) {
            log.warn(
                    "Keeping Codex resume guard because Worker process snapshot did not prove absence: taskId={}, workerId={}",
                    activeTask.getTaskId(), activeTask.getWorkerId());
            return false;
        }

        resolvePendingInteractionForStaleTask(activeTask);
        String previousStatus = activeTask.getStatus();
        activeTask.setStatus("FAILED");
        activeTask.setErrorMessage(CodexStaleTaskRepairedException.CODE);
        persistRepairedResumeTask(activeTask);
        publishStatusChange(activeTask, previousStatus);
        log.warn(
                "Repaired stale Codex resume guard after Worker task and CLI absence were verified: taskId={}, workerId={}, previousStatus={}",
                activeTask.getTaskId(), activeTask.getWorkerId(), previousStatus);
        return true;
    }

    private void persistRepairedResumeTask(CodexTaskEntity task) {
        CodexTaskEntity saved = taskRepository.save(task);
        syncSessionTask(saved);
        syncRepairedResumeSessionProjection(saved);
    }

    private void syncRepairedResumeSessionProjection(CodexTaskEntity task) {
        if (sessionEntityRepository == null
                || task.getSessionId() == null || task.getSessionId().isBlank()) {
            return;
        }
        SessionEntity session = sessionEntityRepository.findById(task.getSessionId()).orElse(null);
        if (session == null || !Objects.equals(stringValue(session.getLatestTaskId()), task.getTaskId())) {
            return;
        }
        // The repair may race with rewind or runtime migration. Only the terminal UI state
        // belongs to this transition; provider, worker, thread and latest-task affinity stay intact.
        session.setInteractionState(deriveInteractionState(task.getStatus()));
        session.setLastActivityAt(LocalDateTime.now());
        sessionEntityRepository.save(session);
    }

    private boolean supportsSdkResumeReconciliation(CodexTaskEntity task) {
        String providerType = resolveProviderType(task);
        return CodexRuntimeType.SDK_EXEC.name().equals(task.getRuntimeType())
                && (CODEX_PROVIDER_TYPE.equals(providerType) || CODEX_BIZ_PROVIDER_TYPE.equals(providerType));
    }

    private boolean processSnapshotConfirmsTaskAbsent(Map<String, Object> snapshot,
                                                      String workerTaskId,
                                                      String codexThreadId) {
        if (snapshot == null || workerTaskId == null) {
            return false;
        }
        Object processValue = snapshot.get("processes");
        if (!(processValue instanceof List<?> processes)) {
            return false;
        }
        Integer total = exactNonNegativeInteger(snapshot.get("total"));
        Integer activeTaskCount = exactNonNegativeInteger(snapshot.get("active_task_count"));
        if (total == null || total != processes.size() || activeTaskCount == null) {
            return false;
        }

        Set<Integer> observedPids = new LinkedHashSet<>();
        for (Object processValueItem : processes) {
            if (!(processValueItem instanceof Map<?, ?> process)) {
                return false;
            }
            Integer pid = exactPositiveInteger(process.get("pid"));
            if (pid == null || !observedPids.add(pid)
                    || !"codex".equals(process.get("command"))
                    || !"codex".equals(process.get("process_type"))
                    || !isFiniteNonNegativeNumber(process.get("memory_mb"))
                    || !(process.get("is_orphan") instanceof Boolean orphan)) {
                return false;
            }
            String observedTaskId = processIdentifier(process, "foggy_task_id");
            String observedThreadId = processIdentifier(process, "codex_thread_id");
            if (observedTaskId == null && process.containsKey("foggy_task_id")) {
                return false;
            }
            if (observedThreadId == null && process.containsKey("codex_thread_id")) {
                return false;
            }
            if (workerTaskId.equals(observedTaskId)
                    || (codexThreadId != null && codexThreadId.equals(observedThreadId))) {
                return false;
            }
            if (orphan) {
                // An orphan without a recoverable thread identity may still be
                // the old CLI after a Worker restart, so absence is ambiguous.
                if (observedTaskId != null || observedThreadId == null) {
                    return false;
                }
            } else if (observedTaskId == null) {
                return false;
            }
        }
        return true;
    }

    private String processIdentifier(Map<?, ?> process, String field) {
        Object value = process.get(field);
        if (!(value instanceof String identifier)
                || identifier.isBlank()
                || identifier.length() > 256
                || !identifier.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")) {
            return null;
        }
        return identifier;
    }

    private Integer exactPositiveInteger(Object value) {
        Integer number = exactNonNegativeInteger(value);
        return number != null && number > 0 ? number : null;
    }

    private Integer exactNonNegativeInteger(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double decimal = number.doubleValue();
        long integer = number.longValue();
        if (!Double.isFinite(decimal) || decimal != integer
                || integer < 0 || integer > Integer.MAX_VALUE) {
            return null;
        }
        return (int) integer;
    }

    private boolean isFiniteNonNegativeNumber(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        double decimal = number.doubleValue();
        return Double.isFinite(decimal) && decimal >= 0;
    }

    private WebClientResponseException findWorkerResponseError(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof WebClientResponseException responseError) {
                return responseError;
            }
        }
        return null;
    }

    private void resolvePendingInteractionForStaleTask(CodexTaskEntity task) {
        SessionTaskEntity sessionTask = sessionTaskRepository.findByTaskIdForUpdate(task.getTaskId()).orElse(null);
        if (sessionTask == null) {
            return;
        }
        Map<String, Object> pending = pendingUserInput(sessionTask);
        if (!pending.isEmpty() && !"RESOLVED".equals(stringValue(pending.get("state")))) {
            markPendingResolved(sessionTask, task, pending, "stale_task_repaired");
        }
    }

    private DispatchTaskDTO createAndStartTask(String userId, String tenantId,
                                               CodexTaskCreateCommand form, String existingSessionId) {
        if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        if (form.getPrompt() == null || form.getPrompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        String effectiveProviderType = normalizeProviderType(form.getProviderType());
        form.setProviderType(effectiveProviderType);
        if (existingSessionId != null && !existingSessionId.isBlank()) {
            validateExistingSession(userId, existingSessionId);
            validateExistingSessionProvider(existingSessionId, effectiveProviderType);
            if (form.isInitializeRuntimeAffinity()) {
                validatePreallocatedSessionForRuntimeInitialization(
                        existingSessionId, userId, tenantId, effectiveProviderType);
            }
        }
        normalizeAndValidateCodexBizHomeKey(form, effectiveProviderType);

        // 验证 Worker 存在且当前 user/tenant 可访问（通过 WorkerManagementFacade SPI）
        workerManagementFacade.validateWorkerAccess(userId, tenantId, form.getWorkerId());

        String cwd = form.getCwd();
        if ((cwd == null || cwd.isBlank())
                && form.getDirectoryId() != null
                && !form.getDirectoryId().isBlank()) {
            cwd = workerManagementFacade.getDirectoryPath(userId, form.getDirectoryId());
        }
        // Codex CLI (Rust) 不接受 Windows 反斜杠路径，需转为正斜杠
        if (cwd != null) {
            cwd = cwd.replace('\\', '/');
        }

        String effectiveAgentId = resolveLogicalAgentId(form.getAgentId(), existingSessionId);

        String effectiveModelConfigId = resolveEffectiveModelConfigId(form.getModelConfigId(), effectiveAgentId);
        LlmModelConfigDTO effectiveModelConfig = validateAndResolveModelConfig(
                effectiveModelConfigId, form.getWorkerId());
        validateModelConfigProvider(effectiveModelConfigId, effectiveModelConfig, effectiveProviderType);
        ModelResolution effectiveModelResolution = resolveEffectiveModel(
                form.getModel(), effectiveAgentId, effectiveModelConfig);
        validateProviderModel(effectiveProviderType, effectiveModelResolution.model());
        validateEffectiveModelGrant(
                effectiveModelResolution.model(), effectiveModelConfigId, effectiveModelConfig);
        String modelConfigSource = resolveModelConfigSource(form.getModelConfigId(), effectiveAgentId);

        String taskId = IdGenerator.shortId();

        String sessionId = resolveSessionId(userId, tenantId, form.getPrompt(),
                existingSessionId, effectiveAgentId, effectiveProviderType);

        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setDirectoryId(form.getDirectoryId());
        entity.setWorkerId(form.getWorkerId());
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setResolvedAgentId(effectiveAgentId);
        entity.setContextId(form.getContextId());
        entity.setProviderType(effectiveProviderType);
        if (isCodexBizProvider(effectiveProviderType)) {
            entity.setCodexHomeKey(form.getCodexHomeKey());
            entity.setPrivateAccountId(firstNonBlank(form.getPrivateAccountId(), form.getCodexHomeKey()));
        }
        entity.setPrompt(form.getPrompt());
        entity.setCwd(cwd);
        entity.setModel(effectiveModelResolution.model());
        entity.setStatus("RUNNING");
        entity.setSource("PLATFORM");
        entity.setCodexThreadId(form.getCodexThreadId());
        applyRuntimeBinding(entity, resolveRuntimeBinding(
                form.getWorkerId(), entity.getModel(), effectiveProviderType, taskId, existingSessionId,
                runtimeRequirements(form, effectiveProviderType), form.isInitializeRuntimeAffinity()));

        persistTask(entity);
        log.info("Created Codex task: taskId={}, providerType={}, workerId={}, sessionId={}",
                taskId, effectiveProviderType, form.getWorkerId(), sessionId);

        // 解析 auth + envVars（apiKey + baseUrl + 环境变量，无配置时 Worker 使用本地凭证）
        CodexAuthResult auth = resolveCodexAuth(effectiveModelConfigId);
        log.info(
                "Resolved Codex task auth: taskId={}, agentId={}, modelConfigId={}, modelConfigSource={}, model={}, modelSource={}, hasApiKey={}, hasBaseUrl={}, envVarKeys={}",
                taskId,
                effectiveAgentId,
                effectiveModelConfigId,
                modelConfigSource,
                entity.getModel(),
                effectiveModelResolution.source(),
                auth.apiKey != null && !auth.apiKey.isBlank(),
                auth.baseUrl != null && !auth.baseUrl.isBlank(),
                auth.envVars != null ? auth.envVars.keySet() : List.of()
        );

        // 发布统一事件触发 CodexStreamRelay（通过 providerType 条件过滤）
        Map<String, Object> providerConfig = new LinkedHashMap<>();
        putIfNotBlank(providerConfig, "codexThreadId", form.getCodexThreadId());
        putIfNotBlank(providerConfig, "images", form.getImages());
        if (form.getAttachments() != null && !form.getAttachments().isEmpty()) {
            providerConfig.put("attachments", form.getAttachments());
        }
        putIfNotBlank(providerConfig, "baseUrl", auth.baseUrl);
        if (auth.envVars != null && !auth.envVars.isEmpty()) {
            providerConfig.put("extraEnvVars", auth.envVars);
        }
        if (isCodexBizProvider(effectiveProviderType)) {
            putIfNotBlank(providerConfig, "codexHomeKey", form.getCodexHomeKey());
            putIfNotBlank(providerConfig, "developerInstructions", form.getDeveloperInstructions());
            if (form.getBusinessRuntimeContext() != null && !form.getBusinessRuntimeContext().isEmpty()) {
                providerConfig.put("businessRuntimeContext", form.getBusinessRuntimeContext());
            }
            putIfNotBlank(providerConfig, "sandboxMode", form.getSandboxMode());
            putIfNotBlank(providerConfig, "approvalPolicy", form.getApprovalPolicy());
            putIfNotBlank(providerConfig, "webSearchMode", form.getWebSearchMode());
            if (form.getOutputSchema() != null && !form.getOutputSchema().isEmpty()) {
                providerConfig.put("outputSchema", form.getOutputSchema());
            }
            if (form.getCodexConfig() != null && !form.getCodexConfig().isEmpty()) {
                providerConfig.put("codexConfig", form.getCodexConfig());
            }
            if (form.getNetworkAccessEnabled() != null) {
                providerConfig.put("networkAccessEnabled", form.getNetworkAccessEnabled());
            }
            if (form.getAdditionalDirectories() != null && !form.getAdditionalDirectories().isEmpty()) {
                providerConfig.put("additionalDirectories", form.getAdditionalDirectories());
            }
        }

        eventPublisher.publishEvent(WorkerTaskStartEvent.builder()
                .taskId(taskId).sessionId(sessionId).workerId(form.getWorkerId())
                .prompt(form.getPrompt()).cwd(cwd)
                .model(entity.getModel()).maxTurns(form.getMaxTurns())
                .apiKey(auth.apiKey).providerType(effectiveProviderType)
                .providerConfig(providerConfig)
                .build());

        return toDispatchDTO(entity);
    }

    /**
     * 创建受追踪的同步任务记录（由 SPI syncQueryTracked 调用）
     */
    @Transactional
    public String createTrackedSyncTask(String userId, String workerId, String sessionId,
                                          String prompt, String cwd, String directoryId,
                                          String codexThreadId) {
        return createTrackedSyncTask(userId, workerId, sessionId, prompt, cwd,
                directoryId, codexThreadId, null);
    }

    @Transactional
    public String createTrackedSyncTask(String userId, String workerId, String sessionId,
                                         String prompt, String cwd, String directoryId,
                                         String codexThreadId, String model) {
        validateProviderModel(CODEX_PROVIDER_TYPE, model);
        if (sessionId != null && !sessionId.isBlank()) {
            validateExistingSession(userId, sessionId);
            validateExistingSessionProvider(sessionId, CODEX_PROVIDER_TYPE);
        }
        String taskId = IdGenerator.shortId();
        // Codex CLI (Rust) 不接受 Windows 反斜杠路径
        String normalizedCwd = cwd != null ? cwd.replace('\\', '/') : null;

        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setDirectoryId(directoryId);
        entity.setWorkerId(workerId);
        entity.setUserId(userId);
        entity.setProviderType(CODEX_PROVIDER_TYPE);
        entity.setPrompt(prompt);
        entity.setCwd(normalizedCwd);
        entity.setStatus("RUNNING");
        entity.setSource("PLATFORM");
        entity.setCodexThreadId(codexThreadId);
        entity.setModel(model);
        CodexRuntimeBinding binding;
        if (sessionId != null && !sessionId.isBlank()) {
            binding = resolveRuntimeBinding(
                    workerId, model, CODEX_PROVIDER_TYPE, taskId, sessionId);
        } else if (codexThreadId != null && !codexThreadId.isBlank()) {
            binding = resolveThreadRuntimeBinding(
                    codexThreadId, workerId, userId, CODEX_PROVIDER_TYPE);
        } else {
            binding = resolveRuntimeBinding(workerId, model, CODEX_PROVIDER_TYPE, taskId, null);
        }
        applyRuntimeBinding(entity, binding);

        persistTask(entity);
        log.info("Created tracked sync Codex task: taskId={}, sessionId={}", taskId, sessionId);
        return taskId;
    }

    /**
     * 记录 Worker 侧任务元数据与流消费进度。
     */
    @Transactional
    public void recordWorkerProgress(String taskId, String workerTaskId, String codexThreadId,
                                      String model, Integer ackSeq) {
        recordWorkerProgress(taskId, workerTaskId, codexThreadId, model, ackSeq, false, false);
    }

    @Transactional
    public void recordWorkerProgress(String taskId, String workerTaskId, String codexThreadId,
                                      String model, Integer ackSeq, boolean userVisibleOutput) {
        recordWorkerProgress(taskId, workerTaskId, codexThreadId, model, ackSeq,
                userVisibleOutput, userVisibleOutput);
    }

    @Transactional
    public void recordWorkerProgress(String taskId, String workerTaskId, String codexThreadId,
                                      String model, Integer ackSeq, boolean userVisibleOutput,
                                      boolean executionCommitted) {
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null) {
            log.warn("recordWorkerProgress: task not found: {}", taskId);
            return;
        }

        if (workerTaskId != null && !workerTaskId.isBlank()) {
            if (entity.getWorkerTaskId() != null && !entity.getWorkerTaskId().isBlank()
                    && !entity.getWorkerTaskId().equals(workerTaskId)) {
                throw new IllegalStateException(
                        "CODEX_RUNTIME_IDEMPOTENCY_CONFLICT: worker task id changed for " + taskId);
            }
            entity.setWorkerTaskId(workerTaskId);
        }
        if (codexThreadId != null && !codexThreadId.isBlank()) {
            entity.setCodexThreadId(codexThreadId);
        }
        applyWorkerReportedModel(entity, model);
        if (ackSeq != null) {
            Integer current = entity.getLastAckedSeq();
            entity.setLastAckedSeq(current == null ? ackSeq : Math.max(current, ackSeq));
        }
        if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())
                && executionCommitted
                && !isTerminalStatus(entity.getStatus())
                && !"TERMINAL".equals(entity.getRuntimeAcceptanceState())) {
            entity.setRuntimeAcceptanceState("COMMITTED");
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setLastAliveAt(now);
        if (userVisibleOutput) {
            entity.setLastOutputAt(now);
        }
        persistTask(entity);
    }

    /**
     * 获取任务详情
     */
    public DispatchTaskDTO getTask(String userId, String taskId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return toDispatchDTO(entity);
    }

    public DispatchTaskDTO getTaskForProvider(String userId, String taskId, String providerType) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireTaskProvider(entity, providerType);
        return toDispatchDTO(entity);
    }

    /**
     * 获取任务 Entity（内部使用）
     */
    public CodexTaskEntity getTaskEntity(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    /**
     * 列出用户的所有任务
     */
    public List<DispatchTaskDTO> listTasks(String userId) {
        return toDispatchDTOs(taskRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    public List<DispatchTaskDTO> listTasksForProvider(String userId, String providerType) {
        return toDispatchDTOs(filterTasksByProvider(
                taskRepository.findByUserIdOrderByCreatedAtDesc(userId), providerType));
    }

    /**
     * 列出 Worker 下的任务
     */
    public List<DispatchTaskDTO> listTasksByWorker(String userId, String workerId) {
        return toDispatchDTOs(taskRepository.findByWorkerIdAndUserId(workerId, userId));
    }

    public List<DispatchTaskDTO> listTasksByWorkerForProvider(
            String userId, String workerId, String providerType) {
        return toDispatchDTOs(filterTasksByProvider(
                taskRepository.findByWorkerIdAndUserId(workerId, userId), providerType));
    }

    @Override
    public void cancelTaskDirect(String taskId, String userId) {
        cancelTaskDirectForProvider(CODEX_PROVIDER_TYPE, taskId, userId);
    }

    public void cancelTaskDirectForProvider(String providerType, String taskId, String userId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireTaskProvider(entity, providerType);
        if ("RUNNING".equals(entity.getStatus()) || "AWAITING_PERMISSION".equals(entity.getStatus())
                || "AWAITING_INPUT".equals(entity.getStatus())) {
            abortTask(taskId);
        }
    }

    @Override
    public void reconnectTask(String taskId, String userId) {
        reconnectTaskForProvider(CODEX_PROVIDER_TYPE, taskId, userId);
    }

    public void reconnectTaskForProvider(String providerType, String taskId, String userId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireTaskProvider(entity, providerType);
        if (!"RUNNING".equals(entity.getStatus()) && !"AWAITING_INPUT".equals(entity.getStatus())
                && !"CANCEL_REQUESTED".equals(entity.getStatus())) {
            return;
        }
        streamRelay.reconnectTask(taskId, entity.getSessionId(), entity.getWorkerId());
    }

    /**
     * 检查指定 Codex 会话是否有正在运行的任务（并发保护）
     */
    public boolean hasRunningTask(String codexThreadId, String workerId, String userId) {
        return taskRepository.existsByCodexThreadIdAndWorkerIdAndUserIdAndStatusIn(
                codexThreadId, workerId, userId, List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"));
    }

    public boolean hasRunningTaskForProvider(String codexThreadId, String workerId,
                                             String userId, String providerType) {
        return taskRepository.existsByCodexThreadIdAndWorkerIdAndUserIdAndProviderTypeAndStatusIn(
                codexThreadId, workerId, userId, normalizeProviderType(providerType),
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"));
    }

    /** Registers the sanitized app-server requestUserInput projection before it is acknowledged. */
    @Transactional
    public UserInputRegistration registerPendingUserInput(String taskId, Map<String, Object> projection) {
        requireUserInputPersistence();
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            throw interactionError("CODEX_USER_INPUT_RUNTIME_UNSUPPORTED");
        }
        // A late input card is not terminal evidence and must never reopen a
        // cancellation that has already been durably accepted for dispatch.
        if (isTerminalStatus(entity.getStatus()) || "CANCEL_REQUESTED".equals(entity.getStatus())) {
            return UserInputRegistration.ignored();
        }

        Map<String, Object> pending = sanitizePendingUserInput(entity, projection);
        String requestId = externalRequestToken(taskId, pending.get("request_id"));
        SessionTaskEntity sessionTask = requireSessionTask(entity);
        Map<String, Object> existing = pendingUserInput(sessionTask);
        if (!existing.isEmpty()) {
            String existingState = stringValue(existing.get("state"));
            if (sameWireRequestId(pending.get("request_id"), existing.get("request_id"))) {
                if ("RESOLVED".equals(existingState)) {
                    return UserInputRegistration.ignored();
                }
                if (!"PENDING".equals(existingState)) {
                    throw interactionError("CODEX_USER_INPUT_STATE_INVALID");
                }
                if (!samePendingUserInput(existing, pending)) {
                    throw interactionError("CODEX_USER_INPUT_REPLAY_MISMATCH");
                }
            } else if ("PENDING".equals(existingState)) {
                throw interactionError("CODEX_USER_INPUT_OVERLAP");
            }
        }

        pending.put("state", "PENDING");
        savePendingUserInput(sessionTask, entity, pending);
        String previousStatus = entity.getStatus();
        String previousRuntimeAcceptanceState = entity.getRuntimeAcceptanceState();
        if (entity.getCodexThreadId() == null || entity.getCodexThreadId().isBlank()) {
            entity.setCodexThreadId(stringValue(pending.get("thread_id")));
        }
        entity.setStatus("AWAITING_INPUT");
        entity.setErrorMessage(null);
        entity.setLastAliveAt(LocalDateTime.now());
        persistTask(entity);
        if (!"AWAITING_INPUT".equals(previousStatus)) {
            publishStatusChange(entity, previousStatus);
        }
        return new UserInputRegistration(true, requestId, confirmationPayload(taskId, pending));
    }

    /**
     * Sends one response to the exact bound runtime. The task row lock serializes concurrent replies;
     * answers are never written to task state.
     */
    @Override
    @Transactional
    public void respondToTask(String taskId, String userId, Map<String, Object> response) {
        respondToTaskForProvider(CODEX_PROVIDER_TYPE, taskId, userId, response);
    }

    @Transactional
    public void respondToTaskForProvider(String providerType, String taskId, String userId,
                                         Map<String, Object> response) {
        requireUserInputPersistence();
        if (response == null) {
            throw new IllegalArgumentException("response is required");
        }
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserIdForUpdate(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireTaskProvider(entity, providerType);
        if (!"AWAITING_INPUT".equals(entity.getStatus())) {
            throw interactionError("CODEX_USER_INPUT_NOT_PENDING");
        }
        if (!CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            throw interactionError("CODEX_USER_INPUT_RUNTIME_UNSUPPORTED");
        }

        SessionTaskEntity sessionTask = requireSessionTask(entity);
        Map<String, Object> pending = pendingUserInput(sessionTask);
        if (!"PENDING".equals(stringValue(pending.get("state")))) {
            throw interactionError("CODEX_USER_INPUT_NOT_PENDING");
        }
        String requestId = externalRequestToken(taskId, pending.get("request_id"));
        Object suppliedRequestId = firstNonNull(
                response.get("requestId"), response.get("request_id"), response.get("permissionId"));
        if (!(suppliedRequestId instanceof String suppliedToken) || !requestId.equals(suppliedToken)) {
            throw interactionError("CODEX_USER_INPUT_REQUEST_MISMATCH");
        }

        NormalizedUserInputAnswers normalized = normalizeUserInputAnswers(pending, response.get("answers"));
        CodexRuntimeBinding binding = bindingFromTask(entity, entity.getWorkerId());
        if (binding == null || binding.getRuntimeType() != CodexRuntimeType.APP_SERVER
                || binding.getInstanceId() == null || binding.getInstanceId().isBlank()) {
            throw interactionError("CODEX_USER_INPUT_RUNTIME_AFFINITY_LOST");
        }
        String workerTaskId = stringValue(entity.getWorkerTaskId());
        if (workerTaskId == null) {
            throw interactionError("CODEX_USER_INPUT_REMOTE_TASK_MISSING");
        }

        CodexWorkerClient client = clientFactory.getOrCreate(
                "runtime:" + binding.getRuntimeId() + ":" + binding.getRuntimeRevision(),
                binding.getEndpointUrl(), binding.getAuthToken(), binding.getInstanceId());
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("request_id", pending.get("request_id"));
        requestBody.put("answers", normalized.workerAnswers());

        Map<String, Object> remoteResult = null;
        boolean alreadyResponded = false;
        try {
            remoteResult = client.respondToTask(workerTaskId, requestBody).block(Duration.ofSeconds(15));
        } catch (RuntimeException error) {
            CodexWorkerClient.UserInputResponseException responseError = findUserInputResponseError(error);
            if (responseError != null && "USER_INPUT_ALREADY_RESPONDED".equals(responseError.getCode())) {
                alreadyResponded = true;
            } else if (responseError != null) {
                throw interactionError(responseError.getCode(), error);
            } else if (findRuntimeProofError(error) != null) {
                throw interactionError("CODEX_USER_INPUT_RUNTIME_AFFINITY_LOST", error);
            } else {
                throw interactionError("CODEX_USER_INPUT_RESPONSE_UNKNOWN", error);
            }
        }
        if (!alreadyResponded) {
            validateUserInputResponse(remoteResult, workerTaskId, pending.get("request_id"));
        }

        markPendingResolved(sessionTask, entity, pending, "answered");
        String previousStatus = entity.getStatus();
        entity.setStatus("RUNNING");
        entity.setErrorMessage(null);
        entity.setLastAliveAt(LocalDateTime.now());
        persistTask(entity);
        publishStatusChange(entity, previousStatus);
        streamRelay.publishUserInputResponse(
                entity.getSessionId(), resolveProviderType(entity), entity.getTaskId(), requestId,
                "allow", normalized.containsSecret() ? null : normalized.historyAnswers());
    }

    /** Applies Worker-side clearing/auto-resolution without reopening a completed request. */
    @Transactional
    public UserInputResolution resolvePendingUserInput(String taskId, Map<String, Object> resolution) {
        requireUserInputPersistence();
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null) {
            return UserInputResolution.ignored();
        }
        SessionTaskEntity sessionTask = sessionTaskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (sessionTask == null) {
            return UserInputResolution.ignored();
        }
        Map<String, Object> pending = pendingUserInput(sessionTask);
        if (pending.isEmpty() || "RESOLVED".equals(stringValue(pending.get("state")))) {
            return UserInputResolution.ignored();
        }
        String requestId = externalRequestToken(taskId, pending.get("request_id"));
        Object resolvedRequestId = resolution != null
                ? firstNonNull(resolution.get("request_id"), resolution.get("requestId")) : null;
        if (resolvedRequestId != null
                && !sameWireRequestId(pending.get("request_id"), resolvedRequestId)) {
            log.warn("Ignoring stale Codex user input resolution: taskId={}, requestId={}", taskId, requestId);
            return UserInputResolution.ignored();
        }
        String reason = resolution != null ? stringValue(resolution.get("reason")) : null;
        if (reason == null || !Set.of("answered", "cleared", "auto_resolved").contains(reason)) {
            reason = "cleared";
        }
        markPendingResolved(sessionTask, entity, pending, reason);
        String previousStatus = entity.getStatus();
        if ("AWAITING_INPUT".equals(previousStatus)) {
            entity.setStatus("RUNNING");
            entity.setErrorMessage(null);
            entity.setLastAliveAt(LocalDateTime.now());
            persistTask(entity);
            publishStatusChange(entity, previousStatus);
        }
        return new UserInputResolution(true, requestId, reason);
    }

    /** Closes an orphaned card before a terminal Worker event is projected. */
    @Transactional
    public UserInputResolution resolvePendingUserInputForTerminal(String taskId) {
        requireUserInputPersistence();
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        SessionTaskEntity sessionTask = sessionTaskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null || sessionTask == null) {
            return UserInputResolution.ignored();
        }
        Map<String, Object> pending = pendingUserInput(sessionTask);
        if (!"PENDING".equals(stringValue(pending.get("state")))) {
            return UserInputResolution.ignored();
        }
        String requestId = externalRequestToken(taskId, pending.get("request_id"));
        markPendingResolved(sessionTask, entity, pending, "cleared");
        return new UserInputResolution(true, requestId, "cleared");
    }

    public record UserInputRegistration(boolean shouldPublish, String requestId,
                                        Map<String, Object> confirmationPayload) {
        private static UserInputRegistration ignored() {
            return new UserInputRegistration(false, null, Map.of());
        }
    }

    public record UserInputResolution(boolean shouldPublish, String requestId, String reason) {
        private static UserInputResolution ignored() {
            return new UserInputResolution(false, null, null);
        }

        public String decision() {
            return "answered".equals(reason) ? "allow" : "deny";
        }
    }

    private record NormalizedUserInputAnswers(Map<String, List<String>> workerAnswers,
                                              Map<String, String> historyAnswers,
                                              boolean containsSecret) {
    }

    /**
     * Requests an explicitly authorized remote cancellation.  This method
     * never locally aborts a process, disposes the stream, releases ownership,
     * or writes ABORTED: only Worker terminal evidence can do that.
     */
    public void abortTask(String taskId) {
        dispatchAuthorizedAbort(reserveAuthorizedAbort(taskId, null));
    }

    /**
     * Compatibility entry for A2A's already-resolved Worker id.  It follows
     * the same operation/capability gate as {@link #abortTask(String)}.
     *
     * @param taskId       平台侧 taskId
     * @param remoteTaskId 已解析的远端 Worker 任务标识（可能为 null，由装饰层通过 resolveRemoteTaskId 提供）
     */
    public void doAbortWorkerTask(String taskId, String remoteTaskId) {
        dispatchAuthorizedAbort(reserveAuthorizedAbort(taskId, remoteTaskId));
    }

    /**
     * Reserves exactly one operation while the task row is locked. The
     * reservation is committed before any Worker HTTP call, so a duplicate
     * cancel cannot mint a second capability and no database lock spans the
     * provider round trip.
     */
    private RemoteTerminationReservation reserveAuthorizedAbort(String taskId, String resolvedRemoteTaskId) {
        return inTerminationTransaction(() -> {
            CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            if (isTerminalStatus(entity.getStatus())) {
                log.info("Ignoring cancellation for terminal Codex task: taskId={}, status={}",
                        taskId, entity.getStatus());
                return null;
            }

            String persistedRemoteTaskId = entity.getWorkerTaskId();
            if (resolvedRemoteTaskId != null && !resolvedRemoteTaskId.isBlank()
                    && !resolvedRemoteTaskId.equals(persistedRemoteTaskId)) {
                throw new IllegalArgumentException("TERMINATION_REMOTE_TASK_MISMATCH");
            }
            if (persistedRemoteTaskId == null || persistedRemoteTaskId.isBlank()) {
                markCancellationAttention(entity, "REMOTE_TASK_ID_UNAVAILABLE");
                return null;
            }
            if (terminationOperationService == null) {
                markCancellationAttention(entity, "TERMINATION_AUDIT_UNAVAILABLE");
                return null;
            }
            if (terminationOperationService.hasActiveOperationForTask(entity.getTaskId())) {
                markCancellationAttention(entity, "TERMINATION_OPERATION_PENDING");
                log.info("Retaining existing Codex termination operation: taskId={}", entity.getTaskId());
                return null;
            }

            String correlationId = "remote-cancel:" + UUID.randomUUID();
            TerminationOperationEntity operation = terminationOperationService.accept(
                    new TerminationOperationService.CreateCommand(
                            entity.getTaskId(), persistedRemoteTaskId, entity.getSessionId(), entity.getUserId(),
                            entity.getTenantId(), resolveProviderType(entity), entity.getWorkerId(),
                            "REMOTE_CANCEL", "UPSTREAM_USER", entity.getUserId(), "USER",
                            "user-request:" + UUID.randomUUID(), "USER_CANCEL", correlationId,
                            null, null, 300));

            String previousStatus = entity.getStatus();
            String previousRuntimeAcceptanceState = entity.getRuntimeAcceptanceState();
            entity.setStatus("CANCEL_REQUESTED");
            entity.setErrorMessage(null);
            if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
                entity.setRuntimeAcceptanceState("ABORT_REQUESTED");
            }
            persistTask(entity);
            if (!"CANCEL_REQUESTED".equals(previousStatus)) {
                publishStatusChange(entity, previousStatus);
            }
            return new RemoteTerminationReservation(entity.getTaskId(), persistedRemoteTaskId,
                    previousStatus, previousRuntimeAcceptanceState, operation);
        });
    }

    private void dispatchAuthorizedAbort(RemoteTerminationReservation reservation) {
        if (reservation == null || terminationOperationService == null) return;
        CodexTaskEntity current = taskRepository.findByTaskId(reservation.taskId()).orElse(null);
        if (current == null || isTerminalStatus(current.getStatus())) return;
        try {
            terminationOperationService.markDispatchStarted(reservation.operation().getOperationId());
            CodexWorkerClient client = terminationClient(current);
            TerminationOperationCapability capability = TerminationOperationCapability.issue(
                    reservation.operation(), client.terminationSigningSecret());
            Map<String, Object> acknowledgement = client.abortTask(reservation.providerTaskId(), capability)
                    .block(Duration.ofSeconds(10));
            if (!isCancellationAcknowledged(acknowledgement)) {
                throw new TerminationDispatchUnconfirmedException("TERMINATION_ACK_INVALID");
            }
            terminationOperationService.markCancelRequested(reservation.operation().getOperationId());
            log.info("Codex cancellation request accepted by Worker: taskId={}, operationId={}",
                    reservation.taskId(), reservation.operation().getOperationId());
        } catch (Exception error) {
            if (isDefinitiveTerminationRejection(error)) {
                rejectOperationAndRestoreTask(reservation.taskId(), reservation.operation().getOperationId(),
                        reservation.previousStatus(), reservation.previousRuntimeAcceptanceState(),
                        terminationRejectionCode(error));
                log.warn("Codex cancellation rejected by Worker: taskId={}, operationId={}, type={}",
                        reservation.taskId(), reservation.operation().getOperationId(), error.getClass().getSimpleName());
            } else {
                terminationOperationService.markUnconfirmed(reservation.operation().getOperationId(),
                        terminationFailureCode(error));
                markCancellationAttention(reservation.taskId(), "TERMINATION_UNCONFIRMED");
                log.warn("Codex cancellation dispatch is unconfirmed: taskId={}, operationId={}, type={}",
                        reservation.taskId(), reservation.operation().getOperationId(), error.getClass().getSimpleName());
            }
        }
    }

    private record RemoteTerminationReservation(String taskId, String providerTaskId, String previousStatus,
                                                String previousRuntimeAcceptanceState,
                                                TerminationOperationEntity operation) {
    }

    /**
     * Safe browser projection for the explicitly requested stale native-turn
     * cleanup. It deliberately contains no thread, turn, runtime, Worker, or
     * capability identity.
     */
    public record StaleTurnCleanupEligibility(String taskId, boolean eligible, String reasonCode) {
    }

    /** Safe browser projection after the Worker observed the exact turn cleanup. */
    public record StaleTurnCleanupResult(String taskId, String operationId, String status) {
    }

    /**
     * Evaluates only persisted platform-side prerequisites. The Worker remains
     * authoritative for the exact native turn and is never contacted by this
     * read endpoint.
     */
    public StaleTurnCleanupEligibility getStaleTurnCleanupEligibility(
            String taskId, String ownerUserId, String tenantId) {
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        String ineligibleReason = staleTurnCleanupIneligibility(entity, ownerUserId, tenantId);
        if (ineligibleReason != null) {
            return new StaleTurnCleanupEligibility(taskId, false, ineligibleReason);
        }
        if (terminationOperationService == null) {
            return new StaleTurnCleanupEligibility(
                    taskId, false, "STALE_TURN_CLEANUP_AUDIT_UNAVAILABLE");
        }
        if (terminationOperationService.hasActiveOperationForTask(taskId)) {
            return new StaleTurnCleanupEligibility(
                    taskId, false, "STALE_TURN_CLEANUP_OPERATION_PENDING");
        }
        return new StaleTurnCleanupEligibility(taskId, true, null);
    }

    /**
     * Starts one signed, exact-turn cleanup attempt. The logical Navigator
     * task is already terminal and is intentionally never reopened, mutated,
     * or treated as proof that the native App Server turn is released.
     */
    public StaleTurnCleanupResult cleanupStaleTurn(
            String taskId, String ownerUserId, String tenantId) {
        StaleTurnCleanupReservation reservation = reserveStaleTurnCleanup(
                taskId, ownerUserId, tenantId);
        try {
            terminationOperationService.markDispatchStarted(reservation.operation().getOperationId());
            CodexTaskEntity current = taskRepository.findByTaskId(reservation.taskId()).orElse(null);
            if (!matchesStaleTurnCleanupReservation(current, reservation)) {
                terminationOperationService.markRejected(
                        reservation.operation().getOperationId(),
                        "STALE_TURN_CLEANUP_AFFINITY_CHANGED");
                throw new StaleTurnCleanupException("STALE_TURN_CLEANUP_AFFINITY_CHANGED");
            }

            CodexWorkerClient client = terminationClient(current);
            TerminationOperationCapability capability = TerminationOperationCapability.issue(
                    reservation.operation(), client.terminationSigningSecret());
            Map<String, Object> workerResult = client.staleTurnCleanup(
                            reservation.providerTaskId(), capability)
                    .block(Duration.ofSeconds(35));
            String status = requireStaleTurnCleanupReceipt(workerResult, reservation);
            terminationOperationService.markObservedTerminal(
                    reservation.operation().getOperationId(), "COMPLETED");
            log.info("Codex stale native turn cleanup observed: taskId={}, operationId={}, status={}",
                    reservation.taskId(), reservation.operation().getOperationId(), status);
            return new StaleTurnCleanupResult(
                    reservation.taskId(), reservation.operation().getOperationId(), status);
        } catch (StaleTurnCleanupException error) {
            throw error;
        } catch (Exception error) {
            String operationId = reservation.operation().getOperationId();
            if (isDefinitiveStaleTurnCleanupRejection(error)) {
                String safeCode = staleTurnCleanupRejectionCode(error);
                terminationOperationService.markRejected(operationId, safeCode);
                log.warn("Codex stale native turn cleanup rejected: taskId={}, operationId={}, type={}",
                        reservation.taskId(), operationId, error.getClass().getSimpleName());
                throw new StaleTurnCleanupException(safeCode);
            }
            // Do not leave a terminal task permanently blocked by an active
            // audit operation. A retry reserves a new operation and must again
            // re-read the exact native turn before interrupting it.
            terminationOperationService.markFailedUnconfirmed(
                    operationId, "STALE_TURN_CLEANUP_UNCONFIRMED");
            log.warn("Codex stale native turn cleanup is unconfirmed: taskId={}, operationId={}, type={}",
                    reservation.taskId(), operationId, error.getClass().getSimpleName());
            throw new StaleTurnCleanupException("STALE_TURN_CLEANUP_UNCONFIRMED", true);
        }
    }

    /**
     * Persists the operation under the task-row lock, then releases that lock
     * before any Worker request. This is intentionally independent from task
     * lifecycle mutations: cleanup is an audit operation over an already
     * terminal task, not a second cancellation.
     */
    private StaleTurnCleanupReservation reserveStaleTurnCleanup(
            String taskId, String ownerUserId, String tenantId) {
        return inTerminationTransaction(() -> {
            CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
            String ineligibleReason = staleTurnCleanupIneligibility(entity, ownerUserId, tenantId);
            if (ineligibleReason != null) {
                throw new StaleTurnCleanupException(ineligibleReason);
            }
            if (terminationOperationService == null) {
                throw new StaleTurnCleanupException("STALE_TURN_CLEANUP_AUDIT_UNAVAILABLE", true);
            }
            if (terminationOperationService.hasActiveOperationForTask(entity.getTaskId())) {
                throw new StaleTurnCleanupException("STALE_TURN_CLEANUP_OPERATION_PENDING");
            }

            TerminationOperationEntity operation = terminationOperationService.accept(
                    new TerminationOperationService.CreateCommand(
                            entity.getTaskId(), entity.getWorkerTaskId(), entity.getSessionId(),
                            entity.getUserId(), entity.getTenantId(), entity.getProviderType(),
                            entity.getWorkerId(), STALE_TURN_INTERRUPT_KIND,
                            STALE_TURN_CLEANUP_ORIGIN, entity.getUserId(),
                            STALE_TURN_CLEANUP_ACTOR_TYPE,
                            issueStaleTurnCleanupAuthorizationDecisionId(),
                            STALE_TURN_CLEANUP_REASON,
                            "stale-turn-cleanup:" + UUID.randomUUID(),
                            null, null, 300));
            return new StaleTurnCleanupReservation(
                    entity.getTaskId(), entity.getWorkerTaskId(), entity.getCodexThreadId(), entity.getWorkerId(),
                    entity.getRuntimeId(), entity.getRuntimeRevision(), entity.getRuntimeInstanceId(),
                    entity.getUserId(), entity.getTenantId(), operation);
        });
    }

    private String staleTurnCleanupIneligibility(
            CodexTaskEntity entity, String ownerUserId, String tenantId) {
        if (!matchesStaleTurnCleanupOwnerScope(entity, ownerUserId, tenantId)) {
            return "STALE_TURN_CLEANUP_UNAVAILABLE";
        }
        if (!CODEX_APP_SERVER_PROVIDER_TYPE.equals(entity.getProviderType())) {
            return "STALE_TURN_CLEANUP_PROVIDER_UNSUPPORTED";
        }
        if (!CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            return "STALE_TURN_CLEANUP_RUNTIME_UNSUPPORTED";
        }
        if (!isTerminalStatus(entity.getStatus())) {
            return "STALE_TURN_CLEANUP_TASK_NOT_TERMINAL";
        }
        if (!hasNonBlank(entity.getSessionId()) || !hasNonBlank(entity.getWorkerTaskId())
                || !hasNonBlank(entity.getCodexThreadId()) || !hasNonBlank(entity.getWorkerId())
                || !hasNonBlank(entity.getRuntimeId()) || entity.getRuntimeRevision() == null
                || !hasNonBlank(entity.getRuntimeInstanceId())) {
            return "STALE_TURN_CLEANUP_BINDING_INCOMPLETE";
        }
        return null;
    }

    private boolean matchesStaleTurnCleanupReservation(
            CodexTaskEntity entity, StaleTurnCleanupReservation reservation) {
        return staleTurnCleanupIneligibility(
                entity, reservation.ownerUserId(), reservation.tenantId()) == null
                && Objects.equals(entity.getTaskId(), reservation.taskId())
                && Objects.equals(entity.getWorkerTaskId(), reservation.providerTaskId())
                && Objects.equals(entity.getCodexThreadId(), reservation.codexThreadId())
                && Objects.equals(entity.getWorkerId(), reservation.workerId())
                && Objects.equals(entity.getRuntimeId(), reservation.runtimeId())
                && Objects.equals(entity.getRuntimeRevision(), reservation.runtimeRevision())
                && Objects.equals(entity.getRuntimeInstanceId(), reservation.runtimeInstanceId());
    }

    private String requireStaleTurnCleanupReceipt(
            Map<String, Object> workerResult, StaleTurnCleanupReservation reservation) {
        if (workerResult == null
                || !hasExpectedString(workerResult.get("task_id"), reservation.providerTaskId())
                || !hasExpectedString(workerResult.get("operation_id"),
                reservation.operation().getOperationId())) {
            throw new StaleTurnCleanupReceiptUnconfirmedException();
        }
        Object status = workerResult.get("status");
        if (!(status instanceof String value) || !STALE_TURN_CLEANUP_SUCCESS_STATUSES.contains(value)) {
            throw new StaleTurnCleanupReceiptUnconfirmedException();
        }
        return value;
    }

    private boolean isDefinitiveStaleTurnCleanupRejection(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof CodexWorkerClient.WorkerQueryRejectedException rejected) {
                return rejected.getStatusCode() == 409;
            }
            if (current instanceof WebClientResponseException response) {
                return response.getStatusCode().value() == 409;
            }
        }
        return false;
    }

    private String staleTurnCleanupRejectionCode(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof CodexWorkerClient.WorkerQueryRejectedException rejected
                    && rejected.getStatusCode() == 409
                    && isSafeStaleTurnCleanupCode(rejected.getCode())) {
                return rejected.getCode();
            }
        }
        return "STALE_TURN_CLEANUP_REJECTED";
    }

    private static boolean hasExpectedString(Object value, String expected) {
        return value instanceof String actual && Objects.equals(actual, expected);
    }

    private static boolean hasNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Mirrors the narrow tenantless ownership rule used by
     * {@code SessionTaskResourceAccessService}: a tenantless principal may
     * access only its own tenantless record. It is not an administrator
     * bypass and must not collapse null/blank tenant scope into cross-tenant
     * access.
     */
    private static boolean matchesStaleTurnCleanupOwnerScope(
            CodexTaskEntity entity, String ownerUserId, String tenantId) {
        if (entity == null || !hasNonBlank(ownerUserId)
                || !Objects.equals(ownerUserId, entity.getUserId())) {
            return false;
        }
        return hasNonBlank(tenantId)
                ? Objects.equals(tenantId, entity.getTenantId())
                : !hasNonBlank(entity.getTenantId());
    }

    private static boolean isSafeStaleTurnCleanupCode(String code) {
        return code != null && code.matches("STALE_TURN_CLEANUP_[A-Z0-9_]{1,120}");
    }

    private static String issueStaleTurnCleanupAuthorizationDecisionId() {
        return "authz-v1:" + STALE_TURN_CLEANUP_ACTOR_TYPE.toLowerCase(Locale.ROOT)
                + ":" + UUID.randomUUID();
    }

    private record StaleTurnCleanupReservation(
            String taskId,
            String providerTaskId,
            String codexThreadId,
            String workerId,
            String runtimeId,
            Integer runtimeRevision,
            String runtimeInstanceId,
            String ownerUserId,
            String tenantId,
            TerminationOperationEntity operation) {
    }

    private static final class StaleTurnCleanupReceiptUnconfirmedException
            extends IllegalStateException {
        private StaleTurnCleanupReceiptUnconfirmedException() {
            super("STALE_TURN_CLEANUP_RECEIPT_INVALID");
        }
    }

    /**
     * Safe API-level outcome for stale-turn cleanup. This intentionally holds
     * a fixed code only; it never carries a native thread/turn, runtime, or
     * Worker error payload into the browser response.
     */
    public static final class StaleTurnCleanupException extends IllegalStateException {
        private final boolean retryable;

        public StaleTurnCleanupException(String safeCode) {
            this(safeCode, false);
        }

        public StaleTurnCleanupException(String safeCode, boolean retryable) {
            super(safeCode);
            this.retryable = retryable;
        }

        public String getSafeCode() {
            return getMessage();
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    /**
     * Accepts a human-authorized PID operation before the Worker is contacted.
     * The returned capability is bound to the Worker task id and exact PID;
     * callers must report the Worker response through
     * {@link #recordManualPidKillResult(ManualPidKillRequest, Map)} rather
     * than inferring a terminal state from a successful HTTP dispatch.
     */
    public ManualPidKillRequest prepareManualPidKill(String taskId, String expectedWorkerId,
                                                      String actorId, String actorType,
                                                      String authorizedTenantId, boolean crossUserAuthorized, int pid,
                                                      String expectedProcessIdentity, String workerToken) {
        if (pid < 1) {
            throw new IllegalArgumentException("TERMINATION_MANUAL_PID_REQUIRED");
        }
        if (expectedProcessIdentity == null || expectedProcessIdentity.isBlank()) {
            throw new IllegalArgumentException("TERMINATION_PROCESS_IDENTITY_REQUIRED");
        }
        ManualPidKillReservation reservation = reserveManualPidKill(taskId, expectedWorkerId, actorId,
                actorType, authorizedTenantId, crossUserAuthorized, pid, expectedProcessIdentity);

        try {
            terminationOperationService.markDispatchStarted(reservation.operation().getOperationId());
            return new ManualPidKillRequest(reservation.operation().getOperationId(), reservation.taskId(),
                    reservation.previousStatus(), reservation.previousRuntimeAcceptanceState(),
                    TerminationOperationCapability.issue(reservation.operation(), workerToken));
        } catch (Exception error) {
            terminationOperationService.markUnconfirmed(reservation.operation().getOperationId(),
                    terminationFailureCode(error));
            markCancellationAttention(reservation.taskId(), "TERMINATION_UNCONFIRMED");
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("TERMINATION_CAPABILITY_ISSUE_FAILED", error);
        }
    }

    /**
     * Commits the task marker and audited operation before any nested
     * operation-status transaction is started.  In particular,
     * {@code markDispatchStarted(REQUIRES_NEW)} must be able to read the
     * operation row rather than racing an uncommitted outer transaction.
     */
    private ManualPidKillReservation reserveManualPidKill(String taskId, String expectedWorkerId,
                                                          String actorId, String actorType,
                                                          String authorizedTenantId, boolean crossUserAuthorized,
                                                          int pid, String expectedProcessIdentity) {
        return inTerminationTransaction(() -> {
            CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            requireManualPidAuthorization(entity, expectedWorkerId, actorId, actorType,
                    authorizedTenantId, crossUserAuthorized);
            if (isTerminalStatus(entity.getStatus())) {
                throw new IllegalStateException("TERMINATION_TASK_ALREADY_TERMINAL");
            }
            if (terminationOperationService == null) {
                markCancellationAttention(entity, "TERMINATION_AUDIT_UNAVAILABLE");
                throw new IllegalStateException("TERMINATION_AUDIT_UNAVAILABLE");
            }
            if (terminationOperationService.hasActiveOperationForTask(entity.getTaskId())) {
                markCancellationAttention(entity, "TERMINATION_OPERATION_PENDING");
                throw new IllegalStateException("TERMINATION_OPERATION_PENDING");
            }
            String workerTaskId = entity.getWorkerTaskId();
            if (workerTaskId == null || workerTaskId.isBlank()) {
                markCancellationAttention(entity, "REMOTE_TASK_ID_UNAVAILABLE");
                throw new IllegalStateException("REMOTE_TASK_ID_UNAVAILABLE");
            }
            requireManualProcessIdentity(entity, pid, expectedProcessIdentity);

            String authorizationDecisionId = issueManualAuthorizationDecisionId(actorType);
            String correlationId = "manual-pid-kill:" + UUID.randomUUID();
            TerminationOperationEntity operation = terminationOperationService.accept(
                    new TerminationOperationService.CreateCommand(
                            entity.getTaskId(), workerTaskId, entity.getSessionId(), entity.getUserId(),
                            entity.getTenantId(), resolveProviderType(entity), entity.getWorkerId(),
                            "MANUAL_PID_KILL", "ADMIN_MANUAL", actorId,
                            firstNonBlank(actorType, "MANUAL_OPERATOR"),
                            authorizationDecisionId, "MANUAL_PID_KILL", correlationId, pid,
                            expectedProcessIdentity,
                            300));

            String previousStatus = entity.getStatus();
            String previousRuntimeAcceptanceState = entity.getRuntimeAcceptanceState();
            entity.setStatus("CANCEL_REQUESTED");
            entity.setErrorMessage(null);
            if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
                entity.setRuntimeAcceptanceState("ABORT_REQUESTED");
            }
            persistTask(entity);
            if (!"CANCEL_REQUESTED".equals(previousStatus)) {
                publishStatusChange(entity, previousStatus);
            }
            return new ManualPidKillReservation(entity.getTaskId(), previousStatus,
                    previousRuntimeAcceptanceState, operation);
        });
    }

    /**
     * The decision id is issued only after the controller role gate and the
     * tenant-scoped administrator check above have succeeded.  It is kept
     * distinct from the transport correlation id and cannot be supplied by a
     * Worker or HTTP request body.
     */
    private static String issueManualAuthorizationDecisionId(String actorType) {
        return "authz-v1:" + actorType.toLowerCase(Locale.ROOT) + ":" + UUID.randomUUID();
    }

    private void requireManualProcessIdentity(CodexTaskEntity entity, int pid, String processIdentity) {
        if (processIdentity == null || processIdentity.isBlank() || processIdentity.length() > 160) {
            throw new IllegalArgumentException("TERMINATION_PROCESS_IDENTITY_REQUIRED");
        }
        if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            String instanceId = entity.getRuntimeInstanceId();
            String expected = instanceId == null || instanceId.isBlank()
                    ? null : "app-server-instance:" + instanceId;
            if (!processIdentity.equals(expected)) {
                throw new IllegalArgumentException("TERMINATION_PROCESS_IDENTITY_MISMATCH");
            }
            return;
        }
        String expectedPrefix = "codex-cli:" + pid + ":";
        if (!processIdentity.startsWith(expectedPrefix) || processIdentity.length() == expectedPrefix.length()) {
            throw new IllegalArgumentException("TERMINATION_PROCESS_IDENTITY_MISMATCH");
        }
    }

    private record ManualPidKillReservation(String taskId, String previousStatus,
                                            String previousRuntimeAcceptanceState,
                                            TerminationOperationEntity operation) {
    }

    /** Records a Worker response without turning an unverified signal into ABORTED. */
    @Transactional
    public void recordManualPidKillResult(ManualPidKillRequest request, Map<String, Object> result) {
        if (request == null || request.operationId() == null || request.taskId() == null) return;
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(request.taskId()).orElse(null);
        if (entity == null || terminationOperationService == null) return;
        if (manualPidExitObserved(request, entity, result)) {
            String previousStatus = entity.getStatus();
            if (!isTerminalStatus(previousStatus)) {
                entity.setStatus("ABORTED");
                entity.setErrorMessage(null);
                entity.setLastAliveAt(LocalDateTime.now());
                persistTask(entity);
                publishStatusChange(entity, previousStatus);
            }
            terminationOperationService.markObservedTerminal(request.operationId(), "ABORTED");
            return;
        }
        terminationOperationService.markAwaitingObservation(request.operationId(), "TERMINATION_UNCONFIRMED");
        if (!isTerminalStatus(entity.getStatus())) {
            markCancellationAttention(entity, "TERMINATION_UNCONFIRMED");
        }
    }

    /** Records an indeterminate network/Worker failure after a manual operation was accepted. */
    @Transactional
    public void markManualPidKillUnconfirmed(ManualPidKillRequest request, Exception error) {
        if (request == null || request.operationId() == null || terminationOperationService == null) return;
        terminationOperationService.markUnconfirmed(request.operationId(), terminationFailureCode(error));
        taskRepository.findByTaskIdForUpdate(request.taskId()).ifPresent(entity -> {
            if (!isTerminalStatus(entity.getStatus())) {
                markCancellationAttention(entity, "TERMINATION_UNCONFIRMED");
            }
        });
    }

    /**
     * Records the only two safe outcomes of a failed manual dispatch: a
     * definitive Worker rejection restores the pre-request task marker;
     * transport uncertainty preserves CANCEL_REQUESTED for reconciliation.
     */
    public void markManualPidKillDispatchFailure(ManualPidKillRequest request, Exception error) {
        if (request == null || request.operationId() == null || terminationOperationService == null) return;
        if (!isDefinitiveTerminationRejection(error)) {
            markManualPidKillUnconfirmed(request, error);
            return;
        }
        rejectOperationAndRestoreTask(request.taskId(), request.operationId(), request.previousStatus(),
                request.previousRuntimeAcceptanceState(), terminationRejectionCode(error));
    }

    private void requireManualPidAuthorization(CodexTaskEntity entity, String expectedWorkerId,
                                               String actorId, String actorType, String authorizedTenantId,
                                               boolean crossUserAuthorized) {
        if (expectedWorkerId == null || !expectedWorkerId.equals(entity.getWorkerId())) {
            throw new IllegalArgumentException("TERMINATION_WORKER_TASK_MISMATCH");
        }
        // A task owner may inspect its Worker, but must not gain signal
        // authority merely through ownership.  Only trusted admin entrypoints
        // set both this actor type and the tenant-scoped authorization flag.
        boolean trustedManualAdministrator = ("TENANT_ADMIN_MANUAL".equals(actorType)
                || "UPSTREAM_ADMIN_MANUAL".equals(actorType))
                && actorId != null && !actorId.isBlank()
                && crossUserAuthorized && authorizedTenantId != null
                && authorizedTenantId.equals(entity.getTenantId());
        if (!trustedManualAdministrator) {
            throw new IllegalArgumentException("TERMINATION_TASK_ACCESS_DENIED");
        }
    }

    private boolean manualPidExitObserved(ManualPidKillRequest request, CodexTaskEntity task,
                                          Map<String, Object> result) {
        if (request == null || task == null || result == null) return false;
        if (!booleanTrue(result.get("observed_exit"))) return false;
        // The authenticated Worker response must prove that this exact
        // operation, task, and physical Worker observed the exit. A lifecycle
        // label or a generic observed_exit flag is not sufficient evidence.
        if (!hasValue(result.get("task_id"), request.taskId())) return false;
        Object operationValue = result.get("termination_operation");
        if (!(operationValue instanceof Map<?, ?> operation)) return false;
        return hasValue(operation.get("operation_id"), request.operationId())
                && hasValue(operation.get("task_id"), request.taskId())
                && hasValue(operation.get("worker_id"), task.getWorkerId())
                && hasValue(operation.get("kind"), "MANUAL_PID_KILL")
                && hasValue(operation.get("origin"), "ADMIN_MANUAL")
                && hasValue(operation.get("status"), "OBSERVED_EXIT")
                && hasObservedExitTimestamp(operation);
    }

    private boolean booleanTrue(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private boolean hasValue(Object value, String expected) {
        return value instanceof String actual && expected.equals(actual);
    }

    private boolean hasText(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private boolean hasObservedExitTimestamp(Map<?, ?> operation) {
        return hasText(operation.get("observed_at"))
                || hasText(operation.get("observed_exit_at"));
    }

    public record ManualPidKillRequest(String operationId, String taskId, String previousStatus,
                                       String previousRuntimeAcceptanceState,
                                       TerminationOperationCapability capability) {
    }

    private boolean isCancellationAcknowledged(Map<String, Object> acknowledgement) {
        if (acknowledgement == null) return false;
        return hasCancellationAcknowledgementValue(acknowledgement.get("status"))
                || hasCancellationAcknowledgementValue(acknowledgement.get("abort_status"))
                || hasCancellationAcknowledgementValue(acknowledgement.get("lifecycle_state"))
                || hasCancellationAcknowledgementValue(acknowledgement.get("lifecycle_status"));
    }

    private boolean hasCancellationAcknowledgementValue(Object value) {
        if (value == null) return false;
        return switch (String.valueOf(value).toLowerCase(Locale.ROOT)) {
            case "cancel_requested", "abort_pending", "aborted", "already_terminal" -> true;
            default -> false;
        };
    }

    private boolean isDefinitiveTerminationRejection(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof WebClientResponseException responseException) {
                int status = responseException.getStatusCode().value();
                // A Worker 404 can mean restart, routing drift, or loss of its
                // in-memory native-task registry. It does not prove that the
                // managed CLI exited, so cancellation must remain unconfirmed.
                return status >= 400 && status < 500
                        && status != 404 && status != 408 && status != 429;
            }
        }
        return false;
    }

    private void restoreRejectedCancellation(CodexTaskEntity entity, String previousStatus,
                                             String previousRuntimeAcceptanceState) {
        if (entity == null || isTerminalStatus(entity.getStatus())
                || !"CANCEL_REQUESTED".equals(entity.getStatus())
                || previousStatus == null || "CANCEL_REQUESTED".equals(previousStatus)) {
            return;
        }
        entity.setStatus(previousStatus);
        entity.setRuntimeAcceptanceState(previousRuntimeAcceptanceState);
        entity.setErrorMessage("TERMINATION_REJECTED");
        persistTask(entity);
        publishStatusChange(entity, "CANCEL_REQUESTED");
    }

    /**
     * Hold the task row lock before making the operation terminal.  Otherwise
     * a retry could observe REJECTED, reserve a new operation, and then be
     * overwritten by this stale pre-request restoration.
     */
    private void rejectOperationAndRestoreTask(String taskId, String operationId, String previousStatus,
                                                String previousRuntimeAcceptanceState, String rejectionCode) {
        inTerminationTransaction(() -> {
            var task = taskRepository.findByTaskIdForUpdate(taskId);
            terminationOperationService.markRejected(operationId, rejectionCode);
            task.ifPresent(entity ->
                    restoreRejectedCancellation(entity, previousStatus, previousRuntimeAcceptanceState));
            return null;
        });
    }

    private void markCancellationAttention(String taskId, String attentionCode) {
        inTerminationTransaction(() -> {
            taskRepository.findByTaskIdForUpdate(taskId).ifPresent(entity ->
                    markCancellationAttention(entity, attentionCode));
            return null;
        });
    }

    private <T> T inTerminationTransaction(Supplier<T> action) {
        if (terminationTransactionManager == null) {
            return action.get();
        }
        TransactionTemplate transaction = new TransactionTemplate(terminationTransactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> action.get());
    }

    private String terminationRejectionCode(Exception error) {
        String suffix = error == null ? "UNKNOWN" : error.getClass().getSimpleName();
        String code = "TERMINATION_REJECTED_" + suffix;
        return code.substring(0, Math.min(code.length(), 160));
    }

    private static final class TerminationDispatchUnconfirmedException extends IllegalStateException {
        private TerminationDispatchUnconfirmedException(String message) {
            super(message);
        }
    }

    private CodexWorkerClient terminationClient(CodexTaskEntity entity) {
        if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            if (runtimeRegistryService == null) {
                throw new IllegalStateException("CODEX_RUNTIME_REGISTRY_UNAVAILABLE");
            }
            CodexRuntimeBinding binding = runtimeRegistryService.resolveBoundRuntime(
                    entity.getRuntimeId(), entity.getRuntimeRevision(), entity.getWorkerId(),
                    entity.getRuntimeInstanceId());
            return clientFactory.getOrCreate(
                    "runtime:" + binding.getRuntimeId() + ":" + binding.getRuntimeRevision(),
                    binding.getEndpointUrl(), binding.getAuthToken(), binding.getInstanceId());
        }
        CodexConfig config = workerManagementFacade.getCodexConfig(entity.getWorkerId());
        if (config == null || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalStateException("CODEX_WORKER_UNAVAILABLE");
        }
        return clientFactory.getOrCreate(entity.getWorkerId() + ":codex",
                config.getBaseUrl(), config.getAuthToken());
    }

    /** Keeps the task active while exposing a safe lifecycle marker. */
    @Transactional
    public void markLifecycleAttention(String taskId, String attentionCode) {
        taskRepository.findByTaskIdForUpdate(taskId).ifPresent(entity -> {
            if (isTerminalStatus(entity.getStatus())) return;
            entity.setErrorMessage(attentionCode);
            persistTask(entity);
        });
    }

    private void markCancellationAttention(CodexTaskEntity entity, String attentionCode) {
        String previousStatus = entity.getStatus();
        if (isTerminalStatus(previousStatus)) return;
        entity.setStatus("CANCEL_REQUESTED");
        entity.setErrorMessage(attentionCode);
        persistTask(entity);
        if (!previousStatus.equals(entity.getStatus())) {
            publishStatusChange(entity, previousStatus);
        }
    }

    private String terminationFailureCode(Exception error) {
        String name = error == null ? "UNKNOWN" : error.getClass().getSimpleName();
        return ("TERMINATION_DISPATCH_" + name).substring(0,
                Math.min(160, ("TERMINATION_DISPATCH_" + name).length()));
    }

    private void markTerminationObserved(String taskId, String outcome) {
        if (terminationOperationService != null) {
            terminationOperationService.markObservedTerminalForTask(taskId, outcome);
        }
    }

    /**
     * 标记任务完成
     */
    @Transactional
    public void completeTask(String taskId, String workerTaskId, String codexThreadId,
                              String resultText, BigDecimal costUsd, Long inputTokens,
                              Long outputTokens, Long durationMs, Integer numTurns,
                              String model) {
        completeTask(taskId, workerTaskId, codexThreadId, resultText, costUsd, inputTokens,
                outputTokens, durationMs, numTurns, model, null);
    }

    /**
     * Atomically persists a terminal Worker transition and its durable ESN.
     * The relay supplies {@code ackSeq} only after the corresponding session
     * message has been durably persisted, so a MySQL failure cannot leave a
     * terminal task without the cursor required for replay.
     */
    @Transactional
    public void completeTask(String taskId, String workerTaskId, String codexThreadId,
                              String resultText, BigDecimal costUsd, Long inputTokens,
                              Long outputTokens, Long durationMs, Integer numTurns,
                              String model, Integer ackSeq) {
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null) {
            log.warn("completeTask: task not found: {}", taskId);
            return;
        }

        String previousStatus = entity.getStatus();
        if (isTerminalStatus(previousStatus) && !"COMPLETED".equals(previousStatus)) {
            log.warn("Ignoring late Codex completion for terminal task: taskId={}, status={}",
                    taskId, previousStatus);
            return;
        }
        validateTerminalWorkerTaskId(entity, workerTaskId);
        entity.setStatus("COMPLETED");
        if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            entity.setRuntimeAcceptanceState("TERMINAL");
        }
        if (workerTaskId != null) entity.setWorkerTaskId(workerTaskId);
        if (codexThreadId != null) entity.setCodexThreadId(codexThreadId);
        if (resultText != null) entity.setResultText(resultText);
        if (costUsd != null) entity.setCostUsd(costUsd);
        if (inputTokens != null) entity.setInputTokens(inputTokens);
        if (outputTokens != null) entity.setOutputTokens(outputTokens);
        if (durationMs != null) entity.setDurationMs(durationMs);
        if (numTurns != null) entity.setNumTurns(numTurns);
        applyWorkerReportedModel(entity, model);
        entity.setErrorMessage(null);
        LocalDateTime now = LocalDateTime.now();
        entity.setLastAliveAt(now);
        entity.setLastOutputAt(now);
        advanceAckSeq(entity, ackSeq);

        persistTask(entity);
        markTerminationObserved(taskId, "COMPLETED");
        log.info("Completed Codex task: taskId={}, cost={}", taskId, costUsd);
        if (!"COMPLETED".equals(previousStatus)) {
            publishStatusChange(entity, previousStatus);
        }
    }

    private void applyWorkerReportedModel(CodexTaskEntity entity, String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())
                && entity.getModel() != null
                && !entity.getModel().isBlank()) {
            return;
        }
        entity.setModel(model);
    }

    /**
     * 标记任务失败
     */
    @Transactional
    public void failTask(String taskId, String workerTaskId, String codexThreadId, String errorMessage) {
        failTask(taskId, workerTaskId, codexThreadId, errorMessage, null);
    }

    /**
     * Atomically persists a terminal Worker failure and its durable ESN after
     * the matching session event has been durably written.
     */
    @Transactional
    public void failTask(String taskId, String workerTaskId, String codexThreadId,
                         String errorMessage, Integer ackSeq) {
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null) {
            log.warn("failTask: task not found: {}", taskId);
            return;
        }

        String previousStatus = entity.getStatus();
        if (isTerminalStatus(previousStatus) && !"FAILED".equals(previousStatus)) {
            log.warn("Ignoring late Codex failure for terminal task: taskId={}, status={}",
                    taskId, previousStatus);
            return;
        }
        validateTerminalWorkerTaskId(entity, workerTaskId);
        String stableError = stableTaskError(entity, errorMessage);
        entity.setStatus("FAILED");
        if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            entity.setRuntimeAcceptanceState("TERMINAL");
        }
        entity.setErrorMessage(stableError);
        if (workerTaskId != null) entity.setWorkerTaskId(workerTaskId);
        if (codexThreadId != null) entity.setCodexThreadId(codexThreadId);
        LocalDateTime now = LocalDateTime.now();
        entity.setLastAliveAt(now);
        entity.setLastOutputAt(now);
        advanceAckSeq(entity, ackSeq);

        persistTask(entity);
        markTerminationObserved(taskId, "FAILED");
        log.info("Failed Codex task: taskId={}, error={}", taskId, stableError);
        if (!"FAILED".equals(previousStatus)) {
            publishStatusChange(entity, previousStatus);
        }
    }

    /**
     * Persists the allowlisted diagnostic snapshot before the terminal message
     * is emitted, then returns the same safe envelope with its opaque reference.
     */
    public ErrorEnvelope attachDiagnostic(String taskId,
                                          ErrorEnvelope envelope,
                                          ErrorDiagnosticInput input) {
        if (errorDiagnosticService == null || envelope == null) return envelope;
        CodexTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        if (entity == null) return envelope;
        envelope.setTaskId(entity.getTaskId());
        envelope.setProviderType(resolveProviderType(entity));
        envelope.setRuntimeType(entity.getRuntimeType());
        if (input != null) input.setWorkerLabel(entity.getWorkerId());
        String diagnosticRef = errorDiagnosticService.createSnapshotSafely(
                envelope, input, entity.getSessionId(), entity.getUserId(), entity.getTenantId());
        envelope.setDiagnosticRef(diagnosticRef);
        return envelope;
    }

    private void advanceAckSeq(CodexTaskEntity entity, Integer ackSeq) {
        if (ackSeq == null) {
            return;
        }
        Integer current = entity.getLastAckedSeq();
        entity.setLastAckedSeq(current == null ? ackSeq : Math.max(current, ackSeq));
    }

    @Transactional
    public void reconcileAbortedTask(String taskId, String workerTaskId, String codexThreadId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null) return;
        String previousStatus = entity.getStatus();
        if (isTerminalStatus(previousStatus) && !"ABORTED".equals(previousStatus)) {
            log.warn("Ignoring late Codex abort for terminal task: taskId={}, status={}",
                    taskId, previousStatus);
            return;
        }
        validateTerminalWorkerTaskId(entity, workerTaskId);
        entity.setStatus("ABORTED");
        entity.setRuntimeAcceptanceState("TERMINAL");
        if (workerTaskId != null) entity.setWorkerTaskId(workerTaskId);
        if (codexThreadId != null) entity.setCodexThreadId(codexThreadId);
        entity.setLastAliveAt(LocalDateTime.now());
        persistTask(entity);
        markTerminationObserved(taskId, "ABORTED");
        log.info("Reconciled remotely aborted Codex task: taskId={}", taskId);
        if (!"ABORTED".equals(previousStatus)) {
            publishStatusChange(entity, previousStatus);
        }
    }

    /** Atomically closes PREPARED only when no acceptance attempt has started. */
    @Transactional
    public boolean failTaskIfAcceptanceNotStarted(String taskId, String failureCode) {
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity == null || isTerminalStatus(entity.getStatus())) {
            return true;
        }
        if (!CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())
                || !"PREPARED".equals(entity.getRuntimeAcceptanceState())) {
            return false;
        }
        String previousStatus = entity.getStatus();
        entity.setStatus("FAILED");
        entity.setRuntimeAcceptanceState("TERMINAL");
        entity.setErrorMessage(failureCode);
        LocalDateTime now = LocalDateTime.now();
        entity.setLastAliveAt(now);
        entity.setLastOutputAt(now);
        persistTask(entity);
        // This transition happens before the Worker accepted a task, so there
        // is deliberately no workerTaskId that resync could reconnect to. It
        // is a definitive failure even though ordinary Worker-side FAILED
        // transitions remain recoverable.
        publishStatusChange(entity, previousStatus, Boolean.FALSE);
        return true;
    }

    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "ABORTED".equals(status);
    }

    private void validateTerminalWorkerTaskId(CodexTaskEntity entity, String workerTaskId) {
        if (workerTaskId != null && !workerTaskId.isBlank()
                && entity.getWorkerTaskId() != null && !entity.getWorkerTaskId().isBlank()
                && !entity.getWorkerTaskId().equals(workerTaskId)) {
            throw new IllegalStateException(
                    "CODEX_RUNTIME_IDEMPOTENCY_CONFLICT: worker task id changed for " + entity.getTaskId());
        }
    }

    private String stableTaskError(CodexTaskEntity entity, String errorMessage) {
        if (!CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            return errorMessage;
        }
        if (errorMessage != null && errorMessage.startsWith("CODEX_")
                && errorMessage.length() <= 256
                && errorMessage.chars().noneMatch(Character::isISOControl)
                && !errorMessage.toLowerCase(Locale.ROOT).contains("http://")
                && !errorMessage.toLowerCase(Locale.ROOT).contains("https://")
                && !errorMessage.toLowerCase(Locale.ROOT).contains("bearer ")) {
            return errorMessage;
        }
        return "CODEX_RUNTIME_TASK_FAILED";
    }

    /**
     * 更新 Codex Thread ID
     */
    @Transactional
    public void updateCodexThreadId(String taskId, String codexThreadId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
        if (entity != null && codexThreadId != null) {
            entity.setCodexThreadId(codexThreadId);
            persistTask(entity);
        }
    }

    // ── Task provider narrow port implementations ──

    @Override
    public String getProviderType() {
        return AGENT_ID;
    }

    @Override
    public Set<TaskQueryCapability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    @Transactional
    public DispatchTaskDTO createTaskDirect(java.util.Map<String, Object> params,
                                             String userId, String tenantId) {
        return createTaskDirectForProvider(CODEX_PROVIDER_TYPE, params, userId, tenantId);
    }

    @Transactional
    public DispatchTaskDTO createTaskDirectForProvider(String expectedProviderType,
                                                        java.util.Map<String, Object> params,
                                                        String userId,
                                                        String tenantId) {
        String effectiveProviderType = requireProviderParams(expectedProviderType, params);
        CodexTaskCreateCommand form = new CodexTaskCreateCommand();
        form.setAgentId((String) params.get("agentId"));
        form.setWorkerId((String) params.get("workerId"));
        form.setPrompt((String) params.get("prompt"));
        form.setCwd((String) params.get("cwd"));
        form.setDirectoryId((String) params.get("directoryId"));
        form.setModel((String) params.get("model"));
        form.setModelConfigId((String) params.get("modelConfigId"));
        form.setSessionId((String) params.get("sessionId"));
        form.setContextId((String) params.get("contextId"));
        form.setImages((String) params.get("images"));
        form.setAttachments(attachmentsParam(params.get("attachments")));
        form.setCodexThreadId((String) params.get("codexThreadId"));
        form.setProviderType(effectiveProviderType);
        form.setInitializeRuntimeAffinity(
                InternalTaskDispatchMarkers.requestsRuntimeAffinityInitialization(params));
        if (isCodexBizProvider(form.getProviderType())) {
            applyCodexBizParams(form, params);
        }
        if (params.get("maxTurns") instanceof Number n) {
            form.setMaxTurns(n.intValue());
        }
        DispatchTaskDTO task = createTask(userId, tenantId, form);
        return getTaskByIdForProvider(task.getTaskId(), form.getProviderType()).orElseThrow();
    }

    @Override
    public Optional<DispatchTaskDTO> getTaskById(String taskId) {
        return getTaskByIdForProvider(taskId, AGENT_ID);
    }

    @Override
    public Optional<DispatchTaskDTO> getTaskByIdAndUser(String taskId, String userId) {
        return getTaskByIdAndUserForProvider(taskId, userId, AGENT_ID);
    }

    @Override
    public List<DispatchTaskDTO> listTasksBySession(String sessionId) {
        return listTasksBySessionForProvider(sessionId, AGENT_ID);
    }

    public Optional<DispatchTaskDTO> getTaskByIdForProvider(String taskId, String providerType) {
        return taskRepository.findByTaskId(taskId)
                .filter(entity -> matchesProvider(entity, providerType))
                .map(this::toDispatchDTO);
    }

    public Optional<DispatchTaskDTO> getTaskByIdAndUserForProvider(String taskId, String userId, String providerType) {
        return taskRepository.findByTaskIdAndUserId(taskId, userId)
                .filter(entity -> matchesProvider(entity, providerType))
                .map(this::toDispatchDTO);
    }

    public List<DispatchTaskDTO> listTasksBySessionForProvider(String sessionId, String providerType) {
        return taskRepository.findBySessionId(sessionId).stream()
                .filter(entity -> matchesProvider(entity, providerType))
                .map(this::toDispatchDTO)
                .toList();
    }

    @Override
    public List<DispatchTaskDTO> listActiveDispatchTasks(String userId) {
        return listActiveDispatchTasksForProvider(userId, AGENT_ID);
    }

    public List<DispatchTaskDTO> listActiveDispatchTasksForProvider(String userId, String providerType) {
        return taskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                        userId, List.of("RUNNING", "AWAITING_PERMISSION", "AWAITING_INPUT", "CANCEL_REQUESTED")).stream()
                .filter(entity -> matchesProvider(entity, providerType))
                .map(this::toDispatchDTO)
                .toList();
    }

    @Override
    public TaskPageResult listTaskPage(String userId, int page, int size, String state) {
        return listTasksPagedForProvider(userId, page, size, state, AGENT_ID);
    }

    public TaskPageResult listTasksPagedForProvider(String userId, int page, int size, String state, String providerType) {
        List<CodexTaskEntity> tasks = taskRepository.findByUserIdOrderByCreatedAtDesc(userId);
        tasks = filterTasksByProvider(tasks, providerType);
        return buildSessionPage(tasks, page, size, state);
    }

    public TaskPageResult listTasksPagedForProvider(String userId, String tenantId,
                                                     int page, int size, String state,
                                                     String workerId,
                                                     String providerType) {
        List<CodexTaskEntity> tasks = taskRepository
                .findByUserIdAndTenantIdOrderByCreatedAtDesc(userId, tenantId);
        tasks = filterTasksByProvider(tasks, providerType);
        if (workerId != null && !workerId.isBlank()) {
            String normalizedWorkerId = workerId.trim();
            tasks = tasks.stream()
                    .filter(task -> normalizedWorkerId.equals(task.getWorkerId()))
                    .toList();
        }
        return buildSessionPage(tasks, page, size, state);
    }

    @Override
    public TaskPageResult listDirectoryTaskPage(String userId, String directoryId, int page, int size, String state) {
        return listTasksByDirectoryPagedForProvider(userId, directoryId, page, size, state, AGENT_ID);
    }

    public TaskPageResult listTasksByDirectoryPagedForProvider(String userId, String directoryId, int page, int size,
                                                               String state, String providerType) {
        List<CodexTaskEntity> tasks = taskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc(directoryId, userId);
        tasks = filterTasksByProvider(tasks, providerType);
        return buildSessionPage(tasks, page, size, state);
    }

    @Override
    public TaskSearchResult searchSessionPage(String userId, String keyword, String workerId,
                                              String directoryId, int page, int size) {
        boolean hasFilter = (keyword != null && !keyword.isBlank())
                || (workerId != null && !workerId.isBlank())
                || (directoryId != null && !directoryId.isBlank());
        if (!hasFilter) {
            return TaskSearchResult.empty(page, size);
        }

        String normalizedKeyword = keyword != null ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        return searchSessionsForProvider(userId, normalizedKeyword, workerId, directoryId, page, size, AGENT_ID);
    }

    public TaskSearchResult searchSessionsForProvider(String userId, String normalizedKeyword, String workerId,
                                                      String directoryId, int page, int size, String providerType) {
        List<List<CodexTaskEntity>> sessions = new ArrayList<>(groupTasksBySession(
                filterTasksByProvider(taskRepository.findByUserIdOrderByCreatedAtDesc(userId), providerType)
        ).values());

        List<Map<String, Object>> filtered = sessions.stream()
                .filter(tasks -> matchesSessionFilters(tasks, normalizedKeyword, workerId, directoryId))
                .map(this::toSearchResult)
                .sorted((a, b) -> compareNullableTime((LocalDateTime) b.get("updatedAt"), (LocalDateTime) a.get("updatedAt")))
                .toList();

        long total = filtered.size();
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return TaskSearchResult.of(filtered.subList(from, to), total, page, size);
    }

    private DispatchTaskDTO toDispatchDTO(CodexTaskEntity entity) {
        return toDispatchDTO(entity, resolveProviderType(entity));
    }

    private DispatchTaskDTO toDispatchDTO(CodexTaskEntity entity, String providerType) {
        String agentId = resolveLogicalAgentId(entity);
        return DispatchTaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getWorkerTaskId())
                .runtimeId(entity.getRuntimeId())
                .runtimeRevision(entity.getRuntimeRevision())
                .runtimeType(entity.getRuntimeType())
                .runtimeInstanceId(entity.getRuntimeInstanceId())
                .routingEpoch(entity.getRoutingEpoch())
                .runtimeAcceptanceState(entity.getRuntimeAcceptanceState())
                .sessionId(entity.getSessionId())
                .workerId(entity.getWorkerId())
                .userId(entity.getUserId())
                .agentId(agentId)
                .providerType(providerType)
                .prompt(entity.getPrompt())
                .cwd(entity.getCwd())
                .directoryId(entity.getDirectoryId())
                .status(entity.getStatus())
                .model(entity.getModel())
                .costUsd(entity.getCostUsd())
                .inputTokens(entity.getInputTokens())
                .outputTokens(entity.getOutputTokens())
                .durationMs(entity.getDurationMs())
                .numTurns(entity.getNumTurns())
                .resultText(entity.getResultText())
                .errorMessage(entity.getErrorMessage())
                .error(toErrorMap(resolveErrorEnvelope(entity)))
                .lastAckedSeq(entity.getLastAckedSeq())
                .lastOutputAt(entity.getLastOutputAt())
                .responseTimedOut(TaskResponseTimeoutSupport.isResponseTimedOut(
                        entity.getStatus(), entity.getLastOutputAt(), entity.getCreatedAt(), LocalDateTime.now()))
                .silentForSeconds(TaskResponseTimeoutSupport.silentForSeconds(
                        entity.getStatus(), entity.getLastOutputAt(), entity.getCreatedAt(), LocalDateTime.now()))
                .responseTimeoutThresholdSeconds(TaskResponseTimeoutSupport.DEFAULT_RESPONSE_TIMEOUT_SECONDS)
                .source(entity.getSource())
                .createdAt(entity.getCreatedAt())
                .createdAtEpochMs(entity.getCreatedAtEpochMs())
                .updatedAt(entity.getUpdatedAt())
                // Codex-specific
                .codexThreadId(entity.getCodexThreadId())
                .contextId(resolveTaskContextId(entity))
                .build();
    }

    @Override
    @Transactional
    public void deleteTask(String userId, String taskId) {
        deleteTaskForProvider(CODEX_PROVIDER_TYPE, userId, taskId);
    }

    @Transactional
    public void deleteTaskForProvider(String providerType, String userId, String taskId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireTaskProvider(entity, providerType);

        if (CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())) {
            entity = taskRuntimeStateService.claimTerminalDeletion(taskId, userId);
            deleteTerminalAppServerTask(entity);
        } else if (!isTerminalStatus(entity.getStatus())) {
            throw new IllegalStateException("Cannot delete a non-terminal task. Wait for observed exit first.");
        }
        if (nativeSubtaskStateRepository != null) {
            nativeSubtaskStateRepository.deleteByTaskId(taskId);
        }
        taskRepository.delete(entity);
        log.info("Codex task deleted: taskId={}, userId={}", taskId, userId);
    }

    private void deleteTerminalAppServerTask(CodexTaskEntity entity) {
        if (!isTerminalStatus(entity.getStatus())) {
            throw new IllegalStateException(
                    "Cannot delete a non-terminal app-server task. Please abort it first.");
        }
        if (runtimeRegistryService == null) {
            throw new IllegalStateException(
                    "CODEX_RUNTIME_REGISTRY_UNAVAILABLE: cannot resolve task affinity for deletion");
        }
        CodexRuntimeBinding binding = runtimeRegistryService.resolveBoundRuntime(
                entity.getRuntimeId(), entity.getRuntimeRevision(), entity.getWorkerId(),
                entity.getRuntimeInstanceId());
        if (binding.getRuntimeType() != CodexRuntimeType.APP_SERVER) {
            throw new IllegalStateException(
                    "CODEX_RUNTIME_AFFINITY_MISMATCH: app-server task resolved to another runtime type");
        }

        String remoteTaskId = firstNonBlank(entity.getWorkerTaskId(), entity.getTaskId());
        CodexWorkerClient client = clientFactory.getOrCreate(
                "runtime:" + binding.getRuntimeId() + ":" + binding.getRuntimeRevision(),
                binding.getEndpointUrl(), binding.getAuthToken(), binding.getInstanceId());
        Boolean removed = client.deleteTask(remoteTaskId).block(Duration.ofSeconds(15));
        if (removed == null) {
            throw new IllegalStateException(
                    "CODEX_RUNTIME_DELETE_UNKNOWN: app-server returned no deletion result");
        }
        log.info("Codex app-server task cleanup confirmed: taskId={}, remoteTaskId={}, removed={}",
                entity.getTaskId(), remoteTaskId, removed);
    }

    @Override
    @Transactional
    public Object resyncTask(String taskId, String userId) {
        return resyncTaskForProvider(CODEX_PROVIDER_TYPE, taskId, userId);
    }

    @Transactional
    public Object resyncTaskForProvider(String providerType, String taskId, String userId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserIdForUpdate(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireTaskProvider(entity, providerType);
        if (!"FAILED".equals(entity.getStatus())) {
            throw new IllegalStateException("Only FAILED tasks can be resynced, current: " + entity.getStatus());
        }
        if ("DELETE_REQUESTED".equals(entity.getRuntimeAcceptanceState())) {
            throw new IllegalStateException("Cannot resync a task while deletion is pending");
        }
        if (entity.getWorkerTaskId() == null) {
            throw new IllegalStateException("No worker task ID, cannot resync");
        }

        String previousStatus = entity.getStatus();
        entity.setStatus("RUNNING");
        entity.setErrorMessage(null);
        LocalDateTime now = LocalDateTime.now();
        entity.setLastAliveAt(now);
        entity.setLastOutputAt(now);
        persistTask(entity);
        log.info("Resync: reset task {} to RUNNING, attempting SSE reconnect", taskId);
        publishStatusChange(entity, previousStatus);

        try {
            streamRelay.reconnectTask(taskId, entity.getSessionId(), entity.getWorkerId());
        } catch (Exception e) {
            log.warn("Resync: SSE reconnect failed for task {}: {}", taskId, e.getMessage());
        }

        return Map.of("status", "RESYNCED", "action", "RECONNECTED", "taskId", taskId);
    }

    private TaskPageResult buildSessionPage(List<CodexTaskEntity> tasks, int page, int size, String interactionState) {
        Set<String> states = parseInteractionStates(interactionState);
        List<List<CodexTaskEntity>> sessions = new ArrayList<>(groupTasksBySession(tasks).values());
        if (!states.isEmpty()) {
            sessions = sessions.stream()
                    .filter(sessionTasks -> states.contains(deriveInteractionState(sessionTasks.get(0).getStatus())))
                    .toList();
        }

        long totalSessions = sessions.size();
        int from = Math.min(page * size, sessions.size());
        int to = Math.min(from + size, sessions.size());
        List<DispatchTaskDTO> content = sessions.subList(from, to).stream()
                .flatMap(Collection::stream)
                .map(this::toDispatchDTO)
                .toList();

        return TaskPageResult.of(content, totalSessions, page, size);
    }

    private Map<String, List<CodexTaskEntity>> groupTasksBySession(List<CodexTaskEntity> tasks) {
        Map<String, List<CodexTaskEntity>> grouped = new LinkedHashMap<>();
        for (CodexTaskEntity task : tasks) {
            String sessionKey = (task.getSessionId() != null && !task.getSessionId().isBlank())
                    ? task.getSessionId()
                    : "task:" + task.getTaskId();
            grouped.computeIfAbsent(sessionKey, ignored -> new ArrayList<>()).add(task);
        }
        return grouped;
    }

    private Set<String> parseInteractionStates(String interactionState) {
        if (interactionState == null || interactionState.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(interactionState.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private boolean matchesSessionFilters(List<CodexTaskEntity> tasks, String keyword, String workerId, String directoryId) {
        CodexTaskEntity latestTask = tasks.get(0);
        if (workerId != null && !workerId.isBlank() && !workerId.equals(latestTask.getWorkerId())) {
            return false;
        }
        if (directoryId != null && !directoryId.isBlank() && !directoryId.equals(latestTask.getDirectoryId())) {
            return false;
        }
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        return tasks.stream().anyMatch(task -> containsIgnoreCase(task.getPrompt(), keyword))
                || tasks.stream().anyMatch(task -> containsIgnoreCase(task.getResultText(), keyword));
    }

    private Map<String, Object> toSearchResult(List<CodexTaskEntity> tasks) {
        CodexTaskEntity latestTask = tasks.get(0);
        CodexTaskEntity earliestTask = tasks.get(tasks.size() - 1);
        BigDecimal totalCost = tasks.stream()
                .map(task -> task.getCostUsd() != null ? task.getCostUsd() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDateTime updatedAt = tasks.stream()
                .map(CodexTaskEntity::getUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(latestTask.getUpdatedAt());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", latestTask.getSessionId());
        result.put("workerId", latestTask.getWorkerId());
        result.put("directoryId", latestTask.getDirectoryId());
        result.put("firstPrompt", truncate(earliestTask.getPrompt(), 200));
        result.put("customTitle", null);
        result.put("tags", List.of());
        result.put("interactionState", deriveInteractionState(latestTask.getStatus()));
        result.put("latestTaskId", latestTask.getTaskId());
        result.put("latestStatus", latestTask.getStatus());
        result.put("model", latestTask.getModel());
        result.put("cwd", latestTask.getCwd());
        result.put("source", latestTask.getSource());
        result.put("totalCost", totalCost);
        result.put("createdAt", earliestTask.getCreatedAt());
        result.put("updatedAt", updatedAt);
        return result;
    }

    private String deriveInteractionState(String taskStatus) {
        if ("RUNNING".equals(taskStatus) || "PENDING".equals(taskStatus)
                || "CANCEL_REQUESTED".equals(taskStatus)) {
            return "PROCESSING";
        }
        if ("COMPLETED".equals(taskStatus) || "FAILED".equals(taskStatus)
                || "ABORTED".equals(taskStatus) || "AWAITING_PERMISSION".equals(taskStatus)
                || "AWAITING_INPUT".equals(taskStatus)) {
            return "AWAITING_REPLY";
        }
        return null;
    }

    private void publishStatusChange(CodexTaskEntity entity, String previousStatus) {
        publishStatusChange(entity, previousStatus, terminalRecoverability(entity.getStatus()));
    }

    private void publishStatusChange(CodexTaskEntity entity, String previousStatus,
                                     Boolean recoverable) {
        eventPublisher.publishEvent(TaskStatusChangeEvent.builder()
                .taskId(entity.getTaskId())
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .tenantId(entity.getTenantId())
                .agentId(firstNonBlank(resolveLogicalAgentId(entity), resolveProviderType(entity)))
                .status(entity.getStatus())
                .previousStatus(previousStatus)
                .errorMessage(entity.getErrorMessage())
                .error(resolveErrorEnvelope(entity))
                .interactionState(deriveInteractionState(entity.getStatus()))
                .recoverable(recoverable)
                .build());
    }

    private ErrorEnvelope resolveErrorEnvelope(CodexTaskEntity entity) {
        if (errorDiagnosticService == null || entity == null || !"FAILED".equals(entity.getStatus())) {
            return null;
        }
        return errorDiagnosticService.findLatestEnvelope(entity.getTaskId());
    }

    private Map<String, Object> toErrorMap(ErrorEnvelope error) {
        if (error == null) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("errorCode", error.getErrorCode());
        value.put("message", error.getMessage());
        value.put("category", error.getCategory());
        value.put("runtimePhase", error.getRuntimePhase());
        value.put("recoverable", error.getRecoverable());
        value.put("diagnosticRef", error.getDiagnosticRef());
        value.put("occurredAt", error.getOccurredAt());
        value.put("taskId", error.getTaskId());
        value.put("providerType", error.getProviderType());
        value.put("runtimeType", error.getRuntimeType());
        value.values().removeIf(java.util.Objects::isNull);
        return value;
    }

    private Boolean terminalRecoverability(String status) {
        if ("FAILED".equals(status)) {
            // FAILED is explicitly accepted by resyncTaskForProvider and can
            // return to RUNNING, so it is recoverable rather than definitive.
            return Boolean.TRUE;
        }
        if ("COMPLETED".equals(status) || "ABORTED".equals(status)
                || "REJECTED".equals(status) || "TIMED_OUT".equals(status)
                || "CANCELLED".equals(status) || "CANCELED".equals(status)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private int compareNullableTime(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private CodexTaskEntity persistTask(CodexTaskEntity entity) {
        CodexTaskEntity saved = taskRepository.save(entity);
        syncSessionTask(saved);
        syncSessionProjection(saved);
        return saved;
    }

    private void syncSessionTask(CodexTaskEntity entity) {
        if (sessionTaskRepository == null) {
            return;
        }
        SessionTaskEntity sessionTask = sessionTaskRepository.findByTaskIdForUpdate(entity.getTaskId())
                .orElseGet(SessionTaskEntity::new);
        String agentId = firstNonBlank(
                entity.getResolvedAgentId(),
                sessionTask.getAgentId(),
                resolveSessionAgentId(entity.getSessionId()));
        String providerType = firstNonBlank(
                entity.getProviderType(),
                sessionTask.getProviderType(),
                resolveSessionProviderType(entity.getSessionId()),
                AGENT_ID);
        entity.setProviderType(providerType);
        sessionTask.setTaskId(entity.getTaskId());
        sessionTask.setSessionId(entity.getSessionId());
        sessionTask.setProviderType(providerType);
        sessionTask.setProviderTaskId(entity.getWorkerTaskId());
        sessionTask.setWorkerId(entity.getWorkerId());
        sessionTask.setUserId(entity.getUserId());
        sessionTask.setTenantId(entity.getTenantId());
        sessionTask.setAgentId(agentId);
        sessionTask.setDirectoryId(entity.getDirectoryId());
        sessionTask.setPrompt(entity.getPrompt());
        sessionTask.setCwd(entity.getCwd());
        sessionTask.setStatus(entity.getStatus());
        sessionTask.setModel(entity.getModel());
        sessionTask.setCostUsd(entity.getCostUsd());
        sessionTask.setInputTokens(entity.getInputTokens());
        sessionTask.setOutputTokens(entity.getOutputTokens());
        sessionTask.setDurationMs(entity.getDurationMs());
        sessionTask.setNumTurns(entity.getNumTurns());
        sessionTask.setResultText(entity.getResultText());
        sessionTask.setErrorMessage(entity.getErrorMessage());
        sessionTask.setSource(entity.getSource());
        sessionTask.setLastAckedSeq(entity.getLastAckedSeq());
        sessionTask.setLastAliveAt(entity.getLastAliveAt());
        sessionTask.setLastOutputAt(entity.getLastOutputAt());
        sessionTask.setCreatedAt(entity.getCreatedAt());
        sessionTask.setUpdatedAt(entity.getUpdatedAt());
        sessionTask.setTaskStateJson(buildCodexTaskStateJson(entity, sessionTask.getTaskStateJson()));
        sessionTaskRepository.save(sessionTask);
    }

    private void syncSessionProjection(CodexTaskEntity entity) {
        if (sessionEntityRepository == null || entity.getSessionId() == null || entity.getSessionId().isBlank()) {
            return;
        }
        String agentId = resolveLogicalAgentId(entity);
        String providerType = resolveProviderType(entity);

        SessionEntity session = sessionEntityRepository.findById(entity.getSessionId())
                .orElseGet(() -> createSessionProjection(entity));
        session.setUserId(firstNonBlank(session.getUserId(), entity.getUserId()));
        session.setTenantId(firstNonBlank(session.getTenantId(), entity.getTenantId()));
        session.setAgentId(firstNonBlank(session.getAgentId(), agentId));
        validateSessionProviderAffinity(session, providerType);
        session.setProviderType(providerType);
        session.setStatus(firstNonBlank(session.getStatus(), "ACTIVE"));
        session.setCurrentWorkerId(firstNonBlank(entity.getWorkerId(), session.getCurrentWorkerId()));
        session.setCurrentDirectoryId(firstNonBlank(entity.getDirectoryId(), session.getCurrentDirectoryId()));
        session.setLatestTaskId(entity.getTaskId());
        session.setLatestModel(firstNonBlank(entity.getModel(), session.getLatestModel()));
        session.setLastActivityAt(firstNonNull(entity.getUpdatedAt(), entity.getLastAliveAt(), LocalDateTime.now()));
        session.setInteractionState(deriveInteractionState(entity.getStatus()));
        Map<String, Object> providerStateValues = new LinkedHashMap<>();
        putIfNotBlank(providerStateValues, ProviderStateCodec.FIELD_CODEX_THREAD_ID, entity.getCodexThreadId());
        putIfNotBlank(providerStateValues, ProviderStateCodec.FIELD_CODEX_RUNTIME_ID, entity.getRuntimeId());
        putIfNotNull(providerStateValues, ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION,
                entity.getRuntimeRevision());
        putIfNotBlank(providerStateValues, ProviderStateCodec.FIELD_CODEX_RUNTIME_TYPE, entity.getRuntimeType());
        putIfNotBlank(providerStateValues, ProviderStateCodec.FIELD_CODEX_RUNTIME_INSTANCE_ID,
                entity.getRuntimeInstanceId());
        putIfNotNull(providerStateValues, ProviderStateCodec.FIELD_CODEX_ROUTING_EPOCH, entity.getRoutingEpoch());
        if (isCodexBizProvider(providerType)) {
            putIfNotBlank(providerStateValues, ProviderStateCodec.FIELD_CODEX_HOME_KEY, entity.getCodexHomeKey());
            putIfNotBlank(providerStateValues, ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID,
                    firstNonBlank(entity.getPrivateAccountId(), entity.getCodexHomeKey()));
        }
        session.setProviderStateJson(ProviderStateCodec.mergeSessionValues(
                session.getProviderStateJson(),
                providerType,
                providerStateValues));
        sessionEntityRepository.save(session);
    }

    private SessionEntity createSessionProjection(CodexTaskEntity entity) {
        String agentId = resolveLogicalAgentId(entity);
        String providerType = resolveProviderType(entity);
        SessionEntity session = new SessionEntity();
        session.setId(entity.getSessionId());
        session.setUserId(entity.getUserId());
        session.setTenantId(entity.getTenantId());
        session.setAgentId(agentId);
        session.setProviderType(providerType);
        session.setStatus("ACTIVE");
        session.setInteractionState(deriveInteractionState(entity.getStatus()));
        session.setCurrentWorkerId(entity.getWorkerId());
        session.setCurrentDirectoryId(entity.getDirectoryId());
        session.setLastActivityAt(firstNonNull(entity.getUpdatedAt(), entity.getLastAliveAt(), LocalDateTime.now()));
        return session;
    }

    private String buildCodexTaskStateJson(CodexTaskEntity entity, String existingJson) {
        Map<String, Object> state = new LinkedHashMap<>();
        putIfNotBlank(state, ProviderStateCodec.FIELD_CODEX_THREAD_ID, entity.getCodexThreadId());
        putIfNotBlank(state, ProviderStateCodec.FIELD_CONTEXT_ID, entity.getContextId());
        putIfNotBlank(state, ProviderStateCodec.FIELD_CODEX_RUNTIME_ID, entity.getRuntimeId());
        putIfNotNull(state, ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION, entity.getRuntimeRevision());
        putIfNotBlank(state, ProviderStateCodec.FIELD_CODEX_RUNTIME_TYPE, entity.getRuntimeType());
        putIfNotBlank(state, ProviderStateCodec.FIELD_CODEX_RUNTIME_INSTANCE_ID, entity.getRuntimeInstanceId());
        putIfNotNull(state, ProviderStateCodec.FIELD_CODEX_ROUTING_EPOCH, entity.getRoutingEpoch());
        putIfNotNull(state, ProviderStateCodec.FIELD_CREATED_AT_EPOCH_MS, entity.getCreatedAtEpochMs());
        putIfNotBlank(state, ProviderStateCodec.FIELD_RUNTIME_ACCEPTANCE_STATE,
                entity.getRuntimeAcceptanceState());
        return ProviderStateCodec.mergeTaskValues(existingJson, resolveProviderType(entity), state);
    }

    private String resolveTaskContextId(CodexTaskEntity entity) {
        if (entity.getContextId() != null && !entity.getContextId().isBlank()) {
            return entity.getContextId();
        }
        if (sessionTaskRepository == null || entity.getTaskId() == null || entity.getTaskId().isBlank()) {
            return null;
        }
        return sessionTaskRepository.findByTaskId(entity.getTaskId())
                .map(SessionTaskEntity::getTaskStateJson)
                .map(json -> ProviderStateCodec.readStringOrNull(json, ProviderStateCodec.FIELD_CONTEXT_ID))
                .filter(contextId -> contextId != null && !contextId.isBlank())
                .orElse(null);
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private CodexRuntimeBinding resolveRuntimeBinding(String workerId, String model, String providerType,
                                                       String routingKey, String existingSessionId) {
        return resolveRuntimeBinding(workerId, model, providerType, routingKey, existingSessionId, Set.of());
    }

    private CodexRuntimeBinding resolveRuntimeBinding(String workerId, String model, String providerType,
                                                       String routingKey, String existingSessionId,
                                                       Set<String> requiredFeatures) {
        return resolveRuntimeBinding(
                workerId, model, providerType, routingKey, existingSessionId, requiredFeatures, false);
    }

    private CodexRuntimeBinding resolveRuntimeBinding(String workerId, String model, String providerType,
                                                       String routingKey, String existingSessionId,
                                                       Set<String> requiredFeatures,
                                                       boolean initializeRuntimeAffinity) {
        boolean appServerProvider = isCodexAppServerProvider(providerType);
        if (existingSessionId != null && !existingSessionId.isBlank() && !initializeRuntimeAffinity) {
            CodexRuntimeBinding sessionBinding = resolveExistingSessionBinding(existingSessionId, workerId);
            if (sessionBinding != null) {
                validateProviderRuntimeBinding(providerType, sessionBinding);
                if (appServerProvider) {
                    runtimeRegistryService.validateBoundRuntimeCapabilities(
                            sessionBinding, model, requiredFeatures);
                }
                return sessionBinding;
            }
            if (appServerProvider) {
                throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_MISSING",
                        "App-server session has no immutable runtime affinity");
            }
            return CodexRuntimeBinding.legacySdk(workerId);
        }
        if (!appServerProvider) {
            return CodexRuntimeBinding.legacySdk(workerId);
        }
        if (runtimeRegistryService == null) {
            String code = isUltraModel(model)
                    ? "CODEX_ULTRA_RUNTIME_UNAVAILABLE" : "CODEX_RUNTIME_UNAVAILABLE";
            throw new CodexRuntimeUnavailableException(code,
                    "Runtime registry is unavailable for the app-server provider");
        }
        CodexRuntimeBinding binding = runtimeRegistryService.selectForNewTask(
                workerId, model, providerType, routingKey, requiredFeatures);
        validateProviderRuntimeBinding(providerType, binding);
        return binding;
    }

    private void validatePreallocatedSessionForRuntimeInitialization(
            String sessionId, String userId, String tenantId, String providerType) {
        if (sessionEntityRepository == null) {
            throw new IllegalStateException("Session repository is unavailable for runtime affinity initialization");
        }
        SessionEntity session = sessionEntityRepository.findByIdAndUserIdForUpdate(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session not found or access denied: " + sessionId));
        if (!java.util.Objects.equals(tenantId, session.getTenantId())) {
            throw new IllegalArgumentException("Session not found or access denied: " + sessionId);
        }
        validateSessionProviderAffinity(session, providerType);
        boolean hasProviderState = !ProviderStateCodec.parseObject(session.getProviderStateJson()).isEmpty();
        boolean hasProviderTask = taskRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId).isPresent();
        boolean hasUnifiedTask = sessionTaskRepository != null
                && !sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc(sessionId).isEmpty();
        if (firstNonBlank(session.getCurrentWorkerId(), session.getLatestTaskId()) != null
                || hasProviderState || hasProviderTask || hasUnifiedTask) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_INITIALIZATION_REJECTED",
                    "Runtime affinity can only be initialized for a pristine preallocated session");
        }
    }

    private Set<String> runtimeRequirements(CodexTaskCreateCommand form, String providerType) {
        Set<String> features = new LinkedHashSet<>();
        if (form.getImages() != null && !form.getImages().isBlank()) features.add("images");
        if (form.getAttachments() != null && !form.getAttachments().isEmpty()) features.add("attachments");
        if (form.getOutputSchema() != null && !form.getOutputSchema().isEmpty()) features.add("output_schema");
        if (form.getDeveloperInstructions() != null && !form.getDeveloperInstructions().isBlank()) {
            features.add("developer_instructions");
        }
        if (form.getSandboxMode() != null && !form.getSandboxMode().isBlank()) features.add("sandbox");
        if (form.getNetworkAccessEnabled() != null) features.add("network");
        if (form.getWebSearchMode() != null && !form.getWebSearchMode().isBlank()) features.add("web");
        if (form.getAdditionalDirectories() != null && !form.getAdditionalDirectories().isEmpty()) {
            features.add("additional_directories");
        }
        if (form.getMaxTurns() != null && form.getMaxTurns() > 1) features.add("max_turns");
        if (form.getApprovalPolicy() != null && !form.getApprovalPolicy().isBlank()) {
            features.add("approval:" + form.getApprovalPolicy());
        }
        if (isCodexBizProvider(providerType)
                || (form.getBusinessRuntimeContext() != null && !form.getBusinessRuntimeContext().isEmpty())) {
            features.add("business_mcp");
        }
        return features;
    }

    private CodexRuntimeBinding resolveExistingSessionBinding(String sessionId, String workerId) {
        String legacySessionWorkerId = null;
        if (sessionEntityRepository != null) {
            SessionEntity session = sessionEntityRepository.findById(sessionId).orElse(null);
            if (session != null) {
                Map<String, Object> state = ProviderStateCodec.parseObject(session.getProviderStateJson());
                CodexRuntimeBinding binding = bindingFromState(state, workerId);
                if (binding != null) return binding;
                legacySessionWorkerId = session.getCurrentWorkerId();
            }
        }
        CodexRuntimeBinding taskBinding = taskRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId)
                .map(task -> bindingFromTask(task, workerId))
                .orElse(null);
        if (taskBinding != null) return taskBinding;
        if (legacySessionWorkerId != null && !legacySessionWorkerId.isBlank()) {
            validateLegacyWorkerAffinity(legacySessionWorkerId, workerId);
            return CodexRuntimeBinding.legacySdk(legacySessionWorkerId);
        }
        return null;
    }

    private CodexRuntimeBinding resolveThreadRuntimeBinding(String codexThreadId, String workerId,
                                                             String userId, String providerType) {
        CodexRuntimeBinding existing = taskRepository
                .findFirstByCodexThreadIdAndWorkerIdAndUserIdAndProviderTypeOrderByCreatedAtDesc(
                        codexThreadId, workerId, userId, providerType)
                .map(task -> bindingFromTask(task, workerId))
                .orElse(null);
        // Threads created before affinity columns were introduced belong to the
        // legacy SDK lane and must not move during rollout.
        return existing != null ? existing : CodexRuntimeBinding.legacySdk(workerId);
    }

    private CodexRuntimeBinding bindingFromState(Map<String, Object> state, String workerId) {
        String runtimeId = stringValue(state.get(ProviderStateCodec.FIELD_CODEX_RUNTIME_ID));
        String runtimeType = stringValue(state.get(ProviderStateCodec.FIELD_CODEX_RUNTIME_TYPE));
        if (runtimeId == null && runtimeType == null) return null;
        if (CodexRuntimeType.SDK_EXEC.name().equals(runtimeType)
                || (runtimeId != null && runtimeId.startsWith("legacy-sdk:"))) {
            if (runtimeId == null || !runtimeId.startsWith("legacy-sdk:")) {
                // Pre-affinity state must fall back to the latest persisted task so its
                // original physical Worker remains authoritative.
                return null;
            }
            String boundWorkerId = runtimeId.substring("legacy-sdk:".length());
            validateLegacyWorkerAffinity(boundWorkerId, workerId);
            return CodexRuntimeBinding.legacySdk(boundWorkerId);
        }
        if (runtimeId == null || runtimeRegistryService == null) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_INVALID",
                    "Session runtime affinity is incomplete: " + runtimeId);
        }
        Integer revision = integerValue(state.get(ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION));
        String instanceId = stringValue(state.get(ProviderStateCodec.FIELD_CODEX_RUNTIME_INSTANCE_ID));
        CodexRuntimeBinding binding = runtimeRegistryService.resolveBoundRuntime(
                runtimeId, revision, workerId, instanceId);
        validateBoundWorker(binding, workerId);
        return binding;
    }

    private CodexRuntimeBinding bindingFromTask(CodexTaskEntity task, String workerId) {
        if (task.getRuntimeId() == null || task.getRuntimeId().isBlank()) return null;
        if (CodexRuntimeType.SDK_EXEC.name().equals(task.getRuntimeType())
                || task.getRuntimeId().startsWith("legacy-sdk:")) {
            String boundWorkerId = task.getWorkerId();
            if (task.getRuntimeId().startsWith("legacy-sdk:")) {
                String encodedWorkerId = task.getRuntimeId().substring("legacy-sdk:".length());
                if (!encodedWorkerId.equals(boundWorkerId)) {
                    throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_INVALID",
                            "Legacy SDK runtime and task worker affinity disagree");
                }
            }
            validateLegacyWorkerAffinity(boundWorkerId, workerId);
            return CodexRuntimeBinding.legacySdk(boundWorkerId);
        }
        if (runtimeRegistryService == null) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_MISSING",
                    "Runtime registry is unavailable for bound session " + task.getSessionId());
        }
        CodexRuntimeBinding binding = runtimeRegistryService.resolveBoundRuntime(
                task.getRuntimeId(), task.getRuntimeRevision(), workerId, task.getRuntimeInstanceId());
        validateBoundWorker(binding, workerId);
        return binding;
    }

    private void validateBoundWorker(CodexRuntimeBinding binding, String workerId) {
        if (binding.getWorkerId() != null && !binding.getWorkerId().equals(workerId)) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_MISMATCH",
                    "Bound runtime belongs to another worker");
        }
    }

    private void validateLegacyWorkerAffinity(String boundWorkerId, String requestedWorkerId) {
        if (boundWorkerId == null || boundWorkerId.isBlank()) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_INVALID",
                    "Legacy SDK runtime affinity has no worker");
        }
        if (requestedWorkerId != null && !requestedWorkerId.equals(boundWorkerId)) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_MISMATCH",
                    "Legacy SDK runtime belongs to another worker");
        }
    }

    private void applyRuntimeBinding(CodexTaskEntity entity, CodexRuntimeBinding binding) {
        validateBoundWorker(binding, entity.getWorkerId());
        validateProviderRuntimeBinding(entity.getProviderType(), binding);
        entity.setRuntimeId(binding.getRuntimeId());
        entity.setRuntimeRevision(binding.getRuntimeRevision());
        entity.setRuntimeType(binding.getRuntimeType().name());
        entity.setRuntimeInstanceId(binding.getInstanceId());
        entity.setRoutingEpoch(binding.getRoutingEpoch());
        if (binding.getRuntimeType() == CodexRuntimeType.APP_SERVER) {
            entity.setRuntimeAcceptanceState("PREPARED");
        }
    }

    private boolean isUltraModel(String model) {
        if (model == null) return false;
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        return "codex-ultra".equals(normalized) || normalized.endsWith(":ultra");
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private void requireUserInputPersistence() {
        if (sessionTaskRepository == null) {
            throw interactionError("CODEX_USER_INPUT_STATE_UNAVAILABLE");
        }
        if (runtimeRegistryService == null) {
            throw interactionError("CODEX_USER_INPUT_RUNTIME_AFFINITY_LOST");
        }
    }

    private SessionTaskEntity requireSessionTask(CodexTaskEntity entity) {
        return sessionTaskRepository.findByTaskIdForUpdate(entity.getTaskId())
                .orElseThrow(() -> interactionError("CODEX_USER_INPUT_STATE_MISSING"));
    }

    private Map<String, Object> pendingUserInput(SessionTaskEntity sessionTask) {
        Object value = ProviderStateCodec.parseObject(sessionTask.getTaskStateJson()).get(USER_INPUT_STATE_KEY);
        return objectMap(value);
    }

    private void savePendingUserInput(SessionTaskEntity sessionTask, CodexTaskEntity entity,
                                      Map<String, Object> pending) {
        sessionTask.setTaskStateJson(ProviderStateCodec.mergeTaskValue(
                sessionTask.getTaskStateJson(), resolveProviderType(entity), USER_INPUT_STATE_KEY, pending));
        sessionTaskRepository.save(sessionTask);
    }

    private void markPendingResolved(SessionTaskEntity sessionTask, CodexTaskEntity entity,
                                     Map<String, Object> pending, String reason) {
        Map<String, Object> resolved = new LinkedHashMap<>(pending);
        resolved.put("state", "RESOLVED");
        resolved.put("resolved_reason", reason);
        resolved.put("resolved_at", LocalDateTime.now().toString());
        savePendingUserInput(sessionTask, entity, resolved);
    }

    private Map<String, Object> sanitizePendingUserInput(CodexTaskEntity entity,
                                                         Map<String, Object> projection) {
        Map<String, Object> source = objectMap(projection != null
                ? projection.get("pending_interaction") : null);
        if (source.isEmpty()) {
            source = projection != null ? new LinkedHashMap<>(projection) : Map.of();
        }
        if (!Integer.valueOf(1).equals(integerValue(source.get("contract_version")))) {
            throw interactionError("CODEX_USER_INPUT_CONTRACT_UNSUPPORTED");
        }
        String method = boundedRequiredString(source.get("method"), "method", 128);
        if (!USER_INPUT_METHOD.equals(method)) {
            throw interactionError("CODEX_USER_INPUT_METHOD_UNSUPPORTED");
        }
        Object requestId = sanitizeRequestId(source.get("request_id"));
        String threadId = boundedRequiredString(source.get("thread_id"), "thread_id", 256);
        String turnId = boundedRequiredString(source.get("turn_id"), "turn_id", 256);
        String itemId = boundedRequiredString(source.get("item_id"), "item_id", 256);
        String runtimeInstanceId = boundedRequiredString(
                source.get("runtime_instance_id"), "runtime_instance_id", 128);
        if (entity.getCodexThreadId() != null && !entity.getCodexThreadId().isBlank()
                && !entity.getCodexThreadId().equals(threadId)) {
            throw interactionError("CODEX_USER_INPUT_THREAD_MISMATCH");
        }

        Object rawQuestions = source.get("questions");
        if (!(rawQuestions instanceof List<?> questions)
                || questions.isEmpty() || questions.size() > MAX_USER_INPUT_QUESTIONS) {
            throw interactionError("CODEX_USER_INPUT_QUESTIONS_INVALID");
        }
        List<Map<String, Object>> sanitizedQuestions = new ArrayList<>();
        Set<String> questionIds = new LinkedHashSet<>();
        for (Object rawQuestion : questions) {
            Map<String, Object> question = objectMap(rawQuestion);
            String id = boundedRequiredString(question.get("id"), "question.id", 256);
            if (!questionIds.add(id)) {
                throw interactionError("CODEX_USER_INPUT_QUESTION_ID_DUPLICATE");
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            sanitized.put("id", id);
            sanitized.put("header", boundedRequiredString(question.get("header"), "question.header", 64));
            sanitized.put("question", boundedRequiredDisplayString(
                    question.get("question"), "question.question", 4_096));
            List<Map<String, Object>> options = sanitizeUserInputOptions(question.get("options"));
            if (!options.isEmpty()) {
                sanitized.put("options", options);
            }
            sanitized.put("is_other", booleanValue(question.get("is_other")));
            sanitized.put("is_secret", booleanValue(question.get("is_secret")));
            sanitizedQuestions.add(sanitized);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contract_version", 1);
        result.put("request_id", requestId);
        result.put("method", method);
        result.put("thread_id", threadId);
        result.put("turn_id", turnId);
        result.put("item_id", itemId);
        result.put("questions", sanitizedQuestions);
        Integer autoResolutionMs = boundedAutoResolutionMs(source.get("auto_resolution_ms"));
        if (autoResolutionMs != null) {
            result.put("auto_resolution_ms", autoResolutionMs);
        }
        result.put("runtime_instance_id", runtimeInstanceId);
        String createdAt = boundedOptionalString(source.get("created_at"), "created_at", 64);
        if (createdAt != null) {
            result.put("created_at", createdAt);
        }
        return result;
    }

    private List<Map<String, Object>> sanitizeUserInputOptions(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> options) || options.size() > 20) {
            throw interactionError("CODEX_USER_INPUT_OPTIONS_INVALID");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object rawOption : options) {
            Map<String, Object> option = objectMap(rawOption);
            if (option.isEmpty() && rawOption instanceof String label) {
                option = Map.of("label", label);
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            sanitized.put("label", boundedRequiredString(option.get("label"), "option.label", 256));
            sanitized.put("description", boundedAllowEmptyString(
                    option.get("description"), "option.description", 2_048));
            result.add(sanitized);
        }
        return result;
    }

    private Map<String, Object> confirmationPayload(String taskId, Map<String, Object> pending) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", externalRequestToken(taskId, pending.get("request_id")));
        payload.put("interactionType", "user_input");
        payload.put("method", pending.get("method"));
        List<Map<String, Object>> projectedQuestions = new ArrayList<>();
        Object rawQuestions = pending.get("questions");
        if (rawQuestions instanceof List<?> questions) {
            for (Object rawQuestion : questions) {
                Map<String, Object> question = objectMap(rawQuestion);
                Map<String, Object> projected = new LinkedHashMap<>();
                projected.put("id", question.get("id"));
                projected.put("header", question.get("header"));
                projected.put("question", question.get("question"));
                if (question.containsKey("options")) {
                    projected.put("options", question.get("options"));
                }
                projected.put("isOther", booleanValue(question.get("is_other")));
                projected.put("isSecret", booleanValue(question.get("is_secret")));
                projected.put("multiSelect", false);
                projectedQuestions.add(projected);
            }
        }
        payload.put("questions", projectedQuestions);
        if (pending.containsKey("auto_resolution_ms")) {
            payload.put("autoResolutionMs", pending.get("auto_resolution_ms"));
        }
        return payload;
    }

    private NormalizedUserInputAnswers normalizeUserInputAnswers(Map<String, Object> pending,
                                                                  Object rawAnswers) {
        if (!(rawAnswers instanceof Map<?, ?> answerMap)) {
            throw interactionError("CODEX_USER_INPUT_ANSWERS_INVALID");
        }
        Map<String, Boolean> expected = new LinkedHashMap<>();
        Object rawQuestions = pending.get("questions");
        if (rawQuestions instanceof List<?> questions) {
            for (Object rawQuestion : questions) {
                Map<String, Object> question = objectMap(rawQuestion);
                expected.put(stringValue(question.get("id")), booleanValue(question.get("is_secret")));
            }
        }
        Map<String, Object> supplied = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : answerMap.entrySet()) {
            if (!(entry.getKey() instanceof String key) || supplied.put(key, entry.getValue()) != null) {
                throw interactionError("CODEX_USER_INPUT_ANSWERS_INVALID");
            }
        }
        if (!supplied.keySet().equals(expected.keySet())) {
            throw interactionError("CODEX_USER_INPUT_QUESTION_MISMATCH");
        }

        Map<String, List<String>> workerAnswers = new LinkedHashMap<>();
        Map<String, String> historyAnswers = new LinkedHashMap<>();
        boolean containsSecret = false;
        for (Map.Entry<String, Boolean> question : expected.entrySet()) {
            String answer = singleUserInputAnswer(supplied.get(question.getKey()));
            workerAnswers.put(question.getKey(), List.of(answer));
            historyAnswers.put(question.getKey(), answer);
            containsSecret |= question.getValue();
        }
        return new NormalizedUserInputAnswers(workerAnswers, historyAnswers, containsSecret);
    }

    private String singleUserInputAnswer(Object value) {
        String answer;
        if (value instanceof String text) {
            answer = text;
        } else if (value instanceof Collection<?> values && values.size() == 1
                && values.iterator().next() instanceof String text) {
            answer = text;
        } else {
            throw interactionError("CODEX_USER_INPUT_ANSWER_CARDINALITY_INVALID");
        }
        if (answer.isEmpty() || answer.length() > 16_384) {
            throw interactionError("CODEX_USER_INPUT_ANSWER_INVALID");
        }
        return answer;
    }

    private void validateUserInputResponse(Map<String, Object> result, String workerTaskId,
                                           Object wireRequestId) {
        if (result == null
                || !workerTaskId.equals(stringValue(result.get("task_id")))
                || !"running".equalsIgnoreCase(stringValue(result.get("status")))
                || !sameWireRequestId(wireRequestId, result.get("request_id"))) {
            throw interactionError("CODEX_USER_INPUT_RESPONSE_INVALID");
        }
    }

    private CodexWorkerClient.UserInputResponseException findUserInputResponseError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CodexWorkerClient.UserInputResponseException responseError) {
                return responseError;
            }
            current = current.getCause();
        }
        return null;
    }

    private CodexWorkerClient.RuntimeInstanceProofException findRuntimeProofError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CodexWorkerClient.RuntimeInstanceProofException proofError) {
                return proofError;
            }
            current = current.getCause();
        }
        return null;
    }

    private Object sanitizeRequestId(Object value) {
        if (value instanceof String text && !text.isBlank() && text.length() <= 256
                && text.chars().noneMatch(Character::isISOControl)) {
            return text;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (number >= -9_007_199_254_740_991L && number <= 9_007_199_254_740_991L) {
                return number;
            }
        }
        throw interactionError("CODEX_USER_INPUT_REQUEST_ID_INVALID");
    }

    private Integer boundedAutoResolutionMs(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) {
            throw interactionError("CODEX_USER_INPUT_AUTO_RESOLUTION_INVALID");
        }
        long milliseconds = ((Number) value).longValue();
        if (milliseconds < 60_000L || milliseconds > 240_000L) {
            throw interactionError("CODEX_USER_INPUT_AUTO_RESOLUTION_INVALID");
        }
        return (int) milliseconds;
    }

    private String externalRequestToken(String taskId, Object value) {
        Object sanitized = sanitizeRequestId(value);
        String typed = sanitized instanceof String text ? "string:" + text : "number:" + sanitized;
        return "task:" + taskId + ":" + typed;
    }

    private boolean sameWireRequestId(Object left, Object right) {
        try {
            Object normalizedLeft = sanitizeRequestId(left);
            Object normalizedRight = sanitizeRequestId(right);
            return normalizedLeft.getClass().equals(normalizedRight.getClass())
                    && normalizedLeft.equals(normalizedRight);
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private String boundedRequiredString(Object value, String field, int maxLength) {
        String text = boundedOptionalString(value, field, maxLength);
        if (text == null) {
            throw interactionError("CODEX_USER_INPUT_FIELD_INVALID: " + field);
        }
        return text;
    }

    private String boundedOptionalString(Object value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength
                || text.chars().anyMatch(Character::isISOControl)) {
            throw interactionError("CODEX_USER_INPUT_FIELD_INVALID: " + field);
        }
        return text;
    }

    private String boundedRequiredDisplayString(Object value, String field, int maxLength) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength
                || text.chars().anyMatch(this::isUnsafeDisplayControl)) {
            throw interactionError("CODEX_USER_INPUT_FIELD_INVALID: " + field);
        }
        return text;
    }

    private String boundedAllowEmptyString(Object value, String field, int maxLength) {
        if (!(value instanceof String text) || text.length() > maxLength
                || text.chars().anyMatch(this::isUnsafeDisplayControl)) {
            throw interactionError("CODEX_USER_INPUT_FIELD_INVALID: " + field);
        }
        return text;
    }

    private boolean isUnsafeDisplayControl(int character) {
        return Character.isISOControl(character)
                && character != '\n' && character != '\r' && character != '\t';
    }

    private boolean samePendingUserInput(Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> existingContract = new LinkedHashMap<>(existing);
        Map<String, Object> incomingContract = new LinkedHashMap<>(incoming);
        for (String transientKey : List.of(
                "state", "resolved_reason", "resolved_at", "request_id")) {
            existingContract.remove(transientKey);
            incomingContract.remove(transientKey);
        }
        return existingContract.equals(incomingContract);
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, entryValue) -> {
            if (key != null) {
                result.put(key.toString(), entryValue);
            }
        });
        return result;
    }

    private IllegalStateException interactionError(String code) {
        return new IllegalStateException(code);
    }

    private IllegalStateException interactionError(String code, Throwable cause) {
        return new IllegalStateException(code, cause);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> attachmentsParam(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeProviderType(String providerType) {
        String normalized = firstNonBlank(providerType, CODEX_PROVIDER_TYPE);
        if (CODEX_PROVIDER_TYPE.equals(normalized)
                || CODEX_APP_SERVER_PROVIDER_TYPE.equals(normalized)
                || CODEX_BIZ_PROVIDER_TYPE.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported Codex providerType: " + normalized);
    }

    private String requireProviderParams(String expectedProviderType, Map<String, Object> params) {
        if (params == null) {
            throw new IllegalArgumentException("Codex task params are required");
        }
        String expected = normalizeProviderType(expectedProviderType);
        for (String key : List.of("providerType", "provider_type")) {
            String requested = stringParam(params, key);
            if (requested != null && !expected.equals(normalizeProviderType(requested))) {
                throw new IllegalArgumentException("CODEX_TASK_PROVIDER_MISMATCH: provider route "
                        + expected + " cannot be overridden by " + requested);
            }
        }
        return expected;
    }

    private void requireTaskProvider(CodexTaskEntity entity, String expectedProviderType) {
        String expected = normalizeProviderType(expectedProviderType);
        if (entity == null || !expected.equals(resolveProviderType(entity))) {
            throw new IllegalArgumentException("Task not found: "
                    + (entity != null ? entity.getTaskId() : null));
        }
    }

    private boolean isCodexBizProvider(String providerType) {
        return CODEX_BIZ_PROVIDER_TYPE.equals(providerType);
    }

    private boolean isCodexAppServerProvider(String providerType) {
        return CODEX_APP_SERVER_PROVIDER_TYPE.equals(providerType);
    }

    private void validateModelConfigProvider(@Nullable String modelConfigId,
                                             @Nullable LlmModelConfigDTO modelConfig,
                                             String executionProviderType) {
        if (modelConfig == null) {
            return;
        }
        String backend = firstNonBlank(modelConfig.getWorkerBackend());
        if (backend == null) {
            if (isCodexAppServerProvider(executionProviderType)) {
                throw new IllegalArgumentException("MODEL_CONFIG_PROVIDER_MISMATCH: model config '"
                        + modelConfigId + "' has no app-server workerBackend");
            }
            return;
        }
        String modelProviderType = ProviderRouteRegistry.providerTypeForWorkerBackend(backend)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported workerBackend for model config '" + modelConfigId + "': " + backend));
        if (!ProviderRouteRegistry.isModelProviderCompatible(modelProviderType, executionProviderType)) {
            throw new IllegalArgumentException("MODEL_CONFIG_PROVIDER_MISMATCH: model config '"
                    + modelConfigId + "' routes to " + modelProviderType
                    + " but task routes to " + executionProviderType);
        }
    }

    private void validateProviderModel(String providerType, @Nullable String model) {
        if (!isCodexAppServerProvider(providerType) && isUltraModel(model)) {
            throw new IllegalArgumentException(
                    "CODEX_ULTRA_APP_SERVER_REQUIRED: Ultra requires codex-app-server-worker");
        }
    }

    private void validateProviderRuntimeBinding(String providerType, CodexRuntimeBinding binding) {
        if (binding == null) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_MISSING",
                    "Codex runtime binding is missing");
        }
        boolean appBinding = binding.getRuntimeType() == CodexRuntimeType.APP_SERVER;
        if (isCodexAppServerProvider(providerType) != appBinding) {
            throw new CodexRuntimeUnavailableException("CODEX_PROVIDER_RUNTIME_MISMATCH",
                    "Provider " + providerType + " cannot execute on runtime " + binding.getRuntimeType());
        }
    }

    private void validateExistingSessionProvider(String sessionId, String requestedProviderType) {
        if (sessionEntityRepository == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionEntityRepository.findById(sessionId).ifPresent(session ->
                validateSessionProviderAffinity(session, requestedProviderType));
    }

    private void validateSessionProviderAffinity(SessionEntity session, String requestedProviderType) {
        String existingProviderType = firstNonBlank(session.getProviderType());
        if (existingProviderType != null && !existingProviderType.equals(requestedProviderType)) {
            throw new IllegalArgumentException("SESSION_PROVIDER_MISMATCH: session " + session.getId()
                    + " is bound to " + existingProviderType
                    + " and cannot continue with " + requestedProviderType);
        }
    }

    private void normalizeAndValidateCodexBizHomeKey(CodexTaskCreateCommand form, String providerType) {
        if (!isCodexBizProvider(providerType)) {
            return;
        }
        String codexHomeKey = firstNonBlank(form.getCodexHomeKey(), form.getPrivateAccountId());
        if (codexHomeKey == null) {
            throw new IllegalArgumentException("codex-biz-worker requires codexHomeKey or privateAccountId");
        }
        form.setCodexHomeKey(codexHomeKey);
    }

    private boolean matchesProvider(CodexTaskEntity entity, String providerType) {
        return normalizeProviderType(providerType).equals(resolveProviderType(entity));
    }

    private List<CodexTaskEntity> filterTasksByProvider(List<CodexTaskEntity> tasks, String providerType) {
        return tasks.stream()
                .filter(entity -> matchesProvider(entity, providerType))
                .toList();
    }

    private String resolveProviderType(CodexTaskEntity entity) {
        if (entity == null) {
            return AGENT_ID;
        }
        String providerType = firstNonBlank(
                entity.getProviderType(),
                resolveSessionTaskProviderType(entity.getTaskId()),
                resolveSessionProviderType(entity.getSessionId()),
                AGENT_ID);
        providerType = normalizeProviderType(providerType);
        entity.setProviderType(providerType);
        return providerType;
    }

    private Map<String, String> resolveProviderTypes(List<CodexTaskEntity> entities) {
        Map<String, String> taskProviders = new LinkedHashMap<>();
        Set<String> unresolvedTaskIds = entities.stream()
                .filter(entity -> firstNonBlank(entity.getProviderType()) == null)
                .map(CodexTaskEntity::getTaskId)
                .filter(taskId -> taskId != null && !taskId.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (sessionTaskRepository != null && !unresolvedTaskIds.isEmpty()) {
            sessionTaskRepository.findByTaskIdIn(unresolvedTaskIds).forEach(sessionTask -> {
                String providerType = firstNonBlank(sessionTask.getProviderType());
                if (providerType != null) {
                    taskProviders.put(sessionTask.getTaskId(), providerType);
                }
            });
        }

        Set<String> unresolvedSessionIds = entities.stream()
                .filter(entity -> firstNonBlank(
                        entity.getProviderType(), taskProviders.get(entity.getTaskId())) == null)
                .map(CodexTaskEntity::getSessionId)
                .filter(sessionId -> sessionId != null && !sessionId.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> sessionProviders = new LinkedHashMap<>();
        if (sessionEntityRepository != null && !unresolvedSessionIds.isEmpty()) {
            sessionEntityRepository.findAllById(unresolvedSessionIds).forEach(session -> {
                String providerType = firstNonBlank(session.getProviderType());
                if (providerType != null) {
                    sessionProviders.put(session.getId(), providerType);
                }
            });
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        entities.forEach(entity -> {
            String providerType = normalizeProviderType(firstNonBlank(
                    entity.getProviderType(),
                    taskProviders.get(entity.getTaskId()),
                    sessionProviders.get(entity.getSessionId()),
                    AGENT_ID));
            entity.setProviderType(providerType);
            resolved.put(entity.getTaskId(), providerType);
        });
        return resolved;
    }

    private String resolveSessionTaskProviderType(String taskId) {
        if (sessionTaskRepository == null || taskId == null || taskId.isBlank()) {
            return null;
        }
        return sessionTaskRepository.findByTaskId(taskId)
                .map(SessionTaskEntity::getProviderType)
                .filter(providerType -> providerType != null && !providerType.isBlank())
                .orElse(null);
    }

    private String resolveSessionProviderType(String sessionId) {
        if (sessionEntityRepository == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionEntityRepository.findById(sessionId)
                .map(SessionEntity::getProviderType)
                .filter(providerType -> providerType != null && !providerType.isBlank())
                .orElse(null);
    }

    private void applyCodexBizParams(CodexTaskCreateCommand form, Map<String, Object> params) {
        Map<String, Object> policy = mapParam(firstPresent(params, "codexPolicy", "codex_policy"));
        form.setCodexHomeKey(firstNonBlank(
                stringParam(params, "codexHomeKey"),
                stringParam(params, "codex_home_key"),
                stringParam(params, "privateAccountId"),
                stringParam(params, "private_account_id")));
        form.setDeveloperInstructions(firstNonBlank(
                stringParam(params, "developerInstructions"),
                stringParam(params, "developer_instructions")));
        form.setBusinessRuntimeContext(mapParam(firstPresent(params,
                "businessRuntimeContext", "business_runtime_context", "runtimeContext", "runtime_context")));
        form.setOutputSchema(mapParam(firstPresent(params,
                "outputSchema", "output_schema", "expectedOutputSchema", "expected_output_schema")));
        form.setCodexConfig(mapParam(firstPresent(params, "codexConfig", "codex_config")));
        form.setSandboxMode(firstNonBlank(
                stringParam(params, "sandboxMode"),
                stringParam(params, "sandbox_mode"),
                stringMap(policy, "sandboxMode"),
                stringMap(policy, "sandbox_mode")));
        form.setApprovalPolicy(firstNonBlank(
                stringParam(params, "approvalPolicy"),
                stringParam(params, "approval_policy"),
                stringMap(policy, "approvalPolicy"),
                stringMap(policy, "approval_policy")));
        form.setNetworkAccessEnabled(firstNonNull(
                booleanParam(firstPresent(params, "networkAccessEnabled", "network_access_enabled")),
                booleanParam(firstPresent(policy, "networkAccessEnabled", "network_access_enabled"))));
        form.setWebSearchMode(firstNonBlank(
                stringParam(params, "webSearchMode"),
                stringParam(params, "web_search_mode"),
                stringMap(policy, "webSearchMode"),
                stringMap(policy, "web_search_mode")));
        form.setAdditionalDirectories(stringListParam(firstPresent(params, "additionalDirectories", "additional_directories")));
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapParam(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private String stringMap(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private Boolean booleanParam(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    private List<String> stringListParam(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(text -> !text.isBlank())
                    .toList();
        }
        return null;
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void validateExistingSession(String userId, String sessionId) {
        if (sessionEntityRepository != null) {
            SessionEntity entity = sessionEntityRepository.findById(sessionId).orElse(null);
            if (entity != null) {
                if (!userId.equals(entity.getUserId())) {
                    throw new IllegalArgumentException("Session not found or access denied: " + sessionId);
                }
                return;
            }
        }
        if (sessionManager != null) {
            Session session = sessionManager.getSession(sessionId);
            if (session != null && userId.equals(session.getUserId())) {
                return;
            }
        }
        throw new IllegalArgumentException("Session not found or access denied: " + sessionId);
    }

    @Override
    @Transactional
    public Object rewindTask(String taskId, String userId, Map<String, Object> params) {
        return rewindTaskForProvider(CODEX_PROVIDER_TYPE, taskId, userId, params);
    }

    @Transactional
    public Object rewindTaskForProvider(String providerType, String taskId, String userId,
                                        Map<String, Object> params) {
        CodexTaskEntity task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!userId.equals(task.getUserId())) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        requireTaskProvider(task, providerType);
        if ("RUNNING".equals(task.getStatus()) || "AWAITING_PERMISSION".equals(task.getStatus())
                || "AWAITING_INPUT".equals(task.getStatus()) || "CANCEL_REQUESTED".equals(task.getStatus())) {
            throw new IllegalStateException("Cannot rewind a running task");
        }

        String mode = params != null && params.get("mode") != null
                ? params.get("mode").toString()
                : "conversation_fork";
        if (!"conversation_fork".equals(mode)) {
            throw new UnsupportedOperationException("Codex only supports conversation rewind; file rewind is not supported");
        }
        if (task.getSessionId() == null || task.getSessionId().isBlank()) {
            throw new IllegalArgumentException("Task has no Navigator session ID");
        }

        int turnIndex = extractTurnIndex(params);
        String userPrompt = findUserPromptAtTurn(task.getSessionId(), turnIndex);
        truncateSessionMessagesQuietly(task.getSessionId(), turnIndex);
        clearCodexThreadId(task.getSessionId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "rewound");
        result.put("taskId", taskId);
        result.put("userPrompt", userPrompt != null ? userPrompt : "");
        result.put("turnIndex", turnIndex);
        result.put("codexThreadId", null);
        return result;
    }

    private int extractTurnIndex(Map<String, Object> params) {
        if (params != null && params.get("turnIndex") instanceof Number n && n.intValue() > 0) {
            return n.intValue();
        }
        return 1;
    }

    private String findUserPromptAtTurn(String sessionId, int turnIndex) {
        if (sessionManager == null) {
            return "";
        }
        try {
            int userTurn = 0;
            for (Message message : sessionManager.getAllMessages(sessionId)) {
                if (message != null && message.getRole() == MessageRole.USER) {
                    userTurn++;
                    if (userTurn == turnIndex) {
                        return message.getContent();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to find Codex rewind prompt for session {} turn {}: {}",
                    sessionId, turnIndex, e.getMessage());
        }
        return "";
    }

    private void truncateSessionMessagesQuietly(String sessionId, int fromUserTurnIndex) {
        if (sessionManager == null) {
            return;
        }
        try {
            int deleted = sessionManager.truncateMessagesFromTurn(sessionId, fromUserTurnIndex);
            log.info("Codex platform conversation rewind: sessionId={}, turn={}, deletedMessages={}",
                    sessionId, fromUserTurnIndex, deleted);
        } catch (Exception e) {
            log.warn("Failed to truncate Codex session {} from user turn {}: {}",
                    sessionId, fromUserTurnIndex, e.getMessage());
        }
    }

    private void clearCodexThreadId(String sessionId) {
        if (sessionEntityRepository == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionEntityRepository.findById(sessionId).ifPresent(session -> {
            session.setProviderStateJson(ProviderStateCodec.mergeSessionValue(
                    session.getProviderStateJson(),
                    firstNonBlank(session.getProviderType(), CODEX_PROVIDER_TYPE),
                    ProviderStateCodec.FIELD_CODEX_THREAD_ID,
                    null));
            sessionEntityRepository.save(session);
        });
    }

    private String resolveLogicalAgentId(@Nullable String requestedAgentId, @Nullable String existingSessionId) {
        if (requestedAgentId != null && !requestedAgentId.isBlank()) {
            return requestedAgentId;
        }
        if (existingSessionId != null && !existingSessionId.isBlank()) {
            String sessionAgentId = resolveSessionAgentId(existingSessionId);
            if (sessionAgentId != null && !sessionAgentId.isBlank()) {
                return sessionAgentId;
            }
        }
        // 没有真实逻辑 Agent 时返回 null，不再回退到 provider 常量（需求 26 约束）
        return null;
    }

    private String resolveLogicalAgentId(CodexTaskEntity entity) {
        if (entity.getResolvedAgentId() != null && !entity.getResolvedAgentId().isBlank()) {
            return entity.getResolvedAgentId();
        }
        if (sessionTaskRepository != null) {
            String sessionTaskAgentId = sessionTaskRepository.findByTaskId(entity.getTaskId())
                    .map(SessionTaskEntity::getAgentId)
                    .orElse(null);
            if (sessionTaskAgentId != null && !sessionTaskAgentId.isBlank()) {
                return sessionTaskAgentId;
            }
        }
        if (entity.getSessionId() != null && !entity.getSessionId().isBlank()) {
            String sessionAgentId = resolveSessionAgentId(entity.getSessionId());
            if (sessionAgentId != null && !sessionAgentId.isBlank()) {
                return sessionAgentId;
            }
        }
        // 没有真实逻辑 Agent 时返回 null，不再回退到 provider 常量（需求 26 约束）
        return null;
    }

    private String resolveSessionAgentId(String sessionId) {
        if (sessionEntityRepository != null) {
            return sessionEntityRepository.findById(sessionId)
                    .map(SessionEntity::getAgentId)
                    .orElse(null);
        }
        return null;
    }

    private String resolveSessionId(String userId, String tenantId, String prompt,
                                     String existingSessionId, String agentId, String providerType) {
        if (existingSessionId == null || existingSessionId.isBlank()) {
            return createPlatformSession(userId, tenantId, prompt, agentId, providerType);
        }

        if (sessionManager == null) {
            log.warn("SessionManager not available, reusing Codex sessionId without persisting message: {}", existingSessionId);
            return existingSessionId;
        }

        sessionManager.addMessage(existingSessionId, Message.builder()
                .sessionId(existingSessionId)
                .role(MessageRole.USER)
                .content(prompt)
                .build());
        return existingSessionId;
    }

    /**
     * 创建平台 SessionEntity（补齐 Codex 之前缺失的会话记录）
     */
    private String createPlatformSession(String userId, String tenantId, String prompt,
                                         String agentId, String providerType) {
        if (sessionManager == null) {
            log.warn("SessionManager not available, falling back to IdGenerator for Codex sessionId");
            return IdGenerator.shortId();
        }
        String title = prompt != null && prompt.length() > 100 ? prompt.substring(0, 100) : prompt;
        String sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(userId)
                .tenantId(tenantId)
                .agentId(agentId)
                .providerType(providerType)
                .taskName(title)
                .build());
        // 记录用户 prompt 到会话消息
        sessionManager.addMessage(sessionId, Message.builder()
                .sessionId(sessionId)
                .role(MessageRole.USER)
                .content(prompt)
                .build());
        return sessionId;
    }

    /** Codex auth 解析结果 */
    private record CodexAuthResult(String apiKey, String baseUrl, Map<String, String> envVars) {
        static final CodexAuthResult EMPTY = new CodexAuthResult(null, null, null);
    }

    /**
     * 解析 Codex auth 配置：apiKey + baseUrl + envVars。
     * <p>
     * 两种模式：
     * - API Key 模式：modelConfig 配置了 apiKey → 解密返回，baseUrl/envVars 可选
     * - Subscription 模式：modelConfig 无 apiKey → Worker 使用本地 ~/.codex/auth.json
     * <p>
     * envVars 用于传递 Codex CLI 配置（如 model_context_window、model_auto_compact_token_limit）
     */
    private CodexAuthResult resolveCodexAuth(String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank() || llmModelManager == null) {
            return CodexAuthResult.EMPTY;
        }
        try {
            var modelConfig = llmModelManager.getModelConfig(modelConfigId).orElse(null);
            if (modelConfig == null) {
                return CodexAuthResult.EMPTY;
            }
            String apiKey = llmModelManager.getDecryptedApiKey(modelConfigId);
            String baseUrl = modelConfig.getBaseUrl();
            Map<String, String> envVars = modelConfig.getEnvVars();
            return new CodexAuthResult(apiKey, baseUrl, envVars);
        } catch (Exception e) {
            log.warn("Failed to resolve Codex auth from modelConfigId={}: {}", modelConfigId, e.getMessage());
            return CodexAuthResult.EMPTY;
        }
    }

    private String resolveEffectiveModelConfigId(String explicitModelConfigId, @Nullable String agentId) {
        if (explicitModelConfigId != null && !explicitModelConfigId.isBlank()) {
            return explicitModelConfigId;
        }
        if (agentId == null || agentId.isBlank() || codingAgentRepository == null) {
            return null;
        }
        CodingAgentEntity agentEntity = codingAgentRepository.findByAgentId(agentId).orElse(null);
        if (agentEntity == null) {
            return null;
        }
        String defaultModelConfigId = agentEntity.getDefaultModelConfigId();
        return (defaultModelConfigId == null || defaultModelConfigId.isBlank()) ? null : defaultModelConfigId;
    }

    private String resolveModelConfigSource(String explicitModelConfigId, @Nullable String agentId) {
        if (explicitModelConfigId != null && !explicitModelConfigId.isBlank()) {
            return "request";
        }
        if (agentId == null || agentId.isBlank() || codingAgentRepository == null) {
            return "none";
        }
        CodingAgentEntity agentEntity = codingAgentRepository.findByAgentId(agentId).orElse(null);
        if (agentEntity == null) {
            return "none";
        }
        String defaultModelConfigId = agentEntity.getDefaultModelConfigId();
        return (defaultModelConfigId == null || defaultModelConfigId.isBlank()) ? "none" : "agent-default";
    }

    private LlmModelConfigDTO validateAndResolveModelConfig(@Nullable String modelConfigId, String workerId) {
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return null;
        }
        if (llmModelManager == null) {
            throw new IllegalStateException(
                    "LLM model manager is unavailable for modelConfigId=" + modelConfigId);
        }
        llmModelManager.validateModelAccessForWorker(modelConfigId, workerId);
        return llmModelManager.getModelConfig(modelConfigId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "LLM model config not found: " + modelConfigId));
    }

    private ModelResolution resolveEffectiveModel(String explicitModel, @Nullable String agentId,
                                                   @Nullable LlmModelConfigDTO modelConfig) {
        if (explicitModel != null && !explicitModel.isBlank()) {
            return new ModelResolution(explicitModel, "request");
        }
        if (agentId != null && !agentId.isBlank() && codingAgentRepository != null) {
            CodingAgentEntity agentEntity = codingAgentRepository.findByAgentId(agentId).orElse(null);
            if (agentEntity != null && agentEntity.getDefaultModel() != null && !agentEntity.getDefaultModel().isBlank()) {
                return new ModelResolution(agentEntity.getDefaultModel(), "agent-default");
            }
        }
        if (modelConfig == null) {
            return new ModelResolution(null, "none");
        }
        String model = modelConfig.getModelName();
        if (model != null && model.isBlank()) {
            model = null;
        }
        return new ModelResolution(model, model != null ? "model-config" : "none");
    }

    private void validateEffectiveModelGrant(@Nullable String model, @Nullable String modelConfigId,
                                             @Nullable LlmModelConfigDTO modelConfig) {
        if (model == null || model.isBlank()) {
            return;
        }
        String requestedModel = model.trim();
        if (isUnsupportedKnownCodexCatalogModel(requestedModel)) {
            throw new IllegalArgumentException("Codex model '" + requestedModel
                    + "' is not supported by the current model catalog");
        }
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return;
        }
        String normalizedModel = requestedModel.toLowerCase(Locale.ROOT);
        Optional<String> catalogModel = normalizeKnownCodexCatalogModel(requestedModel);
        boolean futureGatedModel = catalogModel.isEmpty() && isGatedCodexModel(normalizedModel);
        if (catalogModel.isEmpty() && !futureGatedModel) {
            return;
        }

        List<String> availableModels = modelConfig != null ? modelConfig.getAvailableModels() : null;
        if (availableModels == null || availableModels.isEmpty()) {
            return;
        }
        boolean granted = availableModels.stream()
                .filter(allowedModel -> allowedModel != null && !allowedModel.isBlank())
                .map(String::trim)
                .anyMatch(allowedModel -> catalogModel
                        .map(value -> normalizeKnownCodexCatalogModel(allowedModel)
                                .map(value::equals)
                                .orElse(false))
                        .orElseGet(() -> requestedModel.equals(allowedModel)));
        if (!granted) {
            throw new IllegalArgumentException("Codex model '" + requestedModel
                    + "' requires an explicit availableModels grant in model config '"
                    + modelConfigId + "'");
        }
    }

    private boolean isGatedCodexModel(String normalizedModel) {
        return normalizedModel.endsWith(":max")
                || normalizedModel.endsWith(":ultra");
    }

    private boolean isUnsupportedKnownCodexCatalogModel(String model) {
        String normalized = model.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        int separator = normalized.lastIndexOf(':');
        if (separator <= 0 || !"ultra".equals(normalized.substring(separator + 1))) {
            return false;
        }
        String base = normalized.substring(0, separator);
        return "codex-luna".equals(base) || "gpt-5.6-luna".equals(base);
    }

    private Optional<String> normalizeKnownCodexCatalogModel(String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        String legacy = CODEX_LEGACY_MODEL_VALUES.get(normalized);
        if (legacy != null) {
            return Optional.of(legacy);
        }

        int separator = normalized.lastIndexOf(':');
        String base = separator > 0 ? normalized.substring(0, separator) : normalized;
        String effort = separator > 0 ? normalized.substring(separator + 1) : "medium";
        String fixedLegacyAlias = CODEX_LEGACY_MODEL_VALUES.get(base);
        if (fixedLegacyAlias != null && !"codex-latest".equals(base)
                && !"codex-terra".equals(base) && !"codex-luna".equals(base)) {
            return Optional.of(fixedLegacyAlias);
        }
        if ("extra-high".equals(effort)) {
            effort = "xhigh";
        }
        if (!CODEX_CATALOG_EFFORTS.contains(effort)) {
            return Optional.empty();
        }

        String family;
        if ("codex-latest".equals(base) || "codex-terra".equals(base) || "codex-luna".equals(base)) {
            family = base;
        } else {
            family = CODEX_REAL_MODEL_FAMILIES.get(base);
        }
        if (family == null || ("codex-luna".equals(family) && "ultra".equals(effort))) {
            return Optional.empty();
        }
        return Optional.of(family + ":" + effort);
    }

    private record ModelResolution(@Nullable String model, String source) {
    }

    private List<DispatchTaskDTO> toDispatchDTOs(List<CodexTaskEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<String, String> providerTypes = resolveProviderTypes(entities);
        return entities.stream()
                .map(entity -> toDispatchDTO(entity, providerTypes.get(entity.getTaskId())))
                .toList();
    }
}
