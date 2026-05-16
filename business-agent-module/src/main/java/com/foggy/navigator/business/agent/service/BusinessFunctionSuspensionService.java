package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.business.agent.event.BusinessSuspensionResumeDecisionEvent;
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
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessFunctionSuspensionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final String TYPE_APPROVAL_REQUIRED = "APPROVAL_REQUIRED";
    public static final String TYPE_USER_PAYMENT_REQUIRED = "USER_PAYMENT_REQUIRED";
    public static final String TYPE_USER_CONFIRMATION_REQUIRED = "USER_CONFIRMATION_REQUIRED";
    public static final String TYPE_EXTERNAL_CALLBACK_WAIT = "EXTERNAL_CALLBACK_WAIT";
    public static final String TYPE_MANUAL_CHECK_REQUIRED = "MANUAL_CHECK_REQUIRED";

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_RESUME_DISPATCHED = "RESUME_DISPATCHED";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_EXECUTING = "EXECUTING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_EXECUTE_FAILED = "EXECUTE_FAILED";

    private static final String EXECUTION_WAITING_DECISION = "WAITING_DECISION";
    private static final String EXECUTION_REQUESTED = "REQUESTED";
    private static final String EXECUTION_EXECUTING = "EXECUTING";
    private static final String EXECUTION_COMPLETED = "COMPLETED";
    private static final String EXECUTION_FAILED = "FAILED";
    private static final String EXECUTION_NOT_APPLICABLE = "NOT_APPLICABLE";
    private static final String WORKER_NOTIFICATION_NOT_DISPATCHED = "NOT_DISPATCHED";
    private static final String WORKER_NOTIFICATION_DISPATCH_REQUESTED = "DISPATCH_REQUESTED";
    private static final String BUSINESS_AGENT_ID = "business-agent";
    private static final String SUBTYPE_BUSINESS_FUNCTION_RESULT_MESSAGE = "business_function_result_message";
    private static final String DEFAULT_ADAPTER_SUCCESS_MESSAGE = "Adapter execution successful";

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
        entity.setSuspensionType(TYPE_APPROVAL_REQUIRED);
        entity.setStatus(STATUS_PENDING);
        entity.setBusinessExecutionStatus(EXECUTION_WAITING_DECISION);
        entity.setWorkerNotificationStatus(WORKER_NOTIFICATION_NOT_DISPATCHED);
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

            BusinessFunctionSuspensionEntity suspension = findSuspensionForUpdate(suspendId)
                    .orElseThrow(() -> new IllegalArgumentException("Suspension not found: " + suspendId));

            WorkerGatewayResumeForm.ApprovalResult result = form.getApprovalResult();
            if (result == null) {
                throw new IllegalArgumentException("approval_result is required");
            }
            String resultStatus = result.getStatus();
            boolean approved = "approved".equalsIgnoreCase(resultStatus);
            boolean rejected = "rejected".equalsIgnoreCase(resultStatus);
            if (!approved && !rejected) {
                throw new IllegalArgumentException("Unknown approval status: " + resultStatus);
            }

            String expectedHash = validateResumeBinding(suspension, tenantId, form.getBindingContext());
            if (!STATUS_PENDING.equals(suspension.getStatus())) {
                if (isIdempotentResumeReplay(suspension, approved, rejected)) {
                    log.info("Ignoring idempotent suspension resume replay: suspendId={}, status={}, decision={}",
                            suspendId, suspension.getStatus(), resultStatus);
                    return;
                }
                throw new IllegalStateException("Suspension is not in PENDING state: " + suspension.getStatus());
            }

            if (suspension.getExpiresAt() != null && suspension.getExpiresAt().isBefore(LocalDateTime.now())) {
                suspension.setStatus(STATUS_EXPIRED);
                repository.save(suspension);
                throw new IllegalStateException("Suspension has expired");
            }

            suspension.setApprovalId(result.getApprovalId());
            // For control plane, the actual user approving might be the admin
            suspension.setApprovedBy(userId);
            suspension.setApprovedAt(result.getApprovedAt() != null ? result.getApprovedAt() : LocalDateTime.now());
            suspension.setComment(result.getComment());

            if (approved) {
                suspension.setStatus(STATUS_RESUME_DISPATCHED);
                suspension.setBusinessExecutionStatus(EXECUTION_REQUESTED);
            } else {
                suspension.setStatus(STATUS_REJECTED);
                suspension.setBusinessExecutionStatus(EXECUTION_NOT_APPLICABLE);
            }
            suspension.setWorkerNotificationStatus(WORKER_NOTIFICATION_DISPATCH_REQUESTED);
            repository.save(suspension);

            WorkerGatewayResumeEvent workerNotificationEvent = WorkerGatewayResumeEvent.builder()
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

            BusinessSuspensionResumeDecisionEvent businessDecisionEvent = BusinessSuspensionResumeDecisionEvent.builder()
                    .source(this)
                    .taskId(suspension.getTaskId())
                    .workerTaskId(suspension.getWorkerTaskId())
                    .sessionId(suspension.getSessionId())
                    .workerSessionId(suspension.getWorkerSessionId())
                    .suspendId(suspension.getSuspendId())
                    .suspensionType(suspension.getSuspensionType())
                    .decisionStatus(resultStatus)
                    .comment(result.getComment())
                    .tenantId(suspension.getTenantId())
                    .clientAppId(suspension.getClientAppId())
                    .upstreamUserId(suspension.getUpstreamUserId())
                    .functionId(suspension.getFunctionId())
                    .version(suspension.getVersion())
                    .inputHash(expectedHash)
                    .build();

            publishResumeEventsAfterCommit(suspendId, workerNotificationEvent, businessDecisionEvent);

            log.info("Dispatched suspension resume events for suspendId: {}, status: {}, businessExecutionStatus={}, workerNotificationStatus={}",
                    suspendId, suspension.getStatus(), suspension.getBusinessExecutionStatus(), suspension.getWorkerNotificationStatus());

            // Audit: resume dispatched (best-effort)
            auditService.recordResumeDispatched(tenantId, suspendId);
            if (approved) {
                auditService.recordBusinessExecutionRequested(buildAuditToken(suspension), suspension.getFunctionId(), suspension.getVersion(), suspendId);
            }

        } catch (Exception e) {
            auditService.recordResumeFailed(tenantId, suspendId, e.getMessage());
            throw e;
        }
    }

    private void publishResumeEventsAfterCommit(String suspendId,
                                                WorkerGatewayResumeEvent workerNotificationEvent,
                                                BusinessSuspensionResumeDecisionEvent businessDecisionEvent) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishResumeEventsNow(suspendId, workerNotificationEvent, businessDecisionEvent);
                }
            });
            return;
        }
        publishResumeEventsNow(suspendId, workerNotificationEvent, businessDecisionEvent);
    }

    private void publishResumeEventsNow(String suspendId,
                                        WorkerGatewayResumeEvent workerNotificationEvent,
                                        BusinessSuspensionResumeDecisionEvent businessDecisionEvent) {
        try {
            eventPublisher.publishEvent(workerNotificationEvent);
        } catch (Exception e) {
            log.error("Failed to publish worker conversation resume notification for suspendId: {}", suspendId, e);
        }
        try {
            eventPublisher.publishEvent(businessDecisionEvent);
        } catch (Exception e) {
            log.error("Failed to publish business suspension execution decision for suspendId: {}", suspendId, e);
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleBusinessSuspensionResumeDecisionEvent(BusinessSuspensionResumeDecisionEvent event) {
        if (event == null || !"approved".equalsIgnoreCase(event.getDecisionStatus())) {
            return;
        }
        WorkerGatewayResumeEvent executionEvent = WorkerGatewayResumeEvent.builder()
                .source(event.getSource())
                .taskId(StringUtils.hasText(event.getWorkerTaskId()) ? event.getWorkerTaskId() : event.getTaskId())
                .sessionId(StringUtils.hasText(event.getWorkerSessionId()) ? event.getWorkerSessionId() : event.getSessionId())
                .businessSessionId(event.getSessionId())
                .suspendId(event.getSuspendId())
                .approvalResult(event.getDecisionStatus())
                .comment(event.getComment())
                .tenantId(event.getTenantId())
                .clientAppId(event.getClientAppId())
                .upstreamUserId(event.getUpstreamUserId())
                .functionId(event.getFunctionId())
                .inputHash(event.getInputHash())
                .build();
        try {
            executeApprovedSuspensionInternal(executionEvent);
        } catch (Exception e) {
            log.error("Approved business suspension execution failed: suspendId={}, functionId={}",
                    event.getSuspendId(), event.getFunctionId(), e);
        }
    }

    @Transactional
    public WorkerGatewayInvokeResponseDTO executeApprovedSuspension(WorkerGatewayResumeEvent event) {
        return executeApprovedSuspensionInternal(event);
    }

    private WorkerGatewayInvokeResponseDTO executeApprovedSuspensionInternal(WorkerGatewayResumeEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("resume event is required");
        }
        if (!"approved".equalsIgnoreCase(event.getApprovalResult())) {
            throw new IllegalStateException("Suspension execution requires approved result");
        }
        if (!StringUtils.hasText(event.getSuspendId())) {
            throw new IllegalArgumentException("suspendId is required");
        }

        BusinessFunctionSuspensionEntity suspension = findSuspensionForUpdate(event.getSuspendId())
                .orElseThrow(() -> new IllegalArgumentException("Suspension not found: " + event.getSuspendId()));

        validateExecutionBinding(suspension, event);

        String status = suspension.getStatus();
        if (STATUS_COMPLETED.equals(status)) {
            return idempotentExecutionResponse(suspension, "Suspension already completed; duplicate execution skipped.");
        }
        if (STATUS_EXECUTING.equals(status)) {
            return idempotentExecutionResponse(suspension, "Suspension execution already in progress; duplicate execution skipped.");
        }
        if (STATUS_EXECUTE_FAILED.equals(status)) {
            return idempotentExecutionResponse(suspension, "Suspension execution already failed; automatic replay skipped.");
        }
        if (!STATUS_RESUME_DISPATCHED.equals(status)) {
            throw new IllegalStateException("Suspension is not approved for execution: " + status);
        }

        BusinessTaskScopedTokenDTO auditToken = buildAuditToken(suspension);
        long startTime = System.currentTimeMillis();
        String inputHash = BusinessFunctionRuntimeAuditService.sha256(suspension.getInputJson());
        auditService.recordInvokeStarted(auditToken, suspension.getFunctionId(), suspension.getVersion(), inputHash);

        suspension.setStatus(STATUS_EXECUTING);
        suspension.setBusinessExecutionStatus(EXECUTION_EXECUTING);
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

            suspension.setStatus(STATUS_COMPLETED);
            suspension.setBusinessExecutionStatus(EXECUTION_COMPLETED);
            repository.save(suspension);

            long durationMs = System.currentTimeMillis() - startTime;
            String outputHash = BusinessFunctionRuntimeAuditService.sha256(adapterResult.getOutputJson());
            auditService.recordInvokeSuccess(auditToken, suspension.getFunctionId(), suspension.getVersion(), suspension.getSuspendId(), outputHash, durationMs);
            log.info("Approved business function adapter execution completed: suspendId={}, functionId={}, status={}, outputCode={}, dataPresent={}",
                    suspension.getSuspendId(),
                    suspension.getFunctionId(),
                    BusinessFunctionAdapterResult.STATUS_SUCCESS,
                    extractOutputCode(adapterResult.getOutputJson()),
                    hasOutputData(adapterResult.getOutputJson()));
            publishBusinessExecutionResultMessage(suspension, true, adapterResult, null);

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
            suspension.setStatus(STATUS_EXECUTE_FAILED);
            suspension.setBusinessExecutionStatus(EXECUTION_FAILED);
            repository.save(suspension);
            long durationMs = System.currentTimeMillis() - startTime;
            auditService.recordInvokeFailed(auditToken, suspension.getFunctionId(), suspension.getVersion(), suspension.getSuspendId(), "ADAPTER_ERROR", e.getMessage(), durationMs);
            publishBusinessExecutionResultMessage(suspension, false, null, e.getMessage());
            throw e;
        }
    }

    private Optional<BusinessFunctionSuspensionEntity> findSuspensionForUpdate(String suspendId) {
        Optional<BusinessFunctionSuspensionEntity> locked = repository.findBySuspendIdForUpdate(suspendId);
        if (locked != null && locked.isPresent()) {
            return locked;
        }
        Optional<BusinessFunctionSuspensionEntity> fallback = repository.findBySuspendId(suspendId);
        return fallback != null ? fallback : Optional.empty();
    }

    private String validateResumeBinding(BusinessFunctionSuspensionEntity suspension,
                                         String tenantId,
                                         WorkerGatewayResumeForm.BindingContext binding) {
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
        if (!StringUtils.hasText(binding.getInputHash())) {
            throw new SecurityException("input_hash is required for fail-closed validation");
        }

        String expectedHash = computeSha256Hex(suspension.getInputJson());
        if (!expectedHash.equalsIgnoreCase(binding.getInputHash())) {
            throw new SecurityException("Input hash mismatch");
        }
        return expectedHash;
    }

    private boolean isIdempotentResumeReplay(BusinessFunctionSuspensionEntity suspension, boolean approved, boolean rejected) {
        String status = suspension.getStatus();
        if (approved) {
            return STATUS_RESUME_DISPATCHED.equals(status)
                    || STATUS_EXECUTING.equals(status)
                    || STATUS_COMPLETED.equals(status)
                    || STATUS_EXECUTE_FAILED.equals(status);
        }
        return rejected && STATUS_REJECTED.equals(status);
    }

    private WorkerGatewayInvokeResponseDTO idempotentExecutionResponse(BusinessFunctionSuspensionEntity suspension, String message) {
        auditService.recordBusinessExecutionSkipped(buildAuditToken(suspension), suspension.getFunctionId(),
                suspension.getVersion(), suspension.getSuspendId(), message);

        WorkerGatewayInvokeResponseDTO response = new WorkerGatewayInvokeResponseDTO();
        response.setFunctionId(suspension.getFunctionId());
        response.setVersion(suspension.getVersion());
        response.setStatus(WorkerGatewayInvokeResponseDTO.STATUS_SUCCESS);
        response.setApprovalRequired(true);
        response.setSuspendId(suspension.getSuspendId());
        response.setMessage(message);
        return response;
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

    private void publishBusinessExecutionResultMessage(BusinessFunctionSuspensionEntity suspension,
                                                       boolean success,
                                                       BusinessFunctionAdapterResult adapterResult,
                                                       String errorMessage) {
        String sessionId = resolveConversationSessionId(suspension);
        if (!StringUtils.hasText(sessionId)) {
            log.warn("Skip business execution result message because sessionId is blank: suspendId={}",
                    suspension.getSuspendId());
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subtype", SUBTYPE_BUSINESS_FUNCTION_RESULT_MESSAGE);
        payload.put("content", buildBusinessExecutionResultContent(success, adapterResult, errorMessage));
        payload.put("status", success ? WorkerGatewayInvokeResponseDTO.STATUS_SUCCESS : "FAILED");
        payload.put("executionStatus", success ? EXECUTION_COMPLETED : EXECUTION_FAILED);
        payload.put("suspendId", suspension.getSuspendId());
        payload.put("functionId", suspension.getFunctionId());
        payload.put("version", suspension.getVersion());
        payload.put("businessTaskId", suspension.getTaskId());
        payload.put("businessSessionId", suspension.getSessionId());
        payload.put("workerTaskId", suspension.getWorkerTaskId());
        payload.put("workerSessionId", suspension.getWorkerSessionId());
        if (success && adapterResult != null) {
            payload.put("adapterMessage", adapterResult.getMessage());
            payload.put("outputCode", extractOutputCode(adapterResult.getOutputJson()));
            payload.put("hasOutputData", hasOutputData(adapterResult.getOutputJson()));
        } else if (!success && StringUtils.hasText(errorMessage)) {
            payload.put("errorMessage", truncate(errorMessage, 500));
        }

        AgentMessage message = AgentMessage.of(sessionId, BUSINESS_AGENT_ID, MessageType.TEXT_COMPLETE, payload);
        message.setTaskId(resolveConversationTaskId(suspension));
        publishBusinessExecutionResultMessageAfterCommit(suspension.getSuspendId(), message);
    }

    private String buildBusinessExecutionResultContent(boolean success,
                                                       BusinessFunctionAdapterResult adapterResult,
                                                       String errorMessage) {
        if (success) {
            if (adapterResult != null
                    && StringUtils.hasText(adapterResult.getMessage())
                    && !DEFAULT_ADAPTER_SUCCESS_MESSAGE.equals(adapterResult.getMessage())) {
                return adapterResult.getMessage();
            }
            return "业务函数执行完成。";
        }
        if (StringUtils.hasText(errorMessage)) {
            return "业务函数执行失败：" + truncate(errorMessage, 200);
        }
        return "业务函数执行失败。";
    }

    private void publishBusinessExecutionResultMessageAfterCommit(String suspendId, AgentMessage message) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishBusinessExecutionResultMessageNow(suspendId, message);
                }
            });
            return;
        }
        publishBusinessExecutionResultMessageNow(suspendId, message);
    }

    private void publishBusinessExecutionResultMessageNow(String suspendId, AgentMessage message) {
        try {
            eventPublisher.publishEvent(message);
        } catch (Exception e) {
            log.error("Failed to publish business execution result message for suspendId: {}", suspendId, e);
        }
    }

    private String resolveConversationSessionId(BusinessFunctionSuspensionEntity suspension) {
        return StringUtils.hasText(suspension.getWorkerSessionId())
                ? suspension.getWorkerSessionId()
                : suspension.getSessionId();
    }

    private String resolveConversationTaskId(BusinessFunctionSuspensionEntity suspension) {
        return StringUtils.hasText(suspension.getWorkerTaskId())
                ? suspension.getWorkerTaskId()
                : suspension.getTaskId();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
