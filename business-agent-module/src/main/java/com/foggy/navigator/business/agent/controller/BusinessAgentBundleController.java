package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BusinessAgentBundleDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.form.SyncBusinessAgentBundleForm;
import com.foggy.navigator.business.agent.service.BusinessAgentBundleService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/business-agent")
@RequiredArgsConstructor
public class BusinessAgentBundleController {

    private final BusinessAgentBundleService agentBundleService;
    private final ClientAppControlCredentialService controlCredentialService;

    @PostMapping("/agent-bundles/sync")
    public BusinessAgentBundleDTO syncAgentBundle(
            HttpServletRequest request,
            @RequestBody SyncBusinessAgentBundleForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        ClientAppControlPlanePrincipal principal = controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_AGENT_BUNDLE_SYNC, form.getClientAppId());
        if (!principal.isAdmin()) {
            if (!StringUtils.hasText(form.getClientAppId())) {
                form.setClientAppId(principal.getClientAppId());
            } else if (!form.getClientAppId().equals(principal.getClientAppId())) {
                throw new SecurityException("control-plane credential clientAppId mismatch");
            }
        }
        return agentBundleService.syncAgentBundle(principal.getTenantId(), principal.getActorUserId(), form);
    }
}
