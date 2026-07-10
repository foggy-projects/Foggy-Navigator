package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.entity.CodexRuntimeEntity;
import com.foggy.navigator.codex.worker.model.form.CodexRuntimeRegistrationForm;
import com.foggy.navigator.codex.worker.model.form.CodexRuntimeRoutingForm;
import com.foggy.navigator.codex.worker.repository.CodexRuntimeRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexRuntimeRegistryServiceTest {

    private CodexRuntimeRepository repository;
    private CredentialEncryptor encryptor;
    private CodexWorkerClientFactory clientFactory;
    private CodexWorkerClient client;
    private ObjectMapper objectMapper;
    private CodexRuntimeCapabilityStateService capabilityStateService;
    private CodexRuntimeRegistryService service;

    @BeforeEach
    void setUp() {
        repository = mock(CodexRuntimeRepository.class);
        encryptor = mock(CredentialEncryptor.class);
        clientFactory = mock(CodexWorkerClientFactory.class);
        client = mock(CodexWorkerClient.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        capabilityStateService = new CodexRuntimeCapabilityStateService(repository);
        service = new CodexRuntimeRegistryService(
                repository, encryptor, clientFactory, objectMapper, capabilityStateService);
        ReflectionTestUtils.setField(service, "capabilityMaxAgeSeconds", 120L);
        when(repository.save(any(CodexRuntimeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(encryptor.encrypt(anyString())).thenAnswer(invocation -> "encrypted:" + invocation.getArgument(0));
        when(encryptor.decrypt(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return value.startsWith("encrypted:") ? value.substring("encrypted:".length()) : value;
        });
    }

    @Test
    void registerRevisionPinsCliAndSchemaAndEncryptsToken() {
        when(repository.findMaxRevision("app-main")).thenReturn(0);
        CodexRuntimeRegistrationForm form = registration();

        var result = service.registerRevision(form);

        assertEquals(1, result.getRevision());
        assertEquals(CodexRuntimeRegistryService.PINNED_APP_SERVER_CLI_VERSION,
                result.getExpectedCliVersion());
        assertEquals(CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST,
                result.getExpectedSchemaDigest());
        verify(encryptor).encrypt("runtime-token");
    }

    @Test
    void registrationCannotOverridePinnedProtocol() {
        CodexRuntimeRegistrationForm form = registration();
        form.setExpectedCliVersion("0.145.0");
        assertThrows(IllegalArgumentException.class, () -> service.registerRevision(form));

        form.setExpectedCliVersion(CodexRuntimeRegistryService.PINNED_APP_SERVER_CLI_VERSION);
        form.setExpectedSchemaDigest("unreviewed-schema");
        assertThrows(IllegalArgumentException.class, () -> service.registerRevision(form));
    }

    @Test
    void runtimeNamespaceCannotCrossWorkersOrSkipRevisionSequence() {
        CodexRuntimeEntity existing = runtime("DARK", 0);
        when(repository.findByRuntimeIdOrderByRevisionDesc("app-main")).thenReturn(List.of(existing));
        when(repository.findMaxRevision("app-main")).thenReturn(1);

        CodexRuntimeRegistrationForm otherWorker = registration();
        otherWorker.setWorkerId("worker-2");
        assertThrows(IllegalArgumentException.class, () -> service.registerRevision(otherWorker));

        CodexRuntimeRegistrationForm skipped = registration();
        skipped.setRevision(Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> service.registerRevision(skipped));
        verify(encryptor, never()).encrypt("runtime-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "token\nwith-control"})
    void registrationRejectsMissingOrUnsafeAuthToken(String token) {
        CodexRuntimeRegistrationForm form = registration();
        form.setAuthToken(token);

        assertThrows(IllegalArgumentException.class, () -> service.registerRevision(form));
        verify(encryptor, never()).encrypt(anyString());
    }

    @Test
    void registrationRejectsLegacySdkRuntimeType() {
        CodexRuntimeRegistrationForm form = registration();
        form.setRuntimeType("SDK_EXEC");

        assertThrows(IllegalArgumentException.class, () -> service.registerRevision(form));
        verify(encryptor, never()).encrypt(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://user:token@127.0.0.1:3062",
            "http://127.0.0.1:3062?token=secret",
            "http://127.0.0.1:3062#fragment",
            "file:///tmp/worker",
            "http://127.0.0.1:3062/\nInjected"
    })
    void registrationRejectsUnsafeEndpoint(String endpoint) {
        CodexRuntimeRegistrationForm form = registration();
        form.setEndpointUrl(endpoint);

        assertThrows(IllegalArgumentException.class, () -> service.registerRevision(form));
    }

    @Test
    void routingCannotJumpDirectlyFromDarkToAllDefault() {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        when(repository.findByRuntimeIdAndRevisionForUpdate("app-main", 1)).thenReturn(Optional.of(entity));
        CodexRuntimeRoutingForm form = new CodexRuntimeRoutingForm();
        form.setExpectedRoutingEpoch(1L);
        form.setRoutingPolicy("ALL_DEFAULT");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.updateRouting("app-main", 1, form));

        assertTrue(error.getMessage().contains("CODEX_RUNTIME_ROUTING_TRANSITION_INVALID"));
        assertEquals("DARK", entity.getRoutingPolicy());
    }

    @Test
    void routingUpdateUsesServerEpochCompareAndSet() {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        when(repository.findByRuntimeIdAndRevisionForUpdate("app-main", 1)).thenReturn(Optional.of(entity));
        CodexRuntimeRoutingForm stale = new CodexRuntimeRoutingForm();
        stale.setExpectedRoutingEpoch(0L);
        stale.setRoutingPolicy("ULTRA_CANARY");
        assertThrows(IllegalStateException.class,
                () -> service.updateRouting("app-main", 1, stale));

        CodexRuntimeRoutingForm valid = new CodexRuntimeRoutingForm();
        valid.setExpectedRoutingEpoch(1L);
        valid.setRoutingPolicy("ULTRA_CANARY");
        valid.setRolloutPercentage(5);
        valid.setEnabled(true);
        var updated = service.updateRouting("app-main", 1, valid);

        assertEquals("ULTRA_CANARY", updated.getRoutingPolicy());
        assertEquals(2L, updated.getRoutingEpoch());
    }

    @Test
    void refreshCapabilitiesAcceptsTopLevelContract() throws Exception {
        CodexRuntimeEntity entity = runtime("ULTRA_DEFAULT", 100);
        stubRefresh(entity, topLevelManifest("app-main", 1,
                CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST));

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("READY", result.getReadinessStatus());
        assertEquals("instance-a", result.getInstanceId());
        assertEquals(true, result.getEndpointConfigured());
        assertEquals("http://***:3062", result.getEndpointDisplay());
        assertEquals(true, result.getCapabilityFresh());
        assertEquals(true, result.getSupportsUltra());
    }

    @Test
    void capabilityRefreshPersistsIntoLockedCurrentEntityWithoutOverwritingRoutingCas() throws Exception {
        CodexRuntimeEntity probeTarget = runtime("DARK", 0);
        CodexRuntimeEntity lockedCurrent = runtime("ULTRA_CANARY", 10);
        lockedCurrent.setRoutingEpoch(7L);
        lockedCurrent.setPriority(42);
        when(repository.findByRuntimeIdAndRevision("app-main", 1))
                .thenReturn(Optional.of(probeTarget));
        when(repository.findByRuntimeIdAndRevisionForUpdate("app-main", 1))
                .thenReturn(Optional.of(lockedCurrent));
        when(clientFactory.getOrCreate(anyString(), anyString(), any(), any())).thenReturn(client);
        when(client.probeCapabilities()).thenReturn(Mono.just(probe(topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST))));

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("ULTRA_CANARY", result.getRoutingPolicy());
        assertEquals(10, result.getRolloutPercentage());
        assertEquals(7L, result.getRoutingEpoch());
        assertEquals(42, result.getPriority());
        assertEquals("READY", result.getReadinessStatus());
    }

    @Test
    void refreshCapabilitiesAcceptsNestedNMinusOneContract() throws Exception {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        entity.setEnabled(false);
        entity.setInstanceId(null);
        Map<String, Object> manifest = Map.of(
                "contract_version", "1",
                "runtime", Map.of(
                        "runtimeType", "APP_SERVER",
                        "runtimeId", "app-main",
                        "runtimeRevision", 1,
                        "instanceId", "instance-n1",
                        "cliVersion", "0.144.1",
                        "schemaDigest", CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST),
                "models", Map.of(
                        "supported", List.of("*"),
                        "reasoningEfforts", List.of("minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
                        "aliases", Map.of("codex-latest", "gpt-5.6-sol")),
                "capabilities", Map.of(
                        "readiness", Map.of("ready", true, "reasons", List.of()),
                        "features", appServerFeatures()));
        stubRefresh(entity, manifest);

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("READY", result.getReadinessStatus());
        assertEquals("instance-n1", result.getInstanceId());
    }

    @Test
    void capabilityWithoutInstanceIdentityNeverBecomesReady() {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        entity.setEnabled(false);
        entity.setInstanceId(null);
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        manifest.remove("instance_id");
        stubRefresh(entity, manifest);

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("INCOMPATIBLE", result.getReadinessStatus());
        assertTrue(result.getReadinessMessage().contains("CAPABILITY_INSTANCE_ID_MISMATCH"));
        assertEquals(null, result.getInstanceId());
    }

    @Test
    void initialInstanceBindingRequiresCapabilityResponseHeaderProof() {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        entity.setEnabled(false);
        entity.setInstanceId(null);
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        when(repository.findByRuntimeIdAndRevision("app-main", 1)).thenReturn(Optional.of(entity));
        when(repository.findByRuntimeIdAndRevisionForUpdate("app-main", 1)).thenReturn(Optional.of(entity));
        when(clientFactory.getOrCreate(anyString(), anyString(), any(), any())).thenReturn(client);
        when(client.probeCapabilities()).thenReturn(Mono.just(
                new CodexWorkerClient.CapabilityProbe(manifest, null)));

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("INCOMPATIBLE", result.getReadinessStatus());
        assertTrue(result.getReadinessMessage().contains("CAPABILITY_INSTANCE_PROOF_MISSING"));
        assertEquals(null, result.getInstanceId());
    }

    @Test
    void explicitRecoveryClearsQuarantineOnlyWhenOriginalInstanceFullyMatches() throws Exception {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        entity.setEnabled(false);
        entity.setReadinessStatus("INCOMPATIBLE");
        entity.setReadinessMessage("CAPABILITY_INSTANCE_ID_MISMATCH");
        stubRefresh(entity, topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST));

        var result = service.recoverInstanceQuarantine("app-main", 1);

        assertEquals("READY", result.getReadinessStatus());
        assertEquals(null, result.getReadinessMessage());
        assertEquals("instance-a", result.getInstanceId());
        verify(clientFactory).getOrCreate(
                "runtime:app-main:1", "http://127.0.0.1:3062", "runtime-token", "instance-a");
    }

    @Test
    void failedExplicitRecoveryRetainsInstanceQuarantine() {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        entity.setEnabled(false);
        entity.setReadinessStatus("INCOMPATIBLE");
        entity.setReadinessMessage("CAPABILITY_INSTANCE_ID_MISMATCH");
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        when(repository.findByRuntimeIdAndRevision("app-main", 1)).thenReturn(Optional.of(entity));
        when(repository.findByRuntimeIdAndRevisionForUpdate("app-main", 1)).thenReturn(Optional.of(entity));
        when(clientFactory.getOrCreate(anyString(), anyString(), any(), any())).thenReturn(client);
        when(client.probeCapabilities()).thenReturn(Mono.just(
                new CodexWorkerClient.CapabilityProbe(manifest, null)));

        var result = service.recoverInstanceQuarantine("app-main", 1);

        assertEquals("INCOMPATIBLE", result.getReadinessStatus());
        assertTrue(result.getReadinessMessage().contains("CAPABILITY_INSTANCE_ID_MISMATCH"));
        assertTrue(result.getReadinessMessage().contains("CAPABILITY_INSTANCE_PROOF_MISSING"));
    }

    @Test
    void explicitRecoveryRequiresDisabledDarkRevision() {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        entity.setReadinessMessage("CAPABILITY_INSTANCE_ID_MISMATCH");
        when(repository.findByRuntimeIdAndRevision("app-main", 1)).thenReturn(Optional.of(entity));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.recoverInstanceQuarantine("app-main", 1));

        assertEquals("CODEX_RUNTIME_INSTANCE_RECOVERY_REQUIRES_DISABLED_DARK", error.getMessage());
        verify(clientFactory, never()).getOrCreate(anyString(), anyString(), any(), any());
    }

    @Test
    void refreshCapabilitiesRejectsMissingSchemaDigest() throws Exception {
        CodexRuntimeEntity entity = runtime("ULTRA_DEFAULT", 100);
        Map<String, Object> manifest = topLevelManifest("app-main", 1, null);
        stubRefresh(entity, manifest);

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("INCOMPATIBLE", result.getReadinessStatus());
        assertTrue(result.getReadinessMessage().contains("CAPABILITY_SCHEMA_DIGEST_MISMATCH"));
    }

    @Test
    void refreshCapabilitiesRejectsWrongRuntimeIdentity() throws Exception {
        CodexRuntimeEntity entity = runtime("ULTRA_DEFAULT", 100);
        stubRefresh(entity, topLevelManifest("other-runtime", 2,
                CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST));

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("INCOMPATIBLE", result.getReadinessStatus());
        assertTrue(result.getReadinessMessage().contains("CAPABILITY_RUNTIME_ID_MISMATCH"));
        assertTrue(result.getReadinessMessage().contains("CAPABILITY_RUNTIME_REVISION_MISMATCH"));
    }

    @Test
    void refreshCapabilitiesRejectsDrainingWorkerReadiness() throws Exception {
        CodexRuntimeEntity entity = runtime("ULTRA_DEFAULT", 100);
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        manifest.put("readiness", Map.of("ready", false, "reasons", List.of("APP_SERVER_WORKER_DRAINING")));
        stubRefresh(entity, manifest);

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("INCOMPATIBLE", result.getReadinessStatus());
        assertTrue(result.getReadinessMessage().contains("CAPABILITY_RUNTIME_NOT_READY"));
        assertTrue(result.getReadinessMessage().contains("APP_SERVER_WORKER_DRAINING"));

        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));
        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "codex-ultra",
                        "codex-worker", "task-1"));
        assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @Test
    void refreshCapabilitiesRequiresExplicitReadyTrue() throws Exception {
        CodexRuntimeEntity entity = runtime("ULTRA_DEFAULT", 100);
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        manifest.remove("readiness");
        stubRefresh(entity, manifest);

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("INCOMPATIBLE", result.getReadinessStatus());
        assertTrue(result.getReadinessMessage().contains("CAPABILITY_RUNTIME_NOT_READY"));
    }

    @Test
    void readinessExposesOnlyKnownStableWorkerReasons() throws Exception {
        CodexRuntimeEntity entity = runtime("ULTRA_DEFAULT", 100);
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        manifest.put("readiness", Map.of(
                "ready", false,
                "reasons", List.of("ALLOWED_CWDS_MISSING", "SECRET_TOKEN_SENTINEL")));
        stubRefresh(entity, manifest);

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("INCOMPATIBLE", result.getReadinessStatus());
        assertTrue(result.getReadinessMessage().contains("ALLOWED_CWDS_MISSING"));
        assertFalse(result.getReadinessMessage().contains("SECRET_TOKEN_SENTINEL"));
    }

    @Test
    void refreshCapabilitiesRequiresDurableAppServerContract() throws Exception {
        CodexRuntimeEntity entity = runtime("ULTRA_DEFAULT", 100);
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        Map<String, Object> features = new java.util.LinkedHashMap<>(appServerFeatures());
        features.put("durable_events", false);
        manifest.put("features", features);
        stubRefresh(entity, manifest);

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("INCOMPATIBLE", result.getReadinessStatus());
        assertTrue(result.getReadinessMessage().contains("CAPABILITY_FEATURE_DURABLE_EVENTS_REQUIRED"));
    }

    @Test
    void refreshCapabilitiesRequiresInstanceAffinityGuard() throws Exception {
        CodexRuntimeEntity entity = runtime("ULTRA_DEFAULT", 100);
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        Map<String, Object> features = new java.util.LinkedHashMap<>(appServerFeatures());
        features.remove("instance_affinity_guard");
        manifest.put("features", features);
        stubRefresh(entity, manifest);

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("INCOMPATIBLE", result.getReadinessStatus());
        assertTrue(result.getReadinessMessage().contains(
                "CAPABILITY_FEATURE_INSTANCE_AFFINITY_GUARD_REQUIRED"));
        verify(clientFactory).getOrCreate(
                "runtime:app-main:1", "http://127.0.0.1:3062", "runtime-token", "instance-a");
    }

    @Test
    void listAndSelectionUseRuntimeIdAsDeterministicTieBreaker() throws Exception {
        CodexRuntimeEntity zRuntime = readyRuntime("ALL_DEFAULT", 100, "gpt-5.6-sol");
        zRuntime.setRuntimeId("z-runtime");
        CodexRuntimeEntity aRuntime = readyRuntime("ALL_DEFAULT", 100, "gpt-5.6-sol");
        aRuntime.setRuntimeId("a-runtime");
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(zRuntime, aRuntime));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(zRuntime, aRuntime));

        var listed = service.listByWorker("worker-1");
        CodexRuntimeBinding selected = service.selectForNewTask(
                "worker-1", "codex-latest", "codex-worker", "task-1");

        assertEquals(List.of("a-runtime", "z-runtime"),
                listed.stream().map(runtime -> runtime.getRuntimeId()).toList());
        assertEquals("a-runtime", selected.getRuntimeId());
    }

    @Test
    void ultraRoutingRequiresNativeSubtaskContractV1() throws Exception {
        CodexRuntimeEntity entity = runtime("ULTRA_DEFAULT", 100);
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        Map<String, Object> features = new java.util.LinkedHashMap<>(appServerFeatures());
        features.put("native_subtask_contract_versions", List.of(2));
        manifest.put("features", features);
        stubRefresh(entity, manifest);

        var result = service.refreshCapabilities("app-main", 1);
        assertEquals("READY", result.getReadinessStatus());
        assertFalse(result.getSupportsUltra());

        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));
        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "codex-ultra",
                        "codex-worker", "task-1"));
        assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @Test
    void refreshFailureDoesNotExposeEndpointOrTokenInReadinessPayload() throws Exception {
        String sentinelEndpoint = "http://sentinel-internal.example:3062";
        String sentinelToken = "sentinel-secret-token";
        CodexRuntimeEntity entity = runtime("DARK", 0);
        entity.setEndpointUrl(sentinelEndpoint);
        entity.setAuthTokenCiphertext("encrypted:" + sentinelToken);
        when(repository.findByRuntimeIdAndRevision("app-main", 1)).thenReturn(Optional.of(entity));
        when(repository.findByRuntimeIdAndRevisionForUpdate("app-main", 1)).thenReturn(Optional.of(entity));
        when(clientFactory.getOrCreate(anyString(), anyString(), any(), any())).thenReturn(client);
        when(client.probeCapabilities()).thenReturn(Mono.error(new IllegalStateException(
                "Cannot connect to " + sentinelEndpoint + " using " + sentinelToken)));

        var result = service.refreshCapabilities("app-main", 1);
        String payload = objectMapper.writeValueAsString(result);

        assertEquals("CAPABILITY_REFRESH_FAILED", entity.getReadinessMessage());
        assertEquals("CAPABILITY_REFRESH_FAILED", result.getReadinessMessage());
        assertFalse(payload.contains(sentinelEndpoint));
        assertFalse(payload.contains(sentinelToken));
    }

    @Test
    void newUltraWithoutRuntimeFailsClosed() {
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of());

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "codex-ultra", "codex-worker", "task-1"));

        assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DARK", "ULTRA_CANARY"})
    void newUltraOutsideActiveCohortFailsClosed(String policy) throws Exception {
        CodexRuntimeEntity entity = readyRuntime(policy, 0, "gpt-5.6-sol");
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "gpt-5.6-sol:ultra",
                        "codex-worker", "task-1"));

        assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @Test
    void ultraCanaryTenPercentCohortIsStableAndBounded() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ULTRA_CANARY", 10, "gpt-5.6-sol");
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));
        List<Boolean> firstPass = new java.util.ArrayList<>();

        for (int i = 0; i < 1_000; i++) {
            firstPass.add(isUltraCanarySelected("task-" + i));
        }
        for (int i = 0; i < 1_000; i++) {
            assertEquals(firstPass.get(i), isUltraCanarySelected("task-" + i));
        }

        long selected = firstPass.stream().filter(Boolean::booleanValue).count();
        assertTrue(selected >= 50 && selected <= 150,
                "10% canary sample was outside a conservative bound: " + selected);
        assertTrue(firstPass.contains(false));
    }

    @Test
    void bareNonUltraModelUsesLegacyWhenRuntimeIsDark() {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeBinding binding = service.selectForNewTask(
                "worker-1", "codex-latest", "codex-worker", "task-1");

        assertEquals(CodexRuntimeType.SDK_EXEC, binding.getRuntimeType());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "codex-latest", "codex-fast", "codex-deep", "codex-xhigh", "codex-max", "codex-ultra",
            "codex-mini", "codex-latest:minimal", "codex-latest:medium", "gpt-5.6-sol",
            "gpt-5.6-sol:low", "gpt-5.6-sol:high", "gpt-5.6-sol:xhigh", "gpt-5.6-sol:max",
            "gpt-5.6-sol:ultra", "gpt-5.4-mini"
    })
    void allSupportedAliasesAndExplicitModelsRouteToAppServer(String model) throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeBinding binding = service.selectForNewTask(
                "worker-1", model, "codex-worker", "task-1");

        assertEquals(CodexRuntimeType.APP_SERVER, binding.getRuntimeType());
        assertEquals("http://127.0.0.1:3062", binding.getEndpointUrl());
    }

    @Test
    void perModelReasoningMatrixRejectsUnsupportedMiniTiers() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        manifest.put("models", List.of(
                "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.4-mini"));
        manifest.put("model_reasoning_matrix", Map.of(
                "gpt-5.6-sol", List.of("low", "medium", "high", "xhigh", "max", "ultra"),
                "gpt-5.6-terra", List.of("low", "medium", "high", "xhigh", "max", "ultra"),
                "gpt-5.6-luna", List.of("low", "medium", "high", "xhigh", "max"),
                "gpt-5.4-mini", List.of("none", "low", "medium", "high", "xhigh")));
        entity.setCapabilityManifestJson(objectMapper.writeValueAsString(manifest));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        assertEquals(CodexRuntimeType.APP_SERVER, service.selectForNewTask(
                "worker-1", "gpt-5.6-sol:ultra", "codex-worker", "task-sol").getRuntimeType());
        assertEquals(CodexRuntimeType.APP_SERVER, service.selectForNewTask(
                "worker-1", "gpt-5.4-mini", "codex-worker", "task-mini").getRuntimeType());
        assertEquals(CodexRuntimeType.APP_SERVER, service.selectForNewTask(
                "worker-1", "gpt-5.6-terra:ultra", "codex-worker", "task-terra").getRuntimeType());
        assertThrows(CodexRuntimeUnavailableException.class, () -> service.selectForNewTask(
                "worker-1", "gpt-5.4-mini:max", "codex-worker", "task-mini-max"));
        assertThrows(CodexRuntimeUnavailableException.class, () -> service.selectForNewTask(
                "worker-1", "gpt-5.4-mini:ultra", "codex-worker", "task-mini-ultra"));
        assertThrows(CodexRuntimeUnavailableException.class, () -> service.selectForNewTask(
                "worker-1", "gpt-5.6-luna:ultra", "codex-worker", "task-luna-ultra"));
        assertThrows(CodexRuntimeUnavailableException.class, () -> service.selectForNewTask(
                "worker-1", "gpt-5.6-sol:minimal", "codex-worker", "task-sol-minimal"));
    }

    @Test
    void legacyGlobalReasoningManifestUsesConservativeHighTierFallback() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        manifest.put("models", List.of("gpt-5.6-sol", "gpt-5.4-mini"));
        entity.setCapabilityManifestJson(objectMapper.writeValueAsString(manifest));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        assertEquals(CodexRuntimeType.APP_SERVER, service.selectForNewTask(
                "worker-1", "gpt-5.6-sol:ultra", "codex-worker", "task-sol").getRuntimeType());
        assertEquals(CodexRuntimeType.APP_SERVER, service.selectForNewTask(
                "worker-1", "gpt-5.4-mini", "codex-worker", "task-mini").getRuntimeType());
        assertThrows(CodexRuntimeUnavailableException.class, () -> service.selectForNewTask(
                "worker-1", "gpt-5.4-mini:max", "codex-worker", "task-mini-max"));
        assertThrows(CodexRuntimeUnavailableException.class, () -> service.selectForNewTask(
                "worker-1", "gpt-5.4-mini:ultra", "codex-worker", "task-mini-ultra"));
    }

    @Test
    void staleCapabilityFailsSelectedCohort() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        entity.setLastCapabilityAt(LocalDateTime.now().minusMinutes(5));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "codex-latest",
                        "codex-worker", "task-1"));

        assertEquals("CODEX_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @Test
    void legacyReadyRowWithoutInstanceIdentityIsNotSelectable() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        entity.setInstanceId(null);
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask(
                        "worker-1", "codex-latest", "codex-worker", "task-1"));

        assertEquals("CODEX_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @Test
    void selectedCohortFailsClosedWhenTaskRequiresUnsupportedFeature() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "codex-latest",
                        "codex-worker", "task-1", java.util.Set.of("attachments")));
        assertEquals("CODEX_RUNTIME_UNAVAILABLE", error.getCode());

        CodexRuntimeBinding supported = service.selectForNewTask("worker-1", "codex-latest",
                "codex-worker", "task-2", java.util.Set.of("images", "approval:never"));
        assertEquals(CodexRuntimeType.APP_SERVER, supported.getRuntimeType());
    }

    @Test
    void selectedCohortRejectsReasoningNotDeclaredByManifest() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "codex-latest:extra-high",
                        "codex-worker", "task-1"));
    }

    @Test
    void legacyAffinityResolvesWithoutRegistryLookup() {
        CodexRuntimeBinding binding = service.resolveBoundRuntime("legacy-sdk:worker-1", 1, "worker-1");

        assertEquals(CodexRuntimeType.SDK_EXEC, binding.getRuntimeType());
        verify(repository, never()).findByRuntimeIdAndRevision(anyString(), any());
    }

    @Test
    void legacyAffinityCannotMoveToAnotherWorker() {
        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.resolveBoundRuntime("legacy-sdk:worker-1", 1, "worker-2"));

        assertEquals("CODEX_RUNTIME_AFFINITY_MISMATCH", error.getCode());
        verify(repository, never()).findByRuntimeIdAndRevision(anyString(), any());
    }

    @Test
    void boundRuntimeCannotCrossWorkers() {
        CodexRuntimeEntity entity = runtime("DRAINING", 0);
        when(repository.findByRuntimeIdAndRevision("app-main", 1)).thenReturn(Optional.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.resolveBoundRuntime("app-main", 1, "worker-2"));

        assertEquals("CODEX_RUNTIME_AFFINITY_MISMATCH", error.getCode());
    }

    @Test
    void boundRuntimeCannotMoveToAnotherStatefulInstance() {
        CodexRuntimeEntity entity = runtime("DRAINING", 0);
        when(repository.findByRuntimeIdAndRevision("app-main", 1)).thenReturn(Optional.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.resolveBoundRuntime("app-main", 1, "worker-1", "instance-old"));

        assertEquals("CODEX_RUNTIME_INSTANCE_AFFINITY_MISMATCH", error.getCode());
    }

    @Test
    void existingAppServerBindingWithoutInstanceIdentityFailsClosed() {
        CodexRuntimeEntity entity = runtime("DRAINING", 0);
        when(repository.findByRuntimeIdAndRevision("app-main", 1)).thenReturn(Optional.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.resolveBoundRuntime("app-main", 1, "worker-1", null));

        assertEquals("CODEX_RUNTIME_INSTANCE_AFFINITY_MISSING", error.getCode());
    }

    @Test
    void boundRuntimeRejectsAnEndpointThatReportedAReplacementInstance() {
        CodexRuntimeEntity entity = runtime("DRAINING", 0);
        entity.setReadinessStatus("INCOMPATIBLE");
        entity.setReadinessMessage("CAPABILITY_INSTANCE_ID_MISMATCH");
        when(repository.findByRuntimeIdAndRevision("app-main", 1)).thenReturn(Optional.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.resolveBoundRuntime("app-main", 1, "worker-1", "instance-a"));

        assertEquals("CODEX_RUNTIME_INSTANCE_AFFINITY_MISMATCH", error.getCode());
    }

    @Test
    void instanceIdentityMismatchRemainsQuarantinedAcrossLaterSuccessfulProbes() throws Exception {
        CodexRuntimeEntity entity = runtime("DRAINING", 0);
        entity.setReadinessStatus("INCOMPATIBLE");
        entity.setReadinessMessage("CAPABILITY_INSTANCE_ID_MISMATCH");
        stubRefresh(entity, topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST));

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("INCOMPATIBLE", result.getReadinessStatus());
        assertTrue(result.getReadinessMessage().contains("CAPABILITY_INSTANCE_ID_MISMATCH"));
    }

    @Test
    void exactBoundRuntimeReturnsImmutableEndpoint() {
        CodexRuntimeEntity entity = runtime("DRAINING", 0);
        when(repository.findByRuntimeIdAndRevision("app-main", 1)).thenReturn(Optional.of(entity));

        CodexRuntimeBinding binding = service.resolveBoundRuntime(
                "app-main", 1, "worker-1", "instance-a");

        assertEquals("http://127.0.0.1:3062", binding.getEndpointUrl());
    }

    @Test
    void scheduledRefreshIsolatesFailedRevision() throws Exception {
        CodexRuntimeEntity first = runtime("DARK", 0);
        CodexRuntimeEntity second = runtime("DARK", 0);
        second.setRuntimeId("app-second");
        when(repository.findByEnabledTrueOrderByUpdatedAtAsc()).thenReturn(List.of(first, second));
        when(repository.findByRuntimeIdAndRevision("app-main", 1)).thenReturn(Optional.of(first));
        when(repository.findByRuntimeIdAndRevision("app-second", 1)).thenReturn(Optional.of(second));
        when(repository.findByRuntimeIdAndRevisionForUpdate("app-main", 1)).thenReturn(Optional.of(first));
        when(repository.findByRuntimeIdAndRevisionForUpdate("app-second", 1)).thenReturn(Optional.of(second));
        when(clientFactory.getOrCreate(anyString(), anyString(), any(), any())).thenReturn(client);
        when(client.probeCapabilities())
                .thenReturn(Mono.error(new IllegalStateException("offline")))
                .thenReturn(Mono.just(probe(topLevelManifest("app-second", 1,
                        CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST))));

        service.refreshEnabledCapabilities();

        assertEquals("UNREACHABLE", first.getReadinessStatus());
        assertEquals("READY", second.getReadinessStatus());
        verify(client, times(2)).probeCapabilities();
    }

    private CodexRuntimeRegistrationForm registration() {
        CodexRuntimeRegistrationForm form = new CodexRuntimeRegistrationForm();
        form.setRuntimeId("app-main");
        form.setWorkerId("worker-1");
        form.setEndpointUrl("http://127.0.0.1:3062/");
        form.setAuthToken("runtime-token");
        return form;
    }

    private CodexRuntimeEntity runtime(String policy, int rollout) {
        CodexRuntimeEntity entity = new CodexRuntimeEntity();
        entity.setRuntimeId("app-main");
        entity.setRevision(1);
        entity.setWorkerId("worker-1");
        entity.setRuntimeType("APP_SERVER");
        entity.setEndpointUrl("http://127.0.0.1:3062");
        entity.setAuthTokenCiphertext("encrypted:runtime-token");
        entity.setInstanceId("instance-a");
        entity.setEnabled(true);
        entity.setRoutingPolicy(policy);
        entity.setRolloutPercentage(rollout);
        entity.setPriority(10);
        entity.setRoutingEpoch(1L);
        entity.setReadinessStatus("PENDING");
        entity.setExpectedCliVersion(CodexRuntimeRegistryService.PINNED_APP_SERVER_CLI_VERSION);
        entity.setExpectedSchemaDigest(CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        return entity;
    }

    private CodexRuntimeEntity readyRuntime(String policy, int rollout, String model) throws Exception {
        CodexRuntimeEntity entity = runtime(policy, rollout);
        entity.setReadinessStatus("READY");
        entity.setLastCapabilityAt(LocalDateTime.now());
        Map<String, Object> manifest = topLevelManifest("app-main", 1,
                CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        manifest.put("models", List.of(model));
        entity.setCapabilityManifestJson(objectMapper.writeValueAsString(manifest));
        return entity;
    }

    private void stubRefresh(CodexRuntimeEntity entity, Map<String, Object> manifest) {
        when(repository.findByRuntimeIdAndRevision(entity.getRuntimeId(), entity.getRevision()))
                .thenReturn(Optional.of(entity));
        when(repository.findByRuntimeIdAndRevisionForUpdate(entity.getRuntimeId(), entity.getRevision()))
                .thenReturn(Optional.of(entity));
        when(clientFactory.getOrCreate(anyString(), anyString(), any(), any())).thenReturn(client);
        when(client.probeCapabilities()).thenReturn(Mono.just(probe(manifest)));
    }

    private CodexWorkerClient.CapabilityProbe probe(Map<String, Object> manifest) {
        Object instanceId = manifest.get("instance_id");
        if (instanceId == null && manifest.get("runtime") instanceof Map<?, ?> runtime) {
            instanceId = runtime.containsKey("instance_id")
                    ? runtime.get("instance_id") : runtime.get("instanceId");
        }
        return new CodexWorkerClient.CapabilityProbe(
                manifest, instanceId != null ? instanceId.toString() : null);
    }

    private boolean isUltraCanarySelected(String routingKey) {
        try {
            return service.selectForNewTask(
                    "worker-1", "codex-ultra", "codex-worker", routingKey).getRuntimeType()
                    == CodexRuntimeType.APP_SERVER;
        } catch (CodexRuntimeUnavailableException e) {
            assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", e.getCode());
            return false;
        }
    }

    private Map<String, Object> topLevelManifest(String runtimeId, int revision, String schemaDigest) {
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("contract_version", "1");
        manifest.put("runtime_type", "APP_SERVER");
        manifest.put("runtime_id", runtimeId);
        manifest.put("runtime_revision", revision);
        manifest.put("instance_id", "instance-a");
        manifest.put("cli_version", "0.144.1");
        if (schemaDigest != null) manifest.put("schema_digest", schemaDigest);
        manifest.put("models", List.of("*"));
        manifest.put("reasoning_efforts",
                List.of("minimal", "low", "medium", "high", "xhigh", "max", "ultra"));
        manifest.put("model_aliases", Map.of(
                "codex-latest", "gpt-5.6-sol",
                "codex-mini", "gpt-5.4-mini"));
        manifest.put("readiness", Map.of("ready", true, "reasons", List.of()));
        manifest.put("features", appServerFeatures());
        return manifest;
    }

    private Map<String, Object> appServerFeatures() {
        return Map.ofEntries(
                Map.entry("task_accept", true),
                Map.entry("idempotency_key", true),
                Map.entry("durable_acceptance", true),
                Map.entry("durable_events", true),
                Map.entry("abort", true),
                Map.entry("committed_reconciliation", true),
                Map.entry("instance_affinity_guard", true),
                Map.entry("native_subtask_contract_versions", List.of(1)),
                Map.entry("images", true),
                Map.entry("attachments", false),
                Map.entry("approval_modes", List.of("never")),
                Map.entry("additional_directories", false),
                Map.entry("business_mcp", false),
                Map.entry("max_turns", false));
    }
}
