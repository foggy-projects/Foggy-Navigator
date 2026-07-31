package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.SessionLifecycleSnapshotEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface SessionLifecycleSnapshotRepository
        extends JpaRepository<SessionLifecycleSnapshotEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SessionLifecycleSnapshotEntity s where s.sessionId = :sessionId")
    Optional<SessionLifecycleSnapshotEntity> findForUpdate(@Param("sessionId") String sessionId);

    List<SessionLifecycleSnapshotEntity>
    findByPhysicalWorkerIdAndOwnershipMode(
            String physicalWorkerId, String ownershipMode);
}
