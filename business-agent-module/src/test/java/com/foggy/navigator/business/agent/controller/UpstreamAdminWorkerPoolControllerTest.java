package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BizWorkerPoolDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.AddWorkerPoolMemberForm;
import com.foggy.navigator.business.agent.model.form.UpdateStatusForm;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpstreamAdminWorkerPoolControllerTest {

    private UpstreamClientAppAdminCredentialService adminCredentialService;
    private BizWorkerPoolService workerPoolService;
    private UpstreamAdminWorkerPoolController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        adminCredentialService = mock(UpstreamClientAppAdminCredentialService.class);
        workerPoolService = mock(BizWorkerPoolService.class);
        controller = new UpstreamAdminWorkerPoolController(adminCredentialService, workerPoolService);
        request = new MockHttpServletRequest();
        UpstreamClientAppAdminPrincipal principal = UpstreamClientAppAdminPrincipal.builder()
                .credentialId("cred-1")
                .upstreamSystemId("ups-1")
                .authorizedTenantIds(Set.of("tenant-1"))
                .scopes(Set.of(UpstreamBootstrapRequestService.SCOPE_WORKER_POOL_MANAGE))
                .build();
        when(adminCredentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_WORKER_POOL_MANAGE)))
                .thenReturn(principal);
    }

    @Test
    void listPools_usesAuthenticatedUpstreamOwnerScope() {
        when(workerPoolService.listPools(
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1"))
                .thenReturn(List.of());

        controller.listPools(request, "tenant-1");

        verify(adminCredentialService).requireTenant(
                org.mockito.ArgumentMatchers.any(UpstreamClientAppAdminPrincipal.class),
                eq("tenant-1"));
        verify(workerPoolService).listPools(
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");
    }

    @Test
    void addMember_usesAuthenticatedUpstreamOwnerScope() {
        AddWorkerPoolMemberForm form = new AddWorkerPoolMemberForm();
        form.setWorkerId("worker-1");

        controller.addMember(request, "tenant-1", "pool-1", form);

        verify(workerPoolService).addMember(
                "tenant-1",
                ResourceOwnerType.UPSTREAM_SYSTEM,
                "ups-1",
                "pool-1",
                form);
    }

    @Test
    void updateStatus_usesAuthenticatedUpstreamOwnerScope() {
        UpdateStatusForm form = new UpdateStatusForm();
        form.setStatus(BizWorkerPoolService.STATUS_DISABLED);
        BizWorkerPoolDTO expected = new BizWorkerPoolDTO();
        when(workerPoolService.updatePoolStatus(
                "tenant-1",
                ResourceOwnerType.UPSTREAM_SYSTEM,
                "ups-1",
                "pool-1",
                BizWorkerPoolService.STATUS_DISABLED))
                .thenReturn(expected);

        BizWorkerPoolDTO actual = controller
                .updatePoolStatus(request, "tenant-1", "pool-1", form)
                .getData();

        assertSame(expected, actual);
        verify(workerPoolService).updatePoolStatus(
                "tenant-1",
                ResourceOwnerType.UPSTREAM_SYSTEM,
                "ups-1",
                "pool-1",
                BizWorkerPoolService.STATUS_DISABLED);
    }
}
