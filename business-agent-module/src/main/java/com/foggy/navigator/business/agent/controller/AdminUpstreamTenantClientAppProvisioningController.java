package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.UpstreamTenantClientAppProvisioningDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.EnsureUpstreamTenantClientAppForm;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggy.navigator.business.agent.service.UpstreamTenantClientAppProvisioningService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/upstream-tenants/client-apps")
@RequiredArgsConstructor
@Slf4j
public class AdminUpstreamTenantClientAppProvisioningController {

    private final UpstreamClientAppAdminCredentialService adminCredentialService;
    private final UpstreamTenantClientAppProvisioningService provisioningService;

    @PostMapping("/ensure")
    public RX<UpstreamTenantClientAppProvisioningDTO> ensure(HttpServletRequest request,
                                                             @RequestBody EnsureUpstreamTenantClientAppForm form) {
        log.info("Upstream tenant ClientApp ensure requested: method={}, path={}, remoteAddr={}, sourceSystem={}, sourceTenantId={}, rotateCredentials={}, adminKeyPresent={}",
                request == null ? null : request.getMethod(),
                request == null ? null : request.getRequestURI(),
                request == null ? null : request.getRemoteAddr(),
                form == null ? null : form.getSourceSystem(),
                form == null ? null : form.getSourceTenantId(),
                form == null ? null : form.getRotateCredentials(),
                hasHeader(request, UpstreamClientAppAdminCredentialService.HEADER_ADMIN_KEY));
        UpstreamClientAppAdminPrincipal principal = adminCredentialService.requireAccess(
                request,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE);
        adminCredentialService.requireScope(
                principal,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_CONTROL_KEY_ISSUE);
        UpstreamTenantClientAppProvisioningDTO result = provisioningService.ensure(form, principal);
        log.info("Upstream tenant ClientApp ensure completed: sourceSystem={}, sourceTenantId={}, credentialId={}, navigatorTenantId={}, clientAppId={}, status={}, errorCode={}, created={}, rotated={}, credentialsReplayable={}",
                form == null ? null : form.getSourceSystem(),
                form == null ? null : form.getSourceTenantId(),
                principal.getCredentialId(),
                result.getNavigatorTenantId(),
                result.getClientAppId(),
                result.getStatus(),
                result.getErrorCode(),
                result.isCreated(),
                result.isRotated(),
                result.isCredentialsReplayable());
        return RX.ok(result);
    }

    private boolean hasHeader(HttpServletRequest request, String headerName) {
        return request != null && org.springframework.util.StringUtils.hasText(request.getHeader(headerName));
    }
}
