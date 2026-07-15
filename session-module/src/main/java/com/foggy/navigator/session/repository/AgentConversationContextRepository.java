package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentConversationContextRepository
        extends JpaRepository<AgentConversationContextEntity, String> {

    Optional<AgentConversationContextEntity> findByContextIdAndUserId(String contextId, String userId);

    Optional<AgentConversationContextEntity> findByContextAliasAndUserIdAndTargetAgentId(
            String contextAlias, String userId, String targetAgentId);

    /**
     * 仅在 contextId 的用户和 Agent 归属同时匹配时更新会话引用。
     * 条件更新用于防止“先读后写”窗口覆盖其他主体已绑定的 context。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update AgentConversationContextEntity c
               set c.agentType = :agentType,
                   c.agentSessionRef = COALESCE(:agentSessionRef, c.agentSessionRef),
                   c.lastAccessedAt = :lastAccessedAt
             where c.contextId = :contextId
               and c.userId = :userId
               and c.targetAgentId = :targetAgentId
            """)
    int updateSessionRefIfOwned(
            @Param("contextId") String contextId,
            @Param("agentType") String agentType,
            @Param("agentSessionRef") String agentSessionRef,
            @Param("userId") String userId,
            @Param("targetAgentId") String targetAgentId,
            @Param("lastAccessedAt") LocalDateTime lastAccessedAt);

    /**
     * {@link #updateSessionRefIfOwned} 的完整字段版本。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update AgentConversationContextEntity c
               set c.agentType = :agentType,
                   c.agentSessionRef = COALESCE(:agentSessionRef, c.agentSessionRef),
                   c.navigatorSessionId = COALESCE(:navigatorSessionId, c.navigatorSessionId),
                   c.contextAlias = COALESCE(:contextAlias, c.contextAlias),
                   c.lastAccessedAt = :lastAccessedAt
             where c.contextId = :contextId
               and c.userId = :userId
               and c.targetAgentId = :targetAgentId
            """)
    int updateSessionRefFullIfOwned(
            @Param("contextId") String contextId,
            @Param("agentType") String agentType,
            @Param("agentSessionRef") String agentSessionRef,
            @Param("navigatorSessionId") String navigatorSessionId,
            @Param("userId") String userId,
            @Param("targetAgentId") String targetAgentId,
            @Param("contextAlias") String contextAlias,
            @Param("lastAccessedAt") LocalDateTime lastAccessedAt);

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
