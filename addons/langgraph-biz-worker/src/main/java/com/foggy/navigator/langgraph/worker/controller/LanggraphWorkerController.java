package com.foggy.navigator.langgraph.worker.controller;

import com.foggyframework.core.ex.RX;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphWorkerDTO;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphWorkerRepository;
import com.foggy.navigator.langgraph.worker.service.LanggraphWorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/langgraph-workers")
@RequireAuth
@RequiredArgsConstructor
public class LanggraphWorkerController {

    private final LanggraphWorkerRepository workerRepository;
    private final LanggraphWorkerService workerService;

    @GetMapping
    public RX<List<LanggraphWorkerDTO>> listWorkers() {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(workerService.listWorkers(userId).stream().map(this::toDTO).toList());
    }

    @PostMapping
    public RX<LanggraphWorkerDTO> registerWorker(@RequestBody LanggraphWorkerEntity worker) {
        worker.setUserId(UserContext.getCurrentUserId());
        worker.setTenantId(UserContext.getCurrentTenantId());
        if (worker.getWorkerId() == null || worker.getWorkerId().isBlank()) {
            worker.setWorkerId("lgw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }
        return RX.ok(toDTO(workerRepository.save(worker)));
    }

    @GetMapping("/{workerId}")
    public RX<LanggraphWorkerDTO> getWorker(@PathVariable String workerId) {
        var worker = workerService.getWorkerEntity(workerId);
        assertOwned(worker);
        return RX.ok(toDTO(worker));
    }

    @PutMapping("/{workerId}")
    public RX<LanggraphWorkerDTO> updateWorker(@PathVariable String workerId,
                                               @RequestBody LanggraphWorkerEntity form) {
        var worker = workerService.getWorkerEntity(workerId);
        assertOwned(worker);
        if (form.getName() != null && !form.getName().isBlank()) {
            worker.setName(form.getName());
        }
        if (form.getBaseUrl() != null && !form.getBaseUrl().isBlank()) {
            worker.setBaseUrl(form.getBaseUrl());
        }
        if (form.getAuthToken() != null && !form.getAuthToken().isBlank()) {
            worker.setAuthToken(form.getAuthToken());
        }
        if (form.getAuthMode() != null && !form.getAuthMode().isBlank()) {
            worker.setAuthMode(form.getAuthMode());
        }
        if (form.getProviderExt() != null) {
            worker.setProviderExt(form.getProviderExt());
        }
        return RX.ok(toDTO(workerRepository.save(worker)));
    }

    @DeleteMapping("/{workerId}")
    public RX<Void> deleteWorker(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        var worker = workerRepository.findByWorkerIdAndUserId(workerId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
        workerRepository.delete(worker);
        return RX.ok(null);
    }

    @PostMapping("/{workerId}/health-check")
    public RX<LanggraphWorkerDTO> healthCheck(@PathVariable String workerId) {
        var worker = workerService.getWorkerEntity(workerId);
        assertOwned(worker);
        var client = workerService.createClient(worker);
        try {
            client.healthCheck().block(java.time.Duration.ofSeconds(10));
            worker.setStatus("ONLINE");
        } catch (Exception e) {
            worker.setStatus("OFFLINE");
        }
        return RX.ok(toDTO(workerRepository.save(worker)));
    }

    private static void assertOwned(LanggraphWorkerEntity worker) {
        if (!Objects.equals(worker.getUserId(), UserContext.getCurrentUserId())) {
            throw RX.throwB("Worker not found");
        }
    }

    private LanggraphWorkerDTO toDTO(LanggraphWorkerEntity worker) {
        return LanggraphWorkerDTO.builder()
                .workerId(worker.getWorkerId())
                .name(worker.getName())
                .baseUrl(worker.getBaseUrl())
                .authMode(worker.getAuthMode())
                .status(worker.getStatus())
                .hostname(worker.getHostname())
                .workerVersion(worker.getWorkerVersion())
                .lastHeartbeat(worker.getLastHeartbeat())
                .createdAt(worker.getCreatedAt())
                .providerExt(worker.getProviderExt())
                .build();
    }
}
