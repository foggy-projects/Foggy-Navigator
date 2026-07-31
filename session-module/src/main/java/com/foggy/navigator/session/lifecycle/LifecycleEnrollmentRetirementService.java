package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofReferenceRepository;
import com.foggy.navigator.session.lifecycle.repository.SessionLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.LifecycleEnrollmentRetirementPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class LifecycleEnrollmentRetirementService
        implements LifecycleEnrollmentRetirementPort {
    private final LifecycleWriterProofReferenceRepository references;
    private final TaskLifecycleSnapshotRepository tasks;
    private final SessionLifecycleSnapshotRepository sessions;
    private final WorkerLifecycleSnapshotRepository workers;
    private final WriterExclusivityProofService writerProofs;

    public LifecycleEnrollmentRetirementService(
            LifecycleWriterProofReferenceRepository references,
            TaskLifecycleSnapshotRepository tasks,
            SessionLifecycleSnapshotRepository sessions,
            WorkerLifecycleSnapshotRepository workers,
            WriterExclusivityProofService writerProofs) {
        this.references = references;
        this.tasks = tasks;
        this.sessions = sessions;
        this.workers = workers;
        this.writerProofs = writerProofs;
    }

    @Override
    @Transactional
    public void taskCleanupCompleted(String taskId) {
        release(ProofAggregateType.TASK, taskId,
                "TASK_TERMINAL_CLEANUP_COMPLETED");
    }

    @Override
    @Transactional
    public void sessionClosed(String sessionId) {
        var session = sessions.findForUpdate(sessionId).orElse(null);
        if (session == null) return;
        if (!"FREE".equals(session.getForegroundLaneState())) {
            throw new IllegalStateException(
                    "LIFECYCLE_SESSION_FOREGROUND_LANE_OCCUPIED");
        }
        session.setCanonicalPhase("CLOSED");
        sessions.save(session);
        release(ProofAggregateType.SESSION, sessionId,
                "SESSION_CANONICALLY_CLOSED");
    }

    @Override
    @Transactional
    public void workerRetired(String physicalWorkerId) {
        var worker = workers.findForUpdate(physicalWorkerId).orElse(null);
        if (worker == null) return;
        boolean activeTask = tasks
                .findByPhysicalWorkerIdAndOwnershipMode(
                        physicalWorkerId, "ENFORCED").stream()
                .anyMatch(task -> !TaskCanonicalPhase.TERMINAL.name()
                        .equals(task.getCanonicalPhase())
                        || !TaskCleanupState.COMPLETED.name()
                        .equals(task.getCleanupState()));
        boolean activeSession = sessions
                .findByPhysicalWorkerIdAndOwnershipMode(
                        physicalWorkerId, "ENFORCED").stream()
                .anyMatch(session -> !"CLOSED".equals(
                        session.getCanonicalPhase())
                        || !"FREE".equals(
                        session.getForegroundLaneState()));
        if (activeTask || activeSession) {
            throw new IllegalStateException(
                    "LIFECYCLE_WORKER_RETIREMENT_BLOCKED");
        }
        worker.setOwnershipMode("SHADOW");
        worker.setWriterGenerationId(null);
        workers.save(worker);
        release(ProofAggregateType.WORKER, physicalWorkerId,
                "WORKER_CANONICALLY_RETIRED");
    }

    private void release(
            ProofAggregateType type, String aggregateId, String reason) {
        for (var reference : references
                .findByAggregateTypeAndAggregateIdAndReleasedAtIsNull(
                        type.name(), aggregateId)) {
            if (!writerProofs.releaseReference(
                    reference.getReferenceId(), reason,
                    LocalDateTime.now())) {
                throw new IllegalStateException(
                        "LIFECYCLE_PROOF_REFERENCE_RELEASE_BLOCKED");
            }
        }
    }
}
