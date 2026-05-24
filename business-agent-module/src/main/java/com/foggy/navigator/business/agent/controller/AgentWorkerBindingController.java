package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.AgentWorkerBindingDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.form.BindAgentWorkerForm;
import com.foggy.navigator.business.agent.service.AgentWorkerBindingService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/client-apps/{clientAppId}/agents/{agentId}/worker-bindings")
@RequiredArgsConstructor
public class AgentWorkerBindingController {

    private final AgentWorkerBindingService bindingService;
    private final ClientAppControlCredentialService controlCredentialService;

    @GetMapping
    public RX<List<AgentWorkerBindingDTO>> list(HttpServletRequest request,
                                                @PathVariable String clientAppId,
                                                @PathVariable String agentId) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(bindingService.list(principal.getTenantId(), clientAppId, agentId));
    }

    @PostMapping
    public RX<AgentWorkerBindingDTO> bind(HttpServletRequest request,
                                          @PathVariable String clientAppId,
                                          @PathVariable String agentId,
                                          @RequestBody BindAgentWorkerForm form) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(bindingService.bind(principal.getTenantId(), clientAppId, agentId, form));
    }

    @PutMapping("/default")
    public RX<AgentWorkerBindingDTO> setDefault(HttpServletRequest request,
                                                @PathVariable String clientAppId,
                                                @PathVariable String agentId,
                                                @RequestBody BindAgentWorkerForm form) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(bindingService.setDefault(principal.getTenantId(), clientAppId, agentId, form));
    }

    @DeleteMapping("/{workerPoolId}")
    public RX<Void> unbind(HttpServletRequest request,
                           @PathVariable String clientAppId,
                           @PathVariable String agentId,
                           @PathVariable String workerPoolId) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        bindingService.unbind(principal.getTenantId(), clientAppId, agentId, workerPoolId);
        return RX.ok(null);
    }

    private ClientAppControlPlanePrincipal requireAccess(HttpServletRequest request, String clientAppId) {
        return controlCredentialService.requireAccess(
                request,
                ClientAppControlCredentialService.SCOPE_AGENT_WORKER_BINDING_MANAGE,
                clientAppId);
    }
}
