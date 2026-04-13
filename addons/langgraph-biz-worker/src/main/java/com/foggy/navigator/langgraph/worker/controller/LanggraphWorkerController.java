package com.foggy.navigator.langgraph.worker.controller;

import com.foggyframework.core.ex.RX;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphWorkerRepository;
import com.foggy.navigator.langgraph.worker.service.LanggraphWorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/langgraph-workers")
@RequiredArgsConstructor
public class LanggraphWorkerController {

    private final LanggraphWorkerRepository workerRepository;
    private final LanggraphWorkerService workerService;

    @GetMapping
    public RX<List<LanggraphWorkerEntity>> listWorkers(@RequestParam String userId) {
        return RX.ok(workerService.listWorkers(userId));
    }

    @PostMapping
    public RX<LanggraphWorkerEntity> registerWorker(@RequestBody LanggraphWorkerEntity worker) {
        if (worker.getWorkerId() == null || worker.getWorkerId().isBlank()) {
            worker.setWorkerId("lgw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }
        return RX.ok(workerRepository.save(worker));
    }

    @GetMapping("/{workerId}")
    public RX<LanggraphWorkerEntity> getWorker(@PathVariable String workerId) {
        return RX.ok(workerService.getWorkerEntity(workerId));
    }

    @DeleteMapping("/{workerId}")
    public RX<Void> deleteWorker(@PathVariable String workerId,
                                 @RequestParam String userId) {
        var worker = workerRepository.findByWorkerIdAndUserId(workerId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
        workerRepository.delete(worker);
        return RX.ok(null);
    }

    @PostMapping("/{workerId}/health-check")
    public RX<Object> healthCheck(@PathVariable String workerId) {
        var worker = workerService.getWorkerEntity(workerId);
        var client = workerService.createClient(worker);
        var result = client.healthCheck().block(java.time.Duration.ofSeconds(10));
        return RX.ok(result);
    }
}
