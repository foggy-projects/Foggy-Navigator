package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.codex.worker.service.CodexStreamRelay;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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
    private final CodexStreamRelay streamRelay;
    private final WorkerManagementFacade workerManagementFacade;
    private final CodexWorkerClientFactory clientFactory;

    /**
     * 创建并启动 Codex 任务
     */
    @PostMapping
    public RX<CodexTaskDTO> createTask(@RequestBody CreateCodexTaskForm form) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        return RX.ok(taskService.createTask(userId, tenantId, form));
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/{taskId}")
    public RX<CodexTaskDTO> getTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskService.getTask(userId, taskId));
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

        workerManagementFacade.validateWorkerAccess(userId, tenantId, task.getWorkerId());

        if (task.getCodexThreadId() == null || task.getCodexThreadId().isBlank()) {
            return RX.ok(emptyFileHintsResult(taskId, task.getSessionId(), task.getDirectoryId(),
                    task.getCwd(), "Codex thread 尚未建立，暂时没有文件线索"));
        }

        CodexConfig codexConfig = workerManagementFacade.getCodexConfig(task.getWorkerId());
        if (codexConfig == null || codexConfig.getBaseUrl() == null || codexConfig.getBaseUrl().isBlank()) {
            return RX.failA("Worker 未配置 Codex 服务");
        }

        try {
            var client = clientFactory.getOrCreate(
                    task.getWorkerId() + ":codex",
                    codexConfig.getBaseUrl(),
                    codexConfig.getAuthToken());
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
            log.warn("Failed to get Codex session file hints: taskId={}, error={}", taskId, e.getMessage());
            return RX.failA("获取 Codex 文件线索失败: " + e.getMessage());
        }
    }

    /**
     * 列出用户的所有任务
     */
    @GetMapping
    public RX<List<CodexTaskDTO>> listTasks(
            @RequestParam(required = false) String workerId) {
        String userId = UserContext.getCurrentUserId();
        if (workerId != null && !workerId.isBlank()) {
            return RX.ok(taskService.listTasksByWorker(userId, workerId));
        }
        return RX.ok(taskService.listTasks(userId));
    }

    /**
     * 中止任务
     */
    @PostMapping("/{taskId}/abort")
    public RX<Map<String, String>> abortTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();

        // 验证任务属于该用户
        var task = taskService.getTaskEntity(taskId);
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }

        taskService.abortTask(taskId);
        return RX.ok(Map.of("taskId", taskId, "status", "ABORTED"));
    }

    /**
     * 重连任务流
     */
    @PostMapping("/{taskId}/reconnect")
    public RX<Map<String, String>> reconnectTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();

        var task = taskService.getTaskEntity(taskId);
        if (!task.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }

        if (!"RUNNING".equals(task.getStatus())) {
            return RX.ok(Map.of("taskId", taskId, "status", task.getStatus(), "message", "Task is not running"));
        }

        streamRelay.reconnectTask(taskId, task.getSessionId(), task.getWorkerId());
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
