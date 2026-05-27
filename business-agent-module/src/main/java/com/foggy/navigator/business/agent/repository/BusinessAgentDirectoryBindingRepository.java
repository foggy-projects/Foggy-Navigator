package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessAgentDirectoryBindingRepository extends JpaRepository<AgentDirectoryBindingEntity, Long> {

    List<AgentDirectoryBindingEntity> findByTenantIdAndAgentIdOrderByCreatedAtDesc(String tenantId, String agentId);

    Optional<AgentDirectoryBindingEntity> findByTenantIdAndAgentIdAndDirectoryId(
            String tenantId,
            String agentId,
            String directoryId);

    void deleteByTenantIdAndAgentIdAndDirectoryId(String tenantId, String agentId, String directoryId);
}
