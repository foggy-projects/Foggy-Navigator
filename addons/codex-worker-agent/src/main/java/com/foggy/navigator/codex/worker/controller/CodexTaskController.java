package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRegistryService;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codex 任务管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/codex-tasks")
@RequiredArgsConstructor
public class CodexTaskController {

    private final CodexTaskService taskService;
    private final WorkerManagementFacade workerManagementFacade;
    private final CodexWorkerClientFactory clientFactory;
    private final CodexRuntimeRegistryService runtimeRegistryService;

    /**
     * 创建并启动 Codex 任务
     */
    @PostMapping
    public RX<CodexTaskDTO> createTask(@RequestBody CreateCodexTaskForm form) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        form.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        return RX.ok(taskService.createTask(userId, tenantId, form));
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/{taskId}")
    public RX<CodexTaskDTO> getTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskService.getTaskForProvider(
                userId, taskId, CodexTaskService.CODEX_PROVIDER_TYPE));
    }

    /**
     * 查询当前 Codex 会话中 Worker 侧记录的文件改动线索。
     */
    @GetMapping("/{taskId}/file-hints")
    public RX<Map<String, Object>> getSessionFileHints(
            @PathVariable String taskId,
            @RequestParam(required = false, defaultValue = "30") Integer days,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();

        var task = taskService.getTaskEntity(taskId);
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!CodexTaskService.CODEX_PROVIDER_TYPE.equals(task.getProviderType())) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }

        workerManagementFacade.validateWorkerAccess(userId, tenantId, task.getWorkerId());

        if (task.getCodexThreadId() == null || task.getCodexThreadId().isBlank()) {
            return RX.ok(emptyFileHintsResult(taskId, task.getSessionId(), task.getDirectoryId(),
                    task.getCwd(), "Codex thread 尚未建立，暂时没有文件线索"));
        }

        try {
            var client = legacyClient(task.getWorkerId());
            Map<String, Object> workerResult = client.getSessionFileHints(
                            task.getCodexThreadId(), days, from, to)
                    .block(Duration.ofSeconds(10));
            Map<String, Object> result = workerResult != null
                    ? new LinkedHashMap<>(workerResult)
                    : emptyFileHintsResult(taskId, task.getSessionId(), task.getDirectoryId(), task.getCwd(), null);
            result.put("taskId", taskId);
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
     * Proxies a generated image through Navigator without exposing Worker credentials or WSL paths.
     */
    @GetMapping("/{taskId}/generated-images/{artifactId}")
    public ResponseEntity<byte[]> getGeneratedImage(
            @PathVariable String taskId,
            @PathVariable String artifactId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        var task = taskService.getTaskEntity(taskId);
        if (!task.getUserId().equals(userId)
                || !CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE.equals(task.getProviderType())
                || !CodexRuntimeType.APP_SERVER.name().equals(task.getRuntimeType())) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        workerManagementFacade.validateWorkerAccess(userId, tenantId, task.getWorkerId());

        CodexRuntimeBinding runtime = runtimeRegistryService.resolveBoundRuntime(
                task.getRuntimeId(), task.getRuntimeRevision(), task.getWorkerId(),
                task.getRuntimeInstanceId());
        var client = clientFactory.getOrCreate(
                "runtime:" + runtime.getRuntimeId() + ":" + runtime.getRuntimeRevision(),
                runtime.getEndpointUrl(), runtime.getAuthToken(), runtime.getInstanceId());
        String remoteTaskId = task.getWorkerTaskId() != null && !task.getWorkerTaskId().isBlank()
                ? task.getWorkerTaskId()
                : task.getTaskId();
        try {
            ResponseEntity<byte[]> response = client.getGeneratedImage(remoteTaskId, artifactId)
                    .block(Duration.ofSeconds(30));
            if (response == null) {
                return ResponseEntity.internalServerError().build();
            }
            HttpHeaders headers = new HttpHeaders();
            MediaType contentType = response.getHeaders().getContentType();
            headers.setContentType(contentType != null ? contentType : MediaType.APPLICATION_OCTET_STREAM);
            String contentDisposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
            if (contentDisposition != null) headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
            headers.setCacheControl("private, no-store");
            return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
        } catch (Exception e) {
            log.warn("Failed to proxy generated Codex image: taskId={}, artifactId={}, type={}",
                    taskId, artifactId, e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().build();
        }
    }

    private com.foggy.navigator.codex.worker.client.CodexWorkerClient legacyClient(String workerId) {
        CodexConfig codexConfig = workerManagementFacade.getCodexConfig(workerId);
        if (codexConfig == null || codexConfig.getBaseUrl() == null || codexConfig.getBaseUrl().isBlank()) {
            throw new IllegalStateException("Worker 未配置 Codex 服务");
        }
        return clientFactory.getOrCreate(
                workerId + ":codex", codexConfig.getBaseUrl(), codexConfig.getAuthToken());
    }

    /**
     * 列出用户的所有任务
     */
    @GetMapping
    public RX<List<CodexTaskDTO>> listTasks(
            @RequestParam(required = false) String workerId) {
        String userId = UserContext.getCurrentUserId();
        if (workerId != null && !workerId.isBlank()) {
            return RX.ok(taskService.listTasksByWorkerForProvider(
                    userId, workerId, CodexTaskService.CODEX_PROVIDER_TYPE));
        }
        return RX.ok(taskService.listTasksForProvider(userId, CodexTaskService.CODEX_PROVIDER_TYPE));
    }

    /**
     * 中止任务
     */
    @PostMapping("/{taskId}/abort")
    public RX<Map<String, String>> abortTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();

        taskService.getTaskForProvider(userId, taskId, CodexTaskService.CODEX_PROVIDER_TYPE);
        taskService.cancelTaskDirect(taskId, userId);
        return RX.ok(Map.of("taskId", taskId, "status",
                taskService.getTaskForProvider(
                        userId, taskId, CodexTaskService.CODEX_PROVIDER_TYPE).getStatus()));
    }

    /**
     * 重连任务流
     */
    @PostMapping("/{taskId}/reconnect")
    public RX<Map<String, String>> reconnectTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();

        CodexTaskDTO task = taskService.getTaskForProvider(
                userId, taskId, CodexTaskService.CODEX_PROVIDER_TYPE);

        if (!"RUNNING".equals(task.getStatus())) {
            return RX.ok(Map.of("taskId", taskId, "status", task.getStatus(), "message", "Task is not running"));
        }

        taskService.reconnectTask(taskId, userId);
        return RX.ok(Map.of("taskId", taskId, "status", "RECONNECTING"));
    }

    private Map<String, Object> emptyFileHintsResult(
            String taskId,
            String sessionId,
            String directoryId,
            String cwd,
            String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("sessionId", sessionId);
        result.put("directoryId", directoryId);
        result.put("cwd", cwd);
        result.put("files", List.of());
        result.put("total", 0);
        if (message != null && !message.isBlank()) {
            result.put("message", message);
        }
        return result;
    }
}
