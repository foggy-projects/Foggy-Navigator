package com.foggy.navigator.codex.worker.adapter;

import com.foggy.navigator.codex.worker.repository.CodexCodingAgentRepository;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.util.AgentCardBuilder;
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

/**
 * A2aAgentProvider 实现 — 将 CodingAgentEntity (LOCAL_CODEX_WORKER) 适配为统一 A2aAgent
 */
@Component
@RequiredArgsConstructor
public class CodexWorkerAgentProvider implements A2aAgentProvider {

    private final CodexCodingAgentRepository agentRepository;
    private final CodexTaskService taskService;
    private final LlmModelManager llmModelManager;
    @Nullable
    private final AgentContextStore contextStore;
    /** 用于获取目录路径（目录由 Claude Worker 管理） */
    @Nullable
    private final WorkerManagementFacade workerManagementFacade;

    @Override
    public String getProviderType() {
        return "codex-worker";
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

    private A2aAgent toA2aAgent(CodingAgentEntity entity, String userId) {
        String cwd = resolveDefaultCwd(entity, userId);
        InnerA2aAgent inner = new CodexWorkerInnerA2aAgent(entity, taskService, cwd);
        A2aAgent contextAgent = new ContextResolvingA2aAgent(inner, contextStore, entity);
        // 装饰链：AbortCoordinatingA2aAgent → ContextResolvingA2aAgent → InnerA2aAgent
        return new AbortCoordinatingA2aAgent(contextAgent, inner, taskService);
    }

    /**
     * 通过 WorkerManagementFacade 获取目录路径（Codex 复用 Claude 管理的目录）
     */
    private String resolveDefaultCwd(CodingAgentEntity entity, String userId) {
        if (entity.getDefaultDirectoryId() == null) return null;
        if (workerManagementFacade == null) return null;
        return workerManagementFacade.getDirectoryPath(userId, entity.getDefaultDirectoryId());
    }

    /**
     * Agent 解析：agentId 精确匹配 → name 匹配。
     * directory/binding/workerId 等间接查找已由 TaskDispatchFacade 的 directory# 机制处理。
     */
    private Optional<CodingAgentEntity> resolveManagedEntity(String lookupId, String userId) {
        return agentRepository.findByAgentIdAndUserId(lookupId, userId)
                .or(() -> agentRepository.findByNameAndUserId(lookupId, userId))
                .filter(this::isManagedAgent);
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

    private boolean isManagedAgent(CodingAgentEntity entity) {
        String providerType = resolveProviderType(entity.getDefaultModelConfigId());
        if (providerType != null) {
            return "codex-worker".equals(providerType);
        }
        return "LOCAL_CODEX_WORKER".equals(entity.getAgentType());
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
            case "LANGGRAPH_BIZ" -> "langgraph-biz-worker";
            default -> null;
        };
    }

    private A2aAgentCard toAgentCard(CodingAgentEntity entity) {
        return AgentCardBuilder.fromEntity(entity,
                "coding", "Execute coding tasks via OpenAI Codex",
                List.of("coding", "codex-worker"));
    }
}
