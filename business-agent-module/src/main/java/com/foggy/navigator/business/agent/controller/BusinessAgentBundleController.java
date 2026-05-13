package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BusinessAgentBundleDTO;
import com.foggy.navigator.business.agent.model.form.SyncBusinessAgentBundleForm;
import com.foggy.navigator.business.agent.service.BusinessAgentBundleService;
import com.foggy.navigator.common.annotation.RequireAuth;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/business-agent")
@RequiredArgsConstructor
public class BusinessAgentBundleController {

    private final BusinessAgentBundleService agentBundleService;

    @RequireAuth(roles = "TENANT_ADMIN")
    @PostMapping("/agent-bundles/sync")
    public BusinessAgentBundleDTO syncAgentBundle(
            @RequestAttribute("tenantId") String tenantId,
            @RequestAttribute("userId") String actorUserId,
            @RequestBody SyncBusinessAgentBundleForm form) {
        return agentBundleService.syncAgentBundle(tenantId, actorUserId, form);
    }
}
