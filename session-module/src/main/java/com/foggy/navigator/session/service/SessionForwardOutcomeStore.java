package com.foggy.navigator.session.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionRelationEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Insert-only/read-exact persistence seam for canonical new-session forward outcomes.
 *
 * <p>The deterministic target Session is the relation identity. Fresh writes serialize on that
 * D0-created Session row; replay never updates, deletes, repairs or materializes source data.</p>
 */
@Service
final class SessionForwardOutcomeStore {

    private static final String RELATION_TYPE = "FORWARD";
    private static final String TARGET_MODE = "NEW_SESSION";
    private static final String CONFLICT_CODE = "FORWARD_OUTCOME_CONFLICT";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    SessionForwardOutcomeStore(
            PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.writes = requiresNew(transactionManager, false);
        this.reads = requiresNew(transactionManager, true);
    }

    OutcomeSnapshot insertFresh(OutcomeSpec requested) {
        OutcomeSpec spec = Objects.requireNonNull(requested, "outcome spec must not be null");
        OutcomeSnapshot result = writes.execute(status -> {
            SessionEntity target = entityManager.find(
                    SessionEntity.class,
                    spec.targetSessionId(),
                    LockModeType.PESSIMISTIC_WRITE);
            requireExactTarget(target, spec);
            if (!findByTarget(spec.targetSessionId(), LockModeType.PESSIMISTIC_WRITE).isEmpty()) {
                throw conflict();
            }

            SessionRelationEntity relation = newRelation(spec);
            entityManager.persist(relation);
            entityManager.flush();
            entityManager.refresh(relation);
            return snapshot(relation);
        });
        return Objects.requireNonNull(result, "outcome write returned no result");
    }

    OutcomeSnapshot requireExactReplay(OutcomeSpec requested) {
        OutcomeSpec spec = Objects.requireNonNull(requested, "outcome spec must not be null");
        OutcomeSnapshot result = reads.execute(status -> {
            List<SessionRelationEntity> candidates =
                    findByTarget(spec.targetSessionId(), null);
            if (candidates.size() != 1) {
                throw conflict();
            }
            SessionRelationEntity relation = candidates.get(0);
            requireExact(relation, spec);
            return snapshot(relation);
        });
        return Objects.requireNonNull(result, "outcome read returned no result");
    }

    private List<SessionRelationEntity> findByTarget(
            String targetSessionId,
            LockModeType lockMode) {
        TypedQuery<SessionRelationEntity> query = entityManager.createQuery(
                "select relation from SessionRelationEntity relation "
                        + "where relation.relationType = :relationType "
                        + "and relation.targetMode = :targetMode "
                        + "and relation.targetSessionId = :targetSessionId "
                        + "order by relation.id asc",
                SessionRelationEntity.class);
        query.setParameter("relationType", RELATION_TYPE);
        query.setParameter("targetMode", TARGET_MODE);
        query.setParameter("targetSessionId", targetSessionId);
        query.setMaxResults(2);
        if (lockMode != null) {
            query.setLockMode(lockMode);
        }
        return query.getResultList();
    }

    private static void requireExactTarget(SessionEntity target, OutcomeSpec spec) {
        if (target == null
                || target.getDeletedAt() != null
                || "DELETED".equals(target.getStatus())
                || !Objects.equals(target.getUserId(), spec.ownerUserId())
                || !Objects.equals(normalizeOptional(target.getTenantId()), spec.tenantId())
                || !Objects.equals(target.getCurrentDirectoryId(), spec.targetDirectoryId())
                || !Objects.equals(target.getMilestoneId(), spec.targetMilestoneId())) {
            throw conflict();
        }
    }

    private static SessionRelationEntity newRelation(OutcomeSpec spec) {
        SessionRelationEntity relation = new SessionRelationEntity();
        relation.setUserId(spec.ownerUserId());
        relation.setTenantId(spec.tenantId());
        relation.setRelationType(RELATION_TYPE);
        relation.setTargetMode(TARGET_MODE);
        relation.setSourceSessionId(spec.sourceSessionId());
        relation.setSourceMessageId(spec.sourceReferenceId());
        relation.setTargetSessionId(spec.targetSessionId());
        relation.setSourceWorkerId(spec.sourceWorkerId());
        relation.setSourceDirectoryId(spec.sourceDirectoryId());
        relation.setSourceMilestoneId(spec.sourceMilestoneId());
        relation.setTargetWorkerId(spec.targetWorkerId());
        relation.setTargetDirectoryId(spec.targetDirectoryId());
        relation.setTargetMilestoneId(spec.targetMilestoneId());
        relation.setTargetProviderType(spec.targetProviderType());
        relation.setTargetModelConfigId(spec.targetModelConfigId());
        relation.setMetadataJson(spec.metadataJson());
        return relation;
    }

    private static void requireExact(SessionRelationEntity actual, OutcomeSpec expected) {
        boolean exact = Objects.equals(actual.getUserId(), expected.ownerUserId())
                && Objects.equals(normalizeOptional(actual.getTenantId()), expected.tenantId())
                && Objects.equals(actual.getRelationType(), RELATION_TYPE)
                && Objects.equals(actual.getTargetMode(), TARGET_MODE)
                && Objects.equals(actual.getSourceSessionId(), expected.sourceSessionId())
                && Objects.equals(actual.getSourceMessageId(), expected.sourceReferenceId())
                && Objects.equals(actual.getTargetSessionId(), expected.targetSessionId())
                && Objects.equals(actual.getSourceWorkerId(), expected.sourceWorkerId())
                && Objects.equals(actual.getSourceDirectoryId(), expected.sourceDirectoryId())
                && Objects.equals(actual.getSourceMilestoneId(), expected.sourceMilestoneId())
                && Objects.equals(actual.getTargetWorkerId(), expected.targetWorkerId())
                && Objects.equals(actual.getTargetDirectoryId(), expected.targetDirectoryId())
                && Objects.equals(actual.getTargetMilestoneId(), expected.targetMilestoneId())
                && Objects.equals(actual.getTargetProviderType(), expected.targetProviderType())
                && Objects.equals(actual.getTargetModelConfigId(), expected.targetModelConfigId())
                && Objects.equals(actual.getMetadataJson(), expected.metadataJson());
        if (!exact) {
            throw conflict();
        }
    }

    private static OutcomeSnapshot snapshot(SessionRelationEntity relation) {
        Long relationId = Objects.requireNonNull(
                relation.getId(), "forward outcome relation ID is missing");
        LocalDateTime createdAt = Objects.requireNonNull(
                relation.getCreatedAt(), "forward outcome creation time is missing");
        return new OutcomeSnapshot(relationId, OutcomeSpec.from(relation), createdAt);
    }

    private static TransactionTemplate requiresNew(
            PlatformTransactionManager transactionManager,
            boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(readOnly);
        return template;
    }

    private static ForwardOutcomeConflictException conflict() {
        return new ForwardOutcomeConflictException(CONFLICT_CODE);
    }

    record OutcomeSpec(
            String ownerUserId,
            String tenantId,
            String sourceSessionId,
            String sourceReferenceId,
            String sourceWorkerId,
            String sourceDirectoryId,
            String sourceMilestoneId,
            String targetSessionId,
            String targetWorkerId,
            String targetDirectoryId,
            String targetMilestoneId,
            String targetProviderType,
            String targetModelConfigId,
            String metadataJson) {

        OutcomeSpec {
            ownerUserId = requireReference(ownerUserId, 64, "ownerUserId");
            tenantId = normalizeReference(tenantId, 64, "tenantId");
            sourceSessionId = requireReference(
                    sourceSessionId, 64, "sourceSessionId");
            sourceReferenceId = requireReference(
                    sourceReferenceId, 64, "sourceReferenceId");
            sourceWorkerId = normalizeReference(sourceWorkerId, 64, "sourceWorkerId");
            sourceDirectoryId = normalizeReference(
                    sourceDirectoryId, 64, "sourceDirectoryId");
            sourceMilestoneId = normalizeReference(
                    sourceMilestoneId, 64, "sourceMilestoneId");
            targetSessionId = requireReference(
                    targetSessionId, 64, "targetSessionId");
            targetWorkerId = requireReference(targetWorkerId, 64, "targetWorkerId");
            targetDirectoryId = normalizeReference(
                    targetDirectoryId, 64, "targetDirectoryId");
            targetMilestoneId = normalizeReference(
                    targetMilestoneId, 64, "targetMilestoneId");
            targetProviderType = normalizeReference(
                    targetProviderType, 32, "targetProviderType");
            targetModelConfigId = normalizeReference(
                    targetModelConfigId, 64, "targetModelConfigId");
            metadataJson = requireOpaque(metadataJson, "metadataJson");
        }

        static OutcomeSpec from(
                SessionForwardNewSessionPlan plan,
                String targetSessionId,
                DispatchTaskDTO task) {
            Objects.requireNonNull(plan, "forward plan must not be null");
            Objects.requireNonNull(task, "dispatch task must not be null");
            requireReference(task.getTaskId(), 315, "task.taskId");
            requireCompatible("task.sessionId", task.getSessionId(), targetSessionId);
            requireCompatible("task.workerId", task.getWorkerId(), plan.target().workerId());
            requireCompatible(
                    "task.directoryId", task.getDirectoryId(), plan.target().directoryId());
            requireCompatible(
                    "task.modelConfigId", task.getModelConfigId(), plan.target().modelConfigId());
            requireCompatible("task.model", task.getModel(), plan.target().model());
            requireCompatible("task.agentId", task.getAgentId(), plan.target().logicalAgentId());

            return new OutcomeSpec(
                    plan.ownerUserId(),
                    plan.tenantId(),
                    plan.source().sessionId(),
                    plan.source().referenceId(),
                    plan.source().workerId(),
                    plan.source().directoryId(),
                    plan.source().milestoneId(),
                    targetSessionId,
                    firstNonBlank(task.getWorkerId(), plan.target().workerId()),
                    firstNonBlank(task.getDirectoryId(), plan.target().directoryId()),
                    plan.target().milestoneId(),
                    task.getProviderType(),
                    firstNonBlank(task.getModelConfigId(), plan.target().modelConfigId()),
                    metadata(plan.prompt()));
        }

        private static OutcomeSpec from(SessionRelationEntity relation) {
            return new OutcomeSpec(
                    relation.getUserId(),
                    relation.getTenantId(),
                    relation.getSourceSessionId(),
                    relation.getSourceMessageId(),
                    relation.getSourceWorkerId(),
                    relation.getSourceDirectoryId(),
                    relation.getSourceMilestoneId(),
                    relation.getTargetSessionId(),
                    relation.getTargetWorkerId(),
                    relation.getTargetDirectoryId(),
                    relation.getTargetMilestoneId(),
                    relation.getTargetProviderType(),
                    relation.getTargetModelConfigId(),
                    relation.getMetadataJson());
        }

        @Override
        public String toString() {
            return "OutcomeSpec[ownerBound=true"
                    + ", tenantBound=" + (tenantId != null)
                    + ", sourceSessionId=" + sourceSessionId
                    + ", sourceReferenceId=" + sourceReferenceId
                    + ", targetSessionId=" + targetSessionId
                    + ", targetWorkerId=" + targetWorkerId
                    + ", targetDirectoryId=" + targetDirectoryId
                    + ", targetMilestoneId=" + targetMilestoneId
                    + ", targetProviderType=" + targetProviderType
                    + ", targetModelConfigId=" + targetModelConfigId
                    + ", metadata=<redacted>]";
        }
    }

    record OutcomeSnapshot(Long relationId, OutcomeSpec spec, LocalDateTime createdAt) {
        OutcomeSnapshot {
            Objects.requireNonNull(relationId, "relationId must not be null");
            Objects.requireNonNull(spec, "spec must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }

        @Override
        public String toString() {
            return "OutcomeSnapshot[relationId=" + relationId
                    + ", spec=<redacted>, createdAt=" + createdAt + ']';
        }
    }

    static final class ForwardOutcomeConflictException extends IllegalStateException {
        private ForwardOutcomeConflictException(String safeCode) {
            super(safeCode);
        }
    }

    private static String metadata(String prompt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetMode", TARGET_MODE);
        metadata.put("promptPreview", truncateSafely(prompt, 200));
        metadata.put("promptLength", prompt.length());
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(
                    "FORWARD_OUTCOME_METADATA_SERIALIZATION_FAILED", impossible);
        }
    }

    private static String truncateSafely(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        int end = maxLength;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static void requireCompatible(String field, String actual, String expected) {
        String normalizedActual = normalizeOptional(actual);
        String normalizedExpected = normalizeOptional(expected);
        if (normalizedActual != null && !Objects.equals(normalizedActual, normalizedExpected)) {
            throw new IllegalArgumentException(field + " conflicts with the forward plan");
        }
    }

    private static String firstNonBlank(String first, String second) {
        String normalized = normalizeOptional(first);
        return normalized != null ? normalized : normalizeOptional(second);
    }

    private static String requireReference(String value, int maxLength, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null || normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String normalizeReference(String value, int maxLength, String field) {
        String normalized = normalizeOptional(value);
        return normalized == null
                ? null
                : requireReference(normalized, maxLength, field);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireOpaque(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
