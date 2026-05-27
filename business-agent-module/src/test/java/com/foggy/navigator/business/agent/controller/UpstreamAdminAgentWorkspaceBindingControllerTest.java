package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.AgentWorkspaceBindingDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.BindAgentWorkspaceForm;
import com.foggy.navigator.business.agent.service.AgentWorkspaceBindingService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class UpstreamAdminAgentWorkspaceBindingControllerTest {

    @Test
    void bind_requiresUpstreamAdminWorkspaceBindingScopeAndResolvesTenant() {
        UpstreamClientAppAdminCredentialService credentialService =
                mock(UpstreamClientAppAdminCredentialService.class);
        AgentWorkspaceBindingService bindingService = mock(AgentWorkspaceBindingService.class);
        UpstreamAdminAgentWorkspaceBindingController controller =
                new UpstreamAdminAgentWorkspaceBindingController(credentialService, bindingService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        BindAgentWorkspaceForm form = new BindAgentWorkspaceForm();
        form.setDirectoryId("dir-1");
        AgentWorkspaceBindingDTO dto = new AgentWorkspaceBindingDTO();
        dto.setDirectoryId("dir-1");

        UpstreamClientAppAdminPrincipal principal = principal(Set.of("tenant-1"));
        when(credentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_AGENT_WORKSPACE_BINDING_MANAGE)))
                .thenReturn(principal);
        when(bindingService.bindSystemOwned("tenant-1", principal, "agent-1", form)).thenReturn(dto);

        RX<AgentWorkspaceBindingDTO> result = controller.bind(request, "agent-1", null, form);

        assertEquals("dir-1", result.getData().getDirectoryId());
        verify(bindingService).bindSystemOwned("tenant-1", principal, "agent-1", form);
        verify(credentialService, never()).requireTenant(any(), anyString());
    }

    @Test
    void setDefault_usesExplicitTargetTenantWhenProvided() {
        UpstreamClientAppAdminCredentialService credentialService =
                mock(UpstreamClientAppAdminCredentialService.class);
        AgentWorkspaceBindingService bindingService = mock(AgentWorkspaceBindingService.class);
        UpstreamAdminAgentWorkspaceBindingController controller =
                new UpstreamAdminAgentWorkspaceBindingController(credentialService, bindingService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        BindAgentWorkspaceForm form = new BindAgentWorkspaceForm();
        form.setDirectoryId("dir-2");
        AgentWorkspaceBindingDTO dto = new AgentWorkspaceBindingDTO();
        dto.setDirectoryId("dir-2");

        UpstreamClientAppAdminPrincipal principal = principal(Set.of("tenant-1", "tenant-2"));
        when(credentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_AGENT_WORKSPACE_BINDING_MANAGE)))
                .thenReturn(principal);
        when(bindingService.setSystemOwnedDefault("tenant-2", principal, "agent-1", form)).thenReturn(dto);

        RX<AgentWorkspaceBindingDTO> result = controller.setDefault(request, "agent-1", "tenant-2", form);

        assertEquals("dir-2", result.getData().getDirectoryId());
        verify(credentialService).requireTenant(principal, "tenant-2");
        verify(bindingService).setSystemOwnedDefault("tenant-2", principal, "agent-1", form);
    }

    private UpstreamClientAppAdminPrincipal principal(Set<String> tenantIds) {
        return UpstreamClientAppAdminPrincipal.builder()
                .credentialId("cred-1")
                .upstreamSystemId("ups-1")
                .authorizedTenantIds(tenantIds)
                .scopes(Set.of(UpstreamBootstrapRequestService.SCOPE_AGENT_WORKSPACE_BINDING_MANAGE))
                .build();
    }
}
