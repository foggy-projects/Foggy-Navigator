package com.foggy.navigator.codex.worker.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.common.entity.TerminationOperationEntity;
import com.foggy.navigator.common.termination.TerminationOperationCapability;
import org.springframework.http.codec.ServerSentEvent;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the real Node lifecycle router. This prevents a permissive fake
 * server from hiding method/path/header/envelope drift.
 */
class CodexWorkerLifecycleNodeContractIntegrationTest {

    @Test
    void javaAdapterExecutesNodePutAckInventoryAndEventsContract() throws Exception {
        Path worker = locateWorker();
        Process process = new ProcessBuilder(
                "node", "--import", "tsx",
                "tests/fixtures/lifecycle-router-server.ts")
                .directory(worker.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            BufferedReader output = process.inputReader();
            String line = output.readLine();
            assertThat(line).as("fixture startup envelope").isNotBlank();
            Map<?, ?> started = new ObjectMapper().readValue(line, Map.class);
            CodexWorkerLifecycleHttpAdapter adapter =
                    new CodexWorkerLifecycleHttpAdapter(
                            text(started, "workerId"),
                            text(started, "baseUrl"),
                            "arch001-java-node-fixture-token",
                            new ObjectMapper());
            WorkerLifecycleIdentity identity = new WorkerLifecycleIdentity(
                    text(started, "workerId"),
                    text(started, "stateGeneration"),
                    text(started, "instanceEpoch"));

            assertThat(adapter.probe(identity.physicalWorkerId()).ready()).isTrue();
            assertThat(adapter.inventory(identity, 0).facts()).hasSize(1);
            assertThat(adapter.events(identity, 0).facts()).hasSize(1);
            assertThat(adapter.acknowledge(identity, 1)).isEqualTo(1);
            assertThat(adapter.acknowledge(identity, 0)).isEqualTo(1);
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void exactCodexBizUsesProductionClientAndRealNodeRouterForCreateResumeAbort()
            throws Exception {
        Path worker = locateWorker();
        Process process = startFixture(worker);
        try {
            Map<?, ?> started = startup(process);
            String workerId = text(started, "workerId");
            String baseUrl = text(started, "baseUrl");
            String token = "arch001-java-node-fixture-token";
            ObjectMapper mapper = new ObjectMapper();
            CodexWorkerClient client =
                    new CodexWorkerClient(baseUrl, token);
            WorkerLifecycleIdentity identity = identity(started);
            CodexWorkerLifecycleHttpAdapter adapter =
                    new CodexWorkerLifecycleHttpAdapter(
                            workerId, baseUrl, token, mapper);

            String createDispatch = "arch001-create-dispatch";
            Map<String, Object> createContext = client.lifecycleContext(
                            workerId, "ENFORCED", "TASK_CREATE",
                            "arch001-create", createDispatch, 1, null)
                    .block(Duration.ofSeconds(10));
            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("prompt", "ARCH001_COMPLETE_CREATE");
            createBody.put("cwd", worker.toString());
            createBody.put("model", "gpt-5.6-sol");
            createBody.put("lifecycle_context", createContext);
            List<ServerSentEvent<String>> createEvents =
                    client.streamQuery(createBody)
                            .collectList().block(Duration.ofSeconds(15));
            Map<String, Object> createDisposition =
                    disposition(createEvents, mapper);
            String createProviderTask =
                    String.valueOf(createDisposition.get("provider_task_id"));
            assertThat(createDisposition.get("effect_phase"))
                    .isEqualTo("EFFECT_STARTED");
            assertThat(createDisposition.get("disposition_version"))
                    .isEqualTo(2);
            assertThat(adapter.dispatchStatus(
                    identity,
                    com.foggy.navigator.spi.lifecycle
                            .LifecycleOwnershipMode.ENFORCED,
                    createDispatch, "JCS_SHA256_V1",
                    String.valueOf(createDisposition.get(
                            "safe_binding_digest"))).effectPhase())
                    .isEqualTo("RESULT_OBSERVED");
            String threadId = createEvents.stream()
                    .filter(event -> "message".equals(event.event()))
                    .map(ServerSentEvent::data)
                    .map(data -> read(mapper, data))
                    .map(event -> event.get("session_id"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .findFirst().orElseThrow();

            String resumeDispatch = "arch001-resume-dispatch";
            Map<String, Object> resumeContext = client.lifecycleContext(
                            workerId, "ENFORCED", "TASK_RESUME",
                            "arch001-resume", resumeDispatch, 1, null)
                    .block(Duration.ofSeconds(10));
            Map<String, Object> resumeBody = new LinkedHashMap<>();
            resumeBody.put("prompt", "ARCH001_HOLD_FOR_ABORT");
            resumeBody.put("cwd", worker.toString());
            resumeBody.put("session_id", threadId);
            resumeBody.put("model", "gpt-5.6-sol");
            resumeBody.put("lifecycle_context", resumeContext);
            ServerSentEvent<String> resumeLifecycle =
                    client.streamQuery(resumeBody)
                            .filter(event -> "lifecycle_disposition".equals(
                                    event.event()))
                            .next().block(Duration.ofSeconds(15));
            Map<String, Object> resumeDisposition =
                    read(mapper, resumeLifecycle.data());
            String providerTaskId = String.valueOf(
                    resumeDisposition.get("provider_task_id"));
            assertThat(providerTaskId).isNotEqualTo(createProviderTask);
            assertThat(resumeDisposition.get("effect_phase"))
                    .isEqualTo("EFFECT_STARTED");
            Map<String, Object> running = null;
            for (int attempt = 0; attempt < 150; attempt++) {
                running = client.getTaskStatus(providerTaskId)
                        .block(Duration.ofSeconds(10));
                if (running != null && running.get("pid") instanceof Number) {
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(running).isNotNull();
            assertThat(running.get("status")).isEqualTo("running");
            assertThat(running.get("pid"))
                    .as("real fixture provider process binding")
                    .isInstanceOf(Number.class);

            String operationId = "rt_arch001_real_node_abort";
            String abortDispatch = "arch001-abort-dispatch";
            TerminationOperationEntity operation =
                    operation(operationId, providerTaskId, workerId);
            TerminationOperationCapability capability =
                    TerminationOperationCapability.issueStable(
                            operation, token);
            Map<String, Object> abortContext = client.lifecycleContext(
                            workerId, "ENFORCED", "TERMINATION_CANCEL",
                            "arch001-resume", abortDispatch, 1, operationId)
                    .block(Duration.ofSeconds(10));
            String digest = new CodexLifecycleBindingDigest(mapper)
                    .termination(abortContext, providerTaskId, capability);
            Map<String, Object> acknowledgement = client.abortTask(
                            providerTaskId, capability, abortContext)
                    .block(Duration.ofSeconds(10));
            assertThat(acknowledgement.get("status"))
                    .isIn("cancel_requested", "aborted", "already_terminal");

            com.foggy.navigator.spi.lifecycle.WorkerLifecycleDispatchStatus
                    abortStatus = null;
            for (int attempt = 0; attempt < 300; attempt++) {
                abortStatus = adapter.dispatchStatus(
                        identity,
                        com.foggy.navigator.spi.lifecycle
                                .LifecycleOwnershipMode.ENFORCED,
                        abortDispatch, "JCS_SHA256_V1", digest);
                if ("RESULT_OBSERVED".equals(abortStatus.effectPhase())) {
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(abortStatus.effectPhase())
                    .isEqualTo("RESULT_OBSERVED");
            assertThat(adapter.inventory(identity, 0).facts())
                    .anyMatch(fact ->
                            "TASK_PROVIDER_TERMINAL_OBSERVED".equals(
                                    fact.factType())
                            && abortDispatch.equals(fact.dispatchId())
                            && "CANCELLED".equals(fact.terminalOutcome()));
        } finally {
            stop(process);
        }
    }

    private static Process startFixture(Path worker) throws Exception {
        return new ProcessBuilder(
                "node", "--import", "tsx",
                "tests/fixtures/lifecycle-router-server.ts")
                .directory(worker.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private static Map<?, ?> startup(Process process) throws Exception {
        String line = process.inputReader().readLine();
        assertThat(line).as("fixture startup envelope").isNotBlank();
        return new ObjectMapper().readValue(line, Map.class);
    }

    private static WorkerLifecycleIdentity identity(Map<?, ?> started) {
        return new WorkerLifecycleIdentity(
                text(started, "workerId"),
                text(started, "stateGeneration"),
                text(started, "instanceEpoch"));
    }

    private static void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> disposition(
            List<ServerSentEvent<String>> events,
            ObjectMapper mapper) {
        return events.stream()
                .filter(event -> "lifecycle_disposition".equals(
                        event.event()))
                .map(ServerSentEvent::data)
                .map(data -> read(mapper, data))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(
            ObjectMapper mapper, String value) {
        try {
            return mapper.readValue(value, Map.class);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static TerminationOperationEntity operation(
            String operationId,
            String providerTaskId,
            String workerId) {
        LocalDateTime now = LocalDateTime.now();
        TerminationOperationEntity operation =
                new TerminationOperationEntity();
        operation.setOperationId(operationId);
        operation.setSchemaVersion(1);
        operation.setTaskId("arch001-resume");
        operation.setProviderTaskId(providerTaskId);
        operation.setSessionId("arch001-session");
        operation.setOwnerUserId("arch001-owner");
        operation.setProviderType("codex-biz-worker");
        operation.setWorkerId(workerId);
        operation.setKind("REMOTE_CANCEL");
        operation.setOrigin("UPSTREAM_USER");
        operation.setActorId("arch001-owner");
        operation.setActorType("RUNTIME_CLIENT");
        operation.setAuthorizationDecisionId("arch001-authorized");
        operation.setReasonCode("operator-stuck-task-termination");
        operation.setCorrelationId("arch001-fixture");
        operation.setRequestedAt(now);
        operation.setExpiresAt(now.plusMinutes(5));
        return operation;
    }

    private static Path locateWorker() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve("tools/codex-agent-worker");
            if (Files.isRegularFile(candidate.resolve("package.json"))) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("CODEX_WORKER_FIXTURE_NOT_FOUND");
    }

    private static String text(Map<?, ?> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("FIXTURE_FIELD_MISSING_" + key);
        }
        return text;
    }
}
