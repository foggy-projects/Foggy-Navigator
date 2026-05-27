package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BusinessAgentBundleDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.UpstreamAgentForm;
import com.foggy.navigator.business.agent.service.UpstreamAdminAgentService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class UpstreamAdminAgentControllerTest {

    @Test
    void create_requiresAgentManageScopeAndResolvesSingleTenant() {
        UpstreamClientAppAdminCredentialService credentialService =
                mock(UpstreamClientAppAdminCredentialService.class);
        UpstreamAdminAgentService agentService = mock(UpstreamAdminAgentService.class);
        UpstreamAdminAgentController controller = new UpstreamAdminAgentController(credentialService, agentService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        UpstreamAgentForm form = new UpstreamAgentForm();
        form.setAgentId("agent-1");
        BusinessAgentBundleDTO dto = new BusinessAgentBundleDTO();
        dto.setAgentId("agent-1");
        UpstreamClientAppAdminPrincipal principal = principal(Set.of("tenant-1"));

        when(credentialService.requireAccess(same(request), eq(UpstreamBootstrapRequestService.SCOPE_AGENT_MANAGE)))
                .thenReturn(principal);
        when(agentService.create("tenant-1", principal, form)).thenReturn(dto);

        RX<BusinessAgentBundleDTO> result = controller.create(request, null, form);

        assertEquals("agent-1", result.getData().getAgentId());
        verify(agentService).create("tenant-1", principal, form);
        verify(credentialService, never()).requireTenant(any(), anyString());
    }

    @Test
    void list_usesExplicitTenantForMultiTenantCredential() {
        UpstreamClientAppAdminCredentialService credentialService =
                mock(UpstreamClientAppAdminCredentialService.class);
        UpstreamAdminAgentService agentService = mock(UpstreamAdminAgentService.class);
        UpstreamAdminAgentController controller = new UpstreamAdminAgentController(credentialService, agentService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        UpstreamClientAppAdminPrincipal principal = principal(Set.of("tenant-1", "tenant-2"));

        when(credentialService.requireAccess(same(request), eq(UpstreamBootstrapRequestService.SCOPE_AGENT_MANAGE)))
                .thenReturn(principal);
        when(agentService.list("tenant-2", principal)).thenReturn(List.of(new BusinessAgentBundleDTO()));

        RX<List<BusinessAgentBundleDTO>> result = controller.list(request, "tenant-2");

        assertEquals(1, result.getData().size());
        verify(credentialService).requireTenant(principal, "tenant-2");
        verify(agentService).list("tenant-2", principal);
    }

    private UpstreamClientAppAdminPrincipal principal(Set<String> tenantIds) {
        return UpstreamClientAppAdminPrincipal.builder()
                .credentialId("cred-1")
                .principalId("ups-principal")
                .upstreamSystemId("ups-1")
                .authorizedTenantIds(tenantIds)
                .scopes(Set.of(UpstreamBootstrapRequestService.SCOPE_AGENT_MANAGE))
                .build();
    }
}
