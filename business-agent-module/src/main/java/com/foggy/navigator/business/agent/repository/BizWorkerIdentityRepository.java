package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BizWorkerIdentityRepository extends JpaRepository<BizWorkerIdentityEntity, Long> {

    Optional<BizWorkerIdentityEntity> findByWorkerId(String workerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select identity from BizWorkerIdentityEntity identity
             where identity.workerId = :workerId
               and identity.ownerType = :ownerType
               and identity.ownerId = :ownerId
            """)
    Optional<BizWorkerIdentityEntity> findByWorkerIdAndOwnerTypeAndOwnerIdForUpdate(
            @Param("workerId") String workerId,
            @Param("ownerType") ResourceOwnerType ownerType,
            @Param("ownerId") String ownerId);

    List<BizWorkerIdentityEntity> findByOwnerTypeAndOwnerIdAndWorkerBackendAndStatusAndHealthStatusOrderByUpdatedAtDesc(
            ResourceOwnerType ownerType,
            String ownerId,
            String workerBackend,
            String status,
            String healthStatus);
}
