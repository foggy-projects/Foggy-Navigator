package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.common.entity.CodingAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessCodingAgentRepository extends JpaRepository<CodingAgentEntity, Long> {
    Optional<CodingAgentEntity> findByAgentId(String agentId);
}
