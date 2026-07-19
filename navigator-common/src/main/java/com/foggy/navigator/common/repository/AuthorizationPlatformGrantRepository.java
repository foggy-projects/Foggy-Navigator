package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.AuthorizationPlatformGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Query surface for server-owned S2 platform grants. */
public interface AuthorizationPlatformGrantRepository extends JpaRepository<AuthorizationPlatformGrantEntity, String> {

    Optional<AuthorizationPlatformGrantEntity> findByNavigatorInstanceIdAndEnvironmentProfileAndPrincipalIdAndUpstreamSystemId(
            String navigatorInstanceId,
            String environmentProfile,
            String principalId,
            String upstreamSystemId);

    List<AuthorizationPlatformGrantEntity> findByNavigatorInstanceIdAndUpstreamSystemIdAndStatus(
            String navigatorInstanceId,
            String upstreamSystemId,
            String status);
}
