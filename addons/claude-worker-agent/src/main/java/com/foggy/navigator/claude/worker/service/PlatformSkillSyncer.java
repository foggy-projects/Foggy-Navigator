package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 请求在线 Worker 按其本地打包资源 reconcile 平台 Skill。
 * <p>
 * Skill 正文由 Worker 单一维护；控制面不再持有或推送重复模板。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformSkillSyncer {

    private final ClaudeWorkerRepository workerRepository;
    private final ClaudeWorkerService workerService;

    /**
     * 启动后异步推送（延迟等 health checker 先跑一轮）
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        try {
            Thread.sleep(90_000); // 等待 Worker 上线
            syncAllOnlineWorkers();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 同步所有在线 Worker
     */
    public void syncAllOnlineWorkers() {
        var workers = workerRepository.findAll().stream()
                .filter(w -> "ONLINE".equals(w.getStatus()))
                .toList();
        log.info("Reconciling platform skills on {} online workers", workers.size());
        for (var worker : workers) {
            syncWorkerSkills(worker);
        }
    }

    /**
     * 同步单个 Worker（by workerId）
     */
    public void syncWorkerSkills(String workerId) {
        var worker = workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
        syncWorkerSkills(worker);
    }

    private void syncWorkerSkills(ClaudeWorkerEntity worker) {
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            client.deploySkills(Map.of())
                    .block(Duration.ofSeconds(10));
            log.info("Reconciled platform skills on worker: {} ({})", worker.getName(), worker.getWorkerId());
        } catch (Exception e) {
            log.warn("Failed to sync skills to worker {} ({}): {}",
                    worker.getName(), worker.getWorkerId(), e.getMessage());
        }
    }
}
