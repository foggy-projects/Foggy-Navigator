package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentSessionEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskTerminalStateEntity;
import com.foggy.navigator.business.agent.repository.BusinessAgentSessionRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentTaskRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskTerminalStateRepository;
import com.foggy.navigator.business.agent.service.A2AgentResourceResolver;
import com.foggy.navigator.business.agent.service.ClientAppRuntimeCredentialResolver;
import com.foggy.navigator.claude.worker.model.dto.RuntimeBindingAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeAuditSideEffectsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskFactsDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditStageDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.ErrorDiagnosticEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.entity.TerminationOperationEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.session.repository.ErrorDiagnosticRepository;
import com.foggy.navigator.session.repository.TerminationOperationRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Strictly read-only ClientApp self-audit over durable Navigator registration and task state.
 *
 * <p>This service deliberately depends only on repositories and read-only resource resolvers.
 * It must never call a Worker client, task lifecycle service, reconciler, token issuer, or
 * provisioning mutator.</p>
 */
@Service
@RequiredArgsConstructor
public class RuntimeStateAuditService {

    public static final String CODEX_ROLE_SOURCE = "CLAUDE_WORKER_CODEX_CONFIG";
    public static final String TOKEN_NOT_FOUND = "NOT_FOUND";
    public static final String TOKEN_UNCONFIRMED = "UNCONFIRMED";

    private static final Set<String> TERMINAL_STATUSES =
            Set.of("COMPLETED", "FAILED", "ABORTED", "CANCELLED", "CANCELED");
    private static final List<String> ACTIVE_STATUSES =
            List.of("PENDING", "SUBMITTED", "RUNNING", "AWAITING_PERMISSION",
                    "AWAITING_INPUT", "CANCEL_REQUESTED", "RECONNECTING");

    private final ClientAppRuntimeCredentialResolver credentialResolver;
    private final A2AgentResourceResolver resourceResolver;
    private final BusinessCodingAgentRepository agentRepository;
    private final WorkingDirectoryRepository directoryRepository;
    private final LlmModelManager llmModelManager;
    private final ClaudeWorkerRepository workerRepository;
    private final SessionTaskRepository sessionTaskRepository;
    private final BusinessTaskTerminalStateRepository terminalStateRepository;
    private final BusinessTaskScopedTokenRepository taskTokenRepository;
    private final BusinessAgentTaskRepository businessTaskRepository;
    private final BusinessAgentSessionRepository businessSessionRepository;
    private final ErrorDiagnosticRepository errorDiagnosticRepository;
    private final TerminationOperationRepository terminationOperationRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public RuntimeBindingAuditDTO auditBinding(
            String appKey,
            String appSecret,
            String agentCode,
            String upstreamUserId,
            String modelConfigId,
            String directoryId) {
        ResolvedClientAppCredentialDTO owner = requireOwner(appKey, appSecret);
        requireText(agentCode, "RUNTIME_BINDING_AUDIT_AGENT_REQUIRED");
        requireText(upstreamUserId, "RUNTIME_BINDING_AUDIT_UPSTREAM_USER_REQUIRED");
        requireText(modelConfigId, "RUNTIME_BINDING_AUDIT_MODEL_REQUIRED");
        requireText(directoryId, "RUNTIME_BINDING_AUDIT_DIRECTORY_REQUIRED");

        CodingAgentEntity agent = agentRepository
                .findByAgentIdAndTenantId(agentCode.trim(), owner.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_BINDING_AUDIT_NOT_FOUND"));
        A2AgentResourceResolver.ResolvedAgentResource agentResource =
                resourceResolver.resolveRequiredAgent(
                        owner.getTenantId(),
                        owner.getClientAppId(),
                        upstreamUserId.trim(),
                        agentCode.trim());
        A2AgentResourceResolver.ResolvedModelResource modelResource =
                resourceResolver.resolveRequiredModelForAgent(
                        owner.getTenantId(),
                        owner.getClientAppId(),
                        agentResource,
                        modelConfigId.trim(),
                        null,
                        LlmModelCategory.GENERAL);
        A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource =
                resourceResolver.resolveRequiredWorkspaceForAgent(
                        owner.getTenantId(),
                        owner.getClientAppId(),
                        upstreamUserId.trim(),
                        agentResource,
                        directoryId.trim());

        requireEqual(agentCode, agentResource.agentId(), "RUNTIME_BINDING_AUDIT_AGENT_MISMATCH");
        requireEqual(modelConfigId, modelResource.modelConfigId(), "RUNTIME_BINDING_AUDIT_MODEL_MISMATCH");
        requireEqual(directoryId, workspaceResource.directoryId(), "RUNTIME_BINDING_AUDIT_DIRECTORY_MISMATCH");
        if (StringUtils.hasText(agentResource.physicalWorkerId())) {
            requireEqual(agentResource.physicalWorkerId(), workspaceResource.physicalWorkerId(),
                    "RUNTIME_BINDING_AUDIT_WORKER_MISMATCH");
        }

        WorkingDirectoryEntity directory = directoryRepository.findByDirectoryId(workspaceResource.directoryId())
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_BINDING_AUDIT_NOT_FOUND"));
        LlmModelConfigDTO modelConfig = llmModelManager.getModelConfig(modelResource.modelConfigId())
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_BINDING_AUDIT_NOT_FOUND"));
        ClaudeWorkerEntity worker = workerRepository.findByWorkerId(workspaceResource.physicalWorkerId())
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_BINDING_AUDIT_WORKER_NOT_FOUND"));

        CodexConfig codexConfig = worker.getCodexConfig();
        boolean samePhysicalWorker = codexConfig != null
                && StringUtils.hasText(codexConfig.getBaseUrl())
                && workspaceResource.physicalWorkerId().equals(worker.getWorkerId());

        return RuntimeBindingAuditDTO.builder()
                .observedAt(Instant.now())
                .tenant(owner.getTenantId())
                .upstreamUserId(upstreamUserId.trim())
                .agentCode(agent.getAgentId())
                .agentEnabled(Boolean.TRUE.equals(agent.getEnabled()))
                .modelConfigId(modelConfig.getId())
                .modelVariant(modelResource.modelName())
                .modelBackend(modelResource.workerBackend())
                .directoryId(directory.getDirectoryId())
                .directoryEnabled(Boolean.TRUE.equals(directory.getEnabled()))
                .workerHost(resolveWorkerHost(worker))
                .physicalWorkerId(worker.getWorkerId())
                .physicalWorkerStatus(worker.getStatus())
                .directoryRolePort(parsePort(worker.getBaseUrl()))
                .codexRolePort(codexConfig != null ? parsePort(codexConfig.getBaseUrl()) : null)
                .codexRoleSource(codexConfig != null ? CODEX_ROLE_SOURCE : null)
                .codexRoleSamePhysicalWorker(samePhysicalWorker)
                .activeTaskCount(sessionTaskRepository.countByTenantIdAndWorkerIdAndStatusIn(
                        owner.getTenantId(), worker.getWorkerId(), ACTIVE_STATUSES))
                .auditAccessTokenIssued(false)
                .auditRuntimeTokenIssued(false)
                .auditTaskTokenIssued(false)
                .taskCreated(false)
                .contextCreated(false)
                .sessionCreated(false)
                .modelDispatched(false)
                .businessFunctionDispatched(false)
                .recoveryTriggered(false)
                .provisioningResourceChanged(false)
                .build();
    }

    @Transactional(readOnly = true)
    public RuntimeTaskAuditDTO auditTask(
            String appKey,
            String appSecret,
            String upstreamUserId,
            String taskId) {
        ResolvedClientAppCredentialDTO owner = requireOwner(appKey, appSecret);
        requireText(upstreamUserId, "RUNTIME_TASK_AUDIT_UPSTREAM_USER_REQUIRED");
        requireText(taskId, "RUNTIME_TASK_AUDIT_TASK_REQUIRED");

        SessionTaskEntity task = sessionTaskRepository.findByTaskId(taskId.trim())
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_TASK_AUDIT_NOT_FOUND"));
        if (!owner.getTenantId().equals(task.getTenantId())) {
            throw new SecurityException("RUNTIME_TASK_AUDIT_FORBIDDEN");
        }

        Optional<BusinessTaskTerminalStateEntity> terminal =
                terminalStateRepository.findByTenantIdAndWorkerTaskId(owner.getTenantId(), task.getTaskId());
        Optional<BusinessTaskScopedTokenEntity> token =
                resolveOwnedToken(owner, task, terminal);
        requireTaskOwnership(owner, upstreamUserId.trim(), task, token);

        String providerStatus = normalizedStatus(terminal.map(BusinessTaskTerminalStateEntity::getTerminalStatus)
                .orElse(task.getStatus()));
        List<TerminationOperationEntity> terminationOperations =
                terminationOperationRepository.findByTaskIdOrderByCreatedAtDesc(task.getTaskId());
        boolean operatorTerminated = terminationOperations.stream().anyMatch(this::isOperatorTermination);
        String status = operatorTerminated && "ABORTED".equals(providerStatus) ? "CANCELLED" : providerStatus;
        boolean isTerminal = terminal.isPresent() || TERMINAL_STATUSES.contains(providerStatus);
        Optional<ErrorDiagnosticEntity> diagnostic = errorDiagnosticRepository
                .findFirstByTaskIdAndTenantIdOrderByOccurredAtDesc(task.getTaskId(), owner.getTenantId());
        String sanitizedErrorCode = operatorTerminated && isTerminal
                ? "OPERATOR_TERMINATED"
                : diagnostic.map(ErrorDiagnosticEntity::getErrorCode)
                .filter(StringUtils::hasText)
                .orElse(null);
        String tokenStatus = resolveTokenStatus(token);
        LocalDateTime completedAt = terminal.map(BusinessTaskTerminalStateEntity::getTerminalAt)
                .orElse(isTerminal ? task.getUpdatedAt() : null);
        TaskAttemptCounts counts = resolveAttemptCounts(task);
        boolean activeRegistration = terminal.isEmpty()
                && ACTIVE_STATUSES.contains(normalizedStatus(task.getStatus()));

        Map<String, Object> state = parseState(task.getTaskStateJson());
        boolean functionScopeEmpty = token.map(this::isFunctionScopeEmpty).orElse(false);
        TaskScopeFacts scope = resolveScopeFacts(state, task, functionScopeEmpty);
        List<RuntimeTaskAuditStageDTO> stages = buildTerminalStages(
                task, terminal.orElse(null), token.orElse(null), sanitizedErrorCode, status,
                terminationOperations);

        RuntimeTaskFactsDTO taskFacts = RuntimeTaskFactsDTO.builder()
                .taskId(task.getTaskId())
                .terminal(isTerminal)
                .status(status)
                .sanitizedErrorCode(sanitizedErrorCode)
                .taskTokenStatus(tokenStatus)
                .activeTaskRegistrationPresent(activeRegistration)
                .dispatchCount(counts.dispatchCount())
                .retryCount(counts.retryCount())
                .recoveryCount(counts.recoveryCount())
                .physicalWorkerId(task.getWorkerId())
                .modelConfigId(task.getModelConfigId())
                .modelVariant(task.getModel())
                .requestedToolCount(scope.requestedToolCount())
                .effectiveToolCount(scope.effectiveToolCount())
                .toolScopeKind(scope.toolScopeKind())
                .toolScopeSource(scope.toolScopeSource())
                .requestedFunctionCount(scope.requestedFunctionCount())
                .effectiveFunctionCount(scope.effectiveFunctionCount())
                .functionScopeSource(scope.functionScopeSource())
                .taskTokenFunctionScopeEmpty(scope.taskTokenFunctionScopeEmpty())
                .runtimeDispatched(scope.runtimeDispatched())
                .modelDispatched(scope.modelDispatched())
                .businessFunctionDispatched(scope.businessFunctionDispatched())
                .createdAt(task.getCreatedAt())
                .completedAt(completedAt)
                .stages(stages)
                .build();
        RuntimeAuditSideEffectsDTO auditSideEffects = noAuditSideEffects();

        return RuntimeTaskAuditDTO.builder()
                .observedAt(Instant.now())
                .taskFacts(taskFacts)
                .auditSideEffects(auditSideEffects)
                .taskId(task.getTaskId())
                .terminal(isTerminal)
                .status(status)
                .sanitizedErrorCode(sanitizedErrorCode)
                .taskTokenStatus(tokenStatus)
                .activeTaskRegistrationPresent(activeRegistration)
                .dispatchCount(counts.dispatchCount())
                .retryCount(counts.retryCount())
                .recoveryCount(counts.recoveryCount())
                .physicalWorkerId(task.getWorkerId())
                .modelConfigId(task.getModelConfigId())
                .modelVariant(task.getModel())
                .requestedToolCount(scope.requestedToolCount())
                .effectiveToolCount(scope.effectiveToolCount())
                .toolScopeKind(scope.toolScopeKind())
                .toolScopeSource(scope.toolScopeSource())
                .requestedFunctionCount(scope.requestedFunctionCount())
                .effectiveFunctionCount(scope.effectiveFunctionCount())
                .functionScopeSource(scope.functionScopeSource())
                .taskTokenFunctionScopeEmpty(scope.taskTokenFunctionScopeEmpty())
                .runtimeDispatched(scope.runtimeDispatched())
                .taskModelDispatched(scope.modelDispatched())
                .taskBusinessFunctionDispatched(scope.businessFunctionDispatched())
                .createdAt(task.getCreatedAt())
                .completedAt(completedAt)
                .terminalStages(stages)
                .auditAccessTokenIssued(false)
                .auditRuntimeTokenIssued(false)
                .auditTaskTokenIssued(false)
                .taskCreated(false)
                .contextCreated(false)
                .sessionCreated(false)
                .modelDispatched(false)
                .businessFunctionDispatched(false)
                .recoveryTriggered(false)
                .provisioningResourceChanged(false)
                .build();
    }

    private Optional<BusinessTaskScopedTokenEntity> resolveOwnedToken(
            ResolvedClientAppCredentialDTO owner,
            SessionTaskEntity task,
            Optional<BusinessTaskTerminalStateEntity> terminal) {
        Optional<BusinessTaskScopedTokenEntity> token = terminal
                .map(BusinessTaskTerminalStateEntity::getBusinessTaskId)
                .filter(StringUtils::hasText)
                .flatMap(businessTaskId -> taskTokenRepository
                        .findFirstByTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
                                businessTaskId, owner.getTenantId(), owner.getClientAppId()));
        if (token.isEmpty()) {
            token = taskTokenRepository
                    .findFirstByWorkerTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
                            task.getTaskId(), owner.getTenantId(), owner.getClientAppId());
        }
        return token;
    }

    private void requireTaskOwnership(
            ResolvedClientAppCredentialDTO owner,
            String upstreamUserId,
            SessionTaskEntity task,
            Optional<BusinessTaskScopedTokenEntity> token) {
        if (token.isPresent()) {
            if (upstreamUserId.equals(token.get().getUpstreamUserId())) {
                return;
            }
            throw new SecurityException("RUNTIME_TASK_AUDIT_FORBIDDEN");
        }
        Optional<BusinessAgentTaskEntity> businessTask = businessTaskRepository
                .findByWorkerTaskIdAndTenantIdAndClientAppId(
                        task.getTaskId(), owner.getTenantId(), owner.getClientAppId());
        if (businessTask.filter(value -> upstreamUserId.equals(value.getUpstreamUserId())).isPresent()) {
            return;
        }
        Optional<BusinessAgentSessionEntity> session = businessSessionRepository
                .findByTenantIdAndClientAppIdAndUpstreamUserIdAndSessionId(
                        owner.getTenantId(), owner.getClientAppId(), upstreamUserId, task.getSessionId());
        if (session.isEmpty()) {
            throw new SecurityException("RUNTIME_TASK_AUDIT_FORBIDDEN");
        }
    }

    private String resolveTokenStatus(Optional<BusinessTaskScopedTokenEntity> token) {
        if (token.isEmpty()) {
            return TOKEN_NOT_FOUND;
        }
        String status = normalizedStatus(token.get().getStatus());
        if (!StringUtils.hasText(status)) {
            return TOKEN_UNCONFIRMED;
        }
        return switch (status) {
            case "ACTIVE", "REVOKED", "EXPIRED" -> status;
            default -> TOKEN_UNCONFIRMED;
        };
    }

    private TaskAttemptCounts resolveAttemptCounts(SessionTaskEntity task) {
        Map<String, Object> state = parseState(task.getTaskStateJson());
        int attemptNumber = nonNegativeInt(state.get("attemptNumber"), 1);
        int retryCount = Math.max(0, attemptNumber - 1);
        int recoveryCount = nonNegativeInt(state.get("recoveryCount"), 0);
        if (recoveryCount == 0 && (hasText(state.get("recoveryCorrelationKey"))
                || hasText(state.get("recoveryOfTaskId")))) {
            recoveryCount = 1;
        }
        int initialDispatch = StringUtils.hasText(task.getProviderTaskId()) ? 1 : 0;
        return new TaskAttemptCounts(initialDispatch + retryCount + recoveryCount, retryCount, recoveryCount);
    }

    private List<RuntimeTaskAuditStageDTO> buildTerminalStages(
            SessionTaskEntity task,
            BusinessTaskTerminalStateEntity terminal,
            BusinessTaskScopedTokenEntity token,
            String sanitizedErrorCode,
            String status,
            List<TerminationOperationEntity> terminationOperations) {
        List<RuntimeTaskAuditStageDTO> stages = new ArrayList<>();
        addStage(stages, "TASK_REGISTERED", "RECORDED", null, task.getCreatedAt());
        if (token != null) {
            addStage(stages, "TASK_TOKEN", "ISSUED", null, token.getIssuedAt());
        }
        if (StringUtils.hasText(task.getProviderTaskId())) {
            addStage(stages, "PROVIDER_TASK_REGISTERED", "RECORDED", null, task.getCreatedAt());
            addStage(stages, "RUNTIME_DISPATCH", "DISPATCHED", null, task.getCreatedAt());
            addStage(stages, "MODEL_DISPATCH", "DISPATCHED", null, task.getCreatedAt());
            addStage(stages, "BUSINESS_FUNCTION_DISPATCH", "NOT_DISPATCHED", null, task.getCreatedAt());
        }
        if (terminationOperations != null && !terminationOperations.isEmpty()) {
            TerminationOperationEntity operation = terminationOperations.get(0);
            addStage(stages, "TERMINATION_REQUESTED", operation.getStatus(),
                    operation.getFailureCode(), operation.getRequestedAt());
            addStage(stages, "TERMINATION_DISPATCH", operation.getDispatchState(),
                    operation.getAttentionCode(), operation.getDispatchedAt());
            if (operation.getObservedAt() != null) {
                addStage(stages, "TERMINATION_OBSERVED", operation.getStatus(),
                        operation.getFailureCode(), operation.getObservedAt());
            }
        }
        if (terminal != null) {
            addStage(stages, "TASK_TERMINAL", status, sanitizedErrorCode, terminal.getTerminalAt());
        } else if (TERMINAL_STATUSES.contains(status)) {
            addStage(stages, "TASK_TERMINAL", status, sanitizedErrorCode, task.getUpdatedAt());
        }
        if (token != null && token.getRevokedAt() != null) {
            addStage(stages, "TASK_TOKEN", "REVOKED", null, token.getRevokedAt());
        }
        addStage(stages, "ACTIVE_TASK_REGISTRATION",
                terminal == null && ACTIVE_STATUSES.contains(normalizedStatus(task.getStatus()))
                        ? "PRESENT"
                        : "ABSENT",
                null,
                terminal != null && terminal.getRevocationCompletedAt() != null
                        ? terminal.getRevocationCompletedAt()
                        : task.getUpdatedAt());
        stages.sort(Comparator.comparing(
                RuntimeTaskAuditStageDTO::getOccurredAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(stages);
    }

    public OwnedRuntimeTask requireOwnedTask(
            String appKey, String appSecret, String upstreamUserId, String taskId) {
        ResolvedClientAppCredentialDTO owner = requireOwner(appKey, appSecret);
        requireText(upstreamUserId, "RUNTIME_TASK_UPSTREAM_USER_REQUIRED");
        requireText(taskId, "RUNTIME_TASK_REQUIRED");
        SessionTaskEntity task = sessionTaskRepository.findByTaskId(taskId.trim())
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_TASK_NOT_FOUND"));
        if (!owner.getTenantId().equals(task.getTenantId())) {
            throw new SecurityException("RUNTIME_TASK_FORBIDDEN");
        }
        Optional<BusinessTaskTerminalStateEntity> terminal =
                terminalStateRepository.findByTenantIdAndWorkerTaskId(owner.getTenantId(), task.getTaskId());
        Optional<BusinessTaskScopedTokenEntity> token = resolveOwnedToken(owner, task, terminal);
        requireTaskOwnership(owner, upstreamUserId.trim(), task, token);
        TaskAttemptCounts counts = resolveAttemptCounts(task);
        return new OwnedRuntimeTask(
                task.getTaskId(), task.getUserId(), task.getTenantId(), task.getProviderType(),
                task.getWorkerId(), normalizedStatus(task.getStatus()),
                TERMINAL_STATUSES.contains(normalizedStatus(task.getStatus())) || terminal.isPresent(),
                counts.dispatchCount());
    }

    public record OwnedRuntimeTask(
            String taskId,
            String ownerUserId,
            String tenantId,
            String providerType,
            String physicalWorkerId,
            String status,
            boolean terminal,
            int dispatchCount) {
    }

    private RuntimeAuditSideEffectsDTO noAuditSideEffects() {
        return RuntimeAuditSideEffectsDTO.builder()
                .accessTokenIssued(false)
                .runtimeTokenIssued(false)
                .taskTokenIssued(false)
                .taskCreated(false)
                .contextCreated(false)
                .sessionCreated(false)
                .modelDispatched(false)
                .businessFunctionDispatched(false)
                .recoveryTriggered(false)
                .provisioningResourceChanged(false)
                .build();
    }

    private boolean isOperatorTermination(TerminationOperationEntity operation) {
        return operation != null
                && "REMOTE_CANCEL".equals(operation.getKind())
                && "RUNTIME_CLIENT".equals(operation.getActorType())
                && "operator-stuck-task-termination".equals(operation.getReasonCode());
    }

    private boolean isFunctionScopeEmpty(BusinessTaskScopedTokenEntity token) {
        if (token == null || !StringUtils.hasText(token.getFunctionScopeJson())) return true;
        try {
            Object value = objectMapper.readValue(token.getFunctionScopeJson(), Object.class);
            return value instanceof java.util.Collection<?> collection && collection.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private TaskScopeFacts resolveScopeFacts(
            Map<String, Object> state, SessionTaskEntity task, boolean functionScopeEmpty) {
        Integer requestedToolCount = nullableNonNegativeInt(state.get("requestedToolCount"));
        Integer effectiveToolCount = nullableNonNegativeInt(state.get("effectiveToolCount"));
        String toolScopeKind = textValue(state.get("toolScopeKind"));
        String toolScopeSource = textValue(state.get("toolScopeSource"));
        Integer requestedFunctionCount = nullableNonNegativeInt(state.get("requestedFunctionCount"));
        Integer effectiveFunctionCount = nullableNonNegativeInt(state.get("effectiveFunctionCount"));
        String functionScopeSource = textValue(state.get("functionScopeSource"));
        Boolean tokenEmpty = booleanObject(state.get("taskTokenFunctionScopeEmpty"));

        // Legacy STANDARD rows did durably persist the empty task-token function scope but
        // not its equivalent admission projection. Preserve that durable fact during audit.
        if (functionScopeEmpty) {
            if (requestedToolCount == null) requestedToolCount = 0;
            if (effectiveToolCount == null) effectiveToolCount = 0;
            if (toolScopeKind == null) toolScopeKind = "NO_RUNTIME_MODEL_TOOL_SURFACE";
            if (toolScopeSource == null) toolScopeSource = "REQUEST_EXPLICIT_EMPTY";
            if (requestedFunctionCount == null) requestedFunctionCount = 0;
            if (effectiveFunctionCount == null) effectiveFunctionCount = 0;
            if (functionScopeSource == null) functionScopeSource = "REQUEST_EXPLICIT_EMPTY";
            if (tokenEmpty == null) tokenEmpty = true;
        }
        boolean dispatched = StringUtils.hasText(task.getProviderTaskId());
        return new TaskScopeFacts(
                requestedToolCount, effectiveToolCount, toolScopeKind, toolScopeSource,
                requestedFunctionCount, effectiveFunctionCount, functionScopeSource, tokenEmpty,
                booleanObject(state.get("runtimeDispatched"), dispatched),
                booleanObject(state.get("modelDispatched"), dispatched),
                booleanObject(state.get("businessFunctionDispatched"), false));
    }

    private Integer nullableNonNegativeInt(Object value) {
        if (value == null) return null;
        return nonNegativeInt(value, 0);
    }

    private String textValue(Object value) {
        return value != null && StringUtils.hasText(value.toString()) ? value.toString().trim() : null;
    }

    private Boolean booleanObject(Object value) {
        return value instanceof Boolean flag ? flag : null;
    }

    private boolean booleanObject(Object value, boolean fallback) {
        Boolean result = booleanObject(value);
        return result != null ? result : fallback;
    }

    private record TaskScopeFacts(
            Integer requestedToolCount,
            Integer effectiveToolCount,
            String toolScopeKind,
            String toolScopeSource,
            Integer requestedFunctionCount,
            Integer effectiveFunctionCount,
            String functionScopeSource,
            Boolean taskTokenFunctionScopeEmpty,
            Boolean runtimeDispatched,
            Boolean modelDispatched,
            Boolean businessFunctionDispatched) {
    }

    private void addStage(
            List<RuntimeTaskAuditStageDTO> stages,
            String stage,
            String status,
            String errorCode,
            LocalDateTime occurredAt) {
        if (occurredAt == null) {
            return;
        }
        stages.add(RuntimeTaskAuditStageDTO.builder()
                .stage(stage)
                .status(status)
                .sanitizedErrorCode(errorCode)
                .occurredAt(occurredAt)
                .build());
    }

    private Map<String, Object> parseState(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> state = objectMapper.readValue(
                    json,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return state != null ? state : Map.of();
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private ResolvedClientAppCredentialDTO requireOwner(String appKey, String appSecret) {
        return credentialResolver.resolve(appKey, appSecret)
                .orElseThrow(() -> new IllegalArgumentException("RUNTIME_AUDIT_CREDENTIAL_REQUIRED"));
    }

    private Integer parsePort(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            int port = URI.create(value.trim()).getPort();
            return port > 0 ? port : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String resolveWorkerHost(ClaudeWorkerEntity worker) {
        String name = worker.getName();
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String normalized = name.trim();
        String suffix = " Claude Code Worker";
        return normalized.endsWith(suffix)
                ? normalized.substring(0, normalized.length() - suffix.length())
                : normalized;
    }

    private String normalizedStatus(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private int nonNegativeInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(0, Integer.parseInt(value.toString()));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean hasText(Object value) {
        return value != null && StringUtils.hasText(value.toString());
    }

    private void requireText(String value, String code) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(code);
        }
    }

    private void requireEqual(String expected, String observed, String code) {
        if (!StringUtils.hasText(expected) || !expected.trim().equals(observed)) {
            throw new IllegalArgumentException(code);
        }
    }

    private record TaskAttemptCounts(int dispatchCount, int retryCount, int recoveryCount) {
    }
}
