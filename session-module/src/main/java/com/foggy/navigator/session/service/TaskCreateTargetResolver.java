package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.util.DirectoryAgentId;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.session.exception.SessionProviderBoundMismatchException;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.SessionCodingAgentRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves one immutable, content-free target before any task-create effect. */
final class TaskCreateTargetResolver {

    private static final String LOCAL_CLAUDE_WORKER = "LOCAL_CLAUDE_WORKER";
    private static final String LOCAL_CODEX_WORKER = "LOCAL_CODEX_WORKER";
    private static final String LOCAL_GEMINI_WORKER = "LOCAL_GEMINI_WORKER";
    private static final String LOCAL_LANGGRAPH_WORKER = "LOCAL_LANGGRAPH_WORKER";
    private static final String EXTERNAL_A2A = "EXTERNAL_A2A";
    private static final Set<String> RESERVED_TARGET_METADATA_KEYS = Set.of(
            "agentId",
            "providerType",
            "sessionId",
            "contextId",
            "workerId",
            "directoryId",
            "model",
            "modelConfigId",
            "userId",
            "tenantId",
            "ownerUserId",
            "ownerType",
            "ownerId");

    private final SessionRepository sessionRepository;
    private final SessionTaskResourceAccessService resourceAccessService;
    @Nullable
    private final WorkingDirectoryRepository workingDirectoryRepository;
    @Nullable
    private final SessionCodingAgentRepository codingAgentRepository;
    private final TaskQueryProviderRegistry taskQueryProviderRegistry;
    private final LlmModelManager llmModelManager;
    @Nullable
    private final WorkerManagementFacade workerManagementFacade;
    private final UnifiedAgentResolver agentResolver;

    TaskCreateTargetResolver(SessionRepository sessionRepository,
                             SessionTaskResourceAccessService resourceAccessService,
                             @Nullable WorkingDirectoryRepository workingDirectoryRepository,
                             @Nullable SessionCodingAgentRepository codingAgentRepository,
                             TaskQueryProviderRegistry taskQueryProviderRegistry,
                             LlmModelManager llmModelManager,
                             @Nullable WorkerManagementFacade workerManagementFacade,
                             UnifiedAgentResolver agentResolver) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.resourceAccessService = Objects.requireNonNull(resourceAccessService, "resourceAccessService");
        this.workingDirectoryRepository = workingDirectoryRepository;
        this.codingAgentRepository = codingAgentRepository;
        this.taskQueryProviderRegistry = Objects.requireNonNull(taskQueryProviderRegistry, "taskQueryProviderRegistry");
        this.llmModelManager = Objects.requireNonNull(llmModelManager, "llmModelManager");
        this.workerManagementFacade = workerManagementFacade;
        this.agentResolver = Objects.requireNonNull(agentResolver, "agentResolver");
    }

    CreateExecutionPlan resolveCreateExecutionPlan(TaskDispatchRequest request, AgentResolveContext context) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "resolve context must not be null");
        String ownerUserId = requireText(context.getUserId(), "authenticated userId is required");
        String tenantId = trimToNull(context.getTenantId());

        String sessionId = mergeExact("sessionId", request.getSessionId(), context.getSessionId());
        SessionEntity session = sessionId == null ? null
                : requireOwnedSession(sessionId, ownerUserId, tenantId);

        String requestedAgentId = trimToNull(request.getAgentId());
        String sessionAgentId = session == null ? null : trimToNull(session.getAgentId());
        String selectedAgentId = mergeExact("agentId", requestedAgentId, sessionAgentId);
        String syntheticDirectoryId = selectedAgentId != null && DirectoryAgentId.isDirectoryAgent(selectedAgentId)
                ? requireText(DirectoryAgentId.extractDirectoryId(selectedAgentId), "directory agent id is incomplete")
                : null;
        String logicalAgentLookupId = selectedAgentId != null && !DirectoryAgentId.isDirectoryAgent(selectedAgentId)
                ? selectedAgentId : null;

        CodingAgentEntity agent = logicalAgentLookupId == null ? null
                : requireOwnedAgent(logicalAgentLookupId, ownerUserId, tenantId, context);
        String logicalAgentId = agent == null ? null : requireText(agent.getAgentId(), "Agent id is missing");
        String bindingSource = requestedAgentId != null ? "EXPLICIT_AGENT" : "SESSION_AGENT";

        String sessionDirectoryId = session == null ? null : trimToNull(session.getCurrentDirectoryId());
        String selectedDirectoryId = mergeExact(
                "directoryId", request.getDirectoryId(), syntheticDirectoryId, sessionDirectoryId);
        String directoryId = firstText(
                selectedDirectoryId, agent == null ? null : agent.getDefaultDirectoryId());
        WorkingDirectoryEntity directory = directoryId == null ? null
                : requireOwnedDirectory(directoryId, ownerUserId, tenantId);

        String boundWorkerId = mergeExact("workerId",
                session == null ? null : session.getCurrentWorkerId(),
                directory == null ? null : directory.getWorkerId(),
                localPhysicalWorkerId(agent));
        String requestedWorkerId = trimToNull(request.getWorkerId());
        String physicalWorkerId;
        if (boundWorkerId != null) {
            requireCompatible("workerId", requestedWorkerId, boundWorkerId);
            physicalWorkerId = boundWorkerId;
        } else if (requestedWorkerId != null) {
            physicalWorkerId = requestedWorkerId;
        } else {
            physicalWorkerId = null;
        }
        if (physicalWorkerId != null) {
            requireWorkerAccess(ownerUserId, tenantId, physicalWorkerId);
        }

        String modelConfigId = resolveModelConfigId(request, context, session, directory, agent);
        LlmModelConfigDTO modelConfig = requireModelConfig(modelConfigId, tenantId);
        String modelProviderType = providerType(modelConfig);
        String model = firstText(
                request.getModel(),
                session == null ? null : session.getLatestModel(),
                agent == null ? null : agent.getDefaultModel(),
                modelConfig == null ? null : modelConfig.getModelName());

        String requestedProviderType = trimToNull(request.getProviderType());
        boolean explicitDirect = isKnownCommandProvider(requestedProviderType);
        ExecutionRoute executionRoute = explicitDirect || syntheticDirectoryId != null || logicalAgentId == null
                ? ExecutionRoute.DIRECT : ExecutionRoute.A2A;

        String sessionProviderType = session == null ? null : trimToNull(session.getProviderType());
        String agentProviderType = resolveAgentProviderType(agent, tenantId);
        String providerType = executionRoute == ExecutionRoute.DIRECT
                ? firstText(explicitDirect ? requestedProviderType : null, sessionProviderType,
                modelProviderType, logicalAgentId == null ? null : agentProviderType)
                : firstText(sessionProviderType, modelProviderType, agentProviderType, requestedProviderType);
        providerType = requireText(providerType, "unable to resolve exact execution provider");

        if (sessionProviderType != null && !sessionProviderType.equals(providerType)) {
            throw new SessionProviderBoundMismatchException(sessionId, sessionProviderType, providerType);
        }
        if (requestedProviderType != null && !requestedProviderType.equals(providerType)) {
            throw conflict("providerType", requestedProviderType, providerType);
        }
        if (modelProviderType != null && !isModelProviderCompatible(modelProviderType, providerType)) {
            throw new IllegalArgumentException("modelConfigId " + modelConfigId + " targets provider "
                    + modelProviderType + ", but resolved provider is " + providerType);
        }
        if (executionRoute == ExecutionRoute.DIRECT && !isKnownCommandProvider(providerType)) {
            throw new IllegalArgumentException("Provider not available: " + providerType);
        }
        if (executionRoute == ExecutionRoute.A2A) {
            if (logicalAgentId == null) {
                throw new IllegalArgumentException("A2A route requires an owned logical Agent");
            }
            if (agentProviderType != null && !agentProviderType.equals(providerType)) {
                if (modelProviderType != null && modelProviderType.equals(providerType)) {
                    throw new IllegalArgumentException("modelConfigId " + modelConfigId
                            + " targets provider " + modelProviderType
                            + ", but owned Agent targets provider " + agentProviderType);
                }
                throw conflict("providerType", providerType, agentProviderType);
            }
            if (EXTERNAL_A2A.equals(agent.getAgentType())) {
                requireExternalProviderProof(logicalAgentId, providerType, context);
                if (physicalWorkerId != null) {
                    throw new IllegalArgumentException("external A2A Agent cannot bind a local physical Worker");
                }
            } else if (physicalWorkerId == null) {
                throw new IllegalArgumentException("local A2A Agent requires an exact physical Worker");
            }
        }
        if (syntheticDirectoryId != null && modelConfigId == null) {
            throw new IllegalArgumentException("working directory requires an exact modelConfigId: " + directoryId);
        }
        if (executionRoute == ExecutionRoute.DIRECT
                && agent != null
                && !EXTERNAL_A2A.equals(agent.getAgentType())
                && physicalWorkerId == null) {
            throw new IllegalArgumentException("local Direct Agent requires an exact accessible physical Worker");
        }
        if (modelConfigId != null && physicalWorkerId != null) {
            if (model == null) {
                llmModelManager.validateModelAccessForWorker(modelConfigId, physicalWorkerId);
            } else {
                llmModelManager.validateModelAccessForWorker(modelConfigId, physicalWorkerId, model);
            }
        }

        AgentLookup agentLookup = executionRoute == ExecutionRoute.A2A
                ? new AgentLookup(logicalAgentId, bindingSource, context.getRequestSource()) : null;
        return new CreateExecutionPlan(
                tenantId,
                ownerUserId,
                logicalAgentId,
                providerType,
                physicalWorkerId,
                modelConfigId,
                model,
                sessionId,
                directoryId,
                executionRoute,
                agentLookup);
    }

    private SessionEntity requireOwnedSession(String sessionId, String ownerUserId, @Nullable String tenantId) {
        SessionEntity session = resourceAccessService.requireOwnedSession(sessionId, ownerUserId, tenantId);
        if (session == null) {
            throw new SecurityException("Resource access denied");
        }
        return session;
    }

    private WorkingDirectoryEntity requireOwnedDirectory(String directoryId,
                                                         String ownerUserId,
                                                         @Nullable String tenantId) {
        if (workingDirectoryRepository == null) {
            throw missingAuthority("WorkingDirectory repository");
        }
        WorkingDirectoryEntity directory = workingDirectoryRepository
                .findByDirectoryIdAndUserId(directoryId, ownerUserId)
                .orElseThrow(() -> new SecurityException("Resource access denied"));
        requireOwnerScope(directory.getUserId(), directory.getTenantId(), ownerUserId, tenantId);
        if (!Boolean.TRUE.equals(directory.getEnabled())) {
            throw new IllegalStateException("working directory is disabled: " + directoryId);
        }
        return directory;
    }

    private CodingAgentEntity requireOwnedAgent(String agentId,
                                                String ownerUserId,
                                                @Nullable String tenantId,
                                                AgentResolveContext context) {
        if (codingAgentRepository == null) {
            throw missingAuthority("CodingAgent repository");
        }
        CodingAgentEntity agent = "OPEN_API".equals(context.getRequestSource()) && tenantId != null
                ? codingAgentRepository.findByAgentIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> new SecurityException("Resource access denied"))
                : codingAgentRepository.findByAgentIdAndUserId(agentId, ownerUserId)
                .orElseThrow(() -> new SecurityException("Resource access denied"));
        if (!"OPEN_API".equals(context.getRequestSource())) {
            requireOwnerScope(agent.getUserId(), agent.getTenantId(), ownerUserId, tenantId);
        } else if (!Objects.equals(tenantId, trimToNull(agent.getTenantId()))) {
            throw new SecurityException("Resource access denied");
        }
        if (!Boolean.TRUE.equals(agent.getEnabled())) {
            throw new IllegalStateException("Agent is disabled: " + agentId);
        }
        return agent;
    }

    private void requireWorkerAccess(String ownerUserId,
                                     @Nullable String tenantId,
                                     String workerId) {
        if (workerManagementFacade == null) {
            throw missingAuthority("Worker access proof");
        }
        workerManagementFacade.validateWorkerAccess(ownerUserId, tenantId, workerId);
    }

    @Nullable
    private LlmModelConfigDTO requireModelConfig(@Nullable String modelConfigId,
                                                 @Nullable String tenantId) {
        if (modelConfigId == null) {
            return null;
        }
        LlmModelConfigDTO config = llmModelManager.getModelConfig(modelConfigId)
                .orElseThrow(() -> new IllegalArgumentException("modelConfigId not found: " + modelConfigId));
        if (!Boolean.TRUE.equals(config.getEnabled())
                || !Objects.equals(tenantId, trimToNull(config.getTenantId()))
                || config.getOwnerType() == null
                || trimToNull(config.getOwnerId()) == null) {
            throw new IllegalArgumentException("modelConfigId access denied: " + modelConfigId);
        }
        if (providerType(config) == null) {
            throw new IllegalArgumentException("modelConfigId " + modelConfigId
                    + " does not identify a known Worker backend");
        }
        return config;
    }

    @Nullable
    private String resolveModelConfigId(TaskDispatchRequest request,
                                        AgentResolveContext context,
                                        @Nullable SessionEntity session,
                                        @Nullable WorkingDirectoryEntity directory,
                                        @Nullable CodingAgentEntity agent) {
        String requested = mergeExact("modelConfigId", request.getModelConfigId(), context.getModelConfigId());
        String sessionBound = session == null ? null : trimToNull(session.getAuthModelConfigId());
        if (sessionBound != null) {
            requireCompatible("modelConfigId", requested, sessionBound);
            return sessionBound;
        }
        return firstText(
                requested,
                agent == null ? null : agent.getDefaultModelConfigId(),
                directory == null ? null : directory.getDefaultModelConfigId());
    }

    @Nullable
    private String resolveAgentProviderType(@Nullable CodingAgentEntity agent,
                                            @Nullable String tenantId) {
        if (agent == null) {
            return null;
        }
        String defaultModelConfigId = trimToNull(agent.getDefaultModelConfigId());
        if (defaultModelConfigId != null) {
            LlmModelConfigDTO config = requireModelConfig(defaultModelConfigId, tenantId);
            return providerType(config);
        }
        return switch (String.valueOf(agent.getAgentType())) {
            case LOCAL_CLAUDE_WORKER -> ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER;
            case LOCAL_CODEX_WORKER -> ProviderRouteRegistry.PROVIDER_CODEX_WORKER;
            case LOCAL_GEMINI_WORKER -> ProviderRouteRegistry.PROVIDER_GEMINI_WORKER;
            case LOCAL_LANGGRAPH_WORKER -> ProviderRouteRegistry.PROVIDER_LANGGRAPH_BIZ_WORKER;
            case EXTERNAL_A2A -> null;
            default -> null;
        };
    }

    @Nullable
    private String localPhysicalWorkerId(@Nullable CodingAgentEntity agent) {
        if (agent == null) {
            return null;
        }
        return switch (String.valueOf(agent.getAgentType())) {
            case LOCAL_CLAUDE_WORKER, LOCAL_CODEX_WORKER, LOCAL_GEMINI_WORKER, LOCAL_LANGGRAPH_WORKER ->
                    trimToNull(agent.getWorkerId());
            default -> null;
        };
    }

    private void requireExternalProviderProof(String logicalAgentId,
                                              String providerType,
                                              AgentResolveContext context) {
        boolean exact = agentResolver.listByProviderType(providerType, context).stream()
                .map(A2aAgentCard::getId)
                .map(TaskCreateTargetResolver::trimToNull)
                .anyMatch(logicalAgentId::equals);
        if (!exact) {
            throw new IllegalArgumentException("external A2A provider does not own Agent: " + logicalAgentId);
        }
    }

    private void requireOwnerScope(String actualUserId,
                                   String actualTenantId,
                                   String ownerUserId,
                                   @Nullable String tenantId) {
        if (!ownerUserId.equals(trimToNull(actualUserId))
                || !Objects.equals(tenantId, trimToNull(actualTenantId))) {
            throw new SecurityException("Resource access denied");
        }
    }

    /**
     * Legacy target classification retained for resume/router compatibility.
     * New create ingress must use {@link #resolveCreateExecutionPlan(TaskDispatchRequest, AgentResolveContext)}.
     */
    CreateExecutionTarget resolveCreateExecutionTarget(TaskDispatchRequest request) {
        String agentId = request.getAgentId();
        String requestedProviderType = request.getProviderType();

        if ((agentId == null || !DirectoryAgentId.isDirectoryAgent(agentId))
                && isKnownCommandProvider(requestedProviderType)) {
            return CreateExecutionTarget.direct(requestedProviderType);
        }
        if (agentId != null && DirectoryAgentId.isDirectoryAgent(agentId)) {
            return resolveDirectoryAgentTarget(request, agentId);
        }
        if (agentId != null && !agentId.isBlank()) {
            return CreateExecutionTarget.a2a(new AgentLookup(agentId, "EXPLICIT_AGENT"));
        }

        String sessionAgentId = resolveBoundOrExplicitAgentId(null, request.getSessionId());
        if (sessionAgentId != null && !sessionAgentId.isBlank()) {
            if (DirectoryAgentId.isDirectoryAgent(sessionAgentId)) {
                return resolveDirectoryAgentTarget(request, sessionAgentId);
            }
            return CreateExecutionTarget.a2a(new AgentLookup(sessionAgentId, "SESSION_AGENT"));
        }
        if (isKnownCommandProvider(requestedProviderType)) {
            return CreateExecutionTarget.direct(requestedProviderType);
        }

        String providerType = resolveProviderTypeFromModelConfig(request.getModelConfigId());
        if (providerType != null && isKnownCommandProvider(providerType)) {
            return CreateExecutionTarget.direct(providerType);
        }
        throw new IllegalArgumentException("无法确定执行后端：请指定 agentId 或 modelConfigId");
    }

    CreateExecutionTarget resolveDirectoryAgentTarget(TaskDispatchRequest request, String directoryAgentId) {
        String directoryId = DirectoryAgentId.extractDirectoryId(directoryAgentId);
        if (request.getDirectoryId() == null || request.getDirectoryId().isBlank()) {
            request.setDirectoryId(directoryId);
        }

        String modelConfigId = resolveModelConfigIdFromDirectory(request.getModelConfigId(), directoryId);
        if (modelConfigId == null) {
            throw new IllegalArgumentException("该工作目录需要配置 LLM 模型才能执行任务（directoryId=" + directoryId + "）");
        }
        request.setModelConfigId(modelConfigId);

        String providerType = resolveProviderTypeFromModelConfig(modelConfigId);
        if (providerType == null) {
            throw new IllegalArgumentException("modelConfigId " + modelConfigId + " 无法推导执行后端类型");
        }
        String requestedProviderType = request.getProviderType();
        if (isKnownCommandProvider(requestedProviderType)
                && isModelProviderCompatible(providerType, requestedProviderType)) {
            return CreateExecutionTarget.direct(requestedProviderType);
        }
        return CreateExecutionTarget.direct(providerType);
    }

    String resolveModelConfigIdFromDirectory(String explicitModelConfigId, String directoryId) {
        if (explicitModelConfigId != null && !explicitModelConfigId.isBlank()) {
            return explicitModelConfigId;
        }
        if (workingDirectoryRepository != null && directoryId != null && !directoryId.isBlank()) {
            return workingDirectoryRepository.findByDirectoryId(directoryId)
                    .map(WorkingDirectoryEntity::getDefaultModelConfigId)
                    .filter(id -> !id.isBlank())
                    .orElse(null);
        }
        return null;
    }

    String resolveBoundOrExplicitAgentId(@Nullable String requestedAgentId, @Nullable String sessionId) {
        if (requestedAgentId != null && !requestedAgentId.isBlank()) {
            return requestedAgentId;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionRepository.findById(sessionId)
                .map(SessionEntity::getAgentId)
                .filter(agentId -> !agentId.isBlank())
                .orElse(null);
    }

    private boolean isKnownCommandProvider(@Nullable String providerType) {
        return providerType != null
                && taskQueryProviderRegistry.findCommandProviderByType(providerType).isPresent();
    }

    @Nullable
    String resolveProviderTypeFromModelConfig(@Nullable String modelConfigId) {
        if (trimToNull(modelConfigId) == null) {
            return null;
        }
        return llmModelManager.getModelConfig(modelConfigId)
                .map(this::providerType)
                .orElse(null);
    }

    @Nullable
    private String providerType(@Nullable LlmModelConfigDTO config) {
        return config == null ? null
                : ProviderRouteRegistry.providerTypeForWorkerBackendOrNull(config.getWorkerBackend());
    }

    static boolean isModelProviderCompatible(String modelProviderType, String providerType) {
        return ProviderRouteRegistry.isModelProviderCompatible(modelProviderType, providerType);
    }

    private static String mergeExact(String field, String... values) {
        String resolved = null;
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized == null) {
                continue;
            }
            if (resolved != null && !resolved.equals(normalized)) {
                throw conflict(field, normalized, resolved);
            }
            resolved = normalized;
        }
        return resolved;
    }

    private static void requireCompatible(String field, @Nullable String requested, String resolved) {
        String normalized = trimToNull(requested);
        if (normalized != null && !normalized.equals(resolved)) {
            throw conflict(field, normalized, resolved);
        }
    }

    @Nullable
    private static String firstText(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static IllegalArgumentException conflict(String field, String requested, String resolved) {
        return new IllegalArgumentException(field + " " + requested + " conflicts with resolved " + resolved);
    }

    private static IllegalStateException missingAuthority(String authority) {
        return new IllegalStateException(authority + " is unavailable; target resolution is fail-closed");
    }

    enum ExecutionRoute {
        A2A,
        DIRECT
    }

    static final class AgentLookup {
        final String lookupId;
        final String bindingSource;
        @Nullable
        final String requestSource;

        AgentLookup(String lookupId, String bindingSource) {
            this(lookupId, bindingSource, null);
        }

        AgentLookup(String lookupId, String bindingSource, @Nullable String requestSource) {
            this.lookupId = requireText(lookupId, "Agent lookup id is required");
            this.bindingSource = requireText(bindingSource, "Agent binding source is required");
            this.requestSource = trimToNull(requestSource);
        }

        String lookupId() { return lookupId; }

        String bindingSource() { return bindingSource; }

        @Nullable
        String requestSource() { return requestSource; }
    }

    record CreateExecutionTarget(@Nullable String providerType,
                                 @Nullable AgentLookup agentLookup,
                                 boolean directProviderRoute) {
        static CreateExecutionTarget direct(String providerType) {
            return new CreateExecutionTarget(providerType, null, true);
        }

        static CreateExecutionTarget a2a(AgentLookup agentLookup) {
            return new CreateExecutionTarget(null, agentLookup, false);
        }
    }

    static final class CreateExecutionPlan {
        @Nullable
        private final String tenantId;
        private final String ownerUserId;
        @Nullable
        private final String logicalAgentId;
        private final String providerType;
        @Nullable
        private final String physicalWorkerId;
        @Nullable
        private final String modelConfigId;
        @Nullable
        private final String model;
        @Nullable
        private final String sessionId;
        @Nullable
        private final String directoryId;
        private final ExecutionRoute executionRoute;
        @Nullable
        private final AgentLookup agentLookup;

        private CreateExecutionPlan(@Nullable String tenantId,
                                    String ownerUserId,
                                    @Nullable String logicalAgentId,
                                    String providerType,
                                    @Nullable String physicalWorkerId,
                                    @Nullable String modelConfigId,
                                    @Nullable String model,
                                    @Nullable String sessionId,
                                    @Nullable String directoryId,
                                    ExecutionRoute executionRoute,
                                    @Nullable AgentLookup agentLookup) {
            this.tenantId = trimToNull(tenantId);
            this.ownerUserId = requireText(ownerUserId, "ownerUserId is required");
            this.logicalAgentId = trimToNull(logicalAgentId);
            this.providerType = requireText(providerType, "providerType is required");
            this.physicalWorkerId = trimToNull(physicalWorkerId);
            this.modelConfigId = trimToNull(modelConfigId);
            this.model = trimToNull(model);
            this.sessionId = trimToNull(sessionId);
            this.directoryId = trimToNull(directoryId);
            this.executionRoute = Objects.requireNonNull(executionRoute, "executionRoute is required");
            this.agentLookup = agentLookup;
            if (executionRoute == ExecutionRoute.A2A) {
                if (this.logicalAgentId == null || agentLookup == null
                        || !this.logicalAgentId.equals(agentLookup.lookupId())) {
                    throw new IllegalArgumentException("A2A plan requires one exact logical Agent lookup");
                }
            } else if (agentLookup != null) {
                throw new IllegalArgumentException("Direct plan cannot contain an A2A Agent lookup");
            }
        }

        @Nullable
        String tenantId() { return tenantId; }

        String ownerUserId() { return ownerUserId; }

        @Nullable
        String logicalAgentId() { return logicalAgentId; }

        String providerType() { return providerType; }

        @Nullable
        String physicalWorkerId() { return physicalWorkerId; }

        @Nullable
        String modelConfigId() { return modelConfigId; }

        @Nullable
        String model() { return model; }

        @Nullable
        String sessionId() { return sessionId; }

        @Nullable
        String directoryId() { return directoryId; }

        ExecutionRoute executionRoute() { return executionRoute; }

        @Nullable
        AgentLookup agentLookup() { return agentLookup; }

        boolean directProviderRoute() {
            return executionRoute == ExecutionRoute.DIRECT;
        }

        void requireMatches(TaskDispatchRequest request, AgentResolveContext context) {
            Objects.requireNonNull(request, "request must not be null");
            Objects.requireNonNull(context, "resolve context must not be null");
            if (request.isResume()) {
                throw new IllegalArgumentException(
                        "guarded CREATE cannot execute a resume continuation");
            }
            if (trimToNull(request.getContextAlias()) != null) {
                throw new IllegalArgumentException(
                        "guarded CREATE does not accept contextAlias");
            }
            if (!ownerUserId.equals(trimToNull(context.getUserId()))) {
                throw new SecurityException("Resource access denied");
            }
            if (!Objects.equals(tenantId, trimToNull(context.getTenantId()))) {
                throw new SecurityException("Resource access denied");
            }
            requireCompatible("sessionId", request.getSessionId(), sessionId);
            requireCompatible("sessionId", context.getSessionId(), sessionId);
            requireCompatible("directoryId", request.getDirectoryId(), directoryId);
            requireCompatible("providerType", request.getProviderType(), providerType);
            requireCompatible("workerId", request.getWorkerId(), physicalWorkerId);
            requireCompatible("modelConfigId", request.getModelConfigId(), modelConfigId);
            requireCompatible("modelConfigId", context.getModelConfigId(), modelConfigId);
            requireCompatible("model", request.getModel(), model);
            if (agentLookup != null
                    && !Objects.equals(agentLookup.requestSource(), trimToNull(context.getRequestSource()))) {
                throw new SecurityException("Resource access denied");
            }
            String requestedAgentId = trimToNull(request.getAgentId());
            if (requestedAgentId != null && DirectoryAgentId.isDirectoryAgent(requestedAgentId)) {
                String requestedDirectoryId = DirectoryAgentId.extractDirectoryId(requestedAgentId);
                requireCompatible("directoryId", requestedDirectoryId, directoryId);
            } else {
                requireCompatible("agentId", requestedAgentId, logicalAgentId);
            }
        }

        void applyCanonicalTarget(TaskDispatchRequest request) {
            Map<String, Object> sanitizedMetadata = request.getMetadata() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(request.getMetadata());
            RESERVED_TARGET_METADATA_KEYS.forEach(sanitizedMetadata::remove);
            request.setMetadata(sanitizedMetadata.isEmpty() ? null : sanitizedMetadata);
            request.setAgentId(logicalAgentId);
            request.setProviderType(providerType);
            request.setWorkerId(physicalWorkerId);
            request.setModelConfigId(modelConfigId);
            request.setModel(model);
            request.setSessionId(sessionId);
            request.setDirectoryId(directoryId);
        }
    }
}
