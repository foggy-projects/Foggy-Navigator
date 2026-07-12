package com.foggy.navigator.codex.worker.adapter;

import com.foggy.navigator.codex.worker.repository.CodexCodingAgentRepository;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.util.AgentCardBuilder;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.session.agent.AbortCoordinatingA2aAgent;
import com.foggy.navigator.session.agent.ContextResolvingA2aAgent;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Optional;

/** Shared Agent projection for the two externally distinct Codex providers. */
abstract class AbstractCodexWorkerAgentProvider implements A2aAgentProvider {

    private final CodexCodingAgentRepository agentRepository;
    private final CodexTaskService taskService;
    private final LlmModelManager llmModelManager;
    @Nullable
    private final AgentContextStore contextStore;
    @Nullable
    private final WorkerManagementFacade workerManagementFacade;

    AbstractCodexWorkerAgentProvider(CodexCodingAgentRepository agentRepository,
                                     CodexTaskService taskService,
                                     LlmModelManager llmModelManager,
                                     @Nullable AgentContextStore contextStore,
                                     @Nullable WorkerManagementFacade workerManagementFacade) {
        this.agentRepository = agentRepository;
        this.taskService = taskService;
        this.llmModelManager = llmModelManager;
        this.contextStore = contextStore;
        this.workerManagementFacade = workerManagementFacade;
    }

    @Override
    public List<A2aAgentCard> listAgentCards(String userId) {
        return agentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(this::isManagedAgent)
                .map(this::toAgentCard)
                .toList();
    }

    @Override
    public Optional<A2aAgent> resolveAgent(String agentId, String userId) {
        return resolveManagedEntity(agentId, userId)
                .map(entity -> toA2aAgent(entity, userId));
    }

    @Override
    public List<A2aAgentCard> listAgentCards(AgentResolveContext context) {
        if (context.getTenantId() != null && "OPEN_API".equals(context.getRequestSource())) {
            return listAgentCardsByTenant(context.getTenantId());
        }
        return listAgentCards(context.getUserId());
    }

    @Override
    public Optional<A2aAgent> resolveAgent(String agentId, AgentResolveContext context) {
        if (context.getTenantId() != null && "OPEN_API".equals(context.getRequestSource())) {
            return resolveAgentByTenant(agentId, context.getTenantId());
        }
        return resolveAgent(agentId, context.getUserId());
    }

    public List<A2aAgentCard> listAgentCardsByTenant(String tenantId) {
        return agentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(this::isManagedAgent)
                .map(this::toAgentCard)
                .toList();
    }

    public Optional<A2aAgent> resolveAgentByTenant(String agentId, String tenantId) {
        return agentRepository.findByAgentIdAndTenantId(agentId, tenantId)
                .filter(this::isManagedAgent)
                .map(entity -> toA2aAgent(entity, entity.getUserId()));
    }

    protected boolean acceptsLegacyAgent(CodingAgentEntity entity) {
        return false;
    }

    protected abstract String description();

    private A2aAgent toA2aAgent(CodingAgentEntity entity, String userId) {
        String cwd = resolveDefaultCwd(entity, userId);
        TaskLookupProvider lookupProvider = scopedLookupProvider();
        InnerA2aAgent inner = new CodexWorkerInnerA2aAgent(
                entity, taskService, cwd, getProviderType(), lookupProvider);
        A2aAgent contextAgent = new ContextResolvingA2aAgent(
                inner, contextStore, entity, getProviderType());
        return new AbortCoordinatingA2aAgent(contextAgent, inner, lookupProvider);
    }

    private TaskLookupProvider scopedLookupProvider() {
        String providerType = getProviderType();
        return new TaskLookupProvider() {
            @Override
            public String getProviderType() {
                return providerType;
            }

            @Override
            public Optional<DispatchTaskDTO> getTaskById(String taskId) {
                return taskService.getTaskByIdForProvider(taskId, providerType);
            }

            @Override
            public Optional<DispatchTaskDTO> getTaskByIdAndUser(String taskId, String userId) {
                return taskService.getTaskByIdAndUserForProvider(taskId, userId, providerType);
            }

            @Override
            public List<DispatchTaskDTO> listTasksBySession(String sessionId) {
                return taskService.listTasksBySessionForProvider(sessionId, providerType);
            }

            @Override
            public List<DispatchTaskDTO> listActiveDispatchTasks(String userId) {
                return taskService.listActiveDispatchTasksForProvider(userId, providerType);
            }
        };
    }

    private String resolveDefaultCwd(CodingAgentEntity entity, String userId) {
        if (entity.getDefaultDirectoryId() == null || workerManagementFacade == null) return null;
        return workerManagementFacade.getDirectoryPath(userId, entity.getDefaultDirectoryId());
    }

    private Optional<CodingAgentEntity> resolveManagedEntity(String lookupId, String userId) {
        return agentRepository.findByAgentIdAndUserId(lookupId, userId)
                .or(() -> agentRepository.findByNameAndUserId(lookupId, userId))
                .filter(this::isManagedAgent);
    }

    private boolean isManagedAgent(CodingAgentEntity entity) {
        String modelConfigId = entity.getDefaultModelConfigId();
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return acceptsLegacyAgent(entity);
        }
        String providerType = resolveProviderType(modelConfigId);
        return providerType != null && getProviderType().equals(providerType);
    }

    private String resolveProviderType(String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank()) return null;
        return llmModelManager.getModelConfig(modelConfigId)
                .flatMap(config -> ProviderRouteRegistry.providerTypeForWorkerBackend(config.getWorkerBackend()))
                .orElse(null);
    }

    private A2aAgentCard toAgentCard(CodingAgentEntity entity) {
        return AgentCardBuilder.fromEntity(entity,
                "coding", description(), List.of("coding", getProviderType()));
    }
}
