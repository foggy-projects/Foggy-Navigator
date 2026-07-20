package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeRoutingPolicy;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.dto.CodexAppServerEndpointDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexAppServerEndpointSyncDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeAvailabilityDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexAppServerEndpointEntity;
import com.foggy.navigator.codex.worker.model.entity.CodexRuntimeEntity;
import com.foggy.navigator.codex.worker.model.form.CodexRuntimeLifecycleForm;
import com.foggy.navigator.codex.worker.model.form.CodexRuntimeRoutingForm;
import com.foggy.navigator.codex.worker.repository.CodexAppServerEndpointRepository;
import com.foggy.navigator.codex.worker.repository.CodexRuntimeRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodexRuntimeRegistryService {

    private static final Comparator<CodexRuntimeEntity> RUNTIME_ORDER =
            Comparator.comparing(CodexRuntimeEntity::getPriority,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(CodexRuntimeEntity::getRevision,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(CodexRuntimeEntity::getRuntimeId,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    public static final String CAPABILITY_CONTRACT_VERSION = "1";
    public static final String PINNED_SCHEMA_DIGEST =
            "6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f";
    public static final String ULTRA_AVAILABILITY_BLOCK_REASON =
            "CODEX_ULTRA_RUNTIME_UNAVAILABLE";
    public static final String MODEL_ALIAS_CONFLICT_CODE =
            "CODEX_RUNTIME_MODEL_ALIAS_CONFLICT";

    private static final Map<String, String> DEFAULT_MODEL_ALIASES = Map.ofEntries(
            Map.entry("codex-latest", "gpt-5.6-sol"),
            Map.entry("codex-terra", "gpt-5.6-terra"),
            Map.entry("codex-luna", "gpt-5.6-luna"),
            Map.entry("codex-fast", "gpt-5.6-sol:low"),
            Map.entry("codex-deep", "gpt-5.6-sol:high"),
            Map.entry("codex-xhigh", "gpt-5.6-sol:xhigh"),
            Map.entry("codex-max", "gpt-5.6-sol:max"),
            Map.entry("codex-ultra", "gpt-5.6-sol:ultra"));

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final Set<String> REQUIRED_APP_SERVER_FEATURES = Set.of(
            "task_accept",
            "idempotency_key",
            "durable_acceptance",
            "durable_events",
            "abort",
            "committed_reconciliation",
            "instance_affinity_guard");

    private static final Set<String> SAFE_READINESS_REASONS = Set.of(
            "STATE_ENCRYPTION_KEY_MISSING",
            "CODEX_HOME_MISSING",
            "APP_SERVER_CLI_UNAVAILABLE",
            "APP_SERVER_CLI_VERSION_MISMATCH",
            "ALLOWED_CWDS_MISSING",
            "APP_SERVER_WORKER_DRAINING");

    private final CodexRuntimeRepository runtimeRepository;
    private final CodexAppServerEndpointRepository endpointRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final CodexWorkerClientFactory clientFactory;
    private final ObjectMapper objectMapper;
    private final CodexRuntimeCapabilityStateService capabilityStateService;

    @Value("${navigator.codex.runtime.capability-max-age-seconds:120}")
    private long capabilityMaxAgeSeconds = 120;

    /**
     * Optional exact CLI version gate. By default CLI patch versions may coexist;
     * operators can opt into a pin with NAVIGATOR_CODEX_RUNTIME_EXPECTED_CLI_VERSION.
     */
    @Value("${navigator.codex.runtime.expected-cli-version:}")
    private String expectedCliVersion = "";

    /**
     * Probes an endpoint profile and creates a new platform runtime revision only
     * when its execution fingerprint changes. Endpoint profiles themselves stay
     * editable; a runtime stores a credential snapshot for historical task affinity.
     */
    @Transactional
    public CodexAppServerEndpointSyncDTO synchronizeEndpoint(String endpointId) {
        CodexAppServerEndpointEntity endpoint = endpointRepository.findByEndpointIdForUpdate(endpointId)
                .orElseThrow(() -> new IllegalArgumentException("Endpoint not found: " + endpointId));
        Map<String, Object> manifest = null;
        String actualInstanceId = null;
        String failureCode = null;
        try {
            CodexWorkerClient.CapabilityProbe probe = clientFactory.getOrCreate(
                    "endpoint-sync:" + endpointId,
                    endpoint.getEndpointUrl(),
                    credentialEncryptor.decrypt(endpoint.getAuthTokenCiphertext()),
                    null)
                    .probeCapabilities()
                    .block(Duration.ofSeconds(10));
            if (probe == null || probe.manifest() == null) {
                throw new IllegalStateException("empty capability manifest");
            }
            manifest = probe.manifest();
            actualInstanceId = probe.actualInstanceId();
        } catch (Exception e) {
            failureCode = capabilityFailureCode(e);
            log.warn("Codex app-server endpoint sync failed: endpointId={}, code={}, type={}",
                    endpointId, failureCode, exceptionType(e));
        }

        endpoint.setLastSyncedAt(LocalDateTime.now());
        if (manifest == null) {
            endpoint.setLastSyncStatus("UNREACHABLE");
            endpoint.setLastSyncMessage(failureCode != null ? failureCode : "CAPABILITY_REFRESH_FAILED");
            endpointRepository.save(endpoint);
            return CodexAppServerEndpointSyncDTO.builder()
                    .endpoint(toEndpointDTO(endpoint))
                    .runtimeCreated(false)
                    .runtimeRestored(false)
                    .build();
        }

        String fingerprint = capabilityFingerprint(endpoint, manifest, actualInstanceId);
        List<CodexRuntimeEntity> revisions = runtimeRepository
                .findByEndpointIdOrderByRevisionDesc(endpointId);
        CodexRuntimeEntity current = revisions.stream()
                .filter(entity -> "ENDPOINT_SYNC".equals(entity.getRuntimeSource()))
                .findFirst()
                .orElse(null);
        CodexRuntimeEntity matching = revisions.stream()
                .filter(entity -> "ENDPOINT_SYNC".equals(entity.getRuntimeSource()))
                .filter(entity -> fingerprint.equals(entity.getCapabilityFingerprint()))
                .findFirst()
                .orElse(null);
        boolean created = matching == null;
        CodexRuntimeEntity runtime;
        if (created) {
            runtime = createSyncedRuntime(endpoint, manifest, fingerprint);
        } else {
            runtime = matching;
        }
        if (current != null && current != runtime && current.getArchivedAt() == null) {
            current.setEnabled(false);
            current.setRoutingPolicy(CodexRuntimeRoutingPolicy.DRAINING.name());
            current.setRolloutPercentage(0);
            current.setRoutingEpoch(current.getRoutingEpoch() + 1);
            runtimeRepository.save(current);
        }
        boolean restored = runtime.getArchivedAt() != null;
        if (restored) {
            runtime.setEnabled(false);
            runtime.setRoutingPolicy(CodexRuntimeRoutingPolicy.DARK.name());
            runtime.setRolloutPercentage(0);
            runtime.setArchivedAt(null);
            runtime.setRoutingEpoch(runtime.getRoutingEpoch() + 1);
        }

        try {
            applyManifest(runtime, manifest, actualInstanceId, !created);
        } catch (Exception e) {
            applyCapabilityFailure(runtime, capabilityFailureCode(e));
            log.warn("Codex app-server endpoint manifest apply failed: endpointId={}, code={}, type={}",
                    endpointId, capabilityFailureCode(e), exceptionType(e));
        }
        runtime = runtimeRepository.save(runtime);
        endpoint.setLastSyncStatus(runtime.getReadinessStatus());
        endpoint.setLastSyncMessage(runtime.getReadinessMessage());
        endpoint.setLastRuntimeId(runtime.getRuntimeId());
        endpoint.setLastRuntimeRevision(runtime.getRevision());
        endpointRepository.save(endpoint);
        return CodexAppServerEndpointSyncDTO.builder()
                .endpoint(toEndpointDTO(endpoint))
                .runtime(toDTO(runtime))
                .runtimeCreated(created)
                .runtimeRestored(restored)
                .build();
    }

    @Transactional(readOnly = true)
    public List<CodexRuntimeDTO> listByWorker(String workerId) {
        return listByWorker(workerId, false);
    }

    @Transactional(readOnly = true)
    public List<CodexRuntimeDTO> listByWorker(String workerId, boolean includeArchived) {
        return runtimeRepository.findByWorkerIdOrderByPriorityDescRevisionDesc(workerId).stream()
                .filter(entity -> includeArchived || entity.getArchivedAt() == null)
                .sorted(RUNTIME_ORDER)
                .map(this::toDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public CodexRuntimeAvailabilityDTO availability(String workerId) {
        return availability(workerId, null);
    }

    @Transactional(readOnly = true)
    public CodexRuntimeAvailabilityDTO availability(String workerId, String model) {
        List<CodexRuntimeEntity> runtimes = runtimeRepository
                .findByWorkerIdOrderByPriorityDescRevisionDesc(workerId);
        List<CodexRuntimeEntity> registeredCandidates = runtimes.stream()
                .filter(entity -> CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType()))
                .filter(entity -> "ENDPOINT_SYNC".equals(entity.getRuntimeSource()))
                .filter(this::hasLiveEndpointProfile)
                .filter(entity -> entity.getArchivedAt() == null)
                .toList();
        boolean appServerManaged = !registeredCandidates.isEmpty();
        List<CodexRuntimeEntity> enabledCandidates = registeredCandidates.stream()
                .filter(entity -> Boolean.TRUE.equals(entity.getEnabled()))
                .toList();
        String requestedModel = model == null || model.isBlank() ? "codex-ultra" : model;
        ModelResolution resolution;
        try {
            resolution = resolveCandidateModel(requestedModel, registeredCandidates);
        } catch (CodexRuntimeUnavailableException error) {
            if (!MODEL_ALIAS_CONFLICT_CODE.equals(error.getCode())) throw error;
            return CodexRuntimeAvailabilityDTO.builder()
                    .appServerManaged(appServerManaged)
                    .modelSupported(false)
                    .modelAvailable(false)
                    .ultraAvailable(false)
                    .blockReason(MODEL_ALIAS_CONFLICT_CODE)
                    .build();
        }
        boolean modelSupported = registeredCandidates.stream()
                .anyMatch(entity -> isModelSupported(entity, requestedModel));
        boolean modelAvailable = enabledCandidates.stream()
                .anyMatch(entity -> isModelAvailable(entity, requestedModel));
        boolean ultraAvailable = resolution.isUltra() && modelAvailable;
        return CodexRuntimeAvailabilityDTO.builder()
                .appServerManaged(appServerManaged)
                .modelSupported(modelSupported)
                .modelAvailable(modelAvailable)
                .ultraAvailable(ultraAvailable)
                .blockReason(modelAvailable ? null : resolution.isUltra()
                        ? ULTRA_AVAILABILITY_BLOCK_REASON : "CODEX_RUNTIME_UNAVAILABLE")
                .build();
    }

    @Transactional(readOnly = true)
    public String testEndpointConnection(String workerId, String model) {
        List<CodexAppServerEndpointEntity> endpoints =
                endpointRepository.findByWorkerIdOrderByUpdatedAtDesc(workerId);
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException(
                    "CODEX_APP_SERVER_ENDPOINT_MISSING: no Endpoint Profile for Worker " + workerId);
        }
        String lastFailure = "CODEX_APP_SERVER_ENDPOINT_UNAVAILABLE";
        for (CodexAppServerEndpointEntity endpoint : endpoints) {
            try {
                CodexWorkerClient.CapabilityProbe probe = clientFactory.getOrCreate(
                                "endpoint-test:" + endpoint.getEndpointId(),
                                endpoint.getEndpointUrl(),
                                credentialEncryptor.decrypt(endpoint.getAuthTokenCiphertext()),
                                null)
                        .probeCapabilities()
                        .block(Duration.ofSeconds(10));
                if (probe == null || probe.manifest() == null) {
                    lastFailure = "CODEX_APP_SERVER_CAPABILITY_EMPTY";
                    continue;
                }
                if (model != null && !model.isBlank()
                        && (!supportsModel(probe.manifest(), model)
                            || !supportsModelReasoning(probe.manifest(), model)
                            || (resolveModel(model, modelAliases(probe.manifest())).isUltra()
                                && !supportsNativeSubtaskContractV1(probe.manifest())))) {
                    lastFailure = "CODEX_APP_SERVER_MODEL_UNSUPPORTED";
                    continue;
                }
                return "Codex App Server READY: " + maskedEndpoint(endpoint.getEndpointUrl());
            } catch (Exception error) {
                lastFailure = capabilityFailureCode(error);
            }
        }
        throw new IllegalStateException(lastFailure);
    }

    @Transactional
    public CodexRuntimeDTO updateRouting(String runtimeId, int revision, CodexRuntimeRoutingForm form) {
        CodexRuntimeEntity entity = requireRevisionForUpdate(runtimeId, revision);
        requireLiveEndpointProfile(entity);
        if (entity.getArchivedAt() != null) {
            throw new IllegalStateException("CODEX_RUNTIME_ARCHIVED");
        }
        requireRoutingEpoch(entity, form != null ? form.getExpectedRoutingEpoch() : null);
        CodexRuntimeRoutingPolicy current = parseRoutingPolicy(entity.getRoutingPolicy());
        CodexRuntimeRoutingPolicy requested = form.getRoutingPolicy() != null
                ? parseRoutingPolicy(form.getRoutingPolicy())
                : current;
        if (!isAllowedTransition(current, requested)) {
            throw new IllegalStateException("CODEX_RUNTIME_ROUTING_TRANSITION_INVALID: "
                    + current + " -> " + requested);
        }
        validateRollout(form.getRolloutPercentage());

        if (form.getEnabled() != null) entity.setEnabled(form.getEnabled());
        if (form.getRoutingPolicy() != null) {
            entity.setRoutingPolicy(requested.name());
        }
        if (form.getRolloutPercentage() != null) {
            entity.setRolloutPercentage(form.getRolloutPercentage());
        }
        if (form.getPriority() != null) entity.setPriority(form.getPriority());
        entity.setRoutingEpoch(entity.getRoutingEpoch() + 1);
        return toDTO(runtimeRepository.save(entity));
    }

    @Transactional
    public CodexRuntimeDTO archiveRevision(
            String runtimeId, int revision, CodexRuntimeLifecycleForm form) {
        CodexRuntimeEntity entity = requireRevisionForUpdate(runtimeId, revision);
        requireRoutingEpoch(entity, form != null ? form.getExpectedRoutingEpoch() : null);
        if (entity.getArchivedAt() != null) {
            throw new IllegalStateException("CODEX_RUNTIME_ALREADY_ARCHIVED");
        }
        entity.setEnabled(false);
        entity.setRoutingPolicy(CodexRuntimeRoutingPolicy.DARK.name());
        entity.setRolloutPercentage(0);
        entity.setArchivedAt(LocalDateTime.now());
        entity.setRoutingEpoch(entity.getRoutingEpoch() + 1);
        return toDTO(runtimeRepository.save(entity));
    }

    @Transactional
    public CodexRuntimeDTO unarchiveRevision(
            String runtimeId, int revision, CodexRuntimeLifecycleForm form) {
        CodexRuntimeEntity entity = requireRevisionForUpdate(runtimeId, revision);
        requireLiveEndpointProfile(entity);
        requireRoutingEpoch(entity, form != null ? form.getExpectedRoutingEpoch() : null);
        if (entity.getArchivedAt() == null) {
            throw new IllegalStateException("CODEX_RUNTIME_NOT_ARCHIVED");
        }
        entity.setEnabled(false);
        entity.setRoutingPolicy(CodexRuntimeRoutingPolicy.DARK.name());
        entity.setRolloutPercentage(0);
        entity.setArchivedAt(null);
        entity.setRoutingEpoch(entity.getRoutingEpoch() + 1);
        return toDTO(runtimeRepository.save(entity));
    }

    public CodexRuntimeDTO refreshCapabilities(String runtimeId, int revision) {
        CodexRuntimeEntity probeTarget = requireRevision(runtimeId, revision);
        Map<String, Object> manifest = null;
        String actualInstanceId = null;
        String failureCode = null;
        try {
            CodexWorkerClient client = clientFor(probeTarget);
            CodexWorkerClient.CapabilityProbe probe = client.probeCapabilities()
                    .block(Duration.ofSeconds(10));
            if (probe == null || probe.manifest() == null) {
                throw new IllegalStateException("empty capability manifest");
            }
            manifest = probe.manifest();
            actualInstanceId = probe.actualInstanceId();
        } catch (Exception e) {
            failureCode = capabilityFailureCode(e);
            log.warn("Codex runtime capability refresh failed: runtimeId={}, revision={}, code={}, type={}",
                    runtimeId, revision, failureCode, exceptionType(e));
        }

        Map<String, Object> probedManifest = manifest;
        String probedActualInstanceId = actualInstanceId;
        String probedFailureCode = failureCode;
        CodexRuntimeEntity saved = capabilityStateService.updateLocked(runtimeId, revision, entity -> {
            if (probedManifest == null) {
                applyCapabilityFailure(entity, probedFailureCode);
                return;
            }
            try {
                applyManifest(entity, probedManifest, probedActualInstanceId, true);
            } catch (Exception e) {
                String code = capabilityFailureCode(e);
                applyCapabilityFailure(entity, code);
                log.warn("Codex runtime capability apply failed: runtimeId={}, revision={}, code={}, type={}",
                        runtimeId, revision, code, exceptionType(e));
            }
        });
        return toDTO(saved);
    }

    /**
     * Explicitly clears a latched instance quarantine after the owner has taken
     * the revision out of routing and the original instance proves itself again.
     */
    public CodexRuntimeDTO recoverInstanceQuarantine(String runtimeId, int revision) {
        CodexRuntimeEntity probeTarget = requireRevision(runtimeId, revision);
        requireLiveEndpointProfile(probeTarget);
        validateRecoveryEligibility(probeTarget);
        if (!hasReadinessCode(probeTarget, "CAPABILITY_INSTANCE_ID_MISMATCH")) {
            throw new IllegalStateException("CODEX_RUNTIME_INSTANCE_QUARANTINE_NOT_FOUND");
        }

        Map<String, Object> manifest = null;
        String actualInstanceId = null;
        String failureCode = null;
        try {
            CodexWorkerClient.CapabilityProbe probe = clientFor(probeTarget)
                    .probeCapabilities()
                    .block(Duration.ofSeconds(10));
            if (probe == null || probe.manifest() == null) {
                throw new IllegalStateException("empty capability manifest");
            }
            manifest = probe.manifest();
            actualInstanceId = probe.actualInstanceId();
        } catch (Exception e) {
            failureCode = capabilityFailureCode(e);
            log.warn("Codex runtime instance recovery probe failed: runtimeId={}, revision={}, code={}, type={}",
                    runtimeId, revision, failureCode, exceptionType(e));
        }

        Map<String, Object> probedManifest = manifest;
        String probedActualInstanceId = actualInstanceId;
        String probedFailureCode = failureCode;
        CodexRuntimeEntity saved = capabilityStateService.updateLocked(runtimeId, revision, entity -> {
            validateRecoveryEligibility(entity);
            if (!hasReadinessCode(entity, "CAPABILITY_INSTANCE_ID_MISMATCH")) {
                throw new IllegalStateException("CODEX_RUNTIME_INSTANCE_QUARANTINE_NOT_FOUND");
            }
            if (probedManifest == null) {
                applyCapabilityFailure(entity, probedFailureCode);
                return;
            }
            try {
                applyManifest(entity, probedManifest, probedActualInstanceId, false);
            } catch (Exception e) {
                applyCapabilityFailure(entity, capabilityFailureCode(e));
            }
            if (!"READY".equals(entity.getReadinessStatus())) {
                retainInstanceQuarantine(entity);
            }
        });
        return toDTO(saved);
    }

    @Scheduled(
            fixedDelayString = "${navigator.codex.runtime.refresh-delay-ms:60000}",
            initialDelayString = "${navigator.codex.runtime.refresh-initial-delay-ms:30000}")
    public void refreshEnabledCapabilities() {
        List<CodexRuntimeEntity> managed = runtimeRepository.findByArchivedAtIsNullOrderByUpdatedAtAsc();
        for (CodexRuntimeEntity runtime : managed) {
            if (!CodexRuntimeType.APP_SERVER.name().equals(runtime.getRuntimeType())
                    || !"ENDPOINT_SYNC".equals(runtime.getRuntimeSource())
                    || !hasLiveEndpointProfile(runtime)) continue;
            try {
                refreshCapabilities(runtime.getRuntimeId(), runtime.getRevision());
            } catch (Exception e) {
                // One broken endpoint must not prevent other runtime revisions from refreshing.
                log.warn("Scheduled Codex runtime refresh failed: runtimeId={}, revision={}, code={}, type={}",
                        runtime.getRuntimeId(), runtime.getRevision(), capabilityFailureCode(e), exceptionType(e));
            }
        }
    }

    @Transactional(readOnly = true)
    public CodexRuntimeBinding selectForNewTask(String workerId, String model, String providerType,
                                                 String routingKey) {
        return selectForNewTask(workerId, model, providerType, routingKey, Set.of());
    }

    @Transactional(readOnly = true)
    public CodexRuntimeBinding selectForNewTask(String workerId, String model, String providerType,
                                                 String routingKey, Set<String> requiredFeatures) {
        if (!CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE.equals(providerType)) {
            throw new CodexRuntimeUnavailableException("CODEX_PROVIDER_RUNTIME_MISMATCH",
                    "App-server runtime registry cannot route provider " + providerType);
        }
        List<CodexRuntimeEntity> registeredCandidates = runtimeRepository
                .findByWorkerIdOrderByPriorityDescRevisionDesc(workerId).stream()
                .filter(entity -> CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType()))
                .filter(entity -> "ENDPOINT_SYNC".equals(entity.getRuntimeSource()))
                .filter(this::hasLiveEndpointProfile)
                .filter(entity -> entity.getArchivedAt() == null)
                .sorted(RUNTIME_ORDER)
                .toList();
        List<CodexRuntimeEntity> candidates = runtimeRepository
                .findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                        workerId, CodexRuntimeType.APP_SERVER.name()).stream()
                .filter(entity -> "ENDPOINT_SYNC".equals(entity.getRuntimeSource()))
                .filter(this::hasLiveEndpointProfile)
                .filter(entity -> entity.getArchivedAt() == null)
                .sorted(RUNTIME_ORDER)
                .toList();
        ModelResolution registeredResolution = resolveCandidateModel(model, registeredCandidates);
        boolean ultra = registeredResolution.isUltra();

        for (CodexRuntimeEntity candidate : candidates) {
            CodexRuntimeRoutingPolicy policy = parseRoutingPolicy(candidate.getRoutingPolicy());
            if (!targetsTask(policy, ultra, candidate.getRolloutPercentage(), routingKey,
                    candidate.getRuntimeId())) {
                continue;
            }
            if (isUsable(candidate, model, providerType, requiredFeatures)) {
                return toBinding(candidate);
            }
        }

        String code = ultra ? "CODEX_ULTRA_RUNTIME_UNAVAILABLE" : "CODEX_RUNTIME_UNAVAILABLE";
        throw new CodexRuntimeUnavailableException(code,
                "No compatible READY app-server runtime is available for the selected rollout cohort");
    }

    @Transactional(readOnly = true)
    public CodexRuntimeBinding resolveBoundRuntime(String runtimeId, Integer revision, String workerId) {
        return resolveBoundRuntime(runtimeId, revision, workerId, null);
    }

    @Transactional(readOnly = true)
    public CodexRuntimeBinding resolveBoundRuntime(String runtimeId, Integer revision, String workerId,
                                                    String expectedInstanceId) {
        if (runtimeId == null || runtimeId.isBlank()) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_INVALID",
                    "App-server runtime affinity is missing");
        }
        if (runtimeId.startsWith("legacy-sdk:")) {
            throw new CodexRuntimeUnavailableException("CODEX_PROVIDER_RUNTIME_MISMATCH",
                    "SDK affinity cannot be resolved by the app-server runtime registry");
        }
        if (revision == null) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_INVALID",
                    "Runtime revision is missing for " + runtimeId);
        }
        return runtimeRepository.findByRuntimeIdAndRevision(runtimeId, revision)
                .map(entity -> {
                    if (!"ENDPOINT_SYNC".equals(entity.getRuntimeSource())) {
                        throw new CodexRuntimeUnavailableException(
                                "CODEX_RUNTIME_SOURCE_UNSUPPORTED",
                                "Bound runtime was not created from an Endpoint Profile");
                    }
                    if (workerId != null && !workerId.equals(entity.getWorkerId())) {
                        throw new CodexRuntimeUnavailableException(
                                "CODEX_RUNTIME_AFFINITY_MISMATCH",
                                "Bound runtime belongs to another worker");
                    }
                    if (expectedInstanceId == null || expectedInstanceId.isBlank()
                            || entity.getInstanceId() == null || entity.getInstanceId().isBlank()) {
                        throw new CodexRuntimeUnavailableException(
                                "CODEX_RUNTIME_INSTANCE_AFFINITY_MISSING",
                                "Bound app-server runtime instance affinity is missing");
                    }
                    if (!expectedInstanceId.equals(entity.getInstanceId())) {
                        throw new CodexRuntimeUnavailableException(
                                "CODEX_RUNTIME_INSTANCE_AFFINITY_MISMATCH",
                                "Bound runtime instance changed from " + expectedInstanceId);
                    }
                    if (hasReadinessCode(entity, "CAPABILITY_INSTANCE_ID_MISMATCH")) {
                        throw new CodexRuntimeUnavailableException(
                                "CODEX_RUNTIME_INSTANCE_AFFINITY_MISMATCH",
                                "Runtime endpoint now reports another stateful instance");
                    }
                    return toBinding(entity);
                })
                .orElseThrow(() -> new CodexRuntimeUnavailableException(
                        "CODEX_RUNTIME_AFFINITY_MISSING",
                        "Bound runtime revision no longer exists: " + runtimeId + "@" + revision));
    }

    @Transactional(readOnly = true)
    public void validateBoundRuntimeCapabilities(CodexRuntimeBinding binding, String model,
                                                  Set<String> requiredFeatures) {
        if (binding == null || binding.getRuntimeType() != CodexRuntimeType.APP_SERVER) {
            throw new CodexRuntimeUnavailableException("CODEX_PROVIDER_RUNTIME_MISMATCH",
                    "Bound capability validation requires an app-server runtime");
        }
        CodexRuntimeEntity entity = runtimeRepository
                .findByRuntimeIdAndRevision(binding.getRuntimeId(), binding.getRuntimeRevision())
                .orElseThrow(() -> new CodexRuntimeUnavailableException(
                        "CODEX_RUNTIME_AFFINITY_MISSING",
                        "Bound runtime revision no longer exists"));
        Map<String, Object> manifest = parseManifest(entity.getCapabilityManifestJson());
        boolean ultra = resolveModel(model, modelAliases(manifest)).isUltra();
        boolean compatible = supportsCoreAppServerContract(manifest)
                && supportsModelReasoning(manifest, model)
                && supportsModel(manifest, model)
                && (!ultra || supportsNativeSubtaskContractV1(manifest))
                && supportsFeatures(manifest, requiredFeatures);
        if (!compatible) {
            throw new CodexRuntimeUnavailableException(
                    "CODEX_BOUND_RUNTIME_CAPABILITY_MISMATCH",
                    "The bound runtime revision cannot execute the requested model or features");
        }
    }

    @Transactional(readOnly = true)
    public String ownerWorkerId(String runtimeId, int revision) {
        return requireRevision(runtimeId, revision).getWorkerId();
    }

    private void applyManifest(CodexRuntimeEntity entity, Map<String, Object> manifest,
                               String actualInstanceId, boolean preserveInstanceQuarantine) throws Exception {
        boolean instanceIdentityQuarantined = preserveInstanceQuarantine && hasReadinessCode(
                entity, "CAPABILITY_INSTANCE_ID_MISMATCH");
        String contractVersion = stringValue(manifest, "contract_version", "contractVersion");
        String runtimeId = stringValue(manifest, "runtime_id", "runtimeId");
        String runtimeRevision = stringValue(manifest, "runtime_revision", "runtimeRevision", "revision");
        String runtimeType = normalizeRuntimeType(stringValue(manifest, "runtime_type", "runtimeType"));
        String cliVersion = stringValue(manifest, "cli_version", "cliVersion");
        String schemaDigest = stringValue(manifest, "schema_digest", "schemaDigest");
        String instanceId = blankToNull(stringValue(manifest, "instance_id", "instanceId"));
        String responseInstanceId = blankToNull(actualInstanceId);

        entity.setContractVersion(contractVersion);
        entity.setCliVersion(cliVersion);
        entity.setSchemaDigest(schemaDigest);
        String configuredCliVersion = configuredExpectedCliVersion();
        entity.setExpectedCliVersion(configuredCliVersion);
        String registeredInstanceId = entity.getInstanceId();
        boolean mayBindInitialInstance = (registeredInstanceId == null || registeredInstanceId.isBlank())
                && isValidIdentifier(instanceId, 128)
                && instanceId.equals(responseInstanceId)
                && !Boolean.TRUE.equals(entity.getEnabled())
                && CodexRuntimeRoutingPolicy.DARK.name().equals(entity.getRoutingPolicy())
                && !instanceIdentityQuarantined;
        entity.setCapabilityManifestJson(objectMapper.writeValueAsString(manifest));
        entity.setLastCapabilityAt(LocalDateTime.now());

        List<String> incompatibilities = new ArrayList<>();
        if (!CAPABILITY_CONTRACT_VERSION.equals(contractVersion)) {
            incompatibilities.add("CAPABILITY_CONTRACT_VERSION_MISMATCH");
        }
        if (!Objects.equals(expectedReportedRuntimeId(entity), runtimeId)) {
            incompatibilities.add("CAPABILITY_RUNTIME_ID_MISMATCH");
        }
        if (!Objects.equals(expectedReportedRuntimeRevision(entity), runtimeRevision)) {
            incompatibilities.add("CAPABILITY_RUNTIME_REVISION_MISMATCH");
        }
        if (instanceIdentityQuarantined
                || !isValidIdentifier(instanceId, 128)
                || (!mayBindInitialInstance && (registeredInstanceId == null
                    || registeredInstanceId.isBlank() || !registeredInstanceId.equals(instanceId)))) {
            incompatibilities.add("CAPABILITY_INSTANCE_ID_MISMATCH");
        }
        if (!isValidIdentifier(responseInstanceId, 128)) {
            incompatibilities.add("CAPABILITY_INSTANCE_PROOF_MISSING");
        } else if (!responseInstanceId.equals(instanceId)) {
            incompatibilities.add("CAPABILITY_INSTANCE_ID_MISMATCH");
        }
        if (!entity.getRuntimeType().equals(runtimeType)) {
            incompatibilities.add("CAPABILITY_RUNTIME_TYPE_MISMATCH");
        }
        if (!configuredCliVersion.isBlank() && !configuredCliVersion.equals(cliVersion)) {
            incompatibilities.add("CAPABILITY_CLI_VERSION_MISMATCH");
        }
        if (!entity.getExpectedSchemaDigest().equals(schemaDigest)) {
            incompatibilities.add("CAPABILITY_SCHEMA_DIGEST_MISMATCH");
        }
        Map<String, Object> readiness = readiness(manifest);
        if (!Boolean.TRUE.equals(readiness.get("ready"))) {
            incompatibilities.add("CAPABILITY_RUNTIME_NOT_READY");
            stringList(readiness.get("reasons")).stream()
                    .filter(SAFE_READINESS_REASONS::contains)
                    .forEach(incompatibilities::add);
        }
        Map<String, Object> features = features(manifest);
        for (String required : REQUIRED_APP_SERVER_FEATURES) {
            if (!Boolean.TRUE.equals(features.get(required))) {
                incompatibilities.add("CAPABILITY_FEATURE_"
                        + required.toUpperCase(Locale.ROOT) + "_REQUIRED");
            }
        }

        if (incompatibilities.isEmpty()) {
            if (mayBindInitialInstance) {
                entity.setInstanceId(instanceId);
            }
            entity.setReadinessStatus("READY");
            entity.setReadinessMessage(null);
        } else {
            entity.setReadinessStatus("INCOMPATIBLE");
            entity.setReadinessMessage(String.join("; ", incompatibilities.stream().distinct().toList()));
        }
    }

    private boolean isUsable(CodexRuntimeEntity entity, String model, String providerType,
                             Set<String> requiredFeatures) {
        if (!"READY".equals(entity.getReadinessStatus()) || entity.getLastCapabilityAt() == null) {
            return false;
        }
        if (entity.getInstanceId() == null || entity.getInstanceId().isBlank()) {
            return false;
        }
        if (!isCapabilityFresh(entity)) {
            return false;
        }
        Map<String, Object> manifest = parseManifest(entity.getCapabilityManifestJson());
        if (!supportsCoreAppServerContract(manifest)) {
            return false;
        }
        if (!supportsModelReasoning(manifest, model)) {
            return false;
        }
        if (!supportsModel(manifest, model)) {
            return false;
        }
        if (resolveModel(model, modelAliases(manifest)).isUltra()
                && !supportsNativeSubtaskContractV1(manifest)) {
            return false;
        }
        if (CodexTaskService.CODEX_BIZ_PROVIDER_TYPE.equals(providerType) && !supportsBiz(manifest)) {
            return false;
        }
        return supportsFeatures(manifest, requiredFeatures);
    }

    private boolean targetsTask(CodexRuntimeRoutingPolicy policy, boolean ultra, int percentage,
                                String routingKey, String runtimeId) {
        return switch (policy) {
            case DARK, DRAINING -> false;
            case ULTRA_CANARY -> ultra && inCohort(percentage, routingKey, runtimeId);
            case ULTRA_DEFAULT -> ultra;
            case ALL_CANARY -> ultra || inCohort(percentage, routingKey, runtimeId);
            case ALL_DEFAULT -> true;
        };
    }

    private boolean inCohort(int percentage, String routingKey, String runtimeId) {
        if (percentage <= 0) return false;
        if (percentage >= 100) return true;
        int bucket = Math.floorMod(Objects.hash(routingKey, runtimeId), 100);
        return bucket < percentage;
    }

    private boolean supportsReasoning(Map<String, Object> manifest, String effort) {
        if (effort == null) return true;
        return stringList(capabilityValue(manifest, "reasoning_efforts", "reasoningEfforts")).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(effort::equals);
    }

    private boolean supportsModelReasoning(Map<String, Object> manifest, String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) return true;
        Map<String, String> aliases = modelAliases(manifest);
        String resolved = resolveAlias(requestedModel, aliases);
        String effort = resolvedReasoningEffort(resolved);
        if (effort == null) return true;

        Map<String, List<String>> matrix = modelReasoningMatrix(manifest, aliases);
        if (!matrix.isEmpty()) {
            List<String> supported = matrix.get(resolvedBaseModel(resolved));
            if (supported == null) supported = matrix.get("*");
            return supported != null && supported.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch(effort::equals);
        }

        // N-1 manifests expose only a global effort list. Preserve basic routing,
        // but never infer max/ultra support for another model family.
        if (("max".equals(effort) || "ultra".equals(effort))
                && !"gpt-5.6-sol".equals(resolvedBaseModel(resolved))) {
            return false;
        }
        return supportsReasoning(manifest, effort);
    }

    private Map<String, List<String>> modelReasoningMatrix(
            Map<String, Object> manifest, Map<String, String> aliases) {
        Object value = firstValue(manifest, "model_reasoning_matrix", "modelReasoningMatrix");
        if (value == null && manifest.get("model_capabilities") instanceof Map<?, ?> capabilities) {
            Map<String, Object> typed = new LinkedHashMap<>();
            capabilities.forEach((key, nested) -> typed.put(String.valueOf(key), nested));
            value = firstValue(typed, "model_reasoning_matrix", "modelReasoningMatrix",
                    "reasoning_matrix", "reasoningMatrix");
        }
        if (!(value instanceof Map<?, ?> source)) return Map.of();

        Map<String, List<String>> matrix = new LinkedHashMap<>();
        source.forEach((model, efforts) -> {
            if (model == null) return;
            String resolvedModel = resolveAlias(model.toString(), aliases);
            List<String> normalized = stringList(efforts).stream()
                    .map(effort -> effort.trim().toLowerCase(Locale.ROOT))
                    .filter(effort -> !effort.isBlank())
                    .distinct()
                    .toList();
            matrix.put(resolvedBaseModel(resolvedModel), normalized);
        });
        return matrix;
    }

    private boolean supportsModel(Map<String, Object> manifest, String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) return true;
        Map<String, String> aliases = modelAliases(manifest);
        String requested = baseModel(resolveAlias(requestedModel, aliases));
        List<String> supported = new ArrayList<>(stringList(capabilityValue(manifest, "models")));
        return supported.stream()
                .map(model -> baseModel(resolveAlias(model, aliases)))
                .anyMatch(model -> "*".equals(model) || requested.equals(model));
    }

    private boolean supportsBiz(Map<String, Object> manifest) {
        Object features = capabilityValue(manifest, "features");
        if (features instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get("biz"))
                    || Boolean.TRUE.equals(map.get("business_runtime_context"))
                    || Boolean.TRUE.equals(map.get("business_mcp"));
        }
        return stringList(features).stream().anyMatch(value ->
                "biz".equalsIgnoreCase(value) || "business_runtime_context".equalsIgnoreCase(value));
    }

    private boolean supportsFeatures(Map<String, Object> manifest, Set<String> requiredFeatures) {
        if (requiredFeatures == null || requiredFeatures.isEmpty()) return true;
        Map<String, Object> features = features(manifest);
        for (String feature : requiredFeatures) {
            if (feature.startsWith("approval:")) {
                String approval = feature.substring("approval:".length());
                if (!stringList(features.get("approval_modes")).contains(approval)) return false;
            } else if (!Boolean.TRUE.equals(features.get(feature))) {
                return false;
            }
        }
        return true;
    }

    private boolean supportsCoreAppServerContract(Map<String, Object> manifest) {
        Map<String, Object> features = features(manifest);
        return REQUIRED_APP_SERVER_FEATURES.stream()
                .allMatch(feature -> Boolean.TRUE.equals(features.get(feature)));
    }

    private boolean supportsNativeSubtaskContractV1(Map<String, Object> manifest) {
        return stringList(features(manifest).get("native_subtask_contract_versions")).stream()
                .anyMatch("1"::equals);
    }

    private Map<String, Object> features(Map<String, Object> manifest) {
        return mapValue(capabilityValue(manifest, "features"));
    }

    private Map<String, Object> readiness(Map<String, Object> manifest) {
        Object value = capabilityValue(manifest, "readiness");
        if (value == null && manifest.get("runtime") instanceof Map<?, ?> runtime) {
            value = runtime.get("readiness");
        }
        return mapValue(value);
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, nestedValue) -> result.put(String.valueOf(key), nestedValue));
        return result;
    }

    private Object capabilityValue(Map<String, Object> manifest, String... keys) {
        Object direct = firstValue(manifest, keys);
        if (direct != null && !(direct instanceof Map<?, ?> && containsKey(keys, "models"))) return direct;
        Object capabilities = manifest.get("capabilities");
        if (capabilities instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, value) -> typed.put(String.valueOf(key), value));
            Object value = firstValue(typed, keys);
            if (value != null) return value;
        }
        Object models = manifest.get("models");
        if (models instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, value) -> typed.put(String.valueOf(key), value));
            Object value = firstValue(typed, keys);
            if (value != null) return value;
            if (containsKey(keys, "models")) {
                value = firstValue(typed, "supported", "available", "ids");
                if (value != null) return value;
            }
        }
        return direct;
    }

    private Object firstValue(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) return source.get(key);
        }
        return null;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().map(item -> {
            if (item instanceof Map<?, ?> map) {
                Object id = map.containsKey("id") ? map.get("id") : map.get("model");
                return id != null ? id.toString() : null;
            }
            return item != null ? item.toString() : null;
        }).filter(Objects::nonNull).toList();
    }

    private Map<String, String> modelAliases(Map<String, Object> manifest) {
        Map<String, String> aliases = new LinkedHashMap<>(DEFAULT_MODEL_ALIASES);
        Object value = capabilityValue(manifest, "model_aliases", "modelAliases", "aliases");
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, target) -> {
                if (key != null && target != null && !key.toString().isBlank() && !target.toString().isBlank()) {
                    aliases.put(key.toString().trim().toLowerCase(Locale.ROOT),
                            target.toString().trim().toLowerCase(Locale.ROOT));
                }
            });
        }
        return aliases;
    }

    private String resolveAlias(String model, Map<String, String> aliases) {
        if (model == null) return null;
        String normalized = model.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        int separator = normalized.lastIndexOf(':');
        String name = separator > 0 ? normalized.substring(0, separator) : normalized;
        String requestedEffort = separator > 0 ? normalized.substring(separator + 1) : null;
        String target = aliases.get(name);
        if (target == null) return normalized;
        if (target.contains(":") || requestedEffort == null || requestedEffort.isBlank()) return target;
        return target + ":" + requestedEffort;
    }

    private ModelResolution resolveCandidateModel(
            String model, List<CodexRuntimeEntity> candidates) {
        if (candidates.isEmpty()) {
            return resolveModel(model, DEFAULT_MODEL_ALIASES);
        }
        ModelResolution consensus = null;
        for (CodexRuntimeEntity candidate : candidates) {
            Map<String, Object> manifest = parseManifest(candidate.getCapabilityManifestJson());
            ModelResolution current = resolveModel(model, modelAliases(manifest));
            if (consensus != null && !consensus.equals(current)) {
                throw new CodexRuntimeUnavailableException(MODEL_ALIAS_CONFLICT_CODE,
                        "App-server runtime manifests resolve the requested model inconsistently");
            }
            consensus = current;
        }
        return consensus;
    }

    private ModelResolution resolveModel(String model, Map<String, String> aliases) {
        String resolved = resolveAlias(model, aliases);
        return new ModelResolution(
                resolvedBaseModel(resolved), resolvedReasoningEffort(resolved));
    }

    private String resolvedReasoningEffort(String resolved) {
        if (resolved == null) return null;
        if (resolved.endsWith(":ultra")) return "ultra";
        if (resolved.endsWith(":max")) return "max";
        for (String effort : List.of("xhigh", "high", "medium", "low", "minimal")) {
            if (resolved.endsWith(":" + effort)) return effort;
        }
        int separator = resolved.lastIndexOf(':');
        return separator > 0 ? resolved.substring(separator + 1) : null;
    }

    private String resolvedBaseModel(String resolved) {
        if (resolved == null) return null;
        int separator = resolved.lastIndexOf(':');
        return separator > 0 ? resolved.substring(0, separator) : resolved;
    }

    private String baseModel(String model) {
        if (model == null) return null;
        String normalized = resolveAlias(model, DEFAULT_MODEL_ALIASES);
        int separator = normalized.lastIndexOf(':');
        return separator > 0 ? normalized.substring(0, separator) : normalized;
    }

    private Map<String, Object> parseManifest(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private CodexWorkerClient clientFor(CodexRuntimeEntity entity) {
        return clientFactory.getOrCreate(clientKey(entity), entity.getEndpointUrl(),
                effectiveAuthToken(entity), entity.getInstanceId());
    }

    private CodexRuntimeEntity createSyncedRuntime(CodexAppServerEndpointEntity endpoint,
                                                   Map<String, Object> manifest,
                                                   String fingerprint) {
        String runtimeId = managedRuntimeId(endpoint.getEndpointId());
        Integer storedMaxRevision = runtimeRepository.findMaxRevision(runtimeId);
        int maxRevision = storedMaxRevision != null ? storedMaxRevision : 0;
        if (maxRevision == Integer.MAX_VALUE) {
            throw new IllegalStateException("Runtime revision sequence is exhausted");
        }
        CodexRuntimeEntity entity = new CodexRuntimeEntity();
        entity.setRuntimeId(runtimeId);
        entity.setRevision(maxRevision + 1);
        entity.setWorkerId(endpoint.getWorkerId());
        entity.setRuntimeType(CodexRuntimeType.APP_SERVER.name());
        entity.setEndpointUrl(endpoint.getEndpointUrl());
        entity.setAuthTokenCiphertext(endpoint.getAuthTokenCiphertext());
        entity.setEndpointId(endpoint.getEndpointId());
        entity.setRuntimeSource("ENDPOINT_SYNC");
        entity.setReportedRuntimeId(blankToNull(stringValue(manifest, "runtime_id", "runtimeId")));
        entity.setReportedRuntimeRevision(integerValue(stringValue(
                manifest, "runtime_revision", "runtimeRevision", "revision")));
        entity.setCapabilityFingerprint(fingerprint);
        entity.setEnabled(false);
        entity.setRoutingPolicy(CodexRuntimeRoutingPolicy.DARK.name());
        entity.setRolloutPercentage(0);
        entity.setPriority(0);
        entity.setRoutingEpoch(1L);
        entity.setReadinessStatus("PENDING");
        entity.setExpectedCliVersion(configuredExpectedCliVersion());
        entity.setExpectedSchemaDigest(PINNED_SCHEMA_DIGEST);
        return entity;
    }

    private String configuredExpectedCliVersion() {
        return expectedCliVersion == null ? "" : expectedCliVersion.trim();
    }

    private boolean hasLiveEndpointProfile(CodexRuntimeEntity entity) {
        return entity != null
                && "ENDPOINT_SYNC".equals(entity.getRuntimeSource())
                && entity.getEndpointId() != null
                && !entity.getEndpointId().isBlank()
                && endpointRepository.findByEndpointId(entity.getEndpointId()).isPresent();
    }

    private void requireLiveEndpointProfile(CodexRuntimeEntity entity) {
        if (!hasLiveEndpointProfile(entity)) {
            throw new IllegalStateException(
                    "CODEX_APP_SERVER_ENDPOINT_MISSING: runtime cannot accept new routing changes");
        }
    }

    private String managedRuntimeId(String endpointId) {
        return "appserver-" + endpointId.replace("endpoint-", "");
    }

    private String expectedReportedRuntimeId(CodexRuntimeEntity entity) {
        return "ENDPOINT_SYNC".equals(entity.getRuntimeSource())
                ? entity.getReportedRuntimeId() : entity.getRuntimeId();
    }

    private String expectedReportedRuntimeRevision(CodexRuntimeEntity entity) {
        if (!"ENDPOINT_SYNC".equals(entity.getRuntimeSource())) {
            return entity.getRevision().toString();
        }
        return entity.getReportedRuntimeRevision() != null
                ? entity.getReportedRuntimeRevision().toString() : null;
    }

    private String clientKey(CodexRuntimeEntity entity) {
        return "runtime:" + entity.getRuntimeId() + ":" + entity.getRevision();
    }

    private CodexRuntimeBinding toBinding(CodexRuntimeEntity entity) {
        return CodexRuntimeBinding.builder()
                .runtimeId(entity.getRuntimeId())
                .runtimeRevision(entity.getRevision())
                .runtimeType(parseRuntimeType(entity.getRuntimeType()))
                .workerId(entity.getWorkerId())
                .endpointUrl(entity.getEndpointUrl())
                .authToken(effectiveAuthToken(entity))
                .instanceId(entity.getInstanceId())
                .routingEpoch(entity.getRoutingEpoch())
                .build();
    }

    /**
     * Runtime identity remains immutable, while its HTTP credential may rotate on the
     * same endpoint profile. Never borrow a credential after worker or URL drift, and
     * honor an explicit token clear instead of reviving the historical snapshot.
     */
    private String effectiveAuthToken(CodexRuntimeEntity entity) {
        String snapshot = credentialEncryptor.decrypt(entity.getAuthTokenCiphertext());
        if (!"ENDPOINT_SYNC".equals(entity.getRuntimeSource())
                || entity.getEndpointId() == null || entity.getEndpointId().isBlank()) {
            return snapshot;
        }
        return endpointRepository.findByEndpointId(entity.getEndpointId())
                .filter(endpoint -> Objects.equals(entity.getWorkerId(), endpoint.getWorkerId()))
                .filter(endpoint -> sameEndpointUrl(entity.getEndpointUrl(), endpoint.getEndpointUrl()))
                .map(endpoint -> credentialEncryptor.decrypt(endpoint.getAuthTokenCiphertext()))
                .orElse(snapshot);
    }

    private boolean sameEndpointUrl(String runtimeUrl, String endpointUrl) {
        return Objects.equals(normalizeStoredEndpointUrl(runtimeUrl),
                normalizeStoredEndpointUrl(endpointUrl));
    }

    private String normalizeStoredEndpointUrl(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private CodexRuntimeDTO toDTO(CodexRuntimeEntity entity) {
        return CodexRuntimeDTO.builder()
                .runtimeId(entity.getRuntimeId())
                .revision(entity.getRevision())
                .workerId(entity.getWorkerId())
                .runtimeType(entity.getRuntimeType())
                .runtimeSource(entity.getRuntimeSource())
                .endpointId(entity.getEndpointId())
                .reportedRuntimeId(entity.getReportedRuntimeId())
                .reportedRuntimeRevision(entity.getReportedRuntimeRevision())
                .endpointConfigured(entity.getEndpointUrl() != null && !entity.getEndpointUrl().isBlank())
                .endpointDisplay(maskedEndpoint(entity.getEndpointUrl()))
                .tokenConfigured(hasConfiguredCredential(effectiveAuthToken(entity)))
                .instanceId(entity.getInstanceId())
                .enabled(entity.getEnabled())
                .routingPolicy(entity.getRoutingPolicy())
                .rolloutPercentage(entity.getRolloutPercentage())
                .priority(entity.getPriority())
                .routingEpoch(entity.getRoutingEpoch())
                .readinessStatus(entity.getReadinessStatus())
                .readinessMessage(entity.getReadinessMessage())
                .contractVersion(entity.getContractVersion())
                .cliVersion(entity.getCliVersion())
                .schemaDigest(entity.getSchemaDigest())
                .expectedCliVersion(entity.getExpectedCliVersion())
                .expectedSchemaDigest(entity.getExpectedSchemaDigest())
                .lastCapabilityAt(entity.getLastCapabilityAt())
                .capabilityFresh(isCapabilityFresh(entity))
                .supportsUltra(supportsUltra(entity))
                .archived(entity.getArchivedAt() != null)
                .archivedAt(entity.getArchivedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private boolean hasConfiguredCredential(String value) {
        return value != null && !value.isBlank();
    }

    private CodexAppServerEndpointDTO toEndpointDTO(CodexAppServerEndpointEntity endpoint) {
        return CodexAppServerEndpointDTO.builder()
                .endpointId(endpoint.getEndpointId())
                .workerId(endpoint.getWorkerId())
                .endpointUrl(endpoint.getEndpointUrl())
                .endpointDisplay(maskedEndpoint(endpoint.getEndpointUrl()))
                .tokenConfigured(endpoint.getAuthTokenCiphertext() != null
                        && !credentialEncryptor.decrypt(endpoint.getAuthTokenCiphertext()).isBlank())
                .configurationVersion(endpoint.getConfigurationVersion())
                .lastSyncStatus(endpoint.getLastSyncStatus())
                .lastSyncMessage(endpoint.getLastSyncMessage())
                .lastSyncedAt(endpoint.getLastSyncedAt())
                .lastRuntimeId(endpoint.getLastRuntimeId())
                .lastRuntimeRevision(endpoint.getLastRuntimeRevision())
                .createdAt(endpoint.getCreatedAt())
                .updatedAt(endpoint.getUpdatedAt())
                .build();
    }

    private boolean isCapabilityFresh(CodexRuntimeEntity entity) {
        return entity.getLastCapabilityAt() != null
                && !entity.getLastCapabilityAt().isBefore(
                        LocalDateTime.now().minusSeconds(capabilityMaxAgeSeconds));
    }

    private String capabilityFingerprint(CodexAppServerEndpointEntity endpoint,
                                         Map<String, Object> manifest,
                                         String actualInstanceId) {
        Map<String, Object> identity = new TreeMap<>();
        identity.put("endpointConfigurationVersion", endpoint.getConfigurationVersion());
        identity.put("runtimeId", stringValue(manifest, "runtime_id", "runtimeId"));
        identity.put("runtimeRevision", stringValue(manifest,
                "runtime_revision", "runtimeRevision", "revision"));
        identity.put("runtimeType", normalizeRuntimeType(stringValue(manifest, "runtime_type", "runtimeType")));
        identity.put("instanceId", stringValue(manifest, "instance_id", "instanceId"));
        identity.put("actualInstanceId", actualInstanceId);
        identity.put("contractVersion", stringValue(manifest, "contract_version", "contractVersion"));
        identity.put("cliVersion", stringValue(manifest, "cli_version", "cliVersion"));
        identity.put("schemaDigest", stringValue(manifest, "schema_digest", "schemaDigest"));
        identity.put("models", canonicalValue(capabilityValue(manifest, "models")));
        identity.put("reasoningEfforts", canonicalValue(capabilityValue(
                manifest, "reasoning_efforts", "reasoningEfforts")));
        identity.put("modelAliases", canonicalValue(capabilityValue(
                manifest, "model_aliases", "modelAliases", "aliases")));
        identity.put("modelReasoningMatrix", canonicalValue(firstValue(manifest,
                "model_reasoning_matrix", "modelReasoningMatrix")));
        identity.put("features", canonicalValue(features(manifest)));
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(identity);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(serialized);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("CAPABILITY_FINGERPRINT_FAILED", e);
        }
    }

    private Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonicalValue(nested)));
            return sorted;
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(this::canonicalValue).toList();
        }
        return value;
    }

    private boolean supportsUltra(CodexRuntimeEntity entity) {
        Map<String, Object> manifest = parseManifest(entity.getCapabilityManifestJson());
        return supportsCoreAppServerContract(manifest)
                && supportsNativeSubtaskContractV1(manifest)
                && ((supportsModelReasoning(manifest, "gpt-5.6-sol:ultra")
                        && supportsModel(manifest, "gpt-5.6-sol:ultra"))
                    || (supportsModelReasoning(manifest, "gpt-5.6-terra:ultra")
                        && supportsModel(manifest, "gpt-5.6-terra:ultra")));
    }

    private boolean isModelAvailable(CodexRuntimeEntity entity, String requestedModel) {
        Map<String, Object> manifest = parseManifest(entity.getCapabilityManifestJson());
        boolean ultra = resolveModel(requestedModel, modelAliases(manifest)).isUltra();
        if (!CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())
                || entity.getArchivedAt() != null
                || !Boolean.TRUE.equals(entity.getEnabled())
                || !"READY".equals(entity.getReadinessStatus())
                || !isCapabilityFresh(entity)
                || !supportsCoreAppServerContract(manifest)
                || (ultra && !supportsNativeSubtaskContractV1(manifest))
                || !supportsModelReasoning(manifest, requestedModel)
                || !supportsModel(manifest, requestedModel)) {
            return false;
        }
        CodexRuntimeRoutingPolicy policy = parseRoutingPolicy(entity.getRoutingPolicy());
        return switch (policy) {
            case ULTRA_CANARY -> ultra && defaultValue(entity.getRolloutPercentage(), 0) > 0;
            case ULTRA_DEFAULT -> ultra;
            case ALL_CANARY -> ultra || defaultValue(entity.getRolloutPercentage(), 0) > 0;
            case ALL_DEFAULT -> true;
            case DARK, DRAINING -> false;
        };
    }

    private boolean isModelSupported(CodexRuntimeEntity entity, String requestedModel) {
        Map<String, Object> manifest = parseManifest(entity.getCapabilityManifestJson());
        boolean ultra = resolveModel(requestedModel, modelAliases(manifest)).isUltra();
        return CodexRuntimeType.APP_SERVER.name().equals(entity.getRuntimeType())
                && entity.getArchivedAt() == null
                && supportsCoreAppServerContract(manifest)
                && (!ultra || supportsNativeSubtaskContractV1(manifest))
                && supportsModelReasoning(manifest, requestedModel)
                && supportsModel(manifest, requestedModel);
    }

    private record ModelResolution(String baseModel, String reasoningEffort) {
        private boolean isUltra() {
            return "ultra".equals(reasoningEffort);
        }
    }

    private String maskedEndpoint(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) return null;
        try {
            URI uri = URI.create(endpointUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank() || uri.getScheme() == null) return "configured";
            if (host.contains(":")) host = "[" + host + "]";
            String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host + port;
        } catch (Exception e) {
            return "configured";
        }
    }

    private CodexRuntimeEntity requireRevision(String runtimeId, int revision) {
        return runtimeRepository.findByRuntimeIdAndRevision(runtimeId, revision)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Runtime revision not found: " + runtimeId + "@" + revision));
    }

    private CodexRuntimeEntity requireRevisionForUpdate(String runtimeId, int revision) {
        return runtimeRepository.findByRuntimeIdAndRevisionForUpdate(runtimeId, revision)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Runtime revision not found: " + runtimeId + "@" + revision));
    }

    private void requireRoutingEpoch(CodexRuntimeEntity entity, Long expectedRoutingEpoch) {
        if (expectedRoutingEpoch == null || !expectedRoutingEpoch.equals(entity.getRoutingEpoch())) {
            throw new IllegalStateException("CODEX_RUNTIME_ROUTING_EPOCH_CONFLICT: expected "
                    + entity.getRoutingEpoch());
        }
    }

    private void validateRollout(Integer percentage) {
        if (percentage != null && (percentage < 0 || percentage > 100)) {
            throw new IllegalArgumentException("rolloutPercentage must be between 0 and 100");
        }
    }

    private boolean isAllowedTransition(CodexRuntimeRoutingPolicy current,
                                        CodexRuntimeRoutingPolicy requested) {
        if (current == requested || requested == CodexRuntimeRoutingPolicy.DRAINING) return true;
        return switch (current) {
            case DARK -> requested == CodexRuntimeRoutingPolicy.ULTRA_CANARY;
            case ULTRA_CANARY -> requested == CodexRuntimeRoutingPolicy.DARK
                    || requested == CodexRuntimeRoutingPolicy.ULTRA_DEFAULT;
            case ULTRA_DEFAULT -> requested == CodexRuntimeRoutingPolicy.ULTRA_CANARY
                    || requested == CodexRuntimeRoutingPolicy.ALL_CANARY;
            case ALL_CANARY -> requested == CodexRuntimeRoutingPolicy.ULTRA_DEFAULT
                    || requested == CodexRuntimeRoutingPolicy.ALL_DEFAULT;
            case ALL_DEFAULT -> requested == CodexRuntimeRoutingPolicy.ALL_CANARY;
            case DRAINING -> requested == CodexRuntimeRoutingPolicy.DARK;
        };
    }

    private boolean isValidIdentifier(String value, int maxLength) {
        return value != null && value.length() <= maxLength && value.matches("[A-Za-z0-9._-]+");
    }

    private CodexRuntimeType parseRuntimeType(String value) {
        try {
            return CodexRuntimeType.valueOf(normalizeRuntimeType(value));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported runtimeType: " + value);
        }
    }

    private String normalizeRuntimeType(String value) {
        return value == null ? null : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private CodexRuntimeRoutingPolicy parseRoutingPolicy(String value) {
        try {
            return CodexRuntimeRoutingPolicy.valueOf(
                    firstNonBlank(value, CodexRuntimeRoutingPolicy.DARK.name())
                            .trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported routingPolicy: " + value);
        }
    }

    private String stringValue(Map<String, Object> map, String... keys) {
        Object value = firstValue(map, keys);
        if (value == null && map.get("runtime") instanceof Map<?, ?> runtime) {
            Map<String, Object> typed = new LinkedHashMap<>();
            runtime.forEach((key, nestedValue) -> typed.put(String.valueOf(key), nestedValue));
            value = firstValue(typed, keys);
        }
        return value != null ? value.toString() : null;
    }

    private Integer integerValue(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean containsKey(String[] values, String expected) {
        for (String value : values) {
            if (expected.equals(value)) return true;
        }
        return false;
    }

    private String capabilityFailureCode(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CodexWorkerClient.RuntimeInstanceProofException proof) {
                return "CODEX_RUNTIME_INSTANCE_PROOF_MISMATCH".equals(proof.getCode())
                        ? "CAPABILITY_INSTANCE_ID_MISMATCH"
                        : "CAPABILITY_INSTANCE_PROOF_MISSING";
            }
            if (current instanceof WebClientResponseException response) {
                return "CAPABILITY_HTTP_" + response.getStatusCode().value();
            }
            if (current instanceof WebClientRequestException) {
                return "CAPABILITY_ENDPOINT_UNREACHABLE";
            }
            current = current.getCause();
        }
        return "CAPABILITY_REFRESH_FAILED";
    }

    private void applyCapabilityFailure(CodexRuntimeEntity entity, String failureCode) {
        entity.setReadinessStatus("UNREACHABLE");
        String currentFailure = failureCode != null ? failureCode : "CAPABILITY_REFRESH_FAILED";
        entity.setReadinessMessage(hasReadinessCode(entity, "CAPABILITY_INSTANCE_ID_MISMATCH")
                ? "CAPABILITY_INSTANCE_ID_MISMATCH; " + currentFailure
                : currentFailure);
        entity.setLastCapabilityAt(LocalDateTime.now());
    }

    private void validateRecoveryEligibility(CodexRuntimeEntity entity) {
        if (Boolean.TRUE.equals(entity.getEnabled())
                || !CodexRuntimeRoutingPolicy.DARK.name().equals(entity.getRoutingPolicy())) {
            throw new IllegalStateException(
                    "CODEX_RUNTIME_INSTANCE_RECOVERY_REQUIRES_DISABLED_DARK");
        }
        if (!isValidIdentifier(entity.getInstanceId(), 128)) {
            throw new IllegalStateException("CODEX_RUNTIME_INSTANCE_AFFINITY_MISSING");
        }
    }

    private void retainInstanceQuarantine(CodexRuntimeEntity entity) {
        String current = entity.getReadinessMessage();
        entity.setReadinessStatus("INCOMPATIBLE");
        entity.setReadinessMessage(hasReadinessCode(entity, "CAPABILITY_INSTANCE_ID_MISMATCH")
                ? current
                : "CAPABILITY_INSTANCE_ID_MISMATCH"
                    + (current == null || current.isBlank() ? "" : "; " + current));
    }

    private boolean hasReadinessCode(CodexRuntimeEntity entity, String code) {
        if (entity.getReadinessMessage() == null || entity.getReadinessMessage().isBlank()) return false;
        for (String value : entity.getReadinessMessage().split(";")) {
            if (code.equals(value.trim())) return true;
        }
        return false;
    }

    private String exceptionType(Throwable error) {
        return error != null ? error.getClass().getSimpleName() : "UnknownException";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private int defaultValue(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

}
