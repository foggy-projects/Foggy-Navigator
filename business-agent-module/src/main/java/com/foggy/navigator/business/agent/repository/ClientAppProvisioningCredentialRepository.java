package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.ClientAppProvisioningCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClientAppProvisioningCredentialRepository extends JpaRepository<ClientAppProvisioningCredentialEntity, Long> {

    Optional<ClientAppProvisioningCredentialEntity> findByCredentialId(String credentialId);

    Optional<ClientAppProvisioningCredentialEntity> findByTokenHash(String tokenHash);
}
