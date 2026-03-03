package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.model.dto.WorkerDTO;
import com.foggy.navigator.claude.worker.model.form.RegisterWorkerForm;
import com.foggy.navigator.claude.worker.model.form.UpdateWorkerForm;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.PlatformSkillSyncer;
import com.foggy.navigator.claude.worker.service.TaskStateReconciler;
import com.foggy.navigator.claude.worker.service.WorkerHealthChecker;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Worker 管理 API
 */
@RestController
@RequestMapping("/api/v1/claude-workers")
@RequireAuth
@Slf4j
@RequiredArgsConstructor
public class ClaudeWorkerController {

    private final ClaudeWorkerService workerService;
    private final WorkerHealthChecker healthChecker;
    private final TaskStateReconciler reconciler;
    private final PlatformSkillSyncer platformSkillSyncer;

    @GetMapping
    public RX<List<WorkerDTO>> listWorkers() {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(workerService.listWorkers(userId));
    }

    @GetMapping("/{workerId}")
    public RX<WorkerDTO> getWorker(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(workerService.getWorker(userId, workerId));
    }

    @PostMapping
    public RX<WorkerDTO> registerWorker(@RequestBody RegisterWorkerForm form) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        WorkerDTO dto = workerService.registerWorker(userId, tenantId, form);

        // 注册后立即进行一次健康检查
        try {
            healthChecker.checkWorker(workerService.getWorkerEntity(dto.getWorkerId()));
        } catch (Exception e) {
            log.warn("Initial health check failed for worker {}: {}", dto.getWorkerId(), e.getMessage());
        }

        // 重新获取以包含健康检查结果
        return RX.ok(workerService.getWorker(userId, dto.getWorkerId()));
    }

    @PutMapping("/{workerId}")
    public RX<WorkerDTO> updateWorker(@PathVariable String workerId, @RequestBody UpdateWorkerForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(workerService.updateWorker(userId, workerId, form));
    }

    @DeleteMapping("/{workerId}")
    public RX<Void> deleteWorker(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        workerService.deleteWorker(userId, workerId);
        return RX.ok(null);
    }

    @PostMapping("/{workerId}/health-check")
    public RX<WorkerDTO> triggerHealthCheck(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        var entity = workerService.getWorkerEntity(workerId);
        if (!entity.getUserId().equals(userId)) {
            throw RX.throwB("Worker not found");
        }
        healthChecker.checkWorker(entity);
        return RX.ok(workerService.getWorker(userId, workerId));
    }

    @GetMapping("/{workerId}/code-server-password")
    public RX<Map<String, String>> getCodeServerPassword(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        var entity = workerService.getWorkerEntity(workerId);
        if (!entity.getUserId().equals(userId)) {
            throw RX.throwB("Worker not found");
        }
        String pwd = workerService.getDecryptedCodeServerPassword(entity);
        if (pwd == null) {
            return RX.failA("未配置 Code Server 密码");
        }
        return RX.ok(Map.of("password", pwd));
    }

    @PostMapping("/{workerId}/sync-skills")
    public RX<Map<String, Object>> syncSkills(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        var entity = workerService.getWorkerEntity(workerId);
        if (!entity.getUserId().equals(userId)) {
            throw RX.throwB("Worker not found");
        }
        try {
            platformSkillSyncer.syncWorkerSkills(workerId);
            return RX.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            log.warn("Failed to sync skills for worker {}: {}", workerId, e.getMessage());
            return RX.failA("同步 Skills 失败: " + e.getMessage());
        }
    }

    // ===== CLI Process Management =====

    @SuppressWarnings("unchecked")
    @GetMapping("/{workerId}/processes")
    public RX<Map<String, Object>> listCliProcesses(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        var entity = workerService.getWorkerEntity(workerId);
        if (!entity.getUserId().equals(userId)) {
            throw RX.throwB("Worker not found");
        }
        var client = workerService.createClient(entity);
        try {
            Map<String, Object> result = client.listCliProcesses()
                    .block(Duration.ofSeconds(10));
            if (result != null) {
                enrichWithOrphanInfo(workerId, result);
            }
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to list CLI processes for worker {}: {}", workerId, e.getMessage());
            return RX.failA("获取 CLI 进程列表失败: " + e.getMessage());
        }
    }

    /**
     * 将 Reconciler 掌握的孤儿信息（首次发现时间）注入到进程列表中。
     * 前端据此展示孤儿标识和手动杀死按钮。
     */
    @SuppressWarnings("unchecked")
    private void enrichWithOrphanInfo(String workerId, Map<String, Object> result) {
        Object procObj = result.get("processes");
        if (!(procObj instanceof List<?> rawList)) return;

        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> rawProc)) continue;
            Map<String, Object> proc = (Map<String, Object>) rawProc;

            Object pidObj = proc.get("pid");
            if (pidObj == null) continue;
            int pid = ((Number) pidObj).intValue();

            Instant firstSeen = reconciler.getOrphanFirstSeen(workerId, pid);
            if (firstSeen != null) {
                proc.put("orphan_first_seen_at", firstSeen.toString());
                // Override is_orphan with Reconciler's authoritative verdict
                proc.put("is_orphan", true);
            }
        }
    }

    @PostMapping("/{workerId}/processes/{pid}/kill")
    public RX<Map<String, Object>> killCliProcess(
            @PathVariable String workerId,
            @PathVariable int pid,
            @RequestBody(required = false) Map<String, Object> body) {
        String userId = UserContext.getCurrentUserId();
        var entity = workerService.getWorkerEntity(workerId);
        if (!entity.getUserId().equals(userId)) {
            throw RX.throwB("Worker not found");
        }
        boolean force = false;
        if (body != null && body.containsKey("force")) {
            force = Boolean.TRUE.equals(body.get("force"));
        }
        var client = workerService.createClient(entity);
        try {
            Map<String, Object> result = client.killCliProcess(pid, force)
                    .block(Duration.ofSeconds(10));
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to kill CLI process {} for worker {}: {}", pid, workerId, e.getMessage());
            return RX.failA("终止 CLI 进程失败: " + e.getMessage());
        }
    }
}
