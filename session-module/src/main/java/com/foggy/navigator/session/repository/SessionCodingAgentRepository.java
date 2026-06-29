package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.CodingAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SessionCodingAgentRepository extends JpaRepository<CodingAgentEntity, Long> {

    Optional<CodingAgentEntity> findByAgentIdAndTenantId(String agentId, String tenantId);

    Optional<CodingAgentEntity> findByAgentIdAndUserId(String agentId, String userId);
}
