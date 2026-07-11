package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeRateLimitsDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexRuntimeEntity;
import com.foggy.navigator.codex.worker.repository.CodexRuntimeRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexRuntimeRateLimitsServiceTest {

    private CodexRuntimeRepository repository;
    private CredentialEncryptor encryptor;
    private CodexWorkerClientFactory clientFactory;
    private CodexWorkerClient client;
    private CodexRuntimeRateLimitsService service;

    @BeforeEach
    void setUp() {
        repository = mock(CodexRuntimeRepository.class);
        encryptor = mock(CredentialEncryptor.class);
        clientFactory = mock(CodexWorkerClientFactory.class);
        client = mock(CodexWorkerClient.class);
        service = new CodexRuntimeRateLimitsService(repository, encryptor, clientFactory);

        CodexRuntimeEntity runtime = runtime();
        when(repository.findByRuntimeIdAndRevision("app-main", 1))
                .thenReturn(Optional.of(runtime));
        when(encryptor.decrypt("encrypted-token")).thenReturn("runtime-token");
        when(clientFactory.getOrCreate(
                "rate-limits:app-main:1", "http://worker:15199", "runtime-token", "instance-a"))
                .thenReturn(client);
    }

    @Test
    void readsPinnedRuntimeWithRefreshAndReturnsStrongSnapshot() {
        CodexRuntimeRateLimitsDTO snapshot = snapshot("app-main", 1, "instance-a");
        when(client.getRuntimeRateLimits(true)).thenReturn(Mono.just(snapshot));

        CodexRuntimeRateLimitsDTO result = service.read("app-main", 1, true);

        assertEquals(CodexRuntimeRateLimitsDTO.State.AVAILABLE, result.getState());
        assertEquals(42, result.getLimits().get(0).getPrimary().getUsedPercent());
        verify(client).getRuntimeRateLimits(true);
    }

    @Test
    void rejectsResponseBodyFromAnotherRuntimeEvenWhenTransportCompletes() {
        when(client.getRuntimeRateLimits(false))
                .thenReturn(Mono.just(snapshot("other-runtime", 1, "instance-a")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.read("app-main", 1, false));

        assertEquals("CODEX_RUNTIME_RATE_LIMITS_IDENTITY_MISMATCH", error.getMessage());
    }

    @Test
    void rejectsAQuotaSnapshotFromAnotherCredentialScope() {
        CodexRuntimeRateLimitsDTO snapshot = snapshot("app-main", 1, "instance-a");
        snapshot.setScope("TASK_CODEX_HOME");
        when(client.getRuntimeRateLimits(false)).thenReturn(Mono.just(snapshot));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.read("app-main", 1, false));

        assertEquals("CODEX_RUNTIME_RATE_LIMITS_CONTRACT_MISMATCH", error.getMessage());
    }

    @Test
    void rejectsRuntimeWithoutPinnedInstanceBeforeCreatingClient() {
        CodexRuntimeEntity runtime = runtime();
        runtime.setInstanceId(null);
        when(repository.findByRuntimeIdAndRevision("app-main", 1))
                .thenReturn(Optional.of(runtime));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.read("app-main", 1, false));

        assertEquals("CODEX_RUNTIME_INSTANCE_NOT_PINNED", error.getMessage());
    }

    @Test
    void mapsLegacyWorkerWithoutRateLimitEndpointToUnsupportedSnapshot() {
        when(client.getRuntimeRateLimits(false)).thenReturn(Mono.error(
                WebClientResponseException.create(
                        404, "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));

        CodexRuntimeRateLimitsDTO result = service.read("app-main", 1, false);

        assertEquals(CodexRuntimeRateLimitsDTO.State.UNSUPPORTED, result.getState());
        assertEquals("RATE_LIMITS_UNSUPPORTED", result.getErrorCode());
        assertEquals("instance-a", result.getInstanceId());
        assertEquals(0, result.getLimits().size());
    }

    private CodexRuntimeEntity runtime() {
        CodexRuntimeEntity runtime = new CodexRuntimeEntity();
        runtime.setRuntimeId("app-main");
        runtime.setRevision(1);
        runtime.setRuntimeType("APP_SERVER");
        runtime.setEndpointUrl("http://worker:15199");
        runtime.setAuthTokenCiphertext("encrypted-token");
        runtime.setInstanceId("instance-a");
        return runtime;
    }

    private CodexRuntimeRateLimitsDTO snapshot(String runtimeId, int revision, String instanceId) {
        return CodexRuntimeRateLimitsDTO.builder()
                .contractVersion(1)
                .runtimeId(runtimeId)
                .runtimeRevision(revision)
                .instanceId(instanceId)
                .scope("DEFAULT_CODEX_HOME")
                .state(CodexRuntimeRateLimitsDTO.State.AVAILABLE)
                .observedAtEpochMs(1_783_728_000_000L)
                .stale(false)
                .limits(java.util.List.of(CodexRuntimeRateLimitsDTO.Limit.builder()
                        .limitId("codex")
                        .primary(CodexRuntimeRateLimitsDTO.Window.builder()
                                .usedPercent(42)
                                .windowDurationMins(300L)
                                .resetsAt(1_783_746_000L)
                                .build())
                        .build()))
                .build();
    }
}
