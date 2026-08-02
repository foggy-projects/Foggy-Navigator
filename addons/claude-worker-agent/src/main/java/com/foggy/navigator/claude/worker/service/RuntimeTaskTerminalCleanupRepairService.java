package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.claude.worker.model.dto.RuntimeAuditSideEffectsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskFactsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskTerminalCleanupRepairDTO;
import com.foggy.navigator.claude.worker.model.enums.RuntimeTaskTerminalCleanupRepairOutcome;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupRepairPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * Public orchestration for the narrow, provider-neutral terminal-cleanup
 * repair contract. This service intentionally has no dependency on a Worker,
 * provider closure service, retry/recovery path, or task creation service.
 */
@Service
@RequiredArgsConstructor
public class RuntimeTaskTerminalCleanupRepairService {

    private static final String READY_REASON = "NAVIGATOR_TERMINAL_REPUBLISH_READY";
    private static final String DRY_RUN_REQUIRED =
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_DRY_RUN_REQUIRED";

    private final RuntimeStateAuditService stateAuditService;
    private final RuntimeRequestAuditService requestAuditService;
    private final TerminalCleanupRepairPort terminalCleanupRepairPort;

    public RuntimeTaskTerminalCleanupRepairDTO repair(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String clientRequestId,
            String taskId,
            String expectedPhysicalWorkerId,
            String confirmTaskId,
            boolean dryRun) {
        RuntimeStateAuditService.OwnedRuntimeTask owned = stateAuditService.requireOwnedTask(
                appKey, appSecret, upstreamUserId, taskId);
        requireExpectedWorker(owned, expectedPhysicalWorkerId);
        if (!dryRun && !Objects.equals(taskId, confirmTaskId)) {
            throw new IllegalArgumentException("CONFIRM_TASK_ID_MISMATCH");
        }
        return dryRun
                ? dryRun(appKey, appSecret, upstreamUserId, clientRequestId, owned)
                : confirm(appKey, appSecret, upstreamUserId, clientRequestId, owned);
    }

    private RuntimeTaskTerminalCleanupRepairDTO dryRun(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String clientRequestId,
            RuntimeStateAuditService.OwnedRuntimeTask owned) {
        RuntimeRequestAuditService.TerminalCleanupRepairRegistration registration =
                requestAuditService.beginTerminalCleanupRepair(
                        clientRequestId, appKey, appSecret, upstreamUserId, owned.taskId());
        RuntimeTaskAuditDTO audit = stateAuditService.auditTask(
                appKey, appSecret, upstreamUserId, owned.taskId());
        if (registration.existing() && registration.receipt().completed()) {
            return fromReceipt(clientRequestId, owned, audit, registration.receipt(), true, true);
        }

        // The dry-run is deliberately limited to the provider-neutral read-only
        // lifecycle preflight. It never invokes repair or a provider operation.
        TerminalCleanupRepairPort.TerminalCleanupRepairAssessment assessment =
                terminalCleanupRepairPort.assess(
                        new TerminalCleanupRepairPort.TerminalCleanupRepairAssessmentCommand(
                                owned.taskId(), owned.physicalWorkerId()));
        boolean ready = assessment != null
                && assessment.repairEligible()
                && READY_REASON.equals(assessment.safeReasonCode());
        RuntimeRequestAuditService.TerminalCleanupRepairReceipt receipt =
                requestAuditService.terminalCleanupRepairDryRunCompleted(
                        registration.handle(), evidence(audit), ready,
                        safeReason(assessment));
        return dryRunResponse(clientRequestId, owned, audit, assessment, receipt,
                registration.existing(), true);
    }

    private RuntimeTaskTerminalCleanupRepairDTO confirm(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String clientRequestId,
            RuntimeStateAuditService.OwnedRuntimeTask owned) {
        // Do not register a confirmation. A fresh id must not consume a durable
        // receipt; it has to be the exact id that completed a ready dry-run.
        RuntimeRequestAuditService.TerminalCleanupRepairReceipt prior =
                requestAuditService.findSelfTerminalCleanupRepair(
                                appKey, appSecret, upstreamUserId, clientRequestId)
                        .orElseThrow(() -> new IllegalArgumentException(DRY_RUN_REQUIRED));
        if (!Objects.equals(owned.taskId(), prior.taskId())) {
            throw new IllegalArgumentException(
                    "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_REPLAY_PROHIBITED");
        }
        if (prior.completed()) {
            if ("REPAIRED".equals(prior.status())) {
                RuntimeTaskAuditDTO audit = stateAuditService.auditTask(
                        appKey, appSecret, upstreamUserId, owned.taskId());
                return fromReceipt(clientRequestId, owned, audit, prior, true, false);
            }
            throw new IllegalArgumentException(
                    "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_NOT_READY");
        }
        // The receipt is the only authority that can open the mutation gate.
        // In particular, a rejected dry-run must never become a core repair
        // simply because a caller replays its request id.
        if (!prior.dryRunReady() || !READY_REASON.equals(prior.result())) {
            throw new IllegalArgumentException(
                    "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_NOT_READY");
        }

        // The SPI owns the only mutation. Its same-id replay is provider-free;
        // a different id is rejected by the durable lifecycle receipt.
        TerminalCleanupRepairPort.TerminalCleanupRepairResult result =
                terminalCleanupRepairPort.repair(
                        new TerminalCleanupRepairPort.TerminalCleanupRepairCommand(
                                owned.taskId(), owned.physicalWorkerId(), clientRequestId));
        RuntimeTaskAuditDTO audit = stateAuditService.auditTask(
                appKey, appSecret, upstreamUserId, owned.taskId());
        RuntimeRequestAuditService.TerminalCleanupRepairCompletion completion =
                requestAuditService.terminalCleanupRepairCompleted(
                        new RuntimeRequestAuditService.AuditHandle(clientRequestId),
                        evidence(audit), result != null && result.repairAccepted(),
                        safeReason(result));
        if (completion.idempotentReplay()) {
            // A concurrent confirmer may have entered the provider-neutral
            // core path with a stale ready receipt.  Its public answer must
            // still come from the locked, terminal audit receipt rather than
            // from that stale core return value.
            return fromReceipt(clientRequestId, owned, audit, completion.receipt(), true, false);
        }
        return confirmResponse(clientRequestId, owned, audit, result,
                completion.receipt(), false);
    }

    private RuntimeTaskTerminalCleanupRepairDTO dryRunResponse(
            String clientRequestId,
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            RuntimeTaskAuditDTO audit,
            TerminalCleanupRepairPort.TerminalCleanupRepairAssessment assessment,
            RuntimeRequestAuditService.TerminalCleanupRepairReceipt receipt,
            boolean idempotentReplay,
            boolean dryRun) {
        boolean ready = assessment != null && assessment.repairEligible()
                && READY_REASON.equals(assessment.safeReasonCode());
        // An assessment can prove that no repair is eligible, but the public
        // no-action conclusion still needs the freshly projected durable
        // token and registration facts.  Do not let a completed lifecycle
        // checkpoint hide an active task capability from a caller.
        boolean alreadyConverged = fullyConverged(facts(audit));
        return base(clientRequestId, owned, audit, dryRun)
                .outcome(ready ? RuntimeTaskTerminalCleanupRepairOutcome.READY
                        : alreadyConverged
                        ? RuntimeTaskTerminalCleanupRepairOutcome.ALREADY_CONVERGED
                        : RuntimeTaskTerminalCleanupRepairOutcome.REJECTED)
                .reasonCode(safeReason(assessment))
                .terminalTombstonePresent(assessment != null
                        ? assessment.terminalTombstonePresent() : null)
                .lifecycleCleanupComplete(assessment != null
                        ? assessment.cleanupComplete() : null)
                .repairAllowed(ready)
                .repairAccepted(false)
                .idempotentReplay(idempotentReplay)
                .requestReceiptPersisted(receipt != null)
                .build();
    }

    private RuntimeTaskTerminalCleanupRepairDTO confirmResponse(
            String clientRequestId,
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            RuntimeTaskAuditDTO audit,
            TerminalCleanupRepairPort.TerminalCleanupRepairResult result,
            RuntimeRequestAuditService.TerminalCleanupRepairReceipt receipt,
            boolean idempotentReplay) {
        RuntimeTaskFactsDTO currentFacts = facts(audit);
        Boolean tombstonePresent = currentFacts != null
                && currentFacts.getTerminalTombstonePresent() != null
                ? currentFacts.getTerminalTombstonePresent()
                : result != null ? result.terminalTombstonePresent() : null;
        Boolean cleanupComplete = currentFacts != null
                && currentFacts.getLifecycleCleanupComplete() != null
                ? currentFacts.getLifecycleCleanupComplete()
                : result != null ? result.cleanupComplete() : null;
        boolean accepted = result != null && result.repairAccepted();
        boolean alreadyConverged = !accepted && fullyConverged(currentFacts);
        return base(clientRequestId, owned, audit, false)
                .outcome(accepted ? RuntimeTaskTerminalCleanupRepairOutcome.REPAIRED
                        : alreadyConverged
                        ? RuntimeTaskTerminalCleanupRepairOutcome.ALREADY_CONVERGED
                        : RuntimeTaskTerminalCleanupRepairOutcome.REJECTED)
                .reasonCode(safeReason(result))
                .terminalTombstonePresent(tombstonePresent)
                .lifecycleCleanupComplete(cleanupComplete)
                .repairAllowed(false)
                .repairAccepted(accepted)
                .idempotentReplay(idempotentReplay)
                .requestReceiptPersisted(receipt != null)
                .build();
    }

    private RuntimeTaskTerminalCleanupRepairDTO.RuntimeTaskTerminalCleanupRepairDTOBuilder base(
            String clientRequestId,
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            RuntimeTaskAuditDTO audit,
            boolean dryRun) {
        RuntimeTaskFactsDTO facts = audit != null ? audit.getTaskFacts() : null;
        return RuntimeTaskTerminalCleanupRepairDTO.builder()
                .clientRequestId(clientRequestId)
                .operation("task-terminal-cleanup-repair")
                .taskId(owned.taskId())
                .dryRun(dryRun)
                .currentTaskStatus(facts != null ? facts.getStatus() : owned.status())
                .canonicalTerminal(facts != null
                        ? facts.getLifecycleCanonicalTerminal() : owned.terminal())
                .taskTokenStatus(facts != null ? facts.getTaskTokenStatus() : null)
                .activeTaskRegistrationPresent(facts != null
                        ? facts.getActiveTaskRegistrationPresent() : null)
                .selectedPhysicalWorkerId(owned.physicalWorkerId())
                .taskFacts(facts)
                .auditSideEffects(noAuditSideEffects())
                .workerCommandDispatched(false)
                .runtimeDispatched(false)
                .modelDispatched(false)
                .businessFunctionDispatched(false)
                .terminationTriggered(false)
                .retryTriggered(false)
                .recoveryTriggered(false)
                .newTaskCreated(false)
                .newContextCreated(false)
                .newSessionCreated(false)
                .accessTokenIssued(false)
                .runtimeTokenIssued(false)
                .taskTokenIssued(false)
                .provisioningResourceChanged(false);
    }

    private RuntimeTaskTerminalCleanupRepairDTO fromReceipt(
            String clientRequestId,
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            RuntimeTaskAuditDTO audit,
            RuntimeRequestAuditService.TerminalCleanupRepairReceipt receipt,
            boolean idempotentReplay,
            boolean dryRun) {
        boolean repaired = "REPAIRED".equals(receipt.status());
        RuntimeTaskFactsDTO currentFacts = facts(audit);
        boolean converged = fullyConverged(currentFacts);
        return base(clientRequestId, owned, audit, dryRun)
                .outcome(repaired ? RuntimeTaskTerminalCleanupRepairOutcome.REPAIRED
                        : converged ? RuntimeTaskTerminalCleanupRepairOutcome.ALREADY_CONVERGED
                        : RuntimeTaskTerminalCleanupRepairOutcome.REJECTED)
                .reasonCode(receipt.result())
                .terminalTombstonePresent(currentFacts != null
                        ? currentFacts.getTerminalTombstonePresent() : null)
                .lifecycleCleanupComplete(currentFacts != null
                        ? currentFacts.getLifecycleCleanupComplete() : null)
                .repairAllowed(false)
                .repairAccepted(repaired)
                .idempotentReplay(idempotentReplay)
                .requestReceiptPersisted(true)
                .build();
    }

    private RuntimeRequestAuditService.TaskEvidence evidence(RuntimeTaskAuditDTO audit) {
        RuntimeTaskFactsDTO facts = facts(audit);
        if (facts == null) {
            return new RuntimeRequestAuditService.TaskEvidence(
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }
        return new RuntimeRequestAuditService.TaskEvidence(
                facts.getTaskId(), facts.getStatus(), facts.getTerminal(),
                facts.getSanitizedErrorCode(), null, null,
                facts.getPhysicalWorkerId(), facts.getModelConfigId(), facts.getModelVariant(),
                facts.getRequestedToolCount(), facts.getEffectiveToolCount(),
                facts.getToolScopeKind(), facts.getToolScopeSource(),
                facts.getRequestedFunctionCount(), facts.getEffectiveFunctionCount(),
                facts.getFunctionScopeSource(), facts.getTaskTokenFunctionScopeEmpty(),
                facts.getTaskTokenStatus(), facts.getRuntimeDispatched(),
                facts.getModelDispatched(), facts.getBusinessFunctionDispatched(),
                facts.getDispatchCount(), facts.getRetryCount(), facts.getRecoveryCount(),
                null);
    }

    private RuntimeTaskFactsDTO facts(RuntimeTaskAuditDTO audit) {
        return audit != null ? audit.getTaskFacts() : null;
    }

    /**
     * A terminal cleanup is only publicly converged once all four durable
     * facts agree.  In particular, snapshot cleanup completion alone cannot
     * stand in for a revoked task token or an absent active registration.
     */
    private boolean fullyConverged(RuntimeTaskFactsDTO facts) {
        return facts != null
                && Boolean.TRUE.equals(facts.getLifecycleCanonicalTerminal())
                && Boolean.TRUE.equals(facts.getTerminalTombstonePresent())
                && Boolean.TRUE.equals(facts.getLifecycleCleanupComplete())
                && tokenCleared(facts.getTaskTokenStatus())
                && Boolean.FALSE.equals(facts.getActiveTaskRegistrationPresent());
    }

    /** Only a durable revoke is public proof of task-token closure. */
    private boolean tokenCleared(String status) {
        return "REVOKED".equalsIgnoreCase(status);
    }

    private RuntimeAuditSideEffectsDTO noAuditSideEffects() {
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

    private void requireExpectedWorker(
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            String expectedPhysicalWorkerId) {
        if (!StringUtils.hasText(expectedPhysicalWorkerId)
                || !expectedPhysicalWorkerId.trim().equals(owned.physicalWorkerId())) {
            throw new IllegalArgumentException("EXPECTED_PHYSICAL_WORKER_MISMATCH");
        }
    }

    private String safeReason(TerminalCleanupRepairPort.TerminalCleanupRepairAssessment value) {
        return value != null && StringUtils.hasText(value.safeReasonCode())
                ? value.safeReasonCode() : "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_NOT_READY";
    }

    private String safeReason(TerminalCleanupRepairPort.TerminalCleanupRepairResult value) {
        return value != null && StringUtils.hasText(value.safeReasonCode())
                ? value.safeReasonCode() : "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_FAILED";
    }
}
