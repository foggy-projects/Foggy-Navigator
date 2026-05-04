package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.common.event.WorkerGatewayResumeEvent;
import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionSuspensionEntity;
import com.foggy.navigator.business.agent.model.form.WorkerGatewayResumeForm;
import com.foggy.navigator.business.agent.repository.BusinessFunctionSuspensionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessFunctionSuspensionService {

    private final BusinessFunctionSuspensionRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final BusinessFunctionRuntimeAuditService auditService;

    @Transactional
    public BusinessFunctionSuspensionEntity createSuspension(BusinessTaskScopedTokenDTO token, String functionId, String version, String inputJson, String idempotencyKey) {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setTaskId(token.getTaskId());
        entity.setSessionId(token.getSessionId());
        entity.setTenantId(token.getTenantId());
        entity.setClientAppId(token.getClientAppId());
        entity.setUpstreamUserId(token.getUpstreamUserId());
        entity.setSkillId(token.getSkillId());
        entity.setFunctionId(functionId);
        entity.setVersion(version);
        entity.setInputJson(inputJson);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setStatus("PENDING");
        // default expiration, e.g., 24 hours
        entity.setExpiresAt(LocalDateTime.now().plusHours(24));

        return repository.save(entity);
    }

    @Transactional
    public void resumeSuspension(String tenantId, String userId, String suspendId, WorkerGatewayResumeForm form) {
        // Audit: resume requested (best-effort)
        auditService.recordResumeRequested(tenantId, suspendId, userId);

        try {
            if (form == null) {
                throw new IllegalArgumentException("Resume form cannot be null");
            }

            BusinessFunctionSuspensionEntity suspension = repository.findBySuspendId(suspendId)
                    .orElseThrow(() -> new IllegalArgumentException("Suspension not found: " + suspendId));

            if (!"PENDING".equals(suspension.getStatus())) {
                throw new IllegalStateException("Suspension is not in PENDING state: " + suspension.getStatus());
            }

            if (suspension.getExpiresAt() != null && suspension.getExpiresAt().isBefore(LocalDateTime.now())) {
                suspension.setStatus("EXPIRED");
                repository.save(suspension);
                throw new IllegalStateException("Suspension has expired");
            }

            // Binding Validation
            WorkerGatewayResumeForm.BindingContext binding = form.getBindingContext();
            if (binding == null) {
                throw new IllegalArgumentException("binding_context is required");
            }

            if (!suspension.getTenantId().equals(tenantId)) {
                throw new SecurityException("Tenant ID mismatch: unauthorized resume attempt");
            }
            if (!suspension.getClientAppId().equals(binding.getClientAppId())) {
                throw new SecurityException("Client App ID mismatch");
            }
            if (!suspension.getUpstreamUserId().equals(binding.getUpstreamUserId())) {
                throw new SecurityException("Upstream User ID mismatch");
            }
            if (!suspension.getTaskId().equals(binding.getTaskId())) {
                throw new SecurityException("Task ID mismatch");
            }
            if (!suspension.getSessionId().equals(binding.getSessionId())) {
                throw new SecurityException("Session ID mismatch");
            }
            if (!suspension.getFunctionId().equals(binding.getFunctionId())) {
                throw new SecurityException("Function ID mismatch");
            }
            if (!suspension.getVersion().equals(binding.getVersion())) {
                throw new SecurityException("Version mismatch");
            }

            // Input Hash Validation
            if (!StringUtils.hasText(binding.getInputHash())) {
                throw new SecurityException("input_hash is required for fail-closed validation");
            }

            String expectedHash = computeSha256Hex(suspension.getInputJson());
            if (!expectedHash.equalsIgnoreCase(binding.getInputHash())) {
                throw new SecurityException("Input hash mismatch");
            }

            WorkerGatewayResumeForm.ApprovalResult result = form.getApprovalResult();
            if (result == null) {
                throw new IllegalArgumentException("approval_result is required");
            }

            String resultStatus = result.getStatus();
            if ("approved".equalsIgnoreCase(resultStatus)) {
                suspension.setStatus("APPROVED");
            } else if ("rejected".equalsIgnoreCase(resultStatus)) {
                suspension.setStatus("REJECTED");
            } else {
                throw new IllegalArgumentException("Unknown approval status: " + resultStatus);
            }

            suspension.setApprovalId(result.getApprovalId());
            // For control plane, the actual user approving might be the admin
            suspension.setApprovedBy(userId);
            suspension.setApprovedAt(result.getApprovedAt() != null ? result.getApprovedAt() : LocalDateTime.now());
            suspension.setComment(result.getComment());

            // Publish event to notify LangGraph or other listeners
            WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                    .source(this)
                    .taskId(suspension.getTaskId())
                    .sessionId(suspension.getSessionId())
                    .suspendId(suspension.getSuspendId())
                    .approvalResult(resultStatus)
                    .comment(result.getComment())
                    .tenantId(suspension.getTenantId())
                    .clientAppId(suspension.getClientAppId())
                    .upstreamUserId(suspension.getUpstreamUserId())
                    .functionId(suspension.getFunctionId())
                    .inputHash(expectedHash)
                    .build();

            try {
                eventPublisher.publishEvent(event);
            } catch (Exception e) {
                log.error("Failed to publish WorkerGatewayResumeEvent for suspendId: {}", suspendId, e);
                throw new IllegalStateException("Failed to publish resume event: " + e.getMessage(), e);
            }

            log.info("Dispatched WorkerGatewayResumeEvent for suspendId: {}, status: {}", suspendId, suspension.getStatus());

            // Update status to RESUME_DISPATCHED after event emission
            suspension.setStatus("RESUME_DISPATCHED");
            repository.save(suspension);

            // Audit: resume dispatched (best-effort)
            auditService.recordResumeDispatched(tenantId, suspendId);

        } catch (Exception e) {
            auditService.recordResumeFailed(tenantId, suspendId, e.getMessage());
            throw e;
        }
    }

    private String computeSha256Hex(String input) {
        if (input == null) return "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
