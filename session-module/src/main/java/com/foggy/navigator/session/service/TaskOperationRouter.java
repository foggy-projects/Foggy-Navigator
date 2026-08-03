package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.NativeSubtaskStateRepository;
import com.foggy.navigator.common.util.DirectoryAgentId;
import com.foggy.navigator.session.exception.SessionProviderBoundMismatchException;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.InternalTaskDispatchMarkers;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
final class TaskOperationRouter {

    private static final Set<String> TERMINAL_STATES = Set.of("COMPLETED", "FAILED", "ABORTED");

    private final UnifiedAgentResolver agentResolver;
    private final SessionBindingService bindingService;
    private final SessionRepository sessionRepository;
    private final SessionTaskResourceAccessService resourceAccessService;
    @Nullable
    private final SessionTaskRepository sessionTaskRepository;
    @Nullable
    private final NativeSubtaskStateRepository nativeSubtaskStateRepository;
    private final TaskQueryProviderRegistry taskQueryProviderRegistry;
    private final TaskCreateTargetResolver createTargetResolver;
    private final UnifiedSessionTaskProjectionService projectionService;

    TaskOperationRouter(UnifiedAgentResolver agentResolver,
                        SessionBindingService bindingService,
                        SessionRepository sessionRepository,
                        SessionTaskResourceAccessService resourceAccessService,
                        @Nullable SessionTaskRepository sessionTaskRepository,
                        @Nullable NativeSubtaskStateRepository nativeSubtaskStateRepository,
                        TaskQueryProviderRegistry taskQueryProviderRegistry,
                        TaskCreateTargetResolver createTargetResolver,
                        UnifiedSessionTaskProjectionService projectionService) {
        this.agentResolver = agentResolver;
        this.bindingService = bindingService;
        this.sessionRepository = sessionRepository;
        this.resourceAccessService = resourceAccessService;
        this.sessionTaskRepository = sessionTaskRepository;
        this.nativeSubtaskStateRepository = nativeSubtaskStateRepository;
        this.taskQueryProviderRegistry = taskQueryProviderRegistry;
        this.createTargetResolver = createTargetResolver;
        this.projectionService = projectionService;
    }

    Optional<DispatchTaskDTO> getTask(String taskId, AgentResolveContext context) {
        requireOwnedTask(taskId, context);
        if (sessionTaskRepository != null) {
            Optional<DispatchTaskDTO> unified = context.getUserId() != null
                    ? sessionTaskRepository.findByTaskIdAndUserId(taskId, context.getUserId())
                    .map(projectionService::toDispatchTaskDTO)
                    : sessionTaskRepository.findByTaskId(taskId).map(projectionService::toDispatchTaskDTO);
            if (unified.isPresent()) {
                return unified;
            }
        }

        for (TaskLookupProvider provider : taskQueryProviderRegistry.lookupProviders()) {
            Optional<DispatchTaskDTO> result = context.getUserId() != null
                    ? provider.getTaskByIdAndUser(taskId, context.getUserId())
                    : provider.getTaskById(taskId);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    DispatchTaskDTO createTaskDirect(String providerType, TaskDispatchRequest request, AgentResolveContext context) {
        validateRequestedProviderTypeCompatibility(request.getProviderType(), providerType);
        validateModelConfigProviderCompatibility(request.getModelConfigId(), providerType);

        TaskCommandProvider provider = findTaskCommandProviderByType(providerType)
                .orElseThrow(() -> new IllegalArgumentException("Provider not available: " + providerType));
        Map<String, Object> params = TaskDispatchRequestParams.toCommonParams(request);
        if (request.isInitializeRuntimeAffinity()) {
            InternalTaskDispatchMarkers.markRuntimeAffinityInitialization(params);
        }
        DispatchTaskDTO dto = provider.createTaskDirect(
                params, context.getUserId(), context.getTenantId());
        log.info("Dispatched task directly via provider: providerType={}, taskId={}, workerId={}, directoryId={}",
                providerType, dto.getTaskId(), request.getWorkerId(), request.getDirectoryId());
        return dto;
    }

    /** Resolves the exact Direct Provider without constructing payload or invoking it. */
    ResolvedDirectCreate resolveGuardedDirectCreate(
            String providerType,
            TaskDispatchRequest request) {
        validateRequestedProviderTypeCompatibility(request.getProviderType(), providerType);
        validateModelConfigProviderCompatibility(request.getModelConfigId(), providerType);
        TaskCommandProvider provider = findTaskCommandProviderByType(providerType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Provider not available: " + providerType));
        return new ResolvedDirectCreate(providerType, provider);
    }

    /** Captures Provider-only parameters after the fresh-task participant has completed. */
    CapturedDirectCreate captureGuardedDirectCreate(
            ResolvedDirectCreate route,
            TaskDispatchRequest providerRequest,
            AgentResolveContext context) {
        Objects.requireNonNull(route, "resolved Direct route is required");
        Objects.requireNonNull(providerRequest, "captured Direct request is required");
        Objects.requireNonNull(context, "captured Direct context is required");
        String actualProviderType = route.requireExpectedActualProviderType();
        Map<String, Object> params = TaskDispatchRequestParams.toCommonParams(providerRequest);
        if (providerRequest.isInitializeRuntimeAffinity()) {
            InternalTaskDispatchMarkers.markRuntimeAffinityInitialization(params);
        }
        return new CapturedDirectCreate(
                route,
                actualProviderType,
                params,
                context.getUserId(),
                context.getTenantId());
    }

    /** The sole guarded Direct Provider call; all inputs were captured after permission. */
    DispatchTaskDTO executeCapturedDirectCreate(CapturedDirectCreate captured) {
        Objects.requireNonNull(captured, "captured Direct create is required");
        captured.requireProviderIdentityUnchanged();
        DispatchTaskDTO dto = captured.route.provider.createTaskDirect(
                captured.params,
                captured.ownerUserId,
                captured.tenantId);
        log.info("Dispatched task directly via provider: providerType={}, taskId={}, workerId={}, directoryId={}",
                captured.actualProviderType,
                dto.getTaskId(),
                captured.params.get("workerId"),
                captured.params.get("directoryId"));
        return dto;
    }

    static final class ResolvedDirectCreate {
        private final String expectedProviderType;
        private final TaskCommandProvider provider;

        private ResolvedDirectCreate(
                String expectedProviderType,
                TaskCommandProvider provider) {
            this.expectedProviderType = requireText(
                    expectedProviderType, "resolved Direct Provider is required");
            this.provider = Objects.requireNonNull(provider, "Direct Provider is required");
        }

        String actualProviderType() {
            return requireText(
                    provider.getProviderType(),
                    "Direct Provider returned no identity");
        }

        private String requireExpectedActualProviderType() {
            String actual = actualProviderType();
            if (!expectedProviderType.equals(actual)) {
                throw new IllegalStateException(
                        "TASK_CREATE_DIRECT_PROVIDER_IDENTITY_CHANGED");
            }
            return actual;
        }
    }

    static final class CapturedDirectCreate {
        private final ResolvedDirectCreate route;
        private final String actualProviderType;
        private final Map<String, Object> params;
        private final String ownerUserId;
        @Nullable
        private final String tenantId;

        private CapturedDirectCreate(
                ResolvedDirectCreate route,
                String actualProviderType,
                Map<String, Object> params,
                String ownerUserId,
                @Nullable String tenantId) {
            this.route = Objects.requireNonNull(route, "resolved Direct route is required");
            this.actualProviderType = requireText(
                    actualProviderType, "captured Direct Provider is required");
            this.params = Objects.requireNonNull(params, "captured Direct params are required");
            this.ownerUserId = requireText(ownerUserId, "captured Direct owner is required");
            this.tenantId = tenantId;
        }

        private void requireProviderIdentityUnchanged() {
            if (!actualProviderType.equals(route.actualProviderType())) {
                throw new IllegalStateException(
                        "TASK_CREATE_DIRECT_PROVIDER_IDENTITY_CHANGED");
            }
        }
    }

    TaskTerminationCommandCoordinator.TerminationExecutionPlan
    resolveTerminationExecutionPlan(
            String taskId,
            AgentResolveContext context,
            boolean force) {
        DispatchTaskDTO task = getTask(taskId, context)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        String terminalStatus = terminalStatus(task);
        String providerType = task.getProviderType();
        boolean providerRoute = providerType != null && !providerType.isBlank();
        TaskTerminationCommandCoordinator.TerminationIdentity identity =
                TaskTerminationCommandCoordinator.TerminationIdentity.from(
                        task, context, force);
        if (terminalStatus != null) {
            return new TaskTerminationCommandCoordinator.TerminationExecutionPlan(
                    identity, context, terminalStatus, null);
        }
        if (!providerRoute && force) {
            throw new UnsupportedOperationException(
                    "force cancel not supported by the A2A route for task " + taskId);
        }
        if (!providerRoute && (task.getAgentId() == null || task.getAgentId().isBlank())) {
            throw new IllegalArgumentException(
                    "Cannot cancel task " + taskId
                            + ": agentId is missing and provider route is unavailable");
        }

        if (providerRoute) {
            TaskCommandProvider provider = findTaskCommandProviderByType(providerType)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Provider not found: " + providerType));
            requireTerminationProviderIdentity(provider, providerType);
            return new TaskTerminationCommandCoordinator.TerminationExecutionPlan(
                    identity,
                    context,
                    null,
                    new TaskTerminationCommandCoordinator.CapturedTerminationEffect(() -> {
                        requireTerminationProviderIdentity(provider, providerType);
                        if (identity.force()) {
                            provider.cancelTaskDirect(
                                    identity.taskId(), identity.ownerUserId(), true);
                        } else {
                            provider.cancelTaskDirect(
                                    identity.taskId(), identity.ownerUserId());
                        }
                        log.info("Canonical termination request accepted via captured Provider: "
                                        + "taskId={}, providerType={}, force={}",
                                identity.taskId(), identity.providerType(), identity.force());
                        return TaskTerminationCommandCoordinator.Outcome.accepted();
                    }));
        }

        A2aAgent agent = agentResolver.resolveAgent(task.getAgentId(), context)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot cancel task " + taskId
                                + ": no A2A agent found for agentId=" + task.getAgentId()));
        if (context.getSessionId() != null) {
            bindingService.validateBinding(context.getSessionId(), task.getAgentId());
        }
        return new TaskTerminationCommandCoordinator.TerminationExecutionPlan(
                identity,
                context,
                null,
                new TaskTerminationCommandCoordinator.CapturedTerminationEffect(() -> {
                    agent.cancelTask(identity.taskId());
                    log.info("Canonical termination request accepted via captured A2A Agent: "
                                    + "taskId={}, agentId={}",
                            identity.taskId(), identity.logicalAgentId());
                    return TaskTerminationCommandCoordinator.Outcome.accepted();
                }));
    }

    @Nullable
    String requireTerminationPlanCurrent(
            TaskTerminationCommandCoordinator.TerminationExecutionPlan plan) {
        DispatchTaskDTO current = currentTerminationTask(plan);
        String terminalStatus = terminalStatus(current);
        if (plan.initiallyTerminalStatus() != null && terminalStatus == null) {
            throw new IllegalStateException("TERMINATION_TERMINAL_STATE_REGRESSION");
        }
        if (terminalStatus == null
                && plan.identity().executionRoute()
                == TaskTerminationCommandCoordinator.ExecutionRoute.A2A
                && plan.context().getSessionId() != null) {
            bindingService.validateBinding(
                    plan.context().getSessionId(), plan.identity().logicalAgentId());
        }
        return terminalStatus;
    }

    TaskTerminationCommandCoordinator.Outcome executeTerminationPlan(
            TaskTerminationCommandCoordinator.TerminationExecutionPlan plan,
            TaskTerminationCommandCoordinator.TerminationEffectGate effectGate) {
        Objects.requireNonNull(effectGate, "termination effect gate must not be null");
        String terminalStatus = requireTerminationPlanCurrent(plan);
        if (terminalStatus != null) {
            log.info("canonical termination: task {} already terminal ({})",
                    plan.identity().taskId(), terminalStatus);
        }
        return effectGate.invoke(plan, () -> requireTerminationPlanCurrent(plan));
    }

    private DispatchTaskDTO currentTerminationTask(
            TaskTerminationCommandCoordinator.TerminationExecutionPlan plan) {
        Objects.requireNonNull(plan, "termination plan must not be null");
        DispatchTaskDTO current = getTask(plan.identity().taskId(), plan.context())
                .orElseThrow(() -> new IllegalStateException(
                        "TERMINATION_TASK_UNAVAILABLE"));
        TaskTerminationCommandCoordinator.TerminationIdentity actual =
                TaskTerminationCommandCoordinator.TerminationIdentity.from(
                        current, plan.context(), plan.identity().force());
        if (!plan.identity().equals(actual)) {
            throw new IllegalStateException("TERMINATION_PLAN_IDENTITY_CONFLICT");
        }
        return current;
    }

    @Nullable
    private static String terminalStatus(DispatchTaskDTO task) {
        String status = task.getStatus();
        return status != null && TERMINAL_STATES.contains(status) ? status : null;
    }

    private static void requireTerminationProviderIdentity(
            TaskCommandProvider provider,
            String expectedProviderType) {
        String actualProviderType = provider.getProviderType();
        if (actualProviderType == null
                || actualProviderType.isBlank()
                || !actualProviderType.equals(expectedProviderType)) {
            throw new IllegalStateException(
                    "TERMINATION_PROVIDER_IDENTITY_CHANGED");
        }
    }

    /**
     * 已知 providerType 的任务优先走 Provider 取消。
     * 统一任务投影中的 agentId 保存真实 logical agent，不能用它来判断是否需要 A2A。
     */
    void cancelTask(String taskId, String agentId, AgentResolveContext context, boolean force) {
        DispatchTaskDTO task = getTask(taskId, context).orElse(null);
        if (task != null && task.getStatus() != null && TERMINAL_STATES.contains(task.getStatus())) {
            log.info("cancelTask: task {} already in terminal state ({}), returning no-op",
                    taskId, task.getStatus());
            return;
        }

        // Caller-supplied agentId is routing input, never authorization or ownership evidence.
        String effectiveAgentId = task != null ? task.getAgentId() : null;
        String providerType = task != null ? task.getProviderType() : null;

        if (providerType != null && !providerType.isBlank()) {
            cancelTaskViaProvider(taskId, context.getUserId(), providerType, force);
            return;
        }

        if (force) {
            throw new UnsupportedOperationException(
                    "force cancel not supported by the A2A route for task " + taskId);
        }

        if (effectiveAgentId == null || effectiveAgentId.isBlank()) {
            throw new IllegalArgumentException(
                    "Cannot cancel task " + taskId + ": agentId is missing and provider route is unavailable");
        }

        A2aAgent agent = agentResolver.resolveAgent(effectiveAgentId, context)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot cancel task " + taskId + ": no A2A agent found for agentId=" + effectiveAgentId));

        if (context.getSessionId() != null) {
            bindingService.validateBinding(context.getSessionId(), effectiveAgentId);
        }
        agent.cancelTask(taskId);
        log.info("Cancelled task via A2a Agent: taskId={}, agentId={}", taskId, effectiveAgentId);
    }

    void respondToTask(String taskId, AgentResolveContext context, Map<String, Object> response) {
        requireOwnedTask(taskId, context);
        TaskCommandProvider provider = findProviderForTask(taskId);
        provider.respondToTask(taskId, context.getUserId(), response);
    }

    void reconnectTask(String taskId, AgentResolveContext context) {
        requireOwnedTask(taskId, context);
        TaskCommandProvider provider = findProviderForTask(taskId);
        provider.reconnectTask(taskId, context.getUserId());
    }

    Object resyncTask(String taskId, AgentResolveContext context) {
        requireOwnedTask(taskId, context);
        TaskCommandProvider provider = findProviderForTask(taskId);
        return provider.resyncTask(taskId, context.getUserId());
    }

    Object rewindTask(String taskId, AgentResolveContext context, Map<String, Object> params) {
        requireOwnedTask(taskId, context);
        TaskCommandProvider provider = findProviderForTask(taskId);
        return provider.rewindTask(taskId, context.getUserId(), params);
    }

    DispatchTaskDTO resumeTask(TaskDispatchRequest request, AgentResolveContext context) {
        requireOwnedSession(request != null ? request.getSessionId() : null, context);
        String providerType = resolveResumeProviderType(request, context);
        normalizeResumeRequest(request, providerType);
        validateRequestedProviderTypeCompatibility(request.getProviderType(), providerType);
        validateModelConfigProviderCompatibility(request.getModelConfigId(), providerType);

        TaskCommandProvider provider = taskQueryProviderRegistry.findCommandProviderByType(providerType)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerType));

        Map<String, Object> params = TaskDispatchRequestParams.toCommonParams(request);
        return provider.resumeTask(context.getUserId(), context.getTenantId(), params);
    }

    /**
     * Deletes provider state first, then local projections in recoverable stages.
     * The unified projection is deliberately removed last: if a later cleanup fails,
     * it remains the ownership and routing marker for an idempotent retry.
     */
    void deleteTask(String taskId, AgentResolveContext context) {
        requireOwnedTask(taskId, context);
        String userId = context.getUserId();
        TaskCommandProvider provider = findProviderForTask(taskId);
        boolean ownsUnifiedProjection = sessionTaskRepository != null
                && sessionTaskRepository.findByTaskIdAndUserId(taskId, userId).isPresent();
        boolean shouldCleanupSessionStore = false;
        try {
            provider.deleteTask(userId, taskId);
            shouldCleanupSessionStore = true;
        } catch (IllegalArgumentException e) {
            if (!isProviderTaskAlreadyMissing(e, taskId) || !ownsUnifiedProjection) {
                throw e;
            }
            log.warn("Provider task already missing during delete; cleaning unified session store only: taskId={}", taskId);
            shouldCleanupSessionStore = true;
        }

        // Keep the unified projection as the retry marker until provider/native cleanup succeeds.
        if (shouldCleanupSessionStore && nativeSubtaskStateRepository != null) {
            nativeSubtaskStateRepository.deleteByTaskId(taskId);
        }
        if (shouldCleanupSessionStore && sessionTaskRepository != null) {
            sessionTaskRepository.deleteByTaskId(taskId);
        }
    }

    Object scanCheckpoints(String taskId, AgentResolveContext context) {
        requireOwnedTask(taskId, context);
        TaskCommandProvider provider = findProviderForTask(taskId);
        return provider.scanCheckpoints(taskId, context.getUserId());
    }

    private void requireOwnedTask(String taskId, AgentResolveContext context) {
        resourceAccessService.requireOwnedTask(
                taskId,
                context != null ? context.getUserId() : null,
                context != null ? context.getTenantId() : null);
    }

    private void requireOwnedSession(String sessionId, AgentResolveContext context) {
        resourceAccessService.requireOwnedSession(
                sessionId,
                context != null ? context.getUserId() : null,
                context != null ? context.getTenantId() : null);
    }

    void validateRequestedProviderTypeCompatibility(String requestedProviderType, String resolvedProviderType) {
        if (requestedProviderType == null || requestedProviderType.isBlank()
                || resolvedProviderType == null || resolvedProviderType.isBlank()) {
            return;
        }

        if (!resolvedProviderType.equals(requestedProviderType)) {
            throw new IllegalArgumentException(
                    "providerType " + requestedProviderType + " conflicts with resolved provider " + resolvedProviderType);
        }
    }

    void validateModelConfigProviderCompatibility(String modelConfigId, String providerType) {
        if (modelConfigId == null || modelConfigId.isBlank() || providerType == null || providerType.isBlank()) {
            return;
        }

        String modelProviderType = createTargetResolver.resolveProviderTypeFromModelConfig(modelConfigId);
        if (modelProviderType == null || modelProviderType.isBlank()) {
            return;
        }

        if (!TaskCreateTargetResolver.isModelProviderCompatible(modelProviderType, providerType)) {
            throw new IllegalArgumentException(
                    "modelConfigId " + modelConfigId + " targets provider " + modelProviderType
                            + ", but resolved provider is " + providerType);
        }
    }

    private String resolveResumeProviderType(TaskDispatchRequest request, AgentResolveContext context) {
        String sessionId = request.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
            if (session != null) {
                String boundProviderType = session.getProviderType();
                if (boundProviderType != null && !boundProviderType.isBlank()) {
                    return boundProviderType;
                }
            }
        }
        return resolveResumeProviderTypeFromLegacyContext(request, context);
    }

    private String resolveResumeProviderTypeFromLegacyContext(TaskDispatchRequest request, AgentResolveContext context) {
        @SuppressWarnings("deprecation")
        String requestedProviderType = request.getProviderType();
        String fromModelConfig = createTargetResolver.resolveProviderTypeFromModelConfig(request.getModelConfigId());
        if (requestedProviderType != null && !requestedProviderType.isBlank()
                && findTaskCommandProviderByType(requestedProviderType).isPresent()
                && (fromModelConfig == null
                || TaskCreateTargetResolver.isModelProviderCompatible(fromModelConfig, requestedProviderType))) {
            return requestedProviderType;
        }

        if (fromModelConfig != null && !fromModelConfig.isBlank()) {
            return fromModelConfig;
        }

        if (requestedProviderType != null && !requestedProviderType.isBlank()) {
            return requestedProviderType;
        }

        String agentLookupId = createTargetResolver.resolveBoundOrExplicitAgentId(
                request.getAgentId(), request.getSessionId());
        if (agentLookupId != null && !agentLookupId.isBlank()) {
            if (DirectoryAgentId.isDirectoryAgent(agentLookupId)) {
                String dirId = DirectoryAgentId.extractDirectoryId(agentLookupId);
                String dirModelConfigId = createTargetResolver.resolveModelConfigIdFromDirectory(null, dirId);
                if (dirModelConfigId != null) {
                    String providerType = createTargetResolver.resolveProviderTypeFromModelConfig(dirModelConfigId);
                    if (providerType != null) {
                        return providerType;
                    }
                }
            }
            return agentResolver.getProviderType(agentLookupId, context)
                    .orElseThrow(() -> new IllegalArgumentException("No provider found for agent: " + agentLookupId));
        }

        throw new IllegalArgumentException(
                "No provider found for resume request; old sessions require session.providerType or explicit providerType/modelConfigId/agentId");
    }

    /**
     * Resume 上下文规范化：session 已绑定 provider 时，拒绝任何显式或模型侧的跨 Provider 续接。
     */
    private void normalizeResumeRequest(TaskDispatchRequest request, String resolvedProviderType) {
        if (resolvedProviderType == null || resolvedProviderType.isBlank()) {
            return;
        }

        String reqProvider = request.getProviderType();
        if (reqProvider == null || reqProvider.isBlank()) {
            request.setProviderType(resolvedProviderType);
        } else if (!resolvedProviderType.equals(reqProvider)) {
            throw new SessionProviderBoundMismatchException(
                    request.getSessionId(), resolvedProviderType, reqProvider);
        }

        String modelConfigId = request.getModelConfigId();
        if (modelConfigId != null && !modelConfigId.isBlank()) {
            String modelProviderType = createTargetResolver.resolveProviderTypeFromModelConfig(modelConfigId);
            if (modelProviderType != null
                    && !TaskCreateTargetResolver.isModelProviderCompatible(modelProviderType, resolvedProviderType)) {
                throw new SessionProviderBoundMismatchException(
                        request.getSessionId(), resolvedProviderType, modelProviderType);
            }
        }
    }

    private TaskCommandProvider findProviderForTask(String taskId) {
        if (sessionTaskRepository != null) {
            String providerType = sessionTaskRepository.findByTaskId(taskId)
                    .map(SessionTaskEntity::getProviderType)
                    .orElse(null);
            if (providerType != null && !providerType.isBlank()) {
                return findTaskCommandProviderByType(providerType)
                        .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerType));
            }
        }

        return taskQueryProviderRegistry.findCommandProviderForTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private Optional<TaskCommandProvider> findTaskCommandProviderByType(String providerType) {
        return taskQueryProviderRegistry.findCommandProviderByType(providerType);
    }

    private void cancelTaskViaProvider(String taskId, String userId, @Nullable String providerType,
                                       boolean force) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Cannot cancel task " + taskId + ": userId is required for provider route");
        }
        TaskCommandProvider provider = providerType != null && !providerType.isBlank()
                ? findTaskCommandProviderByType(providerType)
                        .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerType))
                : findProviderForTask(taskId);
        if (force) {
            provider.cancelTaskDirect(taskId, userId, true);
        } else {
            provider.cancelTaskDirect(taskId, userId);
        }
        log.info("Cancellation request accepted via provider route: taskId={}, providerType={}, force={}",
                taskId, provider.getProviderType(), force);
    }

    private boolean isProviderTaskAlreadyMissing(IllegalArgumentException exception, String taskId) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.equals("Task not found: " + taskId) || message.contains("Task not found");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String requireText(@Nullable String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value;
    }
}
