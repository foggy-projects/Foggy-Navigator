package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.TerminationOperationEntity;
import com.foggy.navigator.session.dto.TerminationOperationDTO;
import com.foggy.navigator.session.repository.TerminationOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.List;
import java.util.UUID;

/**
 * Durable control-plane record for termination intent.  Network dispatch must
 * only happen after {@link #accept(CreateCommand)} returns successfully.
 */
@Service
@RequiredArgsConstructor
public class TerminationOperationService {

    private static final int DEFAULT_TTL_SECONDS = 300;
    private static final List<String> TERMINAL_OPERATION_STATUSES =
            List.of("COMPLETED", "FAILED", "ABORTED", "REJECTED");

    private final TerminationOperationRepository repository;

    @Transactional
    public TerminationOperationEntity accept(CreateCommand command) {
        validateCreate(command);
        LocalDateTime now = LocalDateTime.now();
        TerminationOperationEntity entity = new TerminationOperationEntity();
        entity.setOperationId("to_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setSchemaVersion(1);
        entity.setTaskId(command.taskId());
        entity.setProviderTaskId(blankToNull(command.providerTaskId()));
        entity.setSessionId(command.sessionId());
        entity.setOwnerUserId(command.ownerUserId());
        entity.setTenantId(blankToNull(command.tenantId()));
        entity.setProviderType(command.providerType());
        entity.setWorkerId(command.workerId());
        entity.setKind(command.kind());
        entity.setOrigin(command.origin());
        entity.setActorId(command.actorId());
        entity.setActorType(command.actorType());
        entity.setAuthorizationDecisionId(blankToNull(command.authorizationDecisionId()));
        entity.setReasonCode(command.reasonCode());
        entity.setCorrelationId(blankToNull(command.correlationId()));
        entity.setExpectedPid(command.expectedPid());
        entity.setExpectedProcessIdentity(blankToNull(command.expectedProcessIdentity()));
        entity.setStatus("ACCEPTED");
        entity.setDispatchState("PENDING");
        entity.setRequestedAt(now);
        int ttlSeconds = command.ttlSeconds() == null ? DEFAULT_TTL_SECONDS : command.ttlSeconds();
        entity.setExpiresAt(now.plusSeconds(Math.min(DEFAULT_TTL_SECONDS, Math.max(1, ttlSeconds))));
        return repository.saveAndFlush(entity);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDispatchStarted(String operationId) {
        update(operationId, entity -> {
            if (isTerminal(entity.getStatus())) return;
            if ("ACCEPTED".equals(entity.getStatus())) entity.setStatus("RUNNING");
            entity.setDispatchedAt(LocalDateTime.now());
        });
    }

    /** A remote ACK is only evidence that the cancellation request was accepted. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCancelRequested(String operationId) {
        update(operationId, entity -> {
            if (isTerminal(entity.getStatus())) return;
            entity.setStatus("CANCEL_REQUESTED");
            entity.setDispatchState("ACKNOWLEDGED");
            entity.setAttentionCode(null);
            entity.setFailureCode(null);
        });
    }

    /**
     * A Worker may acknowledge an explicit manual PID signal while being
     * unable to prove the child process has exited yet.  Preserve that
     * distinction instead of promoting the task or operation to ABORTED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAwaitingObservation(String operationId, String attentionCode) {
        update(operationId, entity -> {
            if (isTerminal(entity.getStatus())) return;
            entity.setStatus("CANCEL_REQUESTED");
            entity.setDispatchState("ACKNOWLEDGED");
            entity.setAttentionCode(safeCode(attentionCode, "TERMINATION_UNCONFIRMED"));
            entity.setFailureCode(null);
        });
    }

    /** Keep operation and task pending when dispatch cannot be confirmed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnconfirmed(String operationId, String safeFailureCode) {
        update(operationId, entity -> {
            if (isTerminal(entity.getStatus())) return;
            entity.setStatus("RUNNING");
            entity.setDispatchState("UNCONFIRMED");
            entity.setAttentionCode("TERMINATION_UNCONFIRMED");
            entity.setFailureCode(safeCode(safeFailureCode, "TERMINATION_DISPATCH_UNCONFIRMED"));
        });
    }

    /**
     * Closes a one-shot cleanup attempt when its remote outcome cannot be
     * confirmed. Unlike an active cancellation, a stale-turn cleanup can be
     * safely retried only by reserving a fresh operation that re-reads the
     * exact native turn; leaving this operation RUNNING would block that safe
     * retry forever.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailedUnconfirmed(String operationId, String safeFailureCode) {
        update(operationId, entity -> {
            if (isTerminal(entity.getStatus())) return;
            entity.setStatus("FAILED");
            entity.setDispatchState("UNCONFIRMED");
            entity.setAttentionCode("TERMINATION_UNCONFIRMED");
            entity.setFailureCode(safeCode(safeFailureCode, "TERMINATION_DISPATCH_UNCONFIRMED"));
        });
    }

    /**
     * A Worker definitively rejected the signed operation (for example a
     * task/PID binding mismatch). This closes the audit operation without
     * manufacturing a task terminal state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRejected(String operationId, String safeFailureCode) {
        update(operationId, entity -> {
            if (isTerminal(entity.getStatus())) return;
            entity.setStatus("REJECTED");
            entity.setDispatchState("REJECTED");
            entity.setAttentionCode("TERMINATION_REJECTED");
            entity.setFailureCode(safeCode(safeFailureCode, "TERMINATION_REJECTED"));
        });
    }

    /** Called only from a provider terminal/verified-exit observation path. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markObservedTerminal(String operationId, String outcome) {
        update(operationId, entity -> {
            if (isTerminal(entity.getStatus())) return;
            entity.setStatus(normalizeObservedOutcome(outcome));
            entity.setDispatchState("OBSERVED");
            entity.setAttentionCode(null);
            entity.setFailureCode(null);
            entity.setObservedAt(LocalDateTime.now());
        });
    }

    /** Marks the newest in-flight operation for a task after provider evidence is observed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markObservedTerminalForTask(String taskId, String outcome) {
        if (!hasText(taskId)) return;
        repository.findByTaskIdOrderByCreatedAtDescForUpdate(taskId).stream()
                .filter(entity -> !isTerminal(entity.getStatus()))
                .findFirst()
                .ifPresent(entity -> {
                    entity.setStatus(normalizeObservedOutcome(outcome));
                    entity.setDispatchState("OBSERVED");
                    entity.setAttentionCode(null);
                    entity.setFailureCode(null);
                    entity.setObservedAt(LocalDateTime.now());
                    repository.save(entity);
                });
    }

    @Transactional(readOnly = true)
    public List<TerminationOperationDTO> findOwned(String taskId, String ownerUserId, String tenantId) {
        List<TerminationOperationEntity> operations = hasText(tenantId)
                ? repository.findByTaskIdAndOwnerUserIdAndTenantIdOrderByCreatedAtDesc(taskId, ownerUserId, tenantId)
                : repository.findByTaskIdAndOwnerUserIdAndTenantIdIsNullOrderByCreatedAtDesc(taskId, ownerUserId);
        return operations.stream().map(this::toDto).toList();
    }

    /**
     * Callers hold the matching task row lock while reserving a new operation.
     * There can be at most one in-flight termination intent per task, so a
     * later Worker terminal observation has an unambiguous audit target.
     */
    @Transactional
    public boolean hasActiveOperationForTask(String taskId) {
        if (!hasText(taskId)) return false;
        LocalDateTime now = LocalDateTime.now();
        boolean active = false;
        for (TerminationOperationEntity entity : repository.findByTaskIdOrderByCreatedAtDescForUpdate(taskId)) {
            if (isTerminal(entity.getStatus())) continue;
            if (entity.getExpiresAt() != null && !entity.getExpiresAt().isAfter(now)) {
                entity.setStatus("FAILED");
                entity.setDispatchState("UNCONFIRMED");
                entity.setAttentionCode("TERMINATION_UNCONFIRMED");
                entity.setFailureCode("TERMINATION_OPERATION_EXPIRED");
                repository.save(entity);
                continue;
            }
            active = true;
        }
        return active;
    }

    private void update(String operationId, java.util.function.Consumer<TerminationOperationEntity> updater) {
        if (!hasText(operationId)) return;
        repository.findByOperationIdForUpdate(operationId).ifPresent(entity -> {
            updater.accept(entity);
            repository.save(entity);
        });
    }

    private void validateCreate(CreateCommand command) {
        if (command == null || !hasText(command.taskId()) || !hasText(command.sessionId())
                || !hasText(command.ownerUserId()) || !hasText(command.providerType())
                || !hasText(command.workerId()) || !hasText(command.kind()) || !hasText(command.origin())
                || !hasText(command.actorId()) || !hasText(command.actorType()) || !hasText(command.reasonCode())) {
            throw new IllegalArgumentException("TERMINATION_OPERATION_FIELDS_REQUIRED");
        }
        if (!"REMOTE_CANCEL".equals(command.kind())
                && !"MANUAL_PID_KILL".equals(command.kind())
                && !"STALE_TURN_INTERRUPT".equals(command.kind())) {
            throw new IllegalArgumentException("TERMINATION_OPERATION_KIND_INVALID");
        }
        if ("REMOTE_CANCEL".equals(command.kind())) {
            if (!("UPSTREAM_USER".equals(command.origin()) || "UPSTREAM_SYSTEM".equals(command.origin()))
                    || !hasText(command.authorizationDecisionId()) || !hasText(command.providerTaskId())) {
                throw new IllegalArgumentException("TERMINATION_REMOTE_CANCEL_AUTHORIZATION_REQUIRED");
            }
        }
        if ("MANUAL_PID_KILL".equals(command.kind())
                && (!("ADMIN_MANUAL".equals(command.origin())) || command.expectedPid() == null
                || command.expectedPid() < 1 || !hasText(command.expectedProcessIdentity())
                || !isServerIssuedAuthorizationDecision(command.authorizationDecisionId(), command.actorType())
                || !("TENANT_ADMIN_MANUAL".equals(command.actorType())
                || "UPSTREAM_ADMIN_MANUAL".equals(command.actorType())))) {
            throw new IllegalArgumentException("TERMINATION_MANUAL_PID_AUTHORIZATION_REQUIRED");
        }
        if ("STALE_TURN_INTERRUPT".equals(command.kind())
                && (!("UPSTREAM_USER".equals(command.origin()))
                || !hasText(command.providerTaskId())
                || command.expectedPid() != null
                || command.expectedProcessIdentity() != null
                || !isServerIssuedAuthorizationDecision(
                        command.authorizationDecisionId(), command.actorType()))) {
            throw new IllegalArgumentException("TERMINATION_STALE_TURN_AUTHORIZATION_REQUIRED");
        }
    }

    private static boolean isServerIssuedAuthorizationDecision(String authorizationDecisionId,
                                                                String actorType) {
        if (!hasText(authorizationDecisionId) || !hasText(actorType)) {
            return false;
        }
        String prefix = "authz-v1:" + actorType.toLowerCase(Locale.ROOT) + ":";
        if (!authorizationDecisionId.startsWith(prefix)
                || authorizationDecisionId.length() <= prefix.length()) {
            return false;
        }
        String identifier = authorizationDecisionId.substring(prefix.length());
        return identifier.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}");
    }

    private TerminationOperationDTO toDto(TerminationOperationEntity entity) {
        return TerminationOperationDTO.builder()
                .operationId(entity.getOperationId())
                .schemaVersion(entity.getSchemaVersion())
                .taskId(entity.getTaskId())
                .providerTaskId(entity.getProviderTaskId())
                .providerType(entity.getProviderType())
                .workerId(entity.getWorkerId())
                .kind(entity.getKind())
                .origin(entity.getOrigin())
                .actorId(entity.getActorId())
                .actorType(entity.getActorType())
                .authorizationDecisionId(entity.getAuthorizationDecisionId())
                .reasonCode(entity.getReasonCode())
                .correlationId(entity.getCorrelationId())
                .expectedPid(entity.getExpectedPid())
                .expectedProcessIdentity(entity.getExpectedProcessIdentity())
                .status(entity.getStatus())
                .dispatchState(entity.getDispatchState())
                .attentionCode(entity.getAttentionCode())
                .failureCode(entity.getFailureCode())
                .requestedAt(entity.getRequestedAt())
                .dispatchedAt(entity.getDispatchedAt())
                .observedAt(entity.getObservedAt())
                .expiresAt(entity.getExpiresAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private static String normalizeObservedOutcome(String outcome) {
        if ("ABORTED".equalsIgnoreCase(outcome)) return "ABORTED";
        if ("FAILED".equalsIgnoreCase(outcome)) return "FAILED";
        return "COMPLETED";
    }

    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "ABORTED".equals(status)
                || "REJECTED".equals(status);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    private static String safeCode(String value, String fallback) {
        if (!hasText(value)) return fallback;
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    public record CreateCommand(
            String taskId,
            String providerTaskId,
            String sessionId,
            String ownerUserId,
            String tenantId,
            String providerType,
            String workerId,
            String kind,
            String origin,
            String actorId,
            String actorType,
            String authorizationDecisionId,
            String reasonCode,
            String correlationId,
            Integer expectedPid,
            String expectedProcessIdentity,
            Integer ttlSeconds) {
    }
}
