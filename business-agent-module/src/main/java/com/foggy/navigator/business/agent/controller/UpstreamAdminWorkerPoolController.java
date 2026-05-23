package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BizWorkerPoolDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.AddWorkerPoolMemberForm;
import com.foggy.navigator.business.agent.model.form.CreateWorkerPoolForm;
import com.foggy.navigator.business.agent.model.form.UpdateStatusForm;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/upstream-admin/worker-pools")
@RequiredArgsConstructor
public class UpstreamAdminWorkerPoolController {

    private final UpstreamClientAppAdminCredentialService adminCredentialService;
    private final BizWorkerPoolService workerPoolService;

    @GetMapping
    public RX<List<BizWorkerPoolDTO>> listPools(HttpServletRequest request,
                                                @RequestParam(required = false) String targetTenantId) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(workerPoolService.listPools(resolveTargetTenantId(principal, request, targetTenantId)));
    }

    @PostMapping
    public RX<BizWorkerPoolDTO> createPool(HttpServletRequest request,
                                           @RequestParam(required = false) String targetTenantId,
                                           @RequestBody CreateWorkerPoolForm form) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(workerPoolService.createPool(resolveTargetTenantId(principal, request, targetTenantId), form));
    }

    @PostMapping("/{poolId}/members")
    public RX<Void> addMember(HttpServletRequest request,
                              @RequestParam(required = false) String targetTenantId,
                              @PathVariable String poolId,
                              @RequestBody AddWorkerPoolMemberForm form) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        workerPoolService.addMember(resolveTargetTenantId(principal, request, targetTenantId), poolId, form);
        return RX.ok(null);
    }

    @PutMapping("/{poolId}/status")
    public RX<BizWorkerPoolDTO> updatePoolStatus(HttpServletRequest request,
                                                 @RequestParam(required = false) String targetTenantId,
                                                 @PathVariable String poolId,
                                                 @RequestBody UpdateStatusForm form) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(workerPoolService.updatePoolStatus(
                resolveTargetTenantId(principal, request, targetTenantId),
                poolId,
                form == null ? null : form.getStatus()));
    }

    private UpstreamClientAppAdminPrincipal requireAccess(HttpServletRequest request) {
        return adminCredentialService.requireAccess(request, UpstreamBootstrapRequestService.SCOPE_WORKER_POOL_MANAGE);
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
