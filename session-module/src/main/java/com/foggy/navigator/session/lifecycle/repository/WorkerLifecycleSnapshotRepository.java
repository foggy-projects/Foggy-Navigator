package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface WorkerLifecycleSnapshotRepository
        extends JpaRepository<WorkerLifecycleSnapshotEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkerLifecycleSnapshotEntity w "
            + "where w.physicalWorkerId = :physicalWorkerId")
    Optional<WorkerLifecycleSnapshotEntity> findForUpdate(
            String physicalWorkerId);
}
