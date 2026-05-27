package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.ClientAppModelConfigForm;
import com.foggy.navigator.business.agent.service.UpstreamAdminModelConfigService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class UpstreamAdminModelConfigControllerTest {

    @Test
    void create_requiresUpstreamAdminModelScopeAndResolvesTenant() {
        UpstreamClientAppAdminCredentialService adminCredentialService =
                mock(UpstreamClientAppAdminCredentialService.class);
        UpstreamAdminModelConfigService modelConfigService = mock(UpstreamAdminModelConfigService.class);
        UpstreamAdminModelConfigController controller =
                new UpstreamAdminModelConfigController(adminCredentialService, modelConfigService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        ClientAppModelConfigForm form = new ClientAppModelConfigForm();
        form.setName("Shared GPT");

        UpstreamClientAppAdminPrincipal principal = UpstreamClientAppAdminPrincipal.builder()
                .credentialId("cred-1")
                .upstreamSystemId("ups-1")
                .authorizedTenantIds(Set.of("tenant-1"))
                .scopes(Set.of(UpstreamBootstrapRequestService.SCOPE_MODEL_CONFIG_MANAGE))
                .build();
        LlmModelConfigDTO dto = new LlmModelConfigDTO();
        dto.setId("model-1");
        when(adminCredentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_MODEL_CONFIG_MANAGE)))
                .thenReturn(principal);
        when(modelConfigService.create("tenant-1", principal, form)).thenReturn(dto);

        RX<LlmModelConfigDTO> result = controller.create(request, null, form);

        assertEquals("model-1", result.getData().getId());
        verify(modelConfigService).create("tenant-1", principal, form);
        verify(adminCredentialService, never()).requireTenant(any(), anyString());
    }
}
