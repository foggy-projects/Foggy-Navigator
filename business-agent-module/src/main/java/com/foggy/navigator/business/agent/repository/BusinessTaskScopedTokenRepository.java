package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessTaskScopedTokenRepository extends JpaRepository<BusinessTaskScopedTokenEntity, Long> {

    Optional<BusinessTaskScopedTokenEntity> findByTokenId(String tokenId);

    Optional<BusinessTaskScopedTokenEntity> findByTokenIdAndTenantId(String tokenId, String tenantId);

    Optional<BusinessTaskScopedTokenEntity> findByTokenHash(String tokenHash);

    List<BusinessTaskScopedTokenEntity> findByTaskIdAndTenantId(String taskId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from BusinessTaskScopedTokenEntity token where token.tokenHash = :tokenHash")
    Optional<BusinessTaskScopedTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from BusinessTaskScopedTokenEntity token " +
            "where token.tokenId = :tokenId and token.tenantId = :tenantId")
    Optional<BusinessTaskScopedTokenEntity> findByTokenIdAndTenantIdForUpdate(
            @Param("tokenId") String tokenId,
            @Param("tenantId") String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from BusinessTaskScopedTokenEntity token " +
            "where token.taskId = :taskId and token.tenantId = :tenantId")
    List<BusinessTaskScopedTokenEntity> findByTaskIdAndTenantIdForUpdate(
            @Param("taskId") String taskId,
            @Param("tenantId") String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from BusinessTaskScopedTokenEntity token " +
            "where token.tenantId = :tenantId and token.workerTaskId = :workerTaskId")
    List<BusinessTaskScopedTokenEntity> findByTenantIdAndWorkerTaskIdForUpdate(
            @Param("tenantId") String tenantId,
            @Param("workerTaskId") String workerTaskId);

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
