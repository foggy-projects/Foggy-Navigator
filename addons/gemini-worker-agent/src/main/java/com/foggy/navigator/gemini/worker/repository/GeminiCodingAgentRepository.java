package com.foggy.navigator.gemini.worker.repository;

import com.foggy.navigator.common.entity.CodingAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GeminiCodingAgentRepository extends JpaRepository<CodingAgentEntity, Long> {

    Optional<CodingAgentEntity> findByAgentIdAndUserId(String agentId, String userId);

    Optional<CodingAgentEntity> findByNameAndUserId(String name, String userId);

    Optional<CodingAgentEntity> findByAgentId(String agentId);

    List<CodingAgentEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<CodingAgentEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
