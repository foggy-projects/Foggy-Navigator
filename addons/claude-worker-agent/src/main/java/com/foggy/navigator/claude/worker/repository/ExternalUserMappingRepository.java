package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.claude.worker.model.entity.ExternalUserMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExternalUserMappingRepository extends JpaRepository<ExternalUserMappingEntity, String> {

    Optional<ExternalUserMappingEntity> findByTenantIdAndExternalUserId(String tenantId, String externalUserId);

    List<ExternalUserMappingEntity> findByTenantId(String tenantId);

    void deleteByTenantIdAndExternalUserId(String tenantId, String externalUserId);
}
