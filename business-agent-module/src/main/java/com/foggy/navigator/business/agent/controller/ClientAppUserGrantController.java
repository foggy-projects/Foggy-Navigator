package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.dto.ClientAppUpstreamUserGrantDTO;
import com.foggy.navigator.business.agent.model.form.GrantUpstreamUserForm;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/business-agent/client-apps/{clientAppId}/upstream-users")
@RequiredArgsConstructor
public class ClientAppUserGrantController {

    private final ClientAppUserGrantService clientAppUserGrantService;
    private final ClientAppControlCredentialService controlCredentialService;

    @PostMapping
    public ClientAppUpstreamUserGrantDTO grantUpstreamUserAccess(
            HttpServletRequest request,
            @PathVariable String clientAppId,
            @RequestBody GrantUpstreamUserForm form) {
        ClientAppControlPlanePrincipal principal = controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_UPSTREAM_USER_GRANT, clientAppId);
        return clientAppUserGrantService.grantUpstreamUserAccess(
                principal.getTenantId(), clientAppId, principal.getActorUserId(), form);
    }

    @PutMapping("/{upstreamUserId}/status")
    public ClientAppUpstreamUserGrantDTO updateUpstreamUserGrantStatus(
            HttpServletRequest request,
            @PathVariable String clientAppId,
            @PathVariable String upstreamUserId,
            @RequestParam String status) {
        ClientAppControlPlanePrincipal principal = controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_UPSTREAM_USER_GRANT, clientAppId);
        return clientAppUserGrantService.updateUpstreamUserGrantStatus(
                principal.getTenantId(), clientAppId, upstreamUserId, status);
    }
}
