package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRegistryService;
import com.foggy.navigator.codex.worker.service.CodexRuntimeUnavailableException;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.session.service.SessionTaskResourceAccessService;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Codex-specific read capabilities exposed below the unified task route.
 *
 * <p>The unified SessionTask record is the authorization source. The Codex private task record
 * is consulted only after ownership has been established and must agree with that projection.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
@RequireAuth
@RequiredArgsConstructor
public class CodexTaskExtensionController {

    private static final String SDK_RUNTIME_TYPE = "SDK_EXEC";
    private static final String ARTIFACT_ID_PATTERN = "[a-f0-9]{32}";
    private static final String OPERATION_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    private static final Set<String> TERMINAL_TASK_STATUSES = Set.of(
            "COMPLETED", "FAILED", "ABORTED");
    private static final Set<String> SAFE_GENERATED_IMAGE_TYPES = Set.of(
            MediaType.IMAGE_PNG_VALUE,
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_GIF_VALUE,
            "image/webp");
    private static final Set<String> PRIVATE_WORKER_FIELDS = Set.of(
            "authtoken", "auth_token", "apikey", "api_key", "authorization", "token",
            "credential", "credentials", "endpointurl", "endpoint_url", "local_path");

    private final SessionTaskResourceAccessService resourceAccessService;
    private final CodexTaskService taskService;
    private final WorkerManagementFacade workerManagementFacade;
    private final CodexWorkerClientFactory clientFactory;
    private final CodexRuntimeRegistryService runtimeRegistryService;

    /**
     * Operational page used by the app-server canary without reopening the legacy Codex task API.
     */
    @GetMapping("/operations/codex-canary")
    public RX<TaskPageResult> listCodexCanaryTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String workerId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        if (!hasText(userId) || !hasText(tenantId)) {
            throw new SecurityException("Resource access denied");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be greater than or equal to 0");
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("size must be between 1 and 200");
        }
        if (page > Integer.MAX_VALUE / size) {
            throw new IllegalArgumentException("page is too large");
        }
        return RX.ok(taskService.listTasksPagedForProvider(
                userId, tenantId, page, size, null, workerId,
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE));
    }

    /**
     * Queries file-change hints for the Codex SDK thread bound to the owned task.
     */
    @GetMapping("/{taskId}/file-hints")
    public RX<Map<String, Object>> getSessionFileHints(
            @PathVariable String taskId,
            @RequestParam(required = false, defaultValue = "30") Integer days,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        CodexTaskEntity task = requireTask(
                taskId, userId, tenantId, CodexTaskService.CODEX_PROVIDER_TYPE, SDK_RUNTIME_TYPE);

        workerManagementFacade.validateWorkerAccess(userId, tenantId, task.getWorkerId());

        if (task.getCodexThreadId() == null || task.getCodexThreadId().isBlank()) {
            return RX.ok(emptyFileHintsResult(task,
                    "Codex thread 尚未建立，暂时没有文件线索"));
        }

        try {
            Map<String, Object> workerResult = sdkClient(task.getWorkerId())
                    .getSessionFileHints(task.getCodexThreadId(), days, from, to)
                    .block(Duration.ofSeconds(10));
            Map<String, Object> result = workerResult != null
                    ? sanitizeWorkerPayload(workerResult)
                    : emptyFileHintsResult(task, null);
            // Task context is authoritative; a Worker response cannot redirect the UI to another task.
            result.put("taskId", task.getTaskId());
            result.put("sessionId", task.getSessionId());
            result.put("codexThreadId", task.getCodexThreadId());
            result.put("directoryId", task.getDirectoryId());
            result.put("cwd", task.getCwd());
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to get Codex session file hints: taskId={}, type={}",
                    taskId, e.getClass().getSimpleName());
            return RX.failA("获取 Codex 文件线索失败: CODEX_SESSION_FILE_HINTS_UNAVAILABLE");
        }
    }

    /**
     * Proxies one generated image from the exact app-server runtime instance bound to the task.
     */
    @GetMapping("/{taskId}/generated-images/{artifactId}")
    public ResponseEntity<byte[]> getGeneratedImage(
            @PathVariable String taskId,
            @PathVariable String artifactId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        CodexTaskEntity task = requireTask(
                taskId, userId, tenantId, CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                CodexRuntimeType.APP_SERVER.name());
        requireArtifactId(artifactId);
        requirePinnedAppServerRuntime(task);

        workerManagementFacade.validateWorkerAccess(userId, tenantId, task.getWorkerId());

        CodexRuntimeBinding runtime = runtimeRegistryService.resolveBoundRuntime(
                task.getRuntimeId(), task.getRuntimeRevision(), task.getWorkerId(),
                task.getRuntimeInstanceId());
        CodexWorkerClient client = clientFactory.getOrCreate(
                "runtime:" + runtime.getRuntimeId() + ":" + runtime.getRuntimeRevision(),
                runtime.getEndpointUrl(), runtime.getAuthToken(), runtime.getInstanceId());
        String remoteTaskId = hasText(task.getWorkerTaskId())
                ? task.getWorkerTaskId()
                : task.getTaskId();
        try {
            ResponseEntity<byte[]> response = client.getGeneratedImage(remoteTaskId, artifactId)
                    .block(Duration.ofSeconds(30));
            if (response == null) {
                return noStoreErrorResponse();
            }
            if (!isSafeGeneratedImage(response.getHeaders().getContentType())) {
                log.warn("Rejected non-raster Codex image response: taskId={}, artifactId={}",
                        taskId, artifactId);
                return noStoreErrorResponse();
            }
            HttpHeaders headers = safeImageHeaders(response.getHeaders());
            return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
        } catch (Exception e) {
            log.warn("Failed to proxy generated Codex image: taskId={}, artifactId={}, type={}",
                    taskId, artifactId, e.getClass().getSimpleName());
            return noStoreErrorResponse();
        }
    }

    /** Returns the latest task-bound native token-usage observation from the pinned runtime. */
    @GetMapping("/{taskId}/context-usage")
    public RX<Map<String, Object>> getContextUsage(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        CodexTaskEntity task = requireTask(
                taskId, userId, tenantId, CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                CodexRuntimeType.APP_SERVER.name());
        requirePinnedAppServerRuntime(task);
        requireThread(task);
        workerManagementFacade.validateWorkerAccess(userId, tenantId, task.getWorkerId());

        CodexWorkerClient client = pinnedAppServerClient(task);
        try {
            Map<String, Object> workerResult = client.getTaskContextUsage(remoteTaskId(task))
                    .block(Duration.ofSeconds(10));
            return RX.ok(authoritativeContextResult(task, workerResult));
        } catch (CodexWorkerClient.WorkerQueryRejectedException e) {
            return RX.failA(e.getCode());
        } catch (Exception e) {
            log.warn("Failed to get Codex context usage: taskId={}, type={}",
                    taskId, e.getClass().getSimpleName());
            return RX.failA("CODEX_CONTEXT_USAGE_UNAVAILABLE");
        }
    }

    /**
     * Reads the exact App Server turn state before the UI offers a second
     * cancellation attempt. Native identifiers remain Worker/server private.
     */
    @GetMapping("/{taskId}/termination-inspection")
    public RX<Map<String, Object>> getTerminationInspection(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        CodexTaskEntity task = requireTask(
                taskId, userId, tenantId, CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                CodexRuntimeType.APP_SERVER.name());
        requirePinnedAppServerRuntime(task);
        workerManagementFacade.validateWorkerAccess(userId, tenantId, task.getWorkerId());

        try {
            Map<String, Object> workerResult = pinnedAppServerClient(task)
                    .getTerminationInspection(remoteTaskId(task))
                    .block(Duration.ofSeconds(10));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", task.getTaskId());
            result.put("providerType", task.getProviderType());
            result.put("taskStatus", task.getStatus());
            copySafeTerminationField(workerResult, result, "lifecycle_status", "workerLifecycleStatus");
            copySafeTerminationField(workerResult, result, "provider_state", "providerState");
            copySafeTerminationField(workerResult, result, "thread_status", "threadStatus");
            copySafeTerminationField(workerResult, result, "turn_status", "turnStatus");
            copySafeTerminationField(workerResult, result, "recommended_action", "recommendedAction");
            copySafeTerminationField(workerResult, result, "checked_at", "checkedAt");
            return RX.ok(result);
        } catch (CodexWorkerClient.WorkerQueryRejectedException e) {
            return RX.failA(e.getCode());
        } catch (CodexRuntimeUnavailableException e) {
            return RX.failA(e.getCode());
        } catch (Exception e) {
            log.warn("Failed to inspect Codex App Server cancellation: taskId={}, type={}",
                    taskId, e.getClass().getSimpleName());
            return RX.failA("CODEX_TERMINATION_INSPECTION_UNAVAILABLE");
        }
    }

    /** Executes the user-confirmed retry; the Worker revalidates exact affinity again. */
    @PostMapping("/{taskId}/termination-retry")
    public RX<Map<String, Object>> retryTermination(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        CodexTaskEntity task = requireTask(
                taskId, userId, tenantId, CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                CodexRuntimeType.APP_SERVER.name());
        requirePinnedAppServerRuntime(task);
        workerManagementFacade.validateWorkerAccess(userId, tenantId, task.getWorkerId());
        CodexTaskService.AppServerAbortRetryResult result =
                taskService.retryAppServerAbort(taskId, userId, tenantId);
        return RX.ok(Map.of(
                "taskId", result.taskId(),
                "operationId", result.operationId(),
                "providerState", result.providerState(),
                "status", result.status()));
    }

    /**
     * Returns only server-derived eligibility for cleaning a stale native turn.
     * A terminal status alone is never enough to expose the action: the service
     * also requires the persisted App Server affinity and exact task binding.
     */
    @GetMapping("/{taskId}/stale-turn-cleanup-eligibility")
    public RX<Map<String, Object>> getStaleTurnCleanupEligibility(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        // Keep the unified task record as the first authorization boundary.
        // Owned but ineligible tasks receive a safe false projection rather
        // than leaking Codex-private runtime identity to the browser.
        resourceAccessService.requireOwnedTask(taskId, userId, tenantId);
        CodexTaskService.StaleTurnCleanupEligibility eligibility =
                taskService.getStaleTurnCleanupEligibility(taskId, userId, tenantId);
        if (eligibility.eligible()) {
            // Eligibility must mean the action is currently usable, not merely
            // that its persisted Task fields look safe. A revoked Worker grant
            // must not leave a dead-end destructive-looking button visible.
            try {
                CodexTaskEntity task = taskService.getTaskEntity(taskId);
                if (task == null || !hasText(task.getWorkerId())) {
                    eligibility = new CodexTaskService.StaleTurnCleanupEligibility(
                            taskId, false, "STALE_TURN_CLEANUP_UNAVAILABLE");
                } else {
                    workerManagementFacade.validateWorkerAccess(userId, tenantId, task.getWorkerId());
                }
            } catch (IllegalArgumentException | SecurityException ignored) {
                // The caller has already passed the task ownership boundary.
                // Do not disclose Worker identity or access-policy detail in
                // this browser-safe eligibility projection.
                eligibility = new CodexTaskService.StaleTurnCleanupEligibility(
                        taskId, false, "STALE_TURN_CLEANUP_UNAVAILABLE");
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", taskId);
        response.put("eligible", eligibility.eligible());
        if (!eligibility.eligible() && hasText(eligibility.reasonCode())) {
            response.put("reasonCode", eligibility.reasonCode());
        }
        return RX.ok(response);
    }

    /**
     * Cleans only the signed native turn bound to this terminal App Server
     * task. There is intentionally no request body for browser-supplied
     * Worker, runtime, thread, turn, or process identity.
     */
    @PostMapping("/{taskId}/stale-turn-cleanup")
    public RX<Map<String, Object>> cleanupStaleTurn(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        CodexTaskEntity task = requireTask(taskId, userId, tenantId,
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                CodexRuntimeType.APP_SERVER.name());
        workerManagementFacade.validateWorkerAccess(userId, tenantId, task.getWorkerId());
        CodexTaskService.StaleTurnCleanupResult result =
                taskService.cleanupStaleTurn(taskId, userId, tenantId);
        return RX.ok(Map.of(
                "taskId", result.taskId(),
                "operationId", result.operationId(),
                "status", result.status()));
    }

    /**
     * Stale-turn cleanup has two deliberately distinct non-success outcomes:
     * a definitive conflict is safe to show as 409, while an unconfirmed
     * remote/audit/runtime outcome is retryable and must not masquerade as an
     * internal server error. The service provides a fixed safe code only.
     */
    @ExceptionHandler(CodexTaskService.StaleTurnCleanupException.class)
    public ResponseEntity<RX<?>> handleStaleTurnCleanupException(
            CodexTaskService.StaleTurnCleanupException error) {
        HttpStatus status = error.isRetryable()
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(RX.failA(error.getSafeCode()));
    }

    @ExceptionHandler(CodexTaskService.AppServerAbortRetryException.class)
    public ResponseEntity<RX<?>> handleAppServerAbortRetryException(
            CodexTaskService.AppServerAbortRetryException error) {
        HttpStatus status = error.isRetryable()
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(RX.failA(error.getSafeCode()));
    }

    /** Starts one idempotent whole-thread native compaction on the exact pinned runtime. */
    @PostMapping("/{taskId}/compact-context")
    public RX<Map<String, Object>> compactContext(
            @PathVariable String taskId,
            @RequestBody CompactContextRequest request) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        CodexTaskEntity task = requireTask(
                taskId, userId, tenantId, CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                CodexRuntimeType.APP_SERVER.name());
        requireTerminalTask(task);
        requirePinnedAppServerRuntime(task);
        requireThread(task);
        String operationId = requireOperationId(request);
        workerManagementFacade.validateWorkerAccess(userId, tenantId, task.getWorkerId());

        CodexWorkerClient client = pinnedAppServerClient(task);
        try {
            Map<String, Object> workerResult = client.compactTaskContext(
                            remoteTaskId(task), operationId)
                    .block(Duration.ofMinutes(5));
            return RX.ok(authoritativeContextResult(task, workerResult));
        } catch (CodexWorkerClient.WorkerQueryRejectedException e) {
            return RX.failA(e.getCode());
        } catch (Exception e) {
            log.warn("Failed to compact Codex context: taskId={}, operationId={}, type={}",
                    taskId, operationId, e.getClass().getSimpleName());
            return RX.failA("CODEX_CONTEXT_COMPACT_UNAVAILABLE");
        }
    }

    /** Reads a previously submitted idempotent compaction operation. */
    @GetMapping("/{taskId}/compact-context/{operationId}")
    public RX<Map<String, Object>> getCompactContextOperation(
            @PathVariable String taskId,
            @PathVariable String operationId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        CodexTaskEntity task = requireTask(
                taskId, userId, tenantId, CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                CodexRuntimeType.APP_SERVER.name());
        requirePinnedAppServerRuntime(task);
        requireThread(task);
        if (operationId == null || !operationId.matches(OPERATION_ID_PATTERN)) {
            throw new IllegalArgumentException("operationId is invalid");
        }
        workerManagementFacade.validateWorkerAccess(userId, tenantId, task.getWorkerId());

        CodexWorkerClient client = pinnedAppServerClient(task);
        try {
            Map<String, Object> workerResult = client.getTaskContextCompactOperation(
                            remoteTaskId(task), operationId)
                    .block(Duration.ofSeconds(10));
            return RX.ok(authoritativeContextResult(task, workerResult));
        } catch (CodexWorkerClient.WorkerQueryRejectedException e) {
            return RX.failA(e.getCode());
        } catch (Exception e) {
            log.warn("Failed to read Codex context compact operation: taskId={}, operationId={}, type={}",
                    taskId, operationId, e.getClass().getSimpleName());
            return RX.failA("CODEX_CONTEXT_COMPACT_OPERATION_UNAVAILABLE");
        }
    }

    public record CompactContextRequest(String operationId) {}

    private CodexTaskEntity requireTask(String taskId,
                                        String userId,
                                        String tenantId,
                                        String expectedProviderType,
                                        String expectedRuntimeType) {
        SessionTaskEntity ownedTask = resourceAccessService.requireOwnedTask(
                taskId, userId, tenantId);
        if (!expectedProviderType.equals(ownedTask.getProviderType())) {
            throw taskNotFound(taskId);
        }

        CodexTaskEntity task = taskService.getTaskEntity(taskId);
        if (!expectedProviderType.equals(task.getProviderType())
                || !expectedRuntimeType.equals(task.getRuntimeType())
                || !Objects.equals(ownedTask.getSessionId(), task.getSessionId())
                || !Objects.equals(ownedTask.getWorkerId(), task.getWorkerId())
                || !matchesOwnerScope(task, userId, tenantId)) {
            throw taskNotFound(taskId);
        }
        return task;
    }

    private boolean matchesOwnerScope(CodexTaskEntity task, String userId, String tenantId) {
        if (task == null || !hasText(userId) || !Objects.equals(userId, task.getUserId())) {
            return false;
        }
        return hasText(tenantId)
                ? Objects.equals(tenantId, task.getTenantId())
                : !hasText(task.getTenantId());
    }

    private void requirePinnedAppServerRuntime(CodexTaskEntity task) {
        if (!hasText(task.getRuntimeId())
                || task.getRuntimeRevision() == null
                || !hasText(task.getRuntimeInstanceId())
                || !hasText(task.getWorkerId())) {
            throw taskNotFound(task.getTaskId());
        }
    }

    private void requireTerminalTask(CodexTaskEntity task) {
        if (!TERMINAL_TASK_STATUSES.contains(task.getStatus())) {
            throw new IllegalStateException("TASK_NOT_TERMINAL");
        }
    }

    private void requireThread(CodexTaskEntity task) {
        if (!hasText(task.getCodexThreadId())) {
            throw new IllegalStateException("CODEX_THREAD_NOT_ESTABLISHED");
        }
    }

    private String requireOperationId(CompactContextRequest request) {
        String operationId = request == null ? null : request.operationId();
        if (operationId == null || !operationId.matches(OPERATION_ID_PATTERN)) {
            throw new IllegalArgumentException("operationId is invalid");
        }
        return operationId;
    }

    private CodexWorkerClient pinnedAppServerClient(CodexTaskEntity task) {
        CodexRuntimeBinding runtime = runtimeRegistryService.resolveBoundRuntime(
                task.getRuntimeId(), task.getRuntimeRevision(), task.getWorkerId(),
                task.getRuntimeInstanceId());
        return clientFactory.getOrCreate(
                "runtime:" + runtime.getRuntimeId() + ":" + runtime.getRuntimeRevision(),
                runtime.getEndpointUrl(), runtime.getAuthToken(), runtime.getInstanceId());
    }

    private String remoteTaskId(CodexTaskEntity task) {
        return hasText(task.getWorkerTaskId()) ? task.getWorkerTaskId() : task.getTaskId();
    }

    private Map<String, Object> authoritativeContextResult(
            CodexTaskEntity task, Map<String, Object> workerResult) {
        Map<String, Object> result = workerResult == null
                ? new LinkedHashMap<>()
                : sanitizeWorkerPayload(workerResult);
        result.remove("task_id");
        result.remove("thread_id");
        renameIfPresent(result, "last_total_tokens", "current_tokens");
        renameIfPresent(result, "compact_turn_id", "turn_id");
        renameIfPresent(result, "created_at", "started_at");
        result.put("taskId", task.getTaskId());
        result.put("sessionId", task.getSessionId());
        result.put("codexThreadId", task.getCodexThreadId());
        return result;
    }

    private void renameIfPresent(Map<String, Object> result, String source, String target) {
        if (result.containsKey(source)) {
            result.put(target, result.remove(source));
        }
    }

    private void copySafeTerminationField(
            Map<String, Object> source, Map<String, Object> target,
            String sourceKey, String targetKey) {
        if (source == null) return;
        Object value = source.get(sourceKey);
        if (value instanceof String text && !text.isBlank()) {
            target.put(targetKey, text);
        }
    }

    private void requireArtifactId(String artifactId) {
        if (artifactId == null || !artifactId.matches(ARTIFACT_ID_PATTERN)) {
            throw new IllegalArgumentException("artifactId is invalid");
        }
    }

    private CodexWorkerClient sdkClient(String workerId) {
        CodexConfig codexConfig = workerManagementFacade.getCodexConfig(workerId);
        if (codexConfig == null || !hasText(codexConfig.getBaseUrl())) {
            throw new IllegalStateException("Worker 未配置 Codex 服务");
        }
        return clientFactory.getOrCreate(
                workerId + ":codex", codexConfig.getBaseUrl(), codexConfig.getAuthToken());
    }

    private Map<String, Object> sanitizeWorkerPayload(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!isPrivateWorkerField(key)) {
                result.put(key, sanitizeWorkerValue(value));
            }
        });
        return result;
    }

    private Object sanitizeWorkerValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                String field = String.valueOf(key);
                if (!isPrivateWorkerField(field)) {
                    sanitized.put(field, sanitizeWorkerValue(nestedValue));
                }
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>(list.size());
            list.forEach(item -> sanitized.add(sanitizeWorkerValue(item)));
            return sanitized;
        }
        return value;
    }

    private boolean isPrivateWorkerField(String field) {
        return field != null
                && PRIVATE_WORKER_FIELDS.contains(field.toLowerCase(Locale.ROOT));
    }

    private Map<String, Object> emptyFileHintsResult(CodexTaskEntity task, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getTaskId());
        result.put("sessionId", task.getSessionId());
        result.put("directoryId", task.getDirectoryId());
        result.put("cwd", task.getCwd());
        result.put("files", List.of());
        result.put("total", 0);
        if (hasText(message)) {
            result.put("message", message);
        }
        return result;
    }

    private HttpHeaders safeImageHeaders(HttpHeaders workerHeaders) {
        HttpHeaders headers = new HttpHeaders();
        MediaType contentType = workerHeaders.getContentType();
        headers.setContentType(contentType);
        // Never forward a Worker-provided filename or path. The browser only needs inline rendering.
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline");
        headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        return headers;
    }

    private boolean isSafeGeneratedImage(MediaType contentType) {
        return contentType != null
                && SAFE_GENERATED_IMAGE_TYPES.contains(contentType.toString().toLowerCase(Locale.ROOT));
    }

    private ResponseEntity<byte[]> noStoreErrorResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        return new ResponseEntity<>((byte[]) null, headers,
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private IllegalArgumentException taskNotFound(String taskId) {
        return new IllegalArgumentException("Task not found: " + taskId);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
