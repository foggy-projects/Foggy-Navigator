package com.foggy.navigator.session.registry;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 统一 Agent 注册中心 — 聚合所有 A2aAgentProvider 实例，提供统一发现和解析
 */
@Component
public class DefaultA2aAgentRegistry {

    private final List<A2aAgentProvider> providers;

    public DefaultA2aAgentRegistry(List<A2aAgentProvider> providers) {
        this.providers = providers != null ? providers : Collections.emptyList();
    }

    public List<A2aAgentCard> listAgents(String userId) {
        return providers.stream()
                .flatMap(p -> p.listAgentCards(userId).stream())
                .toList();
    }

    public Optional<A2aAgent> resolveAgent(String agentId, String userId) {
        return providers.stream()
                .map(p -> p.resolveAgent(agentId, userId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    public List<A2aAgentCard> listByProviderType(String type, String userId) {
        return providers.stream()
                .filter(p -> p.getProviderType().equals(type))
                .flatMap(p -> p.listAgentCards(userId).stream())
                .toList();
    }
}
