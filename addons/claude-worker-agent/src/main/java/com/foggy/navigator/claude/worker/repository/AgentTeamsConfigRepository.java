package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.claude.worker.model.entity.AgentTeamsConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentTeamsConfigRepository extends JpaRepository<AgentTeamsConfigEntity, Long> {

    List<AgentTeamsConfigEntity> findByDirectoryIdAndUserIdOrderByCreatedAtAsc(String directoryId, String userId);

    Optional<AgentTeamsConfigEntity> findByConfigIdAndUserId(String configId, String userId);

    Optional<AgentTeamsConfigEntity> findByConfigId(String configId);

    Optional<AgentTeamsConfigEntity> findByDirectoryIdAndUserIdAndIsDefaultTrue(String directoryId, String userId);

    void deleteByDirectoryIdAndUserId(String directoryId, String userId);

    boolean existsByDirectoryIdAndUserId(String directoryId, String userId);
}
