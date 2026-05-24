package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.AgentWorkspaceBindingDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.form.BindAgentWorkspaceForm;
import com.foggy.navigator.business.agent.service.AgentWorkspaceBindingService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class AgentWorkspaceBindingControllerTest {

    @Test
    void bind_requiresClientAppWorkspaceBindingScope() {
        AgentWorkspaceBindingService bindingService = mock(AgentWorkspaceBindingService.class);
        ClientAppControlCredentialService credentialService = mock(ClientAppControlCredentialService.class);
        AgentWorkspaceBindingController controller =
                new AgentWorkspaceBindingController(bindingService, credentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        BindAgentWorkspaceForm form = new BindAgentWorkspaceForm();
        form.setDirectoryId("dir-1");
        AgentWorkspaceBindingDTO dto = new AgentWorkspaceBindingDTO();
        dto.setDirectoryId("dir-1");

        when(credentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_AGENT_WORKSPACE_BINDING_MANAGE),
                eq("capp-1")))
                .thenReturn(principal());
        when(bindingService.bind("tenant-1", "capp-1", "agent-1", form)).thenReturn(dto);

        RX<AgentWorkspaceBindingDTO> result = controller.bind(request, "capp-1", "agent-1", form);

        assertEquals("dir-1", result.getData().getDirectoryId());
        verify(bindingService).bind("tenant-1", "capp-1", "agent-1", form);
    }

    @Test
    void unbind_usesPrincipalTenant() {
        AgentWorkspaceBindingService bindingService = mock(AgentWorkspaceBindingService.class);
        ClientAppControlCredentialService credentialService = mock(ClientAppControlCredentialService.class);
        AgentWorkspaceBindingController controller =
                new AgentWorkspaceBindingController(bindingService, credentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(credentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_AGENT_WORKSPACE_BINDING_MANAGE),
                eq("capp-1")))
                .thenReturn(principal());

        controller.unbind(request, "capp-1", "agent-1", "dir-1");

        verify(bindingService).unbind("tenant-1", "capp-1", "agent-1", "dir-1");
    }

    private ClientAppControlPlanePrincipal principal() {
        return ClientAppControlPlanePrincipal.builder()
                .tenantId("tenant-1")
                .clientAppId("capp-1")
                .scopes(Set.of(ClientAppControlCredentialService.SCOPE_AGENT_WORKSPACE_BINDING_MANAGE))
                .build();
    }
}
