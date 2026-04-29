package com.foggy.navigator.session.registry;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 统一 Agent 解析器 —— 聚合所有 A2aAgentProvider，
 * 通过 AgentResolveContext 自动路由 user / tenant / A2A 等维度。
 * <p>
 * 本质是 DefaultA2aAgentRegistry 的上下文感知版本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnifiedAgentResolver {

    private final List<A2aAgentProvider> providers;
    private final LlmModelManager llmModelManager;

    /**
     * 按上下文列出所有 Agent 卡片
     */
    public List<A2aAgentCard> listAgents(AgentResolveContext context) {
        return providers.stream()
                .flatMap(p -> p.listAgentCards(context).stream())
                .toList();
    }

    /**
     * 按 providerType + 上下文列出 Agent 卡片。
     */
    public List<A2aAgentCard> listByProviderType(String providerType, AgentResolveContext context) {
        if (providerType == null || providerType.isBlank()) {
            return listAgents(context);
        }
        return providers.stream()
                .filter(p -> providerType.equals(p.getProviderType()))
                .flatMap(p -> p.listAgentCards(context).stream())
                .toList();
    }

    /**
     * 按上下文解析 Agent 实例
     */
    public Optional<A2aAgent> resolveAgent(String agentId, AgentResolveContext context) {
        Optional<A2aAgentProvider> preferredProvider = resolvePreferredProvider(context);
        if (preferredProvider.isPresent()) {
            return preferredProvider.get().resolveAgent(agentId, context);
        }
        return providers.stream()
                .map(p -> p.resolveAgent(agentId, context))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    /**
     * 获取能解析指定 agentId 的 Provider 类型
     */
    public Optional<String> getProviderType(String agentId, AgentResolveContext context) {
        Optional<A2aAgentProvider> preferredProvider = resolvePreferredProvider(context);
        if (preferredProvider.isPresent()) {
            return preferredProvider.get().resolveAgent(agentId, context).isPresent()
                    ? Optional.of(preferredProvider.get().getProviderType())
                    : Optional.empty();
        }
        return providers.stream()
                .filter(p -> p.resolveAgent(agentId, context).isPresent())
                .map(A2aAgentProvider::getProviderType)
                .findFirst();
    }

    private Optional<A2aAgentProvider> resolvePreferredProvider(AgentResolveContext context) {
        String providerType = resolveProviderTypeFromModelConfig(context.getModelConfigId());
        if (providerType == null) {
            return Optional.empty();
        }
        return providers.stream()
                .filter(p -> providerType.equals(p.getProviderType()))
                .findFirst();
    }

    private String resolveProviderTypeFromModelConfig(String modelConfigId) {
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
}
