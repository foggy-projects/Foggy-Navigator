package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofReferenceEntity;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repo-owned Slice 8 fixture using production JPA entities, proof/reference/
 * outbox authorization, enrollment gate, and the real Node Worker router.
 */
@SpringJUnitConfig(classes = {
        TaskLifecycleOwnerVerticalIntegrationTest.Config.class,
        WriterExclusivityProofService.class
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

            persistFence();
            LifecycleEnrollmentGate.EnrollmentDecision enrollment =
                    new LifecycleEnrollmentGate().evaluate(request(true));
            assertThat(enrollment.enrolled()).isTrue();

            var authorization = writer.authorizeEffect(command(), NOW);
            assertThat(authorization.providerCallAuthorized()).isTrue();
            writer.quarantine("proof-slice8");
            assertThat(writer.authorizeEffect(command(), NOW.plusSeconds(1))
                    .alreadyStarted()).isTrue();
            assertThat(outbox.findById("effect-slice8").orElseThrow()
                    .getEffectState()).isEqualTo("EFFECT_STARTED");
            assertThat(references
                    .countByProofIdAndReleasedAtIsNull("proof-slice8"))
                    .isEqualTo(1);
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
                .isEqualTo(LifecycleSchemaReadiness.ACTIVATION_DISABLED);
    }

    private void persistFence() {
        outbox.deleteAll();
        references.deleteAll();
        proofs.deleteAll();
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

        String referenceId = writer.acquireReference(
                "proof-slice8", ProofAggregateType.TASK,
                "task-slice8", NOW);

        LifecycleEffectOutboxEntity effect = new LifecycleEffectOutboxEntity();
        effect.setEffectId("effect-slice8");
        effect.setAggregateType("TASK");
        effect.setAggregateId("task-slice8");
        effect.setEffectType("TASK_CREATE");
        effect.setEffectClass("EXTERNAL_PROVIDER_ONCE");
        effect.setEffectState("CLAIMED");
        effect.setIdempotencyKey("slice8-effect");
        effect.setAggregateReferenceId(referenceId);
        effect.setWriterGenerationId("generation-slice8");
        effect.setControllerInventoryDigest("inventory-slice8");
        effect.setEffectClaim("TASK_CREATE_PROVIDER_CALL");
        effect.setContentFreePayloadJson("{}");
        outbox.saveAndFlush(effect);
    }

    private WriterExclusivityProofService.EffectAuthorizationCommand command() {
        return new WriterExclusivityProofService.EffectAuthorizationCommand(
                "effect-slice8", "proof-slice8",
                "proof-slice8:TASK:task-slice8",
                ProofAggregateType.TASK, "task-slice8", "generation-slice8",
                "inventory-slice8", "TASK_CREATE_PROVIDER_CALL");
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
}
