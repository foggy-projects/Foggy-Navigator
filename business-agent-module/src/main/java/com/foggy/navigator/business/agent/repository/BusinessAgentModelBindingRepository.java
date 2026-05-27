package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.common.entity.AgentModelBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessAgentModelBindingRepository extends JpaRepository<AgentModelBindingEntity, Long> {

    List<AgentModelBindingEntity> findByTenantIdAndAgentIdOrderByCreatedAtDesc(String tenantId, String agentId);

    Optional<AgentModelBindingEntity> findByTenantIdAndAgentIdAndModelConfigId(
            String tenantId,
            String agentId,
            String modelConfigId);

    void deleteByTenantIdAndAgentIdAndModelConfigId(String tenantId, String agentId, String modelConfigId);
}
