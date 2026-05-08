package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClientAppRuntimeCredentialRepository extends JpaRepository<ClientAppRuntimeCredentialEntity, Long> {

    Optional<ClientAppRuntimeCredentialEntity> findByAppKey(String appKey);

    List<ClientAppRuntimeCredentialEntity> findByClientAppIdOrderByCreatedAtDesc(String clientAppId);
}
