package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 任务状态调解器（Kubernetes Reconciliation Loop 模式）
 *
 * 每 60 秒将数据库任务状态与 Worker CLI 进程（真实状态）对齐：
 *
 * <pre>
 * ┌──────────────────────────┬──────────────┬────────────────────────────────────────────────┐
 * │ DB 状态                   │ CLI 存活      │ 处理                                           │
 * ├──────────────────────────┼──────────────┼────────────────────────────────────────────────┤
 * │ RUNNING / AWAITING       │ ✓ 存活        │ touchAlive()，重置超时基准                      │
 * │ RUNNING / AWAITING       │ ✗ 已死亡      │ 连续 3 次未见后 reconcilerFailTask()            │
 * │ COMPLETED / FAILED / ... │ ✓ 存活        │ 孤儿！记录首次发现时间，仅检测+日志（用户通过 UI 手动管理） │
 * │ COMPLETED / FAILED / ... │ ✗ 已死亡      │ 清理孤儿记录（如有）                            │
 * └──────────────────────────┴──────────────┴────────────────────────────────────────────────┘
 * </pre>
 *
 * 孤儿进程：CLI 进程仍然存活，但 DB 任务已终结（COMPLETED/FAILED）。
 * 通常由 Worker 崩溃重启或 Java 侧强制超时导致 CLI 成为游离进程。
 * Reconciler 通过 {@link #getOrphanFirstSeen} 将状态暴露给 Controller/UI，
 * 由用户通过进程管理界面手动决定是否终止（不自动杀死，以支持 Java 崩溃重启后的任务恢复）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskStateReconciler {

    // -------------------------------------------------------------------------
    // 常量配置
    // -------------------------------------------------------------------------

    /**
     * CLI 进程丢失连续阈值：连续多少次 reconcile 未见 CLI 进程后才强制失败。
     * 容忍 Worker API 瞬间不可用或 Worker 热重启，默认 3 次 × 60s ≈ 3 分钟。
     */
    private static final int DEAD_CLI_MISS_THRESHOLD = 3;

    /**
     * 新建任务保护期（分钟）：任务刚创建时 CLI 进程可能尚未启动，忽略孤儿/丢失检测。
     */
    private static final int NEW_TASK_GRACE_MINUTES = 2;

    // -------------------------------------------------------------------------
    // 依赖
    // -------------------------------------------------------------------------

    private final ClaudeTaskRepository taskRepository;
    private final ClaudeWorkerRepository workerRepository;
    private final ClaudeWorkerService workerService;
    private final ClaudeTaskService taskService;

    // -------------------------------------------------------------------------
    // 内存状态表
    // -------------------------------------------------------------------------

    /**
     * 孤儿进程首次发现时间表。
     * key: "workerId:pid"，value: 首次发现时间（Instant）
     */
    private final ConcurrentHashMap<String, Instant> orphanFirstSeen = new ConcurrentHashMap<>();

    /**
     * CLI 进程连续丢失计数表（DB 活跃 + CLI 已死亡）。
     * key: taskId，value: 连续丢失次数
     */
    private final ConcurrentHashMap<String, Integer> cliDeadMissCount = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // 主调度方法
    // -------------------------------------------------------------------------

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void reconcileAll() {
        List<ClaudeWorkerEntity> workers = workerRepository.findAll();
        for (ClaudeWorkerEntity worker : workers) {
            try {
                reconcileWorker(worker);
            } catch (Exception e) {
                log.warn("Reconciler: failed for worker={}: {}", worker.getWorkerId(), e.getMessage());
            }
        }
        cleanStaleMissEntries();
    }

    // -------------------------------------------------------------------------
    // 单 Worker 调解
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void reconcileWorker(ClaudeWorkerEntity worker) {
        String workerId = worker.getWorkerId();

        // 1. 调用 Worker API 获取所有 CLI 进程（真实状态）
        Map<String, Object> processResponse;
        try {
            processResponse = workerService.createClient(worker)
                    .listCliProcesses()
                    .block(Duration.ofSeconds(8));
        } catch (Exception e) {
            log.debug("Reconciler: cannot reach worker={}, skipping: {}", workerId, e.getMessage());
            return; // Worker 离线由 WorkerHealthChecker 处理
        }

        if (processResponse == null) {
            log.debug("Reconciler: null response from worker={}", workerId);
            return;
        }

        // 2. 解析进程列表，构建 foggyTaskId → pid 映射
        List<Map<String, Object>> processList =
                (List<Map<String, Object>>) processResponse.getOrDefault("processes", List.of());

        Map<String, Integer> foggyTaskIdToPid = new HashMap<>();
        Set<String> allAlivePidKeys = new HashSet<>();

        for (Map<String, Object> proc : processList) {
            Object pidObj = proc.get("pid");
            if (pidObj == null) continue;
            int pid = ((Number) pidObj).intValue();
            String pidKey = workerId + ":" + pid;
            allAlivePidKeys.add(pidKey);

            Object fti = proc.get("foggy_task_id");
            if (fti instanceof String foggyTaskId && !foggyTaskId.isBlank()) {
                foggyTaskIdToPid.put(foggyTaskId, pid);
            }
        }

        // 3. 从 DB 获取该 Worker 的所有活跃任务
        List<String> activeStatuses = List.of("RUNNING", "AWAITING_PERMISSION");
        List<ClaudeTaskEntity> activeTasks =
                taskRepository.findByWorkerIdAndStatusIn(workerId, activeStatuses);
        Set<String> activeTaskIds = activeTasks.stream()
                .map(ClaudeTaskEntity::getTaskId)
                .collect(Collectors.toSet());

        // 4. 四象限处理：DB 活跃任务 vs CLI 进程
        for (ClaudeTaskEntity task : activeTasks) {
            String taskId = task.getTaskId();
            Integer pid = foggyTaskIdToPid.get(taskId);

            if (pid != null) {
                // 象限 1: DB 活跃 + CLI 存活 → 更新心跳时间，清除丢失计数
                taskService.touchAlive(taskId);
                cliDeadMissCount.remove(taskId);
                // 同时清除该 pid 的孤儿记录（正常任务不是孤儿）
                orphanFirstSeen.remove(workerId + ":" + pid);
                log.debug("Reconciler: task={} alive (pid={}), touched lastAliveAt", taskId, pid);

            } else {
                // 象限 2: DB 活跃 + CLI 已死亡 → 宽限计数
                // 跳过刚创建的任务，避免 CLI 启动竞争
                if (task.getCreatedAt().isAfter(LocalDateTime.now().minusMinutes(NEW_TASK_GRACE_MINUTES))) {
                    log.debug("Reconciler: task={} has no CLI but was just created (grace), skipping", taskId);
                    continue;
                }

                int misses = cliDeadMissCount.merge(taskId, 1, Integer::sum);
                log.warn("Reconciler: task={} status={} but no live CLI process (miss={}/{})",
                        taskId, task.getStatus(), misses, DEAD_CLI_MISS_THRESHOLD);

                if (misses >= DEAD_CLI_MISS_THRESHOLD) {
                    log.warn("Reconciler: forcing task={} to FAILED (CLI dead {} consecutive checks)",
                            taskId, misses);
                    cliDeadMissCount.remove(taskId);
                    taskService.reconcilerFailTask(taskId,
                            "CLI process died unexpectedly (detected by reconciler after "
                            + misses + " consecutive checks)");
                }
            }
        }

        // 5. 象限 3 & 4: 处理孤儿进程（CLI 存活但 DB 任务已终结）
        for (Map<String, Object> proc : processList) {
            Object pidObj = proc.get("pid");
            if (pidObj == null) continue;
            int pid = ((Number) pidObj).intValue();
            String pidKey = workerId + ":" + pid;

            Object fti = proc.get("foggy_task_id");
            String foggyTaskId = (fti instanceof String s && !s.isBlank()) ? s : null;

            if (foggyTaskId == null) {
                // 无 foggy_task_id（env 读取失败 / 进程刚启动），保守处理，不追踪孤儿
                log.debug("Reconciler: pid={} on worker={} has no foggy_task_id, skipping orphan check",
                        pid, workerId);
                continue;
            }

            if (activeTaskIds.contains(foggyTaskId)) {
                // 非孤儿（已在上方象限 1/2 处理过），无需处理
                continue;
            }

            // foggy_task_id 存在且 DB 任务已结束 → 孤儿进程
            handleOrphan(workerId, pid, pidKey);
        }

        // 6. 清除已消失进程的孤儿记录（进程自然退出或被杀）
        orphanFirstSeen.keySet().removeIf(key -> {
            if (!key.startsWith(workerId + ":")) return false;
            return !allAlivePidKeys.contains(key);
        });
    }

    // -------------------------------------------------------------------------
    // 孤儿处理
    // -------------------------------------------------------------------------

    private void handleOrphan(String workerId, int pid, String pidKey) {
        Instant firstSeen = orphanFirstSeen.computeIfAbsent(pidKey, k -> {
            log.warn("Reconciler: orphan CLI process detected! worker={} pid={} — manage via UI process list",
                    workerId, pid);
            return Instant.now();
        });

        long ageMinutes = Duration.between(firstSeen, Instant.now()).toMinutes();
        log.debug("Reconciler: orphan worker={} pid={} age={}min (no auto-kill, user-managed)",
                workerId, pid, ageMinutes);
    }

    // -------------------------------------------------------------------------
    // 清理
    // -------------------------------------------------------------------------

    private void cleanStaleMissEntries() {
        // 删除 DB 中任务已不再活跃的丢失计数条目
        cliDeadMissCount.keySet().removeIf(taskId -> {
            Optional<ClaudeTaskEntity> opt = taskRepository.findByTaskId(taskId);
            if (opt.isEmpty()) return true;
            String status = opt.get().getStatus();
            return !"RUNNING".equals(status) && !"AWAITING_PERMISSION".equals(status);
        });
    }

    // -------------------------------------------------------------------------
    // Controller 查询接口（UI 孤儿倒计时展示）
    // -------------------------------------------------------------------------

    /**
     * 获取孤儿进程的首次发现时间（ISO-8601 字符串，供 Controller 注入到进程列表）。
     *
     * @return null 表示该进程不是孤儿（或 Reconciler 尚未标记）
     */
    public Instant getOrphanFirstSeen(String workerId, int pid) {
        return orphanFirstSeen.get(workerId + ":" + pid);
    }

}
