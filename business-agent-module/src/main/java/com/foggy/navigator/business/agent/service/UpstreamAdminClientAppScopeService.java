package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.dto.UpstreamAdminClientAppScopeDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Explicit S1 system-admin facade for one ClientApp. It never accepts a caller supplied
 * tenant: the tenant is derived from a ClientApp that has passed upstream/namespace checks.
 */
@Service
@RequiredArgsConstructor
public class UpstreamAdminClientAppScopeService {

    public static final String PRINCIPAL_TYPE = "UPSTREAM_SYSTEM_ADMIN";
    public static final String CREDENTIAL_LANE = "UPSTREAM_SYSTEM_ADMIN";

    private final UpstreamClientAppAdminCredentialService adminCredentialService;
    private final UpstreamClientAppManagementService clientAppManagementService;

    public ResolvedScope requireScope(HttpServletRequest request, String clientAppId, String requiredScope) {
        UpstreamClientAppAdminPrincipal admin = adminCredentialService.requireAccess(request, requiredScope);
        ClientAppEntity clientApp = clientAppManagementService.requireManagedActiveClientApp(admin, clientAppId);
        ClientAppControlPlanePrincipal principal = ClientAppControlPlanePrincipal.builder()
                .admin(true)
                .tenantId(clientApp.getTenantId())
                .clientAppId(clientApp.getClientAppId())
                .credentialId(admin.getCredentialId())
                .actorUserId("upstream-admin:" + admin.getCredentialId())
                .principalType(PRINCIPAL_TYPE)
                .principalId(admin.getUpstreamSystemId())
                .scopes(admin.getScopes())
                .build();
        return new ResolvedScope(admin, clientApp, principal);
    }

    public UpstreamAdminClientAppScopeDTO inspect(HttpServletRequest request, String clientAppId) {
        ResolvedScope scope = requireScope(request, clientAppId,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE);
        return toDiagnostic(scope);
    }

    public UpstreamAdminClientAppScopeDTO toDiagnostic(ResolvedScope scope) {
        return UpstreamAdminClientAppScopeDTO.builder()
                .credentialLane(CREDENTIAL_LANE)
                .principalType(PRINCIPAL_TYPE)
                .upstreamSystemId(scope.admin().getUpstreamSystemId())
                .tenantId(scope.clientApp().getTenantId())
                .clientAppId(scope.clientApp().getClientAppId())
                .clientAppNamespace(scope.clientApp().getUpstreamClientAppNamespace())
                .targetOwnerType("CLIENT_APP")
                .targetOwnerId(scope.clientApp().getClientAppId())
                .authorizationChecks(List.of(
                        "EXPLICIT_CLIENT_APP_TARGET",
                        "TENANT_AUTHORIZED",
                        "UPSTREAM_SYSTEM_MATCH",
                        "CLIENT_APP_NAMESPACE_MATCH",
                        "CLIENT_APP_ACTIVE"))
                .build();
    }

    public record ResolvedScope(UpstreamClientAppAdminPrincipal admin,
                                ClientAppEntity clientApp,
                                ClientAppControlPlanePrincipal principal) {
    }
}
