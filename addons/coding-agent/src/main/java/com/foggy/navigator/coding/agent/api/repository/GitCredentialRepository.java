package com.foggy.navigator.coding.agent.api.repository;

import com.foggy.navigator.coding.agent.api.model.entity.GitCredentialEntity;
import com.foggy.navigator.coding.agent.api.model.entity.GitProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GitCredentialRepository extends JpaRepository<GitCredentialEntity, Long> {

    Optional<GitCredentialEntity> findByCredentialId(String credentialId);

    List<GitCredentialEntity> findByUserId(String userId);

    List<GitCredentialEntity> findByUserIdAndProvider(String userId, GitProvider provider);

    Optional<GitCredentialEntity> findByUserIdAndProviderAndServerUrl(String userId, GitProvider provider, String serverUrl);

    boolean existsByCredentialId(String credentialId);

    boolean existsByUserIdAndProviderAndServerUrl(String userId, GitProvider provider, String serverUrl);

    void deleteByCredentialId(String credentialId);
}
