package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSentinelLeaseEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface WorkerLifecycleSentinelLeaseRepository
        extends JpaRepository<WorkerLifecycleSentinelLeaseEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from WorkerLifecycleSentinelLeaseEntity l "
            + "where l.physicalWorkerId = :physicalWorkerId")
    Optional<WorkerLifecycleSentinelLeaseEntity> findForUpdate(String physicalWorkerId);
}
