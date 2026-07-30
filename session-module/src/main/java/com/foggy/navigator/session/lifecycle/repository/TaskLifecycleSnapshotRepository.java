package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskLifecycleSnapshotRepository
        extends JpaRepository<TaskLifecycleSnapshotEntity, String> {
}
