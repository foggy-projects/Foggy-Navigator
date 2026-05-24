package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.common.entity.AgentWorkerBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BusinessAgentWorkerBindingRepository extends JpaRepository<AgentWorkerBindingEntity, Long> {

    Optional<AgentWorkerBindingEntity> findByTenantIdAndAgentIdAndWorkerPoolId(
            String tenantId,
            String agentId,
            String workerPoolId);

    List<AgentWorkerBindingEntity> findByTenantIdAndAgentIdOrderByCreatedAtDesc(String tenantId, String agentId);

    void deleteByTenantIdAndAgentIdAndWorkerPoolId(String tenantId, String agentId, String workerPoolId);
}
