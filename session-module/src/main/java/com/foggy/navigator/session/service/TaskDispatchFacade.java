package com.foggy.navigator.session.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.NativeSubtaskStateRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.session.exception.SessionProviderBoundMismatchException;
import com.foggy.navigator.session.lifecycle.SessionForegroundLaneService;
import com.foggy.navigator.session.lifecycle.LifecycleIngressGate;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.AgentConversationContextRepository;
import com.foggy.navigator.session.repository.SessionCodingAgentRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.TaskSearchResult;
import com.foggy.navigator.spi.agent.WorkerSessionMessage;
import com.foggy.navigator.spi.agent.WorkerSessionMessageCount;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import com.foggy.navigator.spi.agent.WorkerSessionSummary;
import com.foggy.navigator.spi.agent.WorkerSessionSyncResult;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 统一任务分发 Facade —— 所有外部入口（前端 / OpenAPI / A2A）的唯一任务操作层。
 * <p>
 * 职责：
 * <ol>
 *   <li>验证/建立会话-Agent 绑定</li>
 *   <li>通过 UnifiedAgentResolver 解析目标 A2aAgent</li>
 *   <li>构造 A2aMessage 并委派到 Agent 执行</li>
 *   <li>返回统一 DispatchTaskDTO</li>
 * </ol>
 * <p>
 * Controller 只依赖本 Facade，不允许直接接触 A2aAgent / Provider / Worker。
 */
@Slf4j
@Service
public class TaskDispatchFacade {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> CONTEXT_BUSY_STATES = List.of(
            "PENDING",
            "SUBMITTED",
            "RUNNING",
            "AWAITING_PERMISSION",
            "AWAITING_INPUT");

    private final UnifiedAgentResolver agentResolver;
    private final SessionBindingService bindingService;
    private final SessionRepository sessionRepository;
    private final SessionTaskResourceAccessService resourceAccessService;
    private final TaskQueryProviderRegistry taskQueryProviderRegistry;
    private final LlmModelManager llmModelManager;

    @Autowired(required = false)
    @Nullable
    private SessionTaskRepository sessionTaskRepository;

    @Autowired(required = false)
    @Nullable
    private NativeSubtaskStateRepository nativeSubtaskStateRepository;

    @Autowired(required = false)
    @Nullable
    private WorkingDirectoryRepository workingDirectoryRepository;

    @Autowired(required = false)
    @Nullable
    private SessionCodingAgentRepository sessionCodingAgentRepository;

    @Autowired(required = false)
    @Nullable
    private WorkerManagementFacade workerManagementFacade;

    @Autowired(required = false)
    @Nullable
    private ErrorDiagnosticService errorDiagnosticService;

    @Autowired(required = false)
    @Nullable
    private AgentConversationContextRepository agentConversationContextRepository;

    @Autowired(required = false)
    @Nullable
    private AgentContextStore agentContextStore;

    @Autowired(required = false)
    @Nullable
    private PlatformTransactionManager transactionManager;

    @Autowired(required = false)
    @Nullable
    private SessionForegroundLaneService lifecycleLaneService;

    @Autowired(required = false)
    @Nullable
    private LifecycleIngressGate lifecycleIngressGate;

    @Value("${navigator.lifecycle.shadow-enabled:false}")
    private boolean lifecycleShadowEnabled;

    public TaskDispatchFacade(UnifiedAgentResolver agentResolver,
                              SessionBindingService bindingService,
                              SessionRepository sessionRepository,
                              SessionTaskResourceAccessService resourceAccessService,
                              List<? extends TaskLookupProvider> taskLookupProviders,
                              List<? extends TaskCommandProvider> taskCommandProviders,
                              List<? extends TaskListingProvider> taskListingProviders,
                              List<? extends WorkerSessionQueryProvider> workerSessionQueryProviders,
                              LlmModelManager llmModelManager) {
        this.agentResolver = agentResolver;
        this.bindingService = bindingService;
        this.sessionRepository = sessionRepository;
        this.resourceAccessService = resourceAccessService;
        this.taskQueryProviderRegistry = new TaskQueryProviderRegistry(
                taskLookupProviders,
                taskCommandProviders,
                taskListingProviders,
                workerSessionQueryProviders);
        this.llmModelManager = llmModelManager;
    }

    private TaskCreateTargetResolver createTargetResolver() {
        return new TaskCreateTargetResolver(
                sessionRepository,
                resourceAccessService,
                workingDirectoryRepository,
                sessionCodingAgentRepository,
                taskQueryProviderRegistry,
                llmModelManager,
                workerManagementFacade,
                agentResolver);
    }

    private UnifiedSessionTaskProjectionService projectionService() {
        return new UnifiedSessionTaskProjectionService(
                sessionRepository, workingDirectoryRepository, errorDiagnosticService);
    }

    private TaskOperationRouter operationRouter() {
        return new TaskOperationRouter(
                agentResolver,
                bindingService,
                sessionRepository,
                resourceAccessService,
                sessionTaskRepository,
                nativeSubtaskStateRepository,
                taskQueryProviderRegistry,
                createTargetResolver(),
                projectionService());
    }

    /**
     * 创建任务。
     * <p>
     * 1. 解析 Agent 查找键（显式 agentId → directoryId → workerId）
     * 2. 验证/建立 Session ↔ Agent 绑定
     * 3. 构造 A2aMessage，调用 A2aAgent.sendTask()
     * 4. 返回统一 DTO
     */
    public DispatchTaskDTO createTask(TaskDispatchRequest request, AgentResolveContext context) {
        validateSessionOwnershipBeforeDispatch(request, context);
        validateContextBindingBeforeDispatch(request, context);
        if (bindContinuationFromContext(request, context) && request.isResume()) {
            return resumeTask(request, context);
        }

        TaskCreateTargetResolver.CreateExecutionTarget target =
                createTargetResolver().resolveCreateExecutionTarget(request);
        if (target.directProviderRoute()) {
            validateSessionProviderBeforeDispatch(request.getSessionId(), target.providerType());
            rejectBusyContextContinuationIfNeeded(target.providerType(), request, context);
            LifecycleIngressGate.IngressPermit permit = reserveBeforeEffect(request);
            try {
                DispatchTaskDTO dto = createTaskDirect(target.providerType(), request, context);
                confirmReservation(permit, dto);
                return dto;
            } catch (RuntimeException failure) {
                releaseFailedReservation(permit);
                throw failure;
            }
        }

        TaskCreateTargetResolver.AgentLookup lookup = target.agentLookup();
        A2aAgent agent = agentResolver.resolveAgent(lookup.lookupId, context)
                .orElseThrow(() -> new IllegalArgumentException("Agent not available: " + lookup.lookupId));
        String providerType = agentResolver.getProviderType(lookup.lookupId, context)
                .orElseThrow(() -> new IllegalArgumentException("No provider found for agent: " + lookup.lookupId));
        String agentId = resolveLogicalAgentId(agent, lookup.lookupId);
        TaskOperationRouter operations = operationRouter();
        operations.validateRequestedProviderTypeCompatibility(request.getProviderType(), providerType);
        operations.validateModelConfigProviderCompatibility(request.getModelConfigId(), providerType);

        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            bindingService.getOrBind(
                    request.getSessionId(), agentId, providerType, lookup.bindingSource);
        }

        A2aMessage message = buildMessage(request);
        LifecycleIngressGate.IngressPermit permit = reserveBeforeEffect(request);
        A2aTask a2aTask;
        try {
            a2aTask = agent.sendTask(message);
        } catch (RuntimeException failure) {
            releaseFailedReservation(permit);
            throw failure;
        }
        log.info("Dispatched task via Facade: agentId={}, providerType={}, taskId={}",
                agentId, providerType, a2aTask.getId());

        DispatchTaskDTO dto = toDispatchDTO(a2aTask, agentId, providerType, request);
        persistTaskRequestFields(dto.getTaskId(), request);
        persistContextBinding(dto, request, context, providerType);
        confirmReservation(permit, dto);
        return dto;
    }

    /** Content-free seam used by create ingress to resolve exactly one execution target. */
    TaskCreateTargetResolver.CreateExecutionPlan resolveCreateExecutionPlan(
            TaskDispatchRequest request, AgentResolveContext context) {
        validateSessionOwnershipBeforeDispatch(request, context);
        validateContextBindingBeforeDispatch(request, context);
        bindContinuationFromContext(request, context);
        if (request != null && request.isResume()) {
            throw new IllegalArgumentException(
                    "guarded CREATE cannot execute a resume continuation");
        }
        if (request != null && trimToNull(request.getContextAlias()) != null) {
            throw new IllegalArgumentException(
                    "guarded CREATE does not accept contextAlias");
        }
        return createTargetResolver().resolveCreateExecutionPlan(request, context);
    }

    /** Executes the exact pre-effect plan; package visibility keeps ingress adapters on one path. */
    DispatchTaskDTO createTask(TaskDispatchRequest request,
                               AgentResolveContext context,
                               TaskCreateTargetResolver.CreateExecutionPlan plan,
                               TaskCreateCommandCoordinator.ProviderEffectGate providerEffectGate) {
        Objects.requireNonNull(plan, "create execution plan is required");
        Objects.requireNonNull(providerEffectGate, "provider effect gate is required");
        plan.requireMatches(request, context);
        plan.applyCanonicalTarget(request);

        if (plan.directProviderRoute()) {
            validateSessionProviderBeforeDispatch(plan.sessionId(), plan.providerType());
            rejectBusyContextContinuationIfNeeded(plan.providerType(), request, context);
            LifecycleIngressGate.IngressPermit permit = reserveBeforeEffect(request);
            try {
                DispatchTaskDTO dto =
                        createTaskDirect(
                                plan.providerType(), request, context, plan, providerEffectGate);
                confirmReservation(permit, dto);
                return dto;
            } catch (RuntimeException failure) {
                if (!providerEffectGate.providerEffectPermitted()) {
                    releaseFailedReservation(permit);
                }
                throw failure;
            }
        }

        TaskCreateTargetResolver.AgentLookup lookup = plan.agentLookup();
        AgentResolveContext executionContext = executionContext(plan, context);

        A2aAgent agent = agentResolver.resolveAgentByProviderTypeExact(
                        plan.providerType(), lookup.lookupId(), executionContext)
                .orElseThrow(() -> new IllegalArgumentException("Agent not available: " + lookup.lookupId()));

        A2aAgentCard actualCard = agent.getAgentCard();
        String agentId = actualCard != null ? actualCard.getId() : null;
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException(
                    "Resolved Agent has no exact card id: " + lookup.lookupId());
        }
        if (!plan.logicalAgentId().equals(agentId)) {
            throw new IllegalArgumentException("agentId " + agentId
                    + " conflicts with resolved " + plan.logicalAgentId());
        }

        TaskOperationRouter operations = operationRouter();
        operations.validateRequestedProviderTypeCompatibility(request.getProviderType(), plan.providerType());
        operations.validateModelConfigProviderCompatibility(request.getModelConfigId(), plan.providerType());

        // 绑定校验
        if (plan.sessionId() != null) {
            bindingService.getOrBind(plan.sessionId(), agentId, plan.providerType(), lookup.bindingSource());
        }

        // 构造 A2aMessage
        A2aMessage message = buildMessage(request);

        LifecycleIngressGate.IngressPermit permit = reserveBeforeEffect(request);
        try {
            plan.requireMatches(request, context);
            TaskCreateCommandCoordinator.ProviderEffectIdentity effectIdentity =
                    TaskCreateCommandCoordinator.ProviderEffectIdentity.atEffectPoint(
                            TaskCreateTargetResolver.ExecutionRoute.A2A,
                            request,
                            context,
                            agentId,
                            plan.providerType());
            A2aTask a2aTask = providerEffectGate.invoke(
                    plan, effectIdentity, () -> agent.sendTask(message));

            log.info("Dispatched task via Facade: agentId={}, providerType={}, taskId={}",
                    agentId, plan.providerType(), a2aTask.getId());

            DispatchTaskDTO dto = toDispatchDTO(a2aTask, agentId, plan.providerType(), request);
            persistTaskRequestFields(dto.getTaskId(), request);
            persistContextBinding(dto, request, context, plan.providerType());
            confirmReservation(permit, dto);
            return dto;
        } catch (RuntimeException failure) {
            if (!providerEffectGate.providerEffectPermitted()) {
                releaseFailedReservation(permit);
            }
            throw failure;
        }
    }

    /**
     * Submit an application-level Agent task and return the A2A task view.
     * <p>
     * This is the implementation backing {@code TaskSubmittingA2aAgent}. It
     * intentionally delegates to {@link #createTask(TaskDispatchRequest, AgentResolveContext)}
     * so complex entry points share the same session projection, provider routing
     * and task persistence path.
     */
    public A2aTask submitTask(AgentTaskSubmitRequest request) {
        return toA2aTask(submitTaskDispatch(request));
    }

    public DispatchTaskDTO submitTaskDispatch(AgentTaskSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("submit request is required");
        }
        AgentResolveContext context = request.getResolveContext();
        if (context == null) {
            context = AgentResolveContext.builder().requestSource("A2A_SUBMIT").build();
        }
        return createTask(toTaskDispatchRequest(request), context);
    }

    /**
     * 查询单个任务（遍历所有 lookup provider）
     */
    public Optional<DispatchTaskDTO> getTask(String taskId, AgentResolveContext context) {
        return operationRouter().getTask(taskId, context);
    }

    /**
     * 按会话查询任务列表（根据 session 绑定的 providerType 路由到对应 Provider）
     */
    public List<DispatchTaskDTO> listTasksBySession(String sessionId, AgentResolveContext context) {
        resourceAccessService.requireOwnedSession(
                sessionId,
                context != null ? context.getUserId() : null,
                context != null ? context.getTenantId() : null);
        if (sessionTaskRepository != null) {
            List<DispatchTaskDTO> tasks = toDispatchTaskDTOs(
                    sessionTaskRepository.findBySessionIdAndUserIdAndTenantIdOrderByCreatedAtDesc(
                            sessionId, context.getUserId(), context.getTenantId()));
            if (!tasks.isEmpty()) {
                return tasks;
            }
        }

        SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) return List.of();

        String providerType = session.getProviderType();
        if (providerType != null && !providerType.isBlank()) {
            // 精确匹配 provider
            return taskQueryProviderRegistry.findLookupProviderByType(providerType)
                    .map(p -> p.listTasksBySession(sessionId))
                    .orElse(List.of());
        }

        // providerType 为空（旧会话），遍历所有 provider
        return taskQueryProviderRegistry.lookupProviders().stream()
                .flatMap(p -> p.listTasksBySession(sessionId).stream())
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();
    }

    /**
     * 聚合所有 Provider 的活跃任务
     */
    public List<DispatchTaskDTO> listActiveTasks(String userId) {
        if (sessionTaskRepository != null) {
            List<DispatchTaskDTO> tasks = toDispatchTaskDTOs(
                    sessionTaskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                            userId, List.of("RUNNING", "AWAITING_PERMISSION", "AWAITING_INPUT"))).stream()
                    .sorted((left, right) -> compareNullableTime(right.getCreatedAt(), left.getCreatedAt()))
                    .toList();
            if (!tasks.isEmpty()) {
                return tasks;
            }
        }

        return taskQueryProviderRegistry.lookupProviders().stream()
                .flatMap(p -> p.listActiveDispatchTasks(userId).stream())
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();
    }

    /**
     * 取消任务。
     */
    public void cancelTask(String taskId, String agentId, AgentResolveContext context) {
        cancelTask(taskId, agentId, context, false);
    }

    /**
     * Cancels a task through its authorized provider projection.
     */
    public void cancelTask(String taskId, String agentId, AgentResolveContext context, boolean force) {
        operationRouter().cancelTask(taskId, agentId, context, force);
    }

    // ── 任务操作（路由到 command provider） ──

    /**
     * 回复权限请求 / 用户问题（不支持的 Provider 自动抛 UnsupportedOperationException）
     */
    public void respondToTask(String taskId, AgentResolveContext context, Map<String, Object> response) {
        operationRouter().respondToTask(taskId, context, response);
    }

    /**
     * 重连任务 SSE 流
     */
    public void reconnectTask(String taskId, AgentResolveContext context) {
        operationRouter().reconnectTask(taskId, context);
    }

    /**
     * 重新同步任务状态
     */
    public Object resyncTask(String taskId, AgentResolveContext context) {
        return operationRouter().resyncTask(taskId, context);
    }

    /**
     * 回退到检查点
     */
    public Object rewindTask(String taskId, AgentResolveContext context, Map<String, Object> params) {
        return operationRouter().rewindTask(taskId, context, params);
    }

    // ── Phase 3: 统一任务端点扩展 ──

    /**
     * 恢复任务（resume）—— 续接已有会话。
     */
    public DispatchTaskDTO resumeTask(TaskDispatchRequest request, AgentResolveContext context) {
        LifecycleIngressGate.IngressPermit permit = reserveBeforeEffect(request);
        try {
            DispatchTaskDTO dto = operationRouter().resumeTask(request, context);
            persistTaskRequestFields(dto.getTaskId(), request);
            persistContextBinding(dto, request, context, dto.getProviderType());
            confirmReservation(permit, dto);
            return dto;
        } catch (RuntimeException failure) {
            releaseFailedReservation(permit);
            throw failure;
        }
    }

    /**
     * 删除任务
     */
    public void deleteTask(String taskId, AgentResolveContext context) {
        operationRouter().deleteTask(taskId, context);
    }

    /**
     * 扫描 checkpoints
     */
    public Object scanCheckpoints(String taskId, AgentResolveContext context) {
        return operationRouter().scanCheckpoints(taskId, context);
    }

    /**
     * 分页查询任务列表（按会话聚合所有 Provider，再统一分页）
     */
    public Object listTasksPaged(String userId, int page, int size, String state) {
        return listTasksPaged(userId, page, size, state, false);
    }

    /**
     * 分页查询任务列表（按会话聚合所有 Provider，再统一分页）
     */
    public Object listTasksPaged(String userId, int page, int size, String state, boolean compact) {
        if (sessionTaskRepository != null) {
            Object unified = listTasksPagedFromSessionStore(userId, null, page, size, state, compact);
            if (unified != null) {
                return unified;
            }
        }

        int fetchSize = computeFetchSize(page, size);
        List<Object> content = new ArrayList<>();
        long totalSessions = 0L;

        for (TaskListingProvider provider : taskQueryProviderRegistry.listingProvidersSupporting(TaskQueryCapability.LIST_TASKS_PAGED)) {
            try {
                TaskPageResult pageResult = provider.listTaskPage(userId, 0, fetchSize, state);
                UnifiedSessionTaskProjectionService.TaskPageEnvelope envelope = toTaskPageEnvelope(pageResult);
                content.addAll(compact
                        ? envelope.content().stream().map(this::toCompactTaskItem).toList()
                        : envelope.content());
                totalSessions += envelope.totalSessions();
            } catch (UnsupportedOperationException ignored) {
            }
        }

        return buildTaskPageResponse(content, totalSessions, page, size);
    }

    /**
     * 搜索会话
     */
    public Object searchSessions(String userId, String keyword, String workerId,
                                  String directoryId, int page, int size) {
        if (sessionTaskRepository != null) {
            Object unified = searchSessionsFromSessionStore(userId, keyword, workerId, directoryId, page, size);
            if (unified != null) {
                return unified;
            }
        }

        int fetchSize = computeFetchSize(page, size);
        List<Object> results = new ArrayList<>();
        long total = 0L;

        for (TaskListingProvider provider : taskQueryProviderRegistry.listingProvidersSupporting(TaskQueryCapability.SEARCH_SESSIONS)) {
            try {
                TaskSearchResult searchResult = provider.searchSessionPage(userId, keyword, workerId, directoryId, 0, fetchSize);
                UnifiedSessionTaskProjectionService.SearchEnvelope envelope = toSearchEnvelope(searchResult);
                results.addAll(envelope.results());
                total += envelope.total();
            } catch (UnsupportedOperationException ignored) {
            }
        }

        Map<String, Object> deduped = new LinkedHashMap<>();
        for (Object result : results) {
            String sessionId = readStringProperty(result, "sessionId");
            String dedupeKey = (sessionId != null && !sessionId.isBlank())
                    ? sessionId
                    : UUID.randomUUID().toString();
            Object existing = deduped.get(dedupeKey);
            if (existing == null || compareNullableTime(
                    readDateTimeProperty(result, "updatedAt"),
                    readDateTimeProperty(existing, "updatedAt")) > 0) {
                deduped.put(dedupeKey, result);
            }
        }

        List<Object> sortedResults = deduped.values().stream()
                .sorted((left, right) -> compareNullableTime(
                        readDateTimeProperty(right, "updatedAt"),
                        readDateTimeProperty(left, "updatedAt")))
                .toList();

        int from = Math.min(page * size, sortedResults.size());
        int to = Math.min(from + size, sortedResults.size());
        return Map.of(
                "results", sortedResults.subList(from, to),
                "total", total,
                "page", page,
                "size", size
        );
    }

    /**
     * 按目录查询任务列表
     */
    public List<DispatchTaskDTO> listTasksByDirectory(String userId, String directoryId) {
        if (sessionTaskRepository != null) {
            List<DispatchTaskDTO> tasks = toDispatchTaskDTOs(
                    sessionTaskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc(directoryId, userId));
            if (!tasks.isEmpty()) {
                return tasks;
            }
        }

        return taskQueryProviderRegistry.listingProvidersSupporting(TaskQueryCapability.LIST_TASKS_BY_DIRECTORY).stream()
                .flatMap(p -> {
                    try {
                        return p.listTasksByDirectory(userId, directoryId).stream();
                    } catch (UnsupportedOperationException e) {
                        return java.util.stream.Stream.empty();
                    }
                })
                .toList();
    }

    /**
     * 按目录分页查询任务列表
     */
    public Object listTasksByDirectoryPaged(String userId, String directoryId,
                                             int page, int size, String state) {
        if (sessionTaskRepository != null) {
            Object unified = listTasksPagedFromSessionStore(userId, directoryId, page, size, state, false);
            if (unified != null) {
                return unified;
            }
        }

        int fetchSize = computeFetchSize(page, size);
        List<Object> content = new ArrayList<>();
        long totalSessions = 0L;

        for (TaskListingProvider provider : taskQueryProviderRegistry.listingProvidersSupporting(TaskQueryCapability.LIST_TASKS_BY_DIRECTORY_PAGED)) {
            try {
                TaskPageResult pageResult = provider.listDirectoryTaskPage(userId, directoryId, 0, fetchSize, state);
                UnifiedSessionTaskProjectionService.TaskPageEnvelope envelope = toTaskPageEnvelope(pageResult);
                content.addAll(envelope.content());
                totalSessions += envelope.totalSessions();
            } catch (UnsupportedOperationException ignored) {
            }
        }

        return buildTaskPageResponse(content, totalSessions, page, size);
    }

    // ── Worker Session 查询（统一端点） ──

    public List<WorkerSessionSummary> listWorkerSessionSummaries(String workerId, String userId) {
        for (WorkerSessionQueryProvider provider : taskQueryProviderRegistry.workerSessionProvidersSupporting(TaskQueryCapability.LIST_WORKER_SESSIONS)) {
            try {
                List<WorkerSessionSummary> sessions = provider.listWorkerSessionSummaries(workerId, userId);
                return sessions != null ? sessions : List.of();
            } catch (UnsupportedOperationException ignored) {
            } catch (IllegalArgumentException e) {
                if (!isWorkerNotFound(e)) {
                    throw e;
                }
            }
        }
        return List.of();
    }

    public List<Map<String, Object>> listWorkerSessions(String workerId, String userId) {
        return listWorkerSessionSummaries(workerId, userId).stream()
                .map(WorkerSessionSummary::toMap)
                .toList();
    }

    public WorkerSessionMessageCount getWorkerSessionMessageCountResult(String workerId, String sessionId, String userId) {
        for (WorkerSessionQueryProvider provider : taskQueryProviderRegistry.workerSessionProvidersSupporting(TaskQueryCapability.GET_WORKER_SESSION_MESSAGE_COUNT)) {
            try {
                WorkerSessionMessageCount count = provider.getWorkerSessionMessageCountResult(workerId, sessionId, userId);
                return count != null ? count : WorkerSessionMessageCount.empty();
            } catch (UnsupportedOperationException ignored) {
            } catch (IllegalArgumentException e) {
                if (!isWorkerNotFound(e)) {
                    throw e;
                }
            }
        }
        return WorkerSessionMessageCount.empty();
    }

    public Map<String, Object> getWorkerSessionMessageCount(String workerId, String sessionId, String userId) {
        return getWorkerSessionMessageCountResult(workerId, sessionId, userId).toMap();
    }

    public List<WorkerSessionMessage> listWorkerSessionMessages(String workerId, String sessionId,
                                                                String userId, Integer offset, Integer limit) {
        for (WorkerSessionQueryProvider provider : taskQueryProviderRegistry.workerSessionProvidersSupporting(TaskQueryCapability.GET_WORKER_SESSION_MESSAGES)) {
            try {
                List<WorkerSessionMessage> messages = provider.listWorkerSessionMessages(workerId, sessionId, userId, offset, limit);
                return messages != null ? messages : List.of();
            } catch (UnsupportedOperationException ignored) {
            } catch (IllegalArgumentException e) {
                if (!isWorkerNotFound(e)) {
                    throw e;
                }
            }
        }
        return List.of();
    }

    public List<Map<String, Object>> getWorkerSessionMessages(String workerId, String sessionId,
                                                               String userId, Integer offset, Integer limit) {
        return listWorkerSessionMessages(workerId, sessionId, userId, offset, limit).stream()
                .map(WorkerSessionMessage::toMap)
                .toList();
    }

    public WorkerSessionSyncResult syncWorkerSessionState(String workerId, String userId, String tenantId) {
        for (WorkerSessionQueryProvider provider : taskQueryProviderRegistry.workerSessionProvidersSupporting(TaskQueryCapability.SYNC_WORKER_SESSIONS)) {
            try {
                WorkerSessionSyncResult result = provider.syncWorkerSessionState(workerId, userId, tenantId);
                return result != null ? result : WorkerSessionSyncResult.of(0, 0);
            } catch (UnsupportedOperationException ignored) {
            } catch (IllegalArgumentException e) {
                if (!isWorkerNotFound(e)) {
                    throw e;
                }
            }
        }
        throw new UnsupportedOperationException("No provider supports syncWorkerSessions");
    }

    public Map<String, Object> syncWorkerSessions(String workerId, String userId, String tenantId) {
        return syncWorkerSessionState(workerId, userId, tenantId).toMap();
    }

    private boolean isWorkerNotFound(IllegalArgumentException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("worker not found");
    }

    // ── 内部方法 ──

    private A2aMessage buildMessage(TaskDispatchRequest request) {
        List<A2aPart> parts = new ArrayList<>();
        parts.add(A2aPart.text(request.getPrompt()));

        // 复用公共参数转换，A2A message 的 metadata 和 Direct params 共享同一组字段
        Map<String, Object> metadata = TaskDispatchRequestParams.toCommonParams(request);

        return A2aMessage.builder()
                .role("user")
                .parts(parts)
                .contextId(request.getContextId())
                .contextAlias(request.getContextAlias())
                .metadata(metadata)
                .build();
    }

    private TaskDispatchRequest toTaskDispatchRequest(AgentTaskSubmitRequest request) {
        A2aMessage message = request.getMessage();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (message != null && message.getMetadata() != null) {
            metadata.putAll(message.getMetadata());
        }
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        String contextId = firstNonBlank(request.getContextId(), message != null ? message.getContextId() : null);
        String contextAlias = firstNonBlank(request.getContextAlias(), message != null ? message.getContextAlias() : null);
        return TaskDispatchRequest.builder()
                .agentId(request.getAgentId())
                .providerType(request.getProviderType())
                .sessionId(request.getSessionId())
                .workerId(request.getWorkerId())
                .prompt(firstNonBlank(request.getPrompt(), extractTextPrompt(message)))
                .cwd(request.getCwd())
                .directoryId(request.getDirectoryId())
                .model(request.getModel())
                .modelConfigId(request.getModelConfigId())
                .maxTurns(request.getMaxTurns())
                .permissionMode(request.getPermissionMode())
                .images(request.getImages())
                .attachments(request.getAttachments())
                .agentTeamsConfigId(request.getAgentTeamsConfigId())
                .agentTeamsJson(request.getAgentTeamsJson())
                .contextId(contextId)
                .context(request.getContext())
                .metadata(metadata.isEmpty() ? null : metadata)
                .contextAlias(contextAlias)
                .initializeRuntimeAffinity(request.isInitializeRuntimeAffinity())
                .build();
    }

    private String extractTextPrompt(A2aMessage message) {
        if (message == null || message.getParts() == null) {
            return null;
        }
        return message.getParts().stream()
                .filter(Objects::nonNull)
                .filter(part -> "text".equals(part.getType()))
                .map(A2aPart::getText)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private DispatchTaskDTO toDispatchDTO(A2aTask a2aTask, String agentId, String providerType,
                                           TaskDispatchRequest request) {
        DispatchTaskDTO.DispatchTaskDTOBuilder builder = DispatchTaskDTO.builder()
                .taskId(a2aTask.getId())
                .agentId(agentId)
                .providerType(providerType)
                .contextId(a2aTask.getContextId());

        // 映射状态
        if (a2aTask.getStatus() != null) {
            builder.status(mapA2aState(a2aTask.getStatus().getState()));
        }

        // 从 A2aTask metadata 提取扩展字段
        Map<String, Object> meta = a2aTask.getMetadata();
        if (meta != null) {
            builder.sessionId(strVal(meta, "sessionId"))
                    .workerId(strVal(meta, "workerId"))
                    .workerTaskId(strVal(meta, "workerTaskId"))
                    .directoryId(strVal(meta, "directoryId"))
                    .claudeSessionId(strVal(meta, "claudeSessionId"))
                    .codexThreadId(strVal(meta, "codexThreadId"))
                    .geminiSessionId(strVal(meta, "geminiSessionId"))
                    .model(strVal(meta, "model"))
                    .modelConfigId(strVal(meta, "modelConfigId"));
        }

        // 从 metadata 提取 source / fileCheckpointingEnabled（Provider 可能已设置）
        if (meta != null) {
            if (meta.get("source") instanceof String s) builder.source(s);
            if (meta.get("fileCheckpointingEnabled") instanceof Boolean b) builder.fileCheckpointingEnabled(b);
        }

        // 从 request 补充（如果 metadata 里没有）
        if (request != null) {
            DispatchTaskDTO dto = builder.build();
            if (dto.getSessionId() == null) builder.sessionId(request.getSessionId());
            if (dto.getWorkerId() == null) builder.workerId(request.getWorkerId());
            if (dto.getDirectoryId() == null) builder.directoryId(request.getDirectoryId());
            // 通过 Facade 创建的任务均来自平台
            if (dto.getSource() == null) builder.source("PLATFORM");
            builder.prompt(request.getPrompt())
                    .cwd(request.getCwd());
            if (dto.getModel() == null) {
                builder.model(request.getModel());
            }
            if (dto.getModelConfigId() == null) {
                builder.modelConfigId(request.getModelConfigId());
            }
        }

        // 从 artifacts 提取 resultText
        if (a2aTask.getArtifacts() != null && !a2aTask.getArtifacts().isEmpty()) {
            a2aTask.getArtifacts().stream()
                    .flatMap(art -> art.getParts() != null ? art.getParts().stream() : java.util.stream.Stream.empty())
                    .filter(p -> "text".equals(p.getType()) && p.getText() != null)
                    .findFirst()
                    .ifPresent(p -> builder.resultText(p.getText()));
        }

        // 错误信息
        if (a2aTask.getStatus() != null && a2aTask.getStatus().getDescription() != null) {
            A2aTaskState state = a2aTask.getStatus().getState();
            if (state == A2aTaskState.FAILED) {
                builder.errorMessage(a2aTask.getStatus().getDescription());
            }
        }

        // 从 Session 补充 parentSessionId（转发创建新会话时需要）
        DispatchTaskDTO preResult = builder.build();
        if (preResult.getSessionId() != null && preResult.getParentSessionId() == null) {
            sessionRepository.findById(preResult.getSessionId()).ifPresent(session -> {
                if (session.getParentSessionId() != null) {
                    builder.parentSessionId(session.getParentSessionId());
                }
            });
        }

        return builder.build();
    }

    private String mapA2aState(A2aTaskState state) {
        if (state == null) return "PENDING";
        return switch (state) {
            case SUBMITTED -> "PENDING";
            case WORKING -> "RUNNING";
            case INPUT_REQUIRED -> "AWAITING_INPUT";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELED -> "ABORTED";
        };
    }

    public A2aTask toA2aTask(DispatchTaskDTO dto) {
        if (dto == null) {
            throw new IllegalStateException("Task submit pipeline returned no task");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "sessionId", dto.getSessionId());
        putIfNotBlank(metadata, "workerId", dto.getWorkerId());
        putIfNotBlank(metadata, "workerTaskId", dto.getWorkerTaskId());
        putIfNotBlank(metadata, "directoryId", dto.getDirectoryId());
        putIfNotBlank(metadata, "model", dto.getModel());
        putIfNotBlank(metadata, "modelConfigId", dto.getModelConfigId());
        putIfNotBlank(metadata, "claudeSessionId", dto.getClaudeSessionId());
        putIfNotBlank(metadata, "codexThreadId", dto.getCodexThreadId());
        putIfNotBlank(metadata, "geminiSessionId", dto.getGeminiSessionId());
        if (dto.getLastAckedSeq() != null) {
            metadata.put("lastAckedSeq", dto.getLastAckedSeq());
        }
        if (dto.getDurationMs() != null) {
            metadata.put("durationMs", dto.getDurationMs());
        }
        if (dto.getCostUsd() != null) {
            metadata.put("costUsd", dto.getCostUsd());
        }
        return A2aTask.builder()
                .id(dto.getTaskId())
                .contextId(dto.getContextId())
                .status(A2aTaskStatus.builder()
                        .state(toA2aState(dto.getStatus()))
                        .description(dto.getErrorMessage())
                        .build())
                .artifacts(buildArtifacts(dto.getResultText()))
                .metadata(metadata.isEmpty() ? null : metadata)
                .build();
    }

    private A2aTaskState toA2aState(String status) {
        if (status == null || status.isBlank()) {
            return A2aTaskState.SUBMITTED;
        }
        return switch (status) {
            case "PENDING" -> A2aTaskState.SUBMITTED;
            case "RUNNING" -> A2aTaskState.WORKING;
            case "AWAITING_INPUT", "AWAITING_PERMISSION" -> A2aTaskState.INPUT_REQUIRED;
            case "COMPLETED" -> A2aTaskState.COMPLETED;
            case "FAILED" -> A2aTaskState.FAILED;
            case "ABORTED", "CANCELLED", "CANCELED" -> A2aTaskState.CANCELED;
            default -> A2aTaskState.WORKING;
        };
    }

    private List<A2aArtifact> buildArtifacts(String resultText) {
        if (resultText == null || resultText.isBlank()) {
            return null;
        }
        return List.of(A2aArtifact.builder()
                .parts(List.of(A2aPart.text(resultText)))
                .build());
    }

    private void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private int computeFetchSize(int page, int size) {
        return Math.max(size, (page + 1) * size);
    }

    private Map<String, Object> buildTaskPageResponse(List<Object> taskItems, long totalSessions, int page, int size) {
        return projectionService().buildTaskPageResponse(taskItems, totalSessions, page, size);
    }

    private UnifiedSessionTaskProjectionService.TaskPageEnvelope toTaskPageEnvelope(Object pageResult) {
        return projectionService().toTaskPageEnvelope(pageResult);
    }

    private UnifiedSessionTaskProjectionService.SearchEnvelope toSearchEnvelope(Object searchResult) {
        return projectionService().toSearchEnvelope(searchResult);
    }

    private String readStringProperty(Object target, String property) {
        return projectionService().readStringProperty(target, property);
    }

    private LocalDateTime readDateTimeProperty(Object target, String property) {
        return projectionService().readDateTimeProperty(target, property);
    }

    private int compareNullableTime(LocalDateTime left, LocalDateTime right) {
        return projectionService().compareNullableTime(left, right);
    }

    private Object listTasksPagedFromSessionStore(String userId, String directoryId, int page, int size, String state,
                                                   boolean compact) {
        return projectionService().listTasksPagedFromSessionStore(
                sessionTaskRepository, userId, directoryId, page, size, state, compact);
    }

    private Object searchSessionsFromSessionStore(String userId, String keyword, String workerId,
                                                   String directoryId, int page, int size) {
        return projectionService().searchSessionsFromSessionStore(
                sessionTaskRepository, userId, keyword, workerId, directoryId, page, size);
    }

    private Object toCompactTaskItem(Object task) {
        return projectionService().toCompactTaskItem(task);
    }

    private List<DispatchTaskDTO> toDispatchTaskDTOs(List<SessionTaskEntity> entities) {
        return projectionService().toDispatchTaskDTOs(entities);
    }

    private DispatchTaskDTO toDispatchTaskDTO(SessionTaskEntity entity) {
        return projectionService().toDispatchTaskDTO(entity);
    }

    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse task/session JSON payload: {}", json);
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task state payload", e);
        }
    }

    /**
     * Post-dispatch: 将 request 中的 model / modelConfigId 持久化到 SessionTaskEntity。
     * 这是 model 和 modelConfigId 写入 session_tasks 的统一入口，在 Facade 层公共处理，各 Provider 无需关心。
     */
    private void persistTaskRequestFields(String taskId, TaskDispatchRequest request) {
        if (sessionTaskRepository == null || taskId == null) return;
        String model = request.getModel();
        String modelConfigId = request.getModelConfigId();
        boolean hasModel = model != null && !model.isBlank();
        boolean hasModelConfigId = modelConfigId != null && !modelConfigId.isBlank();
        boolean hasContextId = request.getContextId() != null && !request.getContextId().isBlank();
        boolean hasDiagnostics = hasDiagnosticMetadata(request.getMetadata());
        if (!hasModel && !hasModelConfigId && !hasContextId && !hasDiagnostics) return;

        Runnable update = () -> sessionTaskRepository.findByTaskIdForUpdate(taskId).ifPresent(st -> {
            if (hasModel) st.setModel(model);
            if (hasModelConfigId) st.setModelConfigId(modelConfigId);
            if (hasContextId || hasDiagnostics) {
                Map<String, Object> state = new LinkedHashMap<>();
                if (hasContextId) {
                    state.put("contextId", request.getContextId().trim());
                }
                copyDiagnosticMetadata(state, request.getMetadata());
                st.setTaskStateJson(ProviderStateCodec.mergeTaskValues(
                        st.getTaskStateJson(), st.getProviderType(), state));
            }
            sessionTaskRepository.save(st);
        });
        if (transactionManager == null) {
            update.run();
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> update.run());
    }

    private boolean hasDiagnosticMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        for (String key : diagnosticMetadataKeys()) {
            if (metadata.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private void copyDiagnosticMetadata(Map<String, Object> state, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        for (String key : diagnosticMetadataKeys()) {
            Object value = metadata.get(key);
            if (value instanceof String text && !text.isBlank()) {
                state.put(key, text.trim());
            } else if (value instanceof Number || value instanceof Boolean) {
                state.put(key, value);
            }
        }
    }

    private List<String> diagnosticMetadataKeys() {
        return List.of(
                "modelConfigId",
                "modelConfigSource",
                "workerBackend",
                "agentSource",
                "workerSource",
                "backendSource",
                "taskSource",
                "contextId",
                "originalTaskId",
                "original_task_id",
                "sourceTaskId",
                "source_task_id",
                "recoveryCorrelationKey",
                "recovery_correlation_key",
                "correlationKey",
                "correlation_key",
                "attemptNumber",
                "attempt_number",
                "attempt",
                "idempotencyKey",
                "idempotency_key",
                "requestedToolCount",
                "effectiveToolCount",
                "toolScopeKind",
                "toolScopeSource",
                "requestedFunctionCount",
                "effectiveFunctionCount",
                "functionScopeSource",
                "taskTokenFunctionScopeEmpty",
                "runtimeDispatched",
                "modelDispatched",
                "businessFunctionDispatched");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private DispatchTaskDTO createTaskDirect(String providerType, TaskDispatchRequest request, AgentResolveContext context) {
        DispatchTaskDTO dto = operationRouter().createTaskDirect(providerType, request, context);
        persistTaskRequestFields(dto.getTaskId(), request);
        persistContextBinding(dto, request, context, providerType);
        return dto;
    }

    private DispatchTaskDTO createTaskDirect(
            String providerType,
            TaskDispatchRequest request,
            AgentResolveContext context,
            TaskCreateTargetResolver.CreateExecutionPlan plan,
            TaskCreateCommandCoordinator.ProviderEffectGate providerEffectGate) {
        DispatchTaskDTO dto = operationRouter().createTaskDirect(
                providerType, request, context, plan, providerEffectGate);
        persistTaskRequestFields(dto.getTaskId(), request);
        persistContextBinding(dto, request, context, providerType);
        return dto;
    }

    private LifecycleIngressGate.IngressPermit reserveBeforeEffect(
            TaskDispatchRequest request) {
        if (lifecycleIngressGate == null || request == null) return null;
        return lifecycleIngressGate.reserveBeforeEffect(
                request.getSessionId(), request.getWorkerId());
    }

    private void confirmReservation(
            LifecycleIngressGate.IngressPermit permit, DispatchTaskDTO dto) {
        if (lifecycleIngressGate != null && permit != null && dto != null) {
            lifecycleIngressGate.confirm(permit, dto.getTaskId());
        }
    }

    private void releaseFailedReservation(
            LifecycleIngressGate.IngressPermit permit) {
        if (lifecycleIngressGate != null && permit != null) {
            lifecycleIngressGate.releaseFailed(permit);
        }
    }

    private void observeLifecycleLane(DispatchTaskDTO dto) {
        if (!lifecycleShadowEnabled || lifecycleLaneService == null || dto == null
                || dto.getSessionId() == null || dto.getSessionId().isBlank()
                || dto.getTaskId() == null || dto.getTaskId().isBlank()) {
            return;
        }
        lifecycleLaneService.observeReservation(
                dto.getSessionId(), dto.getTaskId(), dto.getWorkerId());
    }

    private void rejectBusyContextContinuationIfNeeded(String providerType,
                                                       TaskDispatchRequest request,
                                                       AgentResolveContext context) {
        if (!ProviderRouteRegistry.PROVIDER_LANGGRAPH_BIZ_WORKER.equals(trimToNull(providerType))
                || sessionTaskRepository == null
                || request == null) {
            return;
        }
        String contextId = trimToNull(request.getContextId());
        String sessionId = trimToNull(request.getSessionId());
        String userId = context != null ? trimToNull(context.getUserId()) : null;
        if (contextId == null || sessionId == null || userId == null) {
            return;
        }

        List<SessionTaskEntity> activeTasks =
                sessionTaskRepository.findBySessionIdAndUserIdAndProviderTypeAndStatusInOrderByCreatedAtDesc(
                        sessionId,
                        userId,
                        providerType,
                        CONTEXT_BUSY_STATES);
        if (activeTasks == null || activeTasks.isEmpty()) {
            return;
        }

        SessionTaskEntity activeTask = activeTasks.get(0);
        throw new IllegalStateException(
                "CONTEXT_RUNTIME_BUSY: contextId " + contextId
                        + " already has active task " + activeTask.getTaskId()
                        + " in status " + activeTask.getStatus()
                        + "; retry after the active task reaches a terminal state");
    }

    private boolean bindContinuationFromContext(TaskDispatchRequest request, AgentResolveContext context) {
        if (agentConversationContextRepository == null) {
            return false;
        }
        String contextId = trimToNull(request.getContextId());
        String userId = context != null ? trimToNull(context.getUserId()) : null;
        if (contextId == null || userId == null) {
            return false;
        }

        AgentConversationContextEntity boundContext = agentConversationContextRepository
                .findByContextIdAndUserId(contextId, userId)
                .orElse(null);
        if (boundContext == null) {
            return false;
        }

        validateContextAgentCompatibility(request, boundContext);

        String navigatorSessionId = trimToNull(boundContext.getNavigatorSessionId());
        if (navigatorSessionId == null) {
            return false;
        }

        String requestedSessionId = trimToNull(request.getSessionId());
        if (requestedSessionId != null && !requestedSessionId.equals(navigatorSessionId)) {
            throw new IllegalArgumentException(
                    "CONTEXT_SESSION_MISMATCH: contextId " + contextId
                            + " is bound to session " + navigatorSessionId
                            + ", but request sessionId is " + requestedSessionId);
        }

        resourceAccessService.requireOwnedSession(
                navigatorSessionId,
                userId,
                context != null ? context.getTenantId() : null);
        SessionEntity session = sessionRepository.findById(navigatorSessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "CONTEXT_SESSION_MISMATCH: contextId " + contextId
                                + " is bound to missing session " + navigatorSessionId));
        validateContextSessionCompatibility(request, session);
        request.setSessionId(navigatorSessionId);
        fillFromBoundSession(request, session);
        String targetAgentId = trimToNull(boundContext.getTargetAgentId());
        if (trimToNull(request.getAgentId()) == null && targetAgentId != null) {
            request.setAgentId(targetAgentId);
        }
        return true;
    }

    private void validateContextBindingBeforeDispatch(TaskDispatchRequest request, AgentResolveContext context) {
        if (agentConversationContextRepository == null || request == null) {
            return;
        }
        String contextId = trimToNull(request.getContextId());
        String userId = context != null ? trimToNull(context.getUserId()) : null;
        if (contextId == null || userId == null) {
            return;
        }

        agentConversationContextRepository.findById(contextId).ifPresent(existing -> {
            String existingUserId = trimToNull(existing.getUserId());
            if (existingUserId != null && !existingUserId.equals(userId)) {
                throw new IllegalArgumentException(
                        "CONTEXT_WORKER_MISMATCH: contextId " + contextId + " is already bound to another user");
            }
            String existingTargetAgentId = trimToNull(existing.getTargetAgentId());
            String requestedAgentId = trimToNull(request.getAgentId());
            if (existingTargetAgentId != null && requestedAgentId != null && !existingTargetAgentId.equals(requestedAgentId)) {
                throw new IllegalArgumentException(
                        "CONTEXT_WORKER_MISMATCH: contextId " + contextId
                                + " is already bound to agent " + existingTargetAgentId
                                + ", but request agentId is " + requestedAgentId);
            }
            String existingSessionId = trimToNull(existing.getNavigatorSessionId());
            String requestedSessionId = trimToNull(request.getSessionId());
            if (existingSessionId != null && requestedSessionId != null && !existingSessionId.equals(requestedSessionId)) {
                throw new IllegalArgumentException(
                        "CONTEXT_SESSION_MISMATCH: contextId " + contextId
                                + " is bound to session " + existingSessionId
                                + ", but request sessionId is " + requestedSessionId);
            }
        });
    }

    private void validateSessionOwnershipBeforeDispatch(TaskDispatchRequest request, AgentResolveContext context) {
        if (request == null) return;
        String sessionId = trimToNull(request.getSessionId());
        if (sessionId == null) return;
        resourceAccessService.requireOwnedSession(
                sessionId,
                context != null ? context.getUserId() : null,
                context != null ? context.getTenantId() : null);
    }

    private void validateSessionProviderBeforeDispatch(String sessionId, String requestedProviderType) {
        String normalizedSessionId = trimToNull(sessionId);
        String normalizedRequestedProvider = trimToNull(requestedProviderType);
        if (normalizedSessionId == null || normalizedRequestedProvider == null) {
            return;
        }
        sessionRepository.findById(normalizedSessionId).ifPresent(session -> {
            String boundProviderType = trimToNull(session.getProviderType());
            if (boundProviderType != null && !boundProviderType.equals(normalizedRequestedProvider)) {
                throw new SessionProviderBoundMismatchException(
                        normalizedSessionId, boundProviderType, normalizedRequestedProvider);
            }
        });
    }

    private void validateContextAgentCompatibility(TaskDispatchRequest request,
                                                   AgentConversationContextEntity boundContext) {
        String requestedAgentId = trimToNull(request.getAgentId());
        String targetAgentId = trimToNull(boundContext.getTargetAgentId());
        if (requestedAgentId != null && targetAgentId != null && !requestedAgentId.equals(targetAgentId)) {
            throw new IllegalArgumentException(
                    "CONTEXT_WORKER_MISMATCH: contextId " + boundContext.getContextId()
                            + " is bound to agent " + targetAgentId
                            + ", but request agentId is " + requestedAgentId);
        }
    }

    private void validateContextSessionCompatibility(TaskDispatchRequest request, SessionEntity session) {
        String boundProvider = trimToNull(session.getProviderType());
        String requestedProvider = trimToNull(request.getProviderType());
        if (requestedProvider != null && boundProvider != null && !requestedProvider.equals(boundProvider)) {
            throw new IllegalArgumentException(
                    "CONTEXT_WORKER_MISMATCH: providerType " + requestedProvider
                            + " conflicts with context/session-bound provider " + boundProvider);
        }

        String boundWorkerId = trimToNull(session.getCurrentWorkerId());
        String requestedWorkerId = trimToNull(request.getWorkerId());
        if (requestedWorkerId != null && boundWorkerId != null && !requestedWorkerId.equals(boundWorkerId)) {
            throw new IllegalArgumentException(
                    "CONTEXT_WORKER_MISMATCH: workerId " + requestedWorkerId
                            + " conflicts with context/session-bound worker " + boundWorkerId);
        }

        String boundDirectoryId = trimToNull(session.getCurrentDirectoryId());
        String requestedDirectoryId = trimToNull(request.getDirectoryId());
        if (requestedDirectoryId != null && boundDirectoryId != null && !requestedDirectoryId.equals(boundDirectoryId)) {
            throw new IllegalArgumentException(
                    "CONTEXT_WORKER_MISMATCH: directoryId " + requestedDirectoryId
                            + " conflicts with context/session-bound directory " + boundDirectoryId);
        }

        validateContextScopedHomeCompatibility(request, session);
    }

    private void fillFromBoundSession(TaskDispatchRequest request, SessionEntity session) {
        if (trimToNull(request.getProviderType()) == null) {
            request.setProviderType(trimToNull(session.getProviderType()));
        }
        if (trimToNull(request.getWorkerId()) == null) {
            request.setWorkerId(trimToNull(session.getCurrentWorkerId()));
        }
        if (trimToNull(request.getDirectoryId()) == null) {
            request.setDirectoryId(trimToNull(session.getCurrentDirectoryId()));
        }
        fillCodexBizScopedHomeFromBoundSession(request, session);
    }

    private void persistContextBinding(DispatchTaskDTO dto,
                                       TaskDispatchRequest request,
                                       AgentResolveContext context,
                                       String providerType) {
        if (agentConversationContextRepository == null || agentContextStore == null || dto == null) {
            return;
        }
        String contextId = trimToNull(firstNonBlank(dto.getContextId(), request.getContextId()));
        String userId = context != null ? trimToNull(context.getUserId()) : null;
        String sessionId = trimToNull(dto.getSessionId());
        if (contextId == null || userId == null || sessionId == null) {
            return;
        }

        String targetAgentId = firstNonBlank(dto.getAgentId(), request.getAgentId(), providerType, dto.getProviderType());
        String resolvedProviderType = firstNonBlank(dto.getProviderType(), providerType, request.getProviderType());
        String agentSessionRef = firstNonBlank(
                dto.getClaudeSessionId(),
                dto.getCodexThreadId(),
                dto.getGeminiSessionId(),
                dto.getWorkerTaskId());

        String effectiveTargetAgentId = firstNonBlank(targetAgentId, resolvedProviderType);
        resourceAccessService.requireOwnedSession(
                sessionId,
                userId,
                context != null ? context.getTenantId() : null);
        agentContextStore.saveSessionRefFull(
                contextId,
                firstNonBlank(resolvedProviderType, "unknown"),
                agentSessionRef,
                sessionId,
                userId,
                effectiveTargetAgentId,
                trimToNull(request.getContextAlias()));
        persistCodexBizScopedHomeBinding(sessionId, resolvedProviderType, request);
    }

    private void validateContextScopedHomeCompatibility(TaskDispatchRequest request, SessionEntity session) {
        if (!isCodexBizProvider(firstNonBlank(session.getProviderType(), request.getProviderType()))) {
            return;
        }
        String boundScopedHome = boundScopedHome(session);
        if (boundScopedHome == null) {
            return;
        }
        ScopedHomeSelection requested = requestScopedHomeSelection(request);
        if (requested.hasScopedHome()) {
            if (!boundScopedHome.equals(requested.scopedHome())) {
                throw new IllegalArgumentException(
                        "CONTEXT_WORKER_MISMATCH: " + requested.fieldName() + " " + printable(requested.scopedHome())
                                + " conflicts with context/session-bound scoped home " + boundScopedHome);
            }
        }
    }

    private void fillCodexBizScopedHomeFromBoundSession(TaskDispatchRequest request, SessionEntity session) {
        if (!isCodexBizProvider(firstNonBlank(session.getProviderType(), request.getProviderType()))
                || requestScopedHomeSelection(request).hasScopedHome()) {
            return;
        }

        String boundScopedHome = boundScopedHome(session);
        if (boundScopedHome == null) {
            return;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        metadata.put(ProviderStateCodec.FIELD_CODEX_HOME_KEY, boundScopedHome);
        metadata.put(ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID,
                firstNonBlank(readProviderState(session, ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID), boundScopedHome));
        request.setMetadata(metadata);
    }

    private void persistCodexBizScopedHomeBinding(String sessionId, String providerType, TaskDispatchRequest request) {
        if (!isCodexBizProvider(providerType)) {
            return;
        }
        ScopedHomeSelection requested = requestScopedHomeSelection(request);
        if (!requested.hasScopedHome()) {
            return;
        }
        sessionRepository.findById(sessionId).ifPresent(session -> {
            String boundScopedHome = boundScopedHome(session);
            if (boundScopedHome != null && !boundScopedHome.equals(requested.scopedHome())) {
                throw new IllegalArgumentException(
                        "CONTEXT_WORKER_MISMATCH: " + requested.fieldName() + " " + printable(requested.scopedHome())
                                + " conflicts with context/session-bound scoped home " + boundScopedHome);
            }
            String effectiveScopedHome = requested.scopedHome();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(ProviderStateCodec.FIELD_CODEX_HOME_KEY, effectiveScopedHome);
            values.put(ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID,
                    firstNonBlank(requested.privateAccountId(), effectiveScopedHome));
            session.setProviderStateJson(ProviderStateCodec.mergeSessionValues(
                    session.getProviderStateJson(),
                    ProviderRouteRegistry.PROVIDER_CODEX_BIZ_WORKER,
                    values));
            sessionRepository.save(session);
        });
    }

    private boolean isCodexBizProvider(String providerType) {
        return ProviderRouteRegistry.PROVIDER_CODEX_BIZ_WORKER.equals(trimToNull(providerType));
    }

    private String boundScopedHome(SessionEntity session) {
        return firstNonBlank(
                readProviderState(session, ProviderStateCodec.FIELD_CODEX_HOME_KEY),
                readProviderState(session, ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID));
    }

    private String readProviderState(SessionEntity session, String fieldName) {
        if (session == null) {
            return null;
        }
        return trimToNull(ProviderStateCodec.readStringOrNull(session.getProviderStateJson(), fieldName));
    }

    private ScopedHomeSelection requestScopedHomeSelection(TaskDispatchRequest request) {
        if (request == null || request.getMetadata() == null || request.getMetadata().isEmpty()) {
            return ScopedHomeSelection.empty();
        }
        ScopedHomeValue codexHome = firstMetadataValue(
                request.getMetadata(),
                ProviderStateCodec.FIELD_CODEX_HOME_KEY,
                "codex_home_key");
        ScopedHomeValue privateAccount = firstMetadataValue(
                request.getMetadata(),
                ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID,
                "private_account_id");
        ScopedHomeValue effective = codexHome != null ? codexHome : privateAccount;
        if (effective == null) {
            return ScopedHomeSelection.empty();
        }
        return new ScopedHomeSelection(
                effective.fieldName(),
                effective.value(),
                codexHome != null ? codexHome.value() : null,
                privateAccount != null ? privateAccount.value() : null);
    }

    private ScopedHomeValue firstMetadataValue(Map<String, Object> metadata, String... fieldNames) {
        for (String fieldName : fieldNames) {
            Object raw = metadata.get(fieldName);
            if (raw instanceof String text) {
                String normalized = trimToNull(text);
                if (normalized != null) {
                    return new ScopedHomeValue(fieldName, normalized);
                }
            }
        }
        return null;
    }

    private record ScopedHomeValue(String fieldName, String value) {}

    private record ScopedHomeSelection(String fieldName,
                                       String scopedHome,
                                       String codexHomeKey,
                                       String privateAccountId) {
        private static ScopedHomeSelection empty() {
            return new ScopedHomeSelection(null, null, null, null);
        }

        private boolean hasScopedHome() {
            return scopedHome != null;
        }
    }

    private String printable(String value) {
        return "'" + value + "'";
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private AgentResolveContext executionContext(
            TaskCreateTargetResolver.CreateExecutionPlan plan,
            AgentResolveContext source) {
        return AgentResolveContext.builder()
                .userId(plan.ownerUserId())
                .tenantId(plan.tenantId())
                .sessionId(plan.sessionId())
                .modelConfigId(plan.modelConfigId())
                .requestSource(plan.agentLookup() != null
                        ? plan.agentLookup().requestSource()
                        : source.getRequestSource())
                .build();
    }

    private String resolveLogicalAgentId(A2aAgent agent, String lookupId) {
        if (agent.getAgentCard() != null
                && agent.getAgentCard().getId() != null
                && !agent.getAgentCard().getId().isBlank()) {
            return agent.getAgentCard().getId();
        }
        return lookupId;
    }

}
