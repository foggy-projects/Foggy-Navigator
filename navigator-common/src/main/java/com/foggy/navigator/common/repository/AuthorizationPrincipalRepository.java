package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.AuthorizationPrincipalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Query surface for typed management principals only. */
public interface AuthorizationPrincipalRepository extends JpaRepository<AuthorizationPrincipalEntity, String> {

    Optional<AuthorizationPrincipalEntity> findByNavigatorInstanceIdAndPrincipalTypeAndPrincipalId(
            String navigatorInstanceId,
            String principalType,
            String principalId);

    List<AuthorizationPrincipalEntity> findByNavigatorInstanceIdAndPrincipalTypeAndStatus(
            String navigatorInstanceId,
            String principalType,
            String status);

    /** Exact typed-principal lookup used by management credential/token resolution. */
    Optional<AuthorizationPrincipalEntity>
    findByPrincipalRecordIdAndNavigatorInstanceIdAndEnvironmentProfileAndPrincipalTypeAndPrincipalIdAndStatus(
            String principalRecordId,
            String navigatorInstanceId,
            String environmentProfile,
            String principalType,
            String principalId,
            String status);
}
