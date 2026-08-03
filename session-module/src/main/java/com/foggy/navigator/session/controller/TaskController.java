package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.NativeSubtaskSnapshotResponseDTO;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.model.form.TaskCancelForm;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.session.service.TaskDispatchRequest;
import com.foggy.navigator.session.service.NativeSubtaskQueryService;
import com.foggy.navigator.session.service.TrustedNavigatorTaskTerminationCommandAdapter;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 统一任务 API —— 屏蔽 Claude / Codex / 未来 Agent 差异。
 * <p>
 * 内部 UI 的任务创建、查询和生命周期操作统一使用这组端点。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
@RequireAuth
@RequiredArgsConstructor
public class TaskController {

    private final TaskDispatchFacade taskDispatchFacade;
    private final AgentSubmitPipeline agentSubmitPipeline;
    private final NativeSubtaskQueryService nativeSubtaskQueryService;
    private final TrustedNavigatorTaskTerminationCommandAdapter taskTerminationCommandAdapter;

    /**
     * 创建任务（统一入口）
     */
    @PostMapping
    public RX<DispatchTaskDTO> createTask(
            @RequestBody TaskDispatchRequest request,
            @RequestHeader(value = "X-Navigator-Client-Request-Id", required = false)
            String clientRequestId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();

        AgentResolveContext context = AgentResolveContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .sessionId(request.getSessionId())
                .modelConfigId(request.getModelConfigId())
                .requestSource("UI")
                .build();

        AgentTaskSubmitResult submitResult = agentSubmitPipeline.submit(
                toSubmitRequest(request, context, clientRequestId));
        DispatchTaskDTO result = submitResult.getDispatchTask();
        if (result == null) {
            throw new IllegalStateException("Agent submit pipeline did not return dispatch task");
        }
        return RX.ok(result);
    }

    private AgentTaskSubmitRequest toSubmitRequest(
            TaskDispatchRequest request,
            AgentResolveContext context,
            String clientRequestId) {
        return AgentTaskSubmitRequest.builder()
                .agentId(request.getAgentId())
                .providerType(request.getProviderType())
                .resolveContext(context)
                .sessionId(request.getSessionId())
                .workerId(request.getWorkerId())
                .prompt(request.getPrompt())
                .cwd(request.getCwd())
                .directoryId(request.getDirectoryId())
                .model(request.getModel())
                .modelConfigId(request.getModelConfigId())
                .maxTurns(request.getMaxTurns())
                .permissionMode(request.getPermissionMode())
                .images(request.getImages())
                .attachments(request.getAttachments())
                .agentTeamsConfigId(request.getAgentTeamsConfigId())
                .agentTeamsJson(request.getAgentTeamsJson())
                .contextId(request.getContextId())
                .context(request.getContext())
                .metadata(request.getMetadata())
                .contextAlias(request.getContextAlias())
                .clientRequestId(clientRequestId)
                .build();
    }

    /**
     * 查询单个任务
     */
    @GetMapping("/{taskId}")
    public RX<DispatchTaskDTO> getTask(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .requestSource("UI")
                .build();

        return taskDispatchFacade.getTask(taskId, context)
                .map(RX::ok)
                .orElseGet(() -> RX.failA("Task not found: " + taskId));
    }

    /**
     * Returns the latest provider-native subtask states for a task owned by the current user.
     * Native subtasks are execution details and are not independent Navigator tasks.
     */
    @GetMapping("/{taskId}/native-subtasks")
    public RX<NativeSubtaskSnapshotResponseDTO> getNativeSubtasks(@PathVariable String taskId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .requestSource("UI")
                .build();

        return taskDispatchFacade.getTask(taskId, context)
                .map(nativeSubtaskQueryService::getSnapshot)
                .map(RX::ok)
                .orElseGet(() -> RX.failA("Task not found: " + taskId));
    }

    /**
     * 按会话查询任务列表
     */
    @GetMapping
    public RX<List<DispatchTaskDTO>> listTasks(@RequestParam(required = false) String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return RX.ok(taskDispatchFacade.listTasksBySession(sessionId, currentUiContext()));
        }
        // 无 sessionId 时返回当前用户的活跃任务
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskDispatchFacade.listActiveTasks(userId));
    }

    /**
     * 取消任务
     */
    private static final Pattern SAFE_TERMINATION_ERROR_CODE =
            Pattern.compile("TERMINATION_[A-Z0-9_]{1,128}");

    @PostMapping("/{taskId}/cancel")
    public RX<String> cancelTask(
            @PathVariable String taskId,
            @RequestBody(required = false) TaskCancelForm form,
            @RequestHeader(value = "X-Navigator-Client-Request-Id", required = false)
            String clientRequestId) {
        boolean force = form != null && form.isForceRequested();
        try {
            TrustedNavigatorTaskTerminationCommandAdapter.TerminationResult result =
                    taskTerminationCommandAdapter.terminateUiTask(
                            taskId, force, clientRequestId);
            if (result.terminalStatus() != null) {
                return RX.ok("Task already in terminal state: "
                        + result.terminalStatus());
            }
            return RX.ok(force ? "Force cancellation completed" : "Cancellation request accepted");
        } catch (UnsupportedOperationException e) {
            log.warn("cancelTask: unsupported request for task {}", taskId);
            return RX.failA("TERMINATION_REQUEST_NOT_SUPPORTED");
        } catch (IllegalArgumentException e) {
            log.warn("cancelTask: invalid request for task {}", taskId);
            String message = e.getMessage();
            if (("Task not found: " + taskId).equals(message)
                    || "clientRequestId must be a canonical UUID".equals(message)) {
                return RX.failA(message);
            }
            return RX.failA("TERMINATION_REQUEST_NOT_SUPPORTED");
        } catch (IllegalStateException e) {
            String safeCode = safeTerminationErrorCode(e.getMessage());
            log.warn("cancelTask: termination request failed for task {}: code={}", taskId, safeCode);
            return RX.failB(safeCode);
        } catch (org.springframework.dao.PessimisticLockingFailureException e) {
            log.warn("cancelTask: pre-effect concurrent update for task {}", taskId);
            return RX.failB("Failed to cancel task due to concurrent update, please retry");
        }
    }

    private String safeTerminationErrorCode(String message) {
        if (message != null && SAFE_TERMINATION_ERROR_CODE.matcher(message).matches()) {
            return message;
        }
        return "TERMINATION_REQUEST_FAILED";
    }

    /**
     * 回复权限请求 / 用户问题
     * <p>
     * 不支持此操作的 Agent 会返回 UnsupportedOperationException → 400
     */
    @PostMapping("/{taskId}/respond")
    public RX<String> respondToTask(@PathVariable String taskId,
                                     @RequestBody Map<String, Object> body) {
        try {
            taskDispatchFacade.respondToTask(taskId, currentUiContext(), body);
            return RX.ok("Response sent");
        } catch (UnsupportedOperationException e) {
            return RX.failA(e.getMessage());
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        } catch (IllegalStateException e) {
            return RX.failB(e.getMessage());
        }
    }

    /**
     * 重连任务 SSE 流
     */
    @PostMapping("/{taskId}/reconnect")
    public RX<String> reconnectTask(@PathVariable String taskId) {
        try {
            taskDispatchFacade.reconnectTask(taskId, currentUiContext());
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
        try {
            Object result = taskDispatchFacade.resyncTask(taskId, currentUiContext());
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
        try {
            Object result = taskDispatchFacade.rewindTask(taskId, currentUiContext(), body);
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
                .modelConfigId(request.getModelConfigId())
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
        try {
            taskDispatchFacade.deleteTask(taskId, currentUiContext());
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
        try {
            Object result = taskDispatchFacade.scanCheckpoints(taskId, currentUiContext());
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
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "false") boolean compact) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskDispatchFacade.listTasksPaged(userId, page, size, state, compact));
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

    private AgentResolveContext currentUiContext() {
        return AgentResolveContext.builder()
                .userId(UserContext.getCurrentUserId())
                .tenantId(UserContext.getCurrentTenantId())
                .requestSource("UI")
                .build();
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

    // ── Worker Session 统一查询端点 ──

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
