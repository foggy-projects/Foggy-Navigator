package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeAuditDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionRuntimeAuditEntity;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRuntimeAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Best-effort persistent audit service for business function runtime events.
 * <p>
 * Audit writes are best-effort: exceptions during audit persistence are caught
 * and logged as warnings. They never block or modify the primary execution result.
 * Security validation remains fail-closed and occurs before any audit call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessFunctionRuntimeAuditService {

    public static final String EVENT_INVOKE_STARTED = "INVOKE_STARTED";
    public static final String EVENT_INVOKE_SUCCESS = "INVOKE_SUCCESS";
    public static final String EVENT_INVOKE_SUSPENDED = "INVOKE_SUSPENDED";
    public static final String EVENT_INVOKE_FAILED = "INVOKE_FAILED";
    public static final String EVENT_TOOL_MESSAGE = "TOOL_MESSAGE";
    public static final String EVENT_RESUME_REQUESTED = "RESUME_REQUESTED";
    public static final String EVENT_RESUME_DISPATCHED = "RESUME_DISPATCHED";
    public static final String EVENT_RESUME_FAILED = "RESUME_FAILED";

    private final BusinessFunctionRuntimeAuditRepository repository;

    // ---- Invoke lifecycle ----

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInvokeStarted(BusinessTaskScopedTokenDTO token, String functionId, String version, String inputHash) {
        try {
            BusinessFunctionRuntimeAuditEntity entity = buildFromToken(token);
            entity.setFunctionId(functionId);
            entity.setFunctionVersion(version);
            entity.setEventType(EVENT_INVOKE_STARTED);
            entity.setStatus("STARTED");
            entity.setInputHash(inputHash);
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Best-effort audit write failed for INVOKE_STARTED: {}", e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInvokeSuccess(BusinessTaskScopedTokenDTO token, String functionId, String version, String outputHash, Long durationMs) {
        try {
            BusinessFunctionRuntimeAuditEntity entity = buildFromToken(token);
            entity.setFunctionId(functionId);
            entity.setFunctionVersion(version);
            entity.setEventType(EVENT_INVOKE_SUCCESS);
            entity.setStatus("SUCCESS");
            entity.setOutputHash(outputHash);
            entity.setDurationMs(durationMs);
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Best-effort audit write failed for INVOKE_SUCCESS: {}", e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInvokeSuspended(BusinessTaskScopedTokenDTO token, String functionId, String version, String suspendId) {
        try {
            BusinessFunctionRuntimeAuditEntity entity = buildFromToken(token);
            entity.setFunctionId(functionId);
            entity.setFunctionVersion(version);
            entity.setEventType(EVENT_INVOKE_SUSPENDED);
            entity.setStatus("SUSPENDED");
            entity.setSuspendId(suspendId);
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Best-effort audit write failed for INVOKE_SUSPENDED: {}", e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInvokeFailed(BusinessTaskScopedTokenDTO token, String functionId, String version,
                                    String errorCode, String errorMessage, Long durationMs) {
        try {
            BusinessFunctionRuntimeAuditEntity entity = buildFromToken(token);
            entity.setFunctionId(functionId);
            entity.setFunctionVersion(version);
            entity.setEventType(EVENT_INVOKE_FAILED);
            entity.setStatus("FAILED");
            entity.setErrorCode(errorCode);
            entity.setErrorMessage(boundMessage(errorMessage));
            entity.setDurationMs(durationMs);
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Best-effort audit write failed for INVOKE_FAILED: {}", e.getMessage());
        }
    }

    // ---- Tool message ----

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordToolMessage(BusinessTaskScopedTokenDTO token, String toolName, String functionId,
                                   String status, String message) {
        try {
            BusinessFunctionRuntimeAuditEntity entity = buildFromToken(token);
            entity.setFunctionId(functionId);
            entity.setEventType(EVENT_TOOL_MESSAGE);
            entity.setStatus(status);
            entity.setErrorMessage(boundMessage(toolName + ": " + message));
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Best-effort audit write failed for TOOL_MESSAGE: {}", e.getMessage());
        }
    }

    // ---- Resume lifecycle ----

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResumeRequested(String tenantId, String suspendId, String userId) {
        try {
            BusinessFunctionRuntimeAuditEntity entity = new BusinessFunctionRuntimeAuditEntity();
            entity.setAuditId("aud_" + UUID.randomUUID().toString().replace("-", ""));
            entity.setTenantId(tenantId);
            entity.setSuspendId(suspendId);
            entity.setEventType(EVENT_RESUME_REQUESTED);
            entity.setStatus("REQUESTED");
            entity.setUpstreamUserId(userId);
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Best-effort audit write failed for RESUME_REQUESTED: {}", e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResumeDispatched(String tenantId, String suspendId) {
        try {
            BusinessFunctionRuntimeAuditEntity entity = new BusinessFunctionRuntimeAuditEntity();
            entity.setAuditId("aud_" + UUID.randomUUID().toString().replace("-", ""));
            entity.setTenantId(tenantId);
            entity.setSuspendId(suspendId);
            entity.setEventType(EVENT_RESUME_DISPATCHED);
            entity.setStatus("DISPATCHED");
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Best-effort audit write failed for RESUME_DISPATCHED: {}", e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResumeFailed(String tenantId, String suspendId, String errorMessage) {
        try {
            BusinessFunctionRuntimeAuditEntity entity = new BusinessFunctionRuntimeAuditEntity();
            entity.setAuditId("aud_" + UUID.randomUUID().toString().replace("-", ""));
            entity.setTenantId(tenantId);
            entity.setSuspendId(suspendId);
            entity.setEventType(EVENT_RESUME_FAILED);
            entity.setStatus("FAILED");
            entity.setErrorMessage(boundMessage(errorMessage));
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Best-effort audit write failed for RESUME_FAILED: {}", e.getMessage());
        }
    }

    // ---- Query ----

    public List<BusinessFunctionRuntimeAuditDTO> findByTenantIdAndTaskId(String tenantId, String taskId) {
        return repository.findByTenantIdAndTaskIdOrderByCreatedAtAsc(tenantId, taskId).stream()
                .map(BusinessFunctionRuntimeAuditDTO::fromEntity)
                .collect(Collectors.toList());
    }

    public List<BusinessFunctionRuntimeAuditDTO> findByTenantIdAndSuspendId(String tenantId, String suspendId) {
        return repository.findByTenantIdAndSuspendIdOrderByCreatedAtAsc(tenantId, suspendId).stream()
                .map(BusinessFunctionRuntimeAuditDTO::fromEntity)
                .collect(Collectors.toList());
    }

    // ---- Helpers ----

    private BusinessFunctionRuntimeAuditEntity buildFromToken(BusinessTaskScopedTokenDTO token) {
        BusinessFunctionRuntimeAuditEntity entity = new BusinessFunctionRuntimeAuditEntity();
        entity.setAuditId("aud_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setTenantId(token.getTenantId());
        entity.setClientAppId(token.getClientAppId());
        entity.setUpstreamUserId(token.getUpstreamUserId());
        entity.setTaskId(token.getTaskId());
        entity.setSessionId(token.getSessionId());
        entity.setWorkerPoolId(token.getWorkerPoolId());
        entity.setSkillId(token.getSkillId());
        return entity;
    }

    private String boundMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /**
     * Compute SHA-256 hash of a string for audit purposes (input/output hash).
     */
    public static String sha256(String input) {
        if (input == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
