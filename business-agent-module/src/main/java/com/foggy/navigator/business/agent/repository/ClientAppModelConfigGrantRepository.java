package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.ClientAppModelConfigGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClientAppModelConfigGrantRepository extends JpaRepository<ClientAppModelConfigGrantEntity, Long> {

    List<ClientAppModelConfigGrantEntity> findByClientAppIdOrderByCreatedAtDesc(String clientAppId);

    Optional<ClientAppModelConfigGrantEntity> findByIdAndClientAppId(Long id, String clientAppId);

    Optional<ClientAppModelConfigGrantEntity> findByClientAppIdAndModelConfigId(String clientAppId, String modelConfigId);

    Optional<ClientAppModelConfigGrantEntity> findByClientAppIdAndModelConfigIdAndStatus(
            String clientAppId, String modelConfigId, String status);

    List<ClientAppModelConfigGrantEntity> findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
            String clientAppId, String status);
}
