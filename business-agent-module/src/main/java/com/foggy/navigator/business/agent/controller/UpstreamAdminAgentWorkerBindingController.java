package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.AgentWorkerBindingDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.BindAgentWorkerForm;
import com.foggy.navigator.business.agent.service.AgentWorkerBindingService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/upstream-admin/agents/{agentId}/worker-bindings")
@RequiredArgsConstructor
public class UpstreamAdminAgentWorkerBindingController {

    private final UpstreamClientAppAdminCredentialService adminCredentialService;
    private final AgentWorkerBindingService bindingService;

    @GetMapping
    public RX<List<AgentWorkerBindingDTO>> list(HttpServletRequest request,
                                                @PathVariable String agentId,
                                                @RequestParam(required = false) String targetTenantId) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(bindingService.listSystemOwned(resolveTargetTenantId(principal, request, targetTenantId),
                principal,
                agentId));
    }

    @PostMapping
    public RX<AgentWorkerBindingDTO> bind(HttpServletRequest request,
                                          @PathVariable String agentId,
                                          @RequestParam(required = false) String targetTenantId,
                                          @RequestBody BindAgentWorkerForm form) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(bindingService.bindSystemOwned(resolveTargetTenantId(principal, request, targetTenantId),
                principal,
                agentId,
                form));
    }

    @PutMapping("/default")
    public RX<AgentWorkerBindingDTO> setDefault(HttpServletRequest request,
                                                @PathVariable String agentId,
                                                @RequestParam(required = false) String targetTenantId,
                                                @RequestBody BindAgentWorkerForm form) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(bindingService.setSystemOwnedDefault(resolveTargetTenantId(principal, request, targetTenantId),
                principal,
                agentId,
                form));
    }

    @DeleteMapping("/{workerPoolId}")
    public RX<Void> unbind(HttpServletRequest request,
                           @PathVariable String agentId,
                           @PathVariable String workerPoolId,
                           @RequestParam(required = false) String targetTenantId) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        bindingService.unbindSystemOwned(resolveTargetTenantId(principal, request, targetTenantId),
                principal,
                agentId,
                workerPoolId);
        return RX.ok(null);
    }

    private UpstreamClientAppAdminPrincipal requireAccess(HttpServletRequest request) {
        return adminCredentialService.requireAccess(
                request,
                UpstreamBootstrapRequestService.SCOPE_AGENT_WORKER_BINDING_MANAGE);
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
