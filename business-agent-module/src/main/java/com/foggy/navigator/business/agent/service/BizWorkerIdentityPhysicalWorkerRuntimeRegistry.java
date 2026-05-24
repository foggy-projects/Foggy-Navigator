package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.service.worker.PhysicalWorkerRuntimeRegistry;
import com.foggy.navigator.business.agent.service.worker.ResolvedPhysicalWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BizWorkerIdentityPhysicalWorkerRuntimeRegistry implements PhysicalWorkerRuntimeRegistry {

    private final BizWorkerIdentityRepository workerIdentityRepository;

    @Override
    public Optional<ResolvedPhysicalWorker> resolve(String tenantId, String upstreamSystemId, String workerId) {
        if (!StringUtils.hasText(workerId)) {
            return Optional.empty();
        }
        return workerIdentityRepository.findByWorkerId(workerId.trim())
                .map(worker -> resolveVisibleWorker(upstreamSystemId, worker));
    }

    private ResolvedPhysicalWorker resolveVisibleWorker(String upstreamSystemId, BizWorkerIdentityEntity worker) {
        if (!BizWorkerPoolService.STATUS_ENABLED.equals(worker.getStatus())) {
            throw new IllegalStateException("physical worker is disabled: " + worker.getWorkerId());
        }
        if (!BizWorkerPoolService.HEALTHY.equals(worker.getHealthStatus())) {
            throw new IllegalStateException("physical worker is not healthy: " + worker.getWorkerId());
        }
        if (worker.getOwnerType() == null || !StringUtils.hasText(worker.getOwnerId())) {
            throw new IllegalStateException("physical worker owner is not configured: " + worker.getWorkerId());
        }

        switch (worker.getOwnerType()) {
            case PLATFORM -> {
                // Platform-owned physical workers are shared infrastructure.
            }
            case UPSTREAM_SYSTEM -> {
                if (!StringUtils.hasText(upstreamSystemId) || !upstreamSystemId.equals(worker.getOwnerId())) {
                    throw new SecurityException("physical worker is not visible to upstream system: " + worker.getWorkerId());
                }
            }
            case CLIENT_APP, UPSTREAM_USER -> throw new SecurityException(
                    "physical worker ownerType is not allowed for runtime: " + worker.getWorkerId());
        }

        String workerBackend = trimToNull(worker.getWorkerBackend());
        if (workerBackend == null) {
            throw new IllegalStateException("physical worker backend is not configured: " + worker.getWorkerId());
        }
        return new ResolvedPhysicalWorker(
                worker.getWorkerId(),
                workerBackend,
                worker.getOwnerType(),
                worker.getOwnerId(),
                "PHYSICAL_WORKER_IDENTITY:" + worker.getOwnerType());
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
