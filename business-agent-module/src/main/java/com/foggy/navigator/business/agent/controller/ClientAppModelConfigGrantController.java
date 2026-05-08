package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.business.agent.model.form.GrantModelConfigForm;
import com.foggy.navigator.business.agent.model.form.UpdateStatusForm;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/client-apps/{clientAppId}/model-config-grants")
@RequireAuth(roles = {"TENANT_ADMIN"})
@RequiredArgsConstructor
public class ClientAppModelConfigGrantController {

    private String resolveTenantId() {
        com.foggy.navigator.common.dto.CurrentUser user = UserContext.getCurrentUser();
        String tenantId = user.getTenantId();
        return (tenantId != null && !tenantId.isEmpty()) ? tenantId : user.getUserId();
    }


    private final ClientAppModelConfigGrantService grantService;

    @GetMapping
    public RX<List<ClientAppModelConfigGrantDTO>> listGrants(@PathVariable String clientAppId) {
        return RX.ok(grantService.listGrants(resolveTenantId(), clientAppId));
    }

    @PostMapping
    public RX<ClientAppModelConfigGrantDTO> grantModelConfig(@PathVariable String clientAppId,
                                                             @RequestBody GrantModelConfigForm form) {
        return RX.ok(grantService.grantModelConfig(
                resolveTenantId(), UserContext.getCurrentUserId(), clientAppId, form));
    }

    @PutMapping("/{grantId}/status")
    public RX<ClientAppModelConfigGrantDTO> updateStatus(@PathVariable String clientAppId,
                                                         @PathVariable Long grantId,
                                                         @RequestBody UpdateStatusForm form) {
        return RX.ok(grantService.updateStatus(
                resolveTenantId(), clientAppId, grantId, form == null ? null : form.getStatus()));
    }

    @PutMapping("/{grantId}/default")
    public RX<ClientAppModelConfigGrantDTO> setDefault(@PathVariable String clientAppId,
                                                       @PathVariable Long grantId) {
        return RX.ok(grantService.setDefault(resolveTenantId(), clientAppId, grantId));
    }
}
