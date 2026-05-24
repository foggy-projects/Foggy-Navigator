package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BizWorkerIdentityDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.RegisterWorkerIdentityForm;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpstreamAdminWorkerIdentityControllerTest {

    @Test
    void registerWorkerIdentity_usesUpstreamSystemOwner() {
        UpstreamClientAppAdminCredentialService adminCredentialService =
                mock(UpstreamClientAppAdminCredentialService.class);
        BizWorkerPoolService workerPoolService = mock(BizWorkerPoolService.class);
        UpstreamAdminWorkerIdentityController controller =
                new UpstreamAdminWorkerIdentityController(adminCredentialService, workerPoolService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        RegisterWorkerIdentityForm form = new RegisterWorkerIdentityForm();
        form.setWorkerId("lgw-1");
        form.setWorkerBackend("LANGGRAPH_BIZ");
        form.setBaseUrl("http://127.0.0.1:3061");

        UpstreamClientAppAdminPrincipal principal = UpstreamClientAppAdminPrincipal.builder()
                .credentialId("cred-1")
                .upstreamSystemId("ups-1")
                .authorizedTenantIds(Set.of("tenant-1"))
                .scopes(Set.of(UpstreamBootstrapRequestService.SCOPE_WORKER_POOL_MANAGE))
                .build();
        BizWorkerIdentityDTO dto = new BizWorkerIdentityDTO();
        dto.setWorkerId("lgw-1");
        dto.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        dto.setOwnerId("ups-1");
        when(adminCredentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_WORKER_POOL_MANAGE)))
                .thenReturn(principal);
        when(workerPoolService.registerWorkerIdentity(
                eq(ResourceOwnerType.UPSTREAM_SYSTEM),
                eq("ups-1"),
                same(form)))
                .thenReturn(dto);

        RX<BizWorkerIdentityDTO> result = controller.registerWorkerIdentity(request, form);

        assertEquals("lgw-1", result.getData().getWorkerId());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.getData().getOwnerType());
        assertEquals("ups-1", result.getData().getOwnerId());
        verify(workerPoolService).registerWorkerIdentity(
                ResourceOwnerType.UPSTREAM_SYSTEM,
                "ups-1",
                form);
    }
}
