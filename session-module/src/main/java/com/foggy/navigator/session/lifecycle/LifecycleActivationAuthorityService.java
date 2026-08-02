package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleActivationTargetEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterGenerationEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterInstanceRegistrationEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleActivationTargetRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterGenerationRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterInstanceRegistrationRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofRepository;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleActivationReadiness;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePort;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePortResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class LifecycleActivationAuthorityService {
    static final String TARGET_CLASS = "ISOLATED_LOCAL_NON_FIXTURE";
    static final String LOCAL_DEVELOPMENT_TARGET_CLASS =
            "BOUNDED_ISOLATED_LOCAL_DEVELOPMENT";
    static final String PROVIDER_LANE = "REAL_CODEX_MODEL";
    static final String DISPOSABLE_PROVIDER = "codex-biz-worker";
    static final String LOCAL_DEVELOPMENT_PROVIDER = "codex-worker";
    static final String MANIFEST_SCHEMA =
            "NAVIGATOR_ARCH001_ACTIVATION_TARGET_V2";
    static final String OBSERVATION_SCHEMA =
            "NAVIGATOR_ARCH001_CONTROLLER_OBSERVATION_V1";
    static final String ACTIVE_SLOT = "ACTIVE";
    static final Set<String> REQUIRED_CAPABILITIES = Set.of(
            "AUTHENTICATED_LIFECYCLE_V1",
            "FENCED_INVENTORY_V1",
            "DURABLE_LIFECYCLE_FACTS_V1",
            "MONOTONIC_ACK_V1",
            "EXACT_DISPATCH_DEDUPE_V1",
            "DURABLE_PROVIDER_TASK_ID_V1",
            "TERMINATION_ATOMIC_CAPABILITY_V1");
    private static final Set<String> REQUIRED_CONTROLLER_KINDS = Set.of(
            "process", "supervisor", "manual_launcher", "ci", "timer",
            "docker");
    private static final Map<String, ControllerContract> CONTROLLER_CONTRACT =
            Map.of(
                    "process", new ControllerContract(
                            "target-process-set", "DISABLED", "proc-cwd-scan"),
                    "supervisor", new ControllerContract(
                            "none", "NOT_APPLICABLE",
                            "local-target-no-supervisor"),
                    "manual_launcher", new ControllerContract(
                            "target-pidfiles", "DISABLED",
                            "target-pidfile-scan"),
                    "ci", new ControllerContract(
                            "none", "NOT_APPLICABLE", "local-target-no-ci"),
                    "timer", new ControllerContract(
                            "none", "NOT_APPLICABLE", "local-target-no-timer"),
                    "docker", new ControllerContract(
                            "mysql-compose", "DISABLED", "compose-label-scan"));

    private final LifecycleActivationProperties properties;
    private final LifecycleActivationArtifactSource artifacts;
    private final LifecycleActivationTargetRepository targets;
    private final LifecycleWriterGenerationRepository generations;
    private final LifecycleWriterInstanceRegistrationRepository instances;
    private final LifecycleWriterProofRepository proofs;
    private final WorkerLifecycleSnapshotRepository workers;
    private final List<WorkerLifecyclePortResolver> workerResolvers;
    private final LifecycleAuthorityClock clock;
    private final WriterExclusivityProofService proofService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final boolean terminationReceiptEnabled;

    public LifecycleActivationAuthorityService(
            LifecycleActivationProperties properties,
            LifecycleActivationArtifactSource artifacts,
            LifecycleActivationTargetRepository targets,
            LifecycleWriterGenerationRepository generations,
            LifecycleWriterInstanceRegistrationRepository instances,
            LifecycleWriterProofRepository proofs,
            WorkerLifecycleSnapshotRepository workers,
            List<WorkerLifecyclePortResolver> workerResolvers,
            LifecycleAuthorityClock clock,
            WriterExclusivityProofService proofService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${navigator.runtime-audit.termination-receipt-enabled:false}")
            boolean terminationReceiptEnabled) {
        this.properties = properties;
        this.artifacts = artifacts;
        this.targets = targets;
        this.generations = generations;
        this.instances = instances;
        this.proofs = proofs;
        this.workers = workers;
        this.workerResolvers = List.copyOf(workerResolvers);
        this.clock = clock;
        this.proofService = proofService;
        this.objectMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.transactions = new TransactionTemplate(transactionManager);
        this.terminationReceiptEnabled = terminationReceiptEnabled;
    }

    public ActivationReadiness registerConfiguredTarget(String requestedTargetId) {
        requireControlConfiguration(requestedTargetId);
        var loaded = artifacts.load();
        return transactions.execute(status -> {
            LocalDateTime now = clock.databaseNow();
            verifyArtifacts(loaded, now);
            LifecycleActivationManifest manifest = loaded.manifest();
            LifecycleActivationTargetEntity existing = targets
                    .findForUpdate(manifest.targetId()).orElse(null);
            if (existing != null && Set.of(
                    "RESERVED", "ADMITTED", "CONSUMED", "QUARANTINED",
                    "DESTROYED").contains(existing.getStatus())) {
                throw denied(LifecycleActivationReason.TARGET_CONSUMED);
            }

            String generationId = stableId("activation-generation", manifest.targetId());
            String proofId = stableId("activation-proof", manifest.targetId());
            LifecycleWriterGenerationEntity generation = generations
                    .findForUpdate(generationId)
                    .orElseGet(LifecycleWriterGenerationEntity::new);
            if (generation.getGenerationId() != null) {
                requireGenerationBinding(generation, manifest);
                if (!Set.of("STAGED", "ACTIVE").contains(generation.getStatus())) {
                    throw denied(LifecycleActivationReason.GENERATION_NOT_ACTIVE);
                }
            } else {
                generation.setGenerationId(generationId);
                generation.setMinimumOwnerProtocol(manifest.candidate().ownerProtocol());
                generation.setTargetCommit(manifest.candidate().head());
                generation.setTargetId(manifest.targetId());
                generation.setRunId(manifest.runId());
                generation.setControllerInventoryDigest(
                        manifest.controllerInventoryDigest());
                generation.setStatus("STAGED");
                generations.save(generation);
            }

            LifecycleWriterInstanceRegistrationEntity instance = instances
                    .findForUpdate(required(properties.getInstanceId(),
                            LifecycleActivationReason.INSTANCE_NOT_REGISTERED))
                    .orElseGet(LifecycleWriterInstanceRegistrationEntity::new);
            if (instance.getInstanceId() != null) {
                requireInstanceBinding(instance, manifest, generationId);
            } else {
                instance.setInstanceId(properties.getInstanceId());
                instance.setGenerationId(generationId);
                instance.setOwnerProtocol(properties.getOwnerProtocol());
                instance.setTargetCommit(properties.getCandidateHead());
                instance.setTargetId(manifest.targetId());
                instance.setRunId(manifest.runId());
                instance.setControllerInventoryDigest(
                        manifest.controllerInventoryDigest());
                instance.setRegisteredAt(now);
            }
            instance.setStatus("REGISTERED");
            instance.setLastHeartbeatAt(now);
            instance.setExpiresAt(now.plus(validDuration(
                    properties.getInstanceTtl(), Duration.ofSeconds(5),
                    Duration.ofMinutes(2))));
            instances.save(instance);

            LifecycleActivationTargetEntity target = existing == null
                    ? new LifecycleActivationTargetEntity() : existing;
            if (existing != null) requireTargetManifestBinding(target, loaded);
            applyManifest(target, loaded, generationId, proofId);
            target.setStatus("REGISTERED");
            target.setSafeReasonCode(null);
            target.setLastObservedAt(now);
            targets.save(target);
            return inspectLocked(target, now);
        });
    }

    public ActivationReadiness acquireConfiguredProof(String requestedTargetId) {
        requireControlConfiguration(requestedTargetId);
        var loaded = artifacts.load();
        verifyBeforeWorkerObservation(loaded);
        WorkerLifecycleActivationReadiness observed = observeWorker(loaded.manifest());
        return transactions.execute(status -> {
            LocalDateTime now = clock.databaseNow();
            verifyArtifacts(loaded, now);
            LifecycleActivationTargetEntity target = requiredTargetForUpdate();
            requireTargetManifestBinding(target, loaded);
            if (!"REGISTERED".equals(target.getStatus())
                    && !"READY".equals(target.getStatus())) {
                throw denied(LifecycleActivationReason.TARGET_NOT_READY);
            }
            requireWorkerReadiness(loaded.manifest(), observed);
            LifecycleWriterGenerationEntity generation = generations
                    .findForUpdate(target.getGenerationId())
                    .orElseThrow(() -> denied(
                            LifecycleActivationReason.GENERATION_NOT_ACTIVE));
            requireGenerationBinding(generation, loaded.manifest());
            generations.findByActiveSlot(ACTIVE_SLOT).ifPresent(active -> {
                if (!active.getGenerationId().equals(generation.getGenerationId())) {
                    throw denied(LifecycleActivationReason.GENERATION_NOT_ACTIVE);
                }
            });
            generation.setStatus("ACTIVE");
            generation.setActiveSlot(ACTIVE_SLOT);
            generation.setActivatedAt(now);
            generations.saveAndFlush(generation);

            LifecycleWriterInstanceRegistrationEntity instance = instances
                    .findForUpdate(target.getWriterInstanceId())
                    .orElseThrow(() -> denied(
                            LifecycleActivationReason.INSTANCE_NOT_REGISTERED));
            requireActiveInstance(instance, loaded.manifest(), now);
            instance.setLastHeartbeatAt(now);
            instance.setExpiresAt(now.plus(validDuration(
                    properties.getInstanceTtl(), Duration.ofSeconds(5),
                    Duration.ofMinutes(2))));
            instances.save(instance);

            LifecycleWriterProofEntity proof = proofs
                    .findForUpdate(target.getProofId())
                    .orElseGet(LifecycleWriterProofEntity::new);
            if (proof.getProofId() != null) {
                if (!target.getGenerationId().equals(proof.getGenerationId())
                        || !target.getWriterInstanceId().equals(
                        proof.getHolderInstanceId())
                        || !target.getControllerInventoryDigest().equals(
                        proof.getControllerInventoryDigest())
                        || Set.of("QUARANTINING", "QUARANTINED")
                        .contains(proof.getStatus())) {
                    throw denied(LifecycleActivationReason.PROOF_NOT_ACTIVE);
                }
                proof.setProofVersion(proof.getProofVersion() + 1);
            } else {
                proof.setProofId(target.getProofId());
                proof.setGenerationId(target.getGenerationId());
                proof.setControllerInventoryDigest(
                        target.getControllerInventoryDigest());
                proof.setHolderInstanceId(target.getWriterInstanceId());
                proof.setProofVersion(1);
                proof.setAcquiredAt(now);
            }
            proof.setStatus("ACTIVE");
            proof.setLastVerifiedAt(now);
            proof.setExpiresAt(now.plus(validDuration(
                    properties.getProofLease(), Duration.ofSeconds(5),
                    Duration.ofMinutes(2))));
            proof.setQuarantineCursor(null);
            proofs.save(proof);

            WorkerLifecycleSnapshotEntity worker = workers
                    .findForUpdate(target.getPhysicalWorkerId())
                    .orElseGet(WorkerLifecycleSnapshotEntity::new);
            if (worker.getPhysicalWorkerId() != null) {
                boolean sameGeneration = observed.identity().stateGeneration()
                        .equals(worker.getStateGeneration());
                boolean sameInstance = observed.identity().instanceEpoch()
                        .equals(worker.getInstanceEpoch());
                boolean cleanFrozenRestart = sameGeneration
                        && LifecycleAvailability.OFFLINE_FROZEN.name().equals(
                        worker.getAvailability());
                if (!"SHADOW".equals(worker.getOwnershipMode())
                        || !sameGeneration
                        || (!sameInstance && !cleanFrozenRestart)
                        || !LifecycleConflictState.NONE.name().equals(
                        worker.getConflictState())) {
                    throw denied(
                            LifecycleActivationReason.WORKER_IDENTITY_MISMATCH);
                }
            }
            if (worker.getPhysicalWorkerId() == null) {
                worker.setPhysicalWorkerId(target.getPhysicalWorkerId());
                worker.setOwnershipMode("SHADOW");
                worker.setFactCursor(0);
                worker.setPolicyVersion("ARCH-001-ACT-001");
                worker.setWriterGenerationId(null);
                worker.setSnapshotJson("{}");
            }
            worker.setStateGeneration(observed.identity().stateGeneration());
            worker.setInstanceEpoch(observed.identity().instanceEpoch());
            worker.setAvailability(LifecycleAvailability.READY.name());
            worker.setConflictState(LifecycleConflictState.NONE.name());
            workers.save(worker);

            target.setWorkerStateGeneration(
                    observed.identity().stateGeneration());
            target.setWorkerInstanceEpoch(observed.identity().instanceEpoch());
            target.setStatus("READY");
            target.setSafeReasonCode(null);
            target.setLastObservedAt(now);
            targets.save(target);
            return inspectLocked(target, now);
        });
    }

    public ActivationReadiness observeAndRenewConfiguredProof() {
        String targetId = properties.getExactTargetId();
        if (!properties.isControlEnabled() || targetId == null) {
            return closed(LifecycleActivationReason.CONTROL_DISABLED);
        }
        try {
            var loaded = artifacts.load();
            verifyBeforeWorkerObservation(loaded);
            WorkerLifecycleActivationReadiness observed =
                    observeWorker(loaded.manifest());
            return transactions.execute(status -> renewLocked(
                    loaded, observed, clock.databaseNow()));
        } catch (LifecycleActivationDeniedException drift) {
            quarantineConfiguredTarget(drift.getMessage());
            throw drift;
        } catch (RuntimeException unavailable) {
            quarantineConfiguredTarget(LifecycleActivationReason.WORKER_NOT_READY);
            throw denied(LifecycleActivationReason.WORKER_NOT_READY);
        }
    }

    public void quarantineConfiguredTarget(String safeReasonCode) {
        String targetId = properties.getExactTargetId();
        if (targetId == null) return;
        String proofId = transactions.execute(status -> {
            LifecycleActivationTargetEntity target = targets
                    .findForUpdate(targetId).orElse(null);
            if (target == null || "DESTROYED".equals(target.getStatus())) {
                return null;
            }
            target.setStatus("QUARANTINED");
            target.setSafeReasonCode(safeReasonCode);
            target.setLastObservedAt(clock.databaseNow());
            targets.save(target);
            generations.findForUpdate(target.getGenerationId()).ifPresent(generation -> {
                generation.setStatus("QUARANTINED");
                generation.setActiveSlot(null);
                generations.save(generation);
            });
            instances.findForUpdate(target.getWriterInstanceId()).ifPresent(instance -> {
                instance.setStatus("LOST");
                instances.save(instance);
            });
            return target.getProofId();
        });
        if (proofId != null && proofs.existsById(proofId)) {
            proofService.quarantine(proofId);
        }
    }

    public ActivationReadiness inspect() {
        if (!properties.isControlEnabled()) {
            return closed(LifecycleActivationReason.CONTROL_DISABLED);
        }
        if (properties.getExactTargetId() == null) {
            return closed(LifecycleActivationReason.TARGET_NOT_CONFIGURED);
        }
        return transactions.execute(status -> {
            LocalDateTime now = clock.databaseNow();
            LifecycleActivationTargetEntity target = targets.findById(
                    properties.getExactTargetId()).orElse(null);
            if (target == null) {
                return closed(LifecycleActivationReason.TARGET_NOT_REGISTERED);
            }
            return inspectLocked(target, now);
        });
    }

    void requireAdmissionEnabled() {
        if (!properties.isAdmissionEnabled()) {
            throw denied(LifecycleActivationReason.ADMISSION_DISABLED);
        }
    }

    LifecycleActivationArtifactSource.ActivationArtifacts loadVerifiedArtifacts(
            LocalDateTime now) {
        var loaded = artifacts.load();
        verifyArtifacts(loaded, now);
        return loaded;
    }

    void requireTargetAuthorityLocked(
            LifecycleActivationTargetEntity target,
            LifecycleActivationArtifactSource.ActivationArtifacts loaded,
            LocalDateTime now,
            Set<String> allowedStatuses) {
        requireTargetManifestBinding(target, loaded);
        if (!allowedStatuses.contains(target.getStatus())) {
            throw denied("CONSUMED".equals(target.getStatus())
                    ? LifecycleActivationReason.TARGET_CONSUMED
                    : LifecycleActivationReason.TARGET_NOT_READY);
        }
        LifecycleWriterGenerationEntity generation = generations
                .findForUpdate(target.getGenerationId())
                .orElseThrow(() -> denied(
                        LifecycleActivationReason.GENERATION_NOT_ACTIVE));
        if (!"ACTIVE".equals(generation.getStatus())
                || !ACTIVE_SLOT.equals(generation.getActiveSlot())) {
            throw denied(LifecycleActivationReason.GENERATION_NOT_ACTIVE);
        }
        requireGenerationBinding(generation, loaded.manifest());
        LifecycleWriterInstanceRegistrationEntity instance = instances
                .findForUpdate(target.getWriterInstanceId())
                .orElseThrow(() -> denied(
                        LifecycleActivationReason.INSTANCE_NOT_REGISTERED));
        requireActiveInstance(instance, loaded.manifest(), now);
        LifecycleWriterProofEntity proof = proofs
                .findForUpdate(target.getProofId())
                .orElseThrow(() -> denied(
                        LifecycleActivationReason.PROOF_NOT_ACTIVE));
        if (!"ACTIVE".equals(proof.getStatus())
                || !proof.getExpiresAt().isAfter(now)
                || !target.getGenerationId().equals(proof.getGenerationId())
                || !target.getWriterInstanceId().equals(
                proof.getHolderInstanceId())
                || !target.getControllerInventoryDigest().equals(
                proof.getControllerInventoryDigest())) {
            throw denied(LifecycleActivationReason.PROOF_NOT_ACTIVE);
        }
        if (!terminationReceiptEnabled) {
            throw denied(LifecycleActivationReason.RECEIPT_REQUIRED);
        }
    }

    LifecycleActivationTargetRepository targetRepository() {
        return targets;
    }

    LifecycleActivationProperties properties() {
        return properties;
    }

    private ActivationReadiness renewLocked(
            LifecycleActivationArtifactSource.ActivationArtifacts loaded,
            WorkerLifecycleActivationReadiness observed,
            LocalDateTime now) {
        verifyArtifacts(loaded, now);
        LifecycleActivationTargetEntity target = requiredTargetForUpdate();
        requireTargetAuthorityLocked(target, loaded, now,
                Set.of("READY", "RESERVED", "ADMITTED", "CONSUMED"));
        requireWorkerReadiness(loaded.manifest(), observed);
        if (!Objects.equals(target.getWorkerStateGeneration(),
                observed.identity().stateGeneration())
                || !Objects.equals(target.getWorkerInstanceEpoch(),
                observed.identity().instanceEpoch())) {
            throw denied(LifecycleActivationReason.WORKER_IDENTITY_MISMATCH);
        }
        LifecycleWriterProofEntity proof = proofs
                .findForUpdate(target.getProofId()).orElseThrow();
        proof.setProofVersion(proof.getProofVersion() + 1);
        proof.setLastVerifiedAt(now);
        proof.setExpiresAt(now.plus(validDuration(
                properties.getProofLease(), Duration.ofSeconds(5),
                Duration.ofMinutes(2))));
        proofs.save(proof);
        LifecycleWriterInstanceRegistrationEntity instance = instances
                .findForUpdate(target.getWriterInstanceId()).orElseThrow();
        instance.setLastHeartbeatAt(now);
        instance.setExpiresAt(now.plus(validDuration(
                properties.getInstanceTtl(), Duration.ofSeconds(5),
                Duration.ofMinutes(2))));
        instances.save(instance);
        target.setLastObservedAt(now);
        targets.save(target);
        return inspectLocked(target, now);
    }

    private void verifyArtifacts(
            LifecycleActivationArtifactSource.ActivationArtifacts loaded,
            LocalDateTime now) {
        if (loaded == null) {
            throw denied(LifecycleActivationReason.MANIFEST_INVALID);
        }
        LifecycleActivationManifest manifest = loaded.manifest();
        LifecycleActivationManifest.ControllerObservation observation =
                loaded.observation();
        if (manifest == null || observation == null
                || manifest.candidate() == null
                || manifest.exactTuple() == null
                || manifest.target() == null
                || manifest.worker() == null
                || manifest.controllers() == null
                || manifest.controllers().stream().anyMatch(Objects::isNull)
                || manifest.worker().requiredCapabilities() == null
                || manifest.runId() == null
                || manifest.runId().isBlank()
                || manifest.exactTuple().physicalWorkerId() == null
                || manifest.exactTuple().physicalWorkerId().isBlank()) {
            throw denied(LifecycleActivationReason.MANIFEST_INVALID);
        }
        boolean localDevelopmentTarget = LOCAL_DEVELOPMENT_TARGET_CLASS.equals(
                manifest.targetClass());
        if (localDevelopmentTarget
                && !properties.isLocalDevelopmentTargetEnabled()) {
            throw denied(LifecycleActivationReason
                    .LOCAL_DEVELOPMENT_TARGET_DISABLED);
        }
        String expectedProvider = localDevelopmentTarget
                ? LOCAL_DEVELOPMENT_PROVIDER : DISPOSABLE_PROVIDER;
        if (!MANIFEST_SCHEMA.equals(manifest.schema())
                || !OBSERVATION_SCHEMA.equals(observation.schema())
                || (!TARGET_CLASS.equals(manifest.targetClass())
                && !localDevelopmentTarget)
                || !PROVIDER_LANE.equals(manifest.providerEvidenceLane())
                || !expectedProvider.equals(
                manifest.exactTuple().providerType())
                || !"8.0.44".equals(manifest.target().mysqlVersion())
                || !required(properties.getExactTargetId(),
                LifecycleActivationReason.TARGET_NOT_CONFIGURED)
                .equals(manifest.targetId())
                || !required(properties.getCandidateHead(),
                LifecycleActivationReason.CANDIDATE_MISMATCH)
                .equals(manifest.candidate().head())
                || !required(properties.getCandidatePatchSha256(),
                LifecycleActivationReason.CANDIDATE_MISMATCH)
                .equals(manifest.candidate().patchSha256())
                || properties.getOwnerProtocol()
                != manifest.candidate().ownerProtocol()) {
            throw denied(LifecycleActivationReason.MANIFEST_MISMATCH);
        }
        if (manifest.exactTuple().tenantId() == null
                || manifest.exactTuple().tenantId().isBlank()
                || manifest.exactTuple().userId() == null
                || manifest.exactTuple().userId().isBlank()
                || manifest.exactTuple().modelConfigId() == null
                || manifest.exactTuple().modelConfigId().isBlank()
                || manifest.exactTuple().model() == null
                || manifest.exactTuple().model().isBlank()
                || manifest.exactTuple().promptSha256() == null
                || manifest.exactTuple().promptSha256().length() != 64
                || (!localDevelopmentTarget
                && (manifest.exactTuple().codexHomeKey() == null
                || manifest.exactTuple().codexHomeKey().isBlank()))) {
            throw denied(LifecycleActivationReason.MANIFEST_INVALID);
        }
        LifecycleAuthorityClock.DatabaseIdentity database =
                clock.databaseIdentity();
        if (database == null
                || !"MySQL".equalsIgnoreCase(database.product())
                || database.version() == null
                || !database.version().startsWith(
                manifest.target().mysqlVersion())
                || !Objects.equals(
                manifest.target().database(), database.database())
                || database.host() == null
                || !("localhost".equalsIgnoreCase(database.host())
                || database.host().startsWith("127."))
                || database.port() != manifest.target().mysqlPort()) {
            throw denied(LifecycleActivationReason.DATABASE_MISMATCH);
        }
        String digest = controllerDigest(manifest.controllers());
        Set<String> kinds = new LinkedHashSet<>();
        for (LifecycleActivationManifest.Controller controller :
                manifest.controllers()) {
            kinds.add(controller.kind());
            ControllerContract expected = CONTROLLER_CONTRACT.get(
                    controller.kind());
            if (expected == null
                    || !expected.id().equals(controller.id())
                    || !expected.state().equals(controller.state())
                    || !expected.source().equals(controller.source())
                    || !"NONE".equals(controller.restartPolicy())
                    || !Objects.equals(
                    manifest.runId(), controller.ownershipRunId())
                    || !manifest.candidate().head().equals(
                    controller.artifactCommit())
                    || !Objects.equals(
                    manifest.target().root(), controller.cwd())) {
                throw denied(
                        LifecycleActivationReason.CONTROLLER_INVENTORY_UNPROVEN);
            }
        }
        if (!kinds.equals(REQUIRED_CONTROLLER_KINDS)
                || manifest.controllers().size()
                != REQUIRED_CONTROLLER_KINDS.size()
                || !digest.equals(manifest.controllerInventoryDigest())
                || !manifest.targetId().equals(observation.targetId())
                || !manifest.runId().equals(observation.runId())
                || !loaded.manifestDigest().equals(
                observation.manifestDigest())
                || !digest.equals(observation.controllerInventoryDigest())
                || !observation.allKnownControllersDisabled()
                || observation.lateRelaunchDetected()
                || observation.unknownControllerCount() != 0
                || !"LIVE_LOCAL_INSPECTION".equals(
                observation.evidenceSource())
                || observation.observedAt() == null) {
            throw denied(LifecycleActivationReason.CONTROLLER_DRIFT);
        }
        LocalDateTime observedAt = LocalDateTime.ofInstant(
                observation.observedAt(), ZoneOffset.UTC);
        Duration maximumAge = validDuration(
                properties.getObservationMaxAge(), Duration.ofSeconds(1),
                Duration.ofMinutes(2));
        if (observedAt.isAfter(now.plusSeconds(1))
                || observedAt.plus(maximumAge).isBefore(now)) {
            throw denied(LifecycleActivationReason.CONTROLLER_DRIFT);
        }
        if (!manifest.worker().requiredCapabilities()
                .containsAll(REQUIRED_CAPABILITIES)) {
            throw denied(LifecycleActivationReason.WORKER_CAPABILITY_MISMATCH);
        }
    }

    private WorkerLifecycleActivationReadiness observeWorker(
            LifecycleActivationManifest manifest) {
        List<WorkerLifecyclePort> ports = new ArrayList<>();
        for (WorkerLifecyclePortResolver resolver : workerResolvers) {
            resolver.resolve(manifest.exactTuple().physicalWorkerId())
                    .ifPresent(ports::add);
        }
        if (ports.size() != 1) {
            throw denied(LifecycleActivationReason.WORKER_NOT_READY);
        }
        try {
            return ports.get(0).activationReadiness(
                    manifest.exactTuple().physicalWorkerId());
        } catch (RuntimeException unavailable) {
            throw denied(LifecycleActivationReason.WORKER_NOT_READY);
        }
    }

    private void verifyBeforeWorkerObservation(
            LifecycleActivationArtifactSource.ActivationArtifacts loaded) {
        transactions.executeWithoutResult(status ->
                verifyArtifacts(loaded, clock.databaseNow()));
    }

    private void requireWorkerReadiness(
            LifecycleActivationManifest manifest,
            WorkerLifecycleActivationReadiness observed) {
        if (observed == null || !observed.workerReady()
                || !observed.lifecycleReady()
                || !observed.terminationReady()
                || !observed.lifecycleCredentialAuthenticated()
                || !observed.providerCredentialConfigured()
                || observed.identity() == null) {
            throw denied(LifecycleActivationReason.WORKER_NOT_READY);
        }
        if (!manifest.exactTuple().physicalWorkerId().equals(
                observed.identity().physicalWorkerId())) {
            throw denied(LifecycleActivationReason.WORKER_IDENTITY_MISMATCH);
        }
        if (!"NAVIGATOR_WORKER_LIFECYCLE_V1".equals(
                observed.lifecycleSchema())
                || observed.lifecycleProtocol()
                != manifest.worker().protocolVersion()) {
            throw denied(LifecycleActivationReason.WORKER_PROTOCOL_MISMATCH);
        }
        if (!Objects.equals(manifest.worker().version(),
                observed.workerVersion())) {
            throw denied(LifecycleActivationReason.WORKER_BUILD_MISMATCH);
        }
        if (!observed.capabilities().containsAll(
                manifest.worker().requiredCapabilities())) {
            throw denied(LifecycleActivationReason.WORKER_CAPABILITY_MISMATCH);
        }
    }

    private void applyManifest(
            LifecycleActivationTargetEntity target,
            LifecycleActivationArtifactSource.ActivationArtifacts loaded,
            String generationId,
            String proofId) {
        LifecycleActivationManifest manifest = loaded.manifest();
        target.setTargetId(manifest.targetId());
        target.setRunId(manifest.runId());
        target.setTargetClass(manifest.targetClass());
        target.setProviderEvidenceLane(manifest.providerEvidenceLane());
        target.setProviderType(manifest.exactTuple().providerType());
        target.setTenantId(manifest.exactTuple().tenantId());
        target.setUserId(manifest.exactTuple().userId());
        target.setPhysicalWorkerId(
                manifest.exactTuple().physicalWorkerId());
        target.setModelConfigId(manifest.exactTuple().modelConfigId());
        target.setModel(manifest.exactTuple().model());
        target.setCodexHomeKey(manifest.exactTuple().codexHomeKey());
        target.setPromptSha256(manifest.exactTuple().promptSha256());
        target.setTargetCommit(manifest.candidate().head());
        target.setCandidatePatchSha256(
                manifest.candidate().patchSha256());
        target.setOwnerProtocol(manifest.candidate().ownerProtocol());
        target.setWorkerVersion(manifest.worker().version());
        target.setWorkerProtocol(manifest.worker().protocolVersion());
        target.setRequiredCapabilitiesJson(json(
                manifest.worker().requiredCapabilities()));
        target.setManifestDigest(loaded.manifestDigest());
        target.setControllerInventoryDigest(
                manifest.controllerInventoryDigest());
        target.setGenerationId(generationId);
        target.setWriterInstanceId(properties.getInstanceId());
        target.setProofId(proofId);
    }

    private void requireTargetManifestBinding(
            LifecycleActivationTargetEntity target,
            LifecycleActivationArtifactSource.ActivationArtifacts loaded) {
        LifecycleActivationManifest manifest = loaded.manifest();
        if (!Objects.equals(target.getTargetId(), manifest.targetId())
                || !Objects.equals(target.getRunId(), manifest.runId())
                || !Objects.equals(target.getManifestDigest(),
                loaded.manifestDigest())
                || !Objects.equals(target.getControllerInventoryDigest(),
                manifest.controllerInventoryDigest())
                || !Objects.equals(target.getTargetCommit(),
                manifest.candidate().head())
                || !Objects.equals(target.getCandidatePatchSha256(),
                manifest.candidate().patchSha256())) {
            throw denied(LifecycleActivationReason.MANIFEST_MISMATCH);
        }
    }

    private void requireGenerationBinding(
            LifecycleWriterGenerationEntity generation,
            LifecycleActivationManifest manifest) {
        if (!Objects.equals(generation.getTargetId(), manifest.targetId())
                || !Objects.equals(generation.getRunId(), manifest.runId())
                || !Objects.equals(generation.getTargetCommit(),
                manifest.candidate().head())
                || generation.getMinimumOwnerProtocol()
                != manifest.candidate().ownerProtocol()
                || !Objects.equals(generation.getControllerInventoryDigest(),
                manifest.controllerInventoryDigest())) {
            throw denied(LifecycleActivationReason.CANDIDATE_MISMATCH);
        }
    }

    private void requireInstanceBinding(
            LifecycleWriterInstanceRegistrationEntity instance,
            LifecycleActivationManifest manifest,
            String generationId) {
        if (!Objects.equals(instance.getGenerationId(), generationId)
                || !Objects.equals(instance.getTargetId(), manifest.targetId())
                || !Objects.equals(instance.getRunId(), manifest.runId())
                || !Objects.equals(instance.getTargetCommit(),
                manifest.candidate().head())
                || instance.getOwnerProtocol()
                != manifest.candidate().ownerProtocol()
                || !Objects.equals(instance.getControllerInventoryDigest(),
                manifest.controllerInventoryDigest())) {
            throw denied(LifecycleActivationReason.CANDIDATE_MISMATCH);
        }
    }

    private void requireActiveInstance(
            LifecycleWriterInstanceRegistrationEntity instance,
            LifecycleActivationManifest manifest,
            LocalDateTime now) {
        requireInstanceBinding(instance, manifest,
                stableId("activation-generation", manifest.targetId()));
        if (!Objects.equals(instance.getInstanceId(), required(
                properties.getInstanceId(),
                LifecycleActivationReason.INSTANCE_NOT_REGISTERED))
                || !"REGISTERED".equals(instance.getStatus())
                || instance.getExpiresAt() == null
                || !instance.getExpiresAt().isAfter(now)) {
            throw denied(LifecycleActivationReason.INSTANCE_NOT_REGISTERED);
        }
    }

    private LifecycleActivationTargetEntity requiredTargetForUpdate() {
        return targets.findForUpdate(required(properties.getExactTargetId(),
                        LifecycleActivationReason.TARGET_NOT_CONFIGURED))
                .orElseThrow(() -> denied(
                        LifecycleActivationReason.TARGET_NOT_REGISTERED));
    }

    private ActivationReadiness inspectLocked(
            LifecycleActivationTargetEntity target, LocalDateTime now) {
        boolean generationActive = generations.findById(
                        target.getGenerationId())
                .filter(value -> "ACTIVE".equals(value.getStatus())
                        && ACTIVE_SLOT.equals(value.getActiveSlot()))
                .isPresent();
        boolean instanceActive = instances.findById(
                        target.getWriterInstanceId())
                .filter(value -> "REGISTERED".equals(value.getStatus())
                        && value.getExpiresAt() != null
                        && value.getExpiresAt().isAfter(now))
                .isPresent();
        boolean proofActive = target.getProofId() != null
                && proofs.findById(target.getProofId())
                .filter(value -> "ACTIVE".equals(value.getStatus())
                        && value.getExpiresAt().isAfter(now))
                .isPresent();
        boolean authorityReady = Set.of("READY", "RESERVED")
                .contains(target.getStatus())
                && generationActive && instanceActive && proofActive;
        boolean gateOpen = authorityReady && properties.isAdmissionEnabled();
        String reason = gateOpen
                ? "LIFECYCLE_ACTIVATION_READY_FOR_ONE_BOUNDED_CANARY"
                : authorityReady
                ? LifecycleActivationReason.ADMISSION_DISABLED
                : target.getSafeReasonCode() != null
                ? target.getSafeReasonCode()
                : LifecycleActivationReason.TARGET_NOT_READY;
        return new ActivationReadiness(
                target.getTargetId(), target.getRunId(), target.getStatus(),
                authorityReady, gateOpen, reason,
                target.getManifestDigest(),
                target.getControllerInventoryDigest(),
                target.getGenerationId(), target.getWriterInstanceId(),
                target.getProofId(), generationActive, instanceActive,
                proofActive, target.getLastObservedAt());
    }

    private ActivationReadiness closed(String reason) {
        return new ActivationReadiness(
                properties.getExactTargetId(), null, "CLOSED", false,
                false, reason, null, null, null, null, null,
                false, false, false, null);
    }

    private void requireControlConfiguration(String requestedTargetId) {
        if (!properties.isControlEnabled()) {
            throw denied(LifecycleActivationReason.CONTROL_DISABLED);
        }
        String configured = required(properties.getExactTargetId(),
                LifecycleActivationReason.TARGET_NOT_CONFIGURED);
        if (!configured.equals(requestedTargetId)) {
            throw denied(LifecycleActivationReason.TARGET_MISMATCH);
        }
        required(properties.getInstanceId(),
                LifecycleActivationReason.INSTANCE_NOT_REGISTERED);
        if (properties.getOwnerProtocol() < 1) {
            throw denied(LifecycleActivationReason.CANDIDATE_MISMATCH);
        }
    }

    private Duration validDuration(
            Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw denied(LifecycleActivationReason.MANIFEST_INVALID);
        }
        return value;
    }

    private String controllerDigest(
            List<LifecycleActivationManifest.Controller> controllers) {
        try {
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (LifecycleActivationManifest.Controller controller : controllers) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("kind", controller.kind());
                value.put("id", controller.id());
                value.put("state", controller.state());
                value.put("restartPolicy", controller.restartPolicy());
                value.put("ownershipRunId", controller.ownershipRunId());
                value.put("source", controller.source());
                value.put("artifactCommit", controller.artifactCommit());
                value.put("cwd", controller.cwd());
                normalized.add(value);
            }
            normalized.sort(Comparator
                    .comparing((Map<String, Object> value) ->
                            String.valueOf(value.get("kind")))
                    .thenComparing(value -> String.valueOf(value.get("id"))));
            return sha256(objectMapper.writeValueAsBytes(normalized));
        } catch (JsonProcessingException invalid) {
            throw denied(LifecycleActivationReason.MANIFEST_INVALID);
        }
    }

    private String stableId(String prefix, String value) {
        return prefix + "-" + sha256(value.getBytes(StandardCharsets.UTF_8))
                .substring(0, 48);
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw denied(LifecycleActivationReason.MANIFEST_INVALID);
        }
    }

    private String required(String value, String reason) {
        if (value == null || value.isBlank()) throw denied(reason);
        return value;
    }

    private LifecycleActivationDeniedException denied(String reason) {
        return new LifecycleActivationDeniedException(reason);
    }

    public record ActivationReadiness(
            String targetId,
            String runId,
            String targetStatus,
            boolean authorityReady,
            boolean admissionGateOpen,
            String safeReasonCode,
            String manifestDigest,
            String controllerInventoryDigest,
            String generationId,
            String instanceId,
            String proofId,
            boolean generationActive,
            boolean instanceActive,
            boolean proofActive,
            LocalDateTime lastObservedAt) {
    }

    private record ControllerContract(
            String id, String state, String source) {
    }
}
