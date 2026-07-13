package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.entity.CodexAppServerEndpointEntity;
import com.foggy.navigator.codex.worker.model.entity.CodexRuntimeEntity;
import com.foggy.navigator.codex.worker.model.form.CodexRuntimeLifecycleForm;
import com.foggy.navigator.codex.worker.model.form.CodexRuntimeRoutingForm;
import com.foggy.navigator.codex.worker.repository.CodexAppServerEndpointRepository;
import com.foggy.navigator.codex.worker.repository.CodexRuntimeRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private CodexAppServerEndpointRepository endpointRepository;
    private CredentialEncryptor encryptor;
    private CodexWorkerClientFactory clientFactory;
    private CodexWorkerClient client;
    private ObjectMapper objectMapper;
    private CodexRuntimeCapabilityStateService capabilityStateService;
    private CodexRuntimeRegistryService service;

    @BeforeEach
    void setUp() {
        repository = mock(CodexRuntimeRepository.class);
        endpointRepository = mock(CodexAppServerEndpointRepository.class);
        encryptor = mock(CredentialEncryptor.class);
        clientFactory = mock(CodexWorkerClientFactory.class);
        client = mock(CodexWorkerClient.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        capabilityStateService = new CodexRuntimeCapabilityStateService(repository);
        service = new CodexRuntimeRegistryService(
                repository, endpointRepository, encryptor, clientFactory, objectMapper, capabilityStateService);
        ReflectionTestUtils.setField(service, "capabilityMaxAgeSeconds", 120L);
        when(repository.save(any(CodexRuntimeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(encryptor.encrypt(anyString())).thenAnswer(invocation -> "encrypted:" + invocation.getArgument(0));
        when(encryptor.decrypt(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return value.startsWith("encrypted:") ? value.substring("encrypted:".length()) : value;
        });
        when(endpointRepository.findByEndpointId(anyString())).thenAnswer(invocation ->
                Optional.of(endpoint(invocation.getArgument(0))));
    }

    @Test
    void endpointSyncCreatesManagedRuntimeFromEndpointAndWorkerCapability() {
        CodexAppServerEndpointEntity endpoint = endpoint("endpoint-0123456789abcdef");
        Map<String, Object> manifest = topLevelManifest("codex-app-server-primary", 2,
                CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        when(endpointRepository.findByEndpointIdForUpdate(endpoint.getEndpointId()))
                .thenReturn(Optional.of(endpoint));
        when(repository.findByEndpointIdOrderByRevisionDesc(endpoint.getEndpointId()))
                .thenReturn(List.of());
        when(repository.findMaxRevision("appserver-0123456789abcdef")).thenReturn(0);
        when(clientFactory.getOrCreate(anyString(), anyString(), any(), any())).thenReturn(client);
        when(client.probeCapabilities()).thenReturn(Mono.just(probe(manifest)));

        var result = service.synchronizeEndpoint(endpoint.getEndpointId());

        assertEquals(true, result.getRuntimeCreated());
        assertEquals("appserver-0123456789abcdef", result.getRuntime().getRuntimeId());
        assertEquals(1, result.getRuntime().getRevision());
        assertEquals("ENDPOINT_SYNC", result.getRuntime().getRuntimeSource());
        assertEquals("codex-app-server-primary", result.getRuntime().getReportedRuntimeId());
        assertEquals(2, result.getRuntime().getReportedRuntimeRevision());
        assertEquals("READY", result.getRuntime().getReadinessStatus());
        assertEquals("READY", endpoint.getLastSyncStatus());
        verify(repository).save(any(CodexRuntimeEntity.class));
        verify(endpointRepository).save(endpoint);
    }

    @Test
    void endpointSyncKeepsCurrentRuntimeWhenCapabilityFingerprintIsUnchanged() throws Exception {
        CodexAppServerEndpointEntity endpoint = endpoint("endpoint-0123456789abcdef");
        Map<String, Object> manifest = topLevelManifest("codex-app-server-primary", 2,
                CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        CodexRuntimeEntity current = runtime("DARK", 0);
        current.setRuntimeId("appserver-0123456789abcdef");
        current.setRuntimeSource("ENDPOINT_SYNC");
        current.setEndpointId(endpoint.getEndpointId());
        current.setReportedRuntimeId("codex-app-server-primary");
        current.setReportedRuntimeRevision(2);
        current.setCapabilityFingerprint(ReflectionTestUtils.invokeMethod(
                service, "capabilityFingerprint", endpoint, manifest, "instance-a"));
        when(endpointRepository.findByEndpointIdForUpdate(endpoint.getEndpointId()))
                .thenReturn(Optional.of(endpoint));
        when(repository.findByEndpointIdOrderByRevisionDesc(endpoint.getEndpointId()))
                .thenReturn(List.of(current));
        when(clientFactory.getOrCreate(anyString(), anyString(), any(), any())).thenReturn(client);
        when(client.probeCapabilities()).thenReturn(Mono.just(probe(manifest)));

        var result = service.synchronizeEndpoint(endpoint.getEndpointId());

        assertEquals(false, result.getRuntimeCreated());
        assertEquals(1, result.getRuntime().getRevision());
        assertEquals("READY", result.getRuntime().getReadinessStatus());
        verify(repository, never()).findMaxRevision(anyString());
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
    void deletedEndpointRejectsNewRoutingAndUnarchiveButKeepsBoundAffinity() {
        CodexRuntimeEntity entity = runtime("ALL_DEFAULT", 100);
        entity.setReadinessStatus("READY");
        entity.setArchivedAt(LocalDateTime.now());
        when(endpointRepository.findByEndpointId("endpoint-main")).thenReturn(Optional.empty());
        when(repository.findByRuntimeIdAndRevisionForUpdate("app-main", 1)).thenReturn(Optional.of(entity));
        when(repository.findByRuntimeIdAndRevision("app-main", 1)).thenReturn(Optional.of(entity));

        CodexRuntimeRoutingForm routing = new CodexRuntimeRoutingForm();
        routing.setExpectedRoutingEpoch(1L);
        assertThrows(IllegalStateException.class,
                () -> service.updateRouting("app-main", 1, routing));

        CodexRuntimeLifecycleForm lifecycle = new CodexRuntimeLifecycleForm();
        lifecycle.setExpectedRoutingEpoch(1L);
        assertThrows(IllegalStateException.class,
                () -> service.unarchiveRevision("app-main", 1, lifecycle));

        CodexRuntimeBinding bound = service.resolveBoundRuntime(
                "app-main", 1, "worker-1", "instance-a");
        assertEquals("app-main", bound.getRuntimeId());
    }

    @Test
    void deletedEndpointIsExcludedFromNewTaskSelection() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "gpt-5.6-sol");
        when(endpointRepository.findByEndpointId("endpoint-main")).thenReturn(Optional.empty());
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(entity));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask(
                        "worker-1", "gpt-5.6-sol:high",
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));

        assertEquals("CODEX_RUNTIME_UNAVAILABLE", error.getCode());
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
        assertEquals("http://127.0.0.1:3062", result.getEndpointDisplay());
        assertEquals(true, result.getCapabilityFresh());
        assertEquals(true, result.getSupportsUltra());
    }

    @Test
    void refreshCapabilitiesAcceptsDifferentCliVersionWhenNoVersionConstraintIsConfigured() throws Exception {
        CodexRuntimeEntity entity = runtime("ULTRA_DEFAULT", 100);
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        manifest.put("cli_version", "0.144.3");
        stubRefresh(entity, manifest);

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("READY", result.getReadinessStatus());
        assertEquals("0.144.3", result.getCliVersion());
        assertEquals("", result.getExpectedCliVersion());
    }

    @Test
    void refreshCapabilitiesRejectsDifferentCliVersionWhenEnvConstraintIsConfigured() throws Exception {
        ReflectionTestUtils.setField(service, "expectedCliVersion", "0.144.3");
        CodexRuntimeEntity entity = runtime("ULTRA_DEFAULT", 100);
        stubRefresh(entity, topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST));

        var result = service.refreshCapabilities("app-main", 1);

        assertEquals("INCOMPATIBLE", result.getReadinessStatus());
        assertEquals("0.144.3", result.getExpectedCliVersion());
        assertTrue(result.getReadinessMessage().contains("CAPABILITY_CLI_VERSION_MISMATCH"));
    }

    @Test
    void endpointDisplayPreservesOriginWithoutLegacyUrlSecrets() {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        entity.setEndpointUrl("https://user:password@192.168.31.119:3071/internal?token=secret#fragment");
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(entity));

        var result = service.listByWorker("worker-1");

        assertEquals("https://192.168.31.119:3071", result.get(0).getEndpointDisplay());
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
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));
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
                "worker-1", "codex-latest", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1");

        assertEquals(List.of("a-runtime", "z-runtime"),
                listed.stream().map(runtime -> runtime.getRuntimeId()).toList());
        assertEquals("a-runtime", selected.getRuntimeId());
    }

    @Test
    void archiveRevisionStopsNewRoutingButPreservesBoundRuntimeAffinity() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "gpt-5.6-sol");
        entity.setRoutingEpoch(7L);
        when(repository.findByRuntimeIdAndRevisionForUpdate("app-main", 1))
                .thenReturn(Optional.of(entity));
        when(repository.findByRuntimeIdAndRevision("app-main", 1))
                .thenReturn(Optional.of(entity));
        CodexRuntimeLifecycleForm form = lifecycle(7L);

        var archived = service.archiveRevision("app-main", 1, form);

        assertTrue(archived.getArchived());
        assertFalse(archived.getEnabled());
        assertEquals("DARK", archived.getRoutingPolicy());
        assertEquals(0, archived.getRolloutPercentage());
        assertEquals(8L, archived.getRoutingEpoch());
        assertTrue(archived.getArchivedAt() != null);
        CodexRuntimeBinding bound = service.resolveBoundRuntime(
                "app-main", 1, "worker-1", "instance-a");
        assertEquals(CodexRuntimeType.APP_SERVER, bound.getRuntimeType());

        CodexRuntimeRoutingForm routing = new CodexRuntimeRoutingForm();
        routing.setExpectedRoutingEpoch(8L);
        routing.setEnabled(true);
        assertThrows(IllegalStateException.class,
                () -> service.updateRouting("app-main", 1, routing));
    }

    @Test
    void unarchiveRevisionRestoresOnlyDisabledDarkStateAndRequiresCurrentEpoch() {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        entity.setEnabled(false);
        entity.setRoutingEpoch(8L);
        entity.setArchivedAt(LocalDateTime.now());
        when(repository.findByRuntimeIdAndRevisionForUpdate("app-main", 1))
                .thenReturn(Optional.of(entity));

        assertThrows(IllegalStateException.class,
                () -> service.unarchiveRevision("app-main", 1, lifecycle(7L)));
        var restored = service.unarchiveRevision("app-main", 1, lifecycle(8L));

        assertFalse(restored.getArchived());
        assertEquals(null, restored.getArchivedAt());
        assertFalse(restored.getEnabled());
        assertEquals("DARK", restored.getRoutingPolicy());
        assertEquals(0, restored.getRolloutPercentage());
        assertEquals(9L, restored.getRoutingEpoch());
    }

    @Test
    void activeListAndAvailabilityExcludeArchivedRevisions() {
        CodexRuntimeEntity active = runtime("DARK", 0);
        active.setRuntimeId("active");
        CodexRuntimeEntity archived = runtime("ALL_DEFAULT", 100);
        archived.setRuntimeId("archived");
        archived.setArchivedAt(LocalDateTime.now());
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(archived, active));

        var activeOnly = service.listByWorker("worker-1");
        var withArchived = service.listByWorker("worker-1", true);
        var availability = service.availability("worker-1");

        assertEquals(List.of("active"),
                activeOnly.stream().map(runtime -> runtime.getRuntimeId()).toList());
        assertEquals(2, withArchived.size());
        assertTrue(availability.getAppServerManaged());
        assertFalse(availability.getUltraAvailable());
    }

    @Test
    void archivedRevisionCannotBeSelectedForANewTaskEvenIfStoredAsEnabled() throws Exception {
        CodexRuntimeEntity archived = readyRuntime("ALL_DEFAULT", 100, "gpt-5.6-sol");
        archived.setArchivedAt(LocalDateTime.now());
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(archived));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(archived));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask(
                        "worker-1", "codex-latest",
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));

        assertEquals("CODEX_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @ParameterizedTest
    @CsvSource({
            "DARK,0,false",
            "DRAINING,100,false",
            "ULTRA_CANARY,0,false",
            "ULTRA_CANARY,1,true",
            "ULTRA_DEFAULT,0,true",
            "ALL_CANARY,0,true",
            "ALL_DEFAULT,0,true"
    })
    void availabilityUsesUltraRoutingSemantics(String policy, int rollout, boolean expected)
            throws Exception {
        CodexRuntimeEntity entity = readyRuntime(policy, rollout, "gpt-5.6-sol");
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(entity));

        var result = service.availability("worker-1");

        assertTrue(result.getAppServerManaged());
        assertEquals(expected, result.getModelAvailable());
        assertEquals(expected, result.getUltraAvailable());
        assertEquals(expected ? null : "CODEX_ULTRA_RUNTIME_UNAVAILABLE",
                result.getBlockReason());
    }

    @Test
    void availabilityRequiresEnabledReadyFreshAndUltraCapability() throws Exception {
        CodexRuntimeEntity disabled = readyRuntime("ULTRA_DEFAULT", 100, "gpt-5.6-sol");
        disabled.setEnabled(false);
        CodexRuntimeEntity notReady = readyRuntime("ULTRA_DEFAULT", 100, "gpt-5.6-sol");
        notReady.setReadinessStatus("INCOMPATIBLE");
        CodexRuntimeEntity stale = readyRuntime("ULTRA_DEFAULT", 100, "gpt-5.6-sol");
        stale.setLastCapabilityAt(LocalDateTime.now().minusMinutes(5));
        CodexRuntimeEntity noUltra = readyRuntime("ULTRA_DEFAULT", 100, "gpt-5.5");

        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(disabled, notReady, stale, noUltra));

        var result = service.availability("worker-1");

        assertTrue(result.getAppServerManaged());
        assertTrue(result.getModelSupported());
        assertFalse(result.getUltraAvailable());
        assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", result.getBlockReason());
    }

    @Test
    void availabilityDistinguishesRegisteredAppServerFromAbsentRuntimeWithoutDetails() {
        CodexRuntimeEntity dark = runtime("DARK", 0);
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(dark));
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-2"))
                .thenReturn(List.of());

        var managed = service.availability("worker-1");
        var absent = service.availability("worker-2");

        assertTrue(managed.getAppServerManaged());
        assertFalse(managed.getUltraAvailable());
        assertFalse(absent.getAppServerManaged());
        assertFalse(absent.getUltraAvailable());
        assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", absent.getBlockReason());
    }

    @Test
    void availabilityUsesRequestedModelAndPerModelReasoningMatrix() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ULTRA_DEFAULT", 100, "*");
        setModelCapabilities(entity, Map.of(
                "codex-ultra", "gpt-5.6-sol:ultra",
                "codex-terra", "gpt-5.6-terra",
                "codex-luna", "gpt-5.6-luna"), Map.of(
                "gpt-5.6-sol", List.of("ultra"),
                "gpt-5.6-terra", List.of("ultra"),
                "gpt-5.6-luna", List.of("max")));
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(entity));

        assertTrue(service.availability("worker-1").getUltraAvailable());
        assertTrue(service.availability("worker-1", "codex-terra:ultra").getUltraAvailable());
        assertFalse(service.availability("worker-1", "codex-luna:ultra").getUltraAvailable());
    }

    @Test
    void nonUltraAvailabilityUsesReadyModelAndAllRoutingPolicy() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "gpt-5.6-sol");
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(entity));

        var result = service.availability("worker-1", "gpt-5.6-sol:high");

        assertTrue(result.getModelAvailable());
        assertFalse(result.getUltraAvailable());
        assertEquals(null, result.getBlockReason());
    }

    @Test
    void nonUltraAvailabilityFailsClosedForDarkDisabledStaleAndUnsupportedRuntime() throws Exception {
        CodexRuntimeEntity dark = readyRuntime("DARK", 100, "gpt-5.6-sol");
        CodexRuntimeEntity disabled = readyRuntime("ALL_DEFAULT", 100, "gpt-5.6-sol");
        disabled.setEnabled(false);
        CodexRuntimeEntity stale = readyRuntime("ALL_DEFAULT", 100, "gpt-5.6-sol");
        stale.setLastCapabilityAt(LocalDateTime.now().minusMinutes(5));
        CodexRuntimeEntity unsupported = readyRuntime("ALL_DEFAULT", 100, "gpt-5.5");
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(dark), List.of(disabled), List.of(stale), List.of(unsupported));

        for (int attempt = 0; attempt < 4; attempt++) {
            var result = service.availability("worker-1", "gpt-5.6-sol:high");
            assertFalse(result.getModelAvailable());
            assertEquals("CODEX_RUNTIME_UNAVAILABLE", result.getBlockReason());
        }
    }

    @Test
    void availabilityFailsClosedForConflictingCandidateAliases() throws Exception {
        CodexRuntimeEntity first = readyRuntime("ULTRA_DEFAULT", 100, "*");
        CodexRuntimeEntity second = readyRuntime("ULTRA_DEFAULT", 100, "*");
        second.setRuntimeId("app-second");
        setModelCapabilities(first, Map.of("custom-tier", "gpt-5.6-sol:ultra"),
                Map.of("gpt-5.6-sol", List.of("ultra")));
        setModelCapabilities(second, Map.of("custom-tier", "gpt-5.6-terra:ultra"),
                Map.of("gpt-5.6-terra", List.of("ultra")));
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(first, second));

        var result = service.availability("worker-1", "custom-tier");

        assertTrue(result.getAppServerManaged());
        assertFalse(result.getModelAvailable());
        assertFalse(result.getUltraAvailable());
        assertEquals(CodexRuntimeRegistryService.MODEL_ALIAS_CONFLICT_CODE,
                result.getBlockReason());
    }

    @Test
    void availabilityUsesDisabledManifestAliasSemanticsButReportsUnavailable() throws Exception {
        CodexRuntimeEntity disabled = readyRuntime("ULTRA_DEFAULT", 100, "*");
        disabled.setEnabled(false);
        setModelCapabilities(disabled, Map.of("custom-ultra", "gpt-5.6-sol:ultra"),
                Map.of("gpt-5.6-sol", List.of("ultra")));
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(disabled));

        var result = service.availability("worker-1", "custom-ultra");

        assertTrue(result.getAppServerManaged());
        assertTrue(result.getModelSupported());
        assertFalse(result.getModelAvailable());
        assertFalse(result.getUltraAvailable());
        assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", result.getBlockReason());
    }

    @Test
    void ultraRoutingRequiresNativeSubtaskContractV1() throws Exception {
        CodexRuntimeEntity entity = runtime("ULTRA_DEFAULT", 100);
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        Map<String, Object> features = new java.util.LinkedHashMap<>(appServerFeatures());
        features.put("native_subtask_contract_versions", List.of(2));
        manifest.put("features", features);
        manifest.put("model_aliases", Map.of(
                "codex-latest", "gpt-5.6-sol",
                "codex-terra", "gpt-5.6-terra",
                "custom-ultra", "gpt-5.6-sol:ultra"));
        stubRefresh(entity, manifest);

        var result = service.refreshCapabilities("app-main", 1);
        assertEquals("READY", result.getReadinessStatus());
        assertFalse(result.getSupportsUltra());

        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(entity));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));
        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "codex-ultra",
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));
        assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", error.getCode());
        CodexRuntimeUnavailableException customError = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "custom-ultra",
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-2"));
        assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", customError.getCode());
    }

    @Test
    void refreshFailureExposesSafeEndpointDisplayButNotTokenInReadinessPayload() throws Exception {
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
        assertTrue(payload.contains(sentinelEndpoint));
        assertFalse(payload.contains(sentinelToken));
    }

    @Test
    void newUltraWithoutRuntimeFailsClosed() {
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of());

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "codex-ultra", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));

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
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));

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

    @ParameterizedTest
    @ValueSource(strings = {"codex-latest", "gpt-5.6-sol:high"})
    void defaultAliasAndRealNonUltraModelFailClosedWhenRuntimeIsDark(String model) {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(entity));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask(
                        "worker-1", model,
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));

        assertEquals("CODEX_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @Test
    void manifestAliasCanRouteAnUltraRequestToAppServer() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ULTRA_DEFAULT", 100, "*");
        setModelCapabilities(entity, Map.of("custom-ultra", "gpt-5.6-sol:ultra"),
                Map.of("gpt-5.6-sol", List.of("ultra")));
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(entity));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeBinding binding = service.selectForNewTask(
                "worker-1", "custom-ultra", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1");

        assertEquals(CodexRuntimeType.APP_SERVER, binding.getRuntimeType());
    }

    @Test
    void manifestUltraAliasNeverFallsBackToSdkOutsideTheActiveCohort() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("DARK", 0, "*");
        setModelCapabilities(entity, Map.of("custom-ultra", "gpt-5.6-sol:ultra"),
                Map.of("gpt-5.6-sol", List.of("ultra")));
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(entity));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask(
                        "worker-1", "custom-ultra", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));

        assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @Test
    void manifestNonUltraAliasNeverFallsBackToSdkWhenRuntimeIsDark() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("DARK", 0, "*");
        setModelCapabilities(entity, Map.of("custom-high", "gpt-5.6-sol:high"),
                Map.of("gpt-5.6-sol", List.of("high")));
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(entity));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask(
                        "worker-1", "custom-high", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));

        assertEquals("CODEX_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @Test
    void disabledManifestUltraAliasNeverFallsBackToSdk() throws Exception {
        CodexRuntimeEntity disabled = readyRuntime("ULTRA_DEFAULT", 100, "*");
        disabled.setEnabled(false);
        setModelCapabilities(disabled, Map.of("custom-ultra", "gpt-5.6-sol:ultra"),
                Map.of("gpt-5.6-sol", List.of("ultra")));
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(disabled));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of());

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask(
                        "worker-1", "custom-ultra", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));

        assertEquals("CODEX_ULTRA_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-5.6-sol:high", "gpt-5.6-terra:ultra"})
    void conflictingCandidateAliasResolutionFailsClosed(String secondTarget) throws Exception {
        CodexRuntimeEntity first = readyRuntime("ULTRA_DEFAULT", 100, "*");
        CodexRuntimeEntity second = readyRuntime("ULTRA_DEFAULT", 100, "*");
        second.setRuntimeId("app-second");
        setModelCapabilities(first, Map.of("custom-tier", "gpt-5.6-sol:ultra"),
                Map.of("gpt-5.6-sol", List.of("high", "ultra")));
        setModelCapabilities(second, Map.of("custom-tier", secondTarget), Map.of(
                "gpt-5.6-sol", List.of("high", "ultra"),
                "gpt-5.6-terra", List.of("ultra")));
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(first, second));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(first, second));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask(
                        "worker-1", "custom-tier", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));

        assertEquals(CodexRuntimeRegistryService.MODEL_ALIAS_CONFLICT_CODE, error.getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "codex-latest", "codex-terra", "codex-luna", "codex-fast", "codex-deep", "codex-xhigh",
            "codex-max", "codex-ultra", "codex-latest:minimal", "codex-latest:medium", "gpt-5.6-sol",
            "gpt-5.6-terra", "gpt-5.6-luna",
            "gpt-5.6-sol:low", "gpt-5.6-sol:high", "gpt-5.6-sol:xhigh", "gpt-5.6-sol:max",
            "gpt-5.6-sol:ultra"
    })
    void allSupportedAliasesAndExplicitModelsRouteToAppServer(String model) throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeBinding binding = service.selectForNewTask(
                "worker-1", model, CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1");

        assertEquals(CodexRuntimeType.APP_SERVER, binding.getRuntimeType());
        assertEquals("http://127.0.0.1:3062", binding.getEndpointUrl());
    }

    @Test
    void perModelReasoningMatrixRejectsUnsupportedNonSolTiers() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        manifest.put("models", List.of(
                "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5"));
        manifest.put("model_reasoning_matrix", Map.of(
                "gpt-5.6-sol", List.of("low", "medium", "high", "xhigh", "max", "ultra"),
                "gpt-5.6-terra", List.of("low", "medium", "high", "xhigh", "max", "ultra"),
                "gpt-5.6-luna", List.of("low", "medium", "high", "xhigh", "max"),
                "gpt-5.5", List.of("low", "medium", "high", "xhigh")));
        entity.setCapabilityManifestJson(objectMapper.writeValueAsString(manifest));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        assertEquals(CodexRuntimeType.APP_SERVER, service.selectForNewTask(
                "worker-1", "gpt-5.6-sol:ultra", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-sol").getRuntimeType());
        assertEquals(CodexRuntimeType.APP_SERVER, service.selectForNewTask(
                "worker-1", "gpt-5.5", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-gpt55").getRuntimeType());
        assertEquals(CodexRuntimeType.APP_SERVER, service.selectForNewTask(
                "worker-1", "gpt-5.6-terra:ultra", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-terra").getRuntimeType());
        assertEquals(CodexRuntimeType.APP_SERVER, service.selectForNewTask(
                "worker-1", "codex-terra:max", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-terra-max").getRuntimeType());
        assertEquals(CodexRuntimeType.APP_SERVER, service.selectForNewTask(
                "worker-1", "codex-luna:max", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-luna-max").getRuntimeType());
        assertThrows(CodexRuntimeUnavailableException.class, () -> service.selectForNewTask(
                "worker-1", "gpt-5.5:max", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-gpt55-max"));
        assertThrows(CodexRuntimeUnavailableException.class, () -> service.selectForNewTask(
                "worker-1", "gpt-5.5:ultra", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-gpt55-ultra"));
        assertThrows(CodexRuntimeUnavailableException.class, () -> service.selectForNewTask(
                "worker-1", "gpt-5.6-luna:ultra", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-luna-ultra"));
        assertThrows(CodexRuntimeUnavailableException.class, () -> service.selectForNewTask(
                "worker-1", "gpt-5.6-sol:minimal", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-sol-minimal"));
    }

    @Test
    void legacyGlobalReasoningManifestUsesConservativeHighTierFallback() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        manifest.put("models", List.of("gpt-5.6-sol", "gpt-5.5"));
        entity.setCapabilityManifestJson(objectMapper.writeValueAsString(manifest));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        assertEquals(CodexRuntimeType.APP_SERVER, service.selectForNewTask(
                "worker-1", "gpt-5.6-sol:ultra", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-sol").getRuntimeType());
        assertEquals(CodexRuntimeType.APP_SERVER, service.selectForNewTask(
                "worker-1", "gpt-5.5", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-gpt55").getRuntimeType());
        assertThrows(CodexRuntimeUnavailableException.class, () -> service.selectForNewTask(
                "worker-1", "gpt-5.5:max", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-gpt55-max"));
        assertThrows(CodexRuntimeUnavailableException.class, () -> service.selectForNewTask(
                "worker-1", "gpt-5.5:ultra", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-gpt55-ultra"));
    }

    @Test
    void staleCapabilityFailsSelectedCohort() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        entity.setLastCapabilityAt(LocalDateTime.now().minusMinutes(5));
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "codex-latest",
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));

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
                        "worker-1", "codex-latest", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));

        assertEquals("CODEX_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @Test
    void selectedCohortFailsClosedWhenTaskRequiresUnsupportedFeature() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "codex-latest",
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1", java.util.Set.of("attachments")));
        assertEquals("CODEX_RUNTIME_UNAVAILABLE", error.getCode());

        CodexRuntimeBinding supported = service.selectForNewTask("worker-1", "codex-latest",
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-2", java.util.Set.of("images", "approval:never"));
        assertEquals(CodexRuntimeType.APP_SERVER, supported.getRuntimeType());
    }

    @Test
    void selectedCohortRejectsReasoningNotDeclaredByManifest() throws Exception {
        CodexRuntimeEntity entity = readyRuntime("ALL_DEFAULT", 100, "*");
        when(repository.findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                "worker-1", "APP_SERVER")).thenReturn(List.of(entity));

        assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.selectForNewTask("worker-1", "codex-latest:extra-high",
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-1"));
    }

    @Test
    void legacyAffinityIsRejectedByAppServerRegistry() {
        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.resolveBoundRuntime("legacy-sdk:worker-1", 1, "worker-1"));

        assertEquals("CODEX_PROVIDER_RUNTIME_MISMATCH", error.getCode());
        verify(repository, never()).findByRuntimeIdAndRevision(anyString(), any());
    }

    @Test
    void legacyAffinityForAnotherWorkerIsStillRejectedByProviderBoundary() {
        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.resolveBoundRuntime("legacy-sdk:worker-1", 1, "worker-2"));

        assertEquals("CODEX_PROVIDER_RUNTIME_MISMATCH", error.getCode());
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
    void boundRuntimeCapabilitiesRejectUnsupportedModelAndFeatures() throws Exception {
        CodexRuntimeEntity entity = runtime("DRAINING", 0);
        setModelCapabilities(entity, Map.of("codex-terra", "gpt-5.6-terra"),
                Map.of("gpt-5.6-terra", List.of("high", "ultra")));
        when(repository.findByRuntimeIdAndRevision("app-main", 1)).thenReturn(Optional.of(entity));
        CodexRuntimeBinding binding = service.resolveBoundRuntime(
                "app-main", 1, "worker-1", "instance-a");

        service.validateBoundRuntimeCapabilities(binding, "codex-terra:ultra", Set.of());
        CodexRuntimeUnavailableException modelError = assertThrows(
                CodexRuntimeUnavailableException.class,
                () -> service.validateBoundRuntimeCapabilities(
                        binding, "codex-luna:ultra", Set.of()));
        CodexRuntimeUnavailableException featureError = assertThrows(
                CodexRuntimeUnavailableException.class,
                () -> service.validateBoundRuntimeCapabilities(
                        binding, "codex-terra:high", Set.of("attachments")));

        assertEquals("CODEX_BOUND_RUNTIME_CAPABILITY_MISMATCH", modelError.getCode());
        assertEquals("CODEX_BOUND_RUNTIME_CAPABILITY_MISMATCH", featureError.getCode());
    }

    @Test
    void terraOnlyRuntimeStillDeclaresUltraSupport() throws Exception {
        CodexRuntimeEntity entity = runtime("DARK", 0);
        setModelCapabilities(entity, Map.of("codex-terra", "gpt-5.6-terra"),
                Map.of("gpt-5.6-terra", List.of("high", "ultra")));
        when(repository.findByWorkerIdOrderByPriorityDescRevisionDesc("worker-1"))
                .thenReturn(List.of(entity));

        assertTrue(service.listByWorker("worker-1").get(0).getSupportsUltra());
    }

    @Test
    void endpointConnectionProbeChecksPerModelReasoningMatrix() throws Exception {
        CodexAppServerEndpointEntity endpoint = endpoint("endpoint-model-probe");
        Map<String, Object> manifest = topLevelManifest(
                "app-main", 1, CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        manifest.put("models", List.of("gpt-5.6-luna"));
        manifest.put("model_reasoning_matrix", Map.of(
                "gpt-5.6-luna", List.of("high", "max")));
        when(endpointRepository.findByWorkerIdOrderByUpdatedAtDesc("worker-1"))
                .thenReturn(List.of(endpoint));
        when(clientFactory.getOrCreate(anyString(), anyString(), any(), any())).thenReturn(client);
        when(client.probeCapabilities()).thenReturn(Mono.just(probe(manifest)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.testEndpointConnection("worker-1", "gpt-5.6-luna:ultra"));

        assertEquals("CODEX_APP_SERVER_MODEL_UNSUPPORTED", error.getMessage());
    }

    @Test
    void scheduledRefreshIsolatesFailedRevision() throws Exception {
        CodexRuntimeEntity first = runtime("DARK", 0);
        CodexRuntimeEntity second = runtime("DARK", 0);
        second.setRuntimeId("app-second");
        second.setReportedRuntimeId("app-second");
        when(repository.findByArchivedAtIsNullOrderByUpdatedAtAsc()).thenReturn(List.of(first, second));
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

    private CodexAppServerEndpointEntity endpoint(String endpointId) {
        CodexAppServerEndpointEntity endpoint = new CodexAppServerEndpointEntity();
        endpoint.setEndpointId(endpointId);
        endpoint.setWorkerId("worker-1");
        endpoint.setEndpointUrl("http://127.0.0.1:3071");
        endpoint.setAuthTokenCiphertext("encrypted:runtime-token");
        endpoint.setConfigurationVersion(1L);
        endpoint.setLastSyncStatus("PENDING");
        return endpoint;
    }

    private CodexRuntimeEntity runtime(String policy, int rollout) {
        CodexRuntimeEntity entity = new CodexRuntimeEntity();
        entity.setRuntimeId("app-main");
        entity.setRevision(1);
        entity.setWorkerId("worker-1");
        entity.setRuntimeType("APP_SERVER");
        entity.setRuntimeSource("ENDPOINT_SYNC");
        entity.setEndpointId("endpoint-main");
        entity.setReportedRuntimeId("app-main");
        entity.setReportedRuntimeRevision(1);
        entity.setEndpointUrl("http://127.0.0.1:3062");
        entity.setAuthTokenCiphertext("encrypted:runtime-token");
        entity.setInstanceId("instance-a");
        entity.setEnabled(true);
        entity.setRoutingPolicy(policy);
        entity.setRolloutPercentage(rollout);
        entity.setPriority(10);
        entity.setRoutingEpoch(1L);
        entity.setReadinessStatus("PENDING");
        entity.setExpectedCliVersion("");
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
                    "worker-1", "codex-ultra", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, routingKey).getRuntimeType()
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
                "codex-terra", "gpt-5.6-terra"));
        manifest.put("readiness", Map.of("ready", true, "reasons", List.of()));
        manifest.put("features", appServerFeatures());
        return manifest;
    }

    private CodexRuntimeLifecycleForm lifecycle(long expectedRoutingEpoch) {
        CodexRuntimeLifecycleForm form = new CodexRuntimeLifecycleForm();
        form.setExpectedRoutingEpoch(expectedRoutingEpoch);
        return form;
    }

    private void setModelCapabilities(
            CodexRuntimeEntity entity,
            Map<String, String> aliases,
            Map<String, List<String>> reasoningMatrix) throws Exception {
        Map<String, Object> manifest = topLevelManifest(
                entity.getRuntimeId(), entity.getRevision(),
                CodexRuntimeRegistryService.PINNED_SCHEMA_DIGEST);
        manifest.put("models", reasoningMatrix.keySet().stream().toList());
        manifest.put("model_aliases", aliases);
        manifest.put("model_reasoning_matrix", reasoningMatrix);
        entity.setCapabilityManifestJson(objectMapper.writeValueAsString(manifest));
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
