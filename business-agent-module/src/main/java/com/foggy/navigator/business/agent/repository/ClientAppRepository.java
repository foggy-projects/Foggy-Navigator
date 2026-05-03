package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClientAppRepository extends JpaRepository<ClientAppEntity, Long> {

    Optional<ClientAppEntity> findByClientAppId(String clientAppId);

    Optional<ClientAppEntity> findByClientAppIdAndTenantId(String clientAppId, String tenantId);

    List<ClientAppEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
