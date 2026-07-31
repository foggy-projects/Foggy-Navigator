package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.RuntimeRequestAuditDTO;
import com.foggy.navigator.business.agent.model.dto.RuntimeRequestAuditPageDTO;
import com.foggy.navigator.business.agent.model.dto.RuntimeRequestAuditSideEffectsDTO;
import com.foggy.navigator.business.agent.model.dto.RuntimeRequestAuditStageDTO;
import com.foggy.navigator.business.agent.model.dto.RuntimeRequestTaskFactsDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeCredentialEntity;
import com.foggy.navigator.business.agent.model.entity.RuntimeRequestAuditEntity;
import com.foggy.navigator.business.agent.model.entity.RuntimeRequestAuditStageEntity;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeCredentialRepository;
import com.foggy.navigator.business.agent.repository.RuntimeRequestAuditRepository;
import com.foggy.navigator.business.agent.repository.RuntimeRequestAuditStageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeRequestAuditService {

    public static final String OPERATION_RUNTIME_TOKEN = "runtime-token";
    public static final String OPERATION_SAFE_ASK = "safe-ask";
    public static final String OPERATION_ASK = "ask";
    public static final String OPERATION_TASK_TERMINATE = "task-terminate";
    public static final String OPERATION_TASK_RECONCILE = "task-reconcile";

    public static final String STAGE_CLIENT_REQUEST_RECEIVED = "CLIENT_REQUEST_RECEIVED";
    public static final String STAGE_RUNTIME_TOKEN_REQUEST_RECEIVED = "RUNTIME_TOKEN_REQUEST_RECEIVED";
    public static final String STAGE_RUNTIME_TOKEN_ISSUED = "RUNTIME_TOKEN_ISSUED";
    public static final String STAGE_RUNTIME_TOKEN_REJECTED = "RUNTIME_TOKEN_REJECTED";
    public static final String STAGE_SAFE_SMOKE_REQUEST_RECEIVED = "SAFE_SMOKE_REQUEST_RECEIVED";
    public static final String STAGE_SYNTHETIC_EVIDENCE_CREATED = "SYNTHETIC_EVIDENCE_CREATED";
    public static final String STAGE_TASK_TOKEN_REVOKED = "TASK_TOKEN_REVOKED";
    public static final String STAGE_STANDARD_SCOPE_ADMITTED = "STANDARD_SCOPE_ADMITTED";
    public static final String STAGE_RUNTIME_DISPATCHED = "RUNTIME_DISPATCHED";
    public static final String STAGE_MODEL_DISPATCHED = "MODEL_DISPATCHED";
    public static final String STAGE_BUSINESS_FUNCTION_NOT_DISPATCHED = "BUSINESS_FUNCTION_NOT_DISPATCHED";
    public static final String STAGE_TERMINATION_REQUESTED = "TERMINATION_REQUESTED";
    public static final String STAGE_TERMINATION_DISPATCHED = "TERMINATION_DISPATCHED";
    public static final String STAGE_TERMINATION_EVIDENCE_OBSERVED = "TERMINATION_EVIDENCE_OBSERVED";
    public static final String STAGE_TERMINATION_DRY_RUN_COMPLETED = "TERMINATION_DRY_RUN_COMPLETED";
    public static final String STAGE_RECONCILIATION_REQUESTED = "RECONCILIATION_REQUESTED";
    public static final String STAGE_RECONCILIATION_EVIDENCE_OBSERVED = "RECONCILIATION_EVIDENCE_OBSERVED";
    public static final String STAGE_RECONCILIATION_NO_CHANGE = "RECONCILIATION_NO_CHANGE";
    public static final String STAGE_REQUEST_COMPLETED = "REQUEST_COMPLETED";
    public static final String STAGE_REQUEST_FAILED = "REQUEST_FAILED";
    public static final String STAGE_REQUEST_RECEIVED = "REQUEST_RECEIVED";
    public static final String STAGE_AUTHENTICATION = "AUTHENTICATION";
    public static final String STAGE_RUNTIME_TOKEN_REQUEST = "RUNTIME_TOKEN_REQUEST";
    public static final String STAGE_RUNTIME_TOKEN_NOT_ISSUED = "RUNTIME_TOKEN_NOT_ISSUED";
    public static final String STAGE_STANDARD_ASK_ADMISSION = "STANDARD_ASK_ADMISSION";
    public static final String STAGE_TOOL_SCOPE_RESOLVED = "TOOL_SCOPE_RESOLVED";
    public static final String STAGE_FUNCTION_SCOPE_RESOLVED = "FUNCTION_SCOPE_RESOLVED";
    public static final String STAGE_TASK_CREATED = "TASK_CREATED";
    public static final String STAGE_TASK_NOT_CREATED = "TASK_NOT_CREATED";
    public static final String STAGE_TASK_TOKEN_ISSUED = "TASK_TOKEN_ISSUED";
    public static final String STAGE_TASK_TOKEN_NOT_ISSUED = "TASK_TOKEN_NOT_ISSUED";
    public static final String STAGE_RUNTIME_DISPATCH = "RUNTIME_DISPATCH";
    public static final String STAGE_RUNTIME_NOT_DISPATCHED = "RUNTIME_NOT_DISPATCHED";
    public static final String STAGE_MODEL_DISPATCH = "MODEL_DISPATCH";
    public static final String STAGE_MODEL_NOT_DISPATCHED = "MODEL_NOT_DISPATCHED";
    public static final String STAGE_BUSINESS_FUNCTION_DISPATCH = "BUSINESS_FUNCTION_DISPATCH";
    public static final String STAGE_TASK_TERMINAL = "TASK_TERMINAL";
    public static final String STAGE_TASK_NOT_TERMINAL = "TASK_NOT_TERMINAL";
    public static final String STAGE_TASK_TOKEN_NOT_REVOKED = "TASK_TOKEN_NOT_REVOKED";

    private static final String UNKNOWN = "UNKNOWN";
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(24);
    private static final Duration DEFAULT_TERMINATION_RECEIPT_RETENTION = Duration.ofDays(7);
    private static final Duration DEFAULT_TERMINATION_CONVERGENCE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration HARD_MAX_QUERY_WINDOW = Duration.ofMinutes(15);
    private static final int HARD_MAX_LIMIT = 100;
    private static final Set<String> OPERATIONS = Set.of(
            OPERATION_RUNTIME_TOKEN,
            OPERATION_SAFE_ASK,
            OPERATION_ASK,
            OPERATION_TASK_TERMINATE,
            OPERATION_TASK_RECONCILE);

    private final RuntimeRequestAuditRepository auditRepository;
    private final RuntimeRequestAuditStageRepository stageRepository;
    private final ClientAppRuntimeCredentialRepository runtimeCredentialRepository;
    private final ClientAppRepository clientAppRepository;
    private final ClientAppRuntimeCredentialResolver credentialResolver;
    private final RuntimeRequestAuditProperties properties;

    public record AuditHandle(String clientRequestId) {
    }

    public record TaskOperationRegistration(AuditHandle handle, boolean existing) {
    }

    public record TaskOperationSnapshot(
            String clientRequestId,
            String operation,
            String taskId,
            String upstreamUserId,
            boolean completed,
            String result,
            String status,
            String sanitizedErrorCode,
            String physicalWorkerId,
            boolean convergenceTimedOut) {
        public TaskOperationSnapshot(
                String clientRequestId,
                String operation,
                String taskId,
                String upstreamUserId,
                boolean completed,
                String result,
                String status,
                String sanitizedErrorCode,
                String physicalWorkerId) {
            this(clientRequestId, operation, taskId, upstreamUserId, completed,
                    result, status, sanitizedErrorCode, physicalWorkerId, false);
        }
    }

    public record SafeSmokeEvidence(
            String taskId,
            String status,
            Integer effectiveToolCount,
            String toolScopeKind,
            String toolScopeSource,
            Integer effectiveFunctionCount,
            String functionScopeSource,
            Boolean taskTokenFunctionScopeEmpty,
            String taskTokenStatus,
            Boolean runtimeDispatched,
            String result) {
    }

    public record TaskEvidence(
            String taskId,
            String taskStatus,
            Boolean taskTerminal,
            String sanitizedErrorCode,
            String agentCode,
            String upstreamUserId,
            String physicalWorkerId,
            String modelConfigId,
            String modelVariant,
            Integer requestedToolCount,
            Integer effectiveToolCount,
            String toolScopeKind,
            String toolScopeSource,
            Integer requestedFunctionCount,
            Integer effectiveFunctionCount,
            String functionScopeSource,
            Boolean taskTokenFunctionScopeEmpty,
            String taskTokenStatus,
            Boolean runtimeDispatched,
            Boolean modelDispatched,
            Boolean businessFunctionDispatched,
            Integer dispatchCount,
            Integer retryCount,
            Integer recoveryCount,
            String result) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditHandle beginAsk(
            String clientRequestId,
            ResolvedClientAppCredentialDTO credential,
            String agentCode,
            String upstreamUserId) {
        AuditHandle handle = beginAskRequest(
                clientRequestId,
                credential != null ? credential.getRuntimeTokenClientRequestId() : null,
                resolveOwner(credential),
                agentCode,
                upstreamUserId);
        authenticationCompleted(handle);
        return handle;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditHandle beginAskRequest(
            String clientRequestId,
            String parentClientRequestId,
            String appKey,
            String agentCode,
            String upstreamUserId) {
        return beginAskRequest(
                clientRequestId,
                parentClientRequestId,
                resolveOwnerByAppKey(appKey),
                agentCode,
                upstreamUserId);
    }

    private AuditHandle beginAskRequest(
            String clientRequestId,
            String parentClientRequestId,
            OwnerScope owner,
            String agentCode,
            String upstreamUserId) {
        String requestId = requireRequestId(clientRequestId);
        String parentId = optionalRequestId(parentClientRequestId);
        Instant now = Instant.now();
        RuntimeRequestAuditEntity entity = auditRepository.findByClientRequestId(requestId).orElse(null);
        if (entity == null) {
            entity = baseEntity(requestId, OPERATION_ASK, owner, agentCode, upstreamUserId, now);
            applyParentCorrelation(entity, parentId, owner);
            entity.setStandardAskRequestReceived(true);
            entity.setStatus("REQUEST_RECEIVED");
            saveNew(entity);
            appendStage(requestId, STAGE_REQUEST_RECEIVED, "RECEIVED", null, now);
        } else {
            requireSameOwner(entity, owner);
            if (!OPERATION_ASK.equals(entity.getOperation())) {
                throw new IllegalArgumentException("CLIENT_REQUEST_ID_OPERATION_MISMATCH");
            }
            entity.setStandardAskRequestReceived(true);
            entity.setAgentCode(clean(agentCode, entity.getAgentCode()));
            entity.setUpstreamUserId(clean(upstreamUserId, entity.getUpstreamUserId()));
            if (parentId != null && entity.getParentClientRequestId() == null) {
                applyParentCorrelation(entity, parentId, owner);
            }
            auditRepository.save(entity);
            appendStageOnce(requestId, STAGE_REQUEST_RECEIVED, "RECEIVED", null, now);
        }
        return new AuditHandle(requestId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void authenticationCompleted(AuditHandle handle) {
        RuntimeRequestAuditEntity entity = requireAudit(handle);
        if (!Boolean.TRUE.equals(entity.getTerminal())) {
            appendStageOnce(entity.getClientRequestId(), STAGE_AUTHENTICATION, "SUCCEEDED", null, Instant.now());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void authenticationFailed(AuditHandle handle, String sanitizedErrorCode) {
        RuntimeRequestAuditEntity entity = requireAudit(handle);
        String code = requireSanitizedCode(sanitizedErrorCode);
        appendStageOnce(entity.getClientRequestId(), STAGE_AUTHENTICATION, "FAILED", code, Instant.now());
        failAsk(entity, code);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditHandle beginTaskOperation(
            String clientRequestId,
            String operation,
            String appKey,
            String appSecret,
            String agentCode,
            String upstreamUserId,
            String taskId) {
        return beginTaskOperationIdempotent(
                clientRequestId, operation, appKey, appSecret,
                agentCode, upstreamUserId, taskId).handle();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TaskOperationRegistration beginTaskOperationIdempotent(
            String clientRequestId,
            String operation,
            String appKey,
            String appSecret,
            String agentCode,
            String upstreamUserId,
            String taskId) {
        ResolvedClientAppCredentialDTO credential = credentialResolver.resolve(appKey, appSecret)
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_AUDIT_CREDENTIAL_REQUIRED"));
        return registerTaskOperation(
                clientRequestId, operation, resolveOwner(credential), agentCode, upstreamUserId, taskId);
    }

    /**
     * ARCH-001 receipt variant. The caller owns the surrounding transaction so
     * receipt, lifecycle intent and effect outbox either commit together or all
     * roll back. Existing non-lifecycle callers retain the REQUIRES_NEW method.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public TaskOperationRegistration beginTaskOperationIdempotentAtomic(
            String clientRequestId,
            String operation,
            String appKey,
            String appSecret,
            String agentCode,
            String upstreamUserId,
            String taskId) {
        ResolvedClientAppCredentialDTO credential = credentialResolver.resolve(appKey, appSecret)
                .orElseThrow(() -> new IllegalArgumentException(
                        "RUNTIME_AUDIT_CREDENTIAL_REQUIRED"));
        return registerTaskOperation(
                clientRequestId, operation, resolveOwner(credential),
                agentCode, upstreamUserId, taskId);
    }

    private TaskOperationRegistration registerTaskOperation(
            String clientRequestId,
            String operation,
            OwnerScope owner,
            String agentCode,
            String upstreamUserId,
            String taskId) {
        String requestId = requireRequestId(clientRequestId);
        String normalizedOperation = normalizeOperation(operation);
        if (!Set.of(OPERATION_ASK, OPERATION_TASK_TERMINATE, OPERATION_TASK_RECONCILE)
                .contains(normalizedOperation)) {
            throw new IllegalArgumentException("RUNTIME_AUDIT_OPERATION_INVALID");
        }
        RuntimeRequestAuditEntity existing = auditRepository.findByClientRequestId(requestId).orElse(null);
        if (existing != null) {
            requireSameOwner(existing, owner);
            if (!normalizedOperation.equals(existing.getOperation())
                    || !Objects.equals(clean(taskId, null), clean(existing.getTaskId(), null))
                    || !Objects.equals(
                            clean(upstreamUserId, null),
                            clean(existing.getUpstreamUserId(), null))) {
                throw new IllegalArgumentException("CLIENT_REQUEST_ID_OPERATION_MISMATCH");
            }
            return new TaskOperationRegistration(new AuditHandle(requestId), true);
        }
        Instant now = Instant.now();
        RuntimeRequestAuditEntity entity = baseEntity(
                requestId, normalizedOperation, owner, agentCode, upstreamUserId, now);
        if (OPERATION_TASK_TERMINATE.equals(normalizedOperation)) {
            entity.setExpiresAt(now.plus(effectiveTerminationReceiptRetention()));
        }
        entity.setTaskId(clean(taskId, null));
        entity.setStatus("REQUEST_RECEIVED");
        saveNew(entity);
        appendStage(requestId, STAGE_CLIENT_REQUEST_RECEIVED, "RECEIVED", null, now);
        if (!OPERATION_ASK.equals(normalizedOperation)) {
            appendStage(requestId,
                    OPERATION_TASK_RECONCILE.equals(normalizedOperation)
                            ? STAGE_RECONCILIATION_REQUESTED
                            : STAGE_TERMINATION_REQUESTED,
                    "RECEIVED", null, now);
        }
        return new TaskOperationRegistration(new AuditHandle(requestId), false);
    }

    /**
     * Returns one sanitized operation receipt in the calling ClientApp scope.
     * This method is strictly read-only and deliberately does not create a
     * reconciliation audit record.
     */
    @Transactional(readOnly = true)
    public Optional<TaskOperationSnapshot> findSelfTaskOperation(
            String appKey,
            String appSecret,
            String clientRequestId) {
        ResolvedClientAppCredentialDTO credential = credentialResolver.resolve(appKey, appSecret)
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_AUDIT_CREDENTIAL_REQUIRED"));
        OwnerScope owner = resolveOwner(credential);
        String requestId = requireRequestId(clientRequestId);
        Instant now = Instant.now();
        RuntimeRequestAuditEntity entity = auditRepository.findByClientRequestId(requestId).orElse(null);
        if (entity == null
                || entity.getExpiresAt() == null
                || !entity.getExpiresAt().isAfter(now)
                || !sameOwner(entity, owner)) {
            return Optional.empty();
        }
        return Optional.of(new TaskOperationSnapshot(
                entity.getClientRequestId(),
                entity.getOperation(),
                entity.getTaskId(),
                entity.getUpstreamUserId(),
                Boolean.TRUE.equals(entity.getTerminal()),
                clean(entity.getResult(), UNKNOWN),
                clean(entity.getStatus(), UNKNOWN),
                entity.getSanitizedErrorCode(),
                entity.getPhysicalWorkerId(),
                terminationConvergenceTimedOut(entity, now)));
    }

    @Transactional(readOnly = true)
    public boolean hasDurableTaskOperationReceipt(
            String taskId, String operation) {
        if (!StringUtils.hasText(taskId)
                || !Set.of(OPERATION_TASK_TERMINATE,
                OPERATION_TASK_RECONCILE).contains(operation)) {
            return false;
        }
        return auditRepository
                .findTopByTaskIdAndOperationOrderByReceivedAtDesc(
                        taskId.trim(), operation)
                .isPresent();
    }

    public boolean terminationRequestReceiptEnabled() {
        return properties.isTerminationReceiptEnabled();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void taskAdmissionRecorded(AuditHandle handle, TaskEvidence evidence) {
        RuntimeRequestAuditEntity entity = requireAudit(handle);
        if (Boolean.TRUE.equals(entity.getTerminal())) {
            return;
        }
        applyTaskEvidence(entity, evidence);
        entity.setAdmissionCompleted(true);
        entity.setTaskCreated(false);
        entity.setTaskTokenIssued(false);
        entity.setRuntimeDispatched(false);
        entity.setModelDispatched(false);
        entity.setBusinessFunctionDispatched(false);
        entity.setDispatchCount(0);
        entity.setRetryCount(0);
        entity.setRecoveryCount(0);
        entity.setStatus("ADMITTED");
        auditRepository.save(entity);
        appendStage(entity.getClientRequestId(), STAGE_STANDARD_SCOPE_ADMITTED, "SUCCEEDED", null, Instant.now());
        appendStageOnce(entity.getClientRequestId(), STAGE_STANDARD_ASK_ADMISSION, "SUCCEEDED", null, Instant.now());
        appendStageOnce(entity.getClientRequestId(), STAGE_TOOL_SCOPE_RESOLVED, "SUCCEEDED", null, Instant.now());
        appendStageOnce(entity.getClientRequestId(), STAGE_FUNCTION_SCOPE_RESOLVED, "SUCCEEDED", null, Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void taskDispatchRecorded(AuditHandle handle, TaskEvidence evidence) {
        RuntimeRequestAuditEntity entity = requireAudit(handle);
        if (Boolean.TRUE.equals(entity.getTerminal())) {
            return;
        }
        Instant now = Instant.now();
        applyTaskEvidence(entity, evidence);
        entity.setTaskCreated(true);
        entity.setTaskTokenIssued(true);
        entity.setTerminal(false);
        entity.setCompletedAt(null);
        entity.setResult("STANDARD_ASK_DISPATCHED");
        auditRepository.save(entity);
        appendStageOnce(entity.getClientRequestId(), STAGE_TASK_CREATED, "SUCCEEDED", null, now);
        appendStageOnce(entity.getClientRequestId(), STAGE_TASK_TOKEN_ISSUED, "SUCCEEDED", null, now);
        appendStageOnce(entity.getClientRequestId(), STAGE_RUNTIME_DISPATCH, "SUCCEEDED", null, now);
        appendStageOnce(entity.getClientRequestId(), STAGE_MODEL_DISPATCH, "SUCCEEDED", null, now);
        appendStageOnce(entity.getClientRequestId(),
                STAGE_BUSINESS_FUNCTION_NOT_DISPATCHED, "SUCCEEDED", null, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void taskTerminalRecorded(String taskId, String status, String sanitizedErrorCode) {
        if (!StringUtils.hasText(taskId) || !isTerminalTaskStatus(status)) {
            return;
        }
        Instant now = Instant.now();
        String normalizedTaskId = taskId.trim();
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        RuntimeRequestAuditEntity ask = auditRepository
                .findTopByTaskIdAndOperationAndExpiresAtAfterOrderByReceivedAtDesc(
                        normalizedTaskId, OPERATION_ASK, now)
                .orElse(null);
        if (ask != null) {
            ask.setStatus(normalizedStatus);
            ask.setSanitizedErrorCode(clean(sanitizedErrorCode, ask.getSanitizedErrorCode()));
            ask.setTaskTokenStatus("REVOKED");
            ask.setTerminal(true);
            ask.setCompletedAt(now);
            ask.setResult("COMPLETED".equals(ask.getStatus())
                    ? "STANDARD_ASK_COMPLETED"
                    : "STANDARD_ASK_TERMINAL");
            auditRepository.save(ask);
            appendStageOnce(ask.getClientRequestId(), STAGE_TASK_TERMINAL, "SUCCEEDED", null, now);
            appendStageOnce(ask.getClientRequestId(), STAGE_TASK_TOKEN_REVOKED, "SUCCEEDED", null, now);
            appendStageOnce(ask.getClientRequestId(), STAGE_REQUEST_COMPLETED, "SUCCEEDED", null, now);
        }

        RuntimeRequestAuditEntity termination = auditRepository
                .findTopByTaskIdAndOperationAndExpiresAtAfterOrderByReceivedAtDesc(
                        normalizedTaskId, OPERATION_TASK_TERMINATE, now)
                .orElse(null);
        if (termination == null) {
            return;
        }
        termination.setStatus(normalizedStatus);
        termination.setSanitizedErrorCode(clean(
                sanitizedErrorCode, termination.getSanitizedErrorCode()));
        termination.setTaskTokenStatus("REVOKED");
        termination.setTerminal(true);
        termination.setCompletedAt(now);
        termination.setResult("TASK_TERMINATED");
        termination.setSafeErrorSummary(null);
        auditRepository.save(termination);
        appendStageOnce(termination.getClientRequestId(),
                STAGE_TERMINATION_EVIDENCE_OBSERVED, "SUCCEEDED", null, now);
        appendStageOnce(termination.getClientRequestId(),
                STAGE_TASK_TERMINAL, "SUCCEEDED", null, now);
        appendStageOnce(termination.getClientRequestId(),
                STAGE_TASK_TOKEN_REVOKED, "SUCCEEDED", null, now);
        appendStageOnce(termination.getClientRequestId(),
                STAGE_REQUEST_COMPLETED, "SUCCEEDED", null, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void askFailed(AuditHandle handle, String sanitizedErrorCode) {
        RuntimeRequestAuditEntity entity = requireAudit(handle);
        if (!Boolean.TRUE.equals(entity.getTerminal())) {
            failAsk(entity, requireSanitizedCode(sanitizedErrorCode));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void taskOperationCompleted(AuditHandle handle, TaskEvidence evidence, boolean dryRun, boolean changed) {
        RuntimeRequestAuditEntity entity = requireAudit(handle);
        if (Boolean.TRUE.equals(entity.getTerminal())) {
            return;
        }
        Instant now = Instant.now();
        applyTaskEvidence(entity, evidence);
        if (OPERATION_ASK.equals(entity.getOperation())) {
            taskDispatchRecorded(handle, evidence);
            return;
        } else if (OPERATION_TASK_TERMINATE.equals(entity.getOperation())) {
            appendStage(entity.getClientRequestId(), dryRun
                    ? STAGE_TERMINATION_DRY_RUN_COMPLETED
                    : STAGE_TERMINATION_DISPATCHED, "SUCCEEDED", null, now);
        } else if (OPERATION_TASK_RECONCILE.equals(entity.getOperation())) {
            appendStage(entity.getClientRequestId(), changed
                    ? STAGE_RECONCILIATION_EVIDENCE_OBSERVED
                    : STAGE_RECONCILIATION_NO_CHANGE, "SUCCEEDED", null, now);
        }
        if ("REVOKED".equalsIgnoreCase(entity.getTaskTokenStatus())) {
            appendStage(entity.getClientRequestId(), STAGE_TASK_TOKEN_REVOKED, "SUCCEEDED", null, now);
        }
        complete(entity, clean(evidence.result(), "COMPLETED"), clean(evidence.taskStatus(), "COMPLETED"), now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshCompletedTaskOperation(String taskId, String operation, TaskEvidence evidence) {
        String normalizedOperation = normalizeOperation(operation);
        if (!Set.of(OPERATION_TASK_TERMINATE, OPERATION_TASK_RECONCILE).contains(normalizedOperation)) {
            throw new IllegalArgumentException("RUNTIME_AUDIT_OPERATION_INVALID");
        }
        RuntimeRequestAuditEntity entity = auditRepository
                .findTopByTaskIdAndOperationOrderByReceivedAtDesc(taskId, normalizedOperation)
                .orElse(null);
        if (entity == null || !Boolean.TRUE.equals(entity.getTerminal())) {
            return;
        }
        Instant now = Instant.now();
        applyTaskEvidence(entity, evidence);
        String result = clean(evidence.result(), entity.getResult());
        entity.setTerminal(true);
        entity.setCompletedAt(now);
        entity.setResult(result);
        entity.setStatus(clean(evidence.taskStatus(), entity.getStatus()));
        entity.setSafeErrorSummary(null);
        auditRepository.save(entity);
        if (OPERATION_TASK_TERMINATE.equals(normalizedOperation)
                && Boolean.TRUE.equals(evidence.taskTerminal())) {
            appendStageOnce(entity.getClientRequestId(),
                    STAGE_TERMINATION_EVIDENCE_OBSERVED, "SUCCEEDED", null, now);
        } else if (OPERATION_TASK_RECONCILE.equals(normalizedOperation)) {
            appendStageOnce(entity.getClientRequestId(),
                    "RECONCILIATION_CHANGED".equals(result)
                            ? STAGE_RECONCILIATION_EVIDENCE_OBSERVED
                            : STAGE_RECONCILIATION_NO_CHANGE,
                    "SUCCEEDED", null, now);
        }
        if ("REVOKED".equalsIgnoreCase(entity.getTaskTokenStatus())) {
            appendStageOnce(entity.getClientRequestId(),
                    STAGE_TASK_TOKEN_REVOKED, "SUCCEEDED", null, now);
        }
        appendStageOnce(entity.getClientRequestId(),
                STAGE_REQUEST_COMPLETED, "SUCCEEDED", null, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void taskOperationFailed(AuditHandle handle, String sanitizedErrorCode) {
        RuntimeRequestAuditEntity entity = requireAudit(handle);
        if (Boolean.TRUE.equals(entity.getTerminal())) {
            return;
        }
        fail(entity, requireSanitizedCode(sanitizedErrorCode), "Runtime task operation did not complete", Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditHandle beginRuntimeToken(
            String clientRequestId,
            String operation,
            String appKey,
            String agentCode,
            String upstreamUserId) {
        String requestId = requireRequestId(clientRequestId);
        String normalizedOperation = normalizeOperation(operation);
        OwnerScope owner = resolveOwnerByAppKey(appKey);
        if (auditRepository.findByClientRequestId(requestId).isPresent()) {
            throw new IllegalArgumentException("CLIENT_REQUEST_ID_ALREADY_USED");
        }

        Instant now = Instant.now();
        RuntimeRequestAuditEntity entity = baseEntity(
                requestId, normalizedOperation, owner, agentCode, upstreamUserId, now);
        entity.setRuntimeTokenRequestReceived(true);
        entity.setRuntimeTokenIssued(null);
        entity.setRuntimeTokenExchangeCount(1);
        entity.setSafeSmokeRequestReceived(null);
        entity.setSyntheticEvidenceCreated(null);
        entity.setStatus("RUNTIME_TOKEN_REQUEST_RECEIVED");
        saveNew(entity);
        appendStage(requestId, STAGE_CLIENT_REQUEST_RECEIVED, "RECEIVED", null, now);
        appendStage(requestId, STAGE_RUNTIME_TOKEN_REQUEST_RECEIVED, "RECEIVED", null, now);
        return new AuditHandle(requestId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditHandle beginSafeSmoke(
            String clientRequestId,
            ResolvedClientAppCredentialDTO credential,
            String agentCode,
            String upstreamUserId) {
        String requestId = requireRequestId(clientRequestId);
        OwnerScope owner = resolveOwner(credential);
        Instant now = Instant.now();
        RuntimeRequestAuditEntity entity = auditRepository.findByClientRequestId(requestId).orElse(null);
        if (entity == null) {
            entity = baseEntity(requestId, OPERATION_SAFE_ASK, owner, agentCode, upstreamUserId, now);
            entity.setRuntimeTokenRequestReceived(false);
            entity.setRuntimeTokenIssued(null);
            entity.setSafeSmokeRequestReceived(true);
            entity.setSyntheticEvidenceCreated(false);
            entity.setStatus("SAFE_SMOKE_REQUEST_RECEIVED");
            saveNew(entity);
            appendStage(requestId, STAGE_CLIENT_REQUEST_RECEIVED, "RECEIVED", null, now);
        } else {
            requireSameOwner(entity, owner);
            if (!OPERATION_SAFE_ASK.equals(entity.getOperation())) {
                throw new IllegalArgumentException("CLIENT_REQUEST_ID_OPERATION_MISMATCH");
            }
            if (Boolean.TRUE.equals(entity.getSafeSmokeRequestReceived()) || Boolean.TRUE.equals(entity.getTerminal())) {
                throw new IllegalArgumentException("CLIENT_REQUEST_ID_ALREADY_USED");
            }
            entity.setAgentCode(clean(agentCode, entity.getAgentCode()));
            entity.setUpstreamUserId(clean(upstreamUserId, entity.getUpstreamUserId()));
            entity.setSafeSmokeRequestReceived(true);
            entity.setSyntheticEvidenceCreated(false);
            entity.setStatus("SAFE_SMOKE_REQUEST_RECEIVED");
            auditRepository.save(entity);
        }
        appendStage(requestId, STAGE_SAFE_SMOKE_REQUEST_RECEIVED, "RECEIVED", null, now);
        return new AuditHandle(requestId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runtimeTokenIssued(AuditHandle handle) {
        RuntimeRequestAuditEntity entity = requireAudit(handle);
        Instant now = Instant.now();
        entity.setRuntimeTokenIssued(true);
        appendStage(entity.getClientRequestId(), STAGE_RUNTIME_TOKEN_ISSUED, "SUCCEEDED", null, now);
        if (OPERATION_RUNTIME_TOKEN.equals(entity.getOperation())) {
            complete(entity, "RUNTIME_TOKEN_ISSUED", "COMPLETED", now);
        } else if (OPERATION_SAFE_ASK.equals(entity.getOperation())) {
            entity.setSafeSmokeRequestReceived(false);
            entity.setSyntheticEvidenceCreated(false);
            entity.setStatus("WAITING_FOR_SAFE_SMOKE");
            auditRepository.save(entity);
        } else {
            entity.setStatus("WAITING_FOR_STANDARD_ASK");
            auditRepository.save(entity);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runtimeTokenRejected(AuditHandle handle, String sanitizedErrorCode) {
        RuntimeRequestAuditEntity entity = requireAudit(handle);
        Instant now = Instant.now();
        String code = requireSanitizedCode(sanitizedErrorCode);
        entity.setRuntimeTokenIssued(false);
        entity.setSafeSmokeRequestReceived(false);
        entity.setSyntheticEvidenceCreated(false);
        appendStage(entity.getClientRequestId(), STAGE_RUNTIME_TOKEN_REJECTED, "REJECTED", code, now);
        fail(entity, code, "Runtime credential request was rejected", now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void safeSmokeFailed(AuditHandle handle, String sanitizedErrorCode) {
        RuntimeRequestAuditEntity entity = requireAudit(handle);
        Instant now = Instant.now();
        String code = requireSanitizedCode(sanitizedErrorCode);
        entity.setSyntheticEvidenceCreated(false);
        fail(entity, code, "Safe-smoke request did not complete", now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void safeSmokeCompleted(AuditHandle handle, SafeSmokeEvidence evidence) {
        RuntimeRequestAuditEntity entity = requireAudit(handle);
        Instant now = Instant.now();
        entity.setSyntheticEvidenceCreated(true);
        entity.setTaskId(clean(evidence.taskId(), null));
        entity.setStatus(clean(evidence.status(), "COMPLETED"));
        entity.setEffectiveToolCount(evidence.effectiveToolCount());
        entity.setToolScopeKind(clean(evidence.toolScopeKind(), UNKNOWN));
        entity.setToolScopeSource(clean(evidence.toolScopeSource(), UNKNOWN));
        entity.setEffectiveFunctionCount(evidence.effectiveFunctionCount());
        entity.setFunctionScopeSource(clean(evidence.functionScopeSource(), UNKNOWN));
        entity.setTaskTokenFunctionScopeEmpty(evidence.taskTokenFunctionScopeEmpty());
        entity.setTaskTokenStatus(clean(evidence.taskTokenStatus(), UNKNOWN));
        entity.setRuntimeDispatched(evidence.runtimeDispatched());
        appendStage(entity.getClientRequestId(), STAGE_SYNTHETIC_EVIDENCE_CREATED, "SUCCEEDED", null, now);
        if ("REVOKED".equalsIgnoreCase(entity.getTaskTokenStatus())) {
            appendStage(entity.getClientRequestId(), STAGE_TASK_TOKEN_REVOKED, "SUCCEEDED", null, now);
        }
        complete(entity, clean(evidence.result(), "SAFE_SMOKE_COMPLETED"), "COMPLETED", now);
    }

    @Transactional(readOnly = true)
    public RuntimeRequestAuditPageDTO querySelfAudit(
            String appKey,
            String appSecret,
            String requestId,
            Instant since,
            Instant until,
            String operation,
            String agentCode,
            String upstreamUserId,
            Integer requestedLimit) {
        ResolvedClientAppCredentialDTO credential = credentialResolver.resolve(appKey, appSecret)
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_AUDIT_CREDENTIAL_REQUIRED"));
        ClientAppEntity app = clientAppRepository
                .findByClientAppIdAndTenantId(credential.getClientAppId(), credential.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_AUDIT_SCOPE_NOT_FOUND"));
        OwnerScope owner = owner(credential.getCredentialId(), app);
        int limit = normalizeLimit(requestedLimit);
        String normalizedOperation = StringUtils.hasText(operation) ? normalizeOperation(operation) : null;
        List<RuntimeRequestAuditEntity> entities;
        Instant now = Instant.now();

        if (StringUtils.hasText(requestId)) {
            if (since != null || until != null) {
                throw new IllegalArgumentException("AUDIT_QUERY_MODE_CONFLICT");
            }
            String normalizedRequestId = requireRequestId(requestId);
            RuntimeRequestAuditEntity entity = auditRepository
                    .findByClientRequestIdAndTenantIdAndUpstreamSystemIdAndClientAppIdAndExpiresAtAfter(
                            normalizedRequestId,
                            owner.tenantId(),
                            owner.upstreamSystemId(),
                            owner.clientAppId(),
                            now)
                    .filter(value -> normalizedOperation == null || normalizedOperation.equals(value.getOperation()))
                    .filter(value -> !StringUtils.hasText(agentCode) || agentCode.trim().equals(value.getAgentCode()))
                    .filter(value -> !StringUtils.hasText(upstreamUserId)
                            || upstreamUserId.trim().equals(value.getUpstreamUserId()))
                    .orElseThrow(() -> new IllegalArgumentException("AUDIT_RECORD_EXPIRED_OR_NOT_FOUND"));
            entities = List.of(entity);
        } else {
            validateWindow(since, until, now);
            entities = auditRepository.findVisibleWindow(
                    owner.tenantId(),
                    owner.upstreamSystemId(),
                    owner.clientAppId(),
                    since,
                    until,
                    now,
                    normalizedOperation,
                    clean(agentCode, null),
                    clean(upstreamUserId, null),
                    PageRequest.of(0, limit));
        }

        List<RuntimeRequestAuditDTO> items = entities.stream().map(this::toDto).toList();
        return RuntimeRequestAuditPageDTO.builder()
                .count(items.size())
                .limit(limit)
                .items(items)
                .build();
    }

    private RuntimeRequestAuditEntity baseEntity(
            String requestId,
            String operation,
            OwnerScope owner,
            String agentCode,
            String upstreamUserId,
            Instant now) {
        RuntimeRequestAuditEntity entity = new RuntimeRequestAuditEntity();
        entity.setClientRequestId(requestId);
        entity.setOperation(operation);
        entity.setCorrelationId(requestId);
        entity.setTenantId(owner.tenantId());
        entity.setUpstreamSystemId(owner.upstreamSystemId());
        entity.setClientAppId(owner.clientAppId());
        entity.setCredentialId(owner.credentialId());
        entity.setAgentCode(clean(agentCode, null));
        entity.setUpstreamUserId(clean(upstreamUserId, null));
        entity.setReceivedAt(now);
        entity.setExpiresAt(now.plus(effectiveRetention()));
        entity.setTerminal(false);
        entity.setResult(UNKNOWN);
        entity.setSanitizedErrorCode(null);
        entity.setHttpRequestReceived(true);
        entity.setRuntimeTokenRequestReceived(false);
        entity.setRuntimeTokenIssued(false);
        entity.setRuntimeTokenExchangeCount(0);
        entity.setStandardAskRequestReceived(false);
        entity.setAdmissionCompleted(false);
        entity.setTaskCreated(false);
        entity.setTaskTokenIssued(false);
        entity.setTaskId(null);
        entity.setStatus("RECEIVED");
        entity.setToolScopeKind(UNKNOWN);
        entity.setToolScopeSource(UNKNOWN);
        entity.setFunctionScopeSource(UNKNOWN);
        entity.setTaskTokenStatus(UNKNOWN);
        entity.setRuntimeDispatched(null);
        entity.setModelDispatched(null);
        entity.setBusinessFunctionDispatched(null);
        return entity;
    }

    private void applyParentCorrelation(
            RuntimeRequestAuditEntity entity,
            String parentClientRequestId,
            OwnerScope owner) {
        entity.setParentClientRequestId(parentClientRequestId);
        entity.setCorrelationId(parentClientRequestId == null ? entity.getClientRequestId() : parentClientRequestId);
        if (parentClientRequestId == null) {
            return;
        }
        RuntimeRequestAuditEntity parent = auditRepository.findByClientRequestId(parentClientRequestId).orElse(null);
        if (parent == null) {
            throw new IllegalArgumentException("PARENT_CLIENT_REQUEST_ID_NOT_FOUND");
        }
        requireSameOwner(parent, owner);
        if (!Boolean.TRUE.equals(parent.getRuntimeTokenRequestReceived())
                || !Boolean.TRUE.equals(parent.getRuntimeTokenIssued())) {
            throw new IllegalArgumentException("PARENT_RUNTIME_TOKEN_NOT_ISSUED");
        }
        entity.setRuntimeTokenRequestReceived(true);
        entity.setRuntimeTokenIssued(true);
        entity.setRuntimeTokenExchangeCount(Math.max(1,
                parent.getRuntimeTokenExchangeCount() == null ? 1 : parent.getRuntimeTokenExchangeCount()));
    }

    private void applyTaskEvidence(RuntimeRequestAuditEntity entity, TaskEvidence evidence) {
        entity.setTaskId(clean(evidence.taskId(), entity.getTaskId()));
        entity.setStatus(clean(evidence.taskStatus(), UNKNOWN));
        entity.setSanitizedErrorCode(clean(evidence.sanitizedErrorCode(), null));
        entity.setAgentCode(clean(evidence.agentCode(), entity.getAgentCode()));
        entity.setUpstreamUserId(clean(evidence.upstreamUserId(), entity.getUpstreamUserId()));
        entity.setPhysicalWorkerId(clean(evidence.physicalWorkerId(), null));
        entity.setModelConfigId(clean(evidence.modelConfigId(), null));
        entity.setModelVariant(clean(evidence.modelVariant(), null));
        entity.setRequestedToolCount(evidence.requestedToolCount());
        entity.setEffectiveToolCount(evidence.effectiveToolCount());
        entity.setToolScopeKind(clean(evidence.toolScopeKind(), UNKNOWN));
        entity.setToolScopeSource(clean(evidence.toolScopeSource(), UNKNOWN));
        entity.setRequestedFunctionCount(evidence.requestedFunctionCount());
        entity.setEffectiveFunctionCount(evidence.effectiveFunctionCount());
        entity.setFunctionScopeSource(clean(evidence.functionScopeSource(), UNKNOWN));
        entity.setTaskTokenFunctionScopeEmpty(evidence.taskTokenFunctionScopeEmpty());
        entity.setTaskTokenStatus(clean(evidence.taskTokenStatus(), UNKNOWN));
        entity.setRuntimeDispatched(evidence.runtimeDispatched());
        entity.setModelDispatched(evidence.modelDispatched());
        entity.setBusinessFunctionDispatched(evidence.businessFunctionDispatched());
        entity.setDispatchCount(evidence.dispatchCount());
        entity.setRetryCount(evidence.retryCount());
        entity.setRecoveryCount(evidence.recoveryCount());
    }

    private void complete(RuntimeRequestAuditEntity entity, String result, String status, Instant now) {
        entity.setTerminal(true);
        entity.setCompletedAt(now);
        entity.setResult(result);
        entity.setStatus(status);
        entity.setSafeErrorSummary(null);
        auditRepository.save(entity);
        appendStage(entity.getClientRequestId(), STAGE_REQUEST_COMPLETED, "SUCCEEDED", null, now);
    }

    private void fail(RuntimeRequestAuditEntity entity, String code, String summary, Instant now) {
        entity.setTerminal(true);
        entity.setCompletedAt(now);
        entity.setResult("FAILED");
        entity.setStatus("FAILED");
        entity.setSanitizedErrorCode(code);
        entity.setSafeErrorSummary(summary);
        auditRepository.save(entity);
        appendStage(entity.getClientRequestId(), STAGE_REQUEST_FAILED, "FAILED", code, now);
    }

    private void failAsk(RuntimeRequestAuditEntity entity, String code) {
        Instant now = Instant.now();
        if (!Boolean.TRUE.equals(entity.getAdmissionCompleted())) {
            entity.setAdmissionCompleted(false);
        }
        if (!Boolean.TRUE.equals(entity.getTaskCreated())) {
            entity.setTaskCreated(false);
            entity.setTaskId(null);
        }
        if (!Boolean.TRUE.equals(entity.getTaskTokenIssued())) {
            entity.setTaskTokenIssued(false);
            entity.setTaskTokenStatus("NOT_ISSUED");
        }
        if (!Boolean.TRUE.equals(entity.getRuntimeDispatched())) {
            entity.setRuntimeDispatched(false);
        }
        if (!Boolean.TRUE.equals(entity.getModelDispatched())) {
            entity.setModelDispatched(false);
        }
        if (!Boolean.TRUE.equals(entity.getBusinessFunctionDispatched())) {
            entity.setBusinessFunctionDispatched(false);
        }
        entity.setDispatchCount(entity.getDispatchCount() == null ? 0 : entity.getDispatchCount());
        entity.setRetryCount(entity.getRetryCount() == null ? 0 : entity.getRetryCount());
        entity.setRecoveryCount(entity.getRecoveryCount() == null ? 0 : entity.getRecoveryCount());
        fail(entity, code, "STANDARD ask request did not complete", now);
    }

    private RuntimeRequestAuditEntity requireAudit(AuditHandle handle) {
        if (handle == null || !StringUtils.hasText(handle.clientRequestId())) {
            throw new IllegalArgumentException("RUNTIME_AUDIT_HANDLE_REQUIRED");
        }
        return auditRepository.findByClientRequestId(handle.clientRequestId())
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_AUDIT_RECORD_NOT_FOUND"));
    }

    private void saveNew(RuntimeRequestAuditEntity entity) {
        try {
            auditRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("CLIENT_REQUEST_ID_ALREADY_USED");
        }
    }

    private void appendStage(String requestId, String stage, String status, String errorCode, Instant occurredAt) {
        RuntimeRequestAuditStageEntity event = new RuntimeRequestAuditStageEntity();
        event.setClientRequestId(requestId);
        event.setStage(stage);
        event.setStatus(status);
        event.setSanitizedErrorCode(errorCode);
        event.setOccurredAt(occurredAt);
        stageRepository.save(event);
    }

    private void appendStageOnce(String requestId, String stage, String status, String errorCode, Instant occurredAt) {
        boolean exists = stageRepository.findByClientRequestIdOrderByOccurredAtAscIdAsc(requestId).stream()
                .anyMatch(value -> stage.equals(value.getStage()));
        if (!exists) {
            appendStage(requestId, stage, status, errorCode, occurredAt);
        }
    }

    private RuntimeRequestAuditDTO toDto(RuntimeRequestAuditEntity entity) {
        List<RuntimeRequestAuditStageDTO> recordedStages = stageRepository
                .findByClientRequestIdOrderByOccurredAtAscIdAsc(entity.getClientRequestId())
                .stream()
                .map(stage -> RuntimeRequestAuditStageDTO.builder()
                        .stage(stage.getStage())
                        .status(stage.getStatus())
                        .sanitizedErrorCode(stage.getSanitizedErrorCode())
                        .occurredAt(stage.getOccurredAt())
                        .build())
                .toList();
        List<RuntimeRequestAuditStageDTO> stages = completeStages(entity, recordedStages);
        return RuntimeRequestAuditDTO.builder()
                .clientRequestId(entity.getClientRequestId())
                .parentClientRequestId(entity.getParentClientRequestId())
                .correlationId(clean(entity.getCorrelationId(), entity.getClientRequestId()))
                .operation(entity.getOperation())
                .receivedAt(entity.getReceivedAt())
                .completedAt(entity.getCompletedAt())
                .terminal(entity.getTerminal())
                .result(clean(entity.getResult(), UNKNOWN))
                .sanitizedErrorCode(entity.getSanitizedErrorCode())
                .safeErrorSummary(entity.getSafeErrorSummary())
                .httpRequestReceived(entity.getHttpRequestReceived())
                .runtimeTokenRequestReceived(entity.getRuntimeTokenRequestReceived())
                .runtimeTokenIssued(entity.getRuntimeTokenIssued())
                .runtimeTokenExchangeCount(entity.getRuntimeTokenExchangeCount())
                .standardAskRequestReceived(entity.getStandardAskRequestReceived())
                .admissionCompleted(entity.getAdmissionCompleted())
                .taskCreated(entity.getTaskCreated())
                .taskTokenIssued(entity.getTaskTokenIssued())
                .safeSmokeRequestReceived(entity.getSafeSmokeRequestReceived())
                .syntheticEvidenceCreated(entity.getSyntheticEvidenceCreated())
                .taskId(entity.getTaskId())
                .agentCode(entity.getAgentCode())
                .upstreamUserId(entity.getUpstreamUserId())
                .physicalWorkerId(entity.getPhysicalWorkerId())
                .modelConfigId(entity.getModelConfigId())
                .modelVariant(entity.getModelVariant())
                .status(clean(entity.getStatus(), UNKNOWN))
                .requestedToolCount(entity.getRequestedToolCount())
                .effectiveToolCount(entity.getEffectiveToolCount())
                .toolScopeKind(clean(entity.getToolScopeKind(), UNKNOWN))
                .toolScopeSource(clean(entity.getToolScopeSource(), UNKNOWN))
                .requestedFunctionCount(entity.getRequestedFunctionCount())
                .effectiveFunctionCount(entity.getEffectiveFunctionCount())
                .functionScopeSource(clean(entity.getFunctionScopeSource(), UNKNOWN))
                .taskTokenFunctionScopeEmpty(entity.getTaskTokenFunctionScopeEmpty())
                .taskTokenStatus(clean(entity.getTaskTokenStatus(), UNKNOWN))
                .runtimeDispatched(entity.getRuntimeDispatched())
                .modelDispatched(entity.getModelDispatched())
                .businessFunctionDispatched(entity.getBusinessFunctionDispatched())
                .dispatchCount(entity.getDispatchCount())
                .retryCount(entity.getRetryCount())
                .recoveryCount(entity.getRecoveryCount())
                .taskFacts(toTaskFacts(entity))
                .auditSideEffects(readOnlyAuditSideEffects())
                .stages(stages)
                .build();
    }

    private RuntimeRequestTaskFactsDTO toTaskFacts(RuntimeRequestAuditEntity entity) {
        return RuntimeRequestTaskFactsDTO.builder()
                .taskId(entity.getTaskId())
                .status(clean(entity.getStatus(), UNKNOWN))
                .terminal(Boolean.TRUE.equals(entity.getTaskCreated())
                        && isTerminalTaskStatus(entity.getStatus()))
                .sanitizedErrorCode(entity.getSanitizedErrorCode())
                .agentCode(entity.getAgentCode())
                .upstreamUserId(entity.getUpstreamUserId())
                .physicalWorkerId(entity.getPhysicalWorkerId())
                .modelConfigId(entity.getModelConfigId())
                .modelVariant(entity.getModelVariant())
                .requestedToolCount(entity.getRequestedToolCount())
                .effectiveToolCount(entity.getEffectiveToolCount())
                .toolScopeKind(clean(entity.getToolScopeKind(), UNKNOWN))
                .toolScopeSource(clean(entity.getToolScopeSource(), UNKNOWN))
                .requestedFunctionCount(entity.getRequestedFunctionCount())
                .effectiveFunctionCount(entity.getEffectiveFunctionCount())
                .functionScopeSource(clean(entity.getFunctionScopeSource(), UNKNOWN))
                .taskTokenFunctionScopeEmpty(entity.getTaskTokenFunctionScopeEmpty())
                .taskTokenStatus(clean(entity.getTaskTokenStatus(), UNKNOWN))
                .runtimeDispatched(entity.getRuntimeDispatched())
                .modelDispatched(entity.getModelDispatched())
                .businessFunctionDispatched(entity.getBusinessFunctionDispatched())
                .dispatchCount(entity.getDispatchCount())
                .retryCount(entity.getRetryCount())
                .recoveryCount(entity.getRecoveryCount())
                .build();
    }

    private RuntimeRequestAuditSideEffectsDTO readOnlyAuditSideEffects() {
        return RuntimeRequestAuditSideEffectsDTO.builder()
                .newTaskCreated(false)
                .newContextCreated(false)
                .newSessionCreated(false)
                .accessTokenIssued(false)
                .runtimeTokenIssued(false)
                .taskTokenIssued(false)
                .taskCreated(false)
                .contextCreated(false)
                .sessionCreated(false)
                .modelDispatched(false)
                .modelRedispatched(false)
                .businessFunctionDispatched(false)
                .retryTriggered(false)
                .recoveryTriggered(false)
                .provisioningResourceChanged(false)
                .build();
    }

    private List<RuntimeRequestAuditStageDTO> completeStages(
            RuntimeRequestAuditEntity entity,
            List<RuntimeRequestAuditStageDTO> recorded) {
        if (!OPERATION_ASK.equals(entity.getOperation())) {
            return recorded;
        }
        java.util.ArrayList<RuntimeRequestAuditStageDTO> result = new java.util.ArrayList<>(recorded);
        appendMissing(result, STAGE_REQUEST_RECEIVED, true);
        appendMissing(result, STAGE_AUTHENTICATION, hasStage(recorded, STAGE_AUTHENTICATION));
        appendMissing(result, STAGE_RUNTIME_TOKEN_REQUEST,
                Boolean.TRUE.equals(entity.getRuntimeTokenRequestReceived()));
        appendMissing(result,
                Boolean.TRUE.equals(entity.getRuntimeTokenIssued())
                        ? STAGE_RUNTIME_TOKEN_ISSUED : STAGE_RUNTIME_TOKEN_NOT_ISSUED,
                Boolean.TRUE.equals(entity.getRuntimeTokenIssued()));
        appendMissing(result, STAGE_STANDARD_ASK_ADMISSION,
                Boolean.TRUE.equals(entity.getAdmissionCompleted()));
        appendMissing(result, STAGE_TOOL_SCOPE_RESOLVED,
                Boolean.TRUE.equals(entity.getAdmissionCompleted()));
        appendMissing(result, STAGE_FUNCTION_SCOPE_RESOLVED,
                Boolean.TRUE.equals(entity.getAdmissionCompleted()));
        appendMissing(result,
                Boolean.TRUE.equals(entity.getTaskCreated()) ? STAGE_TASK_CREATED : STAGE_TASK_NOT_CREATED,
                Boolean.TRUE.equals(entity.getTaskCreated()));
        appendMissing(result,
                Boolean.TRUE.equals(entity.getTaskTokenIssued())
                        ? STAGE_TASK_TOKEN_ISSUED : STAGE_TASK_TOKEN_NOT_ISSUED,
                Boolean.TRUE.equals(entity.getTaskTokenIssued()));
        appendMissing(result,
                Boolean.TRUE.equals(entity.getRuntimeDispatched())
                        ? STAGE_RUNTIME_DISPATCH : STAGE_RUNTIME_NOT_DISPATCHED,
                Boolean.TRUE.equals(entity.getRuntimeDispatched()));
        appendMissing(result,
                Boolean.TRUE.equals(entity.getModelDispatched())
                        ? STAGE_MODEL_DISPATCH : STAGE_MODEL_NOT_DISPATCHED,
                Boolean.TRUE.equals(entity.getModelDispatched()));
        appendMissing(result,
                Boolean.TRUE.equals(entity.getBusinessFunctionDispatched())
                        ? STAGE_BUSINESS_FUNCTION_DISPATCH : STAGE_BUSINESS_FUNCTION_NOT_DISPATCHED,
                Boolean.TRUE.equals(entity.getBusinessFunctionDispatched()));
        boolean taskTerminal = Boolean.TRUE.equals(entity.getTaskCreated())
                && isTerminalTaskStatus(entity.getStatus());
        appendMissing(result,
                taskTerminal ? STAGE_TASK_TERMINAL : STAGE_TASK_NOT_TERMINAL,
                taskTerminal);
        appendMissing(result,
                "REVOKED".equalsIgnoreCase(entity.getTaskTokenStatus())
                        ? STAGE_TASK_TOKEN_REVOKED : STAGE_TASK_TOKEN_NOT_REVOKED,
                "REVOKED".equalsIgnoreCase(entity.getTaskTokenStatus()));
        return List.copyOf(result);
    }

    private boolean hasStage(List<RuntimeRequestAuditStageDTO> stages, String name) {
        return stages.stream().anyMatch(stage -> name.equals(stage.getStage()));
    }

    private void appendMissing(
            List<RuntimeRequestAuditStageDTO> stages,
            String name,
            boolean occurred) {
        if (!hasStage(stages, name)) {
            stages.add(RuntimeRequestAuditStageDTO.builder()
                    .stage(name)
                    .status(occurred ? "SUCCEEDED" : "NOT_OCCURRED")
                    .occurredAt(null)
                    .build());
        }
    }

    private boolean isTerminalTaskStatus(String status) {
        return StringUtils.hasText(status) && Set.of(
                "COMPLETED", "FAILED", "CANCELLED", "ABORTED", "TIMED_OUT")
                .contains(status.trim().toUpperCase(Locale.ROOT));
    }

    private OwnerScope resolveOwnerByAppKey(String appKey) {
        if (!StringUtils.hasText(appKey)) {
            throw new IllegalArgumentException("RUNTIME_CLIENT_APP_KEY_REQUIRED");
        }
        ClientAppRuntimeCredentialEntity credential = runtimeCredentialRepository.findByAppKey(appKey.trim())
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_CLIENT_APP_KEY_UNKNOWN"));
        ClientAppEntity app = clientAppRepository
                .findByClientAppIdAndTenantId(credential.getClientAppId(), credential.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_CLIENT_APP_SCOPE_UNKNOWN"));
        return owner(credential.getCredentialId(), app);
    }

    private OwnerScope resolveOwner(ResolvedClientAppCredentialDTO credential) {
        if (credential == null
                || !StringUtils.hasText(credential.getCredentialId())
                || !StringUtils.hasText(credential.getTenantId())
                || !StringUtils.hasText(credential.getClientAppId())) {
            throw new IllegalArgumentException("RUNTIME_CLIENT_APP_CREDENTIAL_REQUIRED");
        }
        ClientAppEntity app = clientAppRepository
                .findByClientAppIdAndTenantId(credential.getClientAppId(), credential.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_CLIENT_APP_SCOPE_UNKNOWN"));
        return owner(credential.getCredentialId(), app);
    }

    private OwnerScope owner(String credentialId, ClientAppEntity app) {
        return new OwnerScope(
                credentialId,
                app.getTenantId(),
                clean(app.getUpstreamSystemId(), UNKNOWN),
                app.getClientAppId());
    }

    private void requireSameOwner(RuntimeRequestAuditEntity entity, OwnerScope owner) {
        if (!sameOwner(entity, owner)) {
            throw new IllegalArgumentException("CLIENT_REQUEST_ID_ALREADY_USED");
        }
    }

    private boolean sameOwner(RuntimeRequestAuditEntity entity, OwnerScope owner) {
        return entity.getTenantId().equals(owner.tenantId())
                && entity.getUpstreamSystemId().equals(owner.upstreamSystemId())
                && entity.getClientAppId().equals(owner.clientAppId());
    }

    private String requireRequestId(String clientRequestId) {
        if (!StringUtils.hasText(clientRequestId)) {
            throw new IllegalArgumentException("CLIENT_REQUEST_ID_REQUIRED");
        }
        try {
            return UUID.fromString(clientRequestId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("CLIENT_REQUEST_ID_INVALID");
        }
    }

    private String optionalRequestId(String clientRequestId) {
        return StringUtils.hasText(clientRequestId) ? requireRequestId(clientRequestId) : null;
    }

    private String normalizeOperation(String operation) {
        String value = clean(operation, OPERATION_RUNTIME_TOKEN).toLowerCase(Locale.ROOT);
        if (!OPERATIONS.contains(value)) {
            throw new IllegalArgumentException("RUNTIME_AUDIT_OPERATION_INVALID");
        }
        return value;
    }

    private int normalizeLimit(Integer requestedLimit) {
        int configuredMax = Math.max(1, Math.min(properties.getMaxLimit(), HARD_MAX_LIMIT));
        int configuredDefault = Math.max(1, Math.min(properties.getDefaultLimit(), configuredMax));
        int limit = requestedLimit == null ? configuredDefault : requestedLimit;
        if (limit < 1 || limit > configuredMax) {
            throw new IllegalArgumentException("RUNTIME_AUDIT_LIMIT_INVALID");
        }
        return limit;
    }

    private void validateWindow(Instant since, Instant until, Instant now) {
        if (since == null || until == null) {
            throw new IllegalArgumentException("RUNTIME_AUDIT_BOUNDED_WINDOW_REQUIRED");
        }
        if (until.isBefore(since)) {
            throw new IllegalArgumentException("RUNTIME_AUDIT_WINDOW_INVALID");
        }
        Duration window = Duration.between(since, until);
        Duration configuredMax = properties.getMaxQueryWindow();
        Duration maxWindow = configuredMax == null || configuredMax.isZero() || configuredMax.isNegative()
                ? HARD_MAX_QUERY_WINDOW
                : configuredMax.compareTo(HARD_MAX_QUERY_WINDOW) > 0
                        ? HARD_MAX_QUERY_WINDOW
                        : configuredMax;
        if (window.compareTo(maxWindow) > 0) {
            throw new IllegalArgumentException("RUNTIME_AUDIT_WINDOW_TOO_LARGE");
        }
        if (since.isAfter(now.plusSeconds(60))) {
            throw new IllegalArgumentException("RUNTIME_AUDIT_WINDOW_INVALID");
        }
    }

    private String requireSanitizedCode(String value) {
        String code = clean(value, "RUNTIME_REQUEST_FAILED");
        if (!code.matches("[A-Z][A-Z0-9_]{2,127}")) {
            return "RUNTIME_REQUEST_FAILED";
        }
        return code;
    }

    private String clean(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    @Scheduled(cron = "${navigator.runtime-audit.cleanup-cron:0 0 2 * * *}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupExpiredAudits() {
        int batchSize = Math.max(1, Math.min(properties.getCleanupBatchSize(), 1000));
        int maxBatches = Math.max(1, Math.min(properties.getCleanupMaxBatches(), 1000));
        for (int batch = 0; batch < maxBatches; batch++) {
            if (cleanupExpiredBatch(batchSize) < batchSize) {
                return;
            }
        }
    }

    private int cleanupExpiredBatch(int batchSize) {
        List<RuntimeRequestAuditEntity> expired = auditRepository
                .findByExpiresAtBeforeOrderByExpiresAtAsc(Instant.now(), PageRequest.of(0, batchSize));
        if (expired.isEmpty()) {
            return 0;
        }
        List<String> requestIds = expired.stream().map(RuntimeRequestAuditEntity::getClientRequestId).toList();
        stageRepository.deleteByClientRequestIdIn(requestIds);
        auditRepository.deleteAll(expired);
        return expired.size();
    }

    private Duration effectiveRetention() {
        Duration retention = properties.getRetention();
        return retention == null || retention.isZero() || retention.isNegative()
                ? DEFAULT_RETENTION
                : retention;
    }

    private Duration effectiveTerminationReceiptRetention() {
        Duration retention = properties.getTerminationReceiptRetention();
        return retention == null || retention.isZero() || retention.isNegative()
                ? DEFAULT_TERMINATION_RECEIPT_RETENTION
                : retention;
    }

    private boolean terminationConvergenceTimedOut(
            RuntimeRequestAuditEntity entity,
            Instant now) {
        Instant startedAt = entity.getCompletedAt() != null
                ? entity.getCompletedAt()
                : entity.getReceivedAt();
        if (startedAt == null) {
            return false;
        }
        Duration timeout = properties.getTerminationConvergenceTimeout();
        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? DEFAULT_TERMINATION_CONVERGENCE_TIMEOUT
                : timeout;
        return !startedAt.plus(effectiveTimeout).isAfter(now);
    }

    private record OwnerScope(String credentialId, String tenantId, String upstreamSystemId, String clientAppId) {
    }
}
