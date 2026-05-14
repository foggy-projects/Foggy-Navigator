package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessAgentSessionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BusinessAgentSessionRepository extends JpaRepository<BusinessAgentSessionEntity, Long> {

    Optional<BusinessAgentSessionEntity> findByTenantIdAndClientAppIdAndUpstreamUserIdAndContextId(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String contextId);

    Optional<BusinessAgentSessionEntity> findByTenantIdAndClientAppIdAndUpstreamUserIdAndSessionId(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String sessionId);

    List<BusinessAgentSessionEntity> findByTenantIdAndClientAppIdAndUpstreamUserIdOrderByLastAccessedAtDesc(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            Pageable pageable);

    List<BusinessAgentSessionEntity> findByTenantIdAndClientAppIdAndUpstreamUserIdAndLastAccessedAtBeforeOrderByLastAccessedAtDesc(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            LocalDateTime lastAccessedAt,
            Pageable pageable);
}
