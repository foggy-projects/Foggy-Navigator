package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessCodingAgentRepository extends JpaRepository<CodingAgentEntity, Long> {
    Optional<CodingAgentEntity> findByAgentIdAndTenantId(String agentId, String tenantId);

    List<CodingAgentEntity> findByTenantIdAndOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
            String tenantId,
            ResourceOwnerType ownerType,
            String ownerId);
}
