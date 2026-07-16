package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Resolves a Pool execution Worker under the same owner, backend and enabled
 * member constraints used before a Biz Worker task is launched.
 */
@Service
@RequiredArgsConstructor
public class BizWorkerPoolWorkerSelector {

    private final BizWorkerPoolService bizWorkerPoolService;
    private final BizWorkerPoolMemberRepository poolMemberRepository;

    public String resolveEnabledWorkerId(
            String tenantId,
            ResourceOwnerType poolOwnerType,
            String poolOwnerId,
            String poolId,
            String expectedWorkerBackend,
            String requestedWorkerId) {
        requireText(tenantId, "tenantId is required");
        if (poolOwnerType == null) {
            throw new IllegalArgumentException("workerPoolOwnerType is required");
        }
        requireText(poolOwnerId, "workerPoolOwnerId is required");
        requireText(poolId, "workerPoolId is required");
        requireText(expectedWorkerBackend, "workerBackend is required");

        BizWorkerPoolEntity pool = bizWorkerPoolService.requireAvailablePool(
                tenantId.trim(), poolOwnerType, poolOwnerId.trim(), poolId.trim());
        if (!expectedWorkerBackend.trim().equals(pool.getWorkerBackend())) {
            throw new IllegalStateException("worker pool backend mismatch: " + poolId.trim());
        }
        List<BizWorkerPoolMemberEntity> enabledMembers = poolMemberRepository
                .findByPoolIdOrderByCreatedAtAsc(pool.getPoolId())
                .stream()
                .filter(item -> BizWorkerPoolService.STATUS_ENABLED.equals(item.getStatus()))
                .toList();
        if (StringUtils.hasText(requestedWorkerId)) {
            String normalizedWorkerId = requestedWorkerId.trim();
            return enabledMembers.stream()
                    .map(BizWorkerPoolMemberEntity::getWorkerId)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .filter(normalizedWorkerId::equals)
                    .findFirst()
                    .orElseThrow(() -> new SecurityException(
                            "physical worker is not an enabled pool member: " + normalizedWorkerId));
        }
        return enabledMembers.stream()
                .map(BizWorkerPoolMemberEntity::getWorkerId)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "worker pool has no enabled members: " + pool.getPoolId()));
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
