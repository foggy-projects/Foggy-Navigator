package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.ConversationConfigDTO;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.*;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.ConversationConfigService;
import com.foggy.navigator.claude.worker.service.WorkerStreamRelay;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 任务管理 API
 */
@RestController
@RequestMapping("/api/v1/claude-tasks")
@RequireAuth
@Slf4j
@RequiredArgsConstructor
public class ClaudeTaskController {

    private final ClaudeTaskService taskService;
    private final ClaudeWorkerService workerService;
    private final ConversationConfigService configService;
    private final WorkerStreamRelay streamRelay;

    @PostMapping
    public RX<TaskDTO> createTask(@RequestBody CreateTaskForm form) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        return RX.ok(taskService.createTask(userId, tenantId, form));
    }

    @PostMapping("/resume")
    public RX<TaskDTO> resumeTask(@RequestBody ResumeTaskForm form) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        return RX.ok(taskService.resumeTask(userId, tenantId, form));
    }

    @GetMapping("/{taskId}")
    public RX<TaskDTO> getTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskService.getTask(userId, taskId));
    }

    @GetMapping
    public RX<List<TaskDTO>> listTasks() {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskService.listTasks(userId));
    }

    @GetMapping("/page")
    public RX<Page<TaskDTO>> listTasksPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskService.listTasks(userId, page, size));
    }

    @PostMapping("/{taskId}/abort")
    public RX<Map<String, Object>> abortTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        var task = taskService.getTaskEntity(taskId);
        if (!task.getUserId().equals(userId)) {
            throw RX.throwB("Task not found");
        }

        // 1. 中止本地流订阅
        streamRelay.abortStream(taskId);

        // 2. 通知 Worker 中止
        try {
            ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
            ClaudeWorkerClient client = workerService.createClient(worker);
            client.abortTask(taskId).block(java.time.Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("Failed to send abort to worker: taskId={}, error={}", taskId, e.getMessage());
        }

        // 3. 更新任务状态
        taskService.abortTask(taskId);

        return RX.ok(Map.of("taskId", taskId, "status", "ABORTED"));
    }

    @GetMapping("/directory/{directoryId}")
    public RX<List<TaskDTO>> listTasksByDirectory(@PathVariable String directoryId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskService.listTasksByDirectory(userId, directoryId));
    }

    @GetMapping("/directory/{directoryId}/page")
    public RX<Page<TaskDTO>> listTasksByDirectoryPaged(
            @PathVariable String directoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskService.listTasksByDirectory(userId, directoryId, page, size));
    }

    @GetMapping("/worker/{workerId}/sessions")
    public RX<List<Map<String, Object>>> listWorkerSessions(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw RX.throwB("Worker not found");
        }

        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            List<Map<String, Object>> sessions = client.listSessions()
                    .block(java.time.Duration.ofSeconds(10));
            return RX.ok(sessions != null ? sessions : List.of());
        } catch (Exception e) {
            log.warn("Failed to list worker sessions: workerId={}, error={}", workerId, e.getMessage());
            return RX.ok(List.of());
        }
    }

    @GetMapping("/worker/{workerId}/sessions/{sessionId}/messages")
    public RX<List<Map<String, Object>>> getWorkerSessionMessages(
            @PathVariable String workerId,
            @PathVariable String sessionId) {
        String userId = UserContext.getCurrentUserId();
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw RX.throwB("Worker not found");
        }

        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            List<Map<String, Object>> messages = client.getSessionMessages(sessionId)
                    .block(java.time.Duration.ofSeconds(30));
            return RX.ok(messages != null ? messages : List.of());
        } catch (Exception e) {
            log.warn("Failed to get session messages: workerId={}, sessionId={}, error={}",
                    workerId, sessionId, e.getMessage());
            return RX.ok(List.of());
        }
    }

    @PostMapping("/worker/{workerId}/sessions/sync")
    public RX<Map<String, Object>> syncWorkerSessions(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw RX.throwB("Worker not found");
        }

        try {
            ClaudeWorkerClient client = workerService.createClient(worker);

            // 1. Trigger Worker to re-scan JSONL files
            client.syncSessions().block(java.time.Duration.ofSeconds(30));

            // 2. Get all sessions from Worker
            java.util.List<Map<String, Object>> sessions = client.listSessions()
                    .block(java.time.Duration.ofSeconds(10));
            if (sessions == null) sessions = java.util.List.of();

            // 2.5. Get Worker auth config (for auto-binding)
            Map<String, Object> workerAuthConfig = null;
            try {
                workerAuthConfig = client.getAuthConfig()
                        .block(java.time.Duration.ofSeconds(5));
            } catch (Exception e) {
                log.warn("Failed to get auth config from worker {}: {}", workerId, e.getMessage());
            }

            // 3. Create ClaudeTask entities for new sessions (with auth binding)
            int created = taskService.syncLocalSessions(userId, tenantId, workerId, sessions, workerAuthConfig);

            return RX.ok(Map.of("synced", created, "total", sessions.size()));
        } catch (Exception e) {
            log.warn("Failed to sync sessions on worker: workerId={}, error={}", workerId, e.getMessage());
            return RX.failB("同步失败: " + e.getMessage());
        }
    }

    // ===== Conversation Config endpoints =====

    @PatchMapping("/conversations/{sessionId}/pin")
    public RX<ConversationConfigDTO> updatePin(
            @PathVariable String sessionId,
            @RequestBody UpdatePinForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(configService.updatePin(sessionId, userId, form.isPinned()));
    }

    @PatchMapping("/conversations/{sessionId}/title")
    public RX<ConversationConfigDTO> updateTitle(
            @PathVariable String sessionId,
            @RequestBody UpdateTitleForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(configService.updateTitle(sessionId, userId, form.getTitle()));
    }

    @PostMapping("/conversations/{sessionId}/bind-auth")
    public RX<ConversationConfigDTO> bindAuth(
            @PathVariable String sessionId,
            @RequestBody BindAuthForm form) {
        String userId = UserContext.getCurrentUserId();
        try {
            return RX.ok(configService.bindAuth(sessionId, userId,
                    form.getAuthMode(), form.getAuthToken(), form.getBaseUrl()));
        } catch (IllegalStateException e) {
            throw RX.throwB(e.getMessage());
        }
    }

    @GetMapping("/conversation-configs")
    public RX<List<ConversationConfigDTO>> listConversationConfigs(
            @RequestParam List<String> sessionIds) {
        return RX.ok(configService.listBySessionIds(sessionIds));
    }

    @DeleteMapping("/{taskId}")
    public RX<Map<String, Object>> deleteTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        taskService.deleteTask(userId, taskId);
        return RX.ok(Map.of("taskId", taskId, "deleted", true));
    }
}
