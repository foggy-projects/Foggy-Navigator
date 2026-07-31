package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalTombstoneRepository;
import com.foggy.navigator.spi.lifecycle.LifecycleEnrollmentRetirementPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TerminalCleanupFinalizer {
    private final TaskLifecycleSnapshotRepository snapshots;
    private final TaskTerminalTombstoneRepository tombstones;
    private final SessionForegroundLaneService lanes;
    private final LifecycleEnrollmentRetirementPort retirement;

    public TerminalCleanupFinalizer(
            TaskLifecycleSnapshotRepository snapshots,
            TaskTerminalTombstoneRepository tombstones,
            SessionForegroundLaneService lanes,
            LifecycleEnrollmentRetirementPort retirement) {
        this.snapshots = snapshots;
        this.tombstones = tombstones;
        this.lanes = lanes;
        this.retirement = retirement;
    }

    /*
     * Cleanup is commonly resumed from a transaction afterCommit callback. At
     * that point Spring still has the old transaction resources bound even
     * though they can no longer commit more work. A new transaction is
     * therefore required for the final checkpoint and lane release.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String taskId) {
        var tombstone = tombstones.findById(taskId).orElseThrow();
        var snapshot = snapshots.findForUpdate(taskId).orElseThrow();
        snapshot.setCleanupState(TaskCleanupState.COMPLETED.name());
        snapshots.save(snapshot);
        lanes.observeReleaseAfterCleanup(tombstone.getSessionId(), taskId);
        retirement.taskCleanupCompleted(taskId);
    }
}
