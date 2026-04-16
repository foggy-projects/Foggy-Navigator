package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.model.dto.MilestoneDeleteResultDTO;
import com.foggy.navigator.claude.worker.model.dto.MilestonePageDTO;
import com.foggy.navigator.claude.worker.model.form.DirectoryMilestoneForm;
import com.foggy.navigator.claude.worker.service.WorkingDirectoryService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.DirectoryMilestoneDTO;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工作目录里程碑独立 CRUD API
 */
@RestController
@RequestMapping("/api/v1/working-directories/{directoryId}/milestones")
@RequireAuth
@RequiredArgsConstructor
public class MilestoneController {

    private final WorkingDirectoryService directoryService;

    @GetMapping
    public RX<List<DirectoryMilestoneDTO>> list(@PathVariable String directoryId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(directoryService.listMilestones(userId, directoryId));
    }

    @GetMapping("/paged")
    public RX<MilestonePageDTO> listPaged(
            @PathVariable String directoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "startAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(directoryService.listMilestonesPaged(userId, directoryId, page, size, sortBy, sortDir));
    }

    @PostMapping
    public RX<DirectoryMilestoneDTO> create(
            @PathVariable String directoryId,
            @RequestBody DirectoryMilestoneForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(directoryService.addMilestone(userId, directoryId, form));
    }

    @PutMapping("/{milestoneId}")
    public RX<DirectoryMilestoneDTO> update(
            @PathVariable String directoryId,
            @PathVariable String milestoneId,
            @RequestBody DirectoryMilestoneForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(directoryService.updateSingleMilestone(userId, directoryId, milestoneId, form));
    }

    @GetMapping("/{milestoneId}/session-count")
    public RX<Map<String, Long>> sessionCount(
            @PathVariable String directoryId,
            @PathVariable String milestoneId) {
        String userId = UserContext.getCurrentUserId();
        long count = directoryService.countSessionsByMilestone(userId, milestoneId);
        return RX.ok(Map.of("sessionCount", count));
    }

    @DeleteMapping("/{milestoneId}")
    public RX<MilestoneDeleteResultDTO> delete(
            @PathVariable String directoryId,
            @PathVariable String milestoneId,
            @RequestParam(defaultValue = "false") boolean force) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(directoryService.deleteMilestone(userId, directoryId, milestoneId, force));
    }
}
