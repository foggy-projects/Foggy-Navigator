package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.claude.worker.model.dto.RuntimeAuditSideEffectsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskClosureDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskFactsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTerminationReadinessDTO;
import com.foggy.navigator.claude.worker.model.enums.RuntimeTaskReconciliationState;
import com.foggy.navigator.claude.worker.model.enums.RuntimeTaskTerminationOutcome;
import com.foggy.navigator.claude.worker.model.enums.RuntimeTerminationCapability;
import com.foggy.navigator.claude.worker.model.enums.RuntimeWorkerIdentityMatch;
import com.foggy.navigator.spi.task.RuntimeTaskClosureProvider;
import com.foggy.navigator.spi.lifecycle.TaskLifecycleProjectionPort;
import org.springframework.lang.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Runtime-lane task termination and reconciliation.
 *
 * <p>Termination acceptance and canonical task terminal state are separate
 * facts. The typed request-id reconciliation path is strictly read-only; the
 * legacy projection-repair method is retained separately for compatibility.</p>
 */
@Service
public class RuntimeTaskClosureService {

    private static final String UNKNOWN = "UNKNOWN";
    private static final Set<String> CANONICAL_TERMINAL_STATUSES = Set.of(
            "COMPLETED", "FAILED", "REJECTED", "TIMED_OUT", "TIMEOUT",
            "ABORTED", "CANCELLED", "CANCELED");

    private final RuntimeStateAuditService stateAuditService;
    private final List<RuntimeTaskClosureProvider> providers;
    private final RuntimeRequestAuditService requestAuditService;
    private final RuntimeTerminationAcceptanceCoordinator acceptanceCoordinator;
    private final RuntimeTerminationOutboxDispatcher outboxDispatcher;

    @Autowired(required = false)
    @Nullable
    private TaskLifecycleProjectionPort lifecycleProjection;

    @Autowired
    public RuntimeTaskClosureService(
            RuntimeStateAuditService stateAuditService,
            List<RuntimeTaskClosureProvider> providers,
            RuntimeRequestAuditService requestAuditService,
            RuntimeTerminationAcceptanceCoordinator acceptanceCoordinator,
            RuntimeTerminationOutboxDispatcher outboxDispatcher) {
        this.stateAuditService = stateAuditService;
        this.providers = providers;
        this.requestAuditService = requestAuditService;
        this.acceptanceCoordinator = acceptanceCoordinator;
        this.outboxDispatcher = outboxDispatcher;
    }

    RuntimeTaskClosureService(
            RuntimeStateAuditService stateAuditService,
            List<RuntimeTaskClosureProvider> providers,
            RuntimeRequestAuditService requestAuditService,
            RuntimeTerminationAcceptanceCoordinator acceptanceCoordinator) {
        this(stateAuditService, providers, requestAuditService,
                acceptanceCoordinator,
                acceptanceCoordinator == null ? null
                        : new RuntimeTerminationOutboxDispatcher(
                                acceptanceCoordinator));
    }

    RuntimeTaskClosureService(
            RuntimeStateAuditService stateAuditService,
            List<RuntimeTaskClosureProvider> providers,
            RuntimeRequestAuditService requestAuditService) {
        this(stateAuditService, providers, requestAuditService, null);
    }

    public RuntimeTerminationReadinessDTO readiness(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String taskId,
            String expectedPhysicalWorkerId) {
        RuntimeStateAuditService.OwnedRuntimeTask owned = stateAuditService.requireOwnedTask(
                appKey, appSecret, upstreamUserId, taskId);
        RuntimeTaskAuditDTO audit = stateAuditService.auditTask(
                appKey, appSecret, upstreamUserId, taskId);
        RuntimeTaskFactsDTO facts = facts(audit);
        RuntimeWorkerIdentityMatch identityMatch =
                workerIdentityMatch(expectedPhysicalWorkerId, owned.physicalWorkerId());
        Optional<RuntimeTaskClosureProvider> selectedProvider = providerOptional(owned);
        RuntimeTerminationCapability capability = selectedProvider.isPresent()
                ? RuntimeTerminationCapability.SUPPORTED
                : RuntimeTerminationCapability.UNAVAILABLE;

        RuntimeTaskClosureProvider.TerminationReadiness providerReadiness = null;
        String reasonCode;
        if (identityMatch == RuntimeWorkerIdentityMatch.UNKNOWN) {
            reasonCode = "EXPECTED_PHYSICAL_WORKER_REQUIRED";
        } else if (identityMatch == RuntimeWorkerIdentityMatch.MISMATCHED) {
            reasonCode = "EXPECTED_PHYSICAL_WORKER_MISMATCH";
        } else if (owned.terminal() || Boolean.TRUE.equals(facts != null ? facts.getTerminal() : null)) {
            reasonCode = "TASK_ALREADY_TERMINAL";
        } else if (selectedProvider.isEmpty()) {
            reasonCode = "RUNTIME_TASK_PROVIDER_UNSUPPORTED";
        } else {
            try {
                providerReadiness = selectedProvider.get().inspect(
                        owned.taskId(), owned.physicalWorkerId());
                reasonCode = readinessReason(providerReadiness, facts);
            } catch (RuntimeException error) {
                capability = RuntimeTerminationCapability.UNAVAILABLE;
                reasonCode = sanitizedCode(error, "TERMINATION_CAPABILITY_UNAVAILABLE");
            }
        }

        boolean allowed = "TERMINATION_READY".equals(reasonCode)
                && providerReadiness != null
                && providerReadiness.terminateAllowed();
        return RuntimeTerminationReadinessDTO.builder()
                .taskExists(true)
                .taskId(owned.taskId())
                .expectedPhysicalWorkerId(clean(expectedPhysicalWorkerId))
                .selectedPhysicalWorkerId(owned.physicalWorkerId())
                .workerIdentityMatch(identityMatch)
                .terminationCapability(capability)
                .currentTaskStatus(taskStatus(facts, owned.status()))
                .canonicalTerminal(facts != null ? facts.getTerminal() : owned.terminal())
                .reasonCode(reasonCode)
                .terminationRequestReceiptEnabled(
                        requestAuditService.terminationRequestReceiptEnabled())
                // Legacy response aliases retained for existing Map callers.
                .terminal(facts != null ? facts.getTerminal() : owned.terminal())
                .status(taskStatus(facts, owned.status()))
                .physicalWorkerId(owned.physicalWorkerId())
                .workerReachable(providerReadiness != null
                        ? providerReadiness.workerReachable() : null)
                .workerActiveTaskPresent(providerReadiness != null
                        ? providerReadiness.workerActiveTaskPresent() : null)
                .terminationReady(providerReadiness != null
                        ? providerReadiness.terminationReady() : null)
                .terminationAuthConfigured(providerReadiness != null
                        ? providerReadiness.terminationAuthConfigured() : null)
                .terminationWorkerIdConfigured(providerReadiness != null
                        ? providerReadiness.terminationWorkerIdConfigured() : null)
                .taskTokenStatus(facts != null ? facts.getTaskTokenStatus() : UNKNOWN)
                .activeTaskRegistrationPresent(facts != null
                        ? facts.getActiveTaskRegistrationPresent() : null)
                .terminateAllowed(allowed)
                .blockedReason(allowed ? null : reasonCode)
                .dryRun(true)
                .taskFacts(facts)
                .auditSideEffects(noSideEffects())
                .build();
    }

    public RuntimeTaskClosureDTO terminate(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String clientRequestId,
            String taskId,
            String expectedPhysicalWorkerId,
            String reason,
            String confirmTaskId,
            boolean dryRun) {
        requireClientRequestId(clientRequestId);
        if (dryRun) {
            return terminateDryRun(
                    appKey, appSecret, upstreamUserId, clientRequestId, taskId,
                    expectedPhysicalWorkerId, reason);
        }

        RuntimeStateAuditService.OwnedRuntimeTask owned;
        RuntimeTaskAuditDTO audit;
        RuntimeTaskClosureProvider selectedProvider;
        try {
            requireTerminationFields(taskId, expectedPhysicalWorkerId, reason);
            if (!Objects.equals(taskId, confirmTaskId)) {
                throw new IllegalArgumentException("CONFIRM_TASK_ID_MISMATCH");
            }
            owned = stateAuditService.requireOwnedTask(
                    appKey, appSecret, upstreamUserId, taskId);
            requireExpectedWorker(owned, expectedPhysicalWorkerId);
            audit = stateAuditService.auditTask(
                    appKey, appSecret, upstreamUserId, taskId);
            selectedProvider = provider(owned);
            RuntimeTaskClosureProvider.TerminationReadiness readiness =
                    selectedProvider.inspect(taskId, owned.physicalWorkerId());
            if (readiness == null || !readiness.terminateAllowed()) {
                throw new IllegalStateException(readinessReason(readiness, facts(audit)));
            }
        } catch (RuntimeException preflightFailure) {
            audit = StringUtils.hasText(taskId)
                    ? safeAudit(appKey, appSecret, upstreamUserId, taskId) : null;
            String code = sanitizedCode(
                    preflightFailure, "TERMINATION_PREFLIGHT_REJECTED");
            return terminationResponse(
                    clientRequestId, taskId, false, audit,
                    clean(expectedPhysicalWorkerId),
                    RuntimeTaskTerminationOutcome.REJECTED,
                    code, false, false, false, false, code);
        }

        RuntimeRequestAuditService.TaskOperationRegistration registration = null;
        if (requestAuditService.terminationRequestReceiptEnabled()) {
            try {
                registration = acceptanceCoordinator != null
                        ? acceptanceCoordinator.accept(
                                clientRequestId, appKey, appSecret,
                                upstreamUserId, taskId,
                                owned.sessionId(), owned.providerType(),
                                owned.physicalWorkerId(), owned.providerTaskId(),
                                owned.ownerUserId(), owned.tenantId(), reason,
                                selectedProvider)
                        : requestAuditService.beginTaskOperationIdempotent(
                                clientRequestId,
                                RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                                appKey,
                                appSecret,
                                null,
                                upstreamUserId,
                                taskId);
            } catch (IllegalArgumentException error) {
                if ("CLIENT_REQUEST_ID_ALREADY_USED".equals(error.getMessage())
                        && requestAuditService.findSelfTaskOperation(
                                appKey, appSecret, clientRequestId).isPresent()) {
                    return replayTermination(
                            appKey, appSecret, upstreamUserId, clientRequestId, taskId,
                            expectedPhysicalWorkerId);
                }
                throw error;
            } catch (RuntimeException persistenceFailure) {
                return receiptPersistenceFailure(
                        appKey, appSecret, upstreamUserId, clientRequestId, taskId);
            }
            if (acceptanceCoordinator != null
                    && !outboxDispatcher.recoveryCapable()) {
                try {
                    var authorization =
                            outboxDispatcher.authorize(clientRequestId);
                    if (!authorization.providerCallAuthorized()) {
                        return replayTermination(
                                appKey, appSecret, upstreamUserId,
                                clientRequestId, taskId,
                                expectedPhysicalWorkerId);
                    }
                } catch (RuntimeException persistenceFailure) {
                    return receiptPersistenceFailure(
                            appKey, appSecret, upstreamUserId,
                            clientRequestId, taskId);
                }
            } else if (acceptanceCoordinator == null
                    && registration.existing()) {
                return replayTermination(
                        appKey, appSecret, upstreamUserId, clientRequestId, taskId,
                        expectedPhysicalWorkerId);
            }
        }

        RuntimeRequestAuditService.AuditHandle requestAudit =
                registration != null ? registration.handle() : null;
        RuntimeTaskClosureProvider.TerminationResult providerResult = null;
        try {
            if (acceptanceCoordinator != null
                    && requestAuditService
                    .terminationRequestReceiptEnabled()
                    && outboxDispatcher.recoveryCapable()) {
                providerResult = outboxDispatcher.dispatch(
                        clientRequestId, reason.trim());
                if (providerResult == null) {
                    return replayTermination(
                            appKey, appSecret, upstreamUserId,
                            clientRequestId, taskId,
                            expectedPhysicalWorkerId);
                }
            } else {
                // BUG-035 compatibility: receipt-disabled requests never enter
                // owner dedupe and each HTTP request remains one provider
                // attempt even when the client request id is repeated.
                providerResult = selectedProvider.terminate(
                        taskId, owned.ownerUserId(), owned.tenantId(),
                        owned.physicalWorkerId(), reason.trim(),
                        clientRequestId, false);
                if (acceptanceCoordinator != null
                        && requestAuditService
                        .terminationRequestReceiptEnabled()) {
                    outboxDispatcher.resultObserved(
                            clientRequestId,
                            providerResult.terminationDispatched()
                                    ? "TERMINATION_DISPATCHED"
                                    : providerResult.alreadyTerminal()
                                    ? "TASK_ALREADY_TERMINAL"
                                    : "TERMINATION_RESULT_OBSERVED");
                }
            }
            audit = safeAudit(
                    appKey, appSecret, upstreamUserId, taskId);

            RuntimeTaskTerminationOutcome outcome = terminationOutcome(providerResult);
            String reasonCode = terminationReason(outcome, providerResult);
            RuntimeTaskFactsDTO currentFacts = facts(audit);
            if (requestAudit != null
                    && outcome == RuntimeTaskTerminationOutcome.REJECTED) {
                requestAuditService.taskOperationFailed(requestAudit, reasonCode);
            } else if (requestAudit != null) {
                requestAuditService.taskOperationCompleted(
                        requestAudit,
                        terminationEvidence(
                                taskId, owned.physicalWorkerId(), currentFacts,
                                providerResult, outcome),
                        false,
                        providerResult.terminationDispatched());
            }
            return terminationResponse(
                    clientRequestId, taskId, false, audit, owned.physicalWorkerId(),
                    outcome, reasonCode, providerResult.alreadyTerminal(),
                    providerResult.terminationDispatched(), providerResult.idempotentReplay(),
                    providerResult.reconcileRequired() || currentFacts == null,
                    providerResult.sanitizedErrorCode());
        } catch (RuntimeException error) {
            String reasonCode = sanitizedCode(error, "RUNTIME_TASK_TERMINATE_FAILED");
            if (StringUtils.hasText(taskId)) {
                audit = safeAudit(appKey, appSecret, upstreamUserId, taskId);
            }
            if (providerResult != null) {
                RuntimeTaskTerminationOutcome outcome = terminationOutcome(providerResult);
                return terminationResponse(
                        clientRequestId, taskId, false, audit,
                        owned != null ? owned.physicalWorkerId() : null,
                        outcome, terminationReason(outcome, providerResult),
                        providerResult.alreadyTerminal(),
                        providerResult.terminationDispatched(),
                        providerResult.idempotentReplay(),
                        true,
                        reasonCode);
            }
            if (terminationMayBeInFlight(audit, reasonCode)) {
                return terminationResponse(
                        clientRequestId, taskId, false, audit,
                        owned != null ? owned.physicalWorkerId() : null,
                        RuntimeTaskTerminationOutcome.PROCESSING, reasonCode,
                        false, false, false, true, reasonCode);
            }
            if (requestAudit != null) {
                requestAuditService.taskOperationFailed(requestAudit, reasonCode);
            }
            return terminationResponse(
                    clientRequestId, taskId, false, audit,
                    owned != null ? owned.physicalWorkerId() : null,
                    RuntimeTaskTerminationOutcome.REJECTED, reasonCode,
                    false, false, false, false, reasonCode);
        }
    }

    private RuntimeTaskClosureDTO receiptPersistenceFailure(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String clientRequestId,
            String taskId) {
        RuntimeTaskAuditDTO audit = StringUtils.hasText(taskId)
                ? safeAudit(appKey, appSecret, upstreamUserId, taskId)
                : null;
        RuntimeTaskFactsDTO currentFacts = facts(audit);
        String currentStatus = taskStatus(
                currentFacts, audit != null ? audit.getStatus() : UNKNOWN);
        Boolean terminal = currentFacts != null
                ? currentFacts.getTerminal()
                : audit != null ? audit.getTerminal() : false;
        return RuntimeTaskClosureDTO.builder()
                .clientRequestId(clientRequestId)
                .operation(RuntimeRequestAuditService.OPERATION_TASK_TERMINATE)
                .taskId(taskId)
                .outcome(RuntimeTaskTerminationOutcome.REJECTED)
                .terminationOutcome(RuntimeTaskTerminationOutcome.REJECTED)
                .reconciliationState(RuntimeTaskReconciliationState.REJECTED)
                .currentTaskStatus(currentStatus)
                .canonicalTerminal(Boolean.TRUE.equals(terminal))
                .reasonCode("TERMINATION_REQUEST_RECEIPT_PERSISTENCE_FAILED")
                .requestFound(false)
                .readOnly(false)
                .sameClientRequestIdReplaySafe(false)
                .terminationReplayRecommended(false)
                .newClientRequestIdAllowed(false)
                .terminationRequestReceiptEnabled(true)
                .terminationRequestReceiptPersisted(false)
                .requestReconciliationAvailable(false)
                .dryRun(false)
                .alreadyTerminal(Boolean.TRUE.equals(terminal))
                .terminationDispatched(false)
                .idempotentReplay(false)
                .reconcileRequired(false)
                .sanitizedErrorCode(
                        "TERMINATION_REQUEST_RECEIPT_PERSISTENCE_FAILED")
                .taskFacts(currentFacts)
                .auditSideEffects(noSideEffects())
                .build();
    }

    private RuntimeTaskClosureDTO terminateDryRun(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String clientRequestId,
            String taskId,
            String expectedPhysicalWorkerId,
            String reason) {
        RuntimeStateAuditService.OwnedRuntimeTask owned = null;
        RuntimeTaskAuditDTO audit = null;
        try {
            requireTerminationFields(taskId, expectedPhysicalWorkerId, reason);
            owned = stateAuditService.requireOwnedTask(
                    appKey, appSecret, upstreamUserId, taskId);
            requireExpectedWorker(owned, expectedPhysicalWorkerId);
            audit = stateAuditService.auditTask(
                    appKey, appSecret, upstreamUserId, taskId);
            RuntimeTaskClosureProvider.TerminationResult result = provider(owned).terminate(
                    taskId, owned.ownerUserId(), owned.tenantId(), owned.physicalWorkerId(),
                    reason.trim(), clientRequestId, true);
            audit = stateAuditService.auditTask(
                    appKey, appSecret, upstreamUserId, taskId);
            RuntimeTaskTerminationOutcome outcome = result.alreadyTerminal()
                    ? RuntimeTaskTerminationOutcome.ALREADY_TERMINAL
                    : StringUtils.hasText(result.sanitizedErrorCode())
                    ? RuntimeTaskTerminationOutcome.REJECTED
                    : RuntimeTaskTerminationOutcome.DRY_RUN;
            String reasonCode = outcome == RuntimeTaskTerminationOutcome.ALREADY_TERMINAL
                    ? "TASK_ALREADY_TERMINAL"
                    : outcome == RuntimeTaskTerminationOutcome.REJECTED
                    ? stableCode(result.sanitizedErrorCode(), "TERMINATION_DRY_RUN_REJECTED")
                    : "TERMINATION_DRY_RUN_READY";
            return terminationResponse(
                    clientRequestId, taskId, true, audit, owned.physicalWorkerId(),
                    outcome, reasonCode, result.alreadyTerminal(), false,
                    result.idempotentReplay(), result.reconcileRequired(),
                    result.sanitizedErrorCode());
        } catch (RuntimeException error) {
            String reasonCode = sanitizedCode(error, "TERMINATION_DRY_RUN_REJECTED");
            if (audit == null && StringUtils.hasText(taskId)) {
                audit = safeAudit(appKey, appSecret, upstreamUserId, taskId);
            }
            return terminationResponse(
                    clientRequestId, taskId, true, audit,
                    owned != null ? owned.physicalWorkerId() : null,
                    RuntimeTaskTerminationOutcome.REJECTED, reasonCode,
                    false, false, false, false, reasonCode);
        }
    }

    private RuntimeTaskClosureDTO replayTermination(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String clientRequestId,
            String taskId,
            String expectedPhysicalWorkerId) {
        Optional<RuntimeRequestAuditService.TaskOperationSnapshot> existing =
                requestAuditService.findSelfTaskOperation(appKey, appSecret, clientRequestId);
        RuntimeTaskAuditDTO audit = safeAudit(
                appKey, appSecret, upstreamUserId, taskId);
        if (existing.isEmpty()) {
            return terminationResponse(
                    clientRequestId, taskId, false, audit, selectedWorker(audit, null),
                    RuntimeTaskTerminationOutcome.PROCESSING,
                    "TERMINATION_REQUEST_PROCESSING",
                    false, false, true, true, null);
        }
        RuntimeRequestAuditService.TaskOperationSnapshot snapshot = existing.get();
        if (!RuntimeRequestAuditService.OPERATION_TASK_TERMINATE.equals(snapshot.operation())
                || !Objects.equals(clean(taskId), clean(snapshot.taskId()))
                || !Objects.equals(clean(upstreamUserId), clean(snapshot.upstreamUserId()))) {
            return terminationResponse(
                    clientRequestId, taskId, false, audit, selectedWorker(audit, null),
                    RuntimeTaskTerminationOutcome.REJECTED,
                    "CLIENT_REQUEST_ID_OPERATION_MISMATCH",
                    false, false, true, false,
                    "CLIENT_REQUEST_ID_OPERATION_MISMATCH");
        }
        if (StringUtils.hasText(snapshot.physicalWorkerId())
                && (!StringUtils.hasText(expectedPhysicalWorkerId)
                || !snapshot.physicalWorkerId().equals(expectedPhysicalWorkerId.trim()))) {
            return terminationResponse(
                    clientRequestId, taskId, false, audit,
                    selectedWorker(audit, snapshot.physicalWorkerId()),
                    RuntimeTaskTerminationOutcome.REJECTED,
                    "CLIENT_REQUEST_ID_OPERATION_MISMATCH",
                    false, false, true, false,
                    "CLIENT_REQUEST_ID_OPERATION_MISMATCH");
        }
        RuntimeTaskTerminationOutcome outcome = snapshotOutcome(snapshot);
        String reasonCode = switch (outcome) {
            case ACCEPTED -> "TERMINATION_REQUEST_ACCEPTED";
            case REJECTED -> stableCode(
                    snapshot.sanitizedErrorCode(), "TERMINATION_REQUEST_REJECTED");
            case ALREADY_TERMINAL -> "TASK_ALREADY_TERMINAL";
            case PROCESSING -> "TERMINATION_REQUEST_PROCESSING";
            default -> "TERMINATION_REQUEST_STATE_AMBIGUOUS";
        };
        return terminationResponse(
                clientRequestId, taskId, false, audit,
                selectedWorker(audit, snapshot.physicalWorkerId()),
                outcome, reasonCode,
                outcome == RuntimeTaskTerminationOutcome.ALREADY_TERMINAL,
                false, true,
                outcome == RuntimeTaskTerminationOutcome.ACCEPTED
                        && !Boolean.TRUE.equals(canonicalTerminal(audit)),
                snapshot.sanitizedErrorCode());
    }

    /**
     * Strictly read-only reconciliation of the original termination request.
     * It never creates an audit record, calls a provider, repairs a projection,
     * or dispatches a Worker command.
     */
    @Transactional(readOnly = true)
    public RuntimeTaskClosureDTO reconcileTerminationRequest(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String originalClientRequestId,
            String taskId) {
        requireClientRequestId(originalClientRequestId);
        if (!requestAuditService.terminationRequestReceiptEnabled()) {
            RuntimeTaskAuditDTO audit = StringUtils.hasText(taskId)
                    ? safeAudit(appKey, appSecret, upstreamUserId, taskId)
                    : null;
            return reconciliationResponse(
                    originalClientRequestId, clean(taskId), audit, null,
                    RuntimeTaskReconciliationState.AMBIGUOUS,
                    RuntimeTaskTerminationOutcome.UNKNOWN,
                    "UNKNOWN", "TERMINATION_REQUEST_RECEIPT_DISABLED", false);
        }
        if (!StringUtils.hasText(taskId)) {
            return reconciliationResponse(
                    originalClientRequestId, null, null, null,
                    RuntimeTaskReconciliationState.AMBIGUOUS,
                    RuntimeTaskTerminationOutcome.UNKNOWN,
                    "UNKNOWN", "RUNTIME_TASK_REQUIRED", false);
        }

        Optional<RuntimeRequestAuditService.TaskOperationSnapshot> existing =
                requestAuditService.findSelfTaskOperation(
                        appKey, appSecret, originalClientRequestId);
        RuntimeTaskAuditDTO audit = safeAudit(
                appKey, appSecret, upstreamUserId, taskId);
        if (existing.isEmpty()) {
            return reconciliationResponse(
                    originalClientRequestId, taskId, audit, null,
                    RuntimeTaskReconciliationState.NOT_FOUND,
                    RuntimeTaskTerminationOutcome.UNKNOWN,
                    "NOT_FOUND", "TERMINATION_REQUEST_NOT_FOUND", false);
        }

        RuntimeRequestAuditService.TaskOperationSnapshot snapshot = existing.get();
        if (!RuntimeRequestAuditService.OPERATION_TASK_TERMINATE.equals(snapshot.operation())
                || !taskId.trim().equals(snapshot.taskId())
                || (StringUtils.hasText(snapshot.upstreamUserId())
                && !snapshot.upstreamUserId().equals(upstreamUserId))) {
            return reconciliationResponse(
                    originalClientRequestId, taskId, audit, snapshot,
                    RuntimeTaskReconciliationState.AMBIGUOUS,
                    RuntimeTaskTerminationOutcome.UNKNOWN,
                    stableText(snapshot.status()), "CLIENT_REQUEST_ID_OPERATION_MISMATCH", true);
        }

        RuntimeTaskTerminationOutcome originalOutcome = snapshotOutcome(snapshot);
        if (originalOutcome == RuntimeTaskTerminationOutcome.REJECTED) {
            return reconciliationResponse(
                    originalClientRequestId, taskId, audit, snapshot,
                    RuntimeTaskReconciliationState.REJECTED,
                    originalOutcome,
                    stableText(snapshot.status()),
                    stableCode(snapshot.sanitizedErrorCode(), "TERMINATION_REQUEST_REJECTED"),
                    true);
        }
        Optional<TaskLifecycleProjectionPort.TaskLifecycleProjection> ownerProjection =
                lifecycleProjection == null
                        ? Optional.empty() : lifecycleProjection.find(taskId);
        boolean canonicalTerminal = ownerProjection
                .map(TaskLifecycleProjectionPort.TaskLifecycleProjection::typedTerminal)
                .orElseGet(() -> Boolean.TRUE.equals(canonicalTerminal(audit)));
        boolean cleanupComplete = ownerProjection
                .map(TaskLifecycleProjectionPort.TaskLifecycleProjection::cleanupComplete)
                .orElseGet(() -> terminalCleanupComplete(audit));
        if (canonicalTerminal) {
            if (!cleanupComplete) {
                return reconciliationResponse(
                        originalClientRequestId, taskId, audit, snapshot,
                        RuntimeTaskReconciliationState.AMBIGUOUS,
                        originalOutcome == RuntimeTaskTerminationOutcome.PROCESSING
                                ? RuntimeTaskTerminationOutcome.ACCEPTED : originalOutcome,
                        stableText(snapshot.status()),
                        "TERMINAL_CLEANUP_INCOMPLETE", true);
            }
            return reconciliationResponse(
                    originalClientRequestId, taskId, audit, snapshot,
                    RuntimeTaskReconciliationState.TERMINAL,
                    originalOutcome == RuntimeTaskTerminationOutcome.PROCESSING
                            ? RuntimeTaskTerminationOutcome.ACCEPTED : originalOutcome,
                    stableText(snapshot.status()), "TASK_CANONICAL_TERMINAL", true);
        }
        if (ownerProjection.isPresent()
                && ownerProjection.get().canonicalTerminal()
                && !ownerProjection.get().typedTerminal()) {
            return reconciliationResponse(
                    originalClientRequestId, taskId, audit, snapshot,
                    RuntimeTaskReconciliationState.AMBIGUOUS,
                    originalOutcome,
                    stableText(snapshot.status()),
                    ownerProjection.get().cleanupComplete()
                            ? "CANONICAL_STATUS_NOT_TERMINAL"
                            : "TERMINAL_CLEANUP_INCOMPLETE",
                    true);
        }
        if (originalOutcome == RuntimeTaskTerminationOutcome.ALREADY_TERMINAL) {
            return reconciliationResponse(
                    originalClientRequestId, taskId, audit, snapshot,
                    RuntimeTaskReconciliationState.AMBIGUOUS,
                    originalOutcome,
                    stableText(snapshot.status()),
                    "TERMINATION_RECEIPT_TERMINAL_WITHOUT_CANONICAL_TASK", true);
        }
        if ("TASK_TERMINATED".equals(stableText(snapshot.result()))) {
            return reconciliationResponse(
                    originalClientRequestId, taskId, audit, snapshot,
                    RuntimeTaskReconciliationState.AMBIGUOUS,
                    originalOutcome,
                    stableText(snapshot.status()),
                    "TERMINATION_EVIDENCE_WITHOUT_CANONICAL_TASK", true);
        }
        if (snapshot.convergenceTimedOut()) {
            return reconciliationResponse(
                    originalClientRequestId, taskId, audit, snapshot,
                    RuntimeTaskReconciliationState.AMBIGUOUS,
                    originalOutcome,
                    stableText(snapshot.status()),
                    snapshot.completed()
                            ? "TERMINATION_RESULT_NOT_OBSERVED_WITHIN_TIMEOUT"
                            : "TERMINATION_REQUEST_NOT_COMPLETED_WITHIN_TIMEOUT",
                    true);
        }
        if (!snapshot.completed()) {
            return reconciliationResponse(
                    originalClientRequestId, taskId, audit, snapshot,
                    RuntimeTaskReconciliationState.IN_PROGRESS,
                    RuntimeTaskTerminationOutcome.PROCESSING,
                    stableText(snapshot.status()), "TERMINATION_REQUEST_PROCESSING", true);
        }
        if (originalOutcome == RuntimeTaskTerminationOutcome.ACCEPTED) {
            return reconciliationResponse(
                    originalClientRequestId, taskId, audit, snapshot,
                    RuntimeTaskReconciliationState.ACCEPTED,
                    originalOutcome,
                    stableText(snapshot.status()), "TERMINATION_REQUEST_ACCEPTED", true);
        }
        return reconciliationResponse(
                originalClientRequestId, taskId, audit, snapshot,
                RuntimeTaskReconciliationState.AMBIGUOUS,
                RuntimeTaskTerminationOutcome.UNKNOWN,
                stableText(snapshot.status()), "TERMINATION_REQUEST_STATE_AMBIGUOUS", true);
    }

    /**
     * Legacy evidence-gated projection repair retained for existing Map/CLI
     * callers. New typed SDK callers never invoke this mutation branch.
     */
    public RuntimeTaskClosureDTO reconcile(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String clientRequestId,
            String taskId,
            String expectedPhysicalWorkerId,
            int expectedDispatchCount,
            String confirmTaskId,
            boolean dryRun) {
        requireClientRequestId(clientRequestId);
        if (!dryRun && !Objects.equals(taskId, confirmTaskId)) {
            throw new IllegalArgumentException("CONFIRM_TASK_ID_MISMATCH");
        }
        RuntimeStateAuditService.OwnedRuntimeTask owned = stateAuditService.requireOwnedTask(
                appKey, appSecret, upstreamUserId, taskId);
        requireExpectedWorker(owned, expectedPhysicalWorkerId);
        if (owned.dispatchCount() != expectedDispatchCount) {
            throw new IllegalArgumentException("EXPECTED_DISPATCH_COUNT_MISMATCH");
        }
        RuntimeRequestAuditService.AuditHandle requestAudit = dryRun ? null
                : requestAuditService.beginTaskOperation(
                        clientRequestId, RuntimeRequestAuditService.OPERATION_TASK_RECONCILE,
                        appKey, appSecret, null, upstreamUserId, taskId);
        try {
            RuntimeTaskClosureProvider.ReconciliationResult result = provider(owned).reconcile(
                    taskId, owned.ownerUserId(), owned.tenantId(), expectedPhysicalWorkerId,
                    expectedDispatchCount, clientRequestId, dryRun);
            RuntimeTaskAuditDTO audit = stateAuditService.auditTask(
                    appKey, appSecret, upstreamUserId, taskId);
            if (requestAudit != null) {
                requestAuditService.taskOperationCompleted(
                        requestAudit,
                        evidence(facts(audit), result.reconciliationChanged()
                                ? "RECONCILIATION_CHANGED" : "RECONCILIATION_NO_CHANGE"),
                        false,
                        result.reconciliationChanged());
            }
            if (!dryRun && Boolean.TRUE.equals(canonicalTerminal(audit))) {
                requestAuditService.refreshCompletedTaskOperation(
                        taskId,
                        RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                        evidence(facts(audit), "TASK_TERMINATED"));
                requestAuditService.refreshCompletedTaskOperation(
                        taskId,
                        RuntimeRequestAuditService.OPERATION_TASK_RECONCILE,
                        evidence(facts(audit), result.reconciliationChanged()
                                ? "RECONCILIATION_CHANGED" : "RECONCILIATION_NO_CHANGE"));
            }
            return base(clientRequestId, "task-reconcile", taskId, dryRun, audit)
                    .currentTaskStatus(taskStatus(facts(audit), null))
                    .canonicalTerminal(canonicalTerminal(audit))
                    .reasonCode(stableCode(
                            result.sanitizedErrorCode(),
                            result.reconciliationChanged()
                                    ? "RECONCILIATION_CHANGED" : "RECONCILIATION_NO_CHANGE"))
                    .selectedPhysicalWorkerId(owned.physicalWorkerId())
                    .reconciliationChanged(result.reconciliationChanged())
                    .alreadyConsistent(result.alreadyConsistent())
                    .durableEvidence(result.durableEvidence())
                    .sanitizedErrorCode(result.sanitizedErrorCode())
                    .build();
        } catch (RuntimeException error) {
            if (requestAudit != null) {
                requestAuditService.taskOperationFailed(
                        requestAudit, sanitizedCode(error, "RUNTIME_TASK_RECONCILE_FAILED"));
            }
            throw error;
        }
    }

    private RuntimeTaskClosureDTO terminationResponse(
            String clientRequestId,
            String taskId,
            boolean dryRun,
            RuntimeTaskAuditDTO audit,
            String selectedPhysicalWorkerId,
            RuntimeTaskTerminationOutcome outcome,
            String reasonCode,
            boolean alreadyTerminal,
            boolean terminationDispatched,
            boolean idempotentReplay,
            boolean reconcileRequired,
            String sanitizedErrorCode) {
        boolean receiptEnabled =
                requestAuditService.terminationRequestReceiptEnabled();
        boolean receiptPersisted = !dryRun && receiptEnabled;
        return base(clientRequestId, "task-terminate", taskId, dryRun, audit)
                .outcome(outcome)
                .currentTaskStatus(taskStatus(facts(audit), null))
                .canonicalTerminal(canonicalTerminal(audit))
                .reasonCode(stableCode(reasonCode, UNKNOWN))
                .selectedPhysicalWorkerId(selectedPhysicalWorkerId)
                .alreadyTerminal(alreadyTerminal)
                .terminationDispatched(terminationDispatched)
                .idempotentReplay(idempotentReplay)
                .reconcileRequired(reconcileRequired)
                .terminationRequestReceiptEnabled(receiptEnabled)
                .terminationRequestReceiptPersisted(receiptPersisted)
                .requestReconciliationAvailable(receiptPersisted)
                .sanitizedErrorCode(sanitizedErrorCode)
                .build();
    }

    private RuntimeTaskClosureDTO reconciliationResponse(
            String clientRequestId,
            String taskId,
            RuntimeTaskAuditDTO audit,
            RuntimeRequestAuditService.TaskOperationSnapshot snapshot,
            RuntimeTaskReconciliationState state,
            RuntimeTaskTerminationOutcome terminationOutcome,
            String transition,
            String reasonCode,
            boolean requestFound) {
        boolean receiptEnabled =
                requestAuditService.terminationRequestReceiptEnabled();
        return base(clientRequestId, "task-reconcile", taskId, false, audit)
                .reconciliationState(state)
                .terminationOutcome(terminationOutcome)
                .transition(stableText(transition))
                .currentTaskStatus(taskStatus(facts(audit), null))
                .canonicalTerminal(canonicalTerminal(audit))
                .reasonCode(stableCode(reasonCode, UNKNOWN))
                .selectedPhysicalWorkerId(selectedWorker(
                        audit, snapshot != null ? snapshot.physicalWorkerId() : null))
                .requestFound(requestFound)
                .readOnly(true)
                .sameClientRequestIdReplaySafe(receiptEnabled)
                .terminationReplayRecommended(receiptEnabled
                        && state == RuntimeTaskReconciliationState.NOT_FOUND)
                .newClientRequestIdAllowed(false)
                .terminationRequestReceiptEnabled(receiptEnabled)
                .requestReconciliationAvailable(receiptEnabled)
                .build();
    }

    private RuntimeTaskClosureDTO.RuntimeTaskClosureDTOBuilder base(
            String clientRequestId,
            String operation,
            String taskId,
            boolean dryRun,
            RuntimeTaskAuditDTO audit) {
        return RuntimeTaskClosureDTO.builder()
                .clientRequestId(clientRequestId)
                .operation(operation)
                .taskId(taskId)
                .dryRun(dryRun)
                .taskFacts(facts(audit))
                .auditSideEffects(noSideEffects())
                .newTaskCreated(false)
                .newContextCreated(false)
                .newSessionCreated(false)
                .accessTokenIssued(false)
                .runtimeTokenIssued(false)
                .taskTokenIssued(false)
                .modelRedispatched(false)
                .businessFunctionDispatched(false)
                .retryTriggered(false)
                .recoveryTriggered(false)
                .provisioningResourceChanged(false);
    }

    private RuntimeTaskTerminationOutcome terminationOutcome(
            RuntimeTaskClosureProvider.TerminationResult result) {
        if (result.alreadyTerminal()) {
            return RuntimeTaskTerminationOutcome.ALREADY_TERMINAL;
        }
        if (result.terminationDispatched() || result.idempotentReplay()) {
            return RuntimeTaskTerminationOutcome.ACCEPTED;
        }
        return RuntimeTaskTerminationOutcome.REJECTED;
    }

    private RuntimeTaskTerminationOutcome snapshotOutcome(
            RuntimeRequestAuditService.TaskOperationSnapshot snapshot) {
        if (!snapshot.completed()) {
            return RuntimeTaskTerminationOutcome.PROCESSING;
        }
        String result = stableText(snapshot.result());
        if ("ALREADY_TERMINAL".equals(result)) {
            return RuntimeTaskTerminationOutcome.ALREADY_TERMINAL;
        }
        if ("TERMINATION_REQUESTED".equals(result)
                || "TERMINATION_ACCEPTED".equals(result)
                || "TASK_TERMINATED".equals(result)) {
            return RuntimeTaskTerminationOutcome.ACCEPTED;
        }
        if ("FAILED".equals(result) || StringUtils.hasText(snapshot.sanitizedErrorCode())) {
            return RuntimeTaskTerminationOutcome.REJECTED;
        }
        return RuntimeTaskTerminationOutcome.UNKNOWN;
    }

    private String terminationReason(
            RuntimeTaskTerminationOutcome outcome,
            RuntimeTaskClosureProvider.TerminationResult result) {
        return switch (outcome) {
            case ACCEPTED -> "TERMINATION_REQUEST_ACCEPTED";
            case ALREADY_TERMINAL -> "TASK_ALREADY_TERMINAL";
            case REJECTED -> stableCode(
                    result.sanitizedErrorCode(), "TERMINATION_REQUEST_REJECTED");
            default -> "TERMINATION_REQUEST_STATE_AMBIGUOUS";
        };
    }

    private String readinessReason(
            RuntimeTaskClosureProvider.TerminationReadiness readiness,
            RuntimeTaskFactsDTO facts) {
        if (facts == null) {
            return "TASK_STATUS_UNKNOWN";
        }
        if (!"ACTIVE".equals(facts.getTaskTokenStatus())) {
            return "TASK_TOKEN_NOT_ACTIVE";
        }
        if (!Boolean.TRUE.equals(facts.getActiveTaskRegistrationPresent())) {
            return "ACTIVE_TASK_REGISTRATION_NOT_PRESENT";
        }
        if (readiness != null && readiness.terminateAllowed()) {
            return "TERMINATION_READY";
        }
        return stableCode(
                readiness != null ? readiness.blockedReason() : null,
                "TERMINATION_CAPABILITY_UNAVAILABLE");
    }

    private Optional<RuntimeTaskClosureProvider> providerOptional(
            RuntimeStateAuditService.OwnedRuntimeTask task) {
        return providers.stream()
                .filter(value -> value.supports(task.providerType()))
                .findFirst();
    }

    private RuntimeTaskClosureProvider provider(RuntimeStateAuditService.OwnedRuntimeTask task) {
        return providerOptional(task)
                .orElseThrow(() -> new IllegalStateException("RUNTIME_TASK_PROVIDER_UNSUPPORTED"));
    }

    private void requireExpectedWorker(
            RuntimeStateAuditService.OwnedRuntimeTask task,
            String expectedPhysicalWorkerId) {
        if (!StringUtils.hasText(expectedPhysicalWorkerId)) {
            throw new IllegalArgumentException("EXPECTED_PHYSICAL_WORKER_REQUIRED");
        }
        if (!expectedPhysicalWorkerId.trim().equals(task.physicalWorkerId())) {
            throw new IllegalArgumentException("EXPECTED_PHYSICAL_WORKER_MISMATCH");
        }
    }

    private void requireTerminationFields(
            String taskId,
            String expectedPhysicalWorkerId,
            String reason) {
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalArgumentException("RUNTIME_TASK_REQUIRED");
        }
        if (!StringUtils.hasText(expectedPhysicalWorkerId)) {
            throw new IllegalArgumentException("EXPECTED_PHYSICAL_WORKER_REQUIRED");
        }
        if (!StringUtils.hasText(reason)) {
            throw new IllegalArgumentException("TERMINATION_REASON_REQUIRED");
        }
        if (reason.trim().length() > 160) {
            throw new IllegalArgumentException("TERMINATION_REASON_INVALID");
        }
    }

    private RuntimeWorkerIdentityMatch workerIdentityMatch(
            String expectedPhysicalWorkerId,
            String selectedPhysicalWorkerId) {
        if (!StringUtils.hasText(expectedPhysicalWorkerId)
                || !StringUtils.hasText(selectedPhysicalWorkerId)) {
            return RuntimeWorkerIdentityMatch.UNKNOWN;
        }
        return expectedPhysicalWorkerId.trim().equals(selectedPhysicalWorkerId)
                ? RuntimeWorkerIdentityMatch.MATCHED
                : RuntimeWorkerIdentityMatch.MISMATCHED;
    }

    private void requireClientRequestId(String value) {
        if (!StringUtils.hasText(value)
                || !value.trim().matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("CLIENT_REQUEST_ID_INVALID");
        }
    }

    private RuntimeTaskAuditDTO safeAudit(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String taskId) {
        try {
            return stateAuditService.auditTask(
                    appKey, appSecret, upstreamUserId, taskId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private RuntimeTaskFactsDTO facts(RuntimeTaskAuditDTO audit) {
        return audit != null ? audit.getTaskFacts() : null;
    }

    private Boolean canonicalTerminal(RuntimeTaskAuditDTO audit) {
        RuntimeTaskFactsDTO facts = facts(audit);
        if (facts == null) {
            return null;
        }
        return Boolean.TRUE.equals(facts.getTerminal())
                && CANONICAL_TERMINAL_STATUSES.contains(
                stableText(facts.getStatus()).toUpperCase(Locale.ROOT));
    }

    private String taskStatus(RuntimeTaskFactsDTO facts, String fallback) {
        return stableText(facts != null ? facts.getStatus() : fallback);
    }

    private String selectedWorker(RuntimeTaskAuditDTO audit, String fallback) {
        RuntimeTaskFactsDTO facts = facts(audit);
        return facts != null && StringUtils.hasText(facts.getPhysicalWorkerId())
                ? facts.getPhysicalWorkerId() : clean(fallback);
    }

    private boolean terminalCleanupComplete(RuntimeTaskAuditDTO audit) {
        RuntimeTaskFactsDTO facts = facts(audit);
        return facts != null
                && "REVOKED".equalsIgnoreCase(facts.getTaskTokenStatus())
                && Boolean.FALSE.equals(facts.getActiveTaskRegistrationPresent());
    }

    private boolean terminationMayBeInFlight(
            RuntimeTaskAuditDTO audit,
            String reasonCode) {
        String status = taskStatus(facts(audit), null);
        return "CANCEL_REQUESTED".equals(status)
                || (StringUtils.hasText(reasonCode)
                && (reasonCode.contains("UNCONFIRMED")
                || "TERMINATION_ACK_INVALID".equals(reasonCode)
                || "TERMINATION_OPERATION_PENDING".equals(reasonCode)));
    }

    private RuntimeRequestAuditService.TaskEvidence evidence(
            RuntimeTaskFactsDTO facts,
            String result) {
        return new RuntimeRequestAuditService.TaskEvidence(
                facts.getTaskId(), facts.getStatus(), facts.getTerminal(), facts.getSanitizedErrorCode(),
                null, null, facts.getPhysicalWorkerId(), facts.getModelConfigId(), facts.getModelVariant(),
                facts.getRequestedToolCount(), facts.getEffectiveToolCount(), facts.getToolScopeKind(),
                facts.getToolScopeSource(), facts.getRequestedFunctionCount(), facts.getEffectiveFunctionCount(),
                facts.getFunctionScopeSource(), facts.getTaskTokenFunctionScopeEmpty(), facts.getTaskTokenStatus(),
                facts.getRuntimeDispatched(), facts.getModelDispatched(), facts.getBusinessFunctionDispatched(),
                facts.getDispatchCount(), facts.getRetryCount(), facts.getRecoveryCount(), result);
    }

    private RuntimeRequestAuditService.TaskEvidence terminationEvidence(
            String taskId,
            String physicalWorkerId,
            RuntimeTaskFactsDTO facts,
            RuntimeTaskClosureProvider.TerminationResult result,
            RuntimeTaskTerminationOutcome outcome) {
        String requestResult = outcome == RuntimeTaskTerminationOutcome.ALREADY_TERMINAL
                ? "ALREADY_TERMINAL" : "TERMINATION_REQUESTED";
        if (facts != null) {
            return evidence(facts, requestResult);
        }
        return new RuntimeRequestAuditService.TaskEvidence(
                taskId,
                stableText(result.providerStatus()),
                outcome == RuntimeTaskTerminationOutcome.ALREADY_TERMINAL ? true : null,
                result.sanitizedErrorCode(),
                null,
                null,
                physicalWorkerId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                requestResult);
    }

    private String sanitizedCode(RuntimeException error, String fallback) {
        return stableCode(error != null ? error.getMessage() : null, fallback);
    }

    private String stableCode(String value, String fallback) {
        String code = StringUtils.hasText(value) ? value.trim() : fallback;
        return StringUtils.hasText(code) && code.matches("[A-Z][A-Z0-9_]{2,127}")
                ? code : fallback;
    }

    private String stableText(String value) {
        return StringUtils.hasText(value) ? value.trim() : UNKNOWN;
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private RuntimeAuditSideEffectsDTO noSideEffects() {
        return RuntimeAuditSideEffectsDTO.builder()
                .accessTokenIssued(false)
                .runtimeTokenIssued(false)
                .taskTokenIssued(false)
                .taskCreated(false)
                .contextCreated(false)
                .sessionCreated(false)
                .workerCommandDispatched(false)
                .modelDispatched(false)
                .businessFunctionDispatched(false)
                .retryTriggered(false)
                .recoveryTriggered(false)
                .terminationTriggered(false)
                .reconciliationTriggered(false)
                .provisioningResourceChanged(false)
                .build();
    }
}
