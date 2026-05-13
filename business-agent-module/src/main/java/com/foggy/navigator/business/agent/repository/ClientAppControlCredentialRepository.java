package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.ClientAppControlCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClientAppControlCredentialRepository extends JpaRepository<ClientAppControlCredentialEntity, Long> {

    Optional<ClientAppControlCredentialEntity> findByControlKeyHash(String controlKeyHash);

    List<ClientAppControlCredentialEntity> findByClientAppIdOrderByCreatedAtDesc(String clientAppId);
}
