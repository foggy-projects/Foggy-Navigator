package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * Exact AppServer runtime selection and durable-affinity boundary.
 *
 * <p>Callers own Task/Session/request and lifecycle orchestration. This adapter
 * resolves only a complete persisted physical affinity and never writes it back.
 */
@Service
@RequiredArgsConstructor
public class CodexAppServerRuntimeAffinityAdapter {

    private static final String APP_SERVER_PROVIDER = "codex-app-server-worker";
    private static final String APP_SERVER_RUNTIME_TYPE = "APP_SERVER";

    private final CodexRuntimeRegistryService runtimeRegistryService;
    private final CodexWorkerClientFactory clientFactory;

    public DurableAffinity selectForNewTask(
            String workerId, String model, String providerType,
            String routingKey, Set<String> requiredFeatures) {
        requireExactProvider(providerType);
        CodexRuntimeBinding selected = runtimeRegistryService.selectForNewTask(
                workerId, model, providerType, routingKey, requiredFeatures);
        DurableAffinity affinity = new DurableAffinity(
                providerType,
                selected != null ? selected.getRuntimeId() : null,
                selected != null ? selected.getRuntimeRevision() : null,
                selected != null && selected.getRuntimeType() != null
                        ? selected.getRuntimeType().name() : null,
                selected != null ? selected.getWorkerId() : null,
                selected != null ? selected.getInstanceId() : null,
                selected != null ? selected.getRoutingEpoch() : null);
        validateComplete(affinity);
        if (!Objects.equals(workerId, affinity.workerId())) {
            throw unavailable("CODEX_RUNTIME_AFFINITY_MISMATCH",
                    "Selected runtime belongs to another worker");
        }
        return affinity;
    }

    public BoundRuntime resolveBound(DurableAffinity affinity) {
        validateComplete(affinity);
        CodexRuntimeBinding current = runtimeRegistryService.resolveBoundRuntime(
                affinity.runtimeId(), affinity.runtimeRevision(),
                affinity.workerId(), affinity.instanceId());
        validateResolvedIdentity(affinity, current);
        return new BoundRuntime(affinity, current);
    }

    public void validateBoundRuntimeCapabilities(
            BoundRuntime boundRuntime, String model, Set<String> requiredFeatures) {
        BoundRuntime bound = requireBound(boundRuntime);
        runtimeRegistryService.validateBoundRuntimeCapabilities(
                bound.registryBinding, model, requiredFeatures);
    }

    public CodexWorkerClient client(BoundRuntime boundRuntime) {
        BoundRuntime bound = requireBound(boundRuntime);
        String endpointUrl = bound.registryBinding.getEndpointUrl();
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw unavailable("CODEX_RUNTIME_AFFINITY_INVALID",
                    "Bound app-server runtime endpoint is missing");
        }
        DurableAffinity affinity = bound.affinity;
        return clientFactory.getOrCreate(
                "runtime:" + affinity.runtimeId() + ":" + affinity.runtimeRevision(),
                endpointUrl, bound.registryBinding.getAuthToken(), affinity.instanceId());
    }

    private void requireExactProvider(String providerType) {
        if (!APP_SERVER_PROVIDER.equals(providerType)) {
            throw unavailable("CODEX_PROVIDER_RUNTIME_MISMATCH",
                    "App-server affinity requires the exact app-server provider");
        }
    }

    private void validateComplete(DurableAffinity affinity) {
        if (affinity == null) {
            throw unavailable("CODEX_RUNTIME_AFFINITY_INVALID",
                    "Durable app-server runtime affinity is missing");
        }
        requireExactProvider(affinity.providerType());
        if (affinity.runtimeId() == null || affinity.runtimeId().isBlank()
                || affinity.runtimeId().startsWith("legacy-sdk:")
                || affinity.runtimeRevision() == null || affinity.runtimeRevision() <= 0
                || !APP_SERVER_RUNTIME_TYPE.equals(affinity.runtimeType())
                || affinity.workerId() == null || affinity.workerId().isBlank()
                || affinity.instanceId() == null || affinity.instanceId().isBlank()
                || affinity.routingEpoch() == null || affinity.routingEpoch() <= 0) {
            throw unavailable("CODEX_RUNTIME_AFFINITY_INVALID",
                    "Durable app-server runtime affinity is incomplete");
        }
    }

    private void validateResolvedIdentity(
            DurableAffinity affinity, CodexRuntimeBinding current) {
        if (current == null || current.getRuntimeType() != CodexRuntimeType.APP_SERVER) {
            throw unavailable("CODEX_PROVIDER_RUNTIME_MISMATCH",
                    "Bound affinity resolved to another runtime type");
        }
        if (!affinity.runtimeId().equals(current.getRuntimeId())
                || !affinity.runtimeRevision().equals(current.getRuntimeRevision())
                || !affinity.workerId().equals(current.getWorkerId())) {
            throw unavailable("CODEX_RUNTIME_AFFINITY_MISMATCH",
                    "Bound runtime physical identity changed");
        }
        if (!affinity.instanceId().equals(current.getInstanceId())) {
            throw unavailable("CODEX_RUNTIME_INSTANCE_AFFINITY_MISMATCH",
                    "Bound runtime instance changed");
        }
        // The registry supplies current endpoint/credential only. Its current
        // routing epoch intentionally does not replace the durable task epoch.
    }

    private BoundRuntime requireBound(BoundRuntime boundRuntime) {
        return Objects.requireNonNull(boundRuntime, "Bound app-server runtime is required");
    }

    private CodexRuntimeUnavailableException unavailable(String code, String message) {
        return new CodexRuntimeUnavailableException(code, message);
    }

    /** Content-free, immutable affinity copied only from an authoritative selection or store. */
    public record DurableAffinity(
            String providerType,
            String runtimeId,
            Integer runtimeRevision,
            String runtimeType,
            String workerId,
            String instanceId,
            Long routingEpoch) {
    }

    /**
     * Controlled proof that registry identity still matches the durable affinity.
     * Current endpoint and credential remain private to the adapter.
     */
    public static final class BoundRuntime {
        private final DurableAffinity affinity;
        private final CodexRuntimeBinding registryBinding;

        private BoundRuntime(
                DurableAffinity affinity, CodexRuntimeBinding registryBinding) {
            this.affinity = affinity;
            this.registryBinding = registryBinding;
        }

        public DurableAffinity affinity() {
            return affinity;
        }
    }
}
