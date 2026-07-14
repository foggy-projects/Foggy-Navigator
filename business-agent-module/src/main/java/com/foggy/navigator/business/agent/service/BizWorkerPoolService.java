package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BizWorkerIdentityDTO;
import com.foggy.navigator.business.agent.model.dto.BizWorkerPoolDTO;
import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.model.form.AddWorkerPoolMemberForm;
import com.foggy.navigator.business.agent.model.form.CreateWorkerPoolForm;
import com.foggy.navigator.business.agent.model.form.RegisterWorkerIdentityForm;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BizWorkerPoolService {

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String HEALTHY = "HEALTHY";
    public static final String UNHEALTHY = "UNHEALTHY";
    public static final String PLATFORM_OWNER_ID = "platform";

    private final BizWorkerIdentityRepository workerIdentityRepository;
    private final BizWorkerPoolRepository workerPoolRepository;
    private final BizWorkerPoolMemberRepository workerPoolMemberRepository;

    @Transactional
    public BizWorkerIdentityDTO registerWorkerIdentity(RegisterWorkerIdentityForm form) {
        return registerWorkerIdentity(ResourceOwnerType.PLATFORM, PLATFORM_OWNER_ID, form);
    }

    @Transactional
    public BizWorkerIdentityDTO registerWorkerIdentity(ResourceOwnerType ownerType,
                                                       String ownerId,
                                                       RegisterWorkerIdentityForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        validateWorkerOwner(ownerType, ownerId);
        requireText(form.getWorkerId(), "workerId is required");
        requireText(form.getWorkerBackend(), "workerBackend is required");
        requireText(form.getBaseUrl(), "baseUrl is required");

        String workerId = form.getWorkerId().trim();
        String normalizedOwnerId = ownerId.trim();
        String workerBackend = form.getWorkerBackend().trim();
        Optional<BizWorkerIdentityEntity> existing = workerIdentityRepository.findByWorkerId(workerId);
        BizWorkerIdentityEntity entity = existing.orElseGet(BizWorkerIdentityEntity::new);
        if (existing.isPresent()) {
            requireSameWorkerOwner(entity, ownerType, normalizedOwnerId);
            requireSameWorkerBackend(entity, workerBackend);
        } else {
            entity.setWorkerId(workerId);
            entity.setOwnerType(ownerType);
            entity.setOwnerId(normalizedOwnerId);
            entity.setWorkerBackend(workerBackend);
            entity.setStatus(STATUS_ENABLED);
            entity.setHealthStatus(HEALTHY);
        }
        boolean hasLegacyIdentityToken = StringUtils.hasText(form.getIdentityToken());
        if (hasLegacyIdentityToken
                && existing.isPresent()
                && entity.getCredentialVersion() != null
                && entity.getCredentialVersion() > 0) {
            throw new IllegalStateException(
                    "modern worker credential must be rotated through the credential API");
        }
        entity.setBaseUrl(form.getBaseUrl().trim());
        entity.setVersion(form.getVersion());
        if (hasLegacyIdentityToken) {
            // Registration tokens remain legacy v0. Strict external Worker
            // authentication deliberately rejects them; only the credential
            // lifecycle service can issue a modern server-generated secret.
            entity.setCredentialVersion(0);
            entity.setTokenHash(SecretTokenSupport.sha256(form.getIdentityToken()));
            entity.setCredentialIssuedAt(null);
            entity.setCredentialExpiresAt(null);
            entity.setCredentialRevokedAt(null);
            entity.setCredentialRotatedAt(null);
        } else if (entity.getCredentialVersion() == null) {
            entity.setCredentialVersion(0);
        }
        return BizWorkerIdentityDTO.fromEntity(workerIdentityRepository.save(entity));
    }

    @Transactional
    public BizWorkerPoolDTO createPool(String tenantId, CreateWorkerPoolForm form) {
        return createPool(tenantId, ResourceOwnerType.PLATFORM, tenantId, form);
    }

    @Transactional
    public BizWorkerPoolDTO createPool(String tenantId,
                                       ResourceOwnerType ownerType,
                                       String ownerId,
                                       CreateWorkerPoolForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(tenantId, "tenantId is required");
        validateWorkerOwner(ownerType, ownerId);
        requireText(form.getName(), "name is required");
        requireText(form.getWorkerBackend(), "workerBackend is required");

        String normalizedTenantId = tenantId.trim();
        String normalizedPoolId = StringUtils.hasText(form.getPoolId())
                ? form.getPoolId().trim()
                : "bwp_" + UUID.randomUUID();
        workerPoolRepository.findByPoolId(normalizedPoolId).ifPresent(existing -> {
            throw new IllegalArgumentException("worker pool already exists: " + normalizedPoolId);
        });

        BizWorkerPoolEntity entity = new BizWorkerPoolEntity();
        entity.setPoolId(normalizedPoolId);
        entity.setTenantId(normalizedTenantId);
        entity.setOwnerType(ownerType);
        entity.setOwnerId(ownerId.trim());
        entity.setName(form.getName().trim());
        entity.setWorkerBackend(form.getWorkerBackend().trim());
        entity.setRoutingPolicy(StringUtils.hasText(form.getRoutingPolicy())
                ? form.getRoutingPolicy().trim()
                : "ROUND_ROBIN");
        entity.setStatus(STATUS_ENABLED);
        entity.setHealthStatus(HEALTHY);
        return BizWorkerPoolDTO.fromEntity(workerPoolRepository.save(entity));
    }

    @Transactional
    public void addMember(String tenantId, String poolId, AddWorkerPoolMemberForm form) {
        addMember(tenantId, ResourceOwnerType.PLATFORM, tenantId, poolId, form);
    }

    @Transactional
    public void addMember(String tenantId,
                          ResourceOwnerType ownerType,
                          String ownerId,
                          String poolId,
                          AddWorkerPoolMemberForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(form.getWorkerId(), "workerId is required");
        requireText(poolId, "poolId is required");
        String normalizedPoolId = poolId.trim();
        BizWorkerPoolEntity pool = requireOwnedPool(
                tenantId, ownerType, ownerId, normalizedPoolId);
        String workerId = form.getWorkerId().trim();
        BizWorkerIdentityEntity worker = workerIdentityRepository.findByWorkerId(workerId)
                .orElseThrow(() -> new IllegalArgumentException("worker identity not found: " + workerId));
        requireAvailableWorker(worker);
        if (!pool.getWorkerBackend().equals(worker.getWorkerBackend())) {
            throw new IllegalArgumentException("worker backend mismatch");
        }
        validateWorkerVisibleToPool(pool, worker);
        workerPoolMemberRepository.findByPoolIdAndWorkerId(normalizedPoolId, workerId).ifPresent(existing -> {
            throw new IllegalArgumentException("worker already in pool: " + workerId);
        });

        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setPoolId(normalizedPoolId);
        member.setWorkerId(workerId);
        member.setStatus(STATUS_ENABLED);
        workerPoolMemberRepository.save(member);
    }

    @Transactional(readOnly = true)
    public List<BizWorkerPoolDTO> listPools(String tenantId) {
        return listPools(tenantId, ResourceOwnerType.PLATFORM, tenantId);
    }

    @Transactional(readOnly = true)
    public List<BizWorkerPoolDTO> listPools(String tenantId,
                                           ResourceOwnerType ownerType,
                                           String ownerId) {
        requireText(tenantId, "tenantId is required");
        validateWorkerOwner(ownerType, ownerId);
        return workerPoolRepository
                .findByTenantIdAndOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                        tenantId.trim(), ownerType, ownerId.trim())
                .stream()
                .map(BizWorkerPoolDTO::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public BizWorkerPoolEntity requireAvailablePool(String tenantId, String poolId) {
        return requireAvailablePool(
                tenantId, ResourceOwnerType.PLATFORM, tenantId, poolId);
    }

    @Transactional(readOnly = true)
    public BizWorkerPoolEntity requireAvailablePool(String tenantId,
                                                    ResourceOwnerType ownerType,
                                                    String ownerId,
                                                    String poolId) {
        BizWorkerPoolEntity pool = requireOwnedPool(tenantId, ownerType, ownerId, poolId);
        if (!STATUS_ENABLED.equals(pool.getStatus()) || !HEALTHY.equals(pool.getHealthStatus())) {
            throw new IllegalStateException("worker pool is not available: " + poolId);
        }
        return pool;
    }

    @Transactional
    public BizWorkerPoolDTO updatePoolStatus(String tenantId, String poolId, String status) {
        return updatePoolStatus(
                tenantId, ResourceOwnerType.PLATFORM, tenantId, poolId, status);
    }

    @Transactional
    public BizWorkerPoolDTO updatePoolStatus(String tenantId,
                                             ResourceOwnerType ownerType,
                                             String ownerId,
                                             String poolId,
                                             String status) {
        String normalizedStatus = requireAllowedStatus(status);
        BizWorkerPoolEntity pool = requireOwnedPool(tenantId, ownerType, ownerId, poolId);
        pool.setStatus(normalizedStatus);
        return BizWorkerPoolDTO.fromEntity(workerPoolRepository.save(pool));
    }

    private BizWorkerPoolEntity requireOwnedPool(String tenantId,
                                                 ResourceOwnerType ownerType,
                                                 String ownerId,
                                                 String poolId) {
        requireText(tenantId, "tenantId is required");
        requireText(poolId, "poolId is required");
        validateWorkerOwner(ownerType, ownerId);
        return workerPoolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                        poolId.trim(), tenantId.trim(), ownerType, ownerId.trim())
                .orElseThrow(() -> new IllegalArgumentException("worker pool not found: " + poolId));
    }

    private void validateWorkerOwner(ResourceOwnerType ownerType, String ownerId) {
        if (ownerType == null || !StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("worker owner is required");
        }
        if (ownerType != ResourceOwnerType.PLATFORM && ownerType != ResourceOwnerType.UPSTREAM_SYSTEM) {
            throw new IllegalArgumentException("worker ownerType is not allowed: " + ownerType);
        }
    }

    private void validateWorkerVisibleToPool(BizWorkerPoolEntity pool, BizWorkerIdentityEntity worker) {
        if (pool.getOwnerType() == null || !StringUtils.hasText(pool.getOwnerId())) {
            throw new IllegalStateException("worker pool owner is not configured: " + pool.getPoolId());
        }
        if (worker.getOwnerType() == null || !StringUtils.hasText(worker.getOwnerId())) {
            throw new IllegalStateException("worker identity owner is not configured: " + worker.getWorkerId());
        }
        validateWorkerOwner(pool.getOwnerType(), pool.getOwnerId());
        validateWorkerOwner(worker.getOwnerType(), worker.getOwnerId());
        if (worker.getOwnerType() == ResourceOwnerType.PLATFORM) {
            return;
        }
        if (pool.getOwnerType() != ResourceOwnerType.UPSTREAM_SYSTEM
                || !worker.getOwnerId().equals(pool.getOwnerId())) {
            throw new SecurityException("worker identity is not visible to worker pool: " + worker.getWorkerId());
        }
    }

    private void requireSameWorkerOwner(BizWorkerIdentityEntity worker,
                                        ResourceOwnerType ownerType,
                                        String ownerId) {
        if (worker.getOwnerType() != ownerType || !ownerId.equals(worker.getOwnerId())) {
            throw new SecurityException("worker identity owner mismatch: " + worker.getWorkerId());
        }
    }

    private void requireSameWorkerBackend(BizWorkerIdentityEntity worker, String workerBackend) {
        String existingBackend = StringUtils.hasText(worker.getWorkerBackend())
                ? worker.getWorkerBackend().trim()
                : null;
        if (!workerBackend.equals(existingBackend)) {
            throw new IllegalArgumentException("worker backend cannot be changed: " + worker.getWorkerId());
        }
    }

    private void requireAvailableWorker(BizWorkerIdentityEntity worker) {
        if (!STATUS_ENABLED.equals(worker.getStatus()) || !HEALTHY.equals(worker.getHealthStatus())) {
            throw new IllegalStateException("worker identity is not available: " + worker.getWorkerId());
        }
    }

    private String requireAllowedStatus(String status) {
        requireText(status, "status is required");
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!STATUS_ENABLED.equals(normalized) && !STATUS_DISABLED.equals(normalized)) {
            throw new IllegalArgumentException("unsupported worker pool status: " + status);
        }
        return normalized;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
