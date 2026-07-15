package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BizWorkerCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.RotateWorkerCredentialForm;
import com.foggy.navigator.business.agent.service.BizWorkerCredentialService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpstreamAdminWorkerCredentialControllerTest {

    private UpstreamClientAppAdminCredentialService adminCredentialService;
    private BizWorkerCredentialService workerCredentialService;
    private UpstreamAdminWorkerCredentialController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        adminCredentialService = mock(UpstreamClientAppAdminCredentialService.class);
        workerCredentialService = mock(BizWorkerCredentialService.class);
        controller = new UpstreamAdminWorkerCredentialController(
                adminCredentialService, workerCredentialService);
        request = new MockHttpServletRequest();
        when(adminCredentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_WORKER_MANAGE)))
                .thenReturn(UpstreamClientAppAdminPrincipal.builder()
                        .credentialId("admin-cred-1")
                        .upstreamSystemId("ups-1")
                        .scopes(Set.of(UpstreamBootstrapRequestService.SCOPE_WORKER_MANAGE))
                        .build());
    }

    @Test
    void rotateUsesAuthenticatedUpstreamSystemOwner() {
        RotateWorkerCredentialForm form = new RotateWorkerCredentialForm();
        form.setTtlSeconds(600L);
        BizWorkerCredentialDTO expected = new BizWorkerCredentialDTO();
        expected.setWorkerId("worker-1");
        expected.setSecret("bwc_once");
        when(workerCredentialService.rotateCredential(
                ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1", "worker-1", 600L))
                .thenReturn(expected);

        MockHttpServletResponse response = new MockHttpServletResponse();
        RX<BizWorkerCredentialDTO> result = controller.rotate(
                request, response, "worker-1", form);

        assertEquals("bwc_once", result.getData().getSecret());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        verify(adminCredentialService).requireAccess(
                request, UpstreamBootstrapRequestService.SCOPE_WORKER_MANAGE);
        verify(workerCredentialService).rotateCredential(
                ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1", "worker-1", 600L);
    }

    @Test
    void revokeUsesAuthenticatedUpstreamSystemOwner() {
        BizWorkerCredentialDTO expected = new BizWorkerCredentialDTO();
        expected.setWorkerId("worker-1");
        when(workerCredentialService.revokeCredential(
                ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1", "worker-1"))
                .thenReturn(expected);

        RX<BizWorkerCredentialDTO> result = controller.revoke(request, "worker-1");

        assertEquals("worker-1", result.getData().getWorkerId());
        verify(adminCredentialService).requireAccess(
                request, UpstreamBootstrapRequestService.SCOPE_WORKER_MANAGE);
        verify(workerCredentialService).revokeCredential(
                ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1", "worker-1");
    }
}
