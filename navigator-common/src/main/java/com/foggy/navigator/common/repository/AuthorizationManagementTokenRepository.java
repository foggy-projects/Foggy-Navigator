package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.AuthorizationManagementTokenEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Query surface for typed management access/action token metadata only. */
public interface AuthorizationManagementTokenRepository extends JpaRepository<AuthorizationManagementTokenEntity, String> {

    Optional<AuthorizationManagementTokenEntity> findByTokenHash(String tokenHash);

    /** Scope-qualified resolver query; the unqualified hash lookup remains only for compatibility. */
    Optional<AuthorizationManagementTokenEntity>
    findByTokenHashAndTokenReferenceAndNavigatorInstanceIdAndEnvironmentProfile(
            String tokenHash,
            String tokenReference,
            String navigatorInstanceId,
            String environmentProfile);

    List<AuthorizationManagementTokenEntity> findByCredentialIdAndPurposeAndStatusOrderByExpiresAtAsc(
            String credentialId,
            String purpose,
            String status);

    /**
     * Single-use security action consumption. Every token identity, deployment
     * binding, purpose and still-active state participates in the compare and
     * set; callers may allow a security action only when this returns one.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update AuthorizationManagementTokenEntity token
               set token.status = :consumedStatus,
                   token.consumedAt = :consumedAt
             where token.tokenId = :tokenId
               and token.tokenHash = :tokenHash
               and token.tokenReference = :tokenReference
               and token.navigatorInstanceId = :navigatorInstanceId
               and token.environmentProfile = :environmentProfile
               and token.purpose = :purpose
               and token.credentialGeneration = :credentialGeneration
               and token.status = :activeStatus
               and token.consumedAt is null
               and token.expiresAt > :now
            """)
    int consumeSecurityActionAtomically(
            @Param("tokenId") String tokenId,
            @Param("tokenHash") String tokenHash,
            @Param("tokenReference") String tokenReference,
            @Param("navigatorInstanceId") String navigatorInstanceId,
            @Param("environmentProfile") String environmentProfile,
            @Param("purpose") String purpose,
            @Param("credentialGeneration") Integer credentialGeneration,
            @Param("activeStatus") String activeStatus,
            @Param("consumedStatus") String consumedStatus,
            @Param("now") LocalDateTime now,
            @Param("consumedAt") LocalDateTime consumedAt);
}
