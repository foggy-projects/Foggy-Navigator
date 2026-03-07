package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.model.dto.CodexWorkerDTO;
import com.foggy.navigator.codex.worker.model.form.RegisterCodexWorkerForm;
import com.foggy.navigator.codex.worker.service.CodexWorkerService;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Codex Worker CRUD 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/codex-workers")
@RequiredArgsConstructor
public class CodexWorkerController {

    private final CodexWorkerService workerService;

    /**
     * 列出用户的所有 Codex Worker
     */
    @GetMapping
    public RX<List<CodexWorkerDTO>> listWorkers() {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(workerService.listWorkers(userId));
    }

    /**
     * 获取 Worker 详情
     */
    @GetMapping("/{workerId}")
    public RX<CodexWorkerDTO> getWorker(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(workerService.getWorker(userId, workerId));
    }

    /**
     * 注册新 Codex Worker
     */
    @PostMapping
    public RX<CodexWorkerDTO> registerWorker(@RequestBody RegisterCodexWorkerForm form) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        return RX.ok(workerService.registerWorker(userId, tenantId, form));
    }

    /**
     * 更新 Worker 信息
     */
    @PutMapping("/{workerId}")
    public RX<CodexWorkerDTO> updateWorker(@PathVariable String workerId,
                                            @RequestBody RegisterCodexWorkerForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(workerService.updateWorker(userId, workerId, form));
    }

    /**
     * 删除 Worker
     */
    @DeleteMapping("/{workerId}")
    public RX<Void> deleteWorker(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        workerService.deleteWorker(userId, workerId);
        return RX.ok(null);
    }

    /**
     * 触发健康检查
     */
    @PostMapping("/{workerId}/health-check")
    public RX<CodexWorkerDTO> triggerHealthCheck(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(workerService.triggerHealthCheck(userId, workerId));
    }
}
