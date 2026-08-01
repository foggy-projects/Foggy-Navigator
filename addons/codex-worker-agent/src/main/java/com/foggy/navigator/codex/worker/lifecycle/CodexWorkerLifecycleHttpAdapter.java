package com.foggy.navigator.codex.worker.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.spi.lifecycle.NormalizedLifecycleFact;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePort;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleReadiness;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleActivationReadiness;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleTask;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleDispatchStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Codex Worker v1 adapter. Credentials and exact identity fences are write-only
 * request material and are never included in results or exceptions.
 */
public final class CodexWorkerLifecycleHttpAdapter implements WorkerLifecyclePort {

    static final String EXPECTED_WORKER = "X-Navigator-Expected-Physical-Worker-Id";
    static final String EXPECTED_GENERATION = "X-Navigator-Expected-State-Generation";
    static final String EXPECTED_MODE = "X-Navigator-Expected-Ownership-Mode";
    static final String EXPECTED_DIGEST_VERSION =
            "X-Navigator-Expected-Safe-Binding-Digest-Version";
    static final String EXPECTED_DIGEST = "X-Navigator-Expected-Safe-Binding-Digest";

    private final String physicalWorkerId;
    private final WebClient client;
    private final ObjectMapper objectMapper;

    public CodexWorkerLifecycleHttpAdapter(
            String physicalWorkerId,
            String baseUrl,
            String lifecycleCredential,
            ObjectMapper objectMapper) {
        this.physicalWorkerId = physicalWorkerId;
        this.objectMapper = objectMapper;
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + lifecycleCredential)
                .build();
    }

    @Override
    public String physicalWorkerId() {
        return physicalWorkerId;
    }

    @Override
    public WorkerLifecycleReadiness probe(String requestedWorkerId) {
        requireWorker(requestedWorkerId);
        try {
            Map<?, ?> health = client.get().uri("/health").retrieve()
                    .bodyToMono(Map.class).block(Duration.ofSeconds(10));
            Map<?, ?> lifecycle = health == null ? null : asMap(health.get("lifecycle_contract"));
            if (lifecycle == null
                    || !Boolean.TRUE.equals(lifecycle.get("ready"))) {
                return new WorkerLifecycleReadiness(
                        false, null, Set.of(), List.of("LIFECYCLE_WORKER_NOT_READY"));
            }
            return new WorkerLifecycleReadiness(
                    true, identity(lifecycle),
                    stringSet(lifecycle.get("capabilities")),
                    List.of());
        } catch (RuntimeException unavailable) {
            return new WorkerLifecycleReadiness(
                    false, null, Set.of(), List.of("LIFECYCLE_WORKER_UNAVAILABLE"));
        }
    }

    @Override
    public WorkerLifecycleActivationReadiness activationReadiness(
            String requestedWorkerId) {
        requireWorker(requestedWorkerId);
        try {
            Map<?, ?> health = client.get().uri("/health").retrieve()
                    .bodyToMono(Map.class).block(Duration.ofSeconds(10));
            Map<?, ?> lifecycle = health == null
                    ? null : asMap(health.get("lifecycle_contract"));
            if (health == null || lifecycle == null) {
                return activationUnavailable(
                        "LIFECYCLE_ACTIVATION_READINESS_UNAVAILABLE");
            }
            boolean lifecycleReady = Boolean.TRUE.equals(
                    lifecycle.get("ready"));
            WorkerLifecycleIdentity observedIdentity = lifecycleReady
                    ? identity(lifecycle) : null;
            boolean lifecycleCredentialAuthenticated = false;
            if (observedIdentity != null) {
                Map<?, ?> inventory = client.get()
                        .uri(uri -> uri.path("/api/v1/lifecycle/inventory")
                                .queryParam("after_sequence", 0).build())
                        .headers(headers -> fence(headers, observedIdentity))
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block(Duration.ofSeconds(10));
                lifecycleCredentialAuthenticated = inventory != null
                        && sameGeneration(
                        observedIdentity, identity(inventory))
                        && Boolean.TRUE.equals(
                        inventory.get("complete_active_task_set"));
            }
            java.util.LinkedHashSet<String> reasons =
                    new java.util.LinkedHashSet<>();
            reasons.addAll(stringList(health.get("reasons")));
            reasons.addAll(stringList(lifecycle.get("reason_codes")));
            reasons.addAll(stringList(health.get("termination_reasons")));
            return new WorkerLifecycleActivationReadiness(
                    Boolean.TRUE.equals(health.get("ready")),
                    lifecycleReady,
                    string(lifecycle.get("schema")),
                    exactInt(lifecycle.get("version")),
                    observedIdentity,
                    stringSet(lifecycle.get("capabilities")),
                    string(health.get("version")),
                    Boolean.TRUE.equals(health.get("termination_ready")),
                    lifecycleCredentialAuthenticated,
                    Boolean.TRUE.equals(health.get("codex_auth_configured")),
                    List.copyOf(reasons));
        } catch (RuntimeException unavailable) {
            return activationUnavailable("LIFECYCLE_WORKER_UNAVAILABLE");
        }
    }

    @Override
    public WorkerLifecycleSnapshot events(
            WorkerLifecycleIdentity expectedIdentity, long afterSequence) {
        requireWorker(expectedIdentity.physicalWorkerId());
        String body = client.get()
                .uri(uri -> uri.path("/api/v1/lifecycle/events")
                        .queryParam("after_sequence", afterSequence).build())
                .headers(headers -> fence(headers, expectedIdentity))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(15));
        if (body == null) throw new IllegalStateException("LIFECYCLE_EVENTS_EMPTY");
        Map<?, ?> checkpoint = null;
        java.util.ArrayList<Map<?, ?>> facts = new java.util.ArrayList<>();
        for (String line : body.split("\\R")) {
            if (!line.startsWith("data:")) continue;
            try {
                Map<?, ?> decoded = objectMapper.readValue(
                        line.substring("data:".length()).trim(), Map.class);
                if (decoded.containsKey("through_sequence")) {
                    checkpoint = decoded;
                } else if (decoded.containsKey("fact_id")) {
                    facts.add(decoded);
                }
            } catch (Exception invalid) {
                throw new IllegalStateException("LIFECYCLE_EVENTS_ENVELOPE_INVALID", invalid);
            }
        }
        if (checkpoint == null) {
            throw new IllegalStateException("LIFECYCLE_EVENTS_CHECKPOINT_MISSING");
        }
        WorkerLifecycleIdentity actual = identity(checkpoint);
        if (!sameGeneration(expectedIdentity, actual)) {
            throw new IllegalStateException("LIFECYCLE_IDENTITY_FENCE_REJECTED");
        }
        return new WorkerLifecycleSnapshot(
                actual,
                number(checkpoint.get("min_available_sequence")),
                number(checkpoint.get("through_sequence")),
                "COMPLETE".equals(checkpoint.get("coverage")),
                List.of(),
                facts(facts, actual, Map.of()));
    }

    @Override
    public WorkerLifecycleSnapshot inventory(
            WorkerLifecycleIdentity expectedIdentity, long afterSequence) {
        requireWorker(expectedIdentity.physicalWorkerId());
        Map<?, ?> body = client.get()
                .uri(uri -> uri.path("/api/v1/lifecycle/inventory")
                        .queryParam("after_sequence", afterSequence).build())
                .headers(headers -> fence(headers, expectedIdentity))
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(15));
        if (body == null) throw new IllegalStateException("LIFECYCLE_INVENTORY_EMPTY");
        WorkerLifecycleIdentity actual = identity(body);
        if (!sameGeneration(expectedIdentity, actual)) {
            throw new IllegalStateException("LIFECYCLE_IDENTITY_FENCE_REJECTED");
        }
        return new WorkerLifecycleSnapshot(
                actual,
                number(body.get("min_available_sequence")),
                number(body.get("through_sequence")),
                Boolean.TRUE.equals(body.get("complete_active_task_set")),
                tasks(body.get("tasks")),
                facts(body.get("facts"), actual,
                        dispositions(body.get("dispatches"))));
    }

    @Override
    public long acknowledge(
            WorkerLifecycleIdentity expectedIdentity, long throughSequence) {
        requireWorker(expectedIdentity.physicalWorkerId());
        Map<?, ?> body = client.put().uri("/api/v1/lifecycle/ack")
                .headers(headers -> fence(headers, expectedIdentity))
                .bodyValue(Map.of(
                        "schema", "NAVIGATOR_WORKER_LIFECYCLE_V1",
                        "physical_worker_id", expectedIdentity.physicalWorkerId(),
                        "state_generation", expectedIdentity.stateGeneration(),
                        "through_sequence", throughSequence))
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(10));
        return body == null ? -1 : number(body.get("acked_through_sequence"));
    }

    @Override
    public WorkerLifecycleDispatchStatus dispatchStatus(
            WorkerLifecycleIdentity expectedIdentity,
            LifecycleOwnershipMode expectedMode,
            String dispatchId,
            String safeBindingDigestVersion,
            String safeBindingDigest) {
        requireWorker(expectedIdentity.physicalWorkerId());
        Map<?, ?> body = client.get()
                .uri("/api/v1/lifecycle/dispatches/{dispatchId}", dispatchId)
                .headers(headers -> {
                    fence(headers, expectedIdentity);
                    headers.set(EXPECTED_MODE, expectedMode.name());
                    headers.set(EXPECTED_DIGEST_VERSION, safeBindingDigestVersion);
                    headers.set(EXPECTED_DIGEST, safeBindingDigest);
                })
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(10));
        if (body == null) {
            throw new IllegalStateException("LIFECYCLE_DISPATCH_STATUS_EMPTY");
        }
        WorkerLifecycleIdentity actual = identity(body);
        if (!sameGeneration(expectedIdentity, actual)
                || expectedMode != LifecycleOwnershipMode.valueOf(
                string(body.get("ownership_mode")))) {
            throw new IllegalStateException("LIFECYCLE_DISPATCH_STATUS_FENCE_REJECTED");
        }
        return new WorkerLifecycleDispatchStatus(
                actual,
                expectedMode,
                string(body.get("navigator_task_id")),
                string(body.get("provider_task_id")),
                string(body.get("dispatch_id")),
                string(body.get("termination_operation_id")),
                string(body.get("safe_binding_digest_version")),
                string(body.get("safe_binding_digest")),
                string(body.get("effect_phase")),
                number(body.get("disposition_version")),
                Boolean.TRUE.equals(body.get("duplicate")),
                Boolean.TRUE.equals(body.get("provider_effect_started")),
                Boolean.TRUE.equals(body.get("reconcile_required")));
    }

    private void fence(HttpHeaders headers, WorkerLifecycleIdentity identity) {
        headers.set(EXPECTED_WORKER, identity.physicalWorkerId());
        headers.set(EXPECTED_GENERATION, identity.stateGeneration());
    }

    private WorkerLifecycleIdentity identity(Map<?, ?> value) {
        if (value == null) throw new IllegalStateException("LIFECYCLE_IDENTITY_MISSING");
        return new WorkerLifecycleIdentity(
                string(value.get("physical_worker_id")),
                string(value.get("state_generation")),
                string(value.get("instance_epoch")));
    }

    private void requireWorker(String requested) {
        if (!physicalWorkerId.equals(requested)) {
            throw new IllegalStateException("LIFECYCLE_PHYSICAL_WORKER_MISMATCH");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private WorkerLifecycleActivationReadiness activationUnavailable(
            String reason) {
        return new WorkerLifecycleActivationReadiness(
                false, false, null, -1, null, Set.of(), null,
                false, false, false, List.of(reason));
    }

    private Set<String> stringSet(Object value) {
        return Set.copyOf(stringList(value));
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(this::string)
                .filter(java.util.Objects::nonNull).toList();
    }

    private int exactInt(Object value) {
        if (!(value instanceof Number number)) return -1;
        long result = number.longValue();
        return result < Integer.MIN_VALUE || result > Integer.MAX_VALUE
                ? -1 : (int) result;
    }

    private List<WorkerLifecycleTask> tasks(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::asMap).filter(java.util.Objects::nonNull)
                .map(task -> new WorkerLifecycleTask(
                        string(task.get("navigator_task_id")),
                        string(task.get("provider_task_id")),
                        LifecycleOwnershipMode.valueOf(string(task.get("ownership_mode"))),
                        string(task.get("initial_dispatch_id")),
                        string(task.get("safe_binding_digest_version")),
                        string(task.get("safe_binding_digest")),
                        string(task.get("lifecycle_state")),
                        number(task.get("last_sequence"))))
                .toList();
    }

    private List<NormalizedLifecycleFact> facts(
            Object value,
            WorkerLifecycleIdentity identity,
            Map<String, Map<?, ?>> dispositions) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::asMap).filter(java.util.Objects::nonNull)
                .map(fact -> {
                    Instant recordedAt = Instant.parse(string(fact.get("recorded_at")));
                    String mode = string(fact.get("ownership_mode"));
                    WorkerLifecycleIdentity sourceIdentity =
                            new WorkerLifecycleIdentity(
                                    string(fact.get("physical_worker_id")),
                                    string(fact.get("state_generation")),
                                    string(fact.get("instance_epoch")));
                    if (!sameGeneration(identity, sourceIdentity)) {
                        throw new IllegalStateException(
                                "LIFECYCLE_FACT_IDENTITY_MISMATCH");
                    }
                    String factDispatchId =
                            string(fact.get("dispatch_id"));
                    Map<?, ?> disposition = factDispatchId == null
                            ? null : dispositions.get(factDispatchId);
                    return new NormalizedLifecycleFact(
                            string(fact.get("fact_id")),
                            string(fact.get("fact_type")),
                            (int) number(fact.get("schema_version")),
                            string(fact.get("aggregate_type")),
                            string(fact.get("aggregate_id")),
                            string(fact.get("session_id")),
                            string(fact.get("navigator_task_id")),
                            string(fact.get("provider_task_id")),
                            string(fact.get("operation_id")),
                            sourceIdentity,
                            mode == null ? LifecycleOwnershipMode.SHADOW
                                    : LifecycleOwnershipMode.valueOf(mode),
                            string(fact.get("dispatch_id")),
                            string(fact.get("safe_binding_digest_version")),
                            string(fact.get("safe_binding_digest")),
                            number(fact.get("source_sequence")),
                            string(fact.get("idempotency_key")),
                            recordedAt,
                            recordedAt,
                            string(fact.get("safe_reason_code")),
                            string(fact.get("terminal_outcome")),
                            disposition == null ? null : string(
                                    disposition.get("acceptance_disposition")),
                            disposition == null ? null : string(
                                    disposition.get("effect_phase")),
                            disposition == null ? null : Boolean.valueOf(
                                    Boolean.TRUE.equals(disposition.get(
                                            "never_accepted_proof"))),
                            disposition == null ? null : number(
                                    disposition.get("disposition_version")));
                }).toList();
    }

    private Map<String, Map<?, ?>> dispositions(Object value) {
        if (!(value instanceof List<?> list)) return Map.of();
        Map<String, Map<?, ?>> result = new java.util.HashMap<>();
        for (Object candidate : list) {
            Map<?, ?> disposition = asMap(candidate);
            if (disposition == null) continue;
            String dispatchId = string(disposition.get("dispatch_id"));
            if (dispatchId != null) result.put(dispatchId, disposition);
        }
        return Map.copyOf(result);
    }

    private boolean sameGeneration(
            WorkerLifecycleIdentity expected,
            WorkerLifecycleIdentity actual) {
        return expected.physicalWorkerId().equals(actual.physicalWorkerId())
                && expected.stateGeneration().equals(actual.stateGeneration())
                && expected.instanceEpoch().equals(actual.instanceEpoch());
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1;
    }

    private String string(Object value) {
        return value instanceof String text && !text.isBlank()
                ? text : null;
    }
}
