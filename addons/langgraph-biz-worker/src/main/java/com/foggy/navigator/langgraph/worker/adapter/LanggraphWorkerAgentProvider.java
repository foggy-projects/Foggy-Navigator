package com.foggy.navigator.langgraph.worker.adapter;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.util.AgentCardBuilder;
import com.foggy.navigator.langgraph.worker.repository.LanggraphCodingAgentRepository;
import com.foggy.navigator.langgraph.worker.service.LanggraphTaskService;
import com.foggy.navigator.session.agent.ContextResolvingA2aAgent;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Registers LangGraph Biz Worker agents into the A2A unified discovery system.
 */
@Component
@RequiredArgsConstructor
public class LanggraphWorkerAgentProvider implements A2aAgentProvider {

    private static final String AGENT_TYPE = "LOCAL_LANGGRAPH_WORKER";

    private final LanggraphCodingAgentRepository agentRepository;
    private final LanggraphTaskService taskService;
    private final LlmModelManager llmModelManager;
    @Nullable
    private final AgentContextStore contextStore;

    @Override
    public String getProviderType() {
        return LanggraphTaskService.PROVIDER_TYPE;
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
                .map(this::toA2aAgent);
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
            return agentRepository.findByAgentId(agentId)
                    .filter(entity -> context.getTenantId().equals(entity.getTenantId()))
                    .filter(this::isManagedAgent)
                    .map(this::toA2aAgent);
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
            return LanggraphTaskService.PROVIDER_TYPE.equals(providerType);
        }
        return AGENT_TYPE.equals(entity.getAgentType());
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
            case "GEMINI_CLI" -> "gemini-worker";
            case "LANGGRAPH_BIZ" -> LanggraphTaskService.PROVIDER_TYPE;
            default -> null;
        };
    }

    private A2aAgent toA2aAgent(CodingAgentEntity entity) {
        InnerA2aAgent inner = new LanggraphWorkerInnerA2aAgent(entity, taskService);
        // Use explicit providerType since ContextResolvingA2aAgent's auto-derive
        // doesn't know about LOCAL_LANGGRAPH_WORKER yet
        return new ContextResolvingA2aAgent(inner, contextStore, entity, LanggraphTaskService.PROVIDER_TYPE);
    }

    private A2aAgentCard toAgentCard(CodingAgentEntity entity) {
        return AgentCardBuilder.fromEntity(entity,
                "business",
                "Execute controlled business queries via LangGraph Skill Runtime",
                List.of("business", "skill-runtime", LanggraphTaskService.PROVIDER_TYPE));
    }
}
