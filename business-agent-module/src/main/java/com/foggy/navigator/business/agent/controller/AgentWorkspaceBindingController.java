package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.AgentWorkspaceBindingDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.form.BindAgentWorkspaceForm;
import com.foggy.navigator.business.agent.service.AgentWorkspaceBindingService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/client-apps/{clientAppId}/agents/{agentId}/workspace-bindings")
@RequiredArgsConstructor
public class AgentWorkspaceBindingController {

    private final AgentWorkspaceBindingService bindingService;
    private final ClientAppControlCredentialService controlCredentialService;

    @GetMapping
    public RX<List<AgentWorkspaceBindingDTO>> list(HttpServletRequest request,
                                                   @PathVariable String clientAppId,
                                                   @PathVariable String agentId) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(bindingService.list(principal.getTenantId(), clientAppId, agentId));
    }

    @PostMapping
    public RX<AgentWorkspaceBindingDTO> bind(HttpServletRequest request,
                                             @PathVariable String clientAppId,
                                             @PathVariable String agentId,
                                             @RequestBody BindAgentWorkspaceForm form) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(bindingService.bind(principal.getTenantId(), clientAppId, agentId, form));
    }

    @PutMapping("/default")
    public RX<AgentWorkspaceBindingDTO> setDefault(HttpServletRequest request,
                                                   @PathVariable String clientAppId,
                                                   @PathVariable String agentId,
                                                   @RequestBody BindAgentWorkspaceForm form) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(bindingService.setDefault(principal.getTenantId(), clientAppId, agentId, form));
    }

    @DeleteMapping("/{directoryId}")
    public RX<Void> unbind(HttpServletRequest request,
                           @PathVariable String clientAppId,
                           @PathVariable String agentId,
                           @PathVariable String directoryId) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        bindingService.unbind(principal.getTenantId(), clientAppId, agentId, directoryId);
        return RX.ok(null);
    }

    private ClientAppControlPlanePrincipal requireAccess(HttpServletRequest request, String clientAppId) {
        return controlCredentialService.requireAccess(
                request,
                ClientAppControlCredentialService.SCOPE_AGENT_WORKSPACE_BINDING_MANAGE,
                clientAppId);
    }
}
