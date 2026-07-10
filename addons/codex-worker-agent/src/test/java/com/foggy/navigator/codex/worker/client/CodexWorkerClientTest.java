package com.foggy.navigator.codex.worker.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                    List.of("/home/sa/workspace/shared")
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
            assertEquals(List.of("/home/sa/workspace/shared"), body.get("additional_directories"));
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
    void createTaskUsesNavigatorTaskIdAsIdempotencyKey() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            CodexWorkerClient client = new CodexWorkerClient(server.baseUrl(), "token");

            var acceptance = client.createTask(
                    "navigator-task-1", Map.of("prompt", "change file"))
                    .block(Duration.ofSeconds(5));

            assertEquals("navigator-task-1", server.idempotencyKey());
            assertEquals("worker-task-9", acceptance.getTaskId());
            assertEquals("accepted", acceptance.getStatus());
        }
    }

    @Test
    void appServerClientSendsExactBoundInstanceAndRequiresResponseProof() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            CodexWorkerClient client = new CodexWorkerClient(server.baseUrl(), "token", "instance-a");

            var acceptance = client.createTask(
                    "navigator-task-1", Map.of("prompt", "change file"))
                    .block(Duration.ofSeconds(5));

            assertEquals("worker-task-9", acceptance.getTaskId());
            assertEquals("instance-a", server.expectedInstanceId());
            assertEquals("sync", client.subscribeToTask("worker-task-9", 0)
                    .blockFirst(Duration.ofSeconds(5)).data());

            server.omitInstanceProof();
            CodexWorkerClient.RuntimeInstanceProofException missing = assertThrows(
                    CodexWorkerClient.RuntimeInstanceProofException.class,
                    () -> client.getCapabilities().block(Duration.ofSeconds(5)));
            assertEquals("CODEX_RUNTIME_INSTANCE_PROOF_MISSING", missing.getCode());
        }
    }

    @Test
    void appServerClientFailsClosedWhenSseComesFromAnotherInstance() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            server.actualInstanceId("instance-b");
            CodexWorkerClient client = new CodexWorkerClient(server.baseUrl(), "token", "instance-a");

            CodexWorkerClient.RuntimeInstanceProofException mismatch = assertThrows(
                    CodexWorkerClient.RuntimeInstanceProofException.class,
                    () -> client.subscribeToTask("worker-task-9", 0)
                            .blockFirst(Duration.ofSeconds(5)));

            assertEquals("CODEX_RUNTIME_INSTANCE_PROOF_MISMATCH", mismatch.getCode());
            assertEquals("instance-a", server.expectedInstanceId());
        }
    }

    @Test
    void getCapabilitiesReadsRuntimeManifest() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            CodexWorkerClient client = new CodexWorkerClient(server.baseUrl(), "token");

            Map<String, Object> manifest = client.getCapabilities().block(Duration.ofSeconds(5));

            assertEquals("APP_SERVER", manifest.get("runtime_type"));
            assertEquals("0.144.1", manifest.get("cli_version"));
        }
    }

    @Test
    void abortTaskParsesAcceptedResponseForTerminalReconciliation() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            CodexWorkerClient client = new CodexWorkerClient(server.baseUrl(), "token");

            Map<String, Object> response = client.abortTask("worker-task-9")
                    .block(Duration.ofSeconds(5));

            assertEquals("POST", server.method());
            assertEquals("/api/v1/tasks/worker-task-9/abort", server.path());
            assertEquals("worker-task-9", response.get("task_id"));
            assertEquals("accepted", response.get("status"));
        }
    }

    @Test
    void abortTaskParsesConflictAsPendingReconciliation() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            server.abortReturnsPendingConflict();
            CodexWorkerClient client = new CodexWorkerClient(server.baseUrl(), "token");

            Map<String, Object> response = client.abortTask("worker-task-9")
                    .block(Duration.ofSeconds(5));

            assertEquals("worker-task-9", response.get("task_id"));
            assertEquals("abort_pending", response.get("status"));
        }
    }

    @Test
    void deleteTaskTreatsSuccessAndNotFoundAsIdempotentOutcomes() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            CodexWorkerClient client = new CodexWorkerClient(server.baseUrl(), "token");

            assertTrue(client.deleteTask("worker-task-9").block(Duration.ofSeconds(5)));
            assertEquals("DELETE", server.method());
            assertEquals("/api/v1/tasks/worker-task-9", server.path());

            server.deleteReturns(404);
            assertFalse(client.deleteTask("worker-task-9").block(Duration.ofSeconds(5)));
        }
    }

    @Test
    void deleteTaskRejectsNonSuccessOtherThanNotFound() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            server.deleteReturns(409);
            CodexWorkerClient client = new CodexWorkerClient(server.baseUrl(), "token");

            WebClientResponseException error = assertThrows(WebClientResponseException.class,
                    () -> client.deleteTask("worker-task-9").block(Duration.ofSeconds(5)));
            assertEquals(409, error.getStatusCode().value());
        }
    }

    @Test
    void buildTaskRequestRejectsInvalidImagesInsteadOfDroppingThem() {
        CodexWorkerClient client = new CodexWorkerClient("http://127.0.0.1:1", null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client.buildTaskRequest(
                        "describe", "D:/repo", null, "codex-latest", null,
                        "{not-json", null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null));

        assertEquals(true, error.getMessage().contains("INVALID_CODEX_IMAGES"));
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
        private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
        private final AtomicReference<String> method = new AtomicReference<>();
        private final AtomicReference<String> path = new AtomicReference<>();
        private final AtomicReference<String> expectedInstanceId = new AtomicReference<>();
        private volatile boolean abortPendingConflict;
        private volatile int deleteStatus = 200;
        private volatile String actualInstanceId = "instance-a";

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
            server.createContext("/api/v1/tasks", exchange -> {
                capture.method.set(exchange.getRequestMethod());
                capture.path.set(exchange.getRequestURI().getPath());
                capture.expectedInstanceId.set(exchange.getRequestHeaders().getFirst(
                        CodexWorkerClient.EXPECTED_INSTANCE_HEADER));
                capture.body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                capture.idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                capture.addInstanceProof(exchange);
                if (exchange.getRequestURI().getPath().endsWith("/subscribe")) {
                    byte[] response = "event: message\ndata: sync\n\n".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                    return;
                }
                if ("DELETE".equals(exchange.getRequestMethod())) {
                    byte[] response = (capture.deleteStatus == 404
                            ? "{\"error\":\"TASK_NOT_FOUND\"}"
                            : capture.deleteStatus == 409
                                    ? "{\"error\":\"TASK_NOT_TERMINAL\"}"
                                    : "{\"task_id\":\"worker-task-9\",\"tombstoned\":true}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(capture.deleteStatus, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                    return;
                }
                boolean abort = exchange.getRequestURI().getPath().endsWith("/abort");
                String responseJson = abort && capture.abortPendingConflict
                        ? "{\"task_id\":\"worker-task-9\",\"status\":\"abort_pending\"}"
                        : "{\"task_id\":\"worker-task-9\",\"status\":\"accepted\"}";
                byte[] response = responseJson
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(abort && capture.abortPendingConflict ? 409 : 202, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.createContext("/api/v1/capabilities", exchange -> {
                capture.expectedInstanceId.set(exchange.getRequestHeaders().getFirst(
                        CodexWorkerClient.EXPECTED_INSTANCE_HEADER));
                byte[] response = "{\"runtime_type\":\"APP_SERVER\",\"cli_version\":\"0.144.1\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                capture.addInstanceProof(exchange);
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

        String idempotencyKey() {
            return idempotencyKey.get();
        }

        String method() {
            return method.get();
        }

        String path() {
            return path.get();
        }

        String expectedInstanceId() {
            return expectedInstanceId.get();
        }

        void actualInstanceId(String value) {
            actualInstanceId = value;
        }

        void omitInstanceProof() {
            actualInstanceId = null;
        }

        private void addInstanceProof(com.sun.net.httpserver.HttpExchange exchange) {
            if (actualInstanceId != null) {
                exchange.getResponseHeaders().add(CodexWorkerClient.ACTUAL_INSTANCE_HEADER, actualInstanceId);
            }
        }

        void abortReturnsPendingConflict() {
            abortPendingConflict = true;
        }

        void deleteReturns(int status) {
            deleteStatus = status;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
