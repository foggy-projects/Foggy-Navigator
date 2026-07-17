package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRegistryService;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
                || !Objects.equals(userId, task.getUserId())
                || !Objects.equals(tenantId, task.getTenantId())) {
            throw taskNotFound(taskId);
        }
        return task;
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
