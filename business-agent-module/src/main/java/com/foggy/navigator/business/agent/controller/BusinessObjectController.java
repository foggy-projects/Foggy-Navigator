package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.dto.BusinessObjectDTO;
import com.foggy.navigator.business.agent.model.form.CreateBusinessObjectForm;
import com.foggy.navigator.business.agent.model.form.UpdateBusinessObjectForm;
import com.foggy.navigator.business.agent.service.BusinessObjectService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/business-agent/business-objects")
@RequiredArgsConstructor
public class BusinessObjectController {

    private final BusinessObjectService businessObjectService;
    private final ClientAppControlCredentialService controlCredentialService;
    private final UpstreamClientAppAdminCredentialService adminCredentialService;

    @PostMapping
    public BusinessObjectDTO createBusinessObject(
            HttpServletRequest request,
            @RequestBody CreateBusinessObjectForm form) {
        BusinessObjectAccess access = resolveAccess(request);
        return businessObjectService.createBusinessObject(access.tenantId(), access.actorUserId(), form);
    }

    @GetMapping("/{objectId}")
    public BusinessObjectDTO getBusinessObject(
            HttpServletRequest request,
            @PathVariable String objectId) {
        BusinessObjectAccess access = resolveAccess(request);
        return businessObjectService.getBusinessObject(access.tenantId(), objectId);
    }

    @PutMapping("/{objectId}")
    public BusinessObjectDTO updateBusinessObject(
            HttpServletRequest request,
            @PathVariable String objectId,
            @RequestBody UpdateBusinessObjectForm form) {
        BusinessObjectAccess access = resolveAccess(request);
        return businessObjectService.updateBusinessObject(access.tenantId(), objectId, access.actorUserId(), form);
    }

    private BusinessObjectAccess resolveAccess(HttpServletRequest request) {
        if (hasAdminKey(request)) {
            UpstreamClientAppAdminPrincipal principal = adminCredentialService.requireAccess(
                    request, UpstreamBootstrapRequestService.SCOPE_BUSINESS_OBJECT_MANAGE);
            return new BusinessObjectAccess(
                    resolveTargetTenantId(principal, request),
                    "upstream-admin:" + principal.getCredentialId());
        }
        ClientAppControlPlanePrincipal principal = controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_BUSINESS_OBJECT_MANAGE, null);
        return new BusinessObjectAccess(principal.getTenantId(), principal.getActorUserId());
    }

    private String resolveTargetTenantId(UpstreamClientAppAdminPrincipal principal, HttpServletRequest request) {
        String tenantId = request == null ? null : request.getHeader("X-Tenant-Id");
        if (StringUtils.hasText(tenantId)) {
            String normalized = tenantId.trim();
            requireBusinessObjectTenant(principal, normalized);
            return normalized;
        }
        Set<String> authorizedTenantIds = principal.getAuthorizedTenantIds();
        if (authorizedTenantIds != null && authorizedTenantIds.size() == 1) {
            String onlyTenant = authorizedTenantIds.iterator().next();
            if (!onlyTenant.equals(principal.getUpstreamSystemId())) {
                return onlyTenant;
            }
        }
        throw new SecurityException("X-Tenant-Id is required for multi-tenant upstream admin credential");
    }

    private void requireBusinessObjectTenant(UpstreamClientAppAdminPrincipal principal, String tenantId) {
        Set<String> authorizedTenantIds = principal == null ? null : principal.getAuthorizedTenantIds();
        if (authorizedTenantIds != null && authorizedTenantIds.contains(tenantId)) {
            return;
        }
        String upstreamSystemId = principal == null ? null : principal.getUpstreamSystemId();
        if (authorizedTenantIds != null
                && StringUtils.hasText(upstreamSystemId)
                && authorizedTenantIds.contains(upstreamSystemId)
                && tenantId.startsWith("nav_" + upstreamSystemId.toLowerCase(Locale.ROOT) + "_")) {
            return;
        }
        throw new SecurityException("upstream admin credential tenant mismatch");
    }

    private boolean hasControlKey(HttpServletRequest request) {
        return request != null
                && StringUtils.hasText(request.getHeader(ClientAppControlCredentialService.HEADER_CONTROL_KEY));
    }

    private boolean hasAdminKey(HttpServletRequest request) {
        return request != null
                && StringUtils.hasText(request.getHeader(UpstreamClientAppAdminCredentialService.HEADER_ADMIN_KEY));
    }

    private record BusinessObjectAccess(String tenantId, String actorUserId) {
    }
}
