package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ClientAppUpstreamUserGrantDTO;
import com.foggy.navigator.business.agent.model.form.GrantUpstreamUserForm;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
import com.foggy.navigator.common.annotation.RequireAuth;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/business-agent/client-apps/{clientAppId}/upstream-users")
@RequiredArgsConstructor
public class ClientAppUserGrantController {

    private final ClientAppUserGrantService clientAppUserGrantService;

    @RequireAuth(roles = "TENANT_ADMIN")
    @PostMapping
    public ClientAppUpstreamUserGrantDTO grantUpstreamUserAccess(
            @RequestAttribute("tenantId") String tenantId,
            @RequestAttribute("userId") String actorUserId,
            @PathVariable String clientAppId,
            @RequestBody GrantUpstreamUserForm form) {
        return clientAppUserGrantService.grantUpstreamUserAccess(tenantId, clientAppId, actorUserId, form);
    }

    @RequireAuth(roles = "TENANT_ADMIN")
    @PutMapping("/{upstreamUserId}/status")
    public ClientAppUpstreamUserGrantDTO updateUpstreamUserGrantStatus(
            @RequestAttribute("tenantId") String tenantId,
            @PathVariable String clientAppId,
            @PathVariable String upstreamUserId,
            @RequestParam String status) {
        return clientAppUserGrantService.updateUpstreamUserGrantStatus(tenantId, clientAppId, upstreamUserId, status);
    }
}
