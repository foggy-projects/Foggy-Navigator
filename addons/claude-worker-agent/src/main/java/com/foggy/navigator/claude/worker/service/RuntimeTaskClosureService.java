package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.claude.worker.model.dto.RuntimeAuditSideEffectsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskClosureDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskFactsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTerminationReadinessDTO;
import com.foggy.navigator.spi.task.RuntimeTaskClosureProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RuntimeTaskClosureService {

    private final RuntimeStateAuditService stateAuditService;
    private final List<RuntimeTaskClosureProvider> providers;
    private final RuntimeRequestAuditService requestAuditService;

    public RuntimeTerminationReadinessDTO readiness(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String taskId,
            String expectedPhysicalWorkerId) {
        RuntimeStateAuditService.OwnedRuntimeTask owned = stateAuditService.requireOwnedTask(
                appKey, appSecret, upstreamUserId, taskId);
        requireExpectedWorker(owned, expectedPhysicalWorkerId);
        RuntimeTaskAuditDTO audit = stateAuditService.auditTask(
                appKey, appSecret, upstreamUserId, taskId);
        RuntimeTaskClosureProvider.TerminationReadiness readiness = provider(owned).inspect(
                taskId, expectedPhysicalWorkerId);
        boolean tokenActive = audit.getTaskFacts() != null
                && "ACTIVE".equals(audit.getTaskFacts().getTaskTokenStatus());
        boolean activeRegistration = audit.getTaskFacts() != null
                && Boolean.TRUE.equals(audit.getTaskFacts().getActiveTaskRegistrationPresent());
        boolean allowed = !owned.terminal() && tokenActive && activeRegistration
                && readiness.terminateAllowed();
        String blocked = allowed ? null
                : owned.terminal() ? "TASK_ALREADY_TERMINAL"
                : !tokenActive ? "TASK_TOKEN_NOT_ACTIVE"
                : !activeRegistration ? "ACTIVE_TASK_REGISTRATION_NOT_PRESENT"
                : readiness.blockedReason();
        return RuntimeTerminationReadinessDTO.builder()
                .taskExists(true)
                .taskId(taskId)
                .terminal(audit.getTaskFacts().getTerminal())
                .status(audit.getTaskFacts().getStatus())
                .physicalWorkerId(owned.physicalWorkerId())
                .workerReachable(readiness.workerReachable())
                .workerActiveTaskPresent(readiness.workerActiveTaskPresent())
                .terminationReady(readiness.terminationReady())
                .terminationAuthConfigured(readiness.terminationAuthConfigured())
                .terminationWorkerIdConfigured(readiness.terminationWorkerIdConfigured())
                .taskTokenStatus(audit.getTaskFacts().getTaskTokenStatus())
                .activeTaskRegistrationPresent(audit.getTaskFacts().getActiveTaskRegistrationPresent())
                .terminateAllowed(allowed)
                .blockedReason(blocked)
                .dryRun(true)
                .taskFacts(audit.getTaskFacts())
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
        if (!dryRun && !Objects.equals(taskId, confirmTaskId)) {
            throw new IllegalArgumentException("CONFIRM_TASK_ID_MISMATCH");
        }
        RuntimeStateAuditService.OwnedRuntimeTask owned = stateAuditService.requireOwnedTask(
                appKey, appSecret, upstreamUserId, taskId);
        requireExpectedWorker(owned, expectedPhysicalWorkerId);
        RuntimeRequestAuditService.AuditHandle requestAudit = dryRun ? null
                : requestAuditService.beginTaskOperation(
                        clientRequestId, RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                        appKey, appSecret, null, upstreamUserId, taskId);
        try {
            RuntimeTaskClosureProvider.TerminationResult result = provider(owned).terminate(
                    taskId, owned.ownerUserId(), owned.tenantId(), expectedPhysicalWorkerId,
                    reason, clientRequestId, dryRun);
            RuntimeTaskAuditDTO audit = stateAuditService.auditTask(
                    appKey, appSecret, upstreamUserId, taskId);
            if (requestAudit != null) {
                requestAuditService.taskOperationCompleted(
                        requestAudit,
                        evidence(audit.getTaskFacts(), result.alreadyTerminal()
                                ? "ALREADY_TERMINAL" : "TERMINATION_REQUESTED"),
                        false,
                        result.terminationDispatched());
            }
            return base(clientRequestId, "task-terminate", taskId, dryRun, audit)
                    .alreadyTerminal(result.alreadyTerminal())
                    .terminationDispatched(result.terminationDispatched())
                    .idempotentReplay(result.idempotentReplay())
                    .reconcileRequired(result.reconcileRequired())
                    .sanitizedErrorCode(result.sanitizedErrorCode())
                    .build();
        } catch (RuntimeException e) {
            if (requestAudit != null) {
                requestAuditService.taskOperationFailed(
                        requestAudit, sanitizedCode(e, "RUNTIME_TASK_TERMINATE_FAILED"));
            }
            throw e;
        }
    }

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
                        evidence(audit.getTaskFacts(), result.reconciliationChanged()
                                ? "RECONCILIATION_CHANGED" : "RECONCILIATION_NO_CHANGE"),
                        false,
                        result.reconciliationChanged());
            }
            if (!dryRun && Boolean.TRUE.equals(audit.getTaskFacts().getTerminal())) {
                requestAuditService.refreshCompletedTaskOperation(
                        taskId,
                        RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                        evidence(audit.getTaskFacts(), "TASK_TERMINATED"));
                requestAuditService.refreshCompletedTaskOperation(
                        taskId,
                        RuntimeRequestAuditService.OPERATION_TASK_RECONCILE,
                        evidence(audit.getTaskFacts(), result.reconciliationChanged()
                                ? "RECONCILIATION_CHANGED" : "RECONCILIATION_NO_CHANGE"));
            }
            return base(clientRequestId, "task-reconcile", taskId, dryRun, audit)
                    .reconciliationChanged(result.reconciliationChanged())
                    .alreadyConsistent(result.alreadyConsistent())
                    .durableEvidence(result.durableEvidence())
                    .sanitizedErrorCode(result.sanitizedErrorCode())
                    .build();
        } catch (RuntimeException e) {
            if (requestAudit != null) {
                requestAuditService.taskOperationFailed(
                        requestAudit, sanitizedCode(e, "RUNTIME_TASK_RECONCILE_FAILED"));
            }
            throw e;
        }
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
                .taskFacts(audit.getTaskFacts())
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

    private RuntimeTaskClosureProvider provider(RuntimeStateAuditService.OwnedRuntimeTask task) {
        return providers.stream().filter(value -> value.supports(task.providerType())).findFirst()
                .orElseThrow(() -> new IllegalStateException("RUNTIME_TASK_PROVIDER_UNSUPPORTED"));
    }

    private void requireExpectedWorker(
            RuntimeStateAuditService.OwnedRuntimeTask task, String expectedPhysicalWorkerId) {
        if (!StringUtils.hasText(expectedPhysicalWorkerId)
                || !expectedPhysicalWorkerId.trim().equals(task.physicalWorkerId())) {
            throw new IllegalArgumentException("EXPECTED_PHYSICAL_WORKER_MISMATCH");
        }
    }

    private void requireClientRequestId(String value) {
        if (!StringUtils.hasText(value)
                || !value.trim().matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("CLIENT_REQUEST_ID_INVALID");
        }
    }

    private RuntimeRequestAuditService.TaskEvidence evidence(RuntimeTaskFactsDTO facts, String result) {
        return new RuntimeRequestAuditService.TaskEvidence(
                facts.getTaskId(), facts.getStatus(), facts.getTerminal(), facts.getSanitizedErrorCode(),
                null, null, facts.getPhysicalWorkerId(), facts.getModelConfigId(), facts.getModelVariant(),
                facts.getRequestedToolCount(), facts.getEffectiveToolCount(), facts.getToolScopeKind(),
                facts.getToolScopeSource(), facts.getRequestedFunctionCount(), facts.getEffectiveFunctionCount(),
                facts.getFunctionScopeSource(), facts.getTaskTokenFunctionScopeEmpty(), facts.getTaskTokenStatus(),
                facts.getRuntimeDispatched(), facts.getModelDispatched(), facts.getBusinessFunctionDispatched(),
                facts.getDispatchCount(), facts.getRetryCount(), facts.getRecoveryCount(), result);
    }

    private String sanitizedCode(RuntimeException error, String fallback) {
        String value = error.getMessage();
        return StringUtils.hasText(value) && value.matches("[A-Z][A-Z0-9_]{2,127}") ? value : fallback;
    }

    private RuntimeAuditSideEffectsDTO noSideEffects() {
        return RuntimeAuditSideEffectsDTO.builder()
                .accessTokenIssued(false).runtimeTokenIssued(false).taskTokenIssued(false)
                .taskCreated(false).contextCreated(false).sessionCreated(false)
                .modelDispatched(false).businessFunctionDispatched(false)
                .recoveryTriggered(false).provisioningResourceChanged(false)
                .build();
    }
}
