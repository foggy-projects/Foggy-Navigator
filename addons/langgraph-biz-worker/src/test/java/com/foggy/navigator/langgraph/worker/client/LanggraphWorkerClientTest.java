package com.foggy.navigator.langgraph.worker.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanggraphWorkerClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void streamQuery_sendsAttachmentsInRequestBody() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            LanggraphWorkerClient client = new LanggraphWorkerClient("worker-1", server.baseUrl(), "token");
            List<Map<String, Object>> attachments = List.of(Map.of(
                    "name", "pod-photo.png",
                    "url", "https://tms.example.com/files/pod-photo.png",
                    "kind", "image"
            ));

            client.streamQuery(
                    "describe",
                    Map.of("source", "test"),
                    Map.of("formId", "tms-1"),
                    "gemini-2.5-pro",
                    "model-config-1",
                    Map.of(
                            "provider", "openai",
                            "base_url", "http://mock-llm",
                            "model", "navigator-e2e-scripted",
                            "api_key", "test-key"
                    ),
                    "task-1",
                    "session-1",
                    "user-1",
                    "tenant-1",
                    attachments
            ).blockFirst(Duration.ofSeconds(5));

            Map<String, Object> body = objectMapper.readValue(server.body(),
                    new TypeReference<>() {});
            assertEquals(attachments, body.get("attachments"));
            @SuppressWarnings("unchecked")
            Map<String, Object> llmConfig = (Map<String, Object>) body.get("llm_config");
            assertEquals("http://mock-llm", llmConfig.get("base_url"));
            assertEquals("navigator-e2e-scripted", llmConfig.get("model"));
        }
    }

    @Test
    void recordInterruption_postsRecoverableFramePayload() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            LanggraphWorkerClient client = new LanggraphWorkerClient("worker-1", server.baseUrl(), "token");

            client.recordInterruption(
                    "lgt_1",
                    "session-1",
                    "ctx-1",
                    "user_cancelled",
                    "Cancelled by user",
                    Map.of("agentId", "tms-root-router-agent")
            ).block(Duration.ofSeconds(5));

            assertEquals("/api/v1/frames/interruption", server.path());
            Map<String, Object> body = objectMapper.readValue(server.body(),
                    new TypeReference<>() {});
            assertEquals("lgt_1", body.get("taskId"));
            assertEquals("session-1", body.get("session_id"));
            assertEquals("ctx-1", body.get("context_id"));
            assertEquals("user_cancelled", body.get("reason"));
            assertEquals("Cancelled by user", body.get("error"));
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>) body.get("context");
            assertEquals("tms-root-router-agent", context.get("agentId"));
        }
    }

    private static class CaptureServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> body = new AtomicReference<>();
        private final AtomicReference<String> path = new AtomicReference<>();

        private CaptureServer(HttpServer server) {
            this.server = server;
        }

        static CaptureServer start() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CaptureServer capture = new CaptureServer(server);
            server.createContext("/api/v1/query", exchange -> {
                capture.path.set(exchange.getRequestURI().getPath());
                capture.body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = "data: {\"type\":\"done\"}\n\n".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.createContext("/api/v1/frames/interruption", exchange -> {
                capture.path.set(exchange.getRequestURI().getPath());
                capture.body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = "{\"status\":\"recorded\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return capture;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String body() {
            return body.get();
        }

        String path() {
            return path.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
