package com.foggy.navigator.metadata.query.config.repository;

import com.foggy.navigator.common.entity.GitProviderConfigEntity;
import com.foggy.navigator.common.enums.GitProviderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Git 提供者配置 Repository
 */
@Repository
public interface GitProviderConfigRepository extends JpaRepository<GitProviderConfigEntity, String> {

    List<GitProviderConfigEntity> findByTenantIdOrderByCreatedAtAsc(String tenantId);

    Optional<GitProviderConfigEntity> findByTenantIdAndProviderType(String tenantId, GitProviderType providerType);

    Optional<GitProviderConfigEntity> findByTenantIdAndProviderTypeAndIsActiveTrue(String tenantId, GitProviderType providerType);

    boolean existsByTenantId(String tenantId);
}
