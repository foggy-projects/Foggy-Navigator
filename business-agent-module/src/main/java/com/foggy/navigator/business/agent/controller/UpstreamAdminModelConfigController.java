package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.ClientAppModelConfigForm;
import com.foggy.navigator.business.agent.model.form.RotateModelConfigKeyForm;
import com.foggy.navigator.business.agent.service.UpstreamAdminModelConfigService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/upstream-admin/model-configs")
@RequiredArgsConstructor
public class UpstreamAdminModelConfigController {

    private final UpstreamClientAppAdminCredentialService adminCredentialService;
    private final UpstreamAdminModelConfigService modelConfigService;

    @GetMapping
    public RX<List<LlmModelConfigDTO>> list(HttpServletRequest request,
                                            @RequestParam(required = false) String targetTenantId) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(modelConfigService.list(resolveTargetTenantId(principal, request, targetTenantId), principal));
    }

    @PostMapping
    public RX<LlmModelConfigDTO> create(HttpServletRequest request,
                                        @RequestParam(required = false) String targetTenantId,
                                        @RequestBody ClientAppModelConfigForm form) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(modelConfigService.create(resolveTargetTenantId(principal, request, targetTenantId), principal, form));
    }

    @PutMapping("/{modelConfigId}")
    public RX<LlmModelConfigDTO> update(HttpServletRequest request,
                                        @RequestParam(required = false) String targetTenantId,
                                        @PathVariable String modelConfigId,
                                        @RequestBody ClientAppModelConfigForm form) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(modelConfigService.update(resolveTargetTenantId(principal, request, targetTenantId),
                principal, modelConfigId, form));
    }

    @PutMapping("/{modelConfigId}/key")
    public RX<LlmModelConfigDTO> rotateKey(HttpServletRequest request,
                                           @RequestParam(required = false) String targetTenantId,
                                           @PathVariable String modelConfigId,
                                           @RequestBody RotateModelConfigKeyForm form) {
        UpstreamClientAppAdminPrincipal principal = requireAccess(request);
        return RX.ok(modelConfigService.rotateKey(resolveTargetTenantId(principal, request, targetTenantId),
                principal, modelConfigId, form));
    }

    private UpstreamClientAppAdminPrincipal requireAccess(HttpServletRequest request) {
        return adminCredentialService.requireAccess(request, UpstreamBootstrapRequestService.SCOPE_MODEL_CONFIG_MANAGE);
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
