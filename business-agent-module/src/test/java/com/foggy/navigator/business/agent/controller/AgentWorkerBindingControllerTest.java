package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.AgentWorkerBindingDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.form.BindAgentWorkerForm;
import com.foggy.navigator.business.agent.service.AgentWorkerBindingService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class AgentWorkerBindingControllerTest {

    @Test
    void bind_requiresClientAppWorkerBindingScope() {
        AgentWorkerBindingService bindingService = mock(AgentWorkerBindingService.class);
        ClientAppControlCredentialService credentialService = mock(ClientAppControlCredentialService.class);
        AgentWorkerBindingController controller =
                new AgentWorkerBindingController(bindingService, credentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        BindAgentWorkerForm form = new BindAgentWorkerForm();
        form.setWorkerPoolId("pool-1");
        AgentWorkerBindingDTO dto = new AgentWorkerBindingDTO();
        dto.setWorkerPoolId("pool-1");

        when(credentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_AGENT_WORKER_BINDING_MANAGE),
                eq("capp-1")))
                .thenReturn(principal());
        when(bindingService.bind("tenant-1", "capp-1", "agent-1", form)).thenReturn(dto);

        RX<AgentWorkerBindingDTO> result = controller.bind(request, "capp-1", "agent-1", form);

        assertEquals("pool-1", result.getData().getWorkerPoolId());
        verify(bindingService).bind("tenant-1", "capp-1", "agent-1", form);
    }

    @Test
    void list_usesPrincipalTenant() {
        AgentWorkerBindingService bindingService = mock(AgentWorkerBindingService.class);
        ClientAppControlCredentialService credentialService = mock(ClientAppControlCredentialService.class);
        AgentWorkerBindingController controller =
                new AgentWorkerBindingController(bindingService, credentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(credentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_AGENT_WORKER_BINDING_MANAGE),
                eq("capp-1")))
                .thenReturn(principal());
        when(bindingService.list("tenant-1", "capp-1", "agent-1")).thenReturn(List.of(new AgentWorkerBindingDTO()));

        RX<List<AgentWorkerBindingDTO>> result = controller.list(request, "capp-1", "agent-1");

        assertEquals(1, result.getData().size());
        verify(bindingService).list("tenant-1", "capp-1", "agent-1");
    }

    @Test
    void unbind_usesPrincipalTenant() {
        AgentWorkerBindingService bindingService = mock(AgentWorkerBindingService.class);
        ClientAppControlCredentialService credentialService = mock(ClientAppControlCredentialService.class);
        AgentWorkerBindingController controller =
                new AgentWorkerBindingController(bindingService, credentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(credentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_AGENT_WORKER_BINDING_MANAGE),
                eq("capp-1")))
                .thenReturn(principal());

        controller.unbind(request, "capp-1", "agent-1", "pool-1");

        verify(bindingService).unbind("tenant-1", "capp-1", "agent-1", "pool-1");
    }

    private ClientAppControlPlanePrincipal principal() {
        return ClientAppControlPlanePrincipal.builder()
                .tenantId("tenant-1")
                .clientAppId("capp-1")
                .principalType("CLIENT_APP")
                .principalId("capp-1")
                .scopes(Set.of(ClientAppControlCredentialService.SCOPE_AGENT_WORKER_BINDING_MANAGE))
                .build();
    }
}
