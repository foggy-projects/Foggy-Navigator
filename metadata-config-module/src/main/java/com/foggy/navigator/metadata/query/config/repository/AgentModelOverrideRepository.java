package com.foggy.navigator.metadata.query.config.repository;

import com.foggy.navigator.common.entity.AgentModelOverrideEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent 模型覆盖 Repository
 */
@Repository
public interface AgentModelOverrideRepository extends JpaRepository<AgentModelOverrideEntity, String> {

    Optional<AgentModelOverrideEntity> findByTenantIdAndAgentId(String tenantId, String agentId);

    List<AgentModelOverrideEntity> findByTenantIdOrderByCreatedAtAsc(String tenantId);

    void deleteByTenantIdAndAgentId(String tenantId, String agentId);
}
