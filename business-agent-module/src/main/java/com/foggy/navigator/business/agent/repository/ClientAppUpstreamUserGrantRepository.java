package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.ClientAppUpstreamUserGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientAppUpstreamUserGrantRepository extends JpaRepository<ClientAppUpstreamUserGrantEntity, Long> {
    Optional<ClientAppUpstreamUserGrantEntity> findByTenantIdAndClientAppIdAndUpstreamUserId(String tenantId, String clientAppId, String upstreamUserId);
    List<ClientAppUpstreamUserGrantEntity> findByTenantIdAndClientAppId(String tenantId, String clientAppId);
}
