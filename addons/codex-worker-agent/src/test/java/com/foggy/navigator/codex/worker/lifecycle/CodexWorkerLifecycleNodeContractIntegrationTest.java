package com.foggy.navigator.codex.worker.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
