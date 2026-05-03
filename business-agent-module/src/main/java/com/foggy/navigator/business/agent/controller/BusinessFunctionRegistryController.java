package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BusinessFunctionSummaryDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppFunctionGrantDTO;
import com.foggy.navigator.business.agent.model.form.GrantBusinessFunctionForm;
import com.foggy.navigator.business.agent.model.form.ImportBusinessFunctionManifestForm;
import com.foggy.navigator.business.agent.service.BusinessFunctionRegistryService;
import com.foggy.navigator.common.annotation.RequireAuth;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/business-agent")
@RequiredArgsConstructor
public class BusinessFunctionRegistryController {

    private final BusinessFunctionRegistryService registryService;

    @RequireAuth(roles = "TENANT_ADMIN")
    @PostMapping("/functions/import")
    public void importManifest(
            @RequestAttribute("tenantId") String tenantId,
            @RequestAttribute("userId") String actorUserId,
            @RequestBody ImportBusinessFunctionManifestForm form) {
        registryService.importManifest(tenantId, actorUserId, form);
    }



    @RequireAuth(roles = "TENANT_ADMIN")
    @PostMapping("/client-apps/{clientAppId}/function-grants")
    public ClientAppFunctionGrantDTO grantFunctionToClientApp(
            @RequestAttribute("tenantId") String tenantId,
            @RequestAttribute("userId") String actorUserId,
            @PathVariable String clientAppId,
            @RequestBody GrantBusinessFunctionForm form) {
        return registryService.grantFunctionToClientApp(tenantId, clientAppId, actorUserId, form);
    }



    @RequireAuth(roles = "TENANT_ADMIN")
    @PutMapping("/client-apps/{clientAppId}/function-grants/{grantId}/status")
    public ClientAppFunctionGrantDTO updateGrantStatus(
            @RequestAttribute("tenantId") String tenantId,
            @PathVariable String clientAppId,
            @PathVariable String grantId,
            @RequestParam String status) {
        return registryService.updateGrantStatus(tenantId, clientAppId, grantId, status);
    }

    @RequireAuth(roles = "TENANT_ADMIN")
    @GetMapping("/client-apps/{clientAppId}/visible-functions")
    public List<BusinessFunctionSummaryDTO> listClientAppVisibleFunctionSummaries(
            @RequestAttribute("tenantId") String tenantId,
            @PathVariable String clientAppId) {
        return registryService.listClientAppVisibleFunctionSummaries(tenantId, clientAppId);
    }
}
