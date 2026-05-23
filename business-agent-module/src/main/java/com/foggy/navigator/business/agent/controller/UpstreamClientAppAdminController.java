package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ClientAppDTO;
import com.foggy.navigator.business.agent.model.dto.IssuedCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.EnsureUpstreamClientAppForm;
import com.foggy.navigator.business.agent.model.form.IssueControlCredentialForm;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppManagementService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/upstream-admin/client-apps")
@RequiredArgsConstructor
public class UpstreamClientAppAdminController {

    private final UpstreamClientAppAdminCredentialService adminCredentialService;
    private final UpstreamClientAppManagementService clientAppManagementService;

    @GetMapping
    public RX<List<ClientAppDTO>> listClientApps(HttpServletRequest request,
                                                 @RequestParam(required = false) String tenantId) {
        UpstreamClientAppAdminPrincipal principal = adminCredentialService.requireAccess(
                request,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE);
        return RX.ok(clientAppManagementService.listClientApps(principal, tenantId));
    }

    @PostMapping("/ensure")
    public RX<ClientAppDTO> ensureClientApp(HttpServletRequest request,
                                            @RequestBody EnsureUpstreamClientAppForm form) {
        UpstreamClientAppAdminPrincipal principal = adminCredentialService.requireAccess(
                request,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE);
        return RX.ok(clientAppManagementService.ensureClientApp(principal, form));
    }

    @PostMapping("/{clientAppId}/control-credentials")
    public RX<IssuedCredentialDTO> issueControlCredential(HttpServletRequest request,
                                                          @PathVariable String clientAppId,
                                                          @RequestBody IssueControlCredentialForm form) {
        UpstreamClientAppAdminPrincipal principal = adminCredentialService.requireAccess(
                request,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_CONTROL_KEY_ISSUE);
        return RX.ok(clientAppManagementService.issueControlCredential(principal, clientAppId, form));
    }
}
