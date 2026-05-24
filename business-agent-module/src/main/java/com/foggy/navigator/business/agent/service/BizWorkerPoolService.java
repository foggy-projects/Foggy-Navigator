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

        BizWorkerIdentityEntity entity = workerIdentityRepository.findByWorkerId(form.getWorkerId())
                .orElseGet(BizWorkerIdentityEntity::new);
        entity.setWorkerId(form.getWorkerId());
        entity.setOwnerType(ownerType);
        entity.setOwnerId(ownerId.trim());
        entity.setWorkerBackend(form.getWorkerBackend());
        entity.setBaseUrl(form.getBaseUrl());
        entity.setVersion(form.getVersion());
        entity.setStatus(STATUS_ENABLED);
        entity.setHealthStatus(HEALTHY);
        if (StringUtils.hasText(form.getIdentityToken())) {
            entity.setTokenHash(SecretTokenSupport.sha256(form.getIdentityToken()));
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

        String poolId = StringUtils.hasText(form.getPoolId()) ? form.getPoolId() : "bwp_" + UUID.randomUUID();
        workerPoolRepository.findByPoolId(poolId).ifPresent(existing -> {
            throw new IllegalArgumentException("worker pool already exists: " + poolId);
        });

        BizWorkerPoolEntity entity = new BizWorkerPoolEntity();
        entity.setPoolId(poolId);
        entity.setTenantId(tenantId);
        entity.setOwnerType(ownerType);
        entity.setOwnerId(ownerId.trim());
        entity.setName(form.getName());
        entity.setWorkerBackend(form.getWorkerBackend());
        entity.setRoutingPolicy(StringUtils.hasText(form.getRoutingPolicy()) ? form.getRoutingPolicy() : "ROUND_ROBIN");
        entity.setStatus(STATUS_ENABLED);
        entity.setHealthStatus(HEALTHY);
        return BizWorkerPoolDTO.fromEntity(workerPoolRepository.save(entity));
    }

    @Transactional
    public void addMember(String tenantId, String poolId, AddWorkerPoolMemberForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(form.getWorkerId(), "workerId is required");
        BizWorkerPoolEntity pool = requirePool(tenantId, poolId);
        BizWorkerIdentityEntity worker = workerIdentityRepository.findByWorkerId(form.getWorkerId())
                .orElseThrow(() -> new IllegalArgumentException("worker identity not found: " + form.getWorkerId()));
        if (!pool.getWorkerBackend().equals(worker.getWorkerBackend())) {
            throw new IllegalArgumentException("worker backend mismatch");
        }
        validateWorkerVisibleToPool(pool, worker);
        workerPoolMemberRepository.findByPoolIdAndWorkerId(poolId, form.getWorkerId()).ifPresent(existing -> {
            throw new IllegalArgumentException("worker already in pool: " + form.getWorkerId());
        });

        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setPoolId(poolId);
        member.setWorkerId(form.getWorkerId());
        member.setStatus(STATUS_ENABLED);
        workerPoolMemberRepository.save(member);
    }

    @Transactional(readOnly = true)
    public List<BizWorkerPoolDTO> listPools(String tenantId) {
        requireText(tenantId, "tenantId is required");
        return workerPoolRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(BizWorkerPoolDTO::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public BizWorkerPoolEntity requireAvailablePool(String tenantId, String poolId) {
        BizWorkerPoolEntity pool = requirePool(tenantId, poolId);
        if (!STATUS_ENABLED.equals(pool.getStatus()) || !HEALTHY.equals(pool.getHealthStatus())) {
            throw new IllegalStateException("worker pool is not available: " + poolId);
        }
        return pool;
    }

    @Transactional
    public BizWorkerPoolDTO updatePoolStatus(String tenantId, String poolId, String status) {
        requireText(status, "status is required");
        BizWorkerPoolEntity pool = requirePool(tenantId, poolId);
        pool.setStatus(status);
        return BizWorkerPoolDTO.fromEntity(workerPoolRepository.save(pool));
    }

    private BizWorkerPoolEntity requirePool(String tenantId, String poolId) {
        requireText(tenantId, "tenantId is required");
        requireText(poolId, "poolId is required");
        return workerPoolRepository.findByPoolIdAndTenantId(poolId, tenantId)
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

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
