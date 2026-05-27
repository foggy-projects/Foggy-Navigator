package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BizWorkerIdentityRepository extends JpaRepository<BizWorkerIdentityEntity, Long> {

    Optional<BizWorkerIdentityEntity> findByWorkerId(String workerId);

    List<BizWorkerIdentityEntity> findByOwnerTypeAndOwnerIdAndWorkerBackendAndStatusAndHealthStatusOrderByUpdatedAtDesc(
            ResourceOwnerType ownerType,
            String ownerId,
            String workerBackend,
            String status,
            String healthStatus);
}
