package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 以只插入语义声明 contextId 的初始 owner/agent 绑定。
 *
 * <p>不能使用 Spring Data {@code save}：该实体使用 assigned String 主键，save 可能走
 * {@code merge} 并覆盖并发胜出记录。独立事务中的 {@code persist + flush} 只会 INSERT，
 * 主键或 alias 冲突会在本方法返回前失败并完成回滚，调用方随后才能安全重读胜出记录。
 */
@Repository
@RequiredArgsConstructor
public class AgentContextOwnershipClaimWriter {

    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AgentConversationContextEntity candidate) {
        entityManager.persist(candidate);
        entityManager.flush();
    }
}
