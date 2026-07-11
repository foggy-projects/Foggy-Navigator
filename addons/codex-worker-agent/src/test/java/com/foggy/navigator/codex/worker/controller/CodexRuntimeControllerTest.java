package com.foggy.navigator.codex.worker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeAvailabilityDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeRateLimitsDTO;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRegistryService;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRateLimitsService;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private CodexRuntimeRateLimitsService runtimeRateLimitsService;

    @Mock
    private WorkerManagementFacade workerManagementFacade;

    @Mock
    private HttpServletResponse httpServletResponse;

    private CodexRuntimeController controller;

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .build());
        controller = new CodexRuntimeController(
                runtimeRegistryService, runtimeRateLimitsService, workerManagementFacade);
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
        verify(workerManagementFacade).validatePhysicalWorkerOwnership("user-1", "worker-1");
        verify(runtimeRegistryService).recoverInstanceQuarantine("app-main", 1);
    }

    @Test
    void recoverInstanceDoesNotProbeWhenOwnerValidationFails() {
        when(runtimeRegistryService.ownerWorkerId("app-main", 1)).thenReturn("worker-1");
        doThrow(new IllegalArgumentException("forbidden"))
                .when(workerManagementFacade).validatePhysicalWorkerOwnership("user-1", "worker-1");

        assertThrows(IllegalArgumentException.class,
                () -> controller.recoverInstance("app-main", 1));

        verify(runtimeRegistryService, never()).recoverInstanceQuarantine("app-main", 1);
    }

    @Test
    void ownerCanReadAggregateAvailabilityWithoutUsingOwnerOnlyRuntimeList() {
        CodexRuntimeAvailabilityDTO availability = CodexRuntimeAvailabilityDTO.builder()
                .appServerManaged(true)
                .ultraAvailable(true)
                .build();
        when(runtimeRegistryService.availability("worker-1", null)).thenReturn(availability);

        var result = controller.availability("worker-1", null);

        assertEquals(true, result.getData().getAppServerManaged());
        assertEquals(true, result.getData().getUltraAvailable());
        Map<?, ?> payload = new ObjectMapper().convertValue(result.getData(), Map.class);
        assertEquals(Set.of("appServerManaged", "ultraAvailable", "blockReason"), payload.keySet());
        verify(workerManagementFacade).validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(workerManagementFacade, never())
                .validatePhysicalWorkerOwnership("user-1", "worker-1");
        verify(runtimeRegistryService).availability("worker-1", null);
        verify(runtimeRegistryService, never()).listByWorker("worker-1");
    }

    @Test
    void tenantGrantedUserCanReadOnlyAggregateAvailability() {
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("granted-user")
                .tenantId("shared-tenant")
                .build());
        CodexRuntimeAvailabilityDTO availability = CodexRuntimeAvailabilityDTO.builder()
                .appServerManaged(true)
                .ultraAvailable(false)
                .blockReason("CODEX_ULTRA_RUNTIME_UNAVAILABLE")
                .build();
        when(runtimeRegistryService.availability("shared-worker", "codex-terra:ultra"))
                .thenReturn(availability);

        var result = controller.availability("shared-worker", "codex-terra:ultra");

        assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", result.getData().getBlockReason());
        verify(workerManagementFacade).validateWorkerAccess(
                "granted-user", "shared-tenant", "shared-worker");
        verify(runtimeRegistryService).availability("shared-worker", "codex-terra:ultra");
        verify(runtimeRegistryService, never()).listByWorker("shared-worker");
    }

    @Test
    void availabilityDoesNotQueryRegistryWhenWorkerAccessFails() {
        doThrow(new IllegalArgumentException("forbidden"))
                .when(workerManagementFacade)
                .validateWorkerAccess("user-1", "tenant-1", "worker-1");

        assertThrows(IllegalArgumentException.class,
                () -> controller.availability("worker-1", "codex-ultra"));

        verify(runtimeRegistryService, never()).availability("worker-1", "codex-ultra");
        verify(runtimeRegistryService, never()).listByWorker("worker-1");
    }

    @Test
    void runtimeListRemainsOwnerOnlyAndReturnsDetailedDtoOnlyToOwner() {
        when(runtimeRegistryService.listByWorker("worker-1")).thenReturn(List.of());

        controller.list("worker-1");

        verify(workerManagementFacade).validatePhysicalWorkerOwnership("user-1", "worker-1");
        verify(workerManagementFacade, never())
                .validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(runtimeRegistryService).listByWorker("worker-1");
    }

    @Test
    void runtimeOwnerCanReadRateLimitsWithoutChangingAvailability() {
        CodexRuntimeRateLimitsDTO snapshot = CodexRuntimeRateLimitsDTO.builder()
                .runtimeId("app-main")
                .runtimeRevision(1)
                .instanceId("instance-a")
                .state(CodexRuntimeRateLimitsDTO.State.AVAILABLE)
                .build();
        when(runtimeRegistryService.ownerWorkerId("app-main", 1)).thenReturn("worker-1");
        when(runtimeRateLimitsService.read("app-main", 1, true)).thenReturn(snapshot);

        var result = controller.rateLimits("app-main", 1, true, httpServletResponse);

        assertEquals(CodexRuntimeRateLimitsDTO.State.AVAILABLE, result.getData().getState());
        verify(workerManagementFacade).validatePhysicalWorkerOwnership("user-1", "worker-1");
        verify(runtimeRateLimitsService).read("app-main", 1, true);
        verify(httpServletResponse).setHeader("Cache-Control", "no-store");
        verify(runtimeRegistryService, never()).availability("worker-1", null);
    }

    @Test
    void rateLimitsDoesNotCallWorkerWhenPhysicalOwnerValidationFails() {
        when(runtimeRegistryService.ownerWorkerId("app-main", 1)).thenReturn("worker-1");
        doThrow(new IllegalArgumentException("forbidden"))
                .when(workerManagementFacade).validatePhysicalWorkerOwnership("user-1", "worker-1");

        assertThrows(IllegalArgumentException.class,
                () -> controller.rateLimits("app-main", 1, false, httpServletResponse));

        verify(runtimeRateLimitsService, never()).read("app-main", 1, false);
        verify(httpServletResponse, never()).setHeader("Cache-Control", "no-store");
    }
}
