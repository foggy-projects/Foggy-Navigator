package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexSdkBackendConnectionTesterTest {

    @Mock
    private WorkerManagementFacade workerManagementFacade;
    @Mock
    private CodexWorkerClientFactory clientFactory;
    @Mock
    private CodexWorkerClient client;

    private CodexSdkBackendConnectionTester tester;

    @BeforeEach
    void setUp() {
        tester = new CodexSdkBackendConnectionTester(workerManagementFacade, clientFactory);
    }

    @Test
    void identifiesSdkBackendAndRequiresSdkWorkerConfiguration() {
        assertEquals(ProviderRouteRegistry.BACKEND_OPENAI_CODEX, tester.getWorkerBackend());

        when(workerManagementFacade.getCodexConfig("missing")).thenReturn(null);
        when(workerManagementFacade.getCodexConfig("blank"))
                .thenReturn(CodexConfig.builder().baseUrl(" ").build());
        when(workerManagementFacade.getCodexConfig("ready"))
                .thenReturn(CodexConfig.builder().baseUrl("http://127.0.0.1:3032").build());

        assertFalse(tester.supportsWorker("missing"));
        assertFalse(tester.supportsWorker("blank"));
        assertTrue(tester.supportsWorker("ready"));
        assertFalse(tester.supportsWorker("ready", "codex-latest:ultra"));
        assertTrue(tester.supportsWorker("ready", "codex-latest:max"));
    }

    @Test
    void testConnectionValidatesAccessAndProbesSdkHealth() {
        CodexConfig config = CodexConfig.builder()
                .baseUrl("http://127.0.0.1:3032")
                .authToken("sdk-token")
                .build();
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(config);
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://127.0.0.1:3032", "sdk-token"))
                .thenReturn(client);
        when(client.healthCheck()).thenReturn(Mono.just(Map.of("status", "ok")));

        assertEquals("Codex SDK Worker READY",
                tester.testConnection("user-1", "tenant-1", "worker-1", "gpt-5.6-codex"));

        verify(workerManagementFacade).validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(client).healthCheck();
    }

    @Test
    void testConnectionRejectsUltraBeforeWorkerAccessOrHealthProbe() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tester.testConnection(
                        "user-1", "tenant-1", "worker-1", "gpt-5.6-sol:ultra"));

        assertTrue(error.getMessage().startsWith("CODEX_ULTRA_APP_SERVER_REQUIRED"));
        verify(workerManagementFacade, never())
                .validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(workerManagementFacade, never()).getCodexConfig("worker-1");
        verify(clientFactory, never()).getOrCreate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void testConnectionRejectsWorkerWithoutSdkConfiguration() {
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tester.testConnection("user-1", "tenant-1", "worker-1", null));

        assertEquals("CODEX_SDK_WORKER_NOT_CONFIGURED: worker-1", error.getMessage());
        verify(clientFactory, never()).getOrCreate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void testConnectionStopsBeforeReadingWorkerConfigurationWhenAccessFails() {
        doThrow(new IllegalArgumentException("forbidden"))
                .when(workerManagementFacade)
                .validateWorkerAccess("user-1", "tenant-1", "worker-1");

        assertThrows(IllegalArgumentException.class,
                () -> tester.testConnection("user-1", "tenant-1", "worker-1", null));

        verify(workerManagementFacade, never()).getCodexConfig("worker-1");
        verify(clientFactory, never()).getOrCreate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void testConnectionFailsWhenSdkHealthHasNoResponse() {
        CodexConfig config = CodexConfig.builder()
                .baseUrl("http://127.0.0.1:3032")
                .build();
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(config);
        when(clientFactory.getOrCreate("worker-1:codex", "http://127.0.0.1:3032", null))
                .thenReturn(client);
        when(client.healthCheck()).thenReturn(Mono.empty());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> tester.testConnection("user-1", "tenant-1", "worker-1", null));

        assertEquals("CODEX_SDK_WORKER_UNAVAILABLE", error.getMessage());
    }
}
