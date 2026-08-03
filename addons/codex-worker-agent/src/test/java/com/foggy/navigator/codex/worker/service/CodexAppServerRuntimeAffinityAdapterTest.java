package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodexAppServerRuntimeAffinityAdapterTest {

    private CodexRuntimeRegistryService runtimeRegistryService;
    private CodexWorkerClientFactory clientFactory;
    private CodexWorkerClient client;
    private CodexAppServerRuntimeAffinityAdapter adapter;

    @BeforeEach
    void setUp() {
        runtimeRegistryService = mock(CodexRuntimeRegistryService.class);
        clientFactory = mock(CodexWorkerClientFactory.class);
        client = mock(CodexWorkerClient.class);
        adapter = new CodexAppServerRuntimeAffinityAdapter(
                runtimeRegistryService, clientFactory);
    }

    @Test
    void isExactServiceWithOnlyFrozenDependenciesAndSevenFieldPublicAffinity() {
        assertTrue(CodexAppServerRuntimeAffinityAdapter.class
                .isAnnotationPresent(Service.class));
        assertEquals(List.of(
                        CodexRuntimeRegistryService.class,
                        CodexWorkerClientFactory.class),
                List.of(CodexAppServerRuntimeAffinityAdapter.class
                        .getDeclaredConstructors()[0].getParameterTypes()));
        assertEquals(List.of(
                        "providerType", "runtimeId", "runtimeRevision", "runtimeType",
                        "workerId", "instanceId", "routingEpoch"),
                Arrays.stream(CodexAppServerRuntimeAffinityAdapter.DurableAffinity.class
                                .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(List.of(
                        String.class, String.class, Integer.class, String.class,
                        String.class, String.class, Long.class),
                Arrays.stream(CodexAppServerRuntimeAffinityAdapter.DurableAffinity.class
                                .getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertTrue(Arrays.stream(
                        CodexAppServerRuntimeAffinityAdapter.BoundRuntime.class.getDeclaredFields())
                .allMatch(field -> Modifier.isPrivate(field.getModifiers())));
        assertEquals(List.of("affinity"), Arrays.stream(
                        CodexAppServerRuntimeAffinityAdapter.BoundRuntime.class.getDeclaredMethods())
                .map(method -> method.getName()).sorted().toList());
    }

    @Test
    void newSelectionCapturesTheRegistryEpochAndOnlyDurableIdentity() {
        Set<String> requiredFeatures = new LinkedHashSet<>(List.of("attachments", "sandbox"));
        CodexRuntimeBinding selected = binding(
                "app-main", 3, "worker-1", "instance-a", 12L,
                "http://current.example", "current-token");
        when(runtimeRegistryService.selectForNewTask(
                "worker-1", "codex-terra:ultra", "codex-app-server-worker",
                "task-new", requiredFeatures)).thenReturn(selected);

        CodexAppServerRuntimeAffinityAdapter.DurableAffinity affinity =
                adapter.selectForNewTask(
                        "worker-1", "codex-terra:ultra", "codex-app-server-worker",
                        "task-new", requiredFeatures);

        assertEquals(new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                "codex-app-server-worker", "app-main", 3, "APP_SERVER",
                "worker-1", "instance-a", 12L), affinity);
        verify(runtimeRegistryService).selectForNewTask(
                eq("worker-1"), eq("codex-terra:ultra"),
                eq("codex-app-server-worker"), eq("task-new"),
                same(requiredFeatures));
        verifyNoInteractions(clientFactory);
    }

    @Test
    void newSelectionRejectsSdkBizGenericAndLegacyResultsWithoutClientEffect() {
        for (String provider : List.of(
                "codex-worker", "codex-biz-worker", "APP_SERVER", "unknown", "")) {
            assertThrows(CodexRuntimeUnavailableException.class,
                    () -> adapter.selectForNewTask(
                            "worker-1", "codex-terra:ultra", provider,
                            "task-new", Set.of()));
        }
        assertThrows(CodexRuntimeUnavailableException.class,
                () -> adapter.selectForNewTask(
                        "worker-1", "codex-terra:ultra", null,
                        "task-new", Set.of()));
        verify(runtimeRegistryService, never()).selectForNewTask(
                "worker-1", "codex-terra:ultra", "codex-app-server-worker",
                "task-new", Set.of());

        when(runtimeRegistryService.selectForNewTask(
                "worker-1", "codex-terra:ultra", "codex-app-server-worker",
                "task-new", Set.of()))
                .thenReturn(CodexRuntimeBinding.legacySdk("worker-1"));
        assertThrows(CodexRuntimeUnavailableException.class,
                () -> adapter.selectForNewTask(
                        "worker-1", "codex-terra:ultra", "codex-app-server-worker",
                        "task-new", Set.of()));

        verifyNoInteractions(clientFactory, client);
    }

    @Test
    void resolvePreservesDurableEpochAndClientUsesCurrentEndpointCredentialWithPersistedInstance() {
        CodexAppServerRuntimeAffinityAdapter.DurableAffinity affinity = affinity(7L);
        CodexRuntimeBinding current = binding(
                "app-main", 2, "worker-1", "instance-a", 99L,
                "http://rotated.example", "rotated-token");
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 2, "worker-1", "instance-a")).thenReturn(current);
        when(clientFactory.getOrCreate(
                "runtime:app-main:2", "http://rotated.example",
                "rotated-token", "instance-a")).thenReturn(client);

        CodexAppServerRuntimeAffinityAdapter.BoundRuntime bound =
                adapter.resolveBound(affinity);

        assertSame(affinity, bound.affinity());
        assertEquals(7L, bound.affinity().routingEpoch());
        assertSame(client, adapter.client(bound));
        verify(clientFactory).getOrCreate(
                "runtime:app-main:2", "http://rotated.example",
                "rotated-token", "instance-a");
        verify(runtimeRegistryService, never()).selectForNewTask(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void incompleteOrNonAppDurableAffinityFailsBeforeRegistryOrClient() {
        List<CodexAppServerRuntimeAffinityAdapter.DurableAffinity> invalid = List.of(
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        null, "app-main", 2, "APP_SERVER", "worker-1", "instance-a", 7L),
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        "codex-worker", "app-main", 2, "APP_SERVER", "worker-1", "instance-a", 7L),
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        "codex-app-server-worker", null, 2, "APP_SERVER", "worker-1", "instance-a", 7L),
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        "codex-app-server-worker", "legacy-sdk:worker-1", 2,
                        "APP_SERVER", "worker-1", "instance-a", 7L),
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        "codex-app-server-worker", "app-main", null,
                        "APP_SERVER", "worker-1", "instance-a", 7L),
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        "codex-app-server-worker", "app-main", 0,
                        "APP_SERVER", "worker-1", "instance-a", 7L),
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        "codex-app-server-worker", "app-main", -1,
                        "APP_SERVER", "worker-1", "instance-a", 7L),
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        "codex-app-server-worker", "app-main", 2,
                        "SDK_EXEC", "worker-1", "instance-a", 7L),
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        "codex-app-server-worker", "app-main", 2,
                        "APP_SERVER", null, "instance-a", 7L),
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        "codex-app-server-worker", "app-main", 2,
                        "APP_SERVER", "worker-1", null, 7L),
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        "codex-app-server-worker", "app-main", 2,
                        "APP_SERVER", "worker-1", "instance-a", null),
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        "codex-app-server-worker", "app-main", 2,
                        "APP_SERVER", "worker-1", "instance-a", 0L),
                new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                        "codex-app-server-worker", "app-main", 2,
                        "APP_SERVER", "worker-1", "instance-a", -1L));

        invalid.forEach(affinity -> assertThrows(
                CodexRuntimeUnavailableException.class,
                () -> adapter.resolveBound(affinity)));

        verifyNoInteractions(runtimeRegistryService, clientFactory, client);
    }

    @Test
    void resolvedRuntimeIdentityDriftIsRejectedBeforeClient() {
        CodexAppServerRuntimeAffinityAdapter.DurableAffinity affinity = affinity(7L);
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 2, "worker-1", "instance-a"))
                .thenReturn(binding("app-other", 2, "worker-1", "instance-a", 8L, "http://one", null))
                .thenReturn(binding("app-main", 3, "worker-1", "instance-a", 8L, "http://one", null))
                .thenReturn(binding("app-main", 2, "worker-2", "instance-a", 8L, "http://one", null))
                .thenReturn(binding("app-main", 2, "worker-1", "instance-b", 8L, "http://one", null))
                .thenReturn(CodexRuntimeBinding.builder()
                        .runtimeId("app-main").runtimeRevision(2)
                        .runtimeType(CodexRuntimeType.SDK_EXEC)
                        .workerId("worker-1").instanceId("instance-a")
                        .routingEpoch(8L).build());

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(CodexRuntimeUnavailableException.class,
                    () -> adapter.resolveBound(affinity));
        }

        verify(runtimeRegistryService, times(5)).resolveBoundRuntime(
                "app-main", 2, "worker-1", "instance-a");
        verifyNoInteractions(clientFactory, client);
    }

    @Test
    void replacementOrQuarantineFailurePropagatesWithoutSelectionOrClient() {
        CodexRuntimeUnavailableException quarantine = new CodexRuntimeUnavailableException(
                "CODEX_RUNTIME_INSTANCE_AFFINITY_MISMATCH", "replacement instance");
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 2, "worker-1", "instance-a"))
                .thenThrow(quarantine);

        CodexRuntimeUnavailableException thrown = assertThrows(
                CodexRuntimeUnavailableException.class,
                () -> adapter.resolveBound(affinity(7L)));

        assertSame(quarantine, thrown);
        verifyNoInteractions(clientFactory, client);
    }

    @Test
    void boundCapabilityValidationUsesTheResolvedRevisionWithoutReselection() {
        Set<String> features = new LinkedHashSet<>(List.of("output_schema"));
        CodexRuntimeBinding current = binding(
                "app-main", 2, "worker-1", "instance-a", 41L,
                "http://archived-but-bound.example", null);
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 2, "worker-1", "instance-a")).thenReturn(current);
        CodexAppServerRuntimeAffinityAdapter.BoundRuntime bound =
                adapter.resolveBound(affinity(7L));

        adapter.validateBoundRuntimeCapabilities(
                bound, "codex-terra:ultra", features);

        verify(runtimeRegistryService).validateBoundRuntimeCapabilities(
                same(current), org.mockito.ArgumentMatchers.eq("codex-terra:ultra"),
                same(features));
        verify(runtimeRegistryService, never()).selectForNewTask(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(clientFactory, client);
    }

    private CodexAppServerRuntimeAffinityAdapter.DurableAffinity affinity(long epoch) {
        return new CodexAppServerRuntimeAffinityAdapter.DurableAffinity(
                "codex-app-server-worker", "app-main", 2, "APP_SERVER",
                "worker-1", "instance-a", epoch);
    }

    private CodexRuntimeBinding binding(
            String runtimeId, int revision, String workerId, String instanceId,
            long routingEpoch, String endpointUrl, String authToken) {
        return CodexRuntimeBinding.builder()
                .runtimeId(runtimeId)
                .runtimeRevision(revision)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId(workerId)
                .instanceId(instanceId)
                .routingEpoch(routingEpoch)
                .endpointUrl(endpointUrl)
                .authToken(authToken)
                .build();
    }
}
