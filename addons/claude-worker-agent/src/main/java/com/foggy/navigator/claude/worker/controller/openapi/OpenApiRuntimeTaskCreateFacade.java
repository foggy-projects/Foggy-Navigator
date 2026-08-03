package com.foggy.navigator.claude.worker.controller.openapi;

import com.foggy.navigator.business.agent.service.BusinessAgentSessionService;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.business.agent.service.TerminalTaskBindingException;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.session.agent.TaskSubmittingA2aAgentDecorator;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.service.OpenApiSessionQueryService;
import com.foggy.navigator.session.service.ScopedOpenApiTaskCreateCommandAdapter;
import com.foggy.navigator.session.service.TaskDispatchRequest;
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
    private static final String BUSINESS_RUNTIME_IMMEDIATE_TERMINAL_REASON =
            "open api task returned terminal after submission";

    private final CodingAgentRepository codingAgentRepository;
    private final UnifiedAgentResolver agentResolver;
    private final AgentSubmitPipeline agentSubmitPipeline;
    private final ScopedOpenApiTaskCreateCommandAdapter scopedTaskCreateCommandAdapter;
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
        workerSelectionRequest.setCallerCredentialId(credential.credentialId());
        workerSelectionRequest.setCallerAccessTokenId(credential.runtimeAccessTokenId());
        BusinessAgentTaskService taskService = businessAgentTaskService.getIfAvailable();
        BusinessAgentTaskService.OpenApiTaskWorkerPreflight workerPreflight = null;
        try {
            if (taskService != null && StringUtils.hasText(command.upstreamUserId())) {
                workerPreflight = taskService.resolveOpenApiTaskWorkerPreflight(
                        credential.tenantId(),
                        agentOwnerUserId,
                        credential.clientAppId(),
                        command.upstreamUserId(),
                        command.skillId(),
                        command.contextId(),
                        modelConfigId,
                        workerSelectionRequest);
            }
            requireExactWorkerPreflight(workerPreflight, workerSelectionRequest, metadata, modelConfigId);
            enrichBusinessRuntimeContext(
                    credential,
                    metadata,
                    command.agentId(),
                    command.upstreamUserId());
        } catch (RuntimeException e) {
            auditService.askFailed(command.auditHandle(), "TASK_WORKER_PREFLIGHT_FAILED");
            throw e;
        }

        A2aMessage message = A2aMessage.user(List.of(A2aPart.text(command.messageContent())));
        message.setContextId(command.contextId());
        if (!metadata.isEmpty()) {
            message.setMetadata(metadata);
        }
        TaskSubmittingA2aAgent submittingAgent = new TaskSubmittingA2aAgentDecorator(
                agent, agentSubmitPipeline, command.agentId(), resolveContext);
        AgentTaskSubmitRequest submitRequest = AgentTaskSubmitRequest.builder()
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
                .clientRequestId(command.auditHandle().clientRequestId())
                .build();
        ScopedCreateParticipants participants = new ScopedCreateParticipants(
                command,
                workerSelectionRequest,
                workerPreflight,
                taskService);
        ScopedOpenApiTaskCreateCommandAdapter.OpenApiCommandScope scope =
                ScopedOpenApiTaskCreateCommandAdapter.OpenApiCommandScope.authenticated(
                        command.auditHandle().clientRequestId(),
                        credential.tenantId(),
                        agentOwnerUserId,
                        credential.clientAppId(),
                        workerPreflight != null ? workerPreflight.upstreamSystemId() : null,
                        command.upstreamUserId(),
                        credential.credentialId(),
                        credential.runtimeAccessTokenId(),
                        new ScopedOpenApiTaskCreateCommandAdapter.TargetExpectation(
                                command.agentId(),
                                command.contextId(),
                                submitRequest.getProviderType(),
                                submitRequest.getWorkerId(),
                                modelConfigId,
                                submitRequest.getModel(),
                                submitRequest.getDirectoryId()));
        A2aTask task;
        try {
            task = scopedTaskCreateCommandAdapter.executeScoped(
                    scope,
                    submitRequest,
                    participants,
                    () -> submittingAgent.submitTask(submitRequest));
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            return CreateOutcome.rejected(e.getMessage(), "open api request rejected");
        }
        if (task == null || !StringUtils.hasText(task.getId())) {
            return CreateOutcome.rejected("open api task submission returned no task id");
        }
        if (!StringUtils.hasText(task.getContextId())) {
            task.setContextId(command.contextId());
        }

        log.info("Open API runtime task created: agentId={}, skillId={}, taskId={}, tenantId={}",
                command.agentId(), command.skillId(), task.getId(), credential.tenantId());
        return CreateOutcome.created(task, participants.safeResponseMetadata());
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

    private void requireExactWorkerPreflight(
            BusinessAgentTaskService.OpenApiTaskWorkerPreflight preflight,
            BusinessAgentWorkerTaskLaunchRequest workerSelectionRequest,
            Map<String, Object> metadata,
            String modelConfigId) {
        if (preflight == null) {
            return;
        }
        if (!modelConfigId.equals(preflight.modelConfigId())
                || !preflight.workerId().equals(workerSelectionRequest.getSelectedWorkerId())) {
            throw new SecurityException("OpenAPI Worker preflight does not match the launch plan");
        }
        metadata.put("workerId", preflight.workerId());
        metadata.put("workerBackend", preflight.workerBackend());
    }

    private void enrichBusinessRuntimeContext(
            RuntimeCredentialReference credential,
            Map<String, Object> metadata,
            String rootAgentId,
            String upstreamUserId) {
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
        }
        metadata.put("context", context);
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

    private RuntimeException openApiRequestRejected(Exception e) {
        String message = sanitizeDiagnosticText(e != null ? e.getMessage() : null);
        return RX.throwB(StringUtils.hasText(message) ? message : "open api request rejected");
    }

    private void bindBusinessRuntimeTokenToWorkerTask(
            BusinessAgentTaskService service,
            String tenantId,
            String businessRuntimeToken,
            DispatchTaskDTO task,
            Map<String, Object> launchMetadata) {
        if (!StringUtils.hasText(businessRuntimeToken)
                || task == null
                || !StringUtils.hasText(task.getTaskId())) {
            return;
        }
        if (service == null) {
            throw new IllegalStateException("business agent task service is not available");
        }
        String expectedWorkerId = launchMetadata != null
                ? stringValue(launchMetadata.get("workerId"))
                : null;
        String workerLeaseId = launchMetadata != null
                ? stringValue(launchMetadata.get("workerLeaseId"))
                : null;
        String actualWorkerId = task.getWorkerId();
        if (!StringUtils.hasText(expectedWorkerId)
                || !StringUtils.hasText(actualWorkerId)
                || !expectedWorkerId.equals(actualWorkerId)) {
            throw new SecurityException(
                    "worker task result does not match the preselected worker");
        }
        service.bindOpenApiTaskScopedTokenToWorkerTask(
                tenantId,
                businessRuntimeToken,
                task.getTaskId(),
                task.getSessionId(),
                actualWorkerId,
                workerLeaseId);
    }

    private void revokeBusinessRuntimeTokenForTerminal(
            BusinessAgentTaskService service,
            String tenantId,
            String businessRuntimeToken,
            String reason) {
        if (!StringUtils.hasText(businessRuntimeToken)) {
            return;
        }
        if (service == null) {
            throw new IllegalStateException("business agent task service is not available");
        }
        service.revokeOpenApiTaskScopedToken(
                tenantId,
                businessRuntimeToken,
                BUSINESS_RUNTIME_TOKEN_REVOKED_BY,
                reason);
    }

    private boolean isTerminalTask(DispatchTaskDTO task) {
        if (task == null || !StringUtils.hasText(task.getStatus())) {
            return false;
        }
        String status = task.getStatus().trim().toUpperCase(java.util.Locale.ROOT);
        return "COMPLETED".equals(status)
                || "FAILED".equals(status)
                || "CANCELLED".equals(status)
                || "CANCELED".equals(status)
                || "ABORTED".equals(status);
    }

    private void bindBusinessAgentSession(
            RuntimeCredentialReference credential,
            String upstreamUserId,
            String agentId,
            String contextId,
            DispatchTaskDTO task,
            String clientContextJson,
            String agentOwnerUserId) {
        if (!StringUtils.hasText(upstreamUserId)) {
            return;
        }
        BusinessAgentSessionService service = businessAgentSessionService.getIfAvailable();
        if (service == null) {
            return;
        }
        String sessionId = task != null ? task.getSessionId() : null;
        if (!StringUtils.hasText(sessionId)) {
            sessionId = resolveNavigatorSessionId(contextId, agentOwnerUserId, agentId).orElse(null);
        }
        if (!StringUtils.hasText(sessionId)) {
            log.warn("Skip binding business agent session because no Navigator sessionId is available: "
                    + "contextId={}, taskId={}", contextId,
                    task != null ? task.getTaskId() : null);
            return;
        }
        service.bindOpenApiSession(
                credential.tenantId(),
                credential.clientAppId(),
                upstreamUserId,
                contextId,
                sessionId,
                agentId,
                task != null ? task.getTaskId() : null,
                clientContextJson);
    }

    private RuntimeRequestAuditService.TaskEvidence dispatchEvidence(
            VerifiedCreateCommand command,
            DispatchTaskDTO task,
            Map<String, Object> metadata,
            String taskTokenStatus) {
        String status = StringUtils.hasText(task.getStatus()) ? task.getStatus() : "SUBMITTED";
        return new RuntimeRequestAuditService.TaskEvidence(
                task.getTaskId(),
                status,
                isTerminalTask(task),
                null,
                command.agentId(),
                command.upstreamUserId(),
                task.getWorkerId(),
                task.getModelConfigId(),
                task.getModel(),
                integerValue(metadata.get("requestedToolCount")),
                integerValue(metadata.get("effectiveToolCount")),
                stringValue(metadata.get("toolScopeKind")),
                stringValue(metadata.get("toolScopeSource")),
                integerValue(metadata.get("requestedFunctionCount")),
                integerValue(metadata.get("effectiveFunctionCount")),
                stringValue(metadata.get("functionScopeSource")),
                booleanValue(metadata.get("taskTokenFunctionScopeEmpty")),
                taskTokenStatus,
                true,
                true,
                false,
                1,
                0,
                0,
                "STANDARD_ASK_DISPATCHED");
    }

    private RuntimeRequestAuditService.TaskEvidence admissionEvidence(
            VerifiedCreateCommand command,
            TaskDispatchRequest request) {
        Map<String, Object> metadata = request.getMetadata() != null
                ? request.getMetadata()
                : Map.of();
        return new RuntimeRequestAuditService.TaskEvidence(
                null,
                "ADMITTED",
                false,
                null,
                command.agentId(),
                command.upstreamUserId(),
                request.getWorkerId(),
                request.getModelConfigId(),
                request.getModel(),
                integerValue(metadata.get("requestedToolCount")),
                integerValue(metadata.get("effectiveToolCount")),
                stringValue(metadata.get("toolScopeKind")),
                stringValue(metadata.get("toolScopeSource")),
                integerValue(metadata.get("requestedFunctionCount")),
                integerValue(metadata.get("effectiveFunctionCount")),
                stringValue(metadata.get("functionScopeSource")),
                booleanValue(metadata.get("taskTokenFunctionScopeEmpty")),
                "NOT_ISSUED",
                false,
                false,
                false,
                0,
                0,
                0,
                "STANDARD_SCOPE_ADMITTED");
    }

    private Map<String, Object> mutableCanonicalMetadata(TaskDispatchRequest request) {
        Map<String, Object> copy = request.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getMetadata());
        Object rawRuntimeContext = copy.get("runtimeContext");
        if (rawRuntimeContext instanceof Map<?, ?> runtimeContext) {
            Map<String, Object> nestedCopy = new LinkedHashMap<>();
            runtimeContext.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    nestedCopy.put(stringKey, value);
                }
            });
            copy.put("runtimeContext", nestedCopy);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> canonicalRuntimeContext(Map<String, Object> metadata) {
        Object rawRuntimeContext = metadata.get("runtimeContext");
        if (rawRuntimeContext instanceof Map<?, ?>) {
            return (Map<String, Object>) rawRuntimeContext;
        }
        Map<String, Object> runtimeContext = new LinkedHashMap<>();
        metadata.put("runtimeContext", runtimeContext);
        return runtimeContext;
    }

    private Map<String, Object> safeResponseMetadata(
            TaskDispatchRequest request,
            String taskTokenStatus) {
        Map<String, Object> source = request.getMetadata() != null
                ? request.getMetadata()
                : Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        putSafeValue(safe, "workerId", request.getWorkerId());
        for (String key : List.of(
                "modelConfigId",
                "modelConfigSource",
                "workerBackend",
                "workerSource",
                "backendSource",
                "requestedToolCount",
                "effectiveToolCount",
                "toolScopeKind",
                "toolScopeSource",
                "requestedFunctionCount",
                "effectiveFunctionCount",
                "functionScopeSource",
                "taskTokenFunctionScopeEmpty",
                "runtimeDispatched",
                "modelDispatched",
                "businessFunctionDispatched")) {
            putSafeValue(safe, key, source.get(key));
        }
        putSafeValue(safe, "taskTokenStatus", taskTokenStatus);
        return safe;
    }

    private void putSafeValue(Map<String, Object> target, String key, Object value) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            target.put(key, text);
        } else if (value instanceof Number || value instanceof Boolean) {
            target.put(key, value);
        }
    }

    private final class ScopedCreateParticipants
            implements ScopedOpenApiTaskCreateCommandAdapter.FreshParticipants {

        private final VerifiedCreateCommand command;
        private final BusinessAgentWorkerTaskLaunchRequest workerSelectionRequest;
        private final BusinessAgentTaskService.OpenApiTaskWorkerPreflight workerPreflight;
        private final BusinessAgentTaskService taskService;

        private BusinessAgentTaskService.PreparedOpenApiTaskScopedToken preparedToken;
        private Map<String, Object> responseMetadata = Map.of();
        private boolean completionSucceeded;

        private ScopedCreateParticipants(
                VerifiedCreateCommand command,
                BusinessAgentWorkerTaskLaunchRequest workerSelectionRequest,
                BusinessAgentTaskService.OpenApiTaskWorkerPreflight workerPreflight,
                BusinessAgentTaskService taskService) {
            this.command = command;
            this.workerSelectionRequest = workerSelectionRequest;
            this.workerPreflight = workerPreflight;
            this.taskService = taskService;
        }

        @Override
        public void prepare(TaskDispatchRequest canonicalRequest) {
            command.preparedContext().auditService().taskAdmissionRecorded(
                    command.auditHandle(),
                    admissionEvidence(command, canonicalRequest));
            if (workerPreflight == null) {
                return;
            }
            if (taskService == null) {
                throw new IllegalStateException("business agent task service is not available");
            }
            BusinessAgentTaskService.PreparedOpenApiTaskScopedToken prepared;
            try {
                prepared = taskService.prepareOpenApiTaskScopedTokenAfterPreflight(
                        command.credential().tenantId(),
                        command.preparedContext().agentOwnerUserId(),
                        command.credential().clientAppId(),
                        command.upstreamUserId(),
                        command.skillId(),
                        command.contextId(),
                        command.preparedContext().context().modelConfigId(),
                        workerSelectionRequest,
                        workerPreflight);
            } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                throw openApiRequestRejected(e);
            }
            if (prepared == null
                    || !workerPreflight.workerId().equals(prepared.workerId())
                    || !prepared.workerId().equals(canonicalRequest.getWorkerId())) {
                throw new SecurityException(
                        "OpenAPI task token Worker does not match the canonical request");
            }

            Map<String, Object> canonicalMetadata = mutableCanonicalMetadata(canonicalRequest);
            Map<String, Object> runtimeContext = canonicalRuntimeContext(canonicalMetadata);
            runtimeContext.put("task_scoped_token", prepared.plainToken());
            runtimeContext.put("worker_id", prepared.workerId());
            runtimeContext.put("worker_lease_id", prepared.workerLeaseId());
            if (workerSelectionRequest.getAllowedTools() != null) {
                runtimeContext.put("allowed_tools", List.copyOf(workerSelectionRequest.getAllowedTools()));
            }
            canonicalMetadata.put("workerId", prepared.workerId());
            canonicalMetadata.put("workerLeaseId", prepared.workerLeaseId());
            canonicalMetadata.put("effectiveFunctionCount", prepared.effectiveFunctionCount());
            canonicalMetadata.put("functionScopeSource", prepared.functionScopeSource());
            canonicalMetadata.put("taskTokenFunctionScopeEmpty", prepared.functionScopeEmpty());
            canonicalRequest.setMetadata(canonicalMetadata);
            preparedToken = prepared;
        }

        @Override
        public void complete(TaskDispatchRequest canonicalRequest, DispatchTaskDTO freshTask) {
            boolean terminalTaskObservedDuringBind = false;
            if (preparedToken != null) {
                try {
                    bindBusinessRuntimeTokenToWorkerTask(
                            taskService,
                            command.credential().tenantId(),
                            preparedToken.plainToken(),
                            freshTask,
                            canonicalRequest.getMetadata());
                } catch (TerminalTaskBindingException terminal) {
                    terminalTaskObservedDuringBind = true;
                }
            }
            boolean immediateTerminal = isTerminalTask(freshTask);
            if (!terminalTaskObservedDuringBind && immediateTerminal && preparedToken != null) {
                revokeBusinessRuntimeTokenForTerminal(
                        taskService,
                        command.credential().tenantId(),
                        preparedToken.plainToken(),
                        BUSINESS_RUNTIME_IMMEDIATE_TERMINAL_REASON);
            }
            boolean terminal = terminalTaskObservedDuringBind || immediateTerminal;
            String taskTokenStatus = preparedToken == null
                    ? "NOT_ISSUED"
                    : terminal ? "REVOKED" : "ACTIVE";
            command.preparedContext().auditService().taskDispatchRecorded(
                    command.auditHandle(),
                    dispatchEvidence(
                            command,
                            freshTask,
                            canonicalRequest.getMetadata() != null
                                    ? canonicalRequest.getMetadata()
                                    : Map.of(),
                            taskTokenStatus));
            if (command.clientContextJson() != null) {
                sessionQueryService.updateClientContextJson(
                        command.contextId(),
                        command.preparedContext().agentOwnerUserId(),
                        command.agentId(),
                        command.clientContextJson());
            }
            bindBusinessAgentSession(
                    command.credential(),
                    command.upstreamUserId(),
                    command.agentId(),
                    command.contextId(),
                    freshTask,
                    command.clientContextJson(),
                    command.preparedContext().agentOwnerUserId());
            responseMetadata = OpenApiRuntimeTaskCreateFacade.this.safeResponseMetadata(
                    canonicalRequest, taskTokenStatus);
            completionSucceeded = true;
        }

        private Map<String, Object> safeResponseMetadata() {
            return completionSucceeded ? responseMetadata : Map.of();
        }
    }

    private String resolveAgentOwnerUserId(String agentId, String tenantId) {
        return codingAgentRepository.findByAgentIdAndTenantId(agentId, tenantId)
                .map(entity -> entity.getUserId())
                .orElseThrow(() -> RX.throwB("Agent not found: " + agentId));
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
