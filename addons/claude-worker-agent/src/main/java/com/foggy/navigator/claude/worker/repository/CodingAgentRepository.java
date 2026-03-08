package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.common.entity.CodingAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CodingAgentRepository extends JpaRepository<CodingAgentEntity, Long> {

    Optional<CodingAgentEntity> findByAgentId(String agentId);

    Optional<CodingAgentEntity> findByAgentIdAndUserId(String agentId, String userId);

    List<CodingAgentEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<CodingAgentEntity> findByWorkerIdAndUserId(String workerId, String userId);
}
