package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.DirectoryAgentId;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
final class TaskOperationRouter {

    private static final Set<String> TERMINAL_STATES = Set.of("COMPLETED", "FAILED", "ABORTED");

    private final UnifiedAgentResolver agentResolver;
    private final SessionBindingService bindingService;
    private final SessionRepository sessionRepository;
    @Nullable
    private final SessionTaskRepository sessionTaskRepository;
    private final TaskQueryProviderRegistry taskQueryProviderRegistry;
    private final TaskCreateTargetResolver createTargetResolver;
    private final UnifiedSessionTaskProjectionService projectionService;

    TaskOperationRouter(UnifiedAgentResolver agentResolver,
                        SessionBindingService bindingService,
                        SessionRepository sessionRepository,
                        @Nullable SessionTaskRepository sessionTaskRepository,
                        TaskQueryProviderRegistry taskQueryProviderRegistry,
                        TaskCreateTargetResolver createTargetResolver,
                        UnifiedSessionTaskProjectionService projectionService) {
        this.agentResolver = agentResolver;
        this.bindingService = bindingService;
        this.sessionRepository = sessionRepository;
        this.sessionTaskRepository = sessionTaskRepository;
        this.taskQueryProviderRegistry = taskQueryProviderRegistry;
        this.createTargetResolver = createTargetResolver;
        this.projectionService = projectionService;
    }

    Optional<DispatchTaskDTO> getTask(String taskId, AgentResolveContext context) {
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
        DispatchTaskDTO dto = provider.createTaskDirect(params, context.getUserId(), context.getTenantId());
        log.info("Dispatched task directly via provider: providerType={}, taskId={}, workerId={}, directoryId={}",
                providerType, dto.getTaskId(), request.getWorkerId(), request.getDirectoryId());
        return dto;
    }

    /**
     * 已知 providerType 的任务优先走 Provider 取消。
     * 统一任务投影中的 agentId 保存真实 logical agent，不能用它来判断是否需要 A2A。
     */
    void cancelTask(String taskId, String agentId, AgentResolveContext context) {
        DispatchTaskDTO task = getTask(taskId, context).orElse(null);
        if (task != null && task.getStatus() != null && TERMINAL_STATES.contains(task.getStatus())) {
            log.info("cancelTask: task {} already in terminal state ({}), returning no-op",
                    taskId, task.getStatus());
            return;
        }

        String effectiveAgentId = firstNonBlank(agentId, task != null ? task.getAgentId() : null);
        String providerType = task != null ? task.getProviderType() : null;

        if (providerType != null && !providerType.isBlank()) {
            cancelTaskViaProvider(taskId, context.getUserId(), providerType);
            return;
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

    void respondToTask(String taskId, String userId, Map<String, Object> response) {
        TaskCommandProvider provider = findProviderForTask(taskId);
        provider.respondToTask(taskId, userId, response);
    }

    void reconnectTask(String taskId, String userId) {
        TaskCommandProvider provider = findProviderForTask(taskId);
        provider.reconnectTask(taskId, userId);
    }

    Object resyncTask(String taskId, String userId) {
        TaskCommandProvider provider = findProviderForTask(taskId);
        return provider.resyncTask(taskId, userId);
    }

    Object rewindTask(String taskId, String userId, Map<String, Object> params) {
        TaskCommandProvider provider = findProviderForTask(taskId);
        return provider.rewindTask(taskId, userId, params);
    }

    DispatchTaskDTO resumeTask(TaskDispatchRequest request, AgentResolveContext context) {
        String providerType = resolveResumeProviderType(request, context);
        normalizeResumeRequest(request, providerType);
        validateRequestedProviderTypeCompatibility(request.getProviderType(), providerType);
        validateModelConfigProviderCompatibility(request.getModelConfigId(), providerType);

        TaskCommandProvider provider = taskQueryProviderRegistry.findCommandProviderByType(providerType)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerType));

        Map<String, Object> params = TaskDispatchRequestParams.toCommonParams(request);
        return provider.resumeTask(context.getUserId(), context.getTenantId(), params);
    }

    void deleteTask(String taskId, String userId) {
        TaskCommandProvider provider = findProviderForTask(taskId);
        boolean shouldCleanupSessionStore = false;
        try {
            provider.deleteTask(userId, taskId);
            shouldCleanupSessionStore = true;
        } catch (IllegalArgumentException e) {
            if (!isProviderTaskAlreadyMissing(e, taskId)) {
                throw e;
            }
            log.warn("Provider task already missing during delete; cleaning unified session store only: taskId={}", taskId);
            shouldCleanupSessionStore = true;
        }

        if (shouldCleanupSessionStore && sessionTaskRepository != null) {
            sessionTaskRepository.deleteByTaskId(taskId);
        }
    }

    Object scanCheckpoints(String taskId, String userId) {
        TaskCommandProvider provider = findProviderForTask(taskId);
        return provider.scanCheckpoints(taskId, userId);
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
     * Resume 上下文规范化：session 已绑定 provider 时，静默修正冲突的 providerType/modelConfigId。
     */
    private void normalizeResumeRequest(TaskDispatchRequest request, String resolvedProviderType) {
        if (resolvedProviderType == null || resolvedProviderType.isBlank()) {
            return;
        }

        String reqProvider = request.getProviderType();
        if (reqProvider != null && !reqProvider.isBlank() && !resolvedProviderType.equals(reqProvider)) {
            log.info("Resume normalize: overriding providerType {} -> {} (session-bound)",
                    reqProvider, resolvedProviderType);
            request.setProviderType(resolvedProviderType);
        }

        String modelConfigId = request.getModelConfigId();
        if (modelConfigId != null && !modelConfigId.isBlank()) {
            String modelProviderType = createTargetResolver.resolveProviderTypeFromModelConfig(modelConfigId);
            if (modelProviderType != null
                    && !TaskCreateTargetResolver.isModelProviderCompatible(modelProviderType, resolvedProviderType)) {
                log.info("Resume normalize: clearing modelConfigId {} (targets {}, session bound to {})",
                        modelConfigId, modelProviderType, resolvedProviderType);
                request.setModelConfigId(null);
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

    private void cancelTaskViaProvider(String taskId, String userId, @Nullable String providerType) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Cannot cancel task " + taskId + ": userId is required for provider route");
        }
        TaskCommandProvider provider = findTaskCommandProviderByType(providerType)
                .orElseGet(() -> findProviderForTask(taskId));
        provider.cancelTaskDirect(taskId, userId);
        log.info("Cancelled task via provider route: taskId={}, providerType={}", taskId, provider.getProviderType());
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
}
