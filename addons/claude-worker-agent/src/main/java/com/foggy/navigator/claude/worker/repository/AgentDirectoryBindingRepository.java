package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentDirectoryBindingRepository extends JpaRepository<AgentDirectoryBindingEntity, Long> {

    List<AgentDirectoryBindingEntity> findByAgentId(String agentId);

    Optional<AgentDirectoryBindingEntity> findByAgentIdAndDirectoryId(String agentId, String directoryId);

    void deleteByAgentIdAndDirectoryId(String agentId, String directoryId);

    void deleteByAgentId(String agentId);
}
