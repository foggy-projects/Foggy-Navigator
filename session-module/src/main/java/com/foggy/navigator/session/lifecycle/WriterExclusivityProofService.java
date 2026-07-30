package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofReferenceEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofReferenceRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class WriterExclusivityProofService {

    private final LifecycleWriterProofRepository proofs;
    private final LifecycleWriterProofReferenceRepository references;
    private final LifecycleEffectOutboxRepository outbox;

    public WriterExclusivityProofService(
            LifecycleWriterProofRepository proofs,
            LifecycleWriterProofReferenceRepository references,
            LifecycleEffectOutboxRepository outbox) {
        this.proofs = proofs;
        this.references = references;
        this.outbox = outbox;
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
            String referenceId, boolean releasePredicateSatisfied,
            String reason, LocalDateTime now) {
        if (!releasePredicateSatisfied) return false;
        LifecycleWriterProofReferenceEntity reference =
                references.findById(referenceId).orElse(null);
        if (reference == null || reference.getReleasedAt() != null) return false;
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
            String effectId, String proofId, LocalDateTime now) {
        LifecycleEffectOutboxEntity effect = outbox.findForUpdate(effectId)
                .orElseThrow(() -> new IllegalStateException("LIFECYCLE_EFFECT_NOT_FOUND"));
        if ("EFFECT_STARTED".equals(effect.getEffectState())) {
            return new EffectAuthorization(false, true, "EFFECT_ALREADY_STARTED");
        }
        LifecycleWriterProofEntity proof = activeProof(proofId, now);
        effect.setProofId(proofId);
        effect.setEffectAuthorizationProofVersion(Long.toString(proof.getProofVersion()));
        effect.setAuthorizedAt(now);
        effect.setEffectState("EFFECT_STARTED");
        outbox.save(effect);
        return new EffectAuthorization(true, false, "EFFECT_AUTHORIZED");
    }

    @Transactional
    public void quarantine(String proofId) {
        LifecycleWriterProofEntity proof = proofs.findForUpdate(proofId)
                .orElseThrow(() -> new IllegalStateException("LIFECYCLE_PROOF_NOT_FOUND"));
        proof.setStatus("QUARANTINED");
        proofs.save(proof);
    }

    public boolean mayReleaseProof(String proofId) {
        return references.countByProofIdAndReleasedAtIsNull(proofId) == 0
                && outbox.countByEffectStateNot("COMPLETED") == 0;
    }

    private LifecycleWriterProofEntity activeProof(String proofId, LocalDateTime now) {
        LifecycleWriterProofEntity proof = proofs.findForUpdate(proofId)
                .orElseThrow(() -> new IllegalStateException("LIFECYCLE_PROOF_NOT_FOUND"));
        if (!"ACTIVE".equals(proof.getStatus())
                || !proof.getExpiresAt().isAfter(now)) {
            throw new IllegalStateException("LIFECYCLE_WRITER_EXCLUSIVITY_LOST");
        }
        return proof;
    }

    public record EffectAuthorization(
            boolean providerCallAuthorized,
            boolean alreadyStarted,
            String safeReasonCode) {
    }
}
