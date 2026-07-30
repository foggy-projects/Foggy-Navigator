package com.foggy.navigator.codex.worker.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CodexWorkerLifecycleHttpAdapterTest {

    @Test
    void probesInventoriesAndAcknowledgesWithExactFence() throws Exception {
        AtomicInteger fencedCalls = new AtomicInteger();
        HttpServer fixture = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/health", exchange -> json(exchange, """
                {"lifecycle_contract":{"ready":true,
                "physical_worker_id":"worker-fixture",
                "state_generation":"generation-fixture",
                "instance_epoch":"epoch-fixture"}}"""));
        fixture.createContext("/api/v1/lifecycle/inventory", exchange -> {
            assertFence(exchange);
            fencedCalls.incrementAndGet();
            json(exchange, """
                    {"physical_worker_id":"worker-fixture",
                    "state_generation":"generation-fixture",
                    "instance_epoch":"epoch-fixture",
                    "min_available_sequence":1,"through_sequence":2,
                    "complete_active_task_set":true,"tasks":[],"facts":[]}""");
        });
        fixture.createContext("/api/v1/lifecycle/ack", exchange -> {
            assertFence(exchange);
            fencedCalls.incrementAndGet();
            json(exchange, "{\"acked_through_sequence\":2}");
        });
        fixture.start();
        try {
            CodexWorkerLifecycleHttpAdapter adapter =
                    new CodexWorkerLifecycleHttpAdapter(
                            "worker-fixture",
                            "http://127.0.0.1:" + fixture.getAddress().getPort(),
                            "fixture-lifecycle-credential",
                            new ObjectMapper());
            WorkerLifecycleIdentity identity =
                    adapter.probe("worker-fixture").identity();
            assertThat(adapter.inventory(identity, 0).throughSequence()).isEqualTo(2);
            assertThat(adapter.acknowledge(identity, 2)).isEqualTo(2);
            assertThat(fencedCalls).hasValue(2);
        } finally {
            fixture.stop(0);
        }
    }

    private void assertFence(HttpExchange exchange) {
        assertThat(exchange.getRequestHeaders()
                .getFirst(CodexWorkerLifecycleHttpAdapter.EXPECTED_WORKER))
                .isEqualTo("worker-fixture");
        assertThat(exchange.getRequestHeaders()
                .getFirst(CodexWorkerLifecycleHttpAdapter.EXPECTED_GENERATION))
                .isEqualTo("generation-fixture");
    }

    private void json(HttpExchange exchange, String body) throws IOException {
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                .isEqualTo("Bearer fixture-lifecycle-credential");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
