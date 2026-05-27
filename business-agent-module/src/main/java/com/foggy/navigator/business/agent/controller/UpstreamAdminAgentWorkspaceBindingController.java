package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.AgentWorkspaceBindingDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.BindAgentWorkspaceForm;
import com.foggy.navigator.business.agent.service.AgentWorkspaceBindingService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/upstream-admin/agents/{agentId}/workspace-bindings")
@RequiredArgsConstructor
public class UpstreamAdminAgentWorkspaceBindingController {

    private final UpstreamClientAppAdminCredentialService adminCredentialService;
    private final AgentWorkspaceBindingService bindingService;

    @GetMapping
    public RX<List<AgentWorkspaceBindingDTO>> list(HttpServletRequest request,
                                                   @PathVariable String agentId,
                                                   @RequestParam(required = false) String targetTenantId) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(bindingService.listSystemOwned(resolveTargetTenantId(principal, request, targetTenantId),
                principal,
                agentId));
    }

    @PostMapping
    public RX<AgentWorkspaceBindingDTO> bind(HttpServletRequest request,
                                             @PathVariable String agentId,
                                             @RequestParam(required = false) String targetTenantId,
                                             @RequestBody BindAgentWorkspaceForm form) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(bindingService.bindSystemOwned(resolveTargetTenantId(principal, request, targetTenantId),
                principal,
                agentId,
                form));
    }

    @PutMapping("/default")
    public RX<AgentWorkspaceBindingDTO> setDefault(HttpServletRequest request,
                                                   @PathVariable String agentId,
                                                   @RequestParam(required = false) String targetTenantId,
                                                   @RequestBody BindAgentWorkspaceForm form) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(bindingService.setSystemOwnedDefault(resolveTargetTenantId(principal, request, targetTenantId),
                principal,
                agentId,
                form));
    }

    @DeleteMapping("/{directoryId}")
    public RX<Void> unbind(HttpServletRequest request,
                           @PathVariable String agentId,
                           @PathVariable String directoryId,
                           @RequestParam(required = false) String targetTenantId) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        bindingService.unbindSystemOwned(resolveTargetTenantId(principal, request, targetTenantId),
                principal,
                agentId,
                directoryId);
        return RX.ok(null);
    }

    private UpstreamClientAppAdminPrincipal requireAccess(HttpServletRequest request) {
        return adminCredentialService.requireAccess(
                request,
                UpstreamBootstrapRequestService.SCOPE_AGENT_WORKSPACE_BINDING_MANAGE);
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
