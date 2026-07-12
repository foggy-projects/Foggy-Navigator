package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.model.dto.CodexAppServerEndpointDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexAppServerEndpointSyncDTO;
import com.foggy.navigator.codex.worker.model.form.CodexAppServerEndpointForm;
import com.foggy.navigator.codex.worker.service.CodexAppServerEndpointService;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexAppServerEndpointControllerTest {

    @Mock
    private CodexAppServerEndpointService endpointService;
    @Mock
    private WorkerManagementFacade workerManagementFacade;

    private CodexAppServerEndpointController controller;

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .build());
        controller = new CodexAppServerEndpointController(endpointService, workerManagementFacade);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createAndListRequirePhysicalWorkerOwnership() {
        CodexAppServerEndpointForm form = new CodexAppServerEndpointForm();
        form.setWorkerId("worker-1");
        CodexAppServerEndpointDTO endpoint = CodexAppServerEndpointDTO.builder()
                .endpointId("endpoint-1")
                .workerId("worker-1")
                .build();
        when(endpointService.create(form)).thenReturn(endpoint);
        when(endpointService.listByWorker("worker-1")).thenReturn(List.of(endpoint));

        assertEquals("endpoint-1", controller.create(form).getData().getEndpointId());
        assertEquals(1, controller.list("worker-1").getData().size());

        verify(workerManagementFacade, times(2))
                .validatePhysicalWorkerOwnership("user-1", "worker-1");
        verify(endpointService).create(form);
        verify(endpointService).listByWorker("worker-1");
    }

    @Test
    void createStopsBeforePersistenceWhenOwnershipValidationFails() {
        CodexAppServerEndpointForm form = new CodexAppServerEndpointForm();
        form.setWorkerId("worker-1");
        doThrow(new IllegalArgumentException("forbidden"))
                .when(workerManagementFacade)
                .validatePhysicalWorkerOwnership("user-1", "worker-1");

        assertThrows(IllegalArgumentException.class, () -> controller.create(form));

        verify(endpointService, never()).create(form);
    }

    @Test
    void updateUsesPersistedEndpointOwnerInsteadOfRequestWorkerId() {
        CodexAppServerEndpointForm form = new CodexAppServerEndpointForm();
        form.setWorkerId("spoofed-worker");
        CodexAppServerEndpointDTO updated = CodexAppServerEndpointDTO.builder()
                .endpointId("endpoint-1")
                .workerId("worker-1")
                .build();
        when(endpointService.ownerWorkerId("endpoint-1")).thenReturn("worker-1");
        when(endpointService.update("endpoint-1", form)).thenReturn(updated);

        assertEquals("worker-1", controller.update("endpoint-1", form).getData().getWorkerId());

        verify(workerManagementFacade).validatePhysicalWorkerOwnership("user-1", "worker-1");
        verify(endpointService).update("endpoint-1", form);
    }

    @Test
    void deleteAndSyncResolveOwnerBeforePerformingOperation() {
        CodexAppServerEndpointSyncDTO sync = CodexAppServerEndpointSyncDTO.builder()
                .runtimeCreated(false)
                .build();
        when(endpointService.ownerWorkerId("endpoint-1")).thenReturn("worker-1");
        when(endpointService.synchronize("endpoint-1")).thenReturn(sync);

        controller.delete("endpoint-1");
        assertEquals(sync, controller.synchronize("endpoint-1").getData());

        verify(endpointService).delete("endpoint-1");
        verify(endpointService).synchronize("endpoint-1");
    }

    @Test
    void endpointMutationStopsWhenPersistedOwnerValidationFails() {
        CodexAppServerEndpointForm form = new CodexAppServerEndpointForm();
        when(endpointService.ownerWorkerId("endpoint-1")).thenReturn("worker-1");
        doThrow(new IllegalArgumentException("forbidden"))
                .when(workerManagementFacade)
                .validatePhysicalWorkerOwnership("user-1", "worker-1");

        assertThrows(IllegalArgumentException.class,
                () -> controller.update("endpoint-1", form));
        assertThrows(IllegalArgumentException.class,
                () -> controller.delete("endpoint-1"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.synchronize("endpoint-1"));

        verify(endpointService, never()).update("endpoint-1", form);
        verify(endpointService, never()).delete("endpoint-1");
        verify(endpointService, never()).synchronize("endpoint-1");
    }
}
