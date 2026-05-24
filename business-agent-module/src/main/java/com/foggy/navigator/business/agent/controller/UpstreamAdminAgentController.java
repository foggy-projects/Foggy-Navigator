package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BusinessAgentBundleDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.UpstreamAgentForm;
import com.foggy.navigator.business.agent.service.UpstreamAdminAgentService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/upstream-admin/agents")
@RequiredArgsConstructor
public class UpstreamAdminAgentController {

    private final UpstreamClientAppAdminCredentialService adminCredentialService;
    private final UpstreamAdminAgentService agentService;

    @GetMapping
    public RX<List<BusinessAgentBundleDTO>> list(HttpServletRequest request,
                                                 @RequestParam(required = false) String targetTenantId) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(agentService.list(resolveTargetTenantId(principal, request, targetTenantId), principal));
    }

    @PostMapping
    public RX<BusinessAgentBundleDTO> create(HttpServletRequest request,
                                             @RequestParam(required = false) String targetTenantId,
                                             @RequestBody UpstreamAgentForm form) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(agentService.create(resolveTargetTenantId(principal, request, targetTenantId), principal, form));
    }

    @GetMapping("/{agentId}")
    public RX<BusinessAgentBundleDTO> get(HttpServletRequest request,
                                          @PathVariable String agentId,
                                          @RequestParam(required = false) String targetTenantId) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(agentService.get(resolveTargetTenantId(principal, request, targetTenantId), principal, agentId));
    }

    @PutMapping("/{agentId}")
    public RX<BusinessAgentBundleDTO> update(HttpServletRequest request,
                                             @PathVariable String agentId,
                                             @RequestParam(required = false) String targetTenantId,
                                             @RequestBody UpstreamAgentForm form) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(agentService.update(resolveTargetTenantId(principal, request, targetTenantId), principal, agentId, form));
    }

    private UpstreamClientAppAdminPrincipal requireAccess(HttpServletRequest request) {
        return adminCredentialService.requireAccess(request, UpstreamBootstrapRequestService.SCOPE_AGENT_MANAGE);
    }

    private String resolveTargetTenantId(UpstreamClientAppAdminPrincipal principal,
                                         HttpServletRequest request,
                                         String targetTenantId) {
        String tenantId = StringUtils.hasText(targetTenantId) ? targetTenantId : request.getHeader("X-Tenant-Id");
        if (StringUtils.hasText(tenantId)) {
            adminCredentialService.requireTenant(principal, tenantId);
            return tenantId;
        }
        if (principal.getAuthorizedTenantIds() != null && principal.getAuthorizedTenantIds().size() == 1) {
            return principal.getAuthorizedTenantIds().iterator().next();
        }
        throw new SecurityException("targetTenantId is required for multi-tenant upstream admin credential");
    }
}
