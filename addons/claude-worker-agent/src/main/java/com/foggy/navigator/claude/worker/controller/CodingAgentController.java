package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.model.dto.CodingAgentDTO;
import com.foggy.navigator.claude.worker.model.form.RegisterAgentForm;
import com.foggy.navigator.claude.worker.model.form.UpdateAgentForm;
import com.foggy.navigator.claude.worker.service.CodingAgentService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 编程 Agent 管理 API
 */
@RestController
@RequestMapping("/api/v1/coding-agents")
@RequireAuth
@Slf4j
@RequiredArgsConstructor
public class CodingAgentController {

    private final CodingAgentService agentService;

    @GetMapping
    public RX<List<CodingAgentDTO>> listAgents() {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(agentService.listAgents(userId));
    }

    @GetMapping("/{agentId}")
    public RX<CodingAgentDTO> getAgent(@PathVariable String agentId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(agentService.getAgent(userId, agentId));
    }

    @PostMapping
    public RX<CodingAgentDTO> registerAgent(@RequestBody RegisterAgentForm form) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        return RX.ok(agentService.registerAgent(userId, tenantId, form));
    }

    @PutMapping("/{agentId}")
    public RX<CodingAgentDTO> updateAgent(@PathVariable String agentId, @RequestBody UpdateAgentForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(agentService.updateAgent(userId, agentId, form));
    }

    @DeleteMapping("/{agentId}")
    public RX<Map<String, Object>> deleteAgent(@PathVariable String agentId) {
        String userId = UserContext.getCurrentUserId();
        agentService.deleteAgent(userId, agentId);
        return RX.ok(Map.of("agentId", agentId, "deleted", true));
    }

    @GetMapping("/{agentId}/directories")
    public RX<List<CodingAgentDTO.DirectorySummary>> getAgentDirectories(@PathVariable String agentId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(agentService.getAgentDirectories(userId, agentId));
    }

    @PostMapping("/{agentId}/directories")
    public RX<Map<String, Object>> bindDirectory(
            @PathVariable String agentId,
            @RequestBody Map<String, String> body) {
        String userId = UserContext.getCurrentUserId();
        String directoryId = body.get("directoryId");
        if (directoryId == null || directoryId.isBlank()) {
            throw new IllegalArgumentException("directoryId is required");
        }
        agentService.bindDirectory(userId, agentId, directoryId);
        return RX.ok(Map.of("agentId", agentId, "directoryId", directoryId, "bound", true));
    }

    @PostMapping("/{agentId}/generate-summary")
    public RX<CodingAgentDTO> generateSummary(
            @PathVariable String agentId,
            @RequestBody(required = false) Map<String, String> body) {
        String userId = UserContext.getCurrentUserId();
        String hint = (body != null) ? body.get("hint") : null;
        return RX.ok(agentService.generateSummary(userId, agentId, hint));
    }

    @DeleteMapping("/{agentId}/directories/{directoryId}")
    public RX<Map<String, Object>> unbindDirectory(
            @PathVariable String agentId,
            @PathVariable String directoryId) {
        String userId = UserContext.getCurrentUserId();
        agentService.unbindDirectory(userId, agentId, directoryId);
        return RX.ok(Map.of("agentId", agentId, "directoryId", directoryId, "unbound", true));
    }
}
