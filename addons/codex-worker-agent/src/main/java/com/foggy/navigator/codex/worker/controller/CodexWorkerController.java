package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/codex-workers")
@RequireAuth
@Slf4j
@RequiredArgsConstructor
public class CodexWorkerController {

    private final WorkerManagementFacade workerManagementFacade;
    private final CodexWorkerClientFactory clientFactory;

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
            log.warn("Failed to list Codex CLI processes for worker {}: {}", workerId, e.getMessage());
            return RX.failA("获取 Codex CLI 进程失败: " + e.getMessage());
        }
    }

    @PostMapping("/{workerId}/processes/{pid}/kill")
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
        var client = clientFactory.getOrCreate(workerId, codexConfig.getBaseUrl(), codexConfig.getAuthToken());
        try {
            return RX.ok(client.killCliProcess(pid, force).block(Duration.ofSeconds(10)));
        } catch (Exception e) {
            log.warn("Failed to kill Codex CLI process {} for worker {}: {}", pid, workerId, e.getMessage());
            return RX.failA("终止 Codex CLI 进程失败: " + e.getMessage());
        }
    }
}
