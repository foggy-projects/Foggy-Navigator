package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.TaskLifecycleProjectionPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class TaskLifecycleProjectionService implements TaskLifecycleProjectionPort {
    private final TaskLifecycleSnapshotRepository snapshots;
    private final SessionTaskRepository tasks;

    public TaskLifecycleProjectionService(
            TaskLifecycleSnapshotRepository snapshots,
            SessionTaskRepository tasks) {
        this.snapshots = snapshots;
        this.tasks = tasks;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskLifecycleProjection> find(String taskId) {
        var snapshot = snapshots.findById(taskId).orElse(null);
        var task = tasks.findByTaskId(taskId).orElse(null);
        if (snapshot == null || task == null) return Optional.empty();
        return Optional.of(new TaskLifecycleProjection(
                taskId,
                task.getStatus(),
                TaskCanonicalPhase.TERMINAL.name()
                        .equals(snapshot.getCanonicalPhase()),
                TaskCleanupState.COMPLETED.name()
                        .equals(snapshot.getCleanupState()),
                snapshot.getTerminalOutcome(),
                snapshot.getTerminalSource(),
                snapshot.getPhysicalWorkerId(),
                snapshot.getProviderTaskId()));
    }
}
