package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/v1/codex-workers")
@RequireAuth
@Slf4j
@RequiredArgsConstructor
public class CodexWorkerController {

    private static final Set<String> SAFE_TERMINATION_ERROR_CODES = Set.of(
            "TERMINATION_MANUAL_PID_REQUIRED",
            "TERMINATION_PROCESS_IDENTITY_REQUIRED",
            "TERMINATION_WORKER_TASK_MISMATCH",
            "TERMINATION_TASK_ACCESS_DENIED",
            "TERMINATION_TASK_ALREADY_TERMINAL",
            "TERMINATION_AUDIT_UNAVAILABLE",
            "TERMINATION_OPERATION_PENDING",
            "TERMINATION_PROCESS_IDENTITY_MISMATCH",
            "REMOTE_TASK_ID_UNAVAILABLE"
    );

    private final WorkerManagementFacade workerManagementFacade;
    private final CodexWorkerClientFactory clientFactory;
    private final CodexTaskService taskService;

    @GetMapping("/{workerId}/processes")
    public RX<Map<String, Object>> listCliProcesses(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        workerManagementFacade.validateWorkerOwnership(userId, workerId);

        var codexConfig = workerManagementFacade.getCodexConfig(workerId);
        if (codexConfig == null || codexConfig.getBaseUrl() == null || codexConfig.getBaseUrl().isBlank()) {
            return RX.failA("Worker 未配置 Codex 服务");
        }

        var client = clientFactory.getOrCreate(workerId, codexConfig.getBaseUrl(), codexConfig.getAuthToken());
        try {
            return RX.ok(client.listCliProcesses().block(Duration.ofSeconds(10)));
        } catch (Exception e) {
            String code = safeWorkerErrorCode(e);
            log.warn("Failed to list Codex CLI processes for worker {}: code={}, type={}",
                    workerId, code, e.getClass().getSimpleName());
            return RX.failA("获取 Codex CLI 进程失败: " + code);
        }
    }

    @PostMapping("/{workerId}/processes/{pid}/kill")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<Map<String, Object>> killCliProcess(
            @PathVariable String workerId,
            @PathVariable int pid,
            @RequestBody(required = false) Map<String, Object> body) {
        String userId = UserContext.getCurrentUserId();
        workerManagementFacade.validateWorkerOwnership(userId, workerId);

        var codexConfig = workerManagementFacade.getCodexConfig(workerId);
        if (codexConfig == null || codexConfig.getBaseUrl() == null || codexConfig.getBaseUrl().isBlank()) {
            return RX.failA("Worker 未配置 Codex 服务");
        }

        boolean force = body != null && Boolean.TRUE.equals(body.get("force"));
        String taskId = requestTaskId(body);
        if (taskId == null) {
            return RX.failA("显式 PID 终止必须提供 taskId");
        }
        var client = clientFactory.getOrCreate(workerId, codexConfig.getBaseUrl(), codexConfig.getAuthToken());
        CodexTaskService.ManualPidKillRequest operation = null;
        try {
            Map<String, Object> snapshot = client.listCliProcesses().block(Duration.ofSeconds(5));
            String processIdentity = processIdentityForPidTask(snapshot, pid, taskId);
            if (processIdentity == null) {
                return RX.failA("无法确认 Codex CLI PID 与任务绑定，已拒绝终止操作");
            }
            // PID termination is an administrator-only, audited operation.  The
            // method-level role gate above is intentionally stronger than the
            // ordinary Worker ownership check used for diagnostic reads.
            String actorType = UserContext.isSuperAdmin() ? "SUPER_ADMIN_MANUAL" : "TENANT_ADMIN_MANUAL";
            operation = taskService.prepareManualPidKill(taskId, workerId, userId, actorType,
                    UserContext.getCurrentTenantId(), true, pid, processIdentity,
                    client.terminationSigningSecret());
            Map<String, Object> workerResult = client.killCliProcess(pid, force, operation.capability())
                    .block(Duration.ofSeconds(10));
            Map<String, Object> result = workerResult == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(workerResult);
            taskService.recordManualPidKillResult(operation, result);
            result.put("termination_operation_id", operation.operationId());
            return RX.ok(result);
        } catch (Exception e) {
            if (operation != null) {
                taskService.markManualPidKillDispatchFailure(operation, e);
            }
            String code = safeWorkerErrorCode(e);
            log.warn("Failed to kill Codex CLI process {} for worker {}: code={}, type={}",
                    pid, workerId, code, e.getClass().getSimpleName());
            return RX.failA("终止 Codex CLI 进程失败: " + code);
        }
    }

    private String requestTaskId(Map<String, Object> body) {
        if (body == null || body.get("taskId") == null) return null;
        String taskId = String.valueOf(body.get("taskId")).trim();
        return taskId.isEmpty() ? null : taskId;
    }

    /**
     * Returns the opaque, Worker-issued identity for the exact task/PID
     * snapshot.  PID alone is never sufficient because operating systems can
     * recycle it between the control-plane preflight and Worker dispatch.
     */
    static String processIdentityForPidTask(Map<String, Object> snapshot, int pid, String taskId) {
        if (snapshot == null || taskId == null) return null;
        Object processValue = snapshot.get("processes");
        if (!(processValue instanceof Iterable<?> processes)) return null;
        for (Object processValueItem : processes) {
            if (!(processValueItem instanceof Map<?, ?> rawProcess)) continue;
            Object pidValue = rawProcess.get("pid");
            Object boundTaskId = rawProcess.get("foggy_task_id");
            if (pidMatches(pidValue, pid) && taskId.equals(String.valueOf(boundTaskId))) {
                Object identity = rawProcess.get("process_identity");
                if (identity instanceof String value && isSafeProcessIdentity(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private static boolean pidMatches(Object value, int expectedPid) {
        if (value instanceof Number number) return number.intValue() == expectedPid;
        try {
            return Integer.parseInt(String.valueOf(value)) == expectedPid;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isSafeProcessIdentity(String value) {
        return value.length() <= 160
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:+-]{0,159}");
    }

    /**
     * A Worker response body may include provider diagnostics, command text,
     * or credentials.  The control-plane UI and lifecycle logs expose only a
     * stable code; full details remain inside the Worker-side protected logs.
     */
    static String safeWorkerErrorCode(Exception error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8 && visited.add(current); depth++, current = current.getCause()) {
            if (current instanceof WebClientResponseException responseException) {
                return "CODEX_WORKER_HTTP_" + responseException.getStatusCode().value();
            }
            if (current instanceof TimeoutException || isBlockingTimeout(current)) {
                return "CODEX_WORKER_TIMEOUT";
            }
            if (current instanceof WebClientRequestException) {
                return "CODEX_WORKER_CONNECTION_UNAVAILABLE";
            }
            if (SAFE_TERMINATION_ERROR_CODES.contains(current.getMessage())) {
                return current.getMessage();
            }
        }
        return "CODEX_WORKER_REQUEST_UNCONFIRMED";
    }

    private static boolean isBlockingTimeout(Throwable error) {
        return error instanceof IllegalStateException
                && error.getMessage() != null
                && error.getMessage().startsWith("Timeout on blocking read for ");
    }
}
