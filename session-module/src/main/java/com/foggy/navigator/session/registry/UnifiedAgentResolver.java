package com.foggy.navigator.session.registry;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
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
 * 按调用上下文解析 Provider 暴露的 A2A Agent。
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
        String modelConfigId = context.getModelConfigId();
        if (modelConfigId != null && !modelConfigId.isBlank()) {
            String mappedProviderType = resolveProviderTypeFromModelConfig(modelConfigId);
            if (mappedProviderType == null) {
                return Optional.empty();
            }
            return findProviderByType(mappedProviderType)
                    .flatMap(provider -> provider.resolveAgent(agentId, context));
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
        String modelConfigId = context.getModelConfigId();
        if (modelConfigId != null && !modelConfigId.isBlank()) {
            String mappedProviderType = resolveProviderTypeFromModelConfig(modelConfigId);
            if (mappedProviderType == null) {
                return Optional.empty();
            }
            return findProviderByType(mappedProviderType)
                    .filter(provider -> provider.resolveAgent(agentId, context).isPresent())
                    .map(A2aAgentProvider::getProviderType);
        }
        return providers.stream()
                .filter(p -> p.resolveAgent(agentId, context).isPresent())
                .map(A2aAgentProvider::getProviderType)
                .findFirst();
    }

    private Optional<A2aAgentProvider> findProviderByType(String providerType) {
        return providers.stream()
                .filter(p -> providerType.equals(p.getProviderType()))
                .findFirst();
    }

    private String resolveProviderTypeFromModelConfig(String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return null;
        }
        return llmModelManager.getModelConfig(modelConfigId)
                .flatMap(config -> ProviderRouteRegistry.providerTypeForWorkerBackend(config.getWorkerBackend()))
                .orElse(null);
    }
}
