package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface BusinessTaskScopedTokenRepository extends JpaRepository<BusinessTaskScopedTokenEntity, Long> {

    Optional<BusinessTaskScopedTokenEntity> findByTokenId(String tokenId);

    Optional<BusinessTaskScopedTokenEntity> findByTokenIdAndTenantId(String tokenId, String tenantId);

    Optional<BusinessTaskScopedTokenEntity> findByTokenHash(String tokenHash);

    Optional<BusinessTaskScopedTokenEntity> findFirstByWorkerTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
            String workerTaskId, String tenantId, String clientAppId);

    boolean existsByTenantIdAndClientAppIdAndUpstreamUserIdAndSessionIdAndStatusAndExpiresAtAfter(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String sessionId,
            String status,
            LocalDateTime expiresAt);

}
