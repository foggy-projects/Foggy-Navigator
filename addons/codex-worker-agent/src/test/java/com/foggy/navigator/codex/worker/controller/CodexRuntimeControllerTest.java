package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeDTO;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRegistryService;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexRuntimeControllerTest {

    @Mock
    private CodexRuntimeRegistryService runtimeRegistryService;

    @Mock
    private WorkerManagementFacade workerManagementFacade;

    private CodexRuntimeController controller;

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUser(CurrentUser.builder().userId("user-1").build());
        controller = new CodexRuntimeController(runtimeRegistryService, workerManagementFacade);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void recoverInstanceRequiresAndUsesRuntimeOwner() {
        CodexRuntimeDTO recovered = CodexRuntimeDTO.builder()
                .runtimeId("app-main")
                .revision(1)
                .readinessStatus("READY")
                .build();
        when(runtimeRegistryService.ownerWorkerId("app-main", 1)).thenReturn("worker-1");
        when(runtimeRegistryService.recoverInstanceQuarantine("app-main", 1)).thenReturn(recovered);

        var result = controller.recoverInstance("app-main", 1);

        assertEquals("READY", result.getData().getReadinessStatus());
        verify(workerManagementFacade).validateWorkerOwnership("user-1", "worker-1");
        verify(runtimeRegistryService).recoverInstanceQuarantine("app-main", 1);
    }

    @Test
    void recoverInstanceDoesNotProbeWhenOwnerValidationFails() {
        when(runtimeRegistryService.ownerWorkerId("app-main", 1)).thenReturn("worker-1");
        doThrow(new IllegalArgumentException("forbidden"))
                .when(workerManagementFacade).validateWorkerOwnership("user-1", "worker-1");

        assertThrows(IllegalArgumentException.class,
                () -> controller.recoverInstance("app-main", 1));

        verify(runtimeRegistryService, never()).recoverInstanceQuarantine("app-main", 1);
    }
}
