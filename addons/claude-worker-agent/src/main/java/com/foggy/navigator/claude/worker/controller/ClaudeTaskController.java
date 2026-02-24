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

        // 2. 通知 Worker 中止 (使用 worker 内部 task ID)
        try {
            ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
            ClaudeWorkerClient client = workerService.createClient(worker);
            String workerTaskId = streamRelay.getWorkerTaskId(taskId);
            client.abortTask(workerTaskId).block(java.time.Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("Failed to send abort to worker: taskId={}, error={}", taskId, e.getMessage());
        }

        // 3. 更新任务状态
        taskService.abortTask(taskId);

        return RX.ok(Map.of("taskId", taskId, "status", "ABORTED"));
    }

    @PostMapping("/{taskId}/respond")
    public RX<Map<String, Object>> respondToPermission(
            @PathVariable String taskId,
            @RequestBody PermissionResponseForm form) {
        String userId = UserContext.getCurrentUserId();
        var task = taskService.getTaskEntity(taskId);
        if (!task.getUserId().equals(userId)) {
            throw RX.throwB("Task not found");
        }

        try {
            ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
            ClaudeWorkerClient client = workerService.createClient(worker);
            String workerTaskId = streamRelay.getWorkerTaskId(taskId);
            client.respondToPermission(workerTaskId, form.getPermissionId(),
                    form.getDecision(), form.getDenyMessage(), form.getScope(),
                    form.getAnswers(), form.getPlanAction())
                    .block(java.time.Duration.ofSeconds(10));

            // Resume task from AWAITING_PERMISSION to RUNNING
            taskService.resumeFromPermission(taskId);

            return RX.ok(Map.of("taskId", taskId, "permissionId", form.getPermissionId(),
                    "decision", form.getDecision()));
        } catch (Exception e) {
            log.warn("Failed to respond to permission: taskId={}, error={}", taskId, e.getMessage());
            return RX.failB("响应权限请求失败: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/rewind")
    public RX<Map<String, Object>> rewindToCheckpoint(
            @PathVariable String taskId,
            @RequestBody Map<String, Object> body) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        var task = taskService.getTaskEntity(taskId);
        if (!task.getUserId().equals(userId)) {
            throw RX.throwB("Task not found");
        }

        // Only allow rewind on completed/failed tasks
        if ("RUNNING".equals(task.getStatus()) || "AWAITING_PERMISSION".equals(task.getStatus())) {
            throw RX.throwB("Cannot rewind a running task");
        }

        String checkpointId = (String) body.get("checkpointId");

        String claudeSessionId = task.getClaudeSessionId();
        if (claudeSessionId == null || claudeSessionId.isEmpty()) {
            throw RX.throwB("Task has no Claude session ID");
        }

        String mode = body.get("mode") != null ? body.get("mode").toString() : "file_rewind";
        Integer turnIndex = body.get("turnIndex") != null ? ((Number) body.get("turnIndex")).intValue() : null;

        if ("conversation_fork".equals(mode)) {
            // Conversation rewind: mark messages from turnIndex onwards as sidechain
            // in the JSONL. Does NOT create a new task — returns the original prompt
            // so the frontend can populate the input field for user to edit and send.

            ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
            ClaudeWorkerClient client = workerService.createClient(worker);

            try {
                Map<String, Object> rewindResult = client.rewindConversation(
                        claudeSessionId, turnIndex != null ? turnIndex : 1)
                        .block(java.time.Duration.ofSeconds(15));

                String rewindStatus = rewindResult != null ? (String) rewindResult.get("status") : "error";
                if ("error".equals(rewindStatus)) {
                    throw RX.throwB("回退会话失败: " + rewindResult.get("message"));
                }

                String userPrompt = rewindResult != null ? (String) rewindResult.get("user_prompt") : "";

                // Clean up Navigator DB messages (3rd layer: JSONL done, UI done by frontend, now DB)
                int effectiveTurn = turnIndex != null ? turnIndex : 1;
                try {
                    taskService.truncateSessionMessages(task.getSessionId(), effectiveTurn);
                } catch (Exception dbEx) {
                    log.warn("Failed to truncate DB messages for session {}: {}", task.getSessionId(), dbEx.getMessage());
                }

                var result = new java.util.HashMap<String, Object>();
                result.put("status", "rewound");
                result.put("taskId", taskId);
                result.put("userPrompt", userPrompt != null ? userPrompt : "");
                result.put("turnIndex", turnIndex);
                result.put("claudeSessionId", claudeSessionId);
                return RX.ok(result);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to rewind conversation: {}", e.getMessage());
                return RX.failB("回退失败: " + e.getMessage());
            }
        }

        // file_rewind mode — requires checkpointId
        if (checkpointId == null || checkpointId.isEmpty()) {
            throw RX.throwB("checkpointId is required for file_rewind mode");
        }

        try {
            ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
            ClaudeWorkerClient client = workerService.createClient(worker);
            Map<String, Object> result = client.rewindFiles(claudeSessionId, checkpointId, task.getCwd())
                    .block(java.time.Duration.ofSeconds(30));
            return RX.ok(result != null ? result : Map.of("status", "rewound", "checkpointId", checkpointId));
        } catch (Exception e) {
            log.warn("Failed to rewind files: taskId={}, error={}", taskId, e.getMessage());
            return RX.failB("回退失败: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/scan-checkpoints")
    public RX<Map<String, Object>> scanCheckpoints(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        var task = taskService.getTaskEntity(taskId);
        if (!task.getUserId().equals(userId)) {
            throw RX.throwB("Task not found");
        }
        String claudeSessionId = task.getClaudeSessionId();
        if (claudeSessionId == null || claudeSessionId.isEmpty()) {
            throw RX.throwB("Task has no Claude session ID");
        }

        try {
            ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
            ClaudeWorkerClient client = workerService.createClient(worker);
            List<Map<String, Object>> scanned = client.scanSessionCheckpoints(claudeSessionId)
                    .block(java.time.Duration.ofSeconds(30));
            if (scanned == null || scanned.isEmpty()) {
                return RX.ok(Map.of("taskId", taskId, "checkpoints", "[]", "count", 0));
            }
            String json = taskService.scanAndPopulateCheckpoints(taskId, scanned);
            return RX.ok(Map.of("taskId", taskId, "checkpoints", json, "count", scanned.size()));
        } catch (Exception e) {
            log.warn("Failed to scan checkpoints: taskId={}, error={}", taskId, e.getMessage());
            return RX.failB("扫描 Checkpoint 失败: " + e.getMessage());
        }
    }

    @GetMapping("/worker/{workerId}/sessions/{sessionId}/message-count")
    public RX<Map<String, Object>> getWorkerSessionMessageCount(
            @PathVariable String workerId,
            @PathVariable String sessionId) {
        String userId = UserContext.getCurrentUserId();
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (!worker.getUserId().equals(userId)) {
            throw RX.throwB("Worker not found");
        }

        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            Map<String, Object> result = client.getSessionMessageCount(sessionId)
                    .block(java.time.Duration.ofSeconds(10));
            return RX.ok(result != null ? result : Map.of("user_count", 0, "assistant_count", 0, "total", 0));
        } catch (Exception e) {
            log.warn("Failed to get message count: workerId={}, sessionId={}, error={}",
                    workerId, sessionId, e.getMessage());
            return RX.ok(Map.of("user_count", 0, "assistant_count", 0, "total", 0));
        }
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

            // 3. Create ClaudeTask entities for new sessions (with directory auth binding)
            int created = taskService.syncLocalSessions(userId, tenantId, workerId, sessions);

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

    @PostMapping("/conversations/batch-bind-auth")
    public RX<Map<String, Object>> batchBindAuth(@RequestBody BatchBindAuthForm form) {
        String userId = UserContext.getCurrentUserId();
        int bound = configService.batchBindAuth(form.getSessionIds(), userId,
                form.getAuthMode(), form.getAuthToken(), form.getBaseUrl(),
                form.isSkipExisting());
        return RX.ok(Map.of("bound", bound, "total", form.getSessionIds().size()));
    }

    @DeleteMapping("/{taskId}")
    public RX<Map<String, Object>> deleteTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        taskService.deleteTask(userId, taskId);
        return RX.ok(Map.of("taskId", taskId, "deleted", true));
    }

    // ===== Private helpers =====

    /**
     * Fallback for conversation_fork when JSONL rewind fails.
     * Creates a brand-new session with conversation context extracted from messages.
     */
    private RX<Map<String, Object>> fallbackConversationFork(
            String userId, String tenantId,
            com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity task,
            ClaudeWorkerClient client, String claudeSessionId,
            Integer turnIndex, String checkpointId) {

        String forkPrompt;
        try {
            List<Map<String, Object>> messages = client.getSessionMessages(claudeSessionId)
                    .block(java.time.Duration.ofSeconds(15));
            forkPrompt = buildForkPrompt(messages, turnIndex);
        } catch (Exception ex) {
            forkPrompt = "Please continue the previous task.";
        }

        CreateTaskForm createForm = new CreateTaskForm();
        createForm.setWorkerId(task.getWorkerId());
        createForm.setPrompt(forkPrompt);
        createForm.setCwd(task.getCwd());
        createForm.setDirectoryId(task.getDirectoryId());

        TaskDTO newTask = taskService.createTask(userId, tenantId, createForm);
        var result = new java.util.HashMap<String, Object>();
        result.put("status", "forked");
        result.put("taskId", newTask.getTaskId());
        result.put("sessionId", newTask.getSessionId());
        result.put("reusedSession", false);
        if (checkpointId != null) result.put("checkpointId", checkpointId);
        return RX.ok(result);
    }

    /**
     * Build a fork prompt from session messages (for fallback new-session mode).
     */
    private String buildForkPrompt(List<Map<String, Object>> messages, Integer turnIndex) {
        if (messages == null || messages.isEmpty()) {
            return "Please continue the previous task.";
        }

        int userTurn = 0;
        int targetTurn = turnIndex != null ? turnIndex : 1;
        StringBuilder context = new StringBuilder();
        String targetUserPrompt = null;

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            String content = (String) msg.get("content");
            if (role == null || content == null || content.isBlank()) continue;

            if ("user".equals(role)) {
                userTurn++;
                if (userTurn == targetTurn) {
                    targetUserPrompt = content;
                    break;
                }
                context.append("[User Turn ").append(userTurn).append("]\n")
                        .append(content).append("\n\n");
            } else if ("assistant".equals(role) && userTurn < targetTurn) {
                String truncated = content.length() > 2000
                        ? content.substring(0, 2000) + "\n... (truncated)"
                        : content;
                context.append("[Assistant Response ").append(userTurn).append("]\n")
                        .append(truncated).append("\n\n");
            }
        }

        if (targetUserPrompt == null) {
            return "Please continue the previous task.";
        }
        if (targetTurn == 1 || context.isEmpty()) {
            return targetUserPrompt;
        }
        return "Below is the conversation history so far. "
                + "Please respond to the last user request.\n\n"
                + "--- Conversation History ---\n"
                + context
                + "--- Current Request ---\n"
                + targetUserPrompt;
    }
}
