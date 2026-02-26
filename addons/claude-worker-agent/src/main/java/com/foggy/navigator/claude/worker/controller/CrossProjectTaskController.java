package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.model.dto.CrossProjectPhaseDTO;
import com.foggy.navigator.claude.worker.model.dto.CrossProjectTaskDTO;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.form.AdvancePhaseForm;
import com.foggy.navigator.claude.worker.model.form.CreateCrossProjectTaskForm;
import com.foggy.navigator.claude.worker.service.CrossProjectTaskService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 跨项目任务管理 API
 */
@RestController
@RequestMapping("/api/v1/cross-project-tasks")
@RequireAuth
@Slf4j
@RequiredArgsConstructor
public class CrossProjectTaskController {

    private final CrossProjectTaskService taskService;

    @PostMapping
    public RX<CrossProjectTaskDTO> createTask(@RequestBody CreateCrossProjectTaskForm form) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        return RX.ok(taskService.createTask(userId, tenantId, form));
    }

    @PostMapping("/{contextId}/start")
    public RX<CrossProjectTaskDTO> startTask(@PathVariable String contextId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskService.startTask(userId, contextId));
    }

    @GetMapping("/{contextId}")
    public RX<CrossProjectTaskDTO> getTask(@PathVariable String contextId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskService.getTask(userId, contextId));
    }

    @GetMapping("/page")
    public RX<Page<CrossProjectTaskDTO>> listTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskService.listTasks(userId, page, size));
    }

    @PostMapping("/{contextId}/review")
    public RX<TaskDTO> triggerReview(@PathVariable String contextId) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        return RX.ok(taskService.triggerReview(userId, tenantId, contextId));
    }

    @PutMapping("/{contextId}/phases/{phaseId}/handoff")
    public RX<CrossProjectPhaseDTO> updateHandoff(
            @PathVariable String contextId,
            @PathVariable String phaseId,
            @RequestBody Map<String, String> body) {
        String userId = UserContext.getCurrentUserId();
        String handoffArtifact = body.get("handoffArtifact");
        return RX.ok(taskService.updateHandoff(userId, contextId, phaseId, handoffArtifact));
    }

    @PostMapping("/{contextId}/advance")
    public RX<CrossProjectTaskDTO> advancePhase(
            @PathVariable String contextId,
            @RequestBody(required = false) AdvancePhaseForm form) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        String handoffOverride = form != null ? form.getHandoffArtifact() : null;
        return RX.ok(taskService.advancePhase(userId, tenantId, contextId, handoffOverride));
    }

    @PostMapping("/{contextId}/cancel")
    public RX<CrossProjectTaskDTO> cancelTask(@PathVariable String contextId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(taskService.cancelTask(userId, contextId));
    }
}
