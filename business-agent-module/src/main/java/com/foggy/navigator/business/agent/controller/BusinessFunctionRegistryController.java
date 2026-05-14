package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BusinessFunctionSummaryDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppFunctionGrantDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.form.GrantBusinessFunctionForm;
import com.foggy.navigator.business.agent.model.form.ImportBusinessFunctionManifestForm;
import com.foggy.navigator.business.agent.service.BusinessFunctionRegistryService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/business-agent")
@RequiredArgsConstructor
public class BusinessFunctionRegistryController {

    private final BusinessFunctionRegistryService registryService;
    private final ClientAppControlCredentialService controlCredentialService;

    @PostMapping("/functions/import")
    public void importManifest(
            HttpServletRequest request,
            @RequestBody ImportBusinessFunctionManifestForm form) {
        ClientAppControlPlanePrincipal principal = controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_FUNCTION_MANIFEST_IMPORT, null);
        registryService.importManifest(principal.getTenantId(), principal.getActorUserId(), form);
    }



    @PostMapping("/client-apps/{clientAppId}/function-grants")
    public ClientAppFunctionGrantDTO grantFunctionToClientApp(
            HttpServletRequest request,
            @PathVariable String clientAppId,
            @RequestBody GrantBusinessFunctionForm form) {
        ClientAppControlPlanePrincipal principal = controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_FUNCTION_GRANT_MANAGE, clientAppId);
        return registryService.grantFunctionToClientApp(principal.getTenantId(), clientAppId, principal.getActorUserId(), form);
    }



    @PutMapping("/client-apps/{clientAppId}/function-grants/{grantId}/status")
    public ClientAppFunctionGrantDTO updateGrantStatus(
            HttpServletRequest request,
            @PathVariable String clientAppId,
            @PathVariable String grantId,
            @RequestParam String status) {
        ClientAppControlPlanePrincipal principal = controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_FUNCTION_GRANT_MANAGE, clientAppId);
        return registryService.updateGrantStatus(principal.getTenantId(), clientAppId, grantId, status);
    }

    @GetMapping("/client-apps/{clientAppId}/visible-functions")
    public List<BusinessFunctionSummaryDTO> listClientAppVisibleFunctionSummaries(
            HttpServletRequest request,
            @PathVariable String clientAppId) {
        ClientAppControlPlanePrincipal principal = controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_FUNCTION_GRANT_MANAGE, clientAppId);
        return registryService.listClientAppVisibleFunctionSummaries(principal.getTenantId(), clientAppId);
    }
}
