package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.RuntimeRequestAuditEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RuntimeRequestAuditRepository extends JpaRepository<RuntimeRequestAuditEntity, Long> {

    Optional<RuntimeRequestAuditEntity> findByClientRequestId(String clientRequestId);

    Optional<RuntimeRequestAuditEntity> findTopByTaskIdAndOperationOrderByReceivedAtDesc(
            String taskId, String operation);

    Optional<RuntimeRequestAuditEntity> findTopByTaskIdAndOperationAndExpiresAtAfterOrderByReceivedAtDesc(
            String taskId, String operation, Instant now);

    Optional<RuntimeRequestAuditEntity> findByClientRequestIdAndTenantIdAndUpstreamSystemIdAndClientAppIdAndExpiresAtAfter(
            String clientRequestId,
            String tenantId,
            String upstreamSystemId,
            String clientAppId,
            Instant now);

    @Query("""
            select a from RuntimeRequestAuditEntity a
            where a.tenantId = :tenantId
              and a.upstreamSystemId = :upstreamSystemId
              and a.clientAppId = :clientAppId
              and a.receivedAt >= :since
              and a.receivedAt <= :until
              and a.expiresAt > :now
              and (:operation is null or a.operation = :operation)
              and (:agentCode is null or a.agentCode = :agentCode)
              and (:upstreamUserId is null or a.upstreamUserId = :upstreamUserId)
            order by a.receivedAt desc
            """)
    List<RuntimeRequestAuditEntity> findVisibleWindow(
            String tenantId,
            String upstreamSystemId,
            String clientAppId,
            Instant since,
            Instant until,
            Instant now,
            String operation,
            String agentCode,
            String upstreamUserId,
            Pageable pageable);

    List<RuntimeRequestAuditEntity> findByExpiresAtBeforeOrderByExpiresAtAsc(Instant now, Pageable pageable);
}
