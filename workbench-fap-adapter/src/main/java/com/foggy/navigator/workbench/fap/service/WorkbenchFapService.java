package com.foggy.navigator.workbench.fap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.foggy.agent.client.CreateTaskCommand;
import com.foggy.agent.client.CreateTaskResult;
import com.foggy.agent.client.FoggyAgentPlatformClient;
import com.foggy.agent.client.PlatformCallerContext;
import com.foggy.agent.client.PlatformClientException;
import com.foggy.agent.client.StartExecutionCommand;
import com.foggy.agent.client.StartExecutionResult;
import com.foggy.agent.contract.access.v1alpha1.CatalogPage;
import com.foggy.agent.contract.access.v1alpha1.ScopeReduction;
import com.foggy.agent.contract.runtime.v1alpha1.ExecutionSnapshot;
import com.foggy.agent.contract.runtime.v1alpha1.OperationAccepted;
import com.foggy.agent.contract.runtime.v1alpha1.TaskSnapshot;
import com.foggy.navigator.workbench.fap.config.WorkbenchFapProperties;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.ContinueConversationForm;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.ConversationView;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.OperationForm;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.StartConversationForm;
import com.foggy.navigator.workbench.fap.persistence.WorkbenchFapConversationBindingEntity;
import com.foggy.navigator.workbench.fap.persistence.WorkbenchFapConversationBindingEntity.BindingStatus;
import com.foggy.navigator.workbench.fap.persistence.WorkbenchFapConversationBindingRepository;
import com.foggy.navigator.workbench.fap.web.WorkbenchFapException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product adapter for the isolated personal FAP lane.
 *
 * <p>This class may persist only Workbench ownership, immutable lane/resource references, request
 * identifiers, Runtime execution/task references, and effective scopes. It must not absorb legacy
 * Session/Task lifecycle, Worker tickets, raw transcripts, credentials, grants, routes, or
 * provider facts. Any proposal to bridge/fallback between FAP and legacy paths is a boundary
 * change requiring explicit owner authorization.
 */
@Service
@ConditionalOnProperty(
        prefix = "navigator.workbench.fap",
        name = "enabled",
        havingValue = "true")
public class WorkbenchFapService {
    private static final int MAX_PROMPT_LENGTH = 200_000;

    private final WorkbenchFapConversationBindingRepository bindings;
    private final FoggyAgentPlatformClient platform;
    private final WorkbenchFapProperties properties;
    private final WorkbenchFapCommandMapper commands;
    private final WorkbenchFapProjectionMapper projections;

    public WorkbenchFapService(
            WorkbenchFapConversationBindingRepository bindings,
            FoggyAgentPlatformClient platform,
            WorkbenchFapProperties properties,
            WorkbenchFapCommandMapper commands,
            WorkbenchFapProjectionMapper projections) {
        this.bindings = bindings;
        this.platform = platform;
        this.properties = properties;
        this.commands = commands;
        this.projections = projections;
    }

    public CatalogPage catalog(String userId, String resourceType) {
        requireEligible(userId);
        try {
            return platform.catalog(caller(userId, null, null), resourceType);
        } catch (PlatformClientException error) {
            throw platformError(error);
        }
    }

    public List<ConversationView> list(String userId) {
        requireEligible(userId);
        return bindings.findTop100ByOwnerUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(binding -> projections.localView(binding, List.of()))
                .toList();
    }

    public ConversationView start(String userId, StartConversationForm form) {
        requireEligible(userId);
        Objects.requireNonNull(form, "form");
        String requestId = required(form.requestId(), "requestId");
        var existing = bindings.findByOwnerUserIdAndStartRequestId(userId, requestId);
        if (existing.isPresent()) {
            WorkbenchFapConversationBindingEntity binding = existing.get();
            if (binding.getBindingStatus() == BindingStatus.ACTIVE) {
                return liveView(binding, List.of());
            }
            throw new WorkbenchFapException(
                    409,
                    "FAP_START_REQUEST_ALREADY_BOUND",
                    "The START request is already bound and will not be rerun automatically",
                    false);
        }

        String prompt = prompt(form.prompt());
        String workerProfileRef = required(form.workerProfileRef(), "workerProfileRef");
        String workspaceRef = required(form.workspaceRef(), "workspaceRef");
        boolean allowDefault = form.allowDefaultModelConfig() == null
                || form.allowDefaultModelConfig();
        String modelConfigRef = optional(form.modelConfigRef());
        if (!allowDefault && modelConfigRef == null) {
            throw new IllegalArgumentException(
                    "modelConfigRef is required when default ModelConfig is disabled");
        }

        String conversationId = UUID.randomUUID().toString();
        WorkbenchFapConversationBindingEntity binding =
                WorkbenchFapConversationBindingEntity.starting(
                        conversationId,
                        userId,
                        requestId,
                        title(form.title(), prompt),
                        workerProfileRef,
                        workspaceRef,
                        modelConfigRef,
                        allowDefault);
        binding = bindings.saveAndFlush(binding);

        StartExecutionResult result;
        try {
            result = platform.start(new StartExecutionCommand(
                    requestId,
                    caller(userId, conversationId, requestId),
                    commands.selection(
                            workerProfileRef, workspaceRef, modelConfigRef, allowDefault),
                    commands.input(prompt),
                    commands.providerOptions(form.providerOptions())));
        } catch (PlatformClientException error) {
            binding.startFailed(error.code(), error.status() == 0 && error.retryable());
            bindings.saveAndFlush(binding);
            throw platformError(error);
        }

        binding.activate(
                result.accepted().executionId(),
                result.accepted().taskId(),
                commands.jsonScope(result.effectiveToolScope()),
                commands.jsonScope(result.effectivePermissionScope()));
        binding = bindings.saveAndFlush(binding);
        return projections.acceptedView(
                binding, result.accepted(), result.scopeReductions());
    }

    @Transactional
    public ConversationView continueConversation(
            String userId, String conversationId, ContinueConversationForm form) {
        requireEligible(userId);
        Objects.requireNonNull(form, "form");
        String requestId = required(form.requestId(), "requestId");
        WorkbenchFapConversationBindingEntity binding = ownedForUpdate(userId, conversationId);
        requireActive(binding);
        if (requestId.equals(binding.getLastTaskRequestId())) {
            return liveView(binding, List.of());
        }

        CreateTaskResult result;
        try {
            result = platform.createTask(new CreateTaskCommand(
                    requestId,
                    caller(userId, conversationId, requestId),
                    binding.getExecutionId(),
                    "CONTINUE",
                    commands.persistedScope(binding.getEffectiveToolScope()),
                    commands.persistedScope(binding.getEffectivePermissionScope()),
                    commands.input(prompt(form.prompt())),
                    null,
                    commands.providerOptions(form.providerOptions())));
        } catch (PlatformClientException error) {
            throw platformError(error);
        }
        binding.advanceTask(requestId, result.accepted().taskId());
        bindings.saveAndFlush(binding);
        return projections.acceptedView(
                binding, result.accepted(), result.scopeReductions());
    }

    public ConversationView get(String userId, String conversationId) {
        requireEligible(userId);
        return liveView(owned(userId, conversationId), List.of());
    }

    @Transactional
    public OperationAccepted cancel(String userId, String conversationId, OperationForm form) {
        requireEligible(userId);
        Objects.requireNonNull(form, "form");
        WorkbenchFapConversationBindingEntity binding = ownedForUpdate(userId, conversationId);
        requireActive(binding);
        try {
            TaskSnapshot task = platform.task(binding.getCurrentTaskId());
            return platform.cancelTask(
                    required(form.requestId(), "requestId"),
                    caller(userId, conversationId, form.requestId()),
                    binding.getCurrentTaskId(),
                    requiredRevision(task.revision(), "task revision"),
                    form.reasonCode() == null || form.reasonCode().isBlank()
                            ? "USER_REQUESTED"
                            : form.reasonCode(),
                    form.message());
        } catch (PlatformClientException error) {
            throw platformError(error);
        }
    }

    @Transactional
    public OperationAccepted reattach(String userId, String conversationId, OperationForm form) {
        requireEligible(userId);
        Objects.requireNonNull(form, "form");
        WorkbenchFapConversationBindingEntity binding = ownedForUpdate(userId, conversationId);
        requireActive(binding);
        try {
            ExecutionSnapshot execution = platform.execution(binding.getExecutionId());
            return platform.reattachExecution(
                    required(form.requestId(), "requestId"),
                    caller(userId, conversationId, form.requestId()),
                    binding.getExecutionId(),
                    requiredRevision(execution.revision(), "execution revision"));
        } catch (PlatformClientException error) {
            throw platformError(error);
        }
    }

    public JsonNode events(String userId, String conversationId, long afterSeq, int limit) {
        requireEligible(userId);
        WorkbenchFapConversationBindingEntity binding = ownedActive(userId, conversationId);
        try {
            return projections.sanitizeEvents(
                    platform.events(binding.getExecutionId(), afterSeq, limit));
        } catch (PlatformClientException error) {
            throw platformError(error);
        }
    }

    public JsonNode resources(String userId, String conversationId) {
        requireEligible(userId);
        WorkbenchFapConversationBindingEntity binding = ownedActive(userId, conversationId);
        try {
            return projections.sanitizeResources(platform.resources(binding.getExecutionId()));
        } catch (PlatformClientException error) {
            throw platformError(error);
        }
    }

    public JsonNode recovery(String userId, String conversationId) {
        requireEligible(userId);
        WorkbenchFapConversationBindingEntity binding = ownedActive(userId, conversationId);
        try {
            return projections.sanitizeRecovery(platform.recovery(binding.getExecutionId()));
        } catch (PlatformClientException error) {
            throw platformError(error);
        }
    }

    private ConversationView liveView(
            WorkbenchFapConversationBindingEntity binding,
            List<ScopeReduction> reductions) {
        if (binding.getBindingStatus() != BindingStatus.ACTIVE) {
            return projections.localView(binding, reductions);
        }
        try {
            ExecutionSnapshot execution = platform.execution(binding.getExecutionId());
            TaskSnapshot task = platform.task(binding.getCurrentTaskId());
            return projections.liveView(binding, execution, task, reductions);
        } catch (PlatformClientException error) {
            throw platformError(error);
        }
    }

    private WorkbenchFapConversationBindingEntity owned(String userId, String conversationId) {
        return bindings.findByConversationIdAndOwnerUserId(
                        required(conversationId, "conversationId"), userId)
                .orElseThrow(() -> new WorkbenchFapException(
                        404, "FAP_CONVERSATION_NOT_FOUND", "FAP conversation was not found", false));
    }

    private WorkbenchFapConversationBindingEntity ownedForUpdate(
            String userId, String conversationId) {
        return bindings.findOwnedForUpdate(required(conversationId, "conversationId"), userId)
                .orElseThrow(() -> new WorkbenchFapException(
                        404, "FAP_CONVERSATION_NOT_FOUND", "FAP conversation was not found", false));
    }

    private WorkbenchFapConversationBindingEntity ownedActive(
            String userId, String conversationId) {
        WorkbenchFapConversationBindingEntity binding = owned(userId, conversationId);
        requireActive(binding);
        return binding;
    }

    private void requireActive(WorkbenchFapConversationBindingEntity binding) {
        if (binding.getBindingStatus() != BindingStatus.ACTIVE
                || binding.getExecutionId() == null
                || binding.getCurrentTaskId() == null) {
            throw new WorkbenchFapException(
                    409,
                    "FAP_CONVERSATION_NOT_ACTIVE",
                    "The FAP conversation is not active and cannot enter another execution lane",
                    false);
        }
    }

    private void requireEligible(String userId) {
        if (!properties.isEligible(userId)) {
            throw new WorkbenchFapException(
                    403,
                    "FAP_CANARY_NOT_AUTHORIZED",
                    "The personal FAP Workbench canary is not enabled for this user",
                    false);
        }
    }

    private PlatformCallerContext caller(
            String userId, String conversationId, String correlationId) {
        return new PlatformCallerContext(
                properties.internalPrincipalRef(userId),
                conversationId,
                userId,
                null,
                correlationId);
    }

    private WorkbenchFapException platformError(PlatformClientException error) {
        int status = error.status() >= 400 && error.status() <= 599 ? error.status() : 503;
        return new WorkbenchFapException(
                status, error.code(), error.getMessage(), error.retryable());
    }

    private String prompt(String value) {
        String prompt = required(value, "prompt");
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("prompt is too large");
        }
        return prompt;
    }

    private String title(String value, String prompt) {
        String title = optional(value);
        if (title == null) {
            title = prompt.replaceAll("\\s+", " ").strip();
            if (title.length() > 80) title = title.substring(0, 80);
        }
        if (title.length() > 256) {
            throw new IllegalArgumentException("title is too large");
        }
        return title;
    }

    private long requiredRevision(Long value, String name) {
        if (value == null || value < 1) {
            throw new WorkbenchFapException(
                    502,
                    "FAP_RUNTIME_REVISION_MISSING",
                    "Runtime did not return a valid " + name,
                    false);
        }
        return value;
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String required(String value, String name) {
        String result = optional(value);
        if (result == null) throw new IllegalArgumentException(name + " is required");
        return result;
    }
}
