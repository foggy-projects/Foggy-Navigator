package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.WorkingDirectoryDTO;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.model.form.CreateWorkingDirectoryForm;
import com.foggy.navigator.claude.worker.model.form.UpdateWorkingDirectoryForm;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.WorkingDirectoryService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 工作目录管理 API
 */
@RestController
@RequestMapping("/api/v1/working-directories")
@RequireAuth
@Slf4j
@RequiredArgsConstructor
public class WorkingDirectoryController {

    private final WorkingDirectoryService directoryService;
    private final ClaudeWorkerService workerService;

    @GetMapping("/worker/{workerId}")
    public RX<List<WorkingDirectoryDTO>> listByWorker(@PathVariable String workerId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(directoryService.listByWorker(userId, workerId));
    }

    @PostMapping
    public RX<WorkingDirectoryDTO> create(@RequestBody CreateWorkingDirectoryForm form) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        return RX.ok(directoryService.createDirectory(userId, tenantId, form));
    }

    @PutMapping("/{directoryId}")
    public RX<WorkingDirectoryDTO> update(
            @PathVariable String directoryId,
            @RequestBody UpdateWorkingDirectoryForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(directoryService.updateDirectory(userId, directoryId, form));
    }

    @DeleteMapping("/{directoryId}")
    public RX<Void> delete(@PathVariable String directoryId) {
        String userId = UserContext.getCurrentUserId();
        directoryService.deleteDirectory(userId, directoryId);
        return RX.ok(null);
    }

    @PostMapping("/{directoryId}/sync")
    public RX<WorkingDirectoryDTO> syncGitInfo(@PathVariable String directoryId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(directoryService.syncGitInfo(userId, directoryId));
    }

    @GetMapping("/{directoryId}/skills")
    public RX<List<Map<String, Object>>> listSkills(@PathVariable String directoryId) {
        String userId = UserContext.getCurrentUserId();
        WorkingDirectoryEntity entity = directoryService.getDirectoryEntity(userId, directoryId);
        ClaudeWorkerClient client = workerService.createClient(
                workerService.getWorkerEntity(entity.getWorkerId()));
        List<Map<String, Object>> skills = client.listSkills(entity.getPath())
                .block(Duration.ofSeconds(10));
        return RX.ok(skills != null ? skills : List.of());
    }
}
