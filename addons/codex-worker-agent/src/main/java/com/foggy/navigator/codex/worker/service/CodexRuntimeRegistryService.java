package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeRoutingPolicy;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexRuntimeEntity;
import com.foggy.navigator.codex.worker.model.form.CodexRuntimeRegistrationForm;
import com.foggy.navigator.codex.worker.model.form.CodexRuntimeRoutingForm;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    public static final String PINNED_APP_SERVER_CLI_VERSION = "0.144.1";
    public static final String PINNED_SCHEMA_DIGEST =
            "6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f";

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
            "WORKER_TOKEN_MISSING",
            "CODEX_HOME_MISSING",
            "APP_SERVER_CLI_UNAVAILABLE",
            "APP_SERVER_CLI_VERSION_MISMATCH",
            "ALLOWED_CWDS_MISSING",
            "APP_SERVER_WORKER_DRAINING");

    private final CodexRuntimeRepository runtimeRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final CodexWorkerClientFactory clientFactory;
    private final ObjectMapper objectMapper;
    private final CodexRuntimeCapabilityStateService capabilityStateService;

    @Value("${navigator.codex.runtime.capability-max-age-seconds:120}")
    private long capabilityMaxAgeSeconds = 120;

    @Transactional
    public CodexRuntimeDTO registerRevision(CodexRuntimeRegistrationForm form) {
        validateRegistration(form);
        List<CodexRuntimeEntity> existingRevisions = runtimeRepository
                .findByRuntimeIdOrderByRevisionDesc(form.getRuntimeId());
        if (existingRevisions.stream().anyMatch(existing ->
                !form.getWorkerId().trim().equals(existing.getWorkerId()))) {
            throw new IllegalArgumentException("Runtime ID is already owned by another worker");
        }
        Integer storedMaxRevision = runtimeRepository.findMaxRevision(form.getRuntimeId());
        int maxRevision = storedMaxRevision != null ? storedMaxRevision : 0;
        if (maxRevision == Integer.MAX_VALUE) {
            throw new IllegalStateException("Runtime revision sequence is exhausted");
        }
        int nextRevision = maxRevision + 1;
        if (form.getRevision() != null && form.getRevision() != nextRevision) {
            throw new IllegalArgumentException("Runtime revision must be the next revision: " + nextRevision);
        }
        int revision = nextRevision;
        if (runtimeRepository.findByRuntimeIdAndRevision(form.getRuntimeId(), revision).isPresent()) {
            throw new IllegalArgumentException("Runtime revision already exists: "
                    + form.getRuntimeId() + "@" + revision);
        }

        CodexRuntimeType runtimeType = parseRuntimeType(form.getRuntimeType());
        CodexRuntimeRoutingPolicy routingPolicy = parseRoutingPolicy(form.getRoutingPolicy());
        validateRollout(form.getRolloutPercentage());

        CodexRuntimeEntity entity = new CodexRuntimeEntity();
        entity.setRuntimeId(form.getRuntimeId().trim());
        entity.setRevision(revision);
        entity.setWorkerId(form.getWorkerId().trim());
        entity.setRuntimeType(runtimeType.name());
        entity.setEndpointUrl(trimTrailingSlash(form.getEndpointUrl()));
        entity.setAuthTokenCiphertext(credentialEncryptor.encrypt(blankToNull(form.getAuthToken())));
        entity.setInstanceId(blankToNull(form.getInstanceId()));
        entity.setEnabled(Boolean.TRUE.equals(form.getEnabled()));
        entity.setRoutingPolicy(routingPolicy.name());
        entity.setRolloutPercentage(defaultValue(form.getRolloutPercentage(), 0));
        entity.setPriority(defaultValue(form.getPriority(), 0));
        entity.setRoutingEpoch(1L);
        entity.setReadinessStatus("PENDING");
        entity.setExpectedCliVersion(firstNonBlank(form.getExpectedCliVersion(), PINNED_APP_SERVER_CLI_VERSION));
        entity.setExpectedSchemaDigest(firstNonBlank(form.getExpectedSchemaDigest(), PINNED_SCHEMA_DIGEST));

        CodexRuntimeEntity saved = runtimeRepository.save(entity);
        log.info("Registered Codex runtime revision: runtimeId={}, revision={}, workerId={}, type={}, policy={}",
                saved.getRuntimeId(), saved.getRevision(), saved.getWorkerId(),
                saved.getRuntimeType(), saved.getRoutingPolicy());
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<CodexRuntimeDTO> listByWorker(String workerId) {
        return runtimeRepository.findByWorkerIdOrderByPriorityDescRevisionDesc(workerId).stream()
                .sorted(RUNTIME_ORDER)
                .map(this::toDTO)
                .toList();
    }

    @Transactional
    public CodexRuntimeDTO updateRouting(String runtimeId, int revision, CodexRuntimeRoutingForm form) {
        CodexRuntimeEntity entity = requireRevisionForUpdate(runtimeId, revision);
        if (form == null || form.getExpectedRoutingEpoch() == null
                || !form.getExpectedRoutingEpoch().equals(entity.getRoutingEpoch())) {
            throw new IllegalStateException("CODEX_RUNTIME_ROUTING_EPOCH_CONFLICT: expected "
                    + entity.getRoutingEpoch());
        }
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
        List<CodexRuntimeEntity> enabled = runtimeRepository.findByEnabledTrueOrderByUpdatedAtAsc();
        for (CodexRuntimeEntity runtime : enabled) {
            if (!CodexRuntimeType.APP_SERVER.name().equals(runtime.getRuntimeType())) continue;
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
        List<CodexRuntimeEntity> candidates = runtimeRepository
                .findByWorkerIdAndRuntimeTypeAndEnabledTrueOrderByPriorityDescRevisionDesc(
                        workerId, CodexRuntimeType.APP_SERVER.name()).stream()
                .sorted(RUNTIME_ORDER)
                .toList();
        boolean ultra = "ultra".equals(reasoningEffort(model));
        boolean targeted = false;

        for (CodexRuntimeEntity candidate : candidates) {
            CodexRuntimeRoutingPolicy policy = parseRoutingPolicy(candidate.getRoutingPolicy());
            if (!targetsTask(policy, ultra, candidate.getRolloutPercentage(), routingKey,
                    candidate.getRuntimeId())) {
                continue;
            }
            targeted = true;
            if (isUsable(candidate, model, providerType, requiredFeatures)) {
                return toBinding(candidate);
            }
        }

        if (targeted || ultra) {
            String code = ultra ? "CODEX_ULTRA_RUNTIME_UNAVAILABLE" : "CODEX_RUNTIME_UNAVAILABLE";
            throw new CodexRuntimeUnavailableException(code,
                    "No compatible READY app-server runtime is available for the selected rollout cohort");
        }
        return CodexRuntimeBinding.legacySdk(workerId);
    }

    @Transactional(readOnly = true)
    public CodexRuntimeBinding resolveBoundRuntime(String runtimeId, Integer revision, String workerId) {
        return resolveBoundRuntime(runtimeId, revision, workerId, null);
    }

    @Transactional(readOnly = true)
    public CodexRuntimeBinding resolveBoundRuntime(String runtimeId, Integer revision, String workerId,
                                                    String expectedInstanceId) {
        if (runtimeId == null || runtimeId.isBlank()) {
            return CodexRuntimeBinding.legacySdk(workerId);
        }
        if (runtimeId.startsWith("legacy-sdk:")) {
            String boundWorkerId = runtimeId.substring("legacy-sdk:".length());
            if (boundWorkerId.isBlank()) {
                throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_INVALID",
                        "Legacy SDK runtime affinity has no worker");
            }
            if (workerId != null && !workerId.equals(boundWorkerId)) {
                throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_MISMATCH",
                        "Legacy SDK runtime belongs to another worker");
            }
            return CodexRuntimeBinding.legacySdk(boundWorkerId);
        }
        if (revision == null) {
            throw new CodexRuntimeUnavailableException("CODEX_RUNTIME_AFFINITY_INVALID",
                    "Runtime revision is missing for " + runtimeId);
        }
        return runtimeRepository.findByRuntimeIdAndRevision(runtimeId, revision)
                .map(entity -> {
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
        if (!entity.getRuntimeId().equals(runtimeId)) {
            incompatibilities.add("CAPABILITY_RUNTIME_ID_MISMATCH");
        }
        if (!entity.getRevision().toString().equals(runtimeRevision)) {
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
        if (!entity.getExpectedCliVersion().equals(cliVersion)) {
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
        if ("ultra".equals(reasoningEffort(model)) && !supportsNativeSubtaskContractV1(manifest)) {
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

    private String reasoningEffort(String model) {
        if (model == null) return null;
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        String resolved = resolveAlias(normalized, DEFAULT_MODEL_ALIASES);
        return resolvedReasoningEffort(resolved);
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
                credentialEncryptor.decrypt(entity.getAuthTokenCiphertext()), entity.getInstanceId());
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
                .authToken(credentialEncryptor.decrypt(entity.getAuthTokenCiphertext()))
                .instanceId(entity.getInstanceId())
                .routingEpoch(entity.getRoutingEpoch())
                .build();
    }

    private CodexRuntimeDTO toDTO(CodexRuntimeEntity entity) {
        return CodexRuntimeDTO.builder()
                .runtimeId(entity.getRuntimeId())
                .revision(entity.getRevision())
                .workerId(entity.getWorkerId())
                .runtimeType(entity.getRuntimeType())
                .endpointConfigured(entity.getEndpointUrl() != null && !entity.getEndpointUrl().isBlank())
                .endpointDisplay(maskedEndpoint(entity.getEndpointUrl()))
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
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private boolean isCapabilityFresh(CodexRuntimeEntity entity) {
        return entity.getLastCapabilityAt() != null
                && !entity.getLastCapabilityAt().isBefore(
                        LocalDateTime.now().minusSeconds(capabilityMaxAgeSeconds));
    }

    private boolean supportsUltra(CodexRuntimeEntity entity) {
        Map<String, Object> manifest = parseManifest(entity.getCapabilityManifestJson());
        return supportsCoreAppServerContract(manifest)
                && supportsNativeSubtaskContractV1(manifest)
                && supportsModelReasoning(manifest, "gpt-5.6-sol:ultra")
                && supportsModel(manifest, "gpt-5.6-sol:ultra");
    }

    private String maskedEndpoint(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) return null;
        try {
            URI uri = URI.create(endpointUrl);
            String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
            return uri.getScheme() + "://***" + port;
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

    private void validateRegistration(CodexRuntimeRegistrationForm form) {
        if (form == null) throw new IllegalArgumentException("runtime registration is required");
        requireIdentifier(form.getRuntimeId(), "runtimeId", 64);
        requireIdentifier(form.getWorkerId(), "workerId", 64);
        if (parseRuntimeType(form.getRuntimeType()) != CodexRuntimeType.APP_SERVER) {
            throw new IllegalArgumentException("runtimeType must be APP_SERVER");
        }
        requireText(form.getEndpointUrl(), "endpointUrl");
        validateEndpoint(form.getEndpointUrl());
        requireText(form.getAuthToken(), "authToken");
        validateOptionalText(form.getAuthToken(), "authToken", 4096);
        validateOptionalIdentifier(form.getInstanceId(), "instanceId", 128);
        if (form.getRevision() != null && form.getRevision() < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (!PINNED_APP_SERVER_CLI_VERSION.equals(
                firstNonBlank(form.getExpectedCliVersion(), PINNED_APP_SERVER_CLI_VERSION))) {
            throw new IllegalArgumentException("expectedCliVersion must be pinned to "
                    + PINNED_APP_SERVER_CLI_VERSION);
        }
        if (!PINNED_SCHEMA_DIGEST.equals(
                firstNonBlank(form.getExpectedSchemaDigest(), PINNED_SCHEMA_DIGEST))) {
            throw new IllegalArgumentException("expectedSchemaDigest must match the pinned canonical schema");
        }
        if (Boolean.TRUE.equals(form.getEnabled())
                || parseRoutingPolicy(form.getRoutingPolicy()) != CodexRuntimeRoutingPolicy.DARK
                || defaultValue(form.getRolloutPercentage(), 0) != 0) {
            throw new IllegalArgumentException("New runtime revisions must start disabled in DARK with 0% rollout");
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

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private void requireIdentifier(String value, String field, int maxLength) {
        requireText(value, field);
        if (value.length() > maxLength || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters or exceeds " + maxLength);
        }
    }

    private void validateOptionalIdentifier(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) return;
        requireIdentifier(value, field, maxLength);
    }

    private boolean isValidIdentifier(String value, int maxLength) {
        return value != null && value.length() <= maxLength && value.matches("[A-Za-z0-9._-]+");
    }

    private void validateOptionalText(String value, String field, int maxLength) {
        if (value == null) return;
        if (value.length() > maxLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains control characters or exceeds " + maxLength);
        }
    }

    private void validateEndpoint(String endpointUrl) {
        if (endpointUrl.length() > 512 || endpointUrl.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("endpointUrl contains control characters or exceeds 512");
        }
        try {
            URI uri = URI.create(endpointUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("endpointUrl must be an absolute http(s) URL");
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "endpointUrl must not contain userinfo, query parameters, or fragments");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("endpointUrl is invalid", e);
        }
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

    private boolean containsKey(String[] values, String expected) {
        for (String value : values) {
            if (expected.equals(value)) return true;
        }
        return false;
    }

    private String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
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
