package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.AuthorizationCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Query surface for future typed management credentials; legacy lanes stay in their own repositories. */
public interface AuthorizationCredentialRepository extends JpaRepository<AuthorizationCredentialEntity, String> {

    Optional<AuthorizationCredentialEntity> findByVerifierReference(String verifierReference);

    /**
     * Scope-qualified resolver query. Authorization code must use this rather
     * than the legacy convenience lookup above.
     */
    Optional<AuthorizationCredentialEntity> findByVerifierReferenceAndNavigatorInstanceIdAndEnvironmentProfile(
            String verifierReference,
            String navigatorInstanceId,
            String environmentProfile);

    /** Revalidation query for a context already accepted by ingress. */
    Optional<AuthorizationCredentialEntity> findByCredentialIdAndNavigatorInstanceIdAndEnvironmentProfile(
            String credentialId,
            String navigatorInstanceId,
            String environmentProfile);

    List<AuthorizationCredentialEntity> findByPrincipalIdAndCredentialLaneAndStatusOrderByExpiresAtAsc(
            String principalId,
            String credentialLane,
            String status);
}
