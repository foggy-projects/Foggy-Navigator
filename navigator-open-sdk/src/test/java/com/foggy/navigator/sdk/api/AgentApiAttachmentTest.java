package com.foggy.navigator.sdk.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.sdk.NavigatorClient;
import com.foggy.navigator.sdk.model.AgentTask;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentApiAttachmentTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;
    private CapturedRequest captured;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void askWithClientAppAccessTokenIncludesAttachments() throws Exception {
        NavigatorClient client = NavigatorClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .noDefaultAuth()
                .build();

        AgentTask task = client.agents().askWithClientAppAccessToken(
                "agent-1",
                "检查附件",
                "ctx-1",
                3,
                Map.of("source", "observer"),
                "model-1",
                List.of(Map.of(
                        "id", "att-1",
                        "name", "photo.png",
                        "mimeType", "image/png",
                        "url", "http://127.0.0.1:5181/api/v1/observer/attachments/att-1/photo.png"
                )),
                "app-key",
                "runtime-token",
                "tms-user-1");

        assertEquals("task-1", task.getTaskId());
        assertNotNull(captured);
        assertEquals("POST", captured.method());
        assertEquals("/api/v1/open/agents/agent-1/ask", captured.path());
        assertEquals("app-key", captured.headers().getFirst("X-Client-App-Key"));
        assertEquals("runtime-token", captured.headers().getFirst("X-Client-App-Access-Token"));
        assertEquals("tms-user-1", captured.headers().getFirst("X-Upstream-User-Id"));

        Map<String, Object> body = OBJECT_MAPPER.readValue(captured.body(), new TypeReference<>() {});
        assertEquals("检查附件", body.get("question"));
        assertEquals("ctx-1", body.get("contextId"));
        assertEquals("model-1", body.get("modelConfigId"));

        List<?> attachments = (List<?>) body.get("attachments");
        assertEquals(1, attachments.size());
        Map<?, ?> attachment = (Map<?, ?>) attachments.get(0);
        assertEquals("att-1", attachment.get("id"));
        assertEquals("photo.png", attachment.get("name"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        captured = new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        byte[] response = """
                {"code":0,"data":{"taskId":"task-1","agentId":"agent-1","status":"SUBMITTED","contextId":"ctx-1"}}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record CapturedRequest(String method, String path, Headers headers, String body) {
    }
}
