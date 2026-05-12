package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentConversationContextRepository
        extends JpaRepository<AgentConversationContextEntity, String> {

    Optional<AgentConversationContextEntity> findByContextIdAndUserId(String contextId, String userId);

    Optional<AgentConversationContextEntity> findByContextAliasAndUserIdAndTargetAgentId(
            String contextAlias, String userId, String targetAgentId);

    long deleteByNavigatorSessionId(String navigatorSessionId);

    // ── Open API: 会话列表查询 ──

    /** 按 userId + agentId 查询会话列表（降序，最近访问优先） */
    List<AgentConversationContextEntity> findByUserIdAndTargetAgentIdOrderByLastAccessedAtDesc(
            String userId, String targetAgentId, Pageable pageable);

    /** 按上一页最后访问时间继续查询会话列表（降序，最近访问优先） */
    List<AgentConversationContextEntity> findByUserIdAndTargetAgentIdAndLastAccessedAtBeforeOrderByLastAccessedAtDesc(
            String userId, String targetAgentId, LocalDateTime lastAccessedAt, Pageable pageable);

    /** 按 contextId 直接查找（不限 userId，Open API 已通过 tenantId 鉴权） */
    Optional<AgentConversationContextEntity> findByContextIdAndTargetAgentId(
            String contextId, String targetAgentId);

    /** 按 navigatorSessionId 反查 contextId（sessionId → contextId 映射） */
    Optional<AgentConversationContextEntity> findByNavigatorSessionId(String navigatorSessionId);
}
