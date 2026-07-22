package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class UpstreamAdminClientAppScopeServiceTest {

    @Test
    void resolvesExplicitTargetIntoClientAppBoundPrincipalAndSafeDiagnostics() {
        UpstreamClientAppAdminCredentialService credentialService = mock(UpstreamClientAppAdminCredentialService.class);
        UpstreamClientAppManagementService managementService = mock(UpstreamClientAppManagementService.class);
        UpstreamAdminClientAppScopeService service = new UpstreamAdminClientAppScopeService(credentialService, managementService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        UpstreamClientAppAdminPrincipal admin = UpstreamClientAppAdminPrincipal.builder()
                .credentialId("cred-1").upstreamSystemId("foggy-world-sim")
                .authorizedClientAppNamespace("foggy-world-sim")
                .authorizedTenantIds(Set.of("sim"))
                .scopes(Set.of(UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE))
                .build();
        ClientAppEntity app = new ClientAppEntity();
        app.setTenantId("nav_foggy-world-sim_sim");
        app.setClientAppId("capp-sim");
        app.setUpstreamSystemId("foggy-world-sim");
        app.setUpstreamClientAppNamespace("foggy-world-sim");

        when(credentialService.requireAccess(same(request), eq(UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE)))
                .thenReturn(admin);
        when(managementService.requireManagedActiveClientApp(admin, "capp-sim")).thenReturn(app);

        var diagnostic = service.inspect(request, "capp-sim");

        assertEquals("UPSTREAM_SYSTEM_ADMIN", diagnostic.getCredentialLane());
        assertEquals("nav_foggy-world-sim_sim", diagnostic.getTenantId());
        assertEquals("CLIENT_APP", diagnostic.getTargetOwnerType());
        assertEquals("capp-sim", diagnostic.getTargetOwnerId());
        assertEquals(Set.of("EXPLICIT_CLIENT_APP_TARGET", "TENANT_AUTHORIZED", "UPSTREAM_SYSTEM_MATCH",
                "CLIENT_APP_NAMESPACE_MATCH", "CLIENT_APP_ACTIVE"), Set.copyOf(diagnostic.getAuthorizationChecks()));
        verify(managementService).requireManagedActiveClientApp(admin, "capp-sim");
    }
}
