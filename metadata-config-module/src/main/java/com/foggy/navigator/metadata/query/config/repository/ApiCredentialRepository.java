package com.foggy.navigator.metadata.query.config.repository;

import com.foggy.navigator.common.entity.ApiCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiCredentialRepository extends JpaRepository<ApiCredentialEntity, String> {

    List<ApiCredentialEntity> findByTenantIdOrderByCreatedAtAsc(String tenantId);

    Optional<ApiCredentialEntity> findByIdAndTenantId(String id, String tenantId);

    List<ApiCredentialEntity> findByTenantIdAndCategoryOrderByCreatedAtAsc(String tenantId, String category);

    Optional<ApiCredentialEntity> findByTenantIdAndName(String tenantId, String name);

    boolean existsByTenantId(String tenantId);
}
