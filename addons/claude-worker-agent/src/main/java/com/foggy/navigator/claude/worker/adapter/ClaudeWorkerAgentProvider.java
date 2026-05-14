package com.foggy.navigator.claude.worker.adapter;

import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
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
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * A2aAgentProvider 实现 — 将 CodingAgentEntity (LOCAL_CLAUDE_WORKER) 适配为统一 A2aAgent
 */
@Component
@RequiredArgsConstructor
public class ClaudeWorkerAgentProvider implements A2aAgentProvider {

    private final CodingAgentRepository agentRepository;
    private final ClaudeTaskService taskService;
    private final WorkingDirectoryRepository directoryRepository;
    private final LlmModelManager llmModelManager;
    @Nullable
    private final AgentContextStore contextStore;

    @Override
    public String getProviderType() {
        return "claude-worker";
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

    private String resolveDefaultCwd(CodingAgentEntity entity) {
        if (entity.getDefaultDirectoryId() == null) return null;
        return directoryRepository.findByDirectoryId(entity.getDefaultDirectoryId())
                .map(WorkingDirectoryEntity::getPath)
                .orElse(null);
    }

    // ── 上下文感知方法：自动路由 user / tenant 维度 ──

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

    /**
     * 租户级 Agent 列表（Open API 用，TENANT_ADMIN 可查看租户下所有 Agent）
     */
    public List<A2aAgentCard> listAgentCardsByTenant(String tenantId) {
        return agentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(this::isManagedAgent)
                .map(this::toAgentCard)
                .toList();
    }

    /**
     * 租户级 Agent 解析（Open API 用，TENANT_ADMIN 可访问租户下任意 Agent）
     */
    /**
     * 租户级 Agent 实体查询（Open API 用，获取 userId 等信息而无需构建完整 A2aAgent 对象）
     */
    public Optional<CodingAgentEntity> getAgentEntityByTenant(String agentId, String tenantId) {
        return agentRepository.findByAgentIdAndTenantId(agentId, tenantId)
                .filter(this::isManagedAgent);
    }

    public Optional<A2aAgent> resolveAgentByTenant(String agentId, String tenantId) {
        return agentRepository.findByAgentIdAndTenantId(agentId, tenantId)
                .filter(this::isManagedAgent)
                .map(this::toA2aAgent);
    }

    /**
     * Agent 解析：agentId 精确匹配 → name 匹配。
     * <p>
     * directory/binding/workerId 等间接查找已由 TaskDispatchFacade 的 directory# 机制处理，
     * Provider 只负责解析真实 Agent 实体。
     */
    private Optional<CodingAgentEntity> resolveManagedEntity(String lookupId, String userId) {
        return agentRepository.findByAgentIdAndUserId(lookupId, userId)
                .or(() -> agentRepository.findByNameAndUserId(lookupId, userId))
                .filter(this::isManagedAgent);
    }

    private boolean isManagedAgent(CodingAgentEntity entity) {
        String providerType = resolveProviderType(entity.getDefaultModelConfigId());
        if (providerType != null) {
            return "claude-worker".equals(providerType);
        }
        return "LOCAL_CLAUDE_WORKER".equals(entity.getAgentType());
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

    private A2aAgent toA2aAgent(CodingAgentEntity entity) {
        String cwd = resolveDefaultCwd(entity);
        InnerA2aAgent inner = new ClaudeWorkerInnerA2aAgent(entity, taskService, cwd);
        A2aAgent contextAgent = new ContextResolvingA2aAgent(inner, contextStore, entity);
        // 装饰链：AbortCoordinatingA2aAgent → ContextResolvingA2aAgent → InnerA2aAgent
        return new AbortCoordinatingA2aAgent(contextAgent, inner, taskService);
    }

    private A2aAgentCard toAgentCard(CodingAgentEntity entity) {
        return AgentCardBuilder.fromEntity(entity,
                "coding", "Execute coding tasks via Claude Code CLI",
                List.of("coding", "claude-worker"));
    }
}
