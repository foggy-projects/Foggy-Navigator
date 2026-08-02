package com.foggy.navigator.claude.worker.controller.openapi;

import com.foggy.navigator.business.agent.service.A2AgentResourceResolver;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.OpenApiQueryForm;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only planning boundary for an Open API runtime task launch.
 *
 * <p>The planner resolves resources and produces an immutable snapshot. It never handles caller
 * credentials, task tokens, audit writes, provider dispatch, session writes, or lifecycle closure.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class OpenApiRuntimeTaskLaunchPlanner {

    private static final String BACKEND_OPENAI_CODEX = ProviderRouteRegistry.BACKEND_OPENAI_CODEX;
    private static final String BACKEND_OPENAI_CODEX_APP_SERVER =
            ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER;
    private static final String BACKEND_LANGGRAPH_BIZ = ProviderRouteRegistry.BACKEND_LANGGRAPH_BIZ;
    private static final String SOURCE_BIZ_WORKER_IDENTITY = "BIZ_WORKER_IDENTITY";
    private static final String SOURCE_CLAUDE_WORKER_CODEX_CONFIG = "CLAUDE_WORKER_CODEX_CONFIG";
    private static final String TOOL_SCOPE_SOURCE_RUNTIME_DEFAULT = "RUNTIME_DEFAULT";
    private static final String TOOL_SCOPE_SOURCE_REQUEST_EXPLICIT_EMPTY = "REQUEST_EXPLICIT_EMPTY";
    private static final String TOOL_SCOPE_SOURCE_REQUEST_ALLOWLIST = "REQUEST_ALLOWLIST";
    private static final String TOOL_SCOPE_KIND_NAVIGATOR_BUSINESS_MCP =
            "NAVIGATOR_BUSINESS_MCP_WRAPPERS";
    private static final String TOOL_SCOPE_KIND_NO_RUNTIME = "NO_RUNTIME_MODEL_TOOL_SURFACE";
    private static final Set<String> ALL_BUSINESS_TOOL_ALIASES = Set.of(
            "business.*", "business.functions.*", "navigator.business_functions");
    private static final Map<String, String> BUSINESS_TOOL_ALIASES = Map.of(
            "business.functions.list", "list_business_functions",
            "business.functions.schema", "get_business_function_schema",
            "business.functions.invoke", "invoke_business_function",
            "list_business_functions", "list_business_functions",
            "get_business_function_schema", "get_business_function_schema",
            "invoke_business_function", "invoke_business_function");

    private final ClaudeWorkerRepository workerRepository;

    public ResolvedLaunchResources resolveResources(
            A2AgentResourceResolver resourceResolver,
            LaunchContext context,
            OpenApiQueryForm form) {
        if (resourceResolver == null) {
            throw new IllegalArgumentException("A2Agent resource resolver is required");
        }
        if (context == null) {
            throw new IllegalArgumentException("launch context is required");
        }
        if (form == null) {
            throw new IllegalArgumentException("launch form is required");
        }

        A2AgentResourceResolver.ResolvedAgentResource agentResource =
                resourceResolver.resolveRequiredAgent(
                        context.tenantId(),
                        context.clientAppId(),
                        context.upstreamUserId(),
                        context.agentId());
        String requestedModelConfigId = extractRequestedModelConfigId(form);
        String effectiveRequestedModelConfigId = StringUtils.hasText(requestedModelConfigId)
                ? requestedModelConfigId
                : agentResource.defaultModelConfigId();
        A2AgentResourceResolver.ResolvedModelResource modelResource =
                resourceResolver.resolveRequiredModelForAgent(
                        context.tenantId(),
                        context.clientAppId(),
                        agentResource,
                        effectiveRequestedModelConfigId,
                        extractRequestedModelVariant(form),
                        LlmModelCategory.GENERAL);
        A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource =
                resolveWorkspaceResource(
                        resourceResolver,
                        context,
                        agentResource,
                        form.getDirectoryId());

        return new ResolvedLaunchResources(
                context,
                agentResource,
                modelResource,
                workspaceResource,
                requiresTaskDirectory(agentResource, modelResource));
    }

    public LaunchPlan plan(
            A2AgentResourceResolver resourceResolver,
            ResolvedLaunchResources resources,
            OpenApiQueryForm form) {
        if (resourceResolver == null) {
            throw new IllegalArgumentException("A2Agent resource resolver is required");
        }
        if (resources == null) {
            throw new IllegalArgumentException("resolved launch resources are required");
        }
        if (form == null) {
            throw new IllegalArgumentException("launch form is required");
        }
        LaunchContext context = resources.context();
        A2AgentResourceResolver.ResolvedAgentResource agentResource = resources.agentResource();
        A2AgentResourceResolver.ResolvedModelResource modelResource = resources.modelResource();
        A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource =
                resources.workspaceResource();

        Map<String, Object> metadata = buildMetadata(
                resourceResolver,
                context,
                agentResource,
                modelResource,
                workspaceResource,
                form);
        Object metadataAttachments = metadata.remove("attachments");
        List<Map<String, Object>> normalizedAttachments = OpenApiAttachmentNormalizer.normalize(
                metadataAttachments,
                form.getAttachments());
        if (!normalizedAttachments.isEmpty()) {
            metadata.put("attachments", normalizedAttachments);
        }

        WorkerSelection workerSelection = buildWorkerSelection(
                context,
                agentResource,
                modelResource,
                workspaceResource,
                metadata,
                form);
        return new LaunchPlan(
                context,
                agentResource,
                modelResource,
                workspaceResource,
                resources.taskDirectoryRequired(),
                metadata,
                normalizedAttachments,
                workerSelection);
    }

    private A2AgentResourceResolver.ResolvedWorkspaceResource resolveWorkspaceResource(
            A2AgentResourceResolver resourceResolver,
            LaunchContext context,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            String requestedDirectoryId) {
        String directoryId = firstNonBlank(requestedDirectoryId, agentResource.defaultDirectoryId());
        if (!StringUtils.hasText(directoryId)) {
            return null;
        }
        return resourceResolver.resolveRequiredWorkspaceForAgent(
                context.tenantId(),
                context.clientAppId(),
                context.upstreamUserId(),
                agentResource,
                directoryId);
    }

    private Map<String, Object> buildMetadata(
            A2AgentResourceResolver resourceResolver,
            LaunchContext context,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedModelResource modelResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource,
            OpenApiQueryForm form) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (form.getMetadata() != null && !form.getMetadata().isEmpty()) {
            metadata.putAll(form.getMetadata());
        }
        removeWorkerLaunchOnlyMetadata(metadata);
        mergeTopLevelRuntimeOptions(metadata, form);
        putUntrimmedText(metadata, "modelConfigId", modelResource.modelConfigId());
        putUntrimmedText(metadata, "model", modelResource.modelName());
        putUntrimmedText(metadata, "requestedModelVariant", modelResource.requestedModelVariant());
        putText(metadata, "modelConfigSource", modelResource.source());
        putText(metadata, "workerBackend", firstNonBlank(
                modelResource.workerBackend(), agentResource.workerBackend()));
        putText(metadata, "agentSource", agentResource.source());
        putText(metadata, "workerSource", firstNonBlank(
                workspaceResource != null ? workspaceResource.source() : null,
                agentResource.physicalWorkerSource(),
                agentResource.workerPoolSource()));
        putText(metadata, "backendSource", firstNonBlank(
                modelResource.source(), agentResource.workerPoolSource()));
        injectOwnerAwareLaunchMetadata(
                metadata,
                context,
                resourceResolver,
                agentResource,
                modelResource,
                workspaceResource);
        injectWorkspaceExecutionPolicy(metadata, workspaceResource);
        mergeTopLevelExecutionPolicy(metadata, form);
        if (form.getMaxTurns() != null) {
            metadata.put("maxTurns", form.getMaxTurns());
        }
        putUntrimmedText(metadata, "systemPrompt", form.getSystemPrompt());
        putUntrimmedText(metadata, "firstMsg", form.getFirstMsg());

        ToolScopeSummary toolScopeSummary = resolveToolScope(form.getAllowedTools());
        metadata.put("requestedToolCount", requestedScopeCount(form.getAllowedTools()));
        metadata.put("effectiveToolCount", toolScopeSummary.effectiveToolCount());
        metadata.put("toolScopeSource", toolScopeSummary.source());
        metadata.put("toolScopeKind", toolScopeSummary.effectiveToolCount() == 0
                ? TOOL_SCOPE_KIND_NO_RUNTIME
                : TOOL_SCOPE_KIND_NAVIGATOR_BUSINESS_MCP);
        metadata.put("requestedFunctionCount", requestedScopeCount(form.getAllowedFunctions()));
        if (form.isAllowedFunctionsProvided()
                && cleanRequestListPreservingEmpty(form.getAllowedFunctions()).isEmpty()) {
            metadata.put("effectiveFunctionCount", 0);
            metadata.put("functionScopeSource", "REQUEST_EXPLICIT_EMPTY");
            metadata.put("taskTokenFunctionScopeEmpty", true);
        }
        metadata.put("runtimeDispatched", false);
        metadata.put("modelDispatched", false);
        metadata.put("businessFunctionDispatched", false);
        return metadata;
    }

    private void injectOwnerAwareLaunchMetadata(
            Map<String, Object> metadata,
            LaunchContext context,
            A2AgentResourceResolver resourceResolver,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedModelResource modelResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource) {
        OwnerAwareLaunchWorker launchWorker = resolveOwnerAwareLaunchWorker(
                context.tenantId(),
                context.clientAppId(),
                resourceResolver,
                agentResource,
                modelResource,
                workspaceResource);
        putText(metadata, "workerId", launchWorker.workerId());
        putText(metadata, "workerSource", launchWorker.workerSource());
        if (workspaceResource != null) {
            putText(metadata, "directoryId", workspaceResource.directoryId());
            putText(metadata, "cwd", workspaceResource.workdir());
        } else {
            putText(metadata, "directoryId", agentResource.defaultDirectoryId());
        }
    }

    OwnerAwareLaunchWorker resolveOwnerAwareLaunchWorker(
            String tenantId,
            String clientAppId,
            A2AgentResourceResolver resourceResolver,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedModelResource modelResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource) {
        String workerBackend = firstNonBlank(
                modelResource != null ? modelResource.workerBackend() : null,
                agentResource != null ? agentResource.workerBackend() : null);
        String workspaceWorkerId = workspaceResource != null
                ? textValue(workspaceResource.physicalWorkerId()) : null;
        String workspaceWorkerSource = workspaceResource != null
                ? textValue(workspaceResource.source()) : null;
        String agentWorkerId = agentResource != null
                ? textValue(agentResource.physicalWorkerId()) : null;
        String agentWorkerSource = agentResource != null
                ? textValue(agentResource.physicalWorkerSource()) : null;
        if ((isBackend(workerBackend, BACKEND_OPENAI_CODEX)
                || isBackend(workerBackend, BACKEND_OPENAI_CODEX_APP_SERVER))
                && StringUtils.hasText(workspaceWorkerId)) {
            if (isBackend(workerBackend, BACKEND_OPENAI_CODEX)
                    && hasClaudeCodexConfig(workspaceWorkerId)) {
                return new OwnerAwareLaunchWorker(
                        workspaceWorkerId, SOURCE_CLAUDE_WORKER_CODEX_CONFIG);
            }
            return new OwnerAwareLaunchWorker(workspaceWorkerId, workspaceWorkerSource);
        }
        if (isBackend(workerBackend, BACKEND_LANGGRAPH_BIZ)) {
            if (agentResource != null && StringUtils.hasText(agentResource.workerPoolId())) {
                return OwnerAwareLaunchWorker.empty();
            }
            if (StringUtils.hasText(agentWorkerId)
                    && SOURCE_BIZ_WORKER_IDENTITY.equals(agentWorkerSource)) {
                return new OwnerAwareLaunchWorker(agentWorkerId, agentWorkerSource);
            }
            Optional<String> workerHostBizWorkerId =
                    resourceResolver.resolveLatestHealthyBizWorkerIdentityId(tenantId, clientAppId);
            if (workerHostBizWorkerId != null && workerHostBizWorkerId.isPresent()) {
                log.info("Resolved Open API LangGraph Biz launch worker from worker host identity: "
                                + "tenantId={}, clientAppId={}, agentId={}, originalWorkerId={}, workerId={}",
                        tenantId,
                        clientAppId,
                        agentResource != null ? agentResource.agentId() : null,
                        agentWorkerId,
                        workerHostBizWorkerId.get());
                return new OwnerAwareLaunchWorker(
                        workerHostBizWorkerId.get(), SOURCE_BIZ_WORKER_IDENTITY);
            }
            return OwnerAwareLaunchWorker.empty();
        }
        if (StringUtils.hasText(agentWorkerId)) {
            return new OwnerAwareLaunchWorker(agentWorkerId, agentWorkerSource);
        }
        return new OwnerAwareLaunchWorker(workspaceWorkerId, workspaceWorkerSource);
    }

    private WorkerSelection buildWorkerSelection(
            LaunchContext context,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedModelResource modelResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource,
            Map<String, Object> metadata,
            OpenApiQueryForm form) {
        String physicalWorkerId = textValue(metadata.get("workerId"));
        String routeId = firstNonBlank(
                agentResource.workerPoolId(), physicalWorkerId, agentResource.physicalWorkerId());
        String workerBackend = firstNonBlank(
                modelResource.workerBackend(), agentResource.workerBackend());
        boolean directCodexPhysicalWorkerRoute = isBackend(workerBackend, BACKEND_OPENAI_CODEX)
                && SOURCE_CLAUDE_WORKER_CODEX_CONFIG.equals(textValue(metadata.get("workerSource")))
                && StringUtils.hasText(physicalWorkerId);
        return new WorkerSelection(
                context,
                directCodexPhysicalWorkerRoute ? physicalWorkerId : routeId,
                directCodexPhysicalWorkerRoute ? null : agentResource.workerPoolOwnerType(),
                directCodexPhysicalWorkerRoute ? null : agentResource.workerPoolOwnerId(),
                physicalWorkerId,
                workerBackend,
                modelResource.modelConfigId(),
                modelResource.modelName(),
                workspaceResource != null ? workspaceResource.directoryId() : null,
                workspaceResource != null ? workspaceResource.workdir() : null,
                workspaceResource != null ? workspaceResource.allowedDirs() : null,
                cleanRequestListPreservingEmpty(form.getAllowedTools()),
                form.isAllowedFunctionsProvided(),
                form.getAllowedFunctions());
    }

    private boolean hasClaudeCodexConfig(String workerId) {
        if (!StringUtils.hasText(workerId)) {
            return false;
        }
        return workerRepository.findByWorkerId(workerId.trim())
                .map(ClaudeWorkerEntity::getCodexConfig)
                .map(config -> config.getBaseUrl())
                .filter(StringUtils::hasText)
                .isPresent();
    }

    private boolean requiresTaskDirectory(
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedModelResource modelResource) {
        return isBackend(firstNonBlank(
                modelResource.workerBackend(), agentResource.workerBackend()), BACKEND_LANGGRAPH_BIZ);
    }

    private void injectWorkspaceExecutionPolicy(
            Map<String, Object> metadata,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource) {
        if (workspaceResource == null) {
            return;
        }
        Map<String, Object> runtimeContext = mutableStringMap(metadata.get("runtimeContext"));
        Map<String, Object> executionPolicy =
                mutableStringMap(runtimeContext.get("execution_policy"));
        putText(executionPolicy, "directory_id", workspaceResource.directoryId());
        if (workspaceResource.workspaceScope() != null) {
            executionPolicy.put("workspace_scope", workspaceResource.workspaceScope().name());
        }
        if (workspaceResource.resolverType() != null) {
            executionPolicy.put("workspace_resolver_type", workspaceResource.resolverType().name());
        }
        executionPolicy.put("read_only", workspaceResource.readOnly());
        putObject(executionPolicy, "quota_policy", workspaceResource.quotaPolicy());
        putObject(executionPolicy, "retention_policy", workspaceResource.retentionPolicy());
        putObject(executionPolicy, "concurrency_policy", workspaceResource.concurrencyPolicy());
        putText(executionPolicy, "workdir", workspaceResource.workdir());
        putStringList(executionPolicy, "allowed_dirs", workspaceResource.allowedDirs());
        if (!executionPolicy.isEmpty()) {
            runtimeContext.put("execution_policy", executionPolicy);
            metadata.put("runtimeContext", runtimeContext);
        }
    }

    private void removeWorkerLaunchOnlyMetadata(Map<String, Object> metadata) {
        for (String key : List.of(
                "runtimeContext", "runtime_context",
                "skill_name", "skillName", "skillId", "skill_id",
                "skill_markdown", "skillMarkdown", "markdownBody",
                "workerId", "worker_id", "physicalWorkerId", "physical_worker_id",
                "selectedWorkerId", "selected_worker_id",
                "workerLeaseId", "worker_lease_id",
                "directoryId", "directory_id", "cwd",
                "modelConfigSource", "model_config_source",
                "workerBackend", "worker_backend",
                "agentSource", "agent_source",
                "workerSource", "worker_source",
                "backendSource", "backend_source",
                "taskSource", "task_source")) {
            metadata.remove(key);
        }
    }

    private void mergeTopLevelExecutionPolicy(
            Map<String, Object> metadata, OpenApiQueryForm form) {
        Map<String, Object> policy = new LinkedHashMap<>();
        putText(policy, "workdir", form.getWorkdir());
        putStringList(policy, "allowed_dirs", form.getAllowedDirs());
        putStringListPreservingEmpty(policy, "allowed_tools", form.getAllowedTools());
        if (policy.isEmpty()) {
            return;
        }
        Map<String, Object> context = mutableStringMap(metadata.get("context"));
        Map<String, Object> executionPolicy = mutableStringMap(context.get("execution_policy"));
        executionPolicy.putAll(policy);
        context.put("execution_policy", executionPolicy);
        metadata.put("context", context);
    }

    private void mergeTopLevelRuntimeOptions(Map<String, Object> metadata, OpenApiQueryForm form) {
        putText(metadata, "providerType", form.getProviderType());
        putText(metadata, "codexHomeKey", form.getCodexHomeKey());
        putText(metadata, "privateAccountId", form.getPrivateAccountId());
        putText(metadata, "sandboxMode", form.getSandboxMode());
        putText(metadata, "approvalPolicy", form.getApprovalPolicy());
        putText(metadata, "webSearchMode", form.getWebSearchMode());
        putObject(metadata, "networkAccessEnabled", form.getNetworkAccessEnabled());
    }

    private String extractRequestedModelConfigId(OpenApiQueryForm form) {
        if (StringUtils.hasText(form.getModelConfigId())) {
            return form.getModelConfigId();
        }
        Object value = form.getMetadata() != null ? form.getMetadata().get("modelConfigId") : null;
        return value instanceof String text && StringUtils.hasText(text) ? text : null;
    }

    private String extractRequestedModelVariant(OpenApiQueryForm form) {
        if (StringUtils.hasText(form.getModelVariant())) {
            return form.getModelVariant();
        }
        if (form.getMetadata() == null || form.getMetadata().isEmpty()) {
            return null;
        }
        for (String key : List.of("modelVariant", "model", "modelName", "model_name", "model_variant")) {
            Object value = form.getMetadata().get(key);
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private ToolScopeSummary resolveToolScope(List<String> allowedTools) {
        if (allowedTools == null) {
            return new ToolScopeSummary(3, TOOL_SCOPE_SOURCE_RUNTIME_DEFAULT);
        }
        List<String> cleaned = cleanRequestListPreservingEmpty(allowedTools);
        if (cleaned.isEmpty()) {
            return new ToolScopeSummary(0, TOOL_SCOPE_SOURCE_REQUEST_EXPLICIT_EMPTY);
        }
        Set<String> effectiveTools = new LinkedHashSet<>();
        for (String allowedTool : cleaned) {
            String normalized = allowedTool.toLowerCase(Locale.ROOT);
            if (ALL_BUSINESS_TOOL_ALIASES.contains(normalized)) {
                return new ToolScopeSummary(3, TOOL_SCOPE_SOURCE_REQUEST_ALLOWLIST);
            }
            String toolName = BUSINESS_TOOL_ALIASES.get(normalized);
            if (toolName != null) {
                effectiveTools.add(toolName);
            }
        }
        return new ToolScopeSummary(effectiveTools.size(), TOOL_SCOPE_SOURCE_REQUEST_ALLOWLIST);
    }

    private Integer requestedScopeCount(List<String> values) {
        return values == null ? null : cleanRequestListPreservingEmpty(values).size();
    }

    private boolean isBackend(String actual, String expected) {
        return expected.equals(ProviderRouteRegistry.canonicalWorkerBackendOrNull(actual));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text : null;
    }

    private static void putText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private static void putUntrimmedText(
            Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private static void putObject(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void putStringList(
            Map<String, Object> target, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> cleaned = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (!cleaned.isEmpty()) {
            target.put(key, cleaned);
        }
    }

    private static void putStringListPreservingEmpty(
            Map<String, Object> target, String key, List<String> values) {
        if (values != null) {
            target.put(key, cleanRequestListPreservingEmpty(values));
        }
    }

    private static List<String> cleanRequestListPreservingEmpty(List<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static Map<String, Object> mutableStringMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) {
            source.forEach((key, item) -> {
                if (key instanceof String stringKey) {
                    result.put(stringKey, mutableCopy(item));
                }
            });
        }
        return result;
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<Object, Object> frozen = new LinkedHashMap<>();
            source.forEach((key, item) -> frozen.put(key, freeze(item)));
            return Collections.unmodifiableMap(frozen);
        }
        if (value instanceof List<?> source) {
            List<Object> frozen = new ArrayList<>(source.size());
            source.forEach(item -> frozen.add(freeze(item)));
            return Collections.unmodifiableList(frozen);
        }
        return value;
    }

    private static Object mutableCopy(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            source.forEach((key, item) -> copy.put(key, mutableCopy(item)));
            return copy;
        }
        if (value instanceof List<?> source) {
            List<Object> copy = new ArrayList<>(source.size());
            source.forEach(item -> copy.add(mutableCopy(item)));
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return (Map<String, Object>) freeze(source == null ? Map.of() : source);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> immutableAttachments(
            List<Map<String, Object>> source) {
        return (List<Map<String, Object>>) (List<?>) freeze(source == null ? List.of() : source);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableMap(Map<String, Object> source) {
        return (Map<String, Object>) mutableCopy(source);
    }

    public record LaunchContext(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String agentId,
            String skillId,
            String contextId) {
    }

    public record ResolvedLaunchResources(
            LaunchContext context,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedModelResource modelResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource,
            boolean taskDirectoryRequired) {

        public boolean taskDirectoryMissing() {
            return taskDirectoryRequired && workspaceResource == null;
        }
    }

    public record LaunchPlan(
            LaunchContext context,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedModelResource modelResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource,
            boolean taskDirectoryRequired,
            Map<String, Object> metadata,
            List<Map<String, Object>> normalizedAttachments,
            WorkerSelection workerSelection) {

        public LaunchPlan {
            metadata = immutableMap(metadata);
            normalizedAttachments = immutableAttachments(normalizedAttachments);
        }

        public boolean taskDirectoryMissing() {
            return taskDirectoryRequired && workspaceResource == null;
        }

        public Map<String, Object> mutableMetadata() {
            return mutableMap(metadata);
        }

        public List<Map<String, Object>> mutableNormalizedAttachments() {
            List<Map<String, Object>> copy = new ArrayList<>(normalizedAttachments.size());
            normalizedAttachments.forEach(item -> copy.add(mutableMap(item)));
            return copy;
        }

        public BusinessAgentWorkerTaskLaunchRequest workerSelectionRequest(String actorUserId) {
            return workerSelection.toRequest(actorUserId);
        }
    }

    record OwnerAwareLaunchWorker(String workerId, String workerSource) {

        private static OwnerAwareLaunchWorker empty() {
            return new OwnerAwareLaunchWorker(null, null);
        }
    }

    public record WorkerSelection(
            LaunchContext context,
            String workerPoolId,
            ResourceOwnerType workerPoolOwnerType,
            String workerPoolOwnerId,
            String physicalWorkerId,
            String workerBackend,
            String modelConfigId,
            String model,
            String directoryId,
            String workdir,
            List<String> allowedDirs,
            List<String> allowedTools,
            boolean allowedFunctionsProvided,
            List<String> allowedFunctions) {

        public WorkerSelection {
            allowedDirs = allowedDirs == null ? null : List.copyOf(allowedDirs);
            allowedTools = allowedTools == null ? null : List.copyOf(allowedTools);
            allowedFunctions = allowedFunctions == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<>(allowedFunctions));
        }

        BusinessAgentWorkerTaskLaunchRequest toRequest(String actorUserId) {
            return BusinessAgentWorkerTaskLaunchRequest.builder()
                    .tenantId(context.tenantId())
                    .actorUserId(actorUserId)
                    .sessionId(context.contextId())
                    .contextId(context.contextId())
                    .clientAppId(context.clientAppId())
                    .upstreamUserId(context.upstreamUserId())
                    .agentId(context.agentId())
                    .skillId(context.skillId())
                    .workerPoolId(workerPoolId)
                    .workerPoolOwnerType(workerPoolOwnerType)
                    .workerPoolOwnerId(workerPoolOwnerId)
                    .physicalWorkerId(physicalWorkerId)
                    .workerBackend(workerBackend)
                    .modelConfigId(modelConfigId)
                    .model(model)
                    .directoryId(directoryId)
                    .workdir(workdir)
                    .allowedDirs(allowedDirs)
                    .allowedTools(allowedTools)
                    .allowedFunctionsProvided(allowedFunctionsProvided)
                    .allowedFunctions(allowedFunctions)
                    .build();
        }
    }

    private record ToolScopeSummary(int effectiveToolCount, String source) {
    }
}
