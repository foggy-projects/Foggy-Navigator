package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofReferenceRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repo-owned Slice 8 fixture using production JPA entities, proof/reference/
 * outbox authorization, enrollment gate, and the real Node Worker router.
 */
@SpringJUnitConfig(classes = {
        TaskLifecycleOwnerVerticalIntegrationTest.Config.class,
        WriterExclusivityProofService.class,
        LifecycleEnrollmentService.class,
        WorkerLifecycleReconciliationCommitService.class
})
class IsolatedEnforcedLifecycleContractTest {
    private static final Set<String> CAPABILITIES = Set.of(
            "AUTHENTICATED_LIFECYCLE_V1",
            "FENCED_INVENTORY_V1",
            "DURABLE_LIFECYCLE_FACTS_V1",
            "MONOTONIC_ACK_V1",
            "EXACT_DISPATCH_DEDUPE_V1",
            "DURABLE_PROVIDER_TASK_ID_V1",
            "TERMINATION_ATOMIC_CAPABILITY_V1");
    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-07-31T12:00:00");

    @org.springframework.beans.factory.annotation.Autowired
    WriterExclusivityProofService writer;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleEnrollmentService enrollment;
    @org.springframework.beans.factory.annotation.Autowired
    WorkerLifecycleReconciliationCommitService reconciliationCommit;
    @org.springframework.beans.factory.annotation.Autowired
    com.foggy.navigator.common.repository.SessionTaskRepository canonicalTasks;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleWriterProofRepository proofs;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleWriterProofReferenceRepository references;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleEffectOutboxRepository outbox;

    @Test
    void enforcedFixtureUsesWorkerRouteAndProductionFencingChain()
            throws Exception {
        Process worker = startWorkerFixture();
        try {
            Map<?, ?> started = new ObjectMapper()
                    .readValue(worker.inputReader().readLine(), Map.class);
            String baseUrl = (String) started.get("baseUrl");
            String generation = (String) started.get("stateGeneration");
            HttpResponse<String> inventory = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    baseUrl
                                            + "/api/v1/lifecycle/inventory?after_sequence=0"))
                            .header("Authorization",
                                    "Bearer arch001-java-node-fixture-token")
                            .header("X-Navigator-Expected-Physical-Worker-Id",
                                    "arch001-java-node-worker")
                            .header("X-Navigator-Expected-State-Generation",
                                    generation)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(inventory.statusCode()).isEqualTo(200);
            assertThat(inventory.body()).contains(
                    "\"schema\":\"NAVIGATOR_WORKER_LIFECYCLE_V1\"");

            HttpResponse<String> queryRoute = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    baseUrl + "/api/v1/query"))
                            .header("Authorization",
                                    "Bearer arch001-java-node-fixture-token")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"lifecycle_context":{
                                      "schema":"NAVIGATOR_WORKER_LIFECYCLE_V1",
                                      "ownership_mode":"ENFORCED",
                                      "command_kind":"TASK_CREATE",
                                      "navigator_task_id":"fixture-query",
                                      "dispatch_id":"fixture-query-dispatch",
                                      "delivery_attempt":1,
                                      "expected_physical_worker_id":"arch001-java-node-worker",
                                      "expected_state_generation":"%s",
                                      "termination_operation_id":null}}
                                    """.formatted(generation)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(queryRoute.statusCode()).isEqualTo(400);

            String operation = java.util.Base64.getUrlEncoder()
                    .withoutPadding().encodeToString(
                            "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            HttpResponse<String> abortRoute = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(baseUrl
                                    + "/api/v1/tasks/provider-task-missing/abort"))
                            .header("Authorization",
                                    "Bearer arch001-java-node-fixture-token")
                            .header("Content-Type", "application/json")
                            .header("X-Navigator-Termination-Operation",
                                    operation)
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"lifecycle_context":{
                                      "schema":"NAVIGATOR_WORKER_LIFECYCLE_V1",
                                      "ownership_mode":"ENFORCED",
                                      "command_kind":"TERMINATION_CANCEL",
                                      "navigator_task_id":"fixture-query",
                                      "dispatch_id":"fixture-abort-dispatch",
                                      "delivery_attempt":1,
                                      "expected_physical_worker_id":"arch001-java-node-worker",
                                      "expected_state_generation":"%s",
                                      "termination_operation_id":"fixture-operation"}}
                                    """.formatted(generation)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(abortRoute.statusCode()).isEqualTo(404);
            assertThat(abortRoute.body()).contains("Task not found");

            HttpResponse<String> dispatchStatus =
                    HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create(baseUrl
                                            + "/api/v1/lifecycle/dispatches/"
                                            + "fixture-query-dispatch"))
                                    .header("Authorization",
                                            "Bearer arch001-java-node-fixture-token")
                                    .header(
                                            "X-Navigator-Expected-Physical-Worker-Id",
                                            "arch001-java-node-worker")
                                    .header(
                                            "X-Navigator-Expected-State-Generation",
                                            generation)
                                    .header(
                                            "X-Navigator-Expected-Ownership-Mode",
                                            "ENFORCED")
                                    .header(
                                            "X-Navigator-Expected-Safe-Binding-Digest-Version",
                                            "JCS_SHA256_V1")
                                    .header(
                                            "X-Navigator-Expected-Safe-Binding-Digest",
                                            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(dispatchStatus.statusCode()).isEqualTo(404);

            var port = new HttpLifecyclePort(baseUrl);
            var sentinel = new WorkerLifecycleSentinel(
                    "fixture-sentinel",
                    (workerId, holderId, now, duration) ->
                            Optional.of(new SentinelLease(
                                    workerId, holderId, 1)),
                    Clock.systemUTC(), Duration.ofSeconds(30));
            var reconcile = sentinel.reconcile(
                    "arch001-java-node-worker",
                    SentinelTrigger.TIMER,
                    port,
                    snapshot -> reconciliationCommit.commit(
                            snapshot, null));
            assertThat(reconcile.state())
                    .isEqualTo(SentinelReconcileState.READY);
            assertThat(reconcile.throughSequence()).isEqualTo(1);
            var identity = reconcile.identity();
            persistCanonicalTaskAndProof();
            LifecycleEnrollmentGate.EnrollmentDecision decision =
                    enrollment.enroll(
                            new LifecycleEnrollmentService.EnrollmentCommand(
                                    request(true), identity, "session-slice8",
                                    new com.foggy.navigator.spi.lifecycle
                                            .WorkerLifecycleTask(
                                            "task-slice8",
                                            "provider-task-slice8",
                                            com.foggy.navigator.spi.lifecycle
                                                    .LifecycleOwnershipMode.SHADOW,
                                            "dispatch-slice8",
                                            "JCS_SHA256_V1",
                                            "binding-slice8",
                                            "RUNNING", 1),
                                    "proof-slice8",
                                    "generation-slice8"));
            assertThat(decision.enrolled()).isTrue();
            assertThat(references
                    .countByProofIdAndReleasedAtIsNull("proof-slice8"))
                    .isEqualTo(3);
            assertThat(writer.mayReleaseProof("proof-slice8")).isFalse();
        } finally {
            worker.destroy();
            if (!worker.waitFor(10, TimeUnit.SECONDS)) worker.destroyForcibly();
        }
    }

    @Test
    void nonFixtureEnrollmentRemainsDisabledWithoutRealActivationEvidence() {
        LifecycleEnrollmentGate.EnrollmentRequest request =
                new LifecycleEnrollmentGate.EnrollmentRequest(
                        "codex-biz-worker", true, false, false, true, true, true,
                        true, true, true, CAPABILITIES, true,
                        NOW.plusMinutes(1), NOW);
        assertThat(new LifecycleEnrollmentGate().evaluate(request).safeReasonCode())
                .isEqualTo(LifecycleActivationReason.AUTHORITY_REQUIRED);
    }

    private void persistCanonicalTaskAndProof() {
        outbox.deleteAll();
        references.deleteAll();
        proofs.deleteAll();
        canonicalTasks.deleteAll();
        var task = new com.foggy.navigator.common.entity.SessionTaskEntity();
        task.setTaskId("task-slice8");
        task.setSessionId("session-slice8");
        task.setProviderType("codex-biz-worker");
        task.setProviderTaskId("provider-task-slice8");
        task.setWorkerId("arch001-java-node-worker");
        task.setUserId("fixture-user");
        task.setTenantId("fixture-tenant");
        task.setStatus("RUNNING");
        canonicalTasks.saveAndFlush(task);

        LifecycleWriterProofEntity proof = new LifecycleWriterProofEntity();
        proof.setProofId("proof-slice8");
        proof.setGenerationId("generation-slice8");
        proof.setControllerInventoryDigest("inventory-slice8");
        proof.setHolderInstanceId("instance-slice8");
        proof.setProofVersion(1);
        proof.setStatus("ACTIVE");
        proof.setAcquiredAt(NOW);
        proof.setLastVerifiedAt(NOW);
        proof.setExpiresAt(NOW.plusMinutes(5));
        proofs.saveAndFlush(proof);
    }

    private LifecycleEnrollmentGate.EnrollmentRequest request(boolean proofActive) {
        return new LifecycleEnrollmentGate.EnrollmentRequest(
                "codex-biz-worker", true, true, false, true, true, true,
                true, true, true, CAPABILITIES, proofActive,
                NOW.plusMinutes(2), NOW);
    }

    private Process startWorkerFixture() throws Exception {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null
                && !Files.isRegularFile(cursor.resolve(
                "tools/codex-agent-worker/package.json"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) throw new IllegalStateException("WORKER_FIXTURE_NOT_FOUND");
        return new ProcessBuilder(
                "node", "--import", "tsx",
                "tests/fixtures/lifecycle-router-server.ts")
                .directory(cursor.resolve("tools/codex-agent-worker").toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private static final class HttpLifecyclePort
            implements com.foggy.navigator.spi.lifecycle.WorkerLifecyclePort {
        private final String baseUrl;
        private final HttpClient client = HttpClient.newHttpClient();
        private final ObjectMapper mapper =
                new ObjectMapper().findAndRegisterModules();

        private HttpLifecyclePort(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        @Override
        public com.foggy.navigator.spi.lifecycle.WorkerLifecycleReadiness probe(
                String physicalWorkerId) {
            try {
                Map<?, ?> body = mapper.readValue(client.send(
                        HttpRequest.newBuilder(URI.create(
                                        baseUrl + "/health"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body(), Map.class);
                Map<?, ?> contract = (Map<?, ?>) body.get(
                        "lifecycle_contract");
                var identity = new com.foggy.navigator.spi.lifecycle
                        .WorkerLifecycleIdentity(
                        (String) contract.get("physical_worker_id"),
                        (String) contract.get("state_generation"),
                        (String) contract.get("instance_epoch"));
                return new com.foggy.navigator.spi.lifecycle
                        .WorkerLifecycleReadiness(
                        true, identity, CAPABILITIES, List.of());
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot inventory(
                com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity expected,
                long afterSequence) {
            try {
                HttpResponse<String> response = client.send(
                        fenced(HttpRequest.newBuilder(URI.create(
                                baseUrl
                                        + "/api/v1/lifecycle/inventory"
                                        + "?after_sequence="
                                        + afterSequence)), expected)
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(
                            "LIFECYCLE_INVENTORY_REJECTED");
                }
                Map<?, ?> body = mapper.readValue(
                        response.body(), Map.class);
                return snapshot(body);
            } catch (Exception error) {
                throw error instanceof IllegalStateException state
                        ? state : new IllegalStateException(error);
            }
        }

        @Override
        public com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot events(
                com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity expected,
                long afterSequence) {
            try {
                HttpResponse<String> response = client.send(
                        fenced(HttpRequest.newBuilder(URI.create(
                                baseUrl + "/api/v1/lifecycle/events"
                                        + "?after_sequence="
                                        + afterSequence)), expected)
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200
                        || !response.body().contains(
                        "event: sync_checkpoint")) {
                    throw new IllegalStateException(
                            "LIFECYCLE_EVENTS_REJECTED");
                }
                return inventory(expected, afterSequence);
            } catch (Exception error) {
                throw error instanceof IllegalStateException state
                        ? state : new IllegalStateException(error);
            }
        }

        @Override
        public long acknowledge(
                com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity expected,
                long throughSequence) {
            try {
                String body = """
                        {"schema":"NAVIGATOR_WORKER_LIFECYCLE_V1",
                         "physical_worker_id":"%s",
                         "state_generation":"%s",
                         "through_sequence":%d}
                        """.formatted(
                        expected.physicalWorkerId(),
                        expected.stateGeneration(), throughSequence);
                HttpResponse<String> response = client.send(
                        fenced(HttpRequest.newBuilder(URI.create(
                                baseUrl + "/api/v1/lifecycle/ack")),
                                expected)
                                .header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(
                            "LIFECYCLE_ACK_REJECTED");
                }
                Map<?, ?> result = mapper.readValue(
                        response.body(), Map.class);
                return ((Number) result.get(
                        "acked_through_sequence")).longValue();
            } catch (Exception error) {
                throw error instanceof IllegalStateException state
                        ? state : new IllegalStateException(error);
            }
        }

        private HttpRequest.Builder fenced(
                HttpRequest.Builder request,
                com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity expected) {
            return request
                    .header("Authorization",
                            "Bearer arch001-java-node-fixture-token")
                    .header(
                            "X-Navigator-Expected-Physical-Worker-Id",
                            expected.physicalWorkerId())
                    .header(
                            "X-Navigator-Expected-State-Generation",
                            expected.stateGeneration());
        }

        private com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot snapshot(
                Map<?, ?> body) {
            var identity = new com.foggy.navigator.spi.lifecycle
                    .WorkerLifecycleIdentity(
                    (String) body.get("physical_worker_id"),
                    (String) body.get("state_generation"),
                    (String) body.get("instance_epoch"));
            return new com.foggy.navigator.spi.lifecycle
                    .WorkerLifecycleSnapshot(
                    identity,
                    ((Number) body.get(
                            "min_available_sequence")).longValue(),
                    ((Number) body.get(
                            "through_sequence")).longValue(),
                    Boolean.TRUE.equals(body.get(
                            "complete_active_task_set")),
                    List.of(), List.of());
        }
    }
}
