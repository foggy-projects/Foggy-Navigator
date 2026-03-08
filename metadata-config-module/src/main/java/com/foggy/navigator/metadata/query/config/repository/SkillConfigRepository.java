package com.foggy.navigator.metadata.query.config.repository;

import com.foggy.navigator.common.entity.SkillConfigEntity;
import com.foggy.navigator.common.enums.SkillScope;
import com.foggy.navigator.common.enums.SkillStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Skill 配置 Repository
 */
@Repository
public interface SkillConfigRepository extends JpaRepository<SkillConfigEntity, String> {

    /**
     * 获取 Agent 可用的所有 Skill（按优先级排序）
     * 包含：SYSTEM, GLOBAL, TENANT（匹配租户）, AGENT（匹配Agent）
     */
    @Query("SELECT s FROM SkillConfigEntity s WHERE s.status = :status " +
           "AND (s.scope = 'SYSTEM' " +
           "OR s.scope = 'GLOBAL' " +
           "OR (s.scope = 'TENANT' AND s.tenantId = :tenantId) " +
           "OR (s.scope = 'AGENT' AND s.agentId = :agentId)) " +
           "ORDER BY s.priority ASC, s.createdAt ASC")
    List<SkillConfigEntity> findAvailableSkillsForAgent(
        @Param("agentId") String agentId,
        @Param("tenantId") String tenantId,
        @Param("status") SkillStatus status
    );

    /**
     * 按作用域查找 Skill
     */
    List<SkillConfigEntity> findByScopeAndStatusOrderByPriorityAsc(SkillScope scope, SkillStatus status);

    /**
     * 按作用域和租户查找 Skill
     */
    List<SkillConfigEntity> findByScopeAndTenantIdAndStatusOrderByPriorityAsc(
        SkillScope scope, String tenantId, SkillStatus status);

    /**
     * 按 Agent ID 查找 Skill
     */
    List<SkillConfigEntity> findByAgentIdAndStatusOrderByPriorityAsc(String agentId, SkillStatus status);

    /**
     * 按名称和租户查找（用于去重）
     */
    Optional<SkillConfigEntity> findByNameAndTenantId(String name, String tenantId);

    /**
     * 按名称查找（全局范围，租户为空）
     */
    Optional<SkillConfigEntity> findByNameAndTenantIdIsNull(String name);

    /**
     * 更新 Skill 状态
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SkillConfigEntity s SET s.status = :status, s.updatedAt = CURRENT_TIMESTAMP WHERE s.id = :id")
    int updateStatus(@Param("id") String id, @Param("status") SkillStatus status);

    /**
     * 根据租户ID查找所有 Skill
     */
    List<SkillConfigEntity> findByTenantIdOrderByPriorityAsc(String tenantId);
}
