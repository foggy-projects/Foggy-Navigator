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

    // ── 上下文感知方法（default 实现回退到 userId 维度，Provider 可 override 支持 tenant 等） ──

    /** 按上下文列出 Agent 卡片 */
    default List<A2aAgentCard> listAgentCards(AgentResolveContext context) {
        return listAgentCards(context.getUserId());
    }

    /** 按上下文解析 Agent 实例 */
    default Optional<A2aAgent> resolveAgent(String agentId, AgentResolveContext context) {
        return resolveAgent(agentId, context.getUserId());
    }
}
