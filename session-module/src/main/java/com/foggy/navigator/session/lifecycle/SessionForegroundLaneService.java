package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.SessionLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.SessionLifecycleSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionForegroundLaneService {

    private final SessionLifecycleSnapshotRepository snapshots;

    public SessionForegroundLaneService(SessionLifecycleSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    /**
     * SHADOW reservation. It records the lane outcome but never rejects or sends
     * the legacy command itself.
     */
    @Transactional
    public SessionLaneDecision observeReservation(
            String sessionId, String taskId, String physicalWorkerId) {
        SessionLifecycleSnapshotEntity snapshot = snapshots.findForUpdate(sessionId)
                .orElseGet(() -> newSnapshot(sessionId, physicalWorkerId));
        String occupant = snapshot.getForegroundTaskId();
        if (occupant != null && !occupant.equals(taskId)) {
            return new SessionLaneDecision(
                    false, occupant, "SESSION_FOREGROUND_LANE_OCCUPIED", true);
        }
        snapshot.setForegroundTaskId(taskId);
        snapshot.setForegroundLaneState("OCCUPIED");
        snapshots.save(snapshot);
        return new SessionLaneDecision(true, taskId, "SHADOW_LANE_RESERVED", true);
    }

    @Transactional
    public boolean observeReleaseAfterCleanup(String sessionId, String taskId) {
        SessionLifecycleSnapshotEntity snapshot = snapshots.findForUpdate(sessionId)
                .orElse(null);
        if (snapshot == null || !taskId.equals(snapshot.getForegroundTaskId())) return false;
        snapshot.setForegroundTaskId(null);
        snapshot.setForegroundLaneState("AVAILABLE");
        snapshots.save(snapshot);
        return true;
    }

    private SessionLifecycleSnapshotEntity newSnapshot(
            String sessionId, String physicalWorkerId) {
        SessionLifecycleSnapshotEntity entity = new SessionLifecycleSnapshotEntity();
        entity.setSessionId(sessionId);
        entity.setPhysicalWorkerId(physicalWorkerId);
        entity.setOwnershipMode("SHADOW");
        entity.setCanonicalPhase("OPEN");
        entity.setForegroundLaneState("AVAILABLE");
        entity.setAvailability(LifecycleAvailability.READY.name());
        entity.setConflictState(LifecycleConflictState.NONE.name());
        return entity;
    }
}
