package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.UpstreamTenantClientAppProvisioningDTO;
import com.foggy.navigator.business.agent.model.form.EnsureUpstreamTenantClientAppForm;
import com.foggy.navigator.business.agent.service.UpstreamTenantClientAppProvisioningService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/upstream-tenants/client-apps")
@RequireAuth(roles = {"TENANT_ADMIN"})
@RequiredArgsConstructor
public class AdminUpstreamTenantClientAppProvisioningController {

    private final UpstreamTenantClientAppProvisioningService provisioningService;

    @PostMapping("/ensure")
    public RX<UpstreamTenantClientAppProvisioningDTO> ensure(@RequestBody EnsureUpstreamTenantClientAppForm form) {
        return RX.ok(provisioningService.ensure(form, UserContext.getCurrentUserId()));
    }
}
