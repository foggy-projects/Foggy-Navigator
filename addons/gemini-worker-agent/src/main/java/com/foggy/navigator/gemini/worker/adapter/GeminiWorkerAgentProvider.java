package com.foggy.navigator.gemini.worker.adapter;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.util.AgentCardBuilder;
import com.foggy.navigator.gemini.worker.repository.GeminiCodingAgentRepository;
import com.foggy.navigator.gemini.worker.service.GeminiTaskService;
import com.foggy.navigator.session.agent.AbortCoordinatingA2aAgent;
import com.foggy.navigator.session.agent.ContextResolvingA2aAgent;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class GeminiWorkerAgentProvider implements A2aAgentProvider {

    private final GeminiCodingAgentRepository agentRepository;
    private final GeminiTaskService taskService;
    private final LlmModelManager llmModelManager;
    @Nullable
    private final AgentContextStore contextStore;
    @Nullable
    private final WorkerManagementFacade workerManagementFacade;

    @Override
    public String getProviderType() {
        return GeminiTaskService.AGENT_ID;
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
        return resolveManagedEntity(agentId, userId).map(entity -> toA2aAgent(entity, userId));
    }

    @Override
    public List<A2aAgentCard> listAgentCards(AgentResolveContext context) {
        if (context.getTenantId() != null && "OPEN_API".equals(context.getRequestSource())) {
            return agentRepository.findByTenantIdOrderByCreatedAtDesc(context.getTenantId()).stream()
                    .filter(this::isManagedAgent)
                    .map(this::toAgentCard)
                    .toList();
        }
        return listAgentCards(context.getUserId());
    }

    @Override
    public Optional<A2aAgent> resolveAgent(String agentId, AgentResolveContext context) {
        if (context.getTenantId() != null && "OPEN_API".equals(context.getRequestSource())) {
            return agentRepository.findByAgentIdAndTenantId(agentId, context.getTenantId())
                    .filter(this::isManagedAgent)
                    .map(entity -> toA2aAgent(entity, entity.getUserId()));
        }
        return resolveAgent(agentId, context.getUserId());
    }

    private Optional<CodingAgentEntity> resolveManagedEntity(String lookupId, String userId) {
        return agentRepository.findByAgentIdAndUserId(lookupId, userId)
                .or(() -> agentRepository.findByNameAndUserId(lookupId, userId))
                .filter(this::isManagedAgent);
    }

    private boolean isManagedAgent(CodingAgentEntity entity) {
        String providerType = resolveProviderType(entity.getDefaultModelConfigId());
        if (providerType != null) {
            return GeminiTaskService.AGENT_ID.equals(providerType);
        }
        return "LOCAL_GEMINI_WORKER".equals(entity.getAgentType());
    }

    private String resolveProviderType(String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return null;
        }
        return llmModelManager.getModelConfig(modelConfigId)
                .map(LlmModelConfigDTO::getWorkerBackend)
                .map(this::mapWorkerBackendToProviderType)
                .orElse(null);
    }

    private String mapWorkerBackendToProviderType(String workerBackend) {
        if (workerBackend == null || workerBackend.isBlank()) {
            return null;
        }
        return switch (workerBackend) {
            case "OPENAI_CODEX" -> "codex-worker";
            case "CLAUDE_CODE" -> "claude-worker";
            case "GEMINI_CLI" -> GeminiTaskService.AGENT_ID;
            case "LANGGRAPH_BIZ" -> "langgraph-biz-worker";
            default -> null;
        };
    }

    private A2aAgent toA2aAgent(CodingAgentEntity entity, String userId) {
        String cwd = entity.getDefaultDirectoryId() != null && workerManagementFacade != null
                ? workerManagementFacade.getDirectoryPath(userId, entity.getDefaultDirectoryId())
                : null;
        InnerA2aAgent inner = new GeminiWorkerInnerA2aAgent(entity, taskService, cwd);
        A2aAgent contextAgent = new ContextResolvingA2aAgent(inner, contextStore, entity);
        return new AbortCoordinatingA2aAgent(contextAgent, inner, taskService);
    }

    private A2aAgentCard toAgentCard(CodingAgentEntity entity) {
        return AgentCardBuilder.fromEntity(entity,
                GeminiWorkerInnerA2aAgent.CARD_CATEGORY,
                GeminiWorkerInnerA2aAgent.CARD_DESCRIPTION,
                GeminiWorkerInnerA2aAgent.CARD_TAGS);
    }
}
