package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphWorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LanggraphWorkerService {

    private final LanggraphWorkerRepository workerRepository;

    @Value("${navigator.langgraph.worker.connect-timeout-ms:10000}")
    private long connectTimeoutMillis = 10_000;

    @Value("${navigator.langgraph.worker.response-timeout-seconds:1800}")
    private long responseTimeoutSeconds = 1_800;

    @Value("${navigator.langgraph.worker.default-worker-id:}")
    private String defaultWorkerId;

    public LanggraphWorkerEntity getWorkerEntity(String workerId) {
        return workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> new IllegalArgumentException("LangGraph worker not found: " + workerId));
    }

    public List<LanggraphWorkerEntity> listWorkers(String userId) {
        return workerRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public String resolveTaskWorkerId(String preferredWorkerId) {
        if (StringUtils.hasText(preferredWorkerId)) {
            String workerId = preferredWorkerId.trim();
            Optional<LanggraphWorkerEntity> worker = workerRepository.findByWorkerId(workerId);
            if (worker.isPresent()) {
                return worker.get().getWorkerId();
            }
            log.warn("Configured LangGraph workerId {} is not registered; falling back to default BizWorker", workerId);
        }
        return resolveDefaultWorker().getWorkerId();
    }

    public LanggraphWorkerEntity resolveDefaultWorker() {
        if (StringUtils.hasText(defaultWorkerId)) {
            String workerId = defaultWorkerId.trim();
            return workerRepository.findByWorkerId(workerId)
                    .orElseThrow(() -> new IllegalStateException("Configured default LangGraph worker not found: " + workerId));
        }

        List<LanggraphWorkerEntity> workers = workerRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
        if (workers.isEmpty()) {
            throw new IllegalStateException("No LangGraph BizWorker is registered; register one worker or set navigator.langgraph.worker.default-worker-id");
        }
        if (workers.size() == 1) {
            return workers.get(0);
        }

        List<LanggraphWorkerEntity> onlineWorkers = workers.stream()
                .filter(worker -> "ONLINE".equals(worker.getStatus()))
                .toList();
        if (onlineWorkers.size() == 1) {
            return onlineWorkers.get(0);
        }
        throw new IllegalStateException("Multiple LangGraph BizWorkers are registered; set navigator.langgraph.worker.default-worker-id");
    }

    public LanggraphWorkerClient createClient(LanggraphWorkerEntity worker) {
        return new LanggraphWorkerClient(
                worker.getWorkerId(),
                worker.getBaseUrl(),
                worker.getAuthToken(),
                Duration.ofMillis(Math.max(1, connectTimeoutMillis)),
                Duration.ofSeconds(Math.max(1, responseTimeoutSeconds))
        );
    }
}
