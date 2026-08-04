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
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import com.foggy.navigator.spi.task.RuntimeTaskClosureProvider;
import com.foggy.navigator.spi.lifecycle.TaskLifecycleProjectionPort;
import org.springframework.lang.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private final VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;

    @Autowired(required = false)
    @Nullable
    private TaskLifecycleProjectionPort lifecycleProjection;

    @Autowired
    public RuntimeTaskClosureService(
            RuntimeStateAuditService stateAuditService,
            List<RuntimeTaskClosureProvider> providers,
            RuntimeRequestAuditService requestAuditService,
            RuntimeTerminationAcceptanceCoordinator acceptanceCoordinator,
            RuntimeTerminationOutboxDispatcher outboxDispatcher,
            VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority) {
        this.stateAuditService = stateAuditService;
        this.providers = providers;
        this.requestAuditService = requestAuditService;
        this.acceptanceCoordinator = acceptanceCoordinator;
        this.outboxDispatcher = outboxDispatcher;
        this.serverAuthority = Objects.requireNonNull(
                serverAuthority, "serverAuthority must not be null");
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
                                acceptanceCoordinator),
                testServerAuthority());
    }

    RuntimeTaskClosureService(
            RuntimeStateAuditService stateAuditService,
            List<RuntimeTaskClosureProvider> providers,
            RuntimeRequestAuditService requestAuditService) {
        this(stateAuditService, providers, requestAuditService, null);
    }

    private static VerifiedCommandAuthorizationDecision.ServerAuthority testServerAuthority() {
        return new VerifiedCommandAuthorizationDecision.ServerAuthority(
                "runtime-termination-test-v1", Clock.systemUTC(), Duration.ofMinutes(5));
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

        RuntimeStateAuditService.OwnedRuntimeTask owned = null;
        RuntimeTaskAuditDTO audit = null;
        try {
            requireTerminationFields(taskId, expectedPhysicalWorkerId, reason);
            if (!Objects.equals(taskId, confirmTaskId)) {
                throw new IllegalArgumentException("CONFIRM_TASK_ID_MISMATCH");
            }
            owned = stateAuditService.requireOwnedTask(
                    appKey, appSecret, upstreamUserId, taskId);
            requireExpectedWorker(owned, expectedPhysicalWorkerId);
        } catch (RuntimeException identityFailure) {
            audit = StringUtils.hasText(taskId)
                    ? safeAudit(appKey, appSecret, upstreamUserId, taskId) : null;
            String code = sanitizedCode(
                    identityFailure, "TERMINATION_PREFLIGHT_REJECTED");
            return terminationResponse(
                    clientRequestId, taskId, false, audit,
                    clean(expectedPhysicalWorkerId),
                    RuntimeTaskTerminationOutcome.REJECTED,
                    code, false, false, false, false, code, false);
        }

        RuntimeTerminationCommandAuthorization commandAuthorization;
        try {
            commandAuthorization = RuntimeTerminationCommandAuthorization.issue(
                    serverAuthority, owned, upstreamUserId, clientRequestId);
        } catch (RuntimeException authorizationFailure) {
            audit = safeAudit(appKey, appSecret, upstreamUserId, taskId);
            String code = sanitizedCode(
                    authorizationFailure, "TERMINATION_AUTHORIZATION_REJECTED");
            return terminationResponse(
                    clientRequestId, taskId, false, audit,
                    owned.physicalWorkerId(),
                    RuntimeTaskTerminationOutcome.REJECTED,
                    code, false, false, false, false, code, false);
        }

        RuntimeRequestAuditService.TaskOperationRegistration registration = null;
        if (requestAuditService.terminationRequestReceiptEnabled()) {
            try {
                registration = requestAuditService.beginTaskOperationIdempotent(
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
            if (registration.existing()) {
                return replayTermination(
                        appKey, appSecret, upstreamUserId, clientRequestId, taskId,
                        expectedPhysicalWorkerId);
            }
        }

        RuntimeRequestAuditService.AuditHandle requestAudit =
                registration != null ? registration.handle() : null;
        boolean receiptPersisted = requestAudit != null;
        boolean durableTerminationAccepted = false;
        RuntimeTaskClosureProvider selectedProvider;
        try {
            audit = stateAuditService.auditTask(
                    appKey, appSecret, upstreamUserId, taskId);
            selectedProvider = provider(owned);
            if (owned.terminal() || Boolean.TRUE.equals(canonicalTerminal(audit))) {
                completeAlreadyTerminal(
                        requestAudit, taskId, owned.physicalWorkerId(), facts(audit));
                return terminationResponse(
                        clientRequestId, taskId, false, audit,
                        owned.physicalWorkerId(),
                        RuntimeTaskTerminationOutcome.ALREADY_TERMINAL,
                        "TASK_ALREADY_TERMINAL", true, false, false,
                        false, null, receiptPersisted);
            }
            RuntimeTaskClosureProvider.TerminationReadiness readiness =
                    selectedProvider.inspect(taskId, owned.physicalWorkerId());
            if (readiness == null || !readiness.terminateAllowed()) {
                audit = safeAudit(appKey, appSecret, upstreamUserId, taskId);
                if (Boolean.TRUE.equals(canonicalTerminal(audit))) {
                    completeAlreadyTerminal(
                            requestAudit, taskId, owned.physicalWorkerId(), facts(audit));
                    return terminationResponse(
                            clientRequestId, taskId, false, audit,
                            owned.physicalWorkerId(),
                            RuntimeTaskTerminationOutcome.ALREADY_TERMINAL,
                            "TASK_ALREADY_TERMINAL", true, false, false,
                            false, null, receiptPersisted);
                }
                String code = readinessReason(readiness, facts(audit));
                if (requestAudit != null) {
                    requestAuditService.taskOperationFailed(requestAudit, code);
                }
                return terminationResponse(
                        clientRequestId, taskId, false, audit,
                        owned.physicalWorkerId(),
                        RuntimeTaskTerminationOutcome.REJECTED,
                        code, false, false, false, false, code,
                        receiptPersisted);
            }
            if (acceptanceCoordinator != null
                    && requestAuditService.terminationRequestReceiptEnabled()) {
                acceptanceCoordinator.accept(
                        clientRequestId, appKey, appSecret,
                        upstreamUserId, reason, selectedProvider,
                        owned, commandAuthorization);
                durableTerminationAccepted = true;
            }
        } catch (RuntimeException admissionFailure) {
            String code = sanitizedCode(
                    admissionFailure, "TERMINATION_PREFLIGHT_REJECTED");
            audit = safeAudit(appKey, appSecret, upstreamUserId, taskId);
            if ("TASK_ALREADY_TERMINAL".equals(code)) {
                completeAlreadyTerminal(
                        requestAudit, taskId, owned.physicalWorkerId(), facts(audit));
                return terminationResponse(
                        clientRequestId, taskId, false, audit,
                        owned.physicalWorkerId(),
                        RuntimeTaskTerminationOutcome.ALREADY_TERMINAL,
                        code, true, false, false, false, null,
                        receiptPersisted);
            }
            if (requestAudit != null) {
                requestAuditService.taskOperationFailed(requestAudit, code);
            }
            return terminationResponse(
                    clientRequestId, taskId, false, audit,
                    owned.physicalWorkerId(),
                    RuntimeTaskTerminationOutcome.REJECTED,
                    code, false, false, false, false, code,
                    receiptPersisted);
        }

        RuntimeTaskClosureProvider.TerminationResult providerResult = null;
        try {
            RuntimeStateAuditService.OwnedRuntimeTask effectOwned =
                    stateAuditService.requireOwnedTask(
                            appKey, appSecret, upstreamUserId, taskId);
            commandAuthorization.require(
                    serverAuthority, effectOwned,
                    upstreamUserId, clientRequestId);
            boolean receiptBacked = acceptanceCoordinator != null
                    && requestAuditService.terminationRequestReceiptEnabled();
            boolean recoveryCapable = receiptBacked
                    && outboxDispatcher.recoveryCapable();
            if (receiptBacked && !recoveryCapable) {
                var authorization = outboxDispatcher.authorize(clientRequestId);
                if (!authorization.providerCallAuthorized()) {
                    return replayTermination(
                            appKey, appSecret, upstreamUserId,
                            clientRequestId, taskId,
                            expectedPhysicalWorkerId);
                }
            }
            if (recoveryCapable) {
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
                        taskId, effectOwned.ownerUserId(), effectOwned.tenantId(),
                        effectOwned.physicalWorkerId(), reason.trim(),
                        clientRequestId, false);
                if (receiptBacked) {
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
                    providerResult.sanitizedErrorCode(), receiptPersisted);
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
                        reasonCode,
                        receiptPersisted);
            }
            if (durableTerminationAccepted
                    || terminationMayBeInFlight(audit, reasonCode)) {
                return terminationResponse(
                        clientRequestId, taskId, false, audit,
                        owned != null ? owned.physicalWorkerId() : null,
                        RuntimeTaskTerminationOutcome.PROCESSING, reasonCode,
                        false, false, false, true, reasonCode,
                        receiptPersisted);
            }
            if (requestAudit != null) {
                requestAuditService.taskOperationFailed(requestAudit, reasonCode);
            }
            return terminationResponse(
                    clientRequestId, taskId, false, audit,
                    owned != null ? owned.physicalWorkerId() : null,
                    RuntimeTaskTerminationOutcome.REJECTED, reasonCode,
                    false, false, false, false, reasonCode,
                    receiptPersisted);
        }
    }

    /**
     * Access-token compatibility entry for the OpenAPI Agent cancel route.
     * This method has no Controller caller until S4-03B2A3. It accepts only
     * Tasks that the existing provider admission proves are ENFORCED and
     * dispatches exclusively through the lifecycle termination outbox.
     */
    public AgentTerminationResult terminateAgentTaskWithRuntimeAccess(
            String appKey,
            String accessToken,
            String upstreamUserId,
            @Nullable String suppliedClientRequestId,
            String pathAgentId,
            String taskId) {
        String clientRequestId = canonicalAgentTerminationClientRequestId(
                suppliedClientRequestId);
        RuntimeStateAuditService.OwnedRuntimeTask owned =
                stateAuditService.requireOwnedAgentTaskByAccessToken(
                        appKey, accessToken, upstreamUserId,
                        pathAgentId, taskId);
        RuntimeTerminationCommandAuthorization authorization =
                RuntimeTerminationCommandAuthorization.issueRuntimeAccessAgent(
                        serverAuthority, owned, upstreamUserId,
                        pathAgentId, clientRequestId);
        String terminalStatus = canonicalAgentTerminalStatus(owned);
        if (terminalStatus != null) {
            return AgentTerminationResult.alreadyTerminal(
                    clientRequestId, owned.taskId(),
                    owned.logicalAgentId(), terminalStatus);
        }
        RuntimeTaskClosureProvider selectedProvider = provider(owned);
        RuntimeTaskClosureProvider.TerminationReadiness readiness;
        try {
            readiness = selectedProvider.inspect(
                    owned.taskId(), owned.physicalWorkerId());
        } catch (RuntimeException unavailable) {
            throw new IllegalStateException(
                    "RUNTIME_AGENT_TERMINATION_OBSERVATION_UNAVAILABLE");
        }
        if (readiness == null || !readiness.terminateAllowed()) {
            throw new IllegalStateException(stableCode(
                    readiness != null ? readiness.blockedReason() : null,
                    "RUNTIME_AGENT_TERMINATION_NOT_READY"));
        }
        if (acceptanceCoordinator == null
                || outboxDispatcher == null
                || !outboxDispatcher.recoveryCapable()) {
            throw new IllegalStateException(
                    "RUNTIME_AGENT_TERMINATION_AUTHORITY_UNAVAILABLE");
        }

        boolean durableAccepted = false;
        try {
            acceptanceCoordinator.acceptAgent(
                    clientRequestId, appKey, accessToken,
                    upstreamUserId, pathAgentId,
                    "openapi-agent-cancel", selectedProvider,
                    owned, authorization);
            durableAccepted = true;

            RuntimeStateAuditService.OwnedRuntimeTask effectOwned =
                    stateAuditService.requireOwnedAgentTaskByAccessToken(
                            appKey, accessToken, upstreamUserId,
                            pathAgentId, taskId);
            authorization.requireRuntimeAccessAgent(
                    serverAuthority, effectOwned, upstreamUserId,
                    pathAgentId, clientRequestId);
            RuntimeTaskClosureProvider.TerminationResult providerResult =
                    outboxDispatcher.dispatch(
                            clientRequestId, "openapi-agent-cancel");
            RuntimeStateAuditService.OwnedRuntimeTask current =
                    stateAuditService.requireOwnedAgentTaskByAccessToken(
                            appKey, accessToken, upstreamUserId,
                            pathAgentId, taskId);
            terminalStatus = canonicalAgentTerminalStatus(current);
            if (terminalStatus != null) {
                return AgentTerminationResult.alreadyTerminal(
                        clientRequestId, current.taskId(),
                        current.logicalAgentId(), terminalStatus);
            }
            return AgentTerminationResult.accepted(
                    clientRequestId, current.taskId(),
                    current.logicalAgentId(),
                    providerResult != null
                            && providerResult.idempotentReplay(),
                    providerResult == null
                            || providerResult.reconcileRequired());
        } catch (RuntimeException failure) {
            if (durableAccepted) {
                return AgentTerminationResult.accepted(
                        clientRequestId, owned.taskId(),
                        owned.logicalAgentId(), false, true);
            }
            throw failure;
        }
    }

    static String canonicalAgentTerminationClientRequestId(
            @Nullable String suppliedClientRequestId) {
        if (!StringUtils.hasText(suppliedClientRequestId)) {
            return UUID.randomUUID().toString();
        }
        String supplied = suppliedClientRequestId;
        try {
            if (supplied.length() != 36) {
                throw new IllegalArgumentException();
            }
            String canonical = UUID.fromString(supplied).toString();
            if (!canonical.equals(supplied)) {
                throw new IllegalArgumentException();
            }
            return canonical;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "AGENT_CANCEL_CLIENT_REQUEST_ID_INVALID", invalid);
        }
    }

    @Nullable
    private String canonicalAgentTerminalStatus(
            RuntimeStateAuditService.OwnedRuntimeTask owned) {
        if (owned == null || !owned.terminal()) return null;
        String status = stableText(owned.status()).toUpperCase(Locale.ROOT);
        return switch (status) {
            case "COMPLETED" -> "COMPLETED";
            case "ABORTED", "CANCELLED", "CANCELED" -> "ABORTED";
            case "FAILED", "REJECTED", "TIMED_OUT", "TIMEOUT" -> "FAILED";
            default -> throw new IllegalStateException(
                    "RUNTIME_AGENT_TERMINAL_STATUS_UNSUPPORTED");
        };
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
                    result.sanitizedErrorCode(), false);
        } catch (RuntimeException error) {
            String reasonCode = sanitizedCode(error, "TERMINATION_DRY_RUN_REJECTED");
            if (audit == null && StringUtils.hasText(taskId)) {
                audit = safeAudit(appKey, appSecret, upstreamUserId, taskId);
            }
            return terminationResponse(
                    clientRequestId, taskId, true, audit,
                    owned != null ? owned.physicalWorkerId() : null,
                    RuntimeTaskTerminationOutcome.REJECTED, reasonCode,
                    false, false, false, false, reasonCode, false);
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
                    false, false, true, true, null, true);
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
                    "CLIENT_REQUEST_ID_OPERATION_MISMATCH", true);
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
                    "CLIENT_REQUEST_ID_OPERATION_MISMATCH", true);
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
                snapshot.sanitizedErrorCode(), true);
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
        // The lifecycle projection proves only core-owned closure. A terminal
        // reconciliation conclusion also requires the independently observed
        // token and registration cleanup facts, so a stale audit projection
        // cannot turn an incomplete terminal into a no-action response.
        boolean cleanupComplete = terminalCleanupComplete(audit);
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
            // A terminal lifecycle snapshot with a recognized persisted task
            // status still needs its tombstone/cleanup fence. Do not describe
            // that as a status problem just because typedTerminal() requires
            // every cleanup component as well.
            boolean canonicalStatusTerminal = terminalTaskStatus(
                    ownerProjection.get().canonicalTaskStatus());
            return reconciliationResponse(
                    originalClientRequestId, taskId, audit, snapshot,
                    RuntimeTaskReconciliationState.AMBIGUOUS,
                    originalOutcome,
                    stableText(snapshot.status()),
                    canonicalStatusTerminal
                            ? "TERMINAL_CLEANUP_INCOMPLETE"
                            : "CANONICAL_STATUS_NOT_TERMINAL",
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
            String sanitizedErrorCode,
            boolean receiptPersisted) {
        boolean receiptEnabled =
                requestAuditService.terminationRequestReceiptEnabled();
        boolean durableReceipt = !dryRun && receiptEnabled && receiptPersisted;
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
                .terminationRequestReceiptPersisted(durableReceipt)
                .requestReconciliationAvailable(durableReceipt)
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
                && Boolean.TRUE.equals(facts.getLifecycleCanonicalTerminal())
                && Boolean.TRUE.equals(facts.getTerminalTombstonePresent())
                && Boolean.TRUE.equals(facts.getLifecycleCleanupComplete())
                && tokenCleared(facts.getTaskTokenStatus())
                && Boolean.FALSE.equals(facts.getActiveTaskRegistrationPresent());
    }

    private boolean terminalTaskStatus(String status) {
        return switch (stableText(status)) {
            case "COMPLETED", "FAILED", "ABORTED", "CANCELLED", "REJECTED",
                 "TIMED_OUT", "TIMEOUT" -> true;
            default -> false;
        };
    }

    /** Only a durable revoke is public proof of task-token closure. */
    private boolean tokenCleared(String status) {
        return "REVOKED".equalsIgnoreCase(status);
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

    private void completeAlreadyTerminal(
            RuntimeRequestAuditService.AuditHandle requestAudit,
            String taskId,
            String physicalWorkerId,
            RuntimeTaskFactsDTO facts) {
        if (requestAudit == null) {
            return;
        }
        requestAuditService.taskOperationCompleted(
                requestAudit,
                terminationEvidence(
                        taskId,
                        physicalWorkerId,
                        facts,
                        new RuntimeTaskClosureProvider.TerminationResult(
                                true, false, false, false,
                                facts != null ? facts.getStatus() : UNKNOWN,
                                null, null),
                        RuntimeTaskTerminationOutcome.ALREADY_TERMINAL),
                false,
                false);
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

    public record AgentTerminationResult(
            String clientRequestId,
            String taskId,
            String agentId,
            boolean idempotentReplay,
            boolean reconcileRequired,
            @Nullable String terminalStatus) {
        private static final Set<String> TERMINAL_STATUSES =
                Set.of("COMPLETED", "FAILED", "ABORTED");

        public AgentTerminationResult {
            String canonicalRequestId = null;
            if (StringUtils.hasText(clientRequestId)) {
                try {
                    canonicalRequestId = UUID.fromString(
                            clientRequestId).toString();
                } catch (IllegalArgumentException ignored) {
                    // Stable validation below deliberately hides parser details.
                }
            }
            if (!clientRequestIdEquals(
                    clientRequestId, canonicalRequestId)) {
                throw new IllegalArgumentException(
                        "AGENT_CANCEL_CLIENT_REQUEST_ID_INVALID");
            }
            if (!StringUtils.hasText(taskId)
                    || !StringUtils.hasText(agentId)) {
                throw new IllegalArgumentException(
                        "agent termination identity is required");
            }
            if (terminalStatus != null
                    && !TERMINAL_STATUSES.contains(terminalStatus)) {
                throw new IllegalArgumentException(
                        "agent termination terminal status is invalid");
            }
        }

        static AgentTerminationResult accepted(
                String clientRequestId,
                String taskId,
                String agentId,
                boolean idempotentReplay,
                boolean reconcileRequired) {
            return new AgentTerminationResult(
                    clientRequestId, taskId, agentId,
                    idempotentReplay, reconcileRequired, null);
        }

        static AgentTerminationResult alreadyTerminal(
                String clientRequestId,
                String taskId,
                String agentId,
                String terminalStatus) {
            return new AgentTerminationResult(
                    clientRequestId, taskId, agentId,
                    false, false, terminalStatus);
        }

        public boolean canonicalTerminal() {
            return terminalStatus != null;
        }

        public String taskStatus() {
            return terminalStatus == null
                    ? "CANCEL_REQUESTED" : terminalStatus;
        }

        @Override
        public String toString() {
            return "AgentTerminationResult[safe]";
        }

        private static boolean clientRequestIdEquals(
                String supplied, String canonical) {
            return supplied != null && supplied.equals(canonical);
        }
    }
}

/**
 * Process-local, content-free authorization for one exact typed runtime termination command.
 *
 * <p>This capability is deliberately not a receipt or an effect gate. It binds the existing
 * runtime receipt/intent/outbox admission to server-resolved identity without persisting command
 * metadata or changing replay ownership.</p>
 */
final class RuntimeTerminationCommandAuthorization {

    private static final String CLIENT_SURFACE = "NAVIGATOR_RUNTIME_API";
    private static final String ROUTE_ID = "/api/v1/open/runtime/task-terminate";
    private static final String AGENT_CLIENT_SURFACE = "NAVIGATOR_OPEN_API";
    private static final String AGENT_ROUTE_ID =
            "/api/v1/open/agents/{agentId}/tasks/{taskId}/cancel";
    private static final String ACTION_ID = "task.terminate";
    private static final String TENANT_PREFIX = "navi.tenant.present.v1:";
    private static final String ACTOR_DOMAIN =
            "navi.runtime-termination-client-app-principal.v1";
    private static final String UPSTREAM_DOMAIN =
            "navi.runtime-termination-upstream-reference.v1";
    private static final String EFFECT_DOMAIN =
            "navi.runtime-termination-effect-scope.v1";
    private static final String EFFECT_PREFIX = "RUNTIME_TASK_TERMINATE_SCOPE_SHA256_V1:";
    private static final String AGENT_ACTOR_DOMAIN =
            "navi.runtime-access-agent-termination-principal.v1";
    private static final String AGENT_UPSTREAM_DOMAIN =
            "navi.runtime-access-agent-termination-upstream.v1";
    private static final String AGENT_EFFECT_DOMAIN =
            "navi.runtime-access-agent-termination-effect.v1";
    private static final String AGENT_EFFECT_PREFIX =
            "RUNTIME_ACCESS_AGENT_TERMINATE_SCOPE_SHA256_V1:";
    private static final String AUTHORIZATION_BINDING_CLAIM_DOMAIN =
            "navi.termination-authorization-binding-claim.v1";

    private final CanonicalCommandEnvelope envelope;
    private final VerifiedCommandAuthorizationDecision decision;
    @Nullable
    private final Identity identity;
    @Nullable
    private final RuntimeAccessAgentIdentity runtimeAccessAgentIdentity;

    private RuntimeTerminationCommandAuthorization(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision,
            Identity identity) {
        this.envelope = envelope;
        this.decision = decision;
        this.identity = identity;
        this.runtimeAccessAgentIdentity = null;
    }

    private RuntimeTerminationCommandAuthorization(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision,
            RuntimeAccessAgentIdentity identity) {
        this.envelope = envelope;
        this.decision = decision;
        this.identity = null;
        this.runtimeAccessAgentIdentity = identity;
    }

    static RuntimeTerminationCommandAuthorization issue(
            VerifiedCommandAuthorizationDecision.ServerAuthority authority,
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            String suppliedUpstreamUserId,
            String clientRequestId) {
        Objects.requireNonNull(authority, "server authority must not be null");
        Objects.requireNonNull(owned, "owned runtime task must not be null");
        Identity identity = identity(
                owned, suppliedUpstreamUserId, clientRequestId);
        CanonicalCommandEnvelope.CommandBinding binding = binding(identity);
        VerifiedCommandAuthorizationDecision decision = authority.issue(binding);
        CanonicalCommandEnvelope envelope = new CanonicalCommandEnvelope(
                CanonicalCommandEnvelope.SCHEMA_VERSION,
                binding,
                decision.metadata());
        RuntimeTerminationCommandAuthorization authorization =
                new RuntimeTerminationCommandAuthorization(
                        envelope, decision, identity);
        authorization.require(
                authority, owned, suppliedUpstreamUserId, clientRequestId);
        return authorization;
    }

    static RuntimeTerminationCommandAuthorization issueRuntimeAccessAgent(
            VerifiedCommandAuthorizationDecision.ServerAuthority authority,
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            String suppliedUpstreamUserId,
            String pathAgentId,
            String clientRequestId) {
        Objects.requireNonNull(authority, "server authority must not be null");
        Objects.requireNonNull(owned, "owned runtime task must not be null");
        RuntimeAccessAgentIdentity identity = runtimeAccessAgentIdentity(
                owned, suppliedUpstreamUserId, pathAgentId,
                clientRequestId);
        CanonicalCommandEnvelope.CommandBinding binding =
                runtimeAccessAgentBinding(identity);
        VerifiedCommandAuthorizationDecision decision =
                authority.issue(binding);
        CanonicalCommandEnvelope envelope = new CanonicalCommandEnvelope(
                CanonicalCommandEnvelope.SCHEMA_VERSION,
                binding,
                decision.metadata());
        RuntimeTerminationCommandAuthorization authorization =
                new RuntimeTerminationCommandAuthorization(
                        envelope, decision, identity);
        authorization.requireRuntimeAccessAgent(
                authority, owned, suppliedUpstreamUserId,
                pathAgentId, clientRequestId);
        return authorization;
    }

    void require(
            VerifiedCommandAuthorizationDecision.ServerAuthority verifier,
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            String suppliedUpstreamUserId,
            String clientRequestId) {
        Objects.requireNonNull(verifier, "server authority must not be null");
        CanonicalCommandEnvelope.CommandBinding verified =
                verifier.requireVerified(envelope, decision);
        if (identity == null) {
            throw rejected("TERMINATION_AUTHORIZATION_BINDING_CONFLICT");
        }
        Identity current = identity(
                owned, suppliedUpstreamUserId, clientRequestId);
        if (!verified.equals(binding(identity))
                || !identity.equals(current)) {
            throw rejected("TERMINATION_AUTHORIZATION_BINDING_CONFLICT");
        }
    }

    void requireRuntimeAccessAgent(
            VerifiedCommandAuthorizationDecision.ServerAuthority verifier,
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            String suppliedUpstreamUserId,
            String pathAgentId,
            String clientRequestId) {
        Objects.requireNonNull(verifier, "server authority must not be null");
        CanonicalCommandEnvelope.CommandBinding verified =
                verifier.requireVerified(envelope, decision);
        if (runtimeAccessAgentIdentity == null) {
            throw rejected("TERMINATION_AUTHORIZATION_BINDING_CONFLICT");
        }
        RuntimeAccessAgentIdentity current = runtimeAccessAgentIdentity(
                owned, suppliedUpstreamUserId, pathAgentId,
                clientRequestId);
        if (!verified.equals(runtimeAccessAgentBinding(
                runtimeAccessAgentIdentity))
                || !runtimeAccessAgentIdentity.equals(current)) {
            throw rejected("TERMINATION_AUTHORIZATION_BINDING_CONFLICT");
        }
    }

    String authorizationBindingClaim() {
        return authorizationBindingClaim(envelope.binding());
    }

    CanonicalCommandEnvelope safeEnvelope() {
        return envelope;
    }

    private static Identity identity(
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            String suppliedUpstreamUserId,
            String clientRequestId) {
        Objects.requireNonNull(owned, "owned runtime task must not be null");
        String upstreamUserId = requireText(
                suppliedUpstreamUserId, "upstream user ID").trim();
        String resolvedUpstreamUserId = requireText(
                owned.upstreamUserId(), "resolved upstream user ID").trim();
        if (!upstreamUserId.equals(resolvedUpstreamUserId)) {
            throw rejected("TERMINATION_AUTHORIZATION_UPSTREAM_CONFLICT");
        }
        return new Identity(
                requireExactReference(clientRequestId, "client request ID"),
                requireText(owned.taskId(), "task ID"),
                optionalText(owned.sessionId()),
                optionalText(owned.providerTaskId()),
                optionalText(owned.logicalAgentId()),
                requireText(owned.ownerUserId(), "owner user ID"),
                requireText(owned.tenantId(), "tenant ID"),
                requireText(owned.clientAppId(), "ClientApp ID"),
                requireText(owned.credentialId(), "credential ID"),
                resolvedUpstreamUserId,
                optionalText(owned.providerType()),
                optionalText(owned.physicalWorkerId()),
                optionalText(owned.modelConfigId()));
    }

    private static CanonicalCommandEnvelope.CommandBinding binding(Identity identity) {
        String tenantReference = TENANT_PREFIX + identity.tenantId();
        requireReferenceLength(tenantReference, "tenant reference");
        String upstreamReference = digest(
                UPSTREAM_DOMAIN,
                identity.tenantId(),
                identity.clientAppId(),
                identity.upstreamUserId());
        CanonicalCommandEnvelope.Target target = new CanonicalCommandEnvelope.Target(
                CanonicalCommandEnvelope.TargetKind.TASK,
                identity.taskId(),
                identity.logicalAgentId(),
                identity.providerType(),
                identity.physicalWorkerId(),
                identity.modelConfigId(),
                identity.taskId(),
                identity.sessionId());
        CanonicalCommandEnvelope.Effect effect = new CanonicalCommandEnvelope.Effect(
                ACTION_ID,
                EFFECT_PREFIX + digest(
                        EFFECT_DOMAIN,
                        identity.tenantId(), identity.ownerUserId(),
                        identity.clientAppId(), upstreamReference,
                        identity.taskId(), identity.sessionId(),
                        identity.providerTaskId(), identity.logicalAgentId(),
                        identity.providerType(), identity.physicalWorkerId(),
                        identity.modelConfigId()));
        return new CanonicalCommandEnvelope.CommandBinding(
                CanonicalCommandEnvelope.CommandKind.TERMINATE,
                new CanonicalCommandEnvelope.Ingress(
                        CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                        CLIENT_SURFACE,
                        ROUTE_ID),
                new CanonicalCommandEnvelope.Request(
                        identity.clientRequestId(),
                        identity.clientRequestId(),
                        identity.clientRequestId()),
                new CanonicalCommandEnvelope.Actor(
                        CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                        AuthorizationPrincipalType.CLIENT_APP,
                        AuthorizationCredentialLane.CLIENT_APP_RUNTIME_CREDENTIAL,
                        digest(
                                ACTOR_DOMAIN,
                                identity.tenantId(),
                                identity.clientAppId(),
                                identity.credentialId()),
                        null),
                new CanonicalCommandEnvelope.Ownership(
                        tenantReference,
                        identity.ownerUserId(),
                        identity.clientAppId(),
                        upstreamReference),
                target,
                effect);
    }

    private static RuntimeAccessAgentIdentity runtimeAccessAgentIdentity(
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            String suppliedUpstreamUserId,
            String pathAgentId,
            String clientRequestId) {
        Objects.requireNonNull(owned, "owned runtime task must not be null");
        String suppliedUpstream = requireText(
                suppliedUpstreamUserId, "upstream user ID").trim();
        String resolvedUpstream = requireText(
                owned.upstreamUserId(), "resolved upstream user ID").trim();
        if (!suppliedUpstream.equals(resolvedUpstream)) {
            throw rejected("TERMINATION_AUTHORIZATION_UPSTREAM_CONFLICT");
        }
        String requestedAgentId = requireText(
                pathAgentId, "path Agent ID").trim();
        String logicalAgentId = requireText(
                owned.logicalAgentId(), "logical Agent ID").trim();
        if (!requestedAgentId.equals(logicalAgentId)) {
            throw rejected("TERMINATION_AUTHORIZATION_AGENT_CONFLICT");
        }
        return new RuntimeAccessAgentIdentity(
                requireExactReference(clientRequestId, "client request ID"),
                requireText(owned.taskId(), "task ID"),
                optionalText(owned.sessionId()),
                optionalText(owned.providerTaskId()),
                logicalAgentId,
                requireText(owned.ownerUserId(), "owner user ID"),
                requireText(owned.tenantId(), "tenant ID"),
                requireText(owned.clientAppId(), "ClientApp ID"),
                requireText(owned.credentialId(), "credential ID"),
                resolvedUpstream,
                optionalText(owned.providerType()),
                optionalText(owned.physicalWorkerId()),
                optionalText(owned.modelConfigId()));
    }

    private static CanonicalCommandEnvelope.CommandBinding
    runtimeAccessAgentBinding(RuntimeAccessAgentIdentity identity) {
        String tenantReference = TENANT_PREFIX + identity.tenantId();
        requireReferenceLength(tenantReference, "tenant reference");
        String upstreamReference = digest(
                AGENT_UPSTREAM_DOMAIN,
                identity.tenantId(),
                identity.clientAppId(),
                identity.upstreamUserId());
        CanonicalCommandEnvelope.Target target =
                new CanonicalCommandEnvelope.Target(
                        CanonicalCommandEnvelope.TargetKind.TASK,
                        identity.taskId(),
                        identity.logicalAgentId(),
                        identity.providerType(),
                        identity.physicalWorkerId(),
                        identity.modelConfigId(),
                        identity.taskId(),
                        identity.sessionId());
        CanonicalCommandEnvelope.Effect effect =
                new CanonicalCommandEnvelope.Effect(
                        ACTION_ID,
                        AGENT_EFFECT_PREFIX + digest(
                                AGENT_EFFECT_DOMAIN,
                                identity.tenantId(),
                                identity.ownerUserId(),
                                identity.clientAppId(),
                                upstreamReference,
                                identity.taskId(),
                                identity.sessionId(),
                                identity.providerTaskId(),
                                identity.logicalAgentId(),
                                identity.providerType(),
                                identity.physicalWorkerId(),
                                identity.modelConfigId()));
        return new CanonicalCommandEnvelope.CommandBinding(
                CanonicalCommandEnvelope.CommandKind.TERMINATE,
                new CanonicalCommandEnvelope.Ingress(
                        CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                        AGENT_CLIENT_SURFACE,
                        AGENT_ROUTE_ID),
                new CanonicalCommandEnvelope.Request(
                        identity.clientRequestId(),
                        identity.clientRequestId(),
                        identity.clientRequestId()),
                new CanonicalCommandEnvelope.Actor(
                        CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                        AuthorizationPrincipalType.CLIENT_APP,
                        AuthorizationCredentialLane.CLIENT_APP_RUNTIME_ACCESS,
                        digest(
                                AGENT_ACTOR_DOMAIN,
                                identity.tenantId(),
                                identity.clientAppId(),
                                identity.credentialId()),
                        null),
                new CanonicalCommandEnvelope.Ownership(
                        tenantReference,
                        identity.ownerUserId(),
                        identity.clientAppId(),
                        upstreamReference),
                target,
                effect);
    }

    private static String authorizationBindingClaim(
            CanonicalCommandEnvelope.CommandBinding binding) {
        CanonicalCommandEnvelope.Ingress ingress = binding.ingress();
        CanonicalCommandEnvelope.Request request = binding.request();
        CanonicalCommandEnvelope.Actor actor = binding.actor();
        CanonicalCommandEnvelope.Ownership ownership = binding.ownership();
        CanonicalCommandEnvelope.Target target = binding.target();
        CanonicalCommandEnvelope.Effect effect = binding.effect();
        return digest(
                AUTHORIZATION_BINDING_CLAIM_DOMAIN,
                binding.commandKind().name(),
                ingress.ingress().name(),
                ingress.clientSurface(),
                ingress.routeId(),
                request.clientRequestId(),
                request.idempotencyKey(),
                request.correlationId(),
                actor.kind().name(),
                actor.principalType() != null
                        ? actor.principalType().name() : null,
                actor.lane() != null ? actor.lane().name() : null,
                actor.fingerprint(),
                actor.serverProcessAuthorityReference(),
                ownership.tenantReference(),
                ownership.ownerReference(),
                ownership.clientAppReference(),
                ownership.upstreamReference(),
                target.kind().name(),
                target.targetId(),
                target.logicalAgentId(),
                target.providerType(),
                target.physicalWorkerId(),
                target.modelConfigId(),
                target.taskId(),
                target.sessionId(),
                effect.actionId(),
                effect.effectScopeReference());
    }

    private static String digest(String domain, @Nullable String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigestField(digest, domain);
            for (String field : fields) {
                digest.update(field == null ? (byte) 0 : (byte) 1);
                if (field != null) {
                    updateDigestField(digest, field);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static void updateDigestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String requireExactReference(String value, String field) {
        if (value == null || value.isBlank()) {
            throw rejected("TERMINATION_AUTHORIZATION_IDENTITY_INCOMPLETE");
        }
        requireReferenceLength(value, field);
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw rejected("TERMINATION_AUTHORIZATION_IDENTITY_INVALID");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw rejected("TERMINATION_AUTHORIZATION_IDENTITY_INCOMPLETE");
        }
        String clean = value.trim();
        requireReferenceLength(clean, field);
        if (clean.chars().anyMatch(Character::isISOControl)) {
            throw rejected("TERMINATION_AUTHORIZATION_IDENTITY_INVALID");
        }
        return clean;
    }

    @Nullable
    private static String optionalText(@Nullable String value) {
        return StringUtils.hasText(value) ? requireText(value, "optional reference") : null;
    }

    private static void requireReferenceLength(String value, String field) {
        if (value.length() > CanonicalCommandEnvelope.MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds maximum length");
        }
    }

    private static SecurityException rejected(String safeCode) {
        return new SecurityException(safeCode);
    }

    @Override
    public String toString() {
        return "RuntimeTerminationCommandAuthorization[content-free]";
    }

    private record Identity(
            String clientRequestId,
            String taskId,
            @Nullable String sessionId,
            @Nullable String providerTaskId,
            @Nullable String logicalAgentId,
            String ownerUserId,
            String tenantId,
            String clientAppId,
            String credentialId,
            String upstreamUserId,
            @Nullable String providerType,
            @Nullable String physicalWorkerId,
            @Nullable String modelConfigId) {
    }

    private record RuntimeAccessAgentIdentity(
            String clientRequestId,
            String taskId,
            @Nullable String sessionId,
            @Nullable String providerTaskId,
            String logicalAgentId,
            String ownerUserId,
            String tenantId,
            String clientAppId,
            String credentialId,
            String upstreamUserId,
            @Nullable String providerType,
            @Nullable String physicalWorkerId,
            @Nullable String modelConfigId) {
    }
}
