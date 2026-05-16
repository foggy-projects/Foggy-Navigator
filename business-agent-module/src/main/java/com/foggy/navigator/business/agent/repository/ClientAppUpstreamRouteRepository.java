package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.ClientAppUpstreamRouteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClientAppUpstreamRouteRepository extends JpaRepository<ClientAppUpstreamRouteEntity, Long> {

    List<ClientAppUpstreamRouteEntity> findByTenantIdAndClientAppIdOrderByUpstreamRefAsc(
            String tenantId, String clientAppId);

    Optional<ClientAppUpstreamRouteEntity> findByTenantIdAndClientAppIdAndUpstreamRef(
            String tenantId, String clientAppId, String upstreamRef);

    Optional<ClientAppUpstreamRouteEntity> findByTenantIdAndClientAppIdAndUpstreamRefAndStatus(
            String tenantId, String clientAppId, String upstreamRef, String status);
}
