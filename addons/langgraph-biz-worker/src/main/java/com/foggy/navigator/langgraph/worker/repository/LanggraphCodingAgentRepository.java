package com.foggy.navigator.langgraph.worker.repository;

import com.foggy.navigator.common.entity.CodingAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for CodingAgentEntity filtered by LOCAL_LANGGRAPH_WORKER type.
 * Each addon maintains its own repository for CodingAgentEntity.
 */
@Repository
public interface LanggraphCodingAgentRepository extends JpaRepository<CodingAgentEntity, Long> {

    Optional<CodingAgentEntity> findByAgentIdAndUserId(String agentId, String userId);

    Optional<CodingAgentEntity> findByNameAndUserId(String name, String userId);

    Optional<CodingAgentEntity> findByAgentId(String agentId);

    List<CodingAgentEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<CodingAgentEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
