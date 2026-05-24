package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.worker.PhysicalWorkerRuntimeRegistry;
import com.foggy.navigator.business.agent.service.worker.ResolvedPhysicalWorker;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ClaudeWorkerPhysicalWorkerRuntimeRegistry implements PhysicalWorkerRuntimeRegistry {

    private static final String STATUS_ONLINE = "ONLINE";

    private final ClaudeWorkerRepository workerRepository;

    @Override
    public Optional<ResolvedPhysicalWorker> resolve(String tenantId, String upstreamSystemId, String workerId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(workerId)) {
            return Optional.empty();
        }
        return workerRepository.findByWorkerId(workerId.trim())
                .map(worker -> resolveTenantWorker(tenantId, worker));
    }

    private ResolvedPhysicalWorker resolveTenantWorker(String tenantId, ClaudeWorkerEntity worker) {
        if (!tenantId.equals(worker.getTenantId())) {
            throw new SecurityException("physical worker tenant mismatch: " + worker.getWorkerId());
        }
        if (!STATUS_ONLINE.equals(worker.getStatus())) {
            throw new IllegalStateException("physical worker is not online: " + worker.getWorkerId());
        }
        return new ResolvedPhysicalWorker(
                worker.getWorkerId(),
                null,
                ResourceOwnerType.PLATFORM,
                "TENANT:" + tenantId,
                "CLAUDE_WORKER:TENANT");
    }
}
