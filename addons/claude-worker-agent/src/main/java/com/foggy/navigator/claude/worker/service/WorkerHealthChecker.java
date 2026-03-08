package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 定期心跳检查所有 Worker 状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerHealthChecker {

    private final ClaudeWorkerRepository workerRepository;
    private final ClaudeWorkerService workerService;

    @Scheduled(fixedDelay = 30000, initialDelay = 60000)
    public void checkAll() {
        List<ClaudeWorkerEntity> workers = workerRepository.findAll();
        for (ClaudeWorkerEntity worker : workers) {
            checkWorker(worker);
        }
    }

    /**
     * 检查单个 Worker
     */
    @SuppressWarnings("unchecked")
    public void checkWorker(ClaudeWorkerEntity worker) {
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            Map<String, Object> health = client.healthCheck().block(java.time.Duration.ofSeconds(5));
            if (health != null) {
                workerService.updateWorkerStatus(worker.getWorkerId(), "ONLINE", health);
            } else {
                workerService.updateWorkerStatus(worker.getWorkerId(), "OFFLINE", null);
            }
        } catch (Exception e) {
            log.debug("Worker health check failed: workerId={}, error={}", worker.getWorkerId(), e.getMessage());
            workerService.updateWorkerStatus(worker.getWorkerId(), "OFFLINE", null);
        }
    }
}
