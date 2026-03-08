package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.model.dto.AgentTeamsConfigDTO;
import com.foggy.navigator.claude.worker.model.form.CreateAgentTeamsConfigForm;
import com.foggy.navigator.claude.worker.model.form.UpdateAgentTeamsConfigForm;
import com.foggy.navigator.claude.worker.service.AgentTeamsConfigService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent Teams 命名配置 CRUD API
 */
@RestController
@RequestMapping("/api/v1/working-directories/{directoryId}/agent-teams-configs")
@RequireAuth
@Slf4j
@RequiredArgsConstructor
public class AgentTeamsConfigController {

    private final AgentTeamsConfigService configService;

    @GetMapping
    public RX<List<AgentTeamsConfigDTO>> list(@PathVariable String directoryId) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(configService.listConfigs(directoryId, userId));
    }

    @PostMapping
    public RX<AgentTeamsConfigDTO> create(
            @PathVariable String directoryId,
            @RequestBody CreateAgentTeamsConfigForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(configService.createConfig(directoryId, userId, form));
    }

    @PutMapping("/{configId}")
    public RX<AgentTeamsConfigDTO> update(
            @PathVariable String directoryId,
            @PathVariable String configId,
            @RequestBody UpdateAgentTeamsConfigForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(configService.updateConfig(configId, userId, form));
    }

    @DeleteMapping("/{configId}")
    public RX<Void> delete(
            @PathVariable String directoryId,
            @PathVariable String configId) {
        String userId = UserContext.getCurrentUserId();
        configService.deleteConfig(configId, userId);
        return RX.ok(null);
    }
}
