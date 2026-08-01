package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofReferenceEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofReferenceRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.SessionLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.Set;

@Service
public class WriterExclusivityProofService {

    private final LifecycleWriterProofRepository proofs;
    private final LifecycleWriterProofReferenceRepository references;
    private final LifecycleEffectOutboxRepository outbox;
    private final TaskLifecycleSnapshotRepository taskSnapshots;
    private final SessionLifecycleSnapshotRepository sessionSnapshots;
    private final WorkerLifecycleSnapshotRepository workerSnapshots;
    private final TransactionTemplate transactions;

    public WriterExclusivityProofService(
            LifecycleWriterProofRepository proofs,
            LifecycleWriterProofReferenceRepository references,
            LifecycleEffectOutboxRepository outbox,
            TaskLifecycleSnapshotRepository taskSnapshots,
            SessionLifecycleSnapshotRepository sessionSnapshots,
            WorkerLifecycleSnapshotRepository workerSnapshots,
            PlatformTransactionManager transactionManager) {
        this.proofs = proofs;
        this.references = references;
        this.outbox = outbox;
        this.taskSnapshots = taskSnapshots;
        this.sessionSnapshots = sessionSnapshots;
        this.workerSnapshots = workerSnapshots;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public String acquireReference(
            String proofId, ProofAggregateType type, String aggregateId,
            LocalDateTime now) {
        LifecycleWriterProofEntity proof = activeProof(proofId, now);
        String referenceId = proofId + ":" + type + ":" + aggregateId;
        if (!references.existsById(referenceId)) {
            LifecycleWriterProofReferenceEntity reference =
                    new LifecycleWriterProofReferenceEntity();
            reference.setReferenceId(referenceId);
            reference.setProofId(proofId);
            reference.setAggregateType(type.name());
            reference.setAggregateId(aggregateId);
            reference.setAcquiredAt(now);
            references.save(reference);
        }
        return referenceId;
    }

    @Transactional
    public boolean releaseReference(
            String referenceId, String reason, LocalDateTime now) {
        LifecycleWriterProofReferenceEntity reference =
                references.findForUpdate(referenceId).orElse(null);
        if (reference == null || reference.getReleasedAt() != null) return false;
        if (!aggregateReleaseSatisfied(reference)
                || outbox.countUnfinishedByReferenceId(referenceId) != 0) {
            return false;
        }
        reference.setReleasedAt(now);
        reference.setReleaseReason(reason);
        references.save(reference);
        return true;
    }

    /**
     * Proof check and EFFECT_STARTED transition share one transaction and locked
     * rows. A prior claim is deliberately not provider-call authorization.
     */
    @Transactional
    public EffectAuthorization authorizeEffect(
            EffectAuthorizationCommand command, LocalDateTime now) {
        // Global lock order: proof -> exact aggregate reference -> outbox.
        LifecycleWriterProofEntity proof = proofs.findForUpdate(command.proofId())
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_PROOF_NOT_FOUND"));
        if (!proof.getGenerationId().equals(command.writerGenerationId())
                || !proof.getControllerInventoryDigest()
                .equals(command.controllerInventoryDigest())) {
            throw new IllegalStateException("LIFECYCLE_PROOF_BINDING_MISMATCH");
        }
        lockRequiredReference(
                command.workerReferenceId(), command.proofId(),
                ProofAggregateType.WORKER, command.physicalWorkerId());
        lockRequiredReference(
                command.sessionReferenceId(), command.proofId(),
                ProofAggregateType.SESSION, command.sessionId());
        LifecycleWriterProofReferenceEntity reference = lockRequiredReference(
                command.aggregateReferenceId(), command.proofId(),
                command.aggregateType(), command.aggregateId());
        if (reference.getReleasedAt() != null
                || !reference.getProofId().equals(command.proofId())
                || !reference.getAggregateType().equals(command.aggregateType().name())
                || !reference.getAggregateId().equals(command.aggregateId())) {
            throw new IllegalStateException(
                    "LIFECYCLE_AGGREGATE_REFERENCE_MISMATCH");
        }
        LifecycleEffectOutboxEntity effect = outbox.findForUpdate(command.effectId())
                .orElseThrow(() -> new IllegalStateException("LIFECYCLE_EFFECT_NOT_FOUND"));
        if ("EFFECT_STARTED".equals(effect.getEffectState())
                || "RESULT_OBSERVED".equals(effect.getEffectState())
                || "COMPLETED".equals(effect.getEffectState())) {
            return new EffectAuthorization(false, true, "EFFECT_ALREADY_STARTED");
        }
        if (!active(proof, now)) {
            beginQuarantineLocked(proof);
            return new EffectAuthorization(
                    false, false, "LIFECYCLE_WRITER_EXCLUSIVITY_LOST");
        }
        if (!Set.of("PREPARED", "CLAIMED").contains(effect.getEffectState())
                || !"EXTERNAL_PROVIDER_ONCE".equals(effect.getEffectClass())
                || !command.aggregateType().name().equals(effect.getAggregateType())
                || !command.aggregateId().equals(effect.getAggregateId())
                || !command.aggregateReferenceId()
                .equals(effect.getAggregateReferenceId())
                || !command.writerGenerationId()
                .equals(effect.getWriterGenerationId())
                || !command.controllerInventoryDigest()
                .equals(effect.getControllerInventoryDigest())
                || !command.effectClaim().equals(effect.getEffectClaim())) {
            throw new IllegalStateException(
                    "LIFECYCLE_EFFECT_AUTHORIZATION_CLAIM_MISMATCH");
        }
        effect.setEffectState("CLAIMED");
        effect.setProofId(command.proofId());
        effect.setEffectAuthorizationProofVersion(Long.toString(proof.getProofVersion()));
        effect.setAuthorizedAt(now);
        effect.setEffectState("EFFECT_STARTED");
        outbox.save(effect);
        return new EffectAuthorization(true, false, "EFFECT_AUTHORIZED");
    }

    public void quarantine(String proofId) {
        transactions.executeWithoutResult(status -> {
            LifecycleWriterProofEntity proof = proofs.findForUpdate(proofId)
                    .orElseThrow(() -> new IllegalStateException(
                            "LIFECYCLE_PROOF_NOT_FOUND"));
            beginQuarantineLocked(proof);
        });
        while (Boolean.TRUE.equals(transactions.execute(status ->
                quarantineBatch(proofId)))) {
            // Each iteration is a separate, bounded transaction. The durable
            // cursor makes interruption and restart safe.
        }
    }

    @Scheduled(fixedDelayString =
            "${navigator.lifecycle.proof-quarantine-recovery-ms:5000}")
    public void resumeQuarantines() {
        for (LifecycleWriterProofEntity proof :
                proofs.findTop10ByStatusOrderByProofIdAsc("QUARANTINING")) {
            transactions.execute(status ->
                    quarantineBatch(proof.getProofId()));
        }
    }

    public boolean mayReleaseProof(String proofId) {
        return references.countByProofIdAndReleasedAtIsNull(proofId) == 0
                && outbox.countUnfinishedByProofId(proofId) == 0;
    }

    private LifecycleWriterProofEntity activeProof(String proofId, LocalDateTime now) {
        LifecycleWriterProofEntity proof = proofs.findForUpdate(proofId)
                .orElseThrow(() -> new IllegalStateException("LIFECYCLE_PROOF_NOT_FOUND"));
        requireActive(proof, now);
        return proof;
    }

    private void requireActive(
            LifecycleWriterProofEntity proof, LocalDateTime now) {
        if (!active(proof, now)) {
            throw new IllegalStateException("LIFECYCLE_WRITER_EXCLUSIVITY_LOST");
        }
    }

    private boolean active(
            LifecycleWriterProofEntity proof, LocalDateTime now) {
        return "ACTIVE".equals(proof.getStatus())
                && proof.getExpiresAt().isAfter(now);
    }

    private LifecycleWriterProofReferenceEntity lockRequiredReference(
            String referenceId,
            String proofId,
            ProofAggregateType type,
            String aggregateId) {
        if (referenceId == null) {
            if (type == ProofAggregateType.TASK) {
                throw new IllegalStateException(
                        "LIFECYCLE_AGGREGATE_REFERENCE_NOT_FOUND");
            }
            return null;
        }
        LifecycleWriterProofReferenceEntity reference = references
                .findForUpdate(referenceId)
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_AGGREGATE_REFERENCE_NOT_FOUND"));
        if (reference.getReleasedAt() != null
                || !proofId.equals(reference.getProofId())
                || !type.name().equals(reference.getAggregateType())
                || !aggregateId.equals(reference.getAggregateId())) {
            throw new IllegalStateException(
                    "LIFECYCLE_AGGREGATE_REFERENCE_MISMATCH");
        }
        return reference;
    }

    private void beginQuarantineLocked(LifecycleWriterProofEntity proof) {
        if ("QUARANTINED".equals(proof.getStatus())) return;
        proof.setStatus("QUARANTINING");
        proofs.save(proof);
    }

    private boolean quarantineBatch(String proofId) {
        LifecycleWriterProofEntity proof = proofs.findForUpdate(proofId)
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_PROOF_NOT_FOUND"));
        if ("QUARANTINED".equals(proof.getStatus())) return false;
        if (!"QUARANTINING".equals(proof.getStatus())) {
            beginQuarantineLocked(proof);
        }
        var batch = references.findQuarantineBatch(
                proofId, proof.getQuarantineCursor(),
                PageRequest.of(0, 50));
        for (LifecycleWriterProofReferenceEntity reference : batch) {
            switch (ProofAggregateType.valueOf(reference.getAggregateType())) {
                case WORKER -> workerSnapshots.findById(
                        reference.getAggregateId()).ifPresent(snapshot -> {
                    snapshot.setAvailability(
                            LifecycleAvailability.AUTHORITY_QUARANTINED.name());
                    snapshot.setConflictState(
                            LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST.name());
                    workerSnapshots.save(snapshot);
                });
                case SESSION -> sessionSnapshots.findById(
                        reference.getAggregateId()).ifPresent(snapshot -> {
                    snapshot.setAvailability(
                            LifecycleAvailability.AUTHORITY_QUARANTINED.name());
                    snapshot.setConflictState(
                            LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST.name());
                    sessionSnapshots.save(snapshot);
                });
                case TASK -> taskSnapshots.findById(
                        reference.getAggregateId()).ifPresent(snapshot -> {
                    snapshot.setAvailability(
                            LifecycleAvailability.AUTHORITY_QUARANTINED.name());
                    snapshot.setConflictState(
                            LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST.name());
                    taskSnapshots.save(snapshot);
                });
            }
        }
        if (!batch.isEmpty()) {
            proof.setQuarantineCursor(
                    batch.get(batch.size() - 1).getReferenceId());
        }
        if (batch.size() < 50) {
            proof.setStatus("QUARANTINED");
        }
        proofs.save(proof);
        return "QUARANTINING".equals(proof.getStatus());
    }

    public record EffectAuthorization(
            boolean providerCallAuthorized,
            boolean alreadyStarted,
            String safeReasonCode) {
    }

    public record EffectAuthorizationCommand(
            String effectId,
            String proofId,
            String aggregateReferenceId,
            ProofAggregateType aggregateType,
            String aggregateId,
            String writerGenerationId,
            String controllerInventoryDigest,
            String effectClaim,
            String workerReferenceId,
            String physicalWorkerId,
            String sessionReferenceId,
            String sessionId) {
        public EffectAuthorizationCommand(
                String effectId,
                String proofId,
                String aggregateReferenceId,
                ProofAggregateType aggregateType,
                String aggregateId,
                String writerGenerationId,
                String controllerInventoryDigest,
                String effectClaim) {
            this(effectId, proofId, aggregateReferenceId, aggregateType,
                    aggregateId, writerGenerationId,
                    controllerInventoryDigest, effectClaim,
                    null, null, null, null);
        }
    }

    private boolean aggregateReleaseSatisfied(
            LifecycleWriterProofReferenceEntity reference) {
        return switch (ProofAggregateType.valueOf(reference.getAggregateType())) {
            case TASK -> taskSnapshots.findById(reference.getAggregateId())
                    .map(task -> TaskCanonicalPhase.TERMINAL.name()
                            .equals(task.getCanonicalPhase())
                            && TaskCleanupState.COMPLETED.name()
                            .equals(task.getCleanupState()))
                    .orElse(false);
            case SESSION -> sessionSnapshots.findById(reference.getAggregateId())
                    .map(session -> "CLOSED".equals(session.getCanonicalPhase())
                            && "FREE".equals(session.getForegroundLaneState()))
                    .orElse(false);
            case WORKER -> workerSnapshots.findById(
                            reference.getAggregateId())
                    .map(worker -> "SHADOW".equals(
                            worker.getOwnershipMode())
                            && worker.getWriterGenerationId() == null)
                    .orElse(false);
        };
    }
}
