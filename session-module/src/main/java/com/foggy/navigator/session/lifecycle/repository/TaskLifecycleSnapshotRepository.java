package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;

public interface TaskLifecycleSnapshotRepository
        extends JpaRepository<TaskLifecycleSnapshotEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select snapshot from TaskLifecycleSnapshotEntity snapshot "
            + "where snapshot.taskId = :taskId")
    Optional<TaskLifecycleSnapshotEntity> findForUpdate(@Param("taskId") String taskId);

    List<TaskLifecycleSnapshotEntity>
    findByPhysicalWorkerIdAndOwnershipMode(
            String physicalWorkerId, String ownershipMode);
}
