package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BizWorkerPoolRepository extends JpaRepository<BizWorkerPoolEntity, Long> {

    Optional<BizWorkerPoolEntity> findByPoolId(String poolId);

    Optional<BizWorkerPoolEntity> findByPoolIdAndTenantId(String poolId, String tenantId);

    List<BizWorkerPoolEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
