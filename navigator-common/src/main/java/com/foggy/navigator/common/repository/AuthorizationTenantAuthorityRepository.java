package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.AuthorizationTenantAuthorityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Query surface for authoritative tenant-to-upstream mappings. */
public interface AuthorizationTenantAuthorityRepository extends JpaRepository<AuthorizationTenantAuthorityEntity, String> {

    Optional<AuthorizationTenantAuthorityEntity> findByNavigatorInstanceIdAndTenantId(
            String navigatorInstanceId,
            String tenantId);

    List<AuthorizationTenantAuthorityEntity> findByNavigatorInstanceIdAndUpstreamSystemIdAndStatus(
            String navigatorInstanceId,
            String upstreamSystemId,
            String status);
}
