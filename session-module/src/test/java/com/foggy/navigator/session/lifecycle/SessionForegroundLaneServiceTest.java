package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.SessionLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.SessionLifecycleSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionForegroundLaneServiceTest {

    @Test
    void secondForegroundTaskCannotBypassOccupiedLane() {
        SessionLifecycleSnapshotEntity snapshot = new SessionLifecycleSnapshotEntity();
        snapshot.setSessionId("session-fixture");
        snapshot.setForegroundTaskId("task-first");
        snapshot.setForegroundLaneState("OCCUPIED");
        SessionLifecycleSnapshotRepository repository =
                mock(SessionLifecycleSnapshotRepository.class);
        when(repository.findForUpdate("session-fixture")).thenReturn(Optional.of(snapshot));

        SessionLaneDecision decision = new SessionForegroundLaneService(repository)
                .observeReservation("session-fixture", "task-second", "worker-fixture");

        assertThat(decision.admitted()).isFalse();
        assertThat(decision.foregroundTaskId()).isEqualTo("task-first");
        assertThat(decision.ownerEffectSuppressed()).isTrue();
    }

    @Test
    void cleanupPendingKeepsLaneOccupied() {
        LifecycleOperationalState offline = new LifecycleOperationalState(
                LifecycleAvailability.OFFLINE_FROZEN,
                LifecycleConflictState.NONE,
                Set.of(LifecycleBlocker.WORKER_OFFLINE));
        OfflineCommandGate.OfflineGateDecision decision =
                new OfflineCommandGate().evaluate(offline, false, true);
        assertThat(decision.wouldAdmit()).isFalse();
        assertThat(decision.ambiguous()).isFalse();
        assertThat(decision.ownerEffectSuppressed()).isTrue();
    }
}
