package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.RuntimeRequestAuditDTO;
import com.foggy.navigator.business.agent.model.dto.RuntimeRequestAuditPageDTO;
import com.foggy.navigator.business.agent.model.dto.RuntimeRequestAuditStageDTO;
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
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeRequestAuditService {

    public static final String OPERATION_RUNTIME_TOKEN = "runtime-token";
    public static final String OPERATION_SAFE_ASK = "safe-ask";

    public static final String STAGE_CLIENT_REQUEST_RECEIVED = "CLIENT_REQUEST_RECEIVED";
    public static final String STAGE_RUNTIME_TOKEN_REQUEST_RECEIVED = "RUNTIME_TOKEN_REQUEST_RECEIVED";
    public static final String STAGE_RUNTIME_TOKEN_ISSUED = "RUNTIME_TOKEN_ISSUED";
    public static final String STAGE_RUNTIME_TOKEN_REJECTED = "RUNTIME_TOKEN_REJECTED";
    public static final String STAGE_SAFE_SMOKE_REQUEST_RECEIVED = "SAFE_SMOKE_REQUEST_RECEIVED";
    public static final String STAGE_SYNTHETIC_EVIDENCE_CREATED = "SYNTHETIC_EVIDENCE_CREATED";
    public static final String STAGE_TASK_TOKEN_REVOKED = "TASK_TOKEN_REVOKED";
    public static final String STAGE_REQUEST_COMPLETED = "REQUEST_COMPLETED";
    public static final String STAGE_REQUEST_FAILED = "REQUEST_FAILED";

    private static final String UNKNOWN = "UNKNOWN";
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(24);
    private static final Duration HARD_MAX_QUERY_WINDOW = Duration.ofMinutes(15);
    private static final int HARD_MAX_LIMIT = 100;
    private static final Set<String> OPERATIONS = Set.of(OPERATION_RUNTIME_TOKEN, OPERATION_SAFE_ASK);

    private final RuntimeRequestAuditRepository auditRepository;
    private final RuntimeRequestAuditStageRepository stageRepository;
    private final ClientAppRuntimeCredentialRepository runtimeCredentialRepository;
    private final ClientAppRepository clientAppRepository;
    private final ClientAppRuntimeCredentialResolver credentialResolver;
    private final RuntimeRequestAuditProperties properties;

    public record AuditHandle(String clientRequestId) {
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
        cleanupExpiredBatch();
        if (auditRepository.findByClientRequestId(requestId).isPresent()) {
            throw new IllegalArgumentException("CLIENT_REQUEST_ID_ALREADY_USED");
        }

        Instant now = Instant.now();
        RuntimeRequestAuditEntity entity = baseEntity(
                requestId, normalizedOperation, owner, agentCode, upstreamUserId, now);
        entity.setRuntimeTokenRequestReceived(true);
        entity.setRuntimeTokenIssued(null);
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
        cleanupExpiredBatch();
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
        } else {
            entity.setSafeSmokeRequestReceived(false);
            entity.setSyntheticEvidenceCreated(false);
            entity.setStatus("WAITING_FOR_SAFE_SMOKE");
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
        entity.setTaskId(null);
        entity.setStatus("RECEIVED");
        entity.setToolScopeKind(UNKNOWN);
        entity.setToolScopeSource(UNKNOWN);
        entity.setFunctionScopeSource(UNKNOWN);
        entity.setTaskTokenStatus(UNKNOWN);
        entity.setRuntimeDispatched(null);
        return entity;
    }

    private void complete(RuntimeRequestAuditEntity entity, String result, String status, Instant now) {
        entity.setTerminal(true);
        entity.setCompletedAt(now);
        entity.setResult(result);
        entity.setStatus(status);
        entity.setSanitizedErrorCode(null);
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

    private RuntimeRequestAuditDTO toDto(RuntimeRequestAuditEntity entity) {
        List<RuntimeRequestAuditStageDTO> stages = stageRepository
                .findByClientRequestIdOrderByOccurredAtAscIdAsc(entity.getClientRequestId())
                .stream()
                .map(stage -> RuntimeRequestAuditStageDTO.builder()
                        .stage(stage.getStage())
                        .status(stage.getStatus())
                        .sanitizedErrorCode(stage.getSanitizedErrorCode())
                        .occurredAt(stage.getOccurredAt())
                        .build())
                .toList();
        return RuntimeRequestAuditDTO.builder()
                .clientRequestId(entity.getClientRequestId())
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
                .safeSmokeRequestReceived(entity.getSafeSmokeRequestReceived())
                .syntheticEvidenceCreated(entity.getSyntheticEvidenceCreated())
                .taskId(entity.getTaskId())
                .status(clean(entity.getStatus(), UNKNOWN))
                .effectiveToolCount(entity.getEffectiveToolCount())
                .toolScopeKind(clean(entity.getToolScopeKind(), UNKNOWN))
                .toolScopeSource(clean(entity.getToolScopeSource(), UNKNOWN))
                .effectiveFunctionCount(entity.getEffectiveFunctionCount())
                .functionScopeSource(clean(entity.getFunctionScopeSource(), UNKNOWN))
                .taskTokenFunctionScopeEmpty(entity.getTaskTokenFunctionScopeEmpty())
                .taskTokenStatus(clean(entity.getTaskTokenStatus(), UNKNOWN))
                .runtimeDispatched(entity.getRuntimeDispatched())
                .stages(stages)
                .build();
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
        if (!entity.getTenantId().equals(owner.tenantId())
                || !entity.getUpstreamSystemId().equals(owner.upstreamSystemId())
                || !entity.getClientAppId().equals(owner.clientAppId())) {
            throw new IllegalArgumentException("CLIENT_REQUEST_ID_ALREADY_USED");
        }
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

    @Scheduled(
            fixedDelayString = "${navigator.runtime-audit.cleanup-interval:PT5M}",
            initialDelayString = "${navigator.runtime-audit.cleanup-initial-delay:PT5M}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupExpiredAudits() {
        cleanupExpiredBatch();
    }

    private void cleanupExpiredBatch() {
        int batchSize = Math.max(1, Math.min(properties.getCleanupBatchSize(), 1000));
        List<RuntimeRequestAuditEntity> expired = auditRepository
                .findByExpiresAtBeforeOrderByExpiresAtAsc(Instant.now(), PageRequest.of(0, batchSize));
        if (expired.isEmpty()) {
            return;
        }
        List<String> requestIds = expired.stream().map(RuntimeRequestAuditEntity::getClientRequestId).toList();
        stageRepository.deleteByClientRequestIdIn(requestIds);
        auditRepository.deleteAll(expired);
    }

    private Duration effectiveRetention() {
        Duration retention = properties.getRetention();
        return retention == null || retention.isZero() || retention.isNegative()
                ? DEFAULT_RETENTION
                : retention;
    }

    private record OwnerScope(String credentialId, String tenantId, String upstreamSystemId, String clientAppId) {
    }
}
