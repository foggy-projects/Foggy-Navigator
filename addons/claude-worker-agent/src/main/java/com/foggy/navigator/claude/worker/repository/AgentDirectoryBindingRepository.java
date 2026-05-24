package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentDirectoryBindingRepository extends JpaRepository<AgentDirectoryBindingEntity, Long> {

    List<AgentDirectoryBindingEntity> findByAgentId(String agentId);

    List<AgentDirectoryBindingEntity> findByTenantIdAndAgentId(String tenantId, String agentId);

    List<AgentDirectoryBindingEntity> findByDirectoryId(String directoryId);

    Optional<AgentDirectoryBindingEntity> findByAgentIdAndDirectoryId(String agentId, String directoryId);

    Optional<AgentDirectoryBindingEntity> findByTenantIdAndAgentIdAndDirectoryId(
            String tenantId,
            String agentId,
            String directoryId);

    void deleteByAgentIdAndDirectoryId(String agentId, String directoryId);

    void deleteByAgentId(String agentId);

    void deleteByTenantIdAndAgentIdAndDirectoryId(String tenantId, String agentId, String directoryId);

    void deleteByTenantIdAndAgentId(String tenantId, String agentId);
}
