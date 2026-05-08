package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.ClientAppFunctionGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientAppFunctionGrantRepository extends JpaRepository<ClientAppFunctionGrantEntity, Long> {
    Optional<ClientAppFunctionGrantEntity> findByGrantIdAndTenantId(String grantId, String tenantId);
    Optional<ClientAppFunctionGrantEntity> findByTenantIdAndClientAppIdAndFunctionIdAndVersion(String tenantId, String clientAppId, String functionId, String version);
    List<ClientAppFunctionGrantEntity> findByTenantIdAndClientAppId(String tenantId, String clientAppId);
}
