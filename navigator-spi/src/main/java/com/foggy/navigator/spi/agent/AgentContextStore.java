package com.foggy.navigator.spi.agent;

import java.util.Optional;

/**
 * A2A 多轮会话 contextId ↔ agentSessionRef 映射存储
 */
public interface AgentContextStore {

    /**
     * 查找会话引用（带 TTL 检查）
     *
     * @param contextId 上下文 ID
     * @param userId    用户 ID（安全隔离）
     * @param ttlHours  过期时间（小时），超过则视为无效
     * @return agentSessionRef（如 claudeSessionId），过期或不存在返回 empty
     */
    Optional<String> findSessionRef(String contextId, String userId, int ttlHours);

    /**
     * 保存/更新会话映射
     */
    void saveSessionRef(String contextId, String agentType,
                        String agentSessionRef, String userId, String targetAgentId);
}
