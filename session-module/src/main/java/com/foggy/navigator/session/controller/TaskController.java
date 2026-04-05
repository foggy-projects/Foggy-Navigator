package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.session.service.TaskDispatchRequest;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一任务 API —— 屏蔽 Claude / Codex / 未来 Agent 差异。
 * <p>
 * 所有前端任务操作逐步迁移到这组端点，旧 /api/v1/claude-tasks 保持兼容。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
@RequireAuth
@RequiredArgsConstructor
public class TaskController {

    private final TaskDispatchFacade taskDispatchFacade;

    /**
     * 创建任务（统一入口）
     */
    @PostMapping
    public RX<DispatchTaskDTO> createTask(@RequestBody TaskDispatchRequest request) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();

        AgentResolveContext context = AgentResolveContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .sessionId(request.getSessionId())
                .requestSource("UI")
                .build();

        DispatchTaskDTO result = taskDispatchFacade.createTask(request, context);
        return RX.ok(result);
    }

    /**
     * 查询单个任务
     */
    @GetMapping("/{taskId}")
    public RX<DispatchTaskDTO> getTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId(userId)
                .requestSource("UI")
                .build();

        return taskDispatchFacade.getTask(taskId, context)
                .map(RX::ok)
                .orElseGet(() -> RX.failA("Task not found: " + taskId));
    }

    /**
     * 按会话查询任务列表
     */
    @GetMapping
    public RX<List<DispatchTaskDTO>> listTasks(@RequestParam(required = false) String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return RX.ok(taskDispatchFacade.listTasksBySession(sessionId));
        }
        // 无 sessionId 时返回当前用户的活跃任务
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskDispatchFacade.listActiveTasks(userId));
    }

    /**
     * 取消任务
     */
    private static final Set<String> TERMINAL_STATES = Set.of("COMPLETED", "FAILED", "ABORTED");

    @PostMapping("/{taskId}/cancel")
    public RX<String> cancelTask(@PathVariable String taskId,
                                  @RequestBody(required = false) Map<String, String> body) {
        String userId = UserContext.getCurrentUserId();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId(userId)
                .requestSource("UI")
                .build();

        // 统一先查任务，用于 terminal-state guard 和 agentId 回填
        DispatchTaskDTO task = taskDispatchFacade.getTask(taskId, context).orElse(null);
        if (task == null) {
            return RX.failA("Task not found: " + taskId);
        }

        // Terminal-state guard：已完成/失败/中止的任务直接返回成功（幂等）
        if (task.getStatus() != null && TERMINAL_STATES.contains(task.getStatus())) {
            log.info("cancelTask: task {} already in terminal state ({}), returning no-op", taskId, task.getStatus());
            return RX.ok("Task already in terminal state: " + task.getStatus());
        }

        // 优先使用请求体传入的 agentId，否则从任务记录回填
        String agentId = body != null ? body.get("agentId") : null;
        if (agentId == null || agentId.isBlank()) {
            agentId = task.getAgentId();
        }

        taskDispatchFacade.cancelTask(taskId, agentId, context);
        return RX.ok("Task cancelled");
    }

    /**
     * 回复权限请求 / 用户问题
     * <p>
     * 不支持此操作的 Agent 会返回 UnsupportedOperationException → 400
     */
    @PostMapping("/{taskId}/respond")
    public RX<String> respondToTask(@PathVariable String taskId,
                                     @RequestBody Map<String, Object> body) {
        String userId = UserContext.getCurrentUserId();
        try {
            taskDispatchFacade.respondToTask(taskId, userId, body);
            return RX.ok("Response sent");
        } catch (UnsupportedOperationException e) {
            return RX.failA(e.getMessage());
        }
    }

    /**
     * 重连任务 SSE 流
     */
    @PostMapping("/{taskId}/reconnect")
    public RX<String> reconnectTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        try {
            taskDispatchFacade.reconnectTask(taskId, userId);
            return RX.ok("Reconnect initiated");
        } catch (UnsupportedOperationException e) {
            return RX.failA(e.getMessage());
        }
    }

    /**
     * 重新同步任务状态
     */
    @PostMapping("/{taskId}/resync")
    public RX<?> resyncTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        try {
            Object result = taskDispatchFacade.resyncTask(taskId, userId);
            return RX.ok(result);
        } catch (UnsupportedOperationException | IllegalStateException | IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        }
    }

    /**
     * 回退到检查点
     */
    @PostMapping("/{taskId}/rewind")
    public RX<?> rewindTask(@PathVariable String taskId,
                             @RequestBody Map<String, Object> body) {
        String userId = UserContext.getCurrentUserId();
        try {
            Object result = taskDispatchFacade.rewindTask(taskId, userId, body);
            return RX.ok(result);
        } catch (UnsupportedOperationException e) {
            return RX.failA(e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return RX.failB(e.getMessage());
        }
    }

    // ── Phase 3: 统一端点扩展 ──

    /**
     * 恢复任务（续接已有会话）
     */
    @PostMapping("/resume")
    public RX<DispatchTaskDTO> resumeTask(@RequestBody TaskDispatchRequest request) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();

        AgentResolveContext context = AgentResolveContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .sessionId(request.getSessionId())
                .requestSource("UI")
                .build();

        try {
            DispatchTaskDTO result = taskDispatchFacade.resumeTask(request, context);
            return RX.ok(result);
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        } catch (IllegalStateException e) {
            return RX.failB(e.getMessage());
        }
    }

    /**
     * 删除任务
     */
    @DeleteMapping("/{taskId}")
    public RX<Map<String, Object>> deleteTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        try {
            taskDispatchFacade.deleteTask(taskId, userId);
            return RX.ok(Map.of("taskId", taskId, "deleted", true));
        } catch (UnsupportedOperationException e) {
            return RX.failA(e.getMessage());
        } catch (IllegalStateException e) {
            return RX.failB(e.getMessage());
        }
    }

    /**
     * 扫描 checkpoints
     */
    @PostMapping("/{taskId}/scan-checkpoints")
    public RX<?> scanCheckpoints(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        try {
            Object result = taskDispatchFacade.scanCheckpoints(taskId, userId);
            return RX.ok(result);
        } catch (UnsupportedOperationException e) {
            return RX.failA(e.getMessage());
        }
    }

    /**
     * 分页查询任务列表
     */
    @GetMapping("/page")
    public RX<?> listTasksPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String state) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskDispatchFacade.listTasksPaged(userId, page, size, state));
    }

    /**
     * 搜索会话
     */
    @GetMapping("/search")
    public RX<?> searchSessions(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String workerId,
            @RequestParam(required = false) String directoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskDispatchFacade.searchSessions(userId, keyword, workerId, directoryId, page, size));
    }

    /**
     * 按目录查询任务列表
     */
    @GetMapping("/directory/{directoryId}")
    public RX<List<DispatchTaskDTO>> listTasksByDirectory(@PathVariable String directoryId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskDispatchFacade.listTasksByDirectory(userId, directoryId));
    }

    /**
     * 按目录分页查询任务列表
     */
    @GetMapping("/directory/{directoryId}/page")
    public RX<?> listTasksByDirectoryPaged(
            @PathVariable String directoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String state) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskDispatchFacade.listTasksByDirectoryPaged(userId, directoryId, page, size, state));
    }

    // ── Worker Session 查询（统一端点，迁移自 ClaudeTaskController） ──

    /**
     * 列出指定 Worker 上的会话列表
     */
    @GetMapping("/workers/{workerId}/sessions")
    public RX<List<Map<String, Object>>> listWorkerSessions(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskDispatchFacade.listWorkerSessions(workerId, userId));
    }

    /**
     * 获取会话消息数量统计
     */
    @GetMapping("/workers/{workerId}/sessions/{sessionId}/message-count")
    public RX<Map<String, Object>> getWorkerSessionMessageCount(
            @PathVariable String workerId,
            @PathVariable String sessionId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskDispatchFacade.getWorkerSessionMessageCount(workerId, sessionId, userId));
    }

    /**
     * 获取会话消息（支持分页）
     */
    @GetMapping("/workers/{workerId}/sessions/{sessionId}/messages")
    public RX<List<Map<String, Object>>> getWorkerSessionMessages(
            @PathVariable String workerId,
            @PathVariable String sessionId,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskDispatchFacade.getWorkerSessionMessages(workerId, sessionId, userId, offset, limit));
    }

    /**
     * 触发 Worker 重新扫描 + 本地同步
     */
    @PostMapping("/workers/{workerId}/sessions/sync")
    public RX<Map<String, Object>> syncWorkerSessions(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        try {
            return RX.ok(taskDispatchFacade.syncWorkerSessions(workerId, userId, tenantId));
        } catch (Exception e) {
            return RX.failB(e.getMessage());
        }
    }
}
