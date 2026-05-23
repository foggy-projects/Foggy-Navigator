package com.foggy.navigator.observer.bff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObserverBffContextContinuityTest {

    private static final FakeNavigatorServer NAVIGATOR = FakeNavigatorServer.start();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("navigator.observer.navigator-base-url", NAVIGATOR::baseUrl);
        registry.add("navigator.observer.client-app-key", () -> "cak-real-bff");
        registry.add("navigator.observer.client-app-access-token", () -> "cat-real-bff");
        registry.add("navigator.observer.upstream-user-id", () -> "tms-user-1");
        registry.add("navigator.observer.agent-id", () -> "tms.navigator.agent");
    }

    @AfterAll
    static void stopServer() {
        NAVIGATOR.stop();
    }

    @Test
    void askForwardsReturnedContextIdOnNextTurn() throws Exception {
        NAVIGATOR.clear();

        Map<?, ?> first = post("/api/v1/open/agents/tms.navigator.agent/ask", Map.of(
                "question", "查询运单 123",
                "maxTurns", 3));
        assertEquals("ctx-real-bff", data(first).get("contextId").asText());

        Map<?, ?> second = post("/api/v1/open/agents/tms.navigator.agent/ask", Map.of(
                "question", "继续查看路由信息",
                "contextId", data(first).get("contextId").asText(),
                "maxTurns", 3));
        assertEquals("ctx-real-bff", data(second).get("contextId").asText());

        List<CapturedRequest> asks = NAVIGATOR.requests("/api/v1/open/agents/tms.navigator.agent/ask");
        assertEquals(2, asks.size());
        JsonNode secondBody = MAPPER.readTree(asks.get(1).body());
        assertEquals("继续查看路由信息", secondBody.get("message").asText());
        assertEquals("ctx-real-bff", secondBody.get("contextId").asText());
        assertEquals("cak-real-bff", asks.get(1).header("X-Client-App-Key"));
        assertEquals("cat-real-bff", asks.get(1).header("X-Client-App-Access-Token"));
        assertEquals("tms-user-1", asks.get(1).header("X-Upstream-User-Id"));
    }

    @Test
    void runtimeSessionHistoryUsesBusinessAgentOpenApiEndpoints() {
        NAVIGATOR.clear();

        restTemplate.getForEntity(baseUrl() + "/api/v1/open/agents/tms.navigator.agent/sessions?limit=10", Map.class);
        restTemplate.getForEntity(baseUrl() + "/api/v1/open/agents/tms.navigator.agent/sessions/ctx-real-bff/messages?limit=5", Map.class);

        assertEquals(1, NAVIGATOR.requests("/api/v1/open/business-agent/sessions").size());
        assertEquals(1, NAVIGATOR.requests("/api/v1/open/business-agent/sessions/ctx-real-bff/messages").size());
        assertTrue(NAVIGATOR.requests("/api/v1/open/agents/tms.navigator.agent/sessions").isEmpty());
    }

    private Map<?, ?> post(String path, Map<String, Object> body) {
        return restTemplate.postForObject(baseUrl() + path, body, Map.class);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private JsonNode data(Map<?, ?> response) {
        return MAPPER.valueToTree(response.get("data"));
    }

    private record CapturedRequest(String method, String path, String query, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }
    }

    private static final class FakeNavigatorServer {
        private final HttpServer server;
        private final AtomicInteger askCount = new AtomicInteger();
        private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

        private FakeNavigatorServer(HttpServer server) {
            this.server = server;
        }

        static FakeNavigatorServer start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                FakeNavigatorServer fake = new FakeNavigatorServer(server);
                server.createContext("/", fake::handle);
                server.start();
                return fake;
            } catch (IOException exception) {
                throw new IllegalStateException("failed to start fake Navigator server", exception);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        void clear() {
            askCount.set(0);
            requests.clear();
        }

        List<CapturedRequest> requests(String path) {
            return requests.stream()
                    .filter(request -> request.path().equals(path))
                    .toList();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(),
                    new ArrayListBackedHeaders(exchange.getRequestHeaders()),
                    body));

            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/v1/open/agents/tms.navigator.agent/ask")) {
                int seq = askCount.incrementAndGet();
                respond(exchange, 200, """
                        {"code":0,"data":{"taskId":"task-real-bff-%d","agentId":"tms.navigator.agent","status":"COMPLETED","contextId":"ctx-real-bff","result":"ok","terminal":true,"terminalStatus":"COMPLETED"}}
                        """.formatted(seq));
                return;
            }
            if (path.equals("/api/v1/open/business-agent/sessions")) {
                respond(exchange, 200, """
                        {"code":0,"data":{"sessions":[{"contextId":"ctx-real-bff","status":"ACTIVE","latestTaskId":"task-real-bff-1"}],"hasMore":false}}
                        """);
                return;
            }
            if (path.equals("/api/v1/open/business-agent/sessions/ctx-real-bff/messages")) {
                respond(exchange, 200, """
                        {"code":0,"data":{"contextId":"ctx-real-bff","messages":[{"messageId":"m-1","contextId":"ctx-real-bff","role":"ASSISTANT","type":"RESULT","content":"历史回答"}],"hasMore":false}}
                        """);
                return;
            }
            respond(exchange, 404, "{\"code\":404,\"msg\":\"not found\"}");
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private static final class ArrayListBackedHeaders extends java.util.LinkedHashMap<String, List<String>> {
        ArrayListBackedHeaders(Map<String, List<String>> source) {
            source.forEach((key, value) -> put(key, new ArrayList<>(value)));
        }
    }
}
