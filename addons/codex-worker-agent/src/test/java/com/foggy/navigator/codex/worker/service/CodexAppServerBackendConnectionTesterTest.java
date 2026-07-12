package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.model.entity.CodexAppServerEndpointEntity;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeAvailabilityDTO;
import com.foggy.navigator.codex.worker.repository.CodexAppServerEndpointRepository;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexAppServerBackendConnectionTesterTest {

    @Mock
    private WorkerManagementFacade workerManagementFacade;
    @Mock
    private CodexAppServerEndpointRepository endpointRepository;
    @Mock
    private CodexRuntimeRegistryService runtimeRegistryService;

    private CodexAppServerBackendConnectionTester tester;

    @BeforeEach
    void setUp() {
        tester = new CodexAppServerBackendConnectionTester(
                workerManagementFacade, endpointRepository, runtimeRegistryService);
    }

    @Test
    void identifiesAppServerBackendAndRequiresEndpointProfile() {
        assertEquals(ProviderRouteRegistry.BACKEND_OPENAI_CODEX_APP_SERVER,
                tester.getWorkerBackend());
        when(endpointRepository.findByWorkerIdOrderByUpdatedAtDesc("missing"))
                .thenReturn(List.of());
        when(endpointRepository.findByWorkerIdOrderByUpdatedAtDesc("ready"))
                .thenReturn(List.of(new CodexAppServerEndpointEntity()));

        assertFalse(tester.supportsWorker("missing"));
        assertTrue(tester.supportsWorker("ready"));
    }

    @Test
    void distinguishesConfigurationSupportFromReadyExecutionAvailability() {
        when(runtimeRegistryService.availability("worker-1", "codex-terra:ultra"))
                .thenReturn(CodexRuntimeAvailabilityDTO.builder()
                        .modelSupported(true)
                        .modelAvailable(false)
                        .build());
        when(runtimeRegistryService.availability("worker-1", "codex-latest:max"))
                .thenReturn(CodexRuntimeAvailabilityDTO.builder()
                        .modelSupported(false)
                        .modelAvailable(false)
                        .build());

        assertTrue(tester.supportsWorkerConfiguration("worker-1", "codex-terra:ultra"));
        assertFalse(tester.supportsWorker("worker-1", "codex-terra:ultra"));
        assertFalse(tester.supportsWorkerConfiguration("worker-1", "codex-latest:max"));
        assertFalse(tester.supportsWorker("worker-1", "codex-latest:max"));
        assertFalse(tester.supportsWorkerConfiguration("worker-1", "codex-luna:ultra"));
        assertFalse(tester.supportsWorker("worker-1", "codex-luna:ultra"));
    }

    @Test
    void testConnectionValidatesAccessAndProbesEndpointProfile() {
        when(runtimeRegistryService.testEndpointConnection("worker-1", "codex-terra:ultra"))
                .thenReturn("Codex App Server READY: http://127.0.0.1:3062");

        assertEquals("Codex App Server READY: http://127.0.0.1:3062",
                tester.testConnection("user-1", "tenant-1", "worker-1", "codex-terra:ultra"));

        verify(workerManagementFacade).validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(runtimeRegistryService).testEndpointConnection("worker-1", "codex-terra:ultra");
    }

    @Test
    void testConnectionRejectsUnsupportedUltraFamilyBeforeAccessOrEndpointProbe() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tester.testConnection(
                        "user-1", "tenant-1", "worker-1", "codex-luna:ultra"));

        assertTrue(error.getMessage().startsWith("CODEX_APP_SERVER_MODEL_UNSUPPORTED"));
        verify(workerManagementFacade, never())
                .validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(runtimeRegistryService, never())
                .testEndpointConnection("worker-1", "codex-luna:ultra");
    }

    @Test
    void testConnectionStopsBeforeEndpointProbeWhenAccessFails() {
        doThrow(new IllegalArgumentException("forbidden"))
                .when(workerManagementFacade)
                .validateWorkerAccess("user-1", "tenant-1", "worker-1");

        assertThrows(IllegalArgumentException.class,
                () -> tester.testConnection(
                        "user-1", "tenant-1", "worker-1", "codex-terra:ultra"));

        verify(runtimeRegistryService, never())
                .testEndpointConnection("worker-1", "codex-terra:ultra");
    }
}
