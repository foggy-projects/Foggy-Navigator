package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.form.AddWorkerPoolMemberForm;
import com.foggy.navigator.business.agent.model.form.CreateWorkerPoolForm;
import com.foggy.navigator.business.agent.model.form.UpdateStatusForm;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BizWorkerPoolControllerTest {

    private BizWorkerPoolService workerPoolService;
    private BizWorkerPoolController controller;

    @BeforeEach
    void setUp() {
        workerPoolService = mock(BizWorkerPoolService.class);
        controller = new BizWorkerPoolController(workerPoolService);
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .roles("TENANT_ADMIN")
                .build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void listPools_usesTenantOwnedPlatformScope() {
        when(workerPoolService.listPools(
                "tenant-1", ResourceOwnerType.PLATFORM, "tenant-1"))
                .thenReturn(List.of());

        controller.listPools();

        verify(workerPoolService).listPools(
                "tenant-1", ResourceOwnerType.PLATFORM, "tenant-1");
    }

    @Test
    void createPool_usesTenantOwnedPlatformDefault() {
        CreateWorkerPoolForm form = new CreateWorkerPoolForm();

        controller.createPool(form);

        verify(workerPoolService).createPool("tenant-1", form);
    }

    @Test
    void addMember_usesTenantOwnedPlatformScope() {
        AddWorkerPoolMemberForm form = new AddWorkerPoolMemberForm();
        form.setWorkerId("worker-1");

        controller.addMember("pool-1", form);

        verify(workerPoolService).addMember(
                "tenant-1",
                ResourceOwnerType.PLATFORM,
                "tenant-1",
                "pool-1",
                form);
    }

    @Test
    void updateStatus_usesTenantOwnedPlatformScope() {
        UpdateStatusForm form = new UpdateStatusForm();
        form.setStatus(BizWorkerPoolService.STATUS_DISABLED);

        controller.updatePoolStatus("pool-1", form);

        verify(workerPoolService).updatePoolStatus(
                "tenant-1",
                ResourceOwnerType.PLATFORM,
                "tenant-1",
                "pool-1",
                BizWorkerPoolService.STATUS_DISABLED);
    }
}
