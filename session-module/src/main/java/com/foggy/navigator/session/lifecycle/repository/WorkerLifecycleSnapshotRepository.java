package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerLifecycleSnapshotRepository
        extends JpaRepository<WorkerLifecycleSnapshotEntity, String> {
}
