package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BizWorkerCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.BizWorkerPrincipal;
import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BizWorkerCredentialService {

    public static final long DEFAULT_TTL_SECONDS = 30L * 24 * 60 * 60;
    public static final long MIN_TTL_SECONDS = 60;
    public static final long MAX_TTL_SECONDS = 365L * 24 * 60 * 60;
    private static final String INVALID_HASH = SecretTokenSupport.sha256(
            "biz-worker-invalid-credential-sentinel");

    private final BizWorkerIdentityRepository workerIdentityRepository;

    @Transactional
    public BizWorkerCredentialDTO rotatePlatformCredential(String workerId, Long ttlSeconds) {
        return rotateCredential(
                ResourceOwnerType.PLATFORM,
                BizWorkerPoolService.PLATFORM_OWNER_ID,
                workerId,
                ttlSeconds);
    }

    @Transactional
    public BizWorkerCredentialDTO rotateCredential(
            ResourceOwnerType ownerType,
            String ownerId,
            String workerId,
            Long ttlSeconds) {
        BizWorkerIdentityEntity identity = requireOwnedIdentityForUpdate(ownerType, ownerId, workerId);
        long normalizedTtlSeconds = normalizeTtlSeconds(ttlSeconds);
        LocalDateTime now = LocalDateTime.now();
        String secret = SecretTokenSupport.randomToken("bwc_");

        identity.setTokenHash(SecretTokenSupport.sha256(secret));
        identity.setCredentialVersion(nextCredentialVersion(identity.getCredentialVersion()));
        identity.setCredentialIssuedAt(now);
        identity.setCredentialExpiresAt(now.plusSeconds(normalizedTtlSeconds));
        identity.setCredentialRevokedAt(null);
        identity.setCredentialRotatedAt(now);
        BizWorkerIdentityEntity saved = workerIdentityRepository.saveAndFlush(identity);
        return BizWorkerCredentialDTO.fromEntity(saved, secret);
    }

    @Transactional
    public BizWorkerCredentialDTO revokePlatformCredential(String workerId) {
        return revokeCredential(
                ResourceOwnerType.PLATFORM,
                BizWorkerPoolService.PLATFORM_OWNER_ID,
                workerId);
    }

    @Transactional
    public BizWorkerCredentialDTO revokeCredential(
            ResourceOwnerType ownerType,
            String ownerId,
            String workerId) {
        BizWorkerIdentityEntity identity = requireOwnedIdentityForUpdate(ownerType, ownerId, workerId);
        if (identity.getCredentialVersion() == null || identity.getCredentialVersion() <= 0) {
            throw new IllegalStateException("legacy worker credential cannot be revoked through the strict credential API");
        }
        if (identity.getCredentialRevokedAt() == null) {
            identity.setCredentialRevokedAt(LocalDateTime.now());
        }
        BizWorkerIdentityEntity saved = workerIdentityRepository.saveAndFlush(identity);
        return BizWorkerCredentialDTO.fromEntity(saved, null);
    }

    /**
     * Validates a modern Worker credential. Version-0 registration tokens are
     * always rejected, even when their hash matches. No expiry grace exists.
     */
    @Transactional(readOnly = true)
    public BizWorkerPrincipal requireStrictCredential(String workerId, String secret) {
        requireText(workerId, "workerId is required");
        requireText(secret, "worker credential is required");
        Optional<BizWorkerIdentityEntity> candidate = workerIdentityRepository.findByWorkerId(workerId.trim());
        String storedHash = candidate
                .map(BizWorkerIdentityEntity::getTokenHash)
                .filter(StringUtils::hasText)
                .orElse(INVALID_HASH);
        boolean secretMatches = constantTimeHashEquals(
                storedHash, SecretTokenSupport.sha256(secret));
        if (candidate.isEmpty()) {
            throw invalidCredential();
        }
        BizWorkerIdentityEntity identity = candidate.get();
        if (!secretMatches || !isStrictlyUsable(identity, LocalDateTime.now())) {
            throw invalidCredential();
        }

        return BizWorkerPrincipal.builder()
                .workerId(identity.getWorkerId())
                .ownerType(identity.getOwnerType())
                .ownerId(identity.getOwnerId())
                .workerBackend(identity.getWorkerBackend())
                .credentialVersion(identity.getCredentialVersion())
                .build();
    }

    private BizWorkerIdentityEntity requireOwnedIdentityForUpdate(
            ResourceOwnerType ownerType,
            String ownerId,
            String workerId) {
        validateOwner(ownerType, ownerId);
        requireText(workerId, "workerId is required");
        String normalizedOwnerId = ownerId.trim();
        BizWorkerIdentityEntity identity = workerIdentityRepository
                .findByWorkerIdAndOwnerTypeAndOwnerIdForUpdate(
                        workerId.trim(), ownerType, normalizedOwnerId)
                .orElseThrow(() -> new IllegalArgumentException("worker identity not found"));
        return identity;
    }

    private int nextCredentialVersion(Integer currentVersion) {
        if (currentVersion == null || currentVersion < 0) {
            return 1;
        }
        if (currentVersion == Integer.MAX_VALUE) {
            throw new IllegalStateException("worker credential version exhausted");
        }
        return currentVersion + 1;
    }

    private long normalizeTtlSeconds(Long ttlSeconds) {
        long normalized = ttlSeconds == null ? DEFAULT_TTL_SECONDS : ttlSeconds;
        if (normalized < MIN_TTL_SECONDS || normalized > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException(
                    "worker credential ttlSeconds must be between "
                            + MIN_TTL_SECONDS + " and " + MAX_TTL_SECONDS);
        }
        return normalized;
    }

    private boolean constantTimeHashEquals(String expectedHash, String candidateHash) {
        return MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.UTF_8),
                candidateHash.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isStrictlyUsable(BizWorkerIdentityEntity identity, LocalDateTime now) {
        return BizWorkerPoolService.STATUS_ENABLED.equals(identity.getStatus())
                && identity.getCredentialVersion() != null
                && identity.getCredentialVersion() > 0
                && identity.getCredentialRevokedAt() == null
                && identity.getCredentialIssuedAt() != null
                && identity.getCredentialExpiresAt() != null
                && identity.getCredentialExpiresAt().isAfter(now);
    }

    private SecurityException invalidCredential() {
        return new SecurityException("invalid worker credential");
    }

    private void validateOwner(ResourceOwnerType ownerType, String ownerId) {
        if ((ownerType != ResourceOwnerType.PLATFORM
                && ownerType != ResourceOwnerType.UPSTREAM_SYSTEM)
                || !StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("worker owner is required");
        }
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
