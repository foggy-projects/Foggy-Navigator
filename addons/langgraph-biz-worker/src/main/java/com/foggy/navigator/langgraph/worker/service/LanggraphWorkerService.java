package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphWorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LanggraphWorkerService {

    private final LanggraphWorkerRepository workerRepository;

    @Value("${navigator.langgraph.worker.connect-timeout-ms:10000}")
    private long connectTimeoutMillis = 10_000;

    @Value("${navigator.langgraph.worker.response-timeout-seconds:1800}")
    private long responseTimeoutSeconds = 1_800;

    public LanggraphWorkerEntity getWorkerEntity(String workerId) {
        return workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> new IllegalArgumentException("LangGraph worker not found: " + workerId));
    }

    public List<LanggraphWorkerEntity> listWorkers(String userId) {
        return workerRepository.findByUserIdOrderByCreatedAtDesc(userId);
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
