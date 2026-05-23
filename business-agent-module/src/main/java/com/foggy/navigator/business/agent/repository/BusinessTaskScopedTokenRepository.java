package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessTaskScopedTokenRepository extends JpaRepository<BusinessTaskScopedTokenEntity, Long> {

    Optional<BusinessTaskScopedTokenEntity> findByTokenId(String tokenId);

    Optional<BusinessTaskScopedTokenEntity> findByTokenIdAndTenantId(String tokenId, String tenantId);

    Optional<BusinessTaskScopedTokenEntity> findByTokenHash(String tokenHash);

    Optional<BusinessTaskScopedTokenEntity> findFirstByWorkerTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
            String workerTaskId, String tenantId, String clientAppId);

}
