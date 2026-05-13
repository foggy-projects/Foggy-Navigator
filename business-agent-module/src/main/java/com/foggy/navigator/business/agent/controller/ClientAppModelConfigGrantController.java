package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.dto.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.business.agent.model.form.GrantModelConfigForm;
import com.foggy.navigator.business.agent.model.form.UpdateStatusForm;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/client-apps/{clientAppId}/model-config-grants")
@RequiredArgsConstructor
public class ClientAppModelConfigGrantController {

    private final ClientAppModelConfigGrantService grantService;
    private final ClientAppControlCredentialService controlCredentialService;

    @GetMapping
    public RX<List<ClientAppModelConfigGrantDTO>> listGrants(HttpServletRequest request,
                                                             @PathVariable String clientAppId) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(grantService.listGrants(principal.getTenantId(), clientAppId));
    }

    @PostMapping
    public RX<ClientAppModelConfigGrantDTO> grantModelConfig(HttpServletRequest request,
                                                             @PathVariable String clientAppId,
                                                             @RequestBody GrantModelConfigForm form) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(grantService.grantModelConfig(
                principal.getTenantId(), principal.getActorUserId(), clientAppId, form));
    }

    @PutMapping("/{grantId}/status")
    public RX<ClientAppModelConfigGrantDTO> updateStatus(HttpServletRequest request,
                                                         @PathVariable String clientAppId,
                                                         @PathVariable Long grantId,
                                                         @RequestBody UpdateStatusForm form) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(grantService.updateStatus(
                principal.getTenantId(), clientAppId, grantId, form == null ? null : form.getStatus()));
    }

    @PutMapping("/{grantId}/default")
    public RX<ClientAppModelConfigGrantDTO> setDefault(HttpServletRequest request,
                                                       @PathVariable String clientAppId,
                                                       @PathVariable Long grantId) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(grantService.setDefault(principal.getTenantId(), clientAppId, grantId));
    }

    private ClientAppControlPlanePrincipal requireAccess(HttpServletRequest request, String clientAppId) {
        return controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_MODEL_CONFIG_GRANT_MANAGE, clientAppId);
    }
}
