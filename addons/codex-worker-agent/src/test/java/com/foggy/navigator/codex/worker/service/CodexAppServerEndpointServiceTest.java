package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.model.dto.CodexAppServerEndpointSyncDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexAppServerEndpointEntity;
import com.foggy.navigator.codex.worker.model.entity.CodexRuntimeEntity;
import com.foggy.navigator.codex.worker.model.form.CodexAppServerEndpointForm;
import com.foggy.navigator.codex.worker.repository.CodexAppServerEndpointRepository;
import com.foggy.navigator.codex.worker.repository.CodexRuntimeRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexAppServerEndpointServiceTest {

    @Mock
    private CodexAppServerEndpointRepository endpointRepository;
    @Mock
    private CodexRuntimeRepository runtimeRepository;
    @Mock
    private CredentialEncryptor credentialEncryptor;
    @Mock
    private CodexRuntimeRegistryService runtimeRegistryService;

    private CodexAppServerEndpointService service;

    @BeforeEach
    void setUp() {
        service = new CodexAppServerEndpointService(
                endpointRepository, runtimeRepository, credentialEncryptor, runtimeRegistryService);
        lenient().when(credentialEncryptor.encrypt(anyString()))
                .thenAnswer(invocation -> "encrypted:" + invocation.getArgument(0, String.class));
        lenient().when(credentialEncryptor.decrypt(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).replaceFirst("^encrypted:", ""));
        lenient().when(endpointRepository.save(any(CodexAppServerEndpointEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createNormalizesUrlAndEncryptsTrimmedOptionalToken() {
        CodexAppServerEndpointForm form = form("worker-1", "https://worker.example:3062/api/", " token-1 ");

        var result = service.create(form);

        assertEquals("worker-1", result.getWorkerId());
        assertEquals("https://worker.example:3062/api", result.getEndpointUrl());
        assertEquals("https://worker.example:3062", result.getEndpointDisplay());
        assertTrue(result.getTokenConfigured());
        assertEquals(1L, result.getConfigurationVersion());
        assertEquals("PENDING", result.getLastSyncStatus());
        verify(credentialEncryptor).encrypt("token-1");
        verify(endpointRepository).save(any(CodexAppServerEndpointEntity.class));
    }

    @Test
    void createAllowsEmptyTokenAndReportsItAsNotConfigured() {
        CodexAppServerEndpointForm form = form("worker-1", "http://127.0.0.1:3062", null);

        var result = service.create(form);

        assertFalse(result.getTokenConfigured());
        verify(credentialEncryptor).encrypt("");
    }

    @Test
    void updateWithBlankTokenPreservesCredentialAndSyncState() {
        CodexAppServerEndpointEntity endpoint = endpoint("endpoint-1", "encrypted:stored-token");
        LocalDateTime syncedAt = LocalDateTime.of(2026, 7, 12, 10, 0);
        endpoint.setLastSyncStatus("READY");
        endpoint.setLastSyncMessage("READY");
        endpoint.setLastSyncedAt(syncedAt);
        when(endpointRepository.findByEndpointIdForUpdate("endpoint-1"))
                .thenReturn(Optional.of(endpoint));
        CodexAppServerEndpointForm form = form("worker-1", endpoint.getEndpointUrl(), "   ");

        var result = service.update("endpoint-1", form);

        assertTrue(result.getTokenConfigured());
        assertEquals("encrypted:stored-token", endpoint.getAuthTokenCiphertext());
        assertEquals(3L, result.getConfigurationVersion());
        assertEquals("READY", result.getLastSyncStatus());
        assertEquals(syncedAt, result.getLastSyncedAt());
        verify(credentialEncryptor, never()).encrypt(anyString());
    }

    @Test
    void updateReplacesTokenAndInvalidatesPreviousSync() {
        CodexAppServerEndpointEntity endpoint = endpoint("endpoint-1", "encrypted:stored-token");
        endpoint.setLastSyncStatus("READY");
        endpoint.setLastSyncMessage("READY");
        endpoint.setLastSyncedAt(LocalDateTime.of(2026, 7, 12, 10, 0));
        when(endpointRepository.findByEndpointIdForUpdate("endpoint-1"))
                .thenReturn(Optional.of(endpoint));
        CodexAppServerEndpointForm form = form("worker-1", null, " new-token ");

        var result = service.update("endpoint-1", form);

        assertEquals("encrypted:new-token", endpoint.getAuthTokenCiphertext());
        assertEquals(4L, result.getConfigurationVersion());
        assertEquals("PENDING", result.getLastSyncStatus());
        assertEquals("ENDPOINT_CONFIGURATION_CHANGED", result.getLastSyncMessage());
        assertNull(result.getLastSyncedAt());
    }

    @Test
    void updateExplicitlyClearsToken() {
        CodexAppServerEndpointEntity endpoint = endpoint("endpoint-1", "encrypted:stored-token");
        when(endpointRepository.findByEndpointIdForUpdate("endpoint-1"))
                .thenReturn(Optional.of(endpoint));
        CodexAppServerEndpointForm form = new CodexAppServerEndpointForm();
        form.setClearAuthToken(true);

        var result = service.update("endpoint-1", form);

        assertFalse(result.getTokenConfigured());
        assertEquals("encrypted:", endpoint.getAuthTokenCiphertext());
        assertEquals(4L, result.getConfigurationVersion());
        assertEquals("PENDING", result.getLastSyncStatus());
        verify(credentialEncryptor).encrypt("");
    }

    @Test
    void updateRejectsMovingEndpointToAnotherWorker() {
        CodexAppServerEndpointEntity endpoint = endpoint("endpoint-1", "encrypted:stored-token");
        when(endpointRepository.findByEndpointIdForUpdate("endpoint-1"))
                .thenReturn(Optional.of(endpoint));
        CodexAppServerEndpointForm form = new CodexAppServerEndpointForm();
        form.setWorkerId("worker-2");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.update("endpoint-1", form));

        assertEquals("workerId cannot be changed for an endpoint", error.getMessage());
        verify(endpointRepository, never()).save(endpoint);
    }

    @Test
    void deleteDrainsLiveRuntimeRevisionsAndLeavesArchivedRevisionUntouched() {
        CodexAppServerEndpointEntity endpoint = endpoint("endpoint-1", "encrypted:");
        CodexRuntimeEntity live = runtime(null, true, "ALL_DEFAULT", 100, 7L);
        CodexRuntimeEntity archived = runtime(LocalDateTime.of(2026, 7, 1, 0, 0),
                true, "ALL_DEFAULT", 100, 9L);
        when(endpointRepository.findByEndpointIdForUpdate("endpoint-1"))
                .thenReturn(Optional.of(endpoint));
        when(runtimeRepository.findByEndpointIdOrderByRevisionDesc("endpoint-1"))
                .thenReturn(List.of(live, archived));

        service.delete("endpoint-1");

        assertFalse(live.getEnabled());
        assertEquals("DRAINING", live.getRoutingPolicy());
        assertEquals(0, live.getRolloutPercentage());
        assertEquals(8L, live.getRoutingEpoch());
        assertTrue(archived.getEnabled());
        assertEquals("ALL_DEFAULT", archived.getRoutingPolicy());
        assertEquals(100, archived.getRolloutPercentage());
        assertEquals(9L, archived.getRoutingEpoch());
        verify(endpointRepository).delete(endpoint);
    }

    @Test
    void synchronizeDelegatesToRuntimeRegistryWithoutEditingEndpoint() {
        CodexAppServerEndpointSyncDTO expected = CodexAppServerEndpointSyncDTO.builder()
                .runtimeCreated(true)
                .build();
        when(runtimeRegistryService.synchronizeEndpoint("endpoint-1")).thenReturn(expected);

        assertEquals(expected, service.synchronize("endpoint-1"));

        verify(runtimeRegistryService).synchronizeEndpoint("endpoint-1");
        verify(endpointRepository, never()).save(any());
    }

    @Test
    void ownerWorkerIdFailsClosedForUnknownEndpoint() {
        when(endpointRepository.findByEndpointId("missing")).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.ownerWorkerId("missing"));

        assertEquals("Endpoint not found: missing", error.getMessage());
    }

    private CodexAppServerEndpointForm form(String workerId, String endpointUrl, String token) {
        CodexAppServerEndpointForm form = new CodexAppServerEndpointForm();
        form.setWorkerId(workerId);
        form.setEndpointUrl(endpointUrl);
        form.setAuthToken(token);
        return form;
    }

    private CodexAppServerEndpointEntity endpoint(String endpointId, String ciphertext) {
        CodexAppServerEndpointEntity endpoint = new CodexAppServerEndpointEntity();
        endpoint.setEndpointId(endpointId);
        endpoint.setWorkerId("worker-1");
        endpoint.setEndpointUrl("http://127.0.0.1:3062");
        endpoint.setAuthTokenCiphertext(ciphertext);
        endpoint.setConfigurationVersion(3L);
        endpoint.setLastSyncStatus("PENDING");
        return endpoint;
    }

    private CodexRuntimeEntity runtime(LocalDateTime archivedAt, boolean enabled, String policy,
                                       int rollout, long routingEpoch) {
        CodexRuntimeEntity runtime = new CodexRuntimeEntity();
        runtime.setArchivedAt(archivedAt);
        runtime.setEnabled(enabled);
        runtime.setRoutingPolicy(policy);
        runtime.setRolloutPercentage(rollout);
        runtime.setRoutingEpoch(routingEpoch);
        return runtime;
    }
}
