package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.dto.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.business.agent.model.form.ClientAppModelConfigForm;
import com.foggy.navigator.business.agent.model.form.RotateModelConfigKeyForm;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggy.navigator.business.agent.service.ClientAppOwnedModelConfigService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/client-apps/{clientAppId}/model-configs")
@RequiredArgsConstructor
public class ClientAppOwnedModelConfigController {

    private final ClientAppOwnedModelConfigService modelConfigService;
    private final ClientAppControlCredentialService controlCredentialService;

    @PostMapping
    public RX<ClientAppModelConfigGrantDTO> create(HttpServletRequest request,
                                                   @PathVariable String clientAppId,
                                                   @RequestBody ClientAppModelConfigForm form) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(modelConfigService.create(principal.getTenantId(), principal.getActorUserId(), clientAppId, form));
    }

    @PutMapping("/{modelConfigId}")
    public RX<ClientAppModelConfigGrantDTO> update(HttpServletRequest request,
                                                   @PathVariable String clientAppId,
                                                   @PathVariable String modelConfigId,
                                                   @RequestBody ClientAppModelConfigForm form) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(modelConfigService.update(principal.getTenantId(), clientAppId, modelConfigId, form));
    }

    @PutMapping("/{modelConfigId}/key")
    public RX<ClientAppModelConfigGrantDTO> rotateKey(HttpServletRequest request,
                                                      @PathVariable String clientAppId,
                                                      @PathVariable String modelConfigId,
                                                      @RequestBody RotateModelConfigKeyForm form) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(modelConfigService.rotateKey(principal.getTenantId(), clientAppId, modelConfigId, form));
    }

    private ClientAppControlPlanePrincipal requireAccess(HttpServletRequest request, String clientAppId) {
        return controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_MODEL_CONFIG_MANAGE, clientAppId);
    }
}
