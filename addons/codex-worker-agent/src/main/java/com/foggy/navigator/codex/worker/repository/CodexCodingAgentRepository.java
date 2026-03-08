package com.foggy.navigator.codex.worker.repository;

import com.foggy.navigator.common.entity.CodingAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * CodingAgentEntity Repository（Codex 模块本地副本）
 * 用于查询 LOCAL_CODEX_WORKER 类型的 Agent 实体
 */
public interface CodexCodingAgentRepository extends JpaRepository<CodingAgentEntity, Long> {

    Optional<CodingAgentEntity> findByAgentId(String agentId);

    Optional<CodingAgentEntity> findByAgentIdAndUserId(String agentId, String userId);

    List<CodingAgentEntity> findByUserIdOrderByCreatedAtDesc(String userId);
}
