package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.SessionLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.SessionLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Foreground-lane reservation and offline pre-accept gate. The reservation is
 * durable before any provider effect. SHADOW records parity but never rejects
 * the legacy dispatch.
 */
@Service
public class LifecycleIngressGate {
    private final SessionLifecycleSnapshotRepository sessions;
    private final WorkerLifecycleSnapshotRepository workers;
    private final OfflineCommandGate offline = new OfflineCommandGate();

    public LifecycleIngressGate(
            SessionLifecycleSnapshotRepository sessions,
            WorkerLifecycleSnapshotRepository workers) {
        this.sessions = sessions;
        this.workers = workers;
    }

    @Transactional
    public IngressPermit reserveBeforeEffect(
            String sessionId, String physicalWorkerId) {
        if (sessionId == null || sessionId.isBlank()) {
            return IngressPermit.notTracked();
        }
        String reservationId = "reservation-" + UUID.randomUUID();
        SessionLifecycleSnapshotEntity session = sessions.findForUpdate(sessionId)
                .orElseGet(() -> newShadowSession(sessionId, physicalWorkerId));
        LifecycleOwnershipMode mode = LifecycleOwnershipMode.valueOf(
                session.getOwnershipMode());
        String occupant = session.getForegroundTaskId();
        if (occupant != null && !occupant.equals(reservationId)) {
            if (mode == LifecycleOwnershipMode.ENFORCED) {
                throw new IllegalStateException("SESSION_FOREGROUND_LANE_OCCUPIED");
            }
            return new IngressPermit(
                    sessionId, null, mode, false,
                    "SHADOW_WOULD_REJECT_FOREGROUND_LANE");
        }
        WorkerLifecycleSnapshotEntity worker = physicalWorkerId == null
                ? null : workers.findById(physicalWorkerId).orElse(null);
        LifecycleOperationalState operational = worker == null
                ? LifecycleOperationalReducer.reduce(Set.of())
                : LifecycleOperationalReducer.reduce(blockers(worker));
        OfflineCommandGate.OfflineGateDecision offlineDecision =
                offline.evaluate(operational, false, worker != null);
        if (mode == LifecycleOwnershipMode.ENFORCED
                && !offlineDecision.wouldAdmit()) {
            throw new IllegalStateException(offlineDecision.safeReasonCode());
        }
        session.setForegroundTaskId(reservationId);
        session.setForegroundLaneState("RESERVED");
        sessions.save(session);
        return new IngressPermit(
                sessionId,
                reservationId,
                mode,
                true,
                offlineDecision.wouldAdmit()
                        ? "FOREGROUND_LANE_RESERVED"
                        : "SHADOW_" + offlineDecision.safeReasonCode());
    }

    @Transactional
    public void confirm(IngressPermit permit, String taskId) {
        if (permit == null || !permit.tracked() || !permit.reserved()) return;
        SessionLifecycleSnapshotEntity session = sessions
                .findForUpdate(permit.sessionId()).orElseThrow();
        if (!permit.reservationId().equals(session.getForegroundTaskId())) {
            throw new IllegalStateException("SESSION_FOREGROUND_RESERVATION_LOST");
        }
        session.setForegroundTaskId(taskId);
        session.setForegroundLaneState("OCCUPIED");
        sessions.save(session);
    }

    @Transactional
    public void releaseFailed(IngressPermit permit) {
        if (permit == null || !permit.tracked() || !permit.reserved()) return;
        sessions.findForUpdate(permit.sessionId()).ifPresent(session -> {
            if (permit.reservationId().equals(session.getForegroundTaskId())) {
                session.setForegroundTaskId(null);
                session.setForegroundLaneState("FREE");
                sessions.save(session);
            }
        });
    }

    private Set<LifecycleBlocker> blockers(WorkerLifecycleSnapshotEntity worker) {
        java.util.EnumSet<LifecycleBlocker> blockers =
                java.util.EnumSet.noneOf(LifecycleBlocker.class);
        if (!LifecycleAvailability.READY.name().equals(worker.getAvailability())) {
            blockers.add(LifecycleBlocker.WORKER_OFFLINE);
        }
        if (!LifecycleConflictState.NONE.name().equals(worker.getConflictState())) {
            blockers.add(LifecycleBlocker.EVIDENCE_CONFLICT);
        }
        return blockers;
    }

    private SessionLifecycleSnapshotEntity newShadowSession(
            String sessionId, String physicalWorkerId) {
        SessionLifecycleSnapshotEntity entity = new SessionLifecycleSnapshotEntity();
        entity.setSessionId(sessionId);
        entity.setPhysicalWorkerId(physicalWorkerId);
        entity.setOwnershipMode(LifecycleOwnershipMode.SHADOW.name());
        entity.setCanonicalPhase("OPEN");
        entity.setForegroundLaneState("FREE");
        entity.setAvailability(LifecycleAvailability.READY.name());
        entity.setConflictState(LifecycleConflictState.NONE.name());
        return entity;
    }

    public record IngressPermit(
            String sessionId,
            String reservationId,
            LifecycleOwnershipMode ownershipMode,
            boolean reserved,
            String safeReasonCode) {
        static IngressPermit notTracked() {
            return new IngressPermit(
                    null, null, LifecycleOwnershipMode.SHADOW,
                    false, "SESSION_NOT_TRACKED");
        }

        public boolean tracked() {
            return sessionId != null;
        }
    }
}
