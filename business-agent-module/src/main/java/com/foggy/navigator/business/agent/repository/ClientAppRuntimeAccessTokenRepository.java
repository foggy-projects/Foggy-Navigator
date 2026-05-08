package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeAccessTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClientAppRuntimeAccessTokenRepository
        extends JpaRepository<ClientAppRuntimeAccessTokenEntity, Long> {

    Optional<ClientAppRuntimeAccessTokenEntity> findByTokenHash(String tokenHash);
}
