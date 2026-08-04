package com.foggy.navigator.sdk.api;

import com.foggy.navigator.sdk.NavigatorClient;
import com.foggy.navigator.sdk.exception.NavigatorApiException;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentApiCancellationTest {

    private static final String REQUEST_ID =
            "a4af5a56-c7c9-4c59-861d-19d7670b2254";

    private HttpServer server;
    private CapturedRequest captured;
    private final AtomicInteger requestCount = new AtomicInteger();

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
    void managementCancelDecodesObjectAndEmitsOneNavigatorIdentity() {
        NavigatorClient client = NavigatorClient.builder()
                .baseUrl(baseUrl())
                .apiKey("management-api-key")
                .tenantId("tenant-1")
                .build();

        AgentTask task = client.agents().cancelTask(
                "agent-1", "task-1", REQUEST_ID);

        assertEquals("task-1", task.getTaskId());
        assertEquals("CANCEL_REQUESTED", task.getStatus());
        assertEquals(REQUEST_ID, task.getClientRequestId());
        assertEquals("management-api-key",
                captured.headers().getFirst("X-API-Key"));
        assertNull(captured.headers().getFirst("Authorization"));
        assertEquals("tenant-1", captured.headers().getFirst("X-Tenant-Id"));
        assertEquals(REQUEST_ID,
                captured.headers().getFirst("X-Navigator-Client-Request-Id"));
    }

    @Test
    void legacyVoidManagementCancelConsumesObjectResponseAndMintsRequestId() {
        NavigatorClient client = NavigatorClient.builder()
                .baseUrl(baseUrl())
                .bearerToken("management-jwt")
                .build();

        client.agents().cancelTask("agent-1", "task-1");

        assertEquals("Bearer management-jwt",
                captured.headers().getFirst("Authorization"));
        UUID.fromString(captured.headers().getFirst(
                "X-Navigator-Client-Request-Id"));
    }

    @Test
    void runtimeCancelSuppressesEveryConfiguredDefaultCredentialAndTenant() {
        NavigatorClient client = NavigatorClient.builder()
                .baseUrl(baseUrl())
                .apiKey("default-api-key")
                .bearerToken("default-bearer")
                .controlApiKey("default-control")
                .operatorApiKey("default-operator")
                .upstreamAdminApiKey("default-admin")
                .tenantId("default-tenant")
                .build();

        AgentTask task = client.agents().cancelTaskWithClientAppAccessToken(
                "agent-1",
                "task-1",
                "runtime-app-key",
                "runtime-access-token",
                "upstream-user",
                REQUEST_ID);

        assertEquals("CANCEL_REQUESTED", task.getStatus());
        assertEquals("runtime-app-key",
                captured.headers().getFirst("X-Client-App-Key"));
        assertEquals("runtime-access-token",
                captured.headers().getFirst("X-Client-App-Access-Token"));
        assertEquals("upstream-user",
                captured.headers().getFirst("X-Upstream-User-Id"));
        assertEquals(REQUEST_ID,
                captured.headers().getFirst("X-Navigator-Client-Request-Id"));
        assertNull(captured.headers().getFirst("X-API-Key"));
        assertNull(captured.headers().getFirst("Authorization"));
        assertNull(captured.headers().getFirst("X-Client-App-Control-Key"));
        assertNull(captured.headers().getFirst("X-Navi-Operator-Key"));
        assertNull(captured.headers().getFirst("X-Navi-Admin-Key"));
        assertNull(captured.headers().getFirst("X-Tenant-Id"));
    }

    @Test
    void legacyVoidRuntimeCancelConsumesObjectResponse() {
        NavigatorClient client = NavigatorClient.builder()
                .baseUrl(baseUrl())
                .noDefaultAuth()
                .build();

        client.agents().cancelTaskWithClientAppAccessToken(
                "agent-1",
                "task-1",
                "runtime-app-key",
                "runtime-access-token",
                "upstream-user");

        assertEquals(1, requestCount.get());
        assertEquals("runtime-app-key",
                captured.headers().getFirst("X-Client-App-Key"));
        UUID.fromString(captured.headers().getFirst(
                "X-Navigator-Client-Request-Id"));
    }

    @Test
    void everyForeignManagementDefaultFailsBeforeNetwork() {
        NavigatorClient control = NavigatorClient.builder()
                .baseUrl(baseUrl())
                .controlApiKey("control")
                .build();
        NavigatorClient operator = NavigatorClient.builder()
                .baseUrl(baseUrl())
                .operatorApiKey("operator")
                .build();
        NavigatorClient admin = NavigatorClient.builder()
                .baseUrl(baseUrl())
                .upstreamAdminApiKey("admin")
                .build();

        for (NavigatorClient client : new NavigatorClient[]{control, operator, admin}) {
            assertThrows(NavigatorApiException.class,
                    () -> client.agents().cancelTask(
                            "agent-1", "task-1", REQUEST_ID));
        }

        assertEquals(0, requestCount.get());
        assertNull(captured);
    }

    @Test
    void managementMixedDefaultsAndInvalidRequestIdFailBeforeNetwork() {
        NavigatorClient mixed = NavigatorClient.builder()
                .baseUrl(baseUrl())
                .apiKey("api-key")
                .bearerToken("bearer")
                .build();
        assertThrows(NavigatorApiException.class,
                () -> mixed.agents().cancelTask(
                        "agent-1", "task-1", REQUEST_ID));

        NavigatorClient runtime = NavigatorClient.builder()
                .baseUrl(baseUrl())
                .noDefaultAuth()
                .build();
        assertThrows(NavigatorApiException.class,
                () -> runtime.agents().cancelTaskWithClientAppAccessToken(
                        "agent-1",
                        "task-1",
                        "app-key",
                        "access-token",
                        "upstream-user",
                        "not-a-uuid"));

        assertEquals(0, requestCount.get());
        assertNull(captured);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        captured = new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders());
        byte[] response = ("""
                {"code":0,"data":{"clientRequestId":"%s","taskId":"task-1",\
                "agentId":"agent-1","status":"CANCEL_REQUESTED"}}
                """.formatted(REQUEST_ID)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record CapturedRequest(String method, String path, Headers headers) {
    }
}
