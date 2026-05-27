package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.AgentModelBindingDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.form.BindAgentModelForm;
import com.foggy.navigator.business.agent.service.AgentModelBindingService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/client-apps/{clientAppId}/agents/{agentId}/model-bindings")
@RequiredArgsConstructor
public class AgentModelBindingController {

    private final AgentModelBindingService bindingService;
    private final ClientAppControlCredentialService controlCredentialService;

    @GetMapping
    public RX<List<AgentModelBindingDTO>> list(HttpServletRequest request,
                                               @PathVariable String clientAppId,
                                               @PathVariable String agentId) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(bindingService.list(principal.getTenantId(), clientAppId, agentId));
    }

    @PostMapping
    public RX<AgentModelBindingDTO> bind(HttpServletRequest request,
                                         @PathVariable String clientAppId,
                                         @PathVariable String agentId,
                                         @RequestBody BindAgentModelForm form) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(bindingService.bind(principal.getTenantId(), clientAppId, agentId, form));
    }

    @PutMapping("/default")
    public RX<AgentModelBindingDTO> setDefault(HttpServletRequest request,
                                               @PathVariable String clientAppId,
                                               @PathVariable String agentId,
                                               @RequestBody BindAgentModelForm form) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(bindingService.setDefault(principal.getTenantId(), clientAppId, agentId, form));
    }

    @DeleteMapping("/{modelConfigId}")
    public RX<Void> unbind(HttpServletRequest request,
                           @PathVariable String clientAppId,
                           @PathVariable String agentId,
                           @PathVariable String modelConfigId) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        bindingService.unbind(principal.getTenantId(), clientAppId, agentId, modelConfigId);
        return RX.ok(null);
    }

    private ClientAppControlPlanePrincipal requireAccess(HttpServletRequest request, String clientAppId) {
        return controlCredentialService.requireAccess(
                request,
                ClientAppControlCredentialService.SCOPE_AGENT_MODEL_BINDING_MANAGE,
                clientAppId);
    }
}
