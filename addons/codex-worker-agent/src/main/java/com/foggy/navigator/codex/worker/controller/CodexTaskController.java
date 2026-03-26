package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.service.CodexStreamRelay;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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

        streamRelay.abortRemoteTask(task);
        streamRelay.abortStream(taskId);
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
}
