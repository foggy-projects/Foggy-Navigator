package com.foggy.navigator.codex.worker.client;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CodexWorkerClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void streamQuery_sendsImagesAndAttachmentsInRequestBody() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            CodexWorkerClient client = new CodexWorkerClient(server.baseUrl(), "token");
            List<Map<String, Object>> attachments = List.of(Map.of(
                    "name", "pod-photo.png",
                    "url", "https://tms.example.com/files/pod-photo.png",
                    "kind", "image"
            ));

            client.streamQuery(
                    "describe",
                    "D:/repo",
                    "thread-1",
                    "gpt-5.4",
                    1,
                    "[{\"name\":\"screen.png\",\"data\":\"base64\",\"mime_type\":\"image/png\"}]",
                    attachments,
                    null,
                    null,
                    null,
                    "tenant/world-sim/scenario-1/actor-1",
                    "Return valid JSON.",
                    Map.of("type", "object"),
                    Map.of("tool_output_token_limit", 4096),
                    "workspace-write",
                    "never",
                    false,
                    "disabled",
                    Map.of("task_scoped_token", "token-1"),
                    List.of("D:/shared")
            ).blockFirst(Duration.ofSeconds(5));

            Map<String, Object> body = objectMapper.readValue(server.body(),
                    new TypeReference<>() {});
            assertEquals(attachments, body.get("attachments"));
            assertInstanceOf(List.class, body.get("images"));
            assertEquals("tenant/world-sim/scenario-1/actor-1", body.get("codex_home_key"));
            assertEquals("Return valid JSON.", body.get("developer_instructions"));
            assertEquals(Map.of("type", "object"), body.get("output_schema"));
            assertEquals(Map.of("tool_output_token_limit", 4096), body.get("codex_config"));
            assertEquals("workspace-write", body.get("sandbox_mode"));
            assertEquals("never", body.get("approval_policy"));
            assertEquals(false, body.get("network_access_enabled"));
            assertEquals("disabled", body.get("web_search_mode"));
            assertEquals(Map.of("task_scoped_token", "token-1"), body.get("business_runtime_context"));
            assertEquals(List.of("D:/shared"), body.get("additional_directories"));
        }
    }

    @Test
    void streamQuery_omitsCodexBizFieldsWhenNotProvided() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            CodexWorkerClient client = new CodexWorkerClient(server.baseUrl(), "token");

            client.streamQuery(
                    "plain codex",
                    "D:/repo",
                    null,
                    "gpt-5.4",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            ).blockFirst(Duration.ofSeconds(5));

            Map<String, Object> body = objectMapper.readValue(server.body(),
                    new TypeReference<>() {});
            assertEquals("plain codex", body.get("prompt"));
            assertEquals("D:/repo", body.get("cwd"));
            assertEquals("gpt-5.4", body.get("model"));
            assertFalse(body.containsKey("codex_home_key"));
            assertFalse(body.containsKey("developer_instructions"));
            assertFalse(body.containsKey("output_schema"));
            assertFalse(body.containsKey("codex_config"));
            assertFalse(body.containsKey("sandbox_mode"));
            assertFalse(body.containsKey("approval_policy"));
            assertFalse(body.containsKey("network_access_enabled"));
            assertFalse(body.containsKey("web_search_mode"));
            assertFalse(body.containsKey("business_runtime_context"));
            assertFalse(body.containsKey("additional_directories"));
        }
    }

    @Test
    void getSessionFileHints_sendsSessionAndDateQueryParams() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            CodexWorkerClient client = new CodexWorkerClient(server.baseUrl(), "token");

            Map<String, Object> response = client.getSessionFileHints(
                    "thread-1", 7, "2026-06-01", "2026-06-28")
                    .block(Duration.ofSeconds(5));

            assertEquals("thread-1", response.get("session_id"));
            assertEquals("session_id=thread-1&days=7&from=2026-06-01&to=2026-06-28", server.query());
        }
    }

    private static class CaptureServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> body = new AtomicReference<>();
        private final AtomicReference<String> query = new AtomicReference<>();

        private CaptureServer(HttpServer server) {
            this.server = server;
        }

        static CaptureServer start() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CaptureServer capture = new CaptureServer(server);
            server.createContext("/api/v1/query", exchange -> {
                capture.body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = "data: {\"type\":\"done\"}\n\n".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.createContext("/api/v1/session-file-hints", exchange -> {
                capture.query.set(exchange.getRequestURI().getRawQuery());
                byte[] response = "{\"session_id\":\"thread-1\",\"files\":[],\"total\":0}".getBytes(StandardCharsets.UTF_8);
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

        String query() {
            return query.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
