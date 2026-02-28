package com.foggy.navigator.spi.agent;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;

import java.util.List;
import java.util.Optional;

/**
 * Provider 模式，各 addon 模块贡献自己管理的 Agent 实例
 */
public interface A2aAgentProvider {

    /** List all agent cards this provider manages for a user */
    List<A2aAgentCard> listAgentCards(String userId);

    /** Resolve an agent instance by ID (returns empty if not managed by this provider) */
    Optional<A2aAgent> resolveAgent(String agentId, String userId);

    /** Provider type identifier (e.g. "claude-worker", "langchain4j", "openhands") */
    String getProviderType();
}
