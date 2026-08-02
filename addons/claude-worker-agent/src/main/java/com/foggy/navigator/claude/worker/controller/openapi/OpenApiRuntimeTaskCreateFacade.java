package com.foggy.navigator.claude.worker.controller.openapi;

import com.foggy.navigator.business.agent.service.BusinessAgentSessionService;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.business.agent.service.TerminalTaskBindingException;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.session.agent.TaskSubmittingA2aAgentDecorator;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.service.OpenApiSessionQueryService;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.agent.TaskSubmittingA2aAgent;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mutation boundary for one already authenticated and admitted Open API task creation.
 *
 * <p>The HTTP adapter owns caller credentials, header precedence, request validation and stable
 * response mapping. This facade accepts only server-verified references plus the read-only launch
 * plan, then performs exactly one provider submission and its token/session/audit side effects.
 * It deliberately has no query, closure, readiness, reconciliation or servlet dependency.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class OpenApiRuntimeTaskCreateFacade {

    private static final String BUSINESS_RUNTIME_TOKEN_REVOKED_BY = "system";
    private static final String BUSINESS_RUNTIME_SUBMIT_FAILURE_REASON =
            "open api task submission failed";
    private static final String BUSINESS_RUNTIME_MISSING_TASK_REASON =
            "open api task submission returned no task id";
    private static final String BUSINESS_RUNTIME_BIND_FAILURE_REASON =
            "open api task token binding failed";
    private static final String BUSINESS_RUNTIME_IMMEDIATE_TERMINAL_REASON =
            "open api task returned terminal after submission";

    private final CodingAgentRepository codingAgentRepository;
    private final UnifiedAgentResolver agentResolver;
    private final AgentSubmitPipeline agentSubmitPipeline;
    private final OpenApiSessionQueryService sessionQueryService;
    private final ObjectProvider<RuntimeRequestAuditService> runtimeRequestAuditService;
    private final ObjectProvider<BusinessAgentTaskService> businessAgentTaskService;
    private final ObjectProvider<BusinessAgentSessionService> businessAgentSessionService;

    public PrepareOutcome prepare(VerifiedCreateContext context) {
        requireContext(context);
        RuntimeRequestAuditService auditService = runtimeRequestAuditService.getIfAvailable();
        if (auditService == null) {
            return PrepareOutcome.rejected("RUNTIME_AUDIT_SERVICE_UNAVAILABLE");
        }

        RuntimeCredentialReference credential = context.credential();
        String agentOwnerUserId;
        try {
            agentOwnerUserId = resolveAgentOwnerUserId(context.agentId(), credential.tenantId());
        } catch (RuntimeException e) {
            auditService.askFailed(context.auditHandle(), "AGENT_OWNER_RESOLUTION_FAILED");
            throw e;
        }
        if (context.existingContextRequested()) {
            try {
                validateBusinessAgentContextOwnership(
                        credential,
                        context.upstreamUserId(),
                        context.contextId(),
                        context.agentId(),
                        agentOwnerUserId);
            } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                auditService.askFailed(context.auditHandle(), "CONTEXT_OWNERSHIP_REJECTED");
                return PrepareOutcome.rejected(e.getMessage(), "open api request rejected");
            }
        }

        AgentResolveContext resolveContext = AgentResolveContext.builder()
                .userId(agentOwnerUserId)
                .tenantId(credential.tenantId())
                .modelConfigId(context.modelConfigId())
                .requestSource("OPEN_API")
                .build();
        A2aAgent agent;
        try {
            agent = agentResolver.resolveAgent(context.agentId(), resolveContext)
                    .orElseThrow(() -> RX.throwB("Agent not found: " + context.agentId()));
        } catch (RuntimeException e) {
            auditService.askFailed(context.auditHandle(), "AGENT_RESOLUTION_FAILED");
            throw e;
        }
        return PrepareOutcome.prepared(new PreparedCreateContext(
                context,
                auditService,
                agentOwnerUserId,
                resolveContext,
                agent));
    }

    public CreateOutcome create(VerifiedCreateCommand command) {
        requireCommand(command);
        PreparedCreateContext prepared = command.preparedContext();
        VerifiedCreateContext context = prepared.context();
        RuntimeRequestAuditService auditService = prepared.auditService();
        RuntimeCredentialReference credential = context.credential();
        OpenApiRuntimeTaskLaunchPlanner.LaunchPlan launchPlan = command.launchPlan();
        String agentOwnerUserId = prepared.agentOwnerUserId();
        AgentResolveContext resolveContext = prepared.resolveContext();
        A2aAgent agent = prepared.agent();
        String modelConfigId = context.modelConfigId();

        Map<String, Object> metadata = launchPlan.mutableMetadata();
        BusinessAgentWorkerTaskLaunchRequest workerSelectionRequest =
                launchPlan.workerSelectionRequest(agentOwnerUserId);
        String businessRuntimeToken;
        try {
            businessRuntimeToken = enrichBusinessRuntimeContext(
                    credential,
                    metadata,
                    command.agentId(),
                    command.skillId(),
                    agentOwnerUserId,
                    command.upstreamUserId(),
                    command.contextId(),
                    workerSelectionRequest);
        } catch (RuntimeException e) {
            auditService.askFailed(command.auditHandle(), "TASK_TOKEN_ISSUANCE_FAILED");
            throw e;
        }

        A2aMessage message = A2aMessage.user(List.of(A2aPart.text(command.messageContent())));
        message.setContextId(command.contextId());
        if (!metadata.isEmpty()) {
            message.setMetadata(metadata);
        }
        TaskSubmittingA2aAgent submittingAgent = new TaskSubmittingA2aAgentDecorator(
                agent, agentSubmitPipeline, command.agentId(), resolveContext);
        A2aTask task;
        try {
            task = submittingAgent.submitTask(AgentTaskSubmitRequest.builder()
                    .agentId(command.agentId())
                    .providerType(stringValue(metadata.get("providerType")))
                    .resolveContext(resolveContext)
                    .message(message)
                    .prompt(command.messageContent())
                    .contextId(command.contextId())
                    .metadata(metadata)
                    .modelConfigId(modelConfigId)
                    .model(launchPlan.modelResource().modelName())
                    .workerId(stringValue(metadata.get("workerId")))
                    .directoryId(stringValue(metadata.get("directoryId")))
                    .cwd(stringValue(metadata.get("cwd")))
                    .maxTurns(command.maxTurns())
                    .attachments(launchPlan.normalizedAttachments().isEmpty()
                            ? null
                            : launchPlan.mutableNormalizedAttachments())
                    .build());
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            revokeBusinessRuntimeTokenAfterFailure(
                    credential.tenantId(),
                    businessRuntimeToken,
                    BUSINESS_RUNTIME_SUBMIT_FAILURE_REASON);
            auditService.askFailed(command.auditHandle(), "STANDARD_ASK_SUBMIT_REJECTED");
            return CreateOutcome.rejected(e.getMessage(), "open api request rejected");
        } catch (RuntimeException e) {
            revokeBusinessRuntimeTokenAfterFailure(
                    credential.tenantId(),
                    businessRuntimeToken,
                    BUSINESS_RUNTIME_SUBMIT_FAILURE_REASON);
            auditService.askFailed(command.auditHandle(), "STANDARD_ASK_SUBMIT_FAILED");
            throw e;
        }
        if (task == null || !StringUtils.hasText(task.getId())) {
            revokeBusinessRuntimeTokenAfterFailure(
                    credential.tenantId(),
                    businessRuntimeToken,
                    BUSINESS_RUNTIME_MISSING_TASK_REASON);
            auditService.askFailed(command.auditHandle(), "TASK_NOT_CREATED");
            return CreateOutcome.rejected(BUSINESS_RUNTIME_MISSING_TASK_REASON);
        }
        if (!StringUtils.hasText(task.getContextId())) {
            task.setContextId(command.contextId());
        }

        boolean terminalTaskObservedDuringBind = false;
        try {
            bindBusinessRuntimeTokenToWorkerTask(
                    credential.tenantId(), businessRuntimeToken, task, metadata);
        } catch (TerminalTaskBindingException e) {
            terminalTaskObservedDuringBind = true;
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            revokeBusinessRuntimeTokenAfterFailure(
                    credential.tenantId(),
                    businessRuntimeToken,
                    BUSINESS_RUNTIME_BIND_FAILURE_REASON);
            auditService.askFailed(command.auditHandle(), "TASK_TOKEN_BIND_REJECTED");
            return CreateOutcome.rejected(e.getMessage(), "open api request rejected");
        } catch (RuntimeException e) {
            revokeBusinessRuntimeTokenAfterFailure(
                    credential.tenantId(),
                    businessRuntimeToken,
                    BUSINESS_RUNTIME_BIND_FAILURE_REASON);
            auditService.askFailed(command.auditHandle(), "TASK_TOKEN_BIND_FAILED");
            throw e;
        }
        if (terminalTaskObservedDuringBind || isTerminalTask(task)) {
            revokeBusinessRuntimeTokenAfterFailure(
                    credential.tenantId(),
                    businessRuntimeToken,
                    BUSINESS_RUNTIME_IMMEDIATE_TERMINAL_REASON);
        }

        try {
            auditService.taskDispatchRecorded(
                    command.auditHandle(),
                    dispatchEvidence(command, task, metadata));
        } catch (RuntimeException e) {
            return CreateOutcome.rejected("RUNTIME_AUDIT_RECORDING_FAILED");
        }
        if (command.clientContextJson() != null) {
            sessionQueryService.updateClientContextJson(
                    command.contextId(),
                    agentOwnerUserId,
                    command.agentId(),
                    command.clientContextJson());
        }
        bindBusinessAgentSession(
                credential,
                command.upstreamUserId(),
                command.agentId(),
                command.contextId(),
                task,
                command.clientContextJson(),
                agentOwnerUserId);

        log.info("Open API runtime task created: agentId={}, skillId={}, taskId={}, tenantId={}",
                command.agentId(), command.skillId(), task.getId(), credential.tenantId());
        return CreateOutcome.created(task, metadata);
    }

    private void validateBusinessAgentContextOwnership(
            RuntimeCredentialReference credential,
            String upstreamUserId,
            String contextId,
            String agentId,
            String agentOwnerUserId) {
        if (!StringUtils.hasText(upstreamUserId)) {
            throw new IllegalArgumentException("upstream user id is required when contextId is provided");
        }
        BusinessAgentSessionService service = businessAgentSessionService.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("business agent session service is not available");
        }
        try {
            service.getSession(
                    credential.tenantId(),
                    credential.clientAppId(),
                    upstreamUserId,
                    contextId);
        } catch (IllegalArgumentException e) {
            if (isBusinessAgentSessionNotFound(e)
                    && hasRecoverableBusinessAgentSession(
                            credential,
                            upstreamUserId,
                            contextId,
                            agentId,
                            agentOwnerUserId)) {
                log.warn("Open API business session row missing but Navigator context exists; "
                                + "contextId={}, upstreamUserId={}",
                        contextId, upstreamUserId);
                return;
            }
            throw e;
        }
    }

    private boolean isBusinessAgentSessionNotFound(IllegalArgumentException e) {
        String message = e != null ? e.getMessage() : null;
        return StringUtils.hasText(message)
                && message.startsWith("business agent session not found:");
    }

    private boolean hasRecoverableBusinessAgentSession(
            RuntimeCredentialReference credential,
            String upstreamUserId,
            String contextId,
            String agentId,
            String agentOwnerUserId) {
        Optional<String> sessionId = resolveNavigatorSessionId(contextId, agentOwnerUserId, agentId);
        if (sessionId.isEmpty()) {
            return false;
        }
        BusinessAgentTaskService taskService = businessAgentTaskService.getIfAvailable();
        return taskService != null && taskService.hasOpenApiTaskScopedTokenForContext(
                credential.tenantId(),
                credential.clientAppId(),
                upstreamUserId,
                contextId);
    }

    private Optional<String> resolveNavigatorSessionId(
            String contextId,
            String agentOwnerUserId,
            String agentId) {
        if (!StringUtils.hasText(contextId) || !StringUtils.hasText(agentOwnerUserId)) {
            return Optional.empty();
        }
        Optional<AgentConversationContextEntity> context =
                sessionQueryService.findContextForUser(contextId, agentOwnerUserId);
        if (context == null || context.isEmpty()) {
            return Optional.empty();
        }
        return context
                .filter(entity -> !StringUtils.hasText(agentId)
                        || agentId.equals(entity.getTargetAgentId()))
                .map(AgentConversationContextEntity::getNavigatorSessionId)
                .filter(StringUtils::hasText);
    }

    private String enrichBusinessRuntimeContext(
            RuntimeCredentialReference credential,
            Map<String, Object> metadata,
            String rootAgentId,
            String skillId,
            String actorUserId,
            String upstreamUserId,
            String contextId,
            BusinessAgentWorkerTaskLaunchRequest workerSelectionRequest) {
        Map<String, Object> context = new LinkedHashMap<>();
        Object rawContext = metadata.get("context");
        if (rawContext instanceof Map<?, ?> existingContext) {
            existingContext.forEach((key, value) -> {
                if (key instanceof String stringKey
                        && !isReservedBusinessRuntimeContextKey(stringKey)) {
                    context.put(stringKey, value);
                }
            });
        }

        context.put("clientAppId", credential.clientAppId());
        context.put("rootAgentId", rootAgentId);
        context.put("credentialId", credential.credentialId());
        context.put("auto_inject_app_public_skills", true);
        if (StringUtils.hasText(upstreamUserId)) {
            context.put("upstreamUserId", upstreamUserId);
            context.put("accountId", upstreamUserId);
            context.put("account_id", upstreamUserId);
            String token = issueBusinessRuntimeToken(
                    credential,
                    actorUserId,
                    metadata,
                    upstreamUserId,
                    skillId,
                    contextId,
                    metadata.get("modelConfigId"),
                    workerSelectionRequest);
            metadata.put("context", context);
            return token;
        }
        metadata.put("context", context);
        return null;
    }

    private boolean isReservedBusinessRuntimeContextKey(String key) {
        return "clientAppId".equals(key)
                || "client_app_id".equals(key)
                || "rootAgentId".equals(key)
                || "businessSkillId".equals(key)
                || "businessSkillName".equals(key)
                || "credentialId".equals(key)
                || "auto_inject_app_public_skills".equals(key)
                || "upstreamUserId".equals(key)
                || "upstream_user_id".equals(key)
                || "accountId".equals(key)
                || "account_id".equals(key)
                || "skill_name".equals(key)
                || "skillName".equals(key)
                || "skillId".equals(key)
                || "skill_id".equals(key)
                || "skill_markdown".equals(key)
                || "skillMarkdown".equals(key)
                || "markdownBody".equals(key)
                || "task_scoped_token".equals(key)
                || "worker_id".equals(key)
                || "workerId".equals(key)
                || "worker_lease_id".equals(key)
                || "workerLeaseId".equals(key)
                || "runtimeContext".equals(key)
                || "runtime_context".equals(key);
    }

    private String issueBusinessRuntimeToken(
            RuntimeCredentialReference credential,
            String actorUserId,
            Map<String, Object> metadata,
            String upstreamUserId,
            String skillId,
            String sessionId,
            Object requestedModelConfigId,
            BusinessAgentWorkerTaskLaunchRequest workerSelectionRequest) {
        BusinessAgentTaskService service = businessAgentTaskService.getIfAvailable();
        if (service == null) {
            return null;
        }
        workerSelectionRequest.setCallerCredentialId(credential.credentialId());
        workerSelectionRequest.setCallerAccessTokenId(credential.runtimeAccessTokenId());
        BusinessAgentTaskService.PreparedOpenApiTaskScopedToken prepared;
        try {
            prepared = service.prepareOpenApiTaskScopedToken(
                    credential.tenantId(),
                    actorUserId,
                    credential.clientAppId(),
                    upstreamUserId,
                    skillId,
                    sessionId,
                    requestedModelConfigId instanceof String value ? value : null,
                    workerSelectionRequest);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            throw openApiRequestRejected(e);
        }
        if (prepared == null) {
            return null;
        }

        Map<String, Object> runtimeContext = new LinkedHashMap<>();
        Object existingRuntimeContext = metadata.get("runtimeContext");
        if (existingRuntimeContext instanceof Map<?, ?> existingMap) {
            existingMap.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    runtimeContext.put(stringKey, value);
                }
            });
        }
        runtimeContext.put("task_scoped_token", prepared.plainToken());
        runtimeContext.put("worker_id", prepared.workerId());
        runtimeContext.put("worker_lease_id", prepared.workerLeaseId());
        if (workerSelectionRequest.getAllowedTools() != null) {
            runtimeContext.put("allowed_tools", workerSelectionRequest.getAllowedTools());
        }
        metadata.put("runtimeContext", runtimeContext);
        metadata.put("workerId", prepared.workerId());
        metadata.put("workerLeaseId", prepared.workerLeaseId());
        metadata.put("effectiveFunctionCount", prepared.effectiveFunctionCount());
        metadata.put("functionScopeSource", prepared.functionScopeSource());
        metadata.put("taskTokenFunctionScopeEmpty", prepared.functionScopeEmpty());
        return prepared.plainToken();
    }

    private RuntimeException openApiRequestRejected(Exception e) {
        String message = sanitizeDiagnosticText(e != null ? e.getMessage() : null);
        return RX.throwB(StringUtils.hasText(message) ? message : "open api request rejected");
    }

    private void bindBusinessRuntimeTokenToWorkerTask(
            String tenantId,
            String businessRuntimeToken,
            A2aTask task,
            Map<String, Object> launchMetadata) {
        if (!StringUtils.hasText(businessRuntimeToken)
                || task == null
                || !StringUtils.hasText(task.getId())) {
            return;
        }
        BusinessAgentTaskService service = businessAgentTaskService.getIfAvailable();
        if (service == null) {
            return;
        }
        String expectedWorkerId = launchMetadata != null
                ? stringValue(launchMetadata.get("workerId"))
                : null;
        String workerLeaseId = launchMetadata != null
                ? stringValue(launchMetadata.get("workerLeaseId"))
                : null;
        String actualWorkerId = task.getMetadata() != null
                ? stringValue(task.getMetadata().get("workerId"))
                : null;
        if (!StringUtils.hasText(expectedWorkerId)
                || !StringUtils.hasText(actualWorkerId)
                || !expectedWorkerId.equals(actualWorkerId)) {
            throw new SecurityException(
                    "worker task result does not match the preselected worker");
        }
        service.bindOpenApiTaskScopedTokenToWorkerTask(
                tenantId,
                businessRuntimeToken,
                task.getId(),
                resolveTaskSessionId(task),
                actualWorkerId,
                workerLeaseId);
    }

    private void revokeBusinessRuntimeTokenAfterFailure(
            String tenantId,
            String businessRuntimeToken,
            String reason) {
        if (!StringUtils.hasText(businessRuntimeToken)) {
            return;
        }
        BusinessAgentTaskService service = businessAgentTaskService.getIfAvailable();
        if (service == null) {
            log.warn("Unable to compensate Open API business runtime token: tenantId={}, reason={}, "
                            + "businessAgentTaskService=unavailable",
                    tenantId, reason);
            return;
        }
        try {
            service.revokeOpenApiTaskScopedToken(
                    tenantId,
                    businessRuntimeToken,
                    BUSINESS_RUNTIME_TOKEN_REVOKED_BY,
                    reason);
        } catch (RuntimeException revocationFailure) {
            log.warn("Failed to compensate Open API business runtime token: "
                            + "tenantId={}, reason={}, errorType={}",
                    tenantId, reason, revocationFailure.getClass().getSimpleName());
        }
    }

    private boolean isTerminalTask(A2aTask task) {
        if (task == null || task.getStatus() == null || task.getStatus().getState() == null) {
            return false;
        }
        return switch (task.getStatus().getState()) {
            case COMPLETED, FAILED, CANCELED -> true;
            default -> false;
        };
    }

    private String resolveTaskSessionId(A2aTask task) {
        if (task != null && task.getMetadata() != null) {
            Object sessionId = task.getMetadata().get("sessionId");
            if (sessionId instanceof String value && StringUtils.hasText(value)) {
                return value;
            }
        }
        return task != null ? task.getContextId() : null;
    }

    private void bindBusinessAgentSession(
            RuntimeCredentialReference credential,
            String upstreamUserId,
            String agentId,
            String contextId,
            A2aTask task,
            String clientContextJson,
            String agentOwnerUserId) {
        if (!StringUtils.hasText(upstreamUserId)) {
            return;
        }
        BusinessAgentSessionService service = businessAgentSessionService.getIfAvailable();
        if (service == null) {
            return;
        }
        String sessionId = resolveTaskNavigatorSessionId(task);
        if (!StringUtils.hasText(sessionId)) {
            sessionId = resolveNavigatorSessionId(contextId, agentOwnerUserId, agentId).orElse(null);
        }
        if (!StringUtils.hasText(sessionId)) {
            log.warn("Skip binding business agent session because no Navigator sessionId is available: "
                    + "contextId={}, taskId={}", contextId, task != null ? task.getId() : null);
            return;
        }
        service.bindOpenApiSession(
                credential.tenantId(),
                credential.clientAppId(),
                upstreamUserId,
                contextId,
                sessionId,
                agentId,
                task != null ? task.getId() : null,
                clientContextJson);
    }

    private String resolveTaskNavigatorSessionId(A2aTask task) {
        if (task != null && task.getMetadata() != null) {
            Object sessionId = task.getMetadata().get("sessionId");
            if (sessionId instanceof String value && StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private RuntimeRequestAuditService.TaskEvidence dispatchEvidence(
            VerifiedCreateCommand command,
            A2aTask task,
            Map<String, Object> metadata) {
        String status = task.getStatus() != null
                ? mapA2aState(task.getStatus().getState())
                : "SUBMITTED";
        return new RuntimeRequestAuditService.TaskEvidence(
                task.getId(),
                status,
                false,
                null,
                command.agentId(),
                command.upstreamUserId(),
                stringValue(metadata.get("workerId")),
                command.launchPlan().modelResource().modelConfigId(),
                command.launchPlan().modelResource().modelName(),
                integerValue(metadata.get("requestedToolCount")),
                integerValue(metadata.get("effectiveToolCount")),
                stringValue(metadata.get("toolScopeKind")),
                stringValue(metadata.get("toolScopeSource")),
                integerValue(metadata.get("requestedFunctionCount")),
                integerValue(metadata.get("effectiveFunctionCount")),
                stringValue(metadata.get("functionScopeSource")),
                booleanValue(metadata.get("taskTokenFunctionScopeEmpty")),
                "ACTIVE",
                true,
                true,
                false,
                1,
                0,
                0,
                "STANDARD_ASK_DISPATCHED");
    }

    private String resolveAgentOwnerUserId(String agentId, String tenantId) {
        return codingAgentRepository.findByAgentIdAndTenantId(agentId, tenantId)
                .map(entity -> entity.getUserId())
                .orElseThrow(() -> RX.throwB("Agent not found: " + agentId));
    }

    private String mapA2aState(A2aTaskState state) {
        if (state == null) {
            return "UNKNOWN";
        }
        return switch (state) {
            case SUBMITTED -> "SUBMITTED";
            case WORKING -> "RUNNING";
            case INPUT_REQUIRED -> "AWAITING_INPUT";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELED -> "CANCELLED";
        };
    }

    private String sanitizeDiagnosticText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String sanitized = text.replace('\n', ' ').replace('\r', ' ').trim()
                .replaceAll("(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(access[_-]?token\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(token\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(client[_-]?secret\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(secret\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(password\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-[REDACTED]");
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }

    private static Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private static String stringValue(Object value) {
        return value instanceof String text && StringUtils.hasText(text) ? text : null;
    }

    private void requireContext(VerifiedCreateContext context) {
        if (context == null
                || context.credential() == null
                || context.auditHandle() == null
                || !StringUtils.hasText(context.credential().tenantId())
                || !StringUtils.hasText(context.credential().clientAppId())
                || !StringUtils.hasText(context.agentId())
                || !StringUtils.hasText(context.contextId())
                || !StringUtils.hasText(context.modelConfigId())) {
            throw new IllegalArgumentException("verified create context is incomplete");
        }
    }

    private void requireCommand(VerifiedCreateCommand command) {
        if (command == null
                || command.preparedContext() == null
                || command.launchPlan() == null
                || !StringUtils.hasText(command.messageContent())) {
            throw new IllegalArgumentException("verified create command is incomplete");
        }
    }

    public record RuntimeCredentialReference(
            String tenantId,
            String clientAppId,
            String credentialId,
            String runtimeAccessTokenId) {
    }

    public record VerifiedCreateContext(
            RuntimeCredentialReference credential,
            String upstreamUserId,
            String agentId,
            String skillId,
            String contextId,
            boolean existingContextRequested,
            String modelConfigId,
            RuntimeRequestAuditService.AuditHandle auditHandle) {
    }

    public record PreparedCreateContext(
            VerifiedCreateContext context,
            RuntimeRequestAuditService auditService,
            String agentOwnerUserId,
            AgentResolveContext resolveContext,
            A2aAgent agent) {
    }

    public record PrepareOutcome(
            PreparedCreateContext preparedContext,
            String rejectionMessage,
            String rejectionFallback) {

        static PrepareOutcome prepared(PreparedCreateContext preparedContext) {
            return new PrepareOutcome(preparedContext, null, null);
        }

        static PrepareOutcome rejected(String message) {
            return new PrepareOutcome(null, message, null);
        }

        static PrepareOutcome rejected(String message, String fallback) {
            return new PrepareOutcome(null, message, fallback);
        }

        public boolean ready() {
            return preparedContext != null;
        }
    }

    public record VerifiedCreateCommand(
            PreparedCreateContext preparedContext,
            String messageContent,
            Integer maxTurns,
            String clientContextJson,
            OpenApiRuntimeTaskLaunchPlanner.LaunchPlan launchPlan) {

        RuntimeCredentialReference credential() {
            return preparedContext.context().credential();
        }

        String upstreamUserId() {
            return preparedContext.context().upstreamUserId();
        }

        String agentId() {
            return preparedContext.context().agentId();
        }

        String skillId() {
            return preparedContext.context().skillId();
        }

        String contextId() {
            return preparedContext.context().contextId();
        }

        RuntimeRequestAuditService.AuditHandle auditHandle() {
            return preparedContext.context().auditHandle();
        }
    }

    public record CreateOutcome(
            A2aTask task,
            Map<String, Object> metadata,
            String rejectionMessage,
            String rejectionFallback) {

        public CreateOutcome {
            metadata = metadata == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }

        static CreateOutcome created(A2aTask task, Map<String, Object> metadata) {
            return new CreateOutcome(task, metadata, null, null);
        }

        static CreateOutcome rejected(String message) {
            return new CreateOutcome(null, Map.of(), message, null);
        }

        static CreateOutcome rejected(String message, String fallback) {
            return new CreateOutcome(null, Map.of(), message, fallback);
        }

        public boolean created() {
            return task != null;
        }
    }
}
