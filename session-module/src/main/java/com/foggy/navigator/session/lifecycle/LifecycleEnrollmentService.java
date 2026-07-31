package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import com.foggy.navigator.session.lifecycle.persistence.SessionLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofRepository;
import com.foggy.navigator.session.lifecycle.repository.SessionLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The production enrollment transaction.  The activation gate remains closed:
 * while activation evidence is absent, only a repo-owned ephemeral fixture may
 * cross from a discovered SHADOW aggregate to ENFORCED.
 */
@Service
public class LifecycleEnrollmentService {
    private final WorkerLifecycleSnapshotRepository workers;
    private final SessionLifecycleSnapshotRepository sessions;
    private final TaskLifecycleSnapshotRepository tasks;
    private final LifecycleWriterProofRepository proofs;
    private final WriterExclusivityProofService writerProofs;
    private final TaskLifecycleOwnerService owner;
    private final LifecycleEnrollmentGate gate = new LifecycleEnrollmentGate();

    public LifecycleEnrollmentService(
            WorkerLifecycleSnapshotRepository workers,
            SessionLifecycleSnapshotRepository sessions,
            TaskLifecycleSnapshotRepository tasks,
            LifecycleWriterProofRepository proofs,
            WriterExclusivityProofService writerProofs,
            TaskLifecycleOwnerService owner) {
        this.workers = workers;
        this.sessions = sessions;
        this.tasks = tasks;
        this.proofs = proofs;
        this.writerProofs = writerProofs;
        this.owner = owner;
    }

    @Transactional
    public LifecycleEnrollmentGate.EnrollmentDecision enroll(
            EnrollmentCommand command) {
        LifecycleEnrollmentGate.EnrollmentDecision decision =
                gate.evaluate(command.request());
        if (!decision.enrolled()) return decision;

        WorkerLifecycleIdentity identity = command.identity();
        var worker = workers.findForUpdate(identity.physicalWorkerId())
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_SHADOW_DISCOVERY_REQUIRED"));
        if (!LifecycleOwnershipMode.SHADOW.name().equals(
                worker.getOwnershipMode())
                || !identity.stateGeneration().equals(
                worker.getStateGeneration())
                || !identity.instanceEpoch().equals(
                worker.getInstanceEpoch())
                || !LifecycleAvailability.READY.name().equals(
                worker.getAvailability())
                || !LifecycleConflictState.NONE.name().equals(
                worker.getConflictState())) {
            throw new IllegalStateException(
                    "LIFECYCLE_ENROLLMENT_WORKER_FENCE_MISMATCH");
        }
        LifecycleWriterProofEntity proof = proofs.findForUpdate(
                        command.proofId())
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_PROOF_NOT_FOUND"));
        LocalDateTime now = command.request().evaluationTime();
        if (!"ACTIVE".equals(proof.getStatus())
                || !proof.getExpiresAt().isAfter(now)
                || !proof.getGenerationId().equals(
                command.writerGenerationId())) {
            throw new IllegalStateException(
                    "LIFECYCLE_WRITER_EXCLUSIVITY_LOST");
        }

        writerProofs.acquireReference(
                proof.getProofId(), ProofAggregateType.WORKER,
                identity.physicalWorkerId(), now);
        writerProofs.acquireReference(
                proof.getProofId(), ProofAggregateType.SESSION,
                command.sessionId(), now);
        writerProofs.acquireReference(
                proof.getProofId(), ProofAggregateType.TASK,
                command.task().navigatorTaskId(), now);

        worker.setOwnershipMode(LifecycleOwnershipMode.ENFORCED.name());
        worker.setWriterGenerationId(command.writerGenerationId());
        workers.save(worker);

        SessionLifecycleSnapshotEntity session = sessions
                .findForUpdate(command.sessionId())
                .orElseGet(SessionLifecycleSnapshotEntity::new);
        if (session.getSessionId() == null) {
            session.setSessionId(command.sessionId());
            session.setCanonicalPhase("OPEN");
            session.setForegroundLaneState("FREE");
        }
        session.setPhysicalWorkerId(identity.physicalWorkerId());
        session.setOwnershipMode(LifecycleOwnershipMode.ENFORCED.name());
        session.setAvailability(LifecycleAvailability.READY.name());
        session.setConflictState(LifecycleConflictState.NONE.name());
        session.setWriterGenerationId(command.writerGenerationId());
        sessions.save(session);

        WorkerLifecycleTask enforcedTask = new WorkerLifecycleTask(
                command.task().navigatorTaskId(),
                command.task().providerTaskId(),
                LifecycleOwnershipMode.ENFORCED,
                command.task().initialDispatchId(),
                command.task().safeBindingDigestVersion(),
                command.task().safeBindingDigest(),
                command.task().lifecycleState(),
                command.task().lastSequence());
        owner.enrollInventoryTask(
                identity, enforcedTask, command.writerGenerationId());
        var task = tasks.findForUpdate(
                        enforcedTask.navigatorTaskId()).orElseThrow();
        task.setOwnershipMode(LifecycleOwnershipMode.ENFORCED.name());
        task.setWriterGenerationId(command.writerGenerationId());
        tasks.save(task);
        return decision;
    }

    public record EnrollmentCommand(
            LifecycleEnrollmentGate.EnrollmentRequest request,
            WorkerLifecycleIdentity identity,
            String sessionId,
            WorkerLifecycleTask task,
            String proofId,
            String writerGenerationId) {
    }
}
