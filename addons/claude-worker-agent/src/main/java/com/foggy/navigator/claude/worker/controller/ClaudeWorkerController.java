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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
                logProcessListDiagnostics(workerId, result);
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
            try {
                Map<String, Object> snapshot = client.listCliProcesses().block(Duration.ofSeconds(5));
                if (snapshot != null) {
                    enrichWithOrphanInfo(workerId, snapshot);
                    logKillRequestDiagnostics(workerId, pid, force, snapshot);
                }
            } catch (Exception snapshotEx) {
                log.warn("Failed to capture CLI process snapshot before kill for worker {} pid {}: {}",
                        workerId, pid, snapshotEx.getMessage());
            }
            Map<String, Object> result = client.killCliProcess(pid, force)
                    .block(Duration.ofSeconds(10));
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to kill CLI process {} for worker {}: {}", pid, workerId, e.getMessage());
            return RX.failA("终止 CLI 进程失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractProcesses(Map<String, Object> result) {
        Object procObj = result.get("processes");
        if (!(procObj instanceof List<?> rawList)) return List.of();
        List<Map<String, Object>> processes = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> rawProc) {
                processes.add((Map<String, Object>) rawProc);
            }
        }
        return processes;
    }

    private Map<String, Object> processBrief(Map<String, Object> proc) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("pid", proc.get("pid"));
        brief.put("claudeSessionId", proc.get("claude_session_id"));
        brief.put("foggyTaskId", proc.get("foggy_task_id"));
        brief.put("foggySessionId", proc.get("foggy_session_id"));
        brief.put("model", proc.get("model"));
        brief.put("isOrphan", proc.get("is_orphan"));
        brief.put("orphanFirstSeenAt", proc.get("orphan_first_seen_at"));
        brief.put("startedAt", proc.get("started_at"));
        Object command = proc.get("command");
        brief.put("command", command == null ? null : String.valueOf(command).substring(0, Math.min(String.valueOf(command).length(), 160)));
        return brief;
    }

    private String duplicateIdentityKey(Map<String, Object> proc) {
        String foggyTaskId = stringValue(proc.get("foggy_task_id"));
        if (foggyTaskId != null && !foggyTaskId.isBlank()) {
            return "foggy_task_id=" + foggyTaskId;
        }
        String claudeSessionId = stringValue(proc.get("claude_session_id"));
        if (claudeSessionId != null && !claudeSessionId.isBlank()) {
            return "claude_session_id=" + claudeSessionId;
        }
        return null;
    }

    private void logProcessListDiagnostics(String workerId, Map<String, Object> result) {
        List<Map<String, Object>> processes = extractProcesses(result);
        Object activeTaskCount = result.get("active_task_count");
        log.info("Java CLI process snapshot: workerId={}, total={}, activeTaskCount={}, processes={}",
                workerId, processes.size(), activeTaskCount,
                processes.stream().map(this::processBrief).toList());

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> proc : processes) {
            String key = duplicateIdentityKey(proc);
            if (key == null) continue;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(proc);
        }

        grouped.forEach((identity, groupedProcs) -> {
            if (groupedProcs.size() <= 1) return;
            log.warn("Java CLI process duplicate identity detected: workerId={}, identity={}, count={}, pids={}, processes={}",
                    workerId,
                    identity,
                    groupedProcs.size(),
                    groupedProcs.stream().map(proc -> proc.get("pid")).toList(),
                    groupedProcs.stream().map(this::processBrief).toList());
        });
    }

    private void logKillRequestDiagnostics(String workerId, int pid, boolean force, Map<String, Object> snapshot) {
        List<Map<String, Object>> processes = extractProcesses(snapshot);
        Map<String, Object> target = processes.stream()
                .filter(proc -> Objects.equals(proc.get("pid"), pid))
                .findFirst()
                .orElse(null);
        if (target == null) {
            log.warn("Java CLI kill requested: workerId={}, pid={}, force={}, targetMissing=true, currentPids={}",
                    workerId, pid, force, processes.stream().map(proc -> proc.get("pid")).toList());
            return;
        }

        String targetFoggyTaskId = stringValue(target.get("foggy_task_id"));
        String targetClaudeSessionId = stringValue(target.get("claude_session_id"));
        List<Map<String, Object>> related = processes.stream()
                .filter(proc -> !Objects.equals(proc.get("pid"), pid))
                .filter(proc -> {
                    String procFoggyTaskId = stringValue(proc.get("foggy_task_id"));
                    String procClaudeSessionId = stringValue(proc.get("claude_session_id"));
                    boolean sameFoggyTask = targetFoggyTaskId != null && targetFoggyTaskId.equals(procFoggyTaskId);
                    boolean sameClaudeSession = targetClaudeSessionId != null && targetClaudeSessionId.equals(procClaudeSessionId);
                    return sameFoggyTask || sameClaudeSession;
                })
                .toList();

        log.warn("Java CLI kill requested: workerId={}, pid={}, force={}, target={}, relatedCount={}, related={}",
                workerId,
                pid,
                force,
                processBrief(target),
                related.size(),
                related.stream().map(this::processBrief).toList());
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
