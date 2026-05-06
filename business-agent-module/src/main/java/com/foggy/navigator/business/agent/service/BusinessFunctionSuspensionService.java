package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.event.WorkerGatewayResumeEvent;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionSuspensionEntity;
import com.foggy.navigator.business.agent.model.form.WorkerGatewayResumeForm;
import com.foggy.navigator.business.agent.repository.BusinessFunctionSuspensionRepository;
import com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterInvoker;
import com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessFunctionSuspensionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BusinessFunctionSuspensionRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final BusinessFunctionRuntimeAuditService auditService;
    private final BusinessFunctionAuthorizationService authorizationService;
    private final BusinessFunctionAdapterInvoker adapterInvoker;

    @Transactional
    public BusinessFunctionSuspensionEntity createSuspension(BusinessTaskScopedTokenDTO token, String functionId, String version, String inputJson, String idempotencyKey) {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setTaskId(token.getTaskId());
        entity.setWorkerTaskId(token.getWorkerTaskId());
        entity.setWorkerSessionId(token.getWorkerSessionId());
        entity.setSessionId(token.getSessionId());
        entity.setTenantId(token.getTenantId());
        entity.setClientAppId(token.getClientAppId());
        entity.setUpstreamUserId(token.getUpstreamUserId());
        entity.setSkillId(token.getSkillId());
        entity.setWorkerPoolId(token.getWorkerPoolId());
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

            if ("approved".equalsIgnoreCase(resultStatus)) {
                suspension.setStatus("RESUME_DISPATCHED");
            }
            repository.save(suspension);

            // Publish event to notify LangGraph or other listeners
            WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                    .source(this)
                    .taskId(StringUtils.hasText(suspension.getWorkerTaskId()) ? suspension.getWorkerTaskId() : suspension.getTaskId())
                    .sessionId(StringUtils.hasText(suspension.getWorkerSessionId()) ? suspension.getWorkerSessionId() : suspension.getSessionId())
                    .businessSessionId(suspension.getSessionId())
                    .suspendId(suspension.getSuspendId())
                    .approvalResult(resultStatus)
                    .comment(result.getComment())
                    .tenantId(suspension.getTenantId())
                    .clientAppId(suspension.getClientAppId())
                    .upstreamUserId(suspension.getUpstreamUserId())
                    .functionId(suspension.getFunctionId())
                    .inputHash(expectedHash)
                    .build();

            publishResumeEventAfterCommit(suspendId, event);

            log.info("Dispatched WorkerGatewayResumeEvent for suspendId: {}, status: {}", suspendId, suspension.getStatus());

            // Audit: resume dispatched (best-effort)
            auditService.recordResumeDispatched(tenantId, suspendId);

        } catch (Exception e) {
            auditService.recordResumeFailed(tenantId, suspendId, e.getMessage());
            throw e;
        }
    }

    private void publishResumeEventAfterCommit(String suspendId, WorkerGatewayResumeEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishResumeEventNow(suspendId, event);
                }
            });
            return;
        }
        publishResumeEventNow(suspendId, event);
    }

    private void publishResumeEventNow(String suspendId, WorkerGatewayResumeEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.error("Failed to publish WorkerGatewayResumeEvent for suspendId: {}", suspendId, e);
        }
    }

    @Transactional
    public WorkerGatewayInvokeResponseDTO executeApprovedSuspension(WorkerGatewayResumeEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("resume event is required");
        }
        if (!"approved".equalsIgnoreCase(event.getApprovalResult())) {
            throw new IllegalStateException("Suspension execution requires approved result");
        }
        if (!StringUtils.hasText(event.getSuspendId())) {
            throw new IllegalArgumentException("suspendId is required");
        }

        BusinessFunctionSuspensionEntity suspension = repository.findBySuspendId(event.getSuspendId())
                .orElseThrow(() -> new IllegalArgumentException("Suspension not found: " + event.getSuspendId()));

        validateExecutionBinding(suspension, event);

        String status = suspension.getStatus();
        if ("COMPLETED".equals(status)) {
            throw new IllegalStateException("Suspension already completed: " + event.getSuspendId());
        }
        if ("EXECUTING".equals(status)) {
            throw new IllegalStateException("Suspension is already executing: " + event.getSuspendId());
        }
        if (!"APPROVED".equals(status) && !"RESUME_DISPATCHED".equals(status)) {
            throw new IllegalStateException("Suspension is not approved for execution: " + status);
        }

        BusinessTaskScopedTokenDTO auditToken = buildAuditToken(suspension);
        long startTime = System.currentTimeMillis();
        String inputHash = BusinessFunctionRuntimeAuditService.sha256(suspension.getInputJson());
        auditService.recordInvokeStarted(auditToken, suspension.getFunctionId(), suspension.getVersion(), inputHash);

        suspension.setStatus("EXECUTING");
        repository.save(suspension);

        try {
            BusinessFunctionRuntimeContextDTO context = authorizationService.resolveExecutableBusinessFunction(
                    suspension.getTenantId(),
                    suspension.getClientAppId(),
                    suspension.getUpstreamUserId(),
                    suspension.getSkillId(),
                    suspension.getFunctionId(),
                    suspension.getVersion()
            );
            attachSuspensionContext(context, suspension);

            BusinessFunctionAdapterResult adapterResult = adapterInvoker.invoke(context, suspension.getInputJson());
            if (adapterResult == null) {
                throw new IllegalArgumentException("Adapter execution returned no result");
            }
            if (!BusinessFunctionAdapterResult.STATUS_SUCCESS.equals(adapterResult.getStatus())) {
                String message = StringUtils.hasText(adapterResult.getMessage())
                        ? adapterResult.getMessage()
                        : "Adapter execution failed";
                throw new IllegalArgumentException(message);
            }

            suspension.setStatus("COMPLETED");
            repository.save(suspension);

            long durationMs = System.currentTimeMillis() - startTime;
            String outputHash = BusinessFunctionRuntimeAuditService.sha256(adapterResult.getOutputJson());
            auditService.recordInvokeSuccess(auditToken, suspension.getFunctionId(), suspension.getVersion(), outputHash, durationMs);
            log.info("Approved business function adapter execution completed: suspendId={}, functionId={}, status={}, outputCode={}, dataPresent={}",
                    suspension.getSuspendId(),
                    suspension.getFunctionId(),
                    BusinessFunctionAdapterResult.STATUS_SUCCESS,
                    extractOutputCode(adapterResult.getOutputJson()),
                    hasOutputData(adapterResult.getOutputJson()));

            WorkerGatewayInvokeResponseDTO response = new WorkerGatewayInvokeResponseDTO();
            response.setFunctionId(suspension.getFunctionId());
            response.setVersion(suspension.getVersion());
            response.setStatus(WorkerGatewayInvokeResponseDTO.STATUS_SUCCESS);
            response.setApprovalRequired(true);
            response.setSuspendId(suspension.getSuspendId());
            response.setOutputJson(adapterResult.getOutputJson());
            response.setMessage(adapterResult.getMessage());
            return response;
        } catch (Exception e) {
            suspension.setStatus("EXECUTE_FAILED");
            repository.save(suspension);
            long durationMs = System.currentTimeMillis() - startTime;
            auditService.recordInvokeFailed(auditToken, suspension.getFunctionId(), suspension.getVersion(), "ADAPTER_ERROR", e.getMessage(), durationMs);
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

    private void validateExecutionBinding(BusinessFunctionSuspensionEntity suspension, WorkerGatewayResumeEvent event) {
        requireText(event.getTenantId(), "tenantId is required");
        requireText(event.getClientAppId(), "clientAppId is required");
        requireText(event.getUpstreamUserId(), "upstreamUserId is required");
        requireText(event.getTaskId(), "taskId is required");
        String businessSessionId = StringUtils.hasText(event.getBusinessSessionId())
                ? event.getBusinessSessionId()
                : event.getSessionId();
        requireText(event.getSessionId(), "sessionId is required");
        requireText(businessSessionId, "businessSessionId is required");
        requireText(event.getFunctionId(), "functionId is required");
        requireText(event.getInputHash(), "inputHash is required");

        if (!suspension.getTenantId().equals(event.getTenantId())) {
            throw new SecurityException("Tenant ID mismatch");
        }
        if (!suspension.getClientAppId().equals(event.getClientAppId())) {
            throw new SecurityException("Client App ID mismatch");
        }
        if (!suspension.getUpstreamUserId().equals(event.getUpstreamUserId())) {
            throw new SecurityException("Upstream User ID mismatch");
        }
        String expectedTaskId = StringUtils.hasText(suspension.getWorkerTaskId()) ? suspension.getWorkerTaskId() : suspension.getTaskId();
        if (!expectedTaskId.equals(event.getTaskId())) {
            throw new SecurityException("Worker task ID mismatch");
        }
        if (!suspension.getSessionId().equals(businessSessionId)) {
            throw new SecurityException("Session ID mismatch");
        }
        if (!suspension.getFunctionId().equals(event.getFunctionId())) {
            throw new SecurityException("Function ID mismatch");
        }
        String expectedHash = BusinessFunctionRuntimeAuditService.sha256(suspension.getInputJson());
        if (!event.getInputHash().equalsIgnoreCase(expectedHash)) {
            throw new SecurityException("Input hash mismatch");
        }
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private void attachSuspensionContext(BusinessFunctionRuntimeContextDTO context, BusinessFunctionSuspensionEntity suspension) {
        context.setClientAppId(suspension.getClientAppId());
        context.setUpstreamUserId(suspension.getUpstreamUserId());
        context.setSkillId(suspension.getSkillId());
        context.setTaskId(suspension.getTaskId());
        context.setSessionId(suspension.getSessionId());
        context.setWorkerPoolId(suspension.getWorkerPoolId());
    }

    private BusinessTaskScopedTokenDTO buildAuditToken(BusinessFunctionSuspensionEntity suspension) {
        BusinessTaskScopedTokenDTO token = new BusinessTaskScopedTokenDTO();
        token.setTaskId(suspension.getTaskId());
        token.setWorkerTaskId(suspension.getWorkerTaskId());
        token.setWorkerSessionId(suspension.getWorkerSessionId());
        token.setSessionId(suspension.getSessionId());
        token.setTenantId(suspension.getTenantId());
        token.setClientAppId(suspension.getClientAppId());
        token.setUpstreamUserId(suspension.getUpstreamUserId());
        token.setSkillId(suspension.getSkillId());
        token.setWorkerPoolId(suspension.getWorkerPoolId());
        return token;
    }

    private String extractOutputCode(String outputJson) {
        if (!StringUtils.hasText(outputJson)) {
            return "UNKNOWN";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(outputJson);
            JsonNode code = root.get("code");
            return code == null || code.isNull() ? "UNKNOWN" : code.asText();
        } catch (Exception e) {
            return "UNPARSEABLE";
        }
    }

    private boolean hasOutputData(String outputJson) {
        if (!StringUtils.hasText(outputJson)) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(outputJson);
            JsonNode data = root.get("data");
            return data != null && !data.isNull();
        } catch (Exception e) {
            return false;
        }
    }
}
