package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.IssuedCredentialDTO;
import com.foggy.navigator.business.agent.model.form.IssueProvisioningCredentialForm;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/client-apps/provisioning-credentials")
@RequireAuth(roles = {"TENANT_ADMIN"})
@RequiredArgsConstructor
public class AdminClientAppController {

    private final ClientAppService clientAppService;

    @PostMapping
    public RX<IssuedCredentialDTO> issueProvisioningCredential(@RequestBody IssueProvisioningCredentialForm form) {
        return RX.ok(clientAppService.issueProvisioningCredential(
                UserContext.getCurrentTenantId(), UserContext.getCurrentUserId(), form));
    }
}
