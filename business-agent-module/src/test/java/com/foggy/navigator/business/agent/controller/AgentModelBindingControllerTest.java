package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.AgentModelBindingDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.form.BindAgentModelForm;
import com.foggy.navigator.business.agent.service.AgentModelBindingService;
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

class AgentModelBindingControllerTest {

    @Test
    void bind_requiresAgentModelBindingScopeAndUsesPrincipalTenant() {
        AgentModelBindingService bindingService = mock(AgentModelBindingService.class);
        ClientAppControlCredentialService credentialService = mock(ClientAppControlCredentialService.class);
        AgentModelBindingController controller = new AgentModelBindingController(bindingService, credentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        BindAgentModelForm form = new BindAgentModelForm();
        form.setModelConfigId("cfg-1");
        AgentModelBindingDTO dto = new AgentModelBindingDTO();
        dto.setModelConfigId("cfg-1");

        when(credentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_AGENT_MODEL_BINDING_MANAGE),
                eq("capp-1")))
                .thenReturn(principal());
        when(bindingService.bind("tenant-1", "capp-1", "agent-1", form)).thenReturn(dto);

        RX<AgentModelBindingDTO> result = controller.bind(request, "capp-1", "agent-1", form);

        assertEquals("cfg-1", result.getData().getModelConfigId());
        verify(bindingService).bind("tenant-1", "capp-1", "agent-1", form);
    }

    @Test
    void list_requiresAgentModelBindingScope() {
        AgentModelBindingService bindingService = mock(AgentModelBindingService.class);
        ClientAppControlCredentialService credentialService = mock(ClientAppControlCredentialService.class);
        AgentModelBindingController controller = new AgentModelBindingController(bindingService, credentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(credentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_AGENT_MODEL_BINDING_MANAGE),
                eq("capp-1")))
                .thenReturn(principal());
        when(bindingService.list("tenant-1", "capp-1", "agent-1")).thenReturn(List.of(new AgentModelBindingDTO()));

        RX<List<AgentModelBindingDTO>> result = controller.list(request, "capp-1", "agent-1");

        assertEquals(1, result.getData().size());
        verify(bindingService).list("tenant-1", "capp-1", "agent-1");
    }

    @Test
    void setDefault_requiresAgentModelBindingScope() {
        AgentModelBindingService bindingService = mock(AgentModelBindingService.class);
        ClientAppControlCredentialService credentialService = mock(ClientAppControlCredentialService.class);
        AgentModelBindingController controller = new AgentModelBindingController(bindingService, credentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        BindAgentModelForm form = new BindAgentModelForm();
        form.setModelConfigId("cfg-2");
        AgentModelBindingDTO dto = new AgentModelBindingDTO();
        dto.setModelConfigId("cfg-2");
        dto.setDefaultModel(true);

        when(credentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_AGENT_MODEL_BINDING_MANAGE),
                eq("capp-1")))
                .thenReturn(principal());
        when(bindingService.setDefault("tenant-1", "capp-1", "agent-1", form)).thenReturn(dto);

        RX<AgentModelBindingDTO> result = controller.setDefault(request, "capp-1", "agent-1", form);

        assertEquals("cfg-2", result.getData().getModelConfigId());
        verify(bindingService).setDefault("tenant-1", "capp-1", "agent-1", form);
    }

    private ClientAppControlPlanePrincipal principal() {
        return ClientAppControlPlanePrincipal.builder()
                .tenantId("tenant-1")
                .clientAppId("capp-1")
                .principalType("CLIENT_APP")
                .principalId("capp-1")
                .scopes(Set.of(ClientAppControlCredentialService.SCOPE_AGENT_MODEL_BINDING_MANAGE))
                .build();
    }
}
