package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.util.DirectoryAgentId;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.springframework.lang.Nullable;

final class TaskCreateTargetResolver {

    private final SessionRepository sessionRepository;
    @Nullable
    private final WorkingDirectoryRepository workingDirectoryRepository;
    private final TaskQueryProviderRegistry taskQueryProviderRegistry;
    private final LlmModelManager llmModelManager;

    TaskCreateTargetResolver(SessionRepository sessionRepository,
                             @Nullable WorkingDirectoryRepository workingDirectoryRepository,
                             TaskQueryProviderRegistry taskQueryProviderRegistry,
                             LlmModelManager llmModelManager) {
        this.sessionRepository = sessionRepository;
        this.workingDirectoryRepository = workingDirectoryRepository;
        this.taskQueryProviderRegistry = taskQueryProviderRegistry;
        this.llmModelManager = llmModelManager;
    }

    CreateExecutionTarget resolveCreateExecutionTarget(TaskDispatchRequest request) {
        String agentId = request.getAgentId();

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

        String requestedProviderType = request.getProviderType();
        if (requestedProviderType != null && !requestedProviderType.isBlank()
                && taskQueryProviderRegistry.findCommandProviderByType(requestedProviderType).isPresent()) {
            return CreateExecutionTarget.direct(requestedProviderType);
        }

        String providerType = resolveProviderTypeFromModelConfig(request.getModelConfigId());
        if (providerType != null && taskQueryProviderRegistry.findCommandProviderByType(providerType).isPresent()) {
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
        if (requestedProviderType != null && !requestedProviderType.isBlank()
                && taskQueryProviderRegistry.findCommandProviderByType(requestedProviderType).isPresent()
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

    String resolveProviderTypeFromModelConfig(String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return null;
        }
        return llmModelManager.getModelConfig(modelConfigId)
                .map(cfg -> ProviderRouteRegistry.providerTypeForWorkerBackendOrNull(cfg.getWorkerBackend()))
                .orElse(null);
    }

    static boolean isModelProviderCompatible(String modelProviderType, String providerType) {
        return ProviderRouteRegistry.isModelProviderCompatible(modelProviderType, providerType);
    }

    static final class AgentLookup {
        final String lookupId;
        final String bindingSource;

        AgentLookup(String lookupId, String bindingSource) {
            this.lookupId = lookupId;
            this.bindingSource = bindingSource;
        }
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
}
