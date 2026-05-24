package com.foggy.navigator.sdk.api;

import com.foggy.navigator.sdk.NavigatorClient;
import com.foggy.navigator.sdk.model.Directory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryApiTest {

    private static HttpServer server;
    private static int port;
    private static String lastPath;
    private static String lastMethod;
    private static String lastClientAppControlKeyHeader;
    private static String lastBody;
    private static String responseOverride;

    private final DirectoryApi api = new DirectoryApi(null);

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().toString();
            lastMethod = exchange.getRequestMethod();
            lastClientAppControlKeyHeader = exchange.getRequestHeaders().getFirst("X-Client-App-Control-Key");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            String response = responseOverride != null ? responseOverride : "{\"code\":0,\"data\":{}}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void resetServerState() {
        lastPath = null;
        lastMethod = null;
        lastClientAppControlKeyHeader = null;
        lastBody = null;
        responseOverride = "{\"code\":0,\"data\":{}}";
    }

    @Test
    @SuppressWarnings("removal")
    void legacyOpenDirectoryMethodsFailLocally() {
        assertRemoved(() -> api.init("worker-1", "/workspace/demo", "demo", Map.of("CLAUDE.md", "# Demo")));
        assertRemoved(() -> api.list());
        assertRemoved(() -> api.listByWorker("worker-1"));
        assertRemoved(() -> api.get("dir-1"));
        assertRemoved(() -> api.delete("dir-1"));
        assertRemoved(() -> api.updateEnvVars("dir-1", Map.of("A", "B")));
        assertRemoved(() -> api.updateFiles("dir-1", Map.of("CLAUDE.md", "# Updated")));
    }

    @Test
    void initWithClientAppControlUsesControlKeyAndOwnerAwarePath() {
        NavigatorClient client = clientAppControlClient();
        responseOverride = "{\"code\":0,\"data\":{\"directoryId\":\"dir-1\",\"clientAppId\":\"app-1\","
                + "\"workspaceScope\":\"CLIENT_APP_SHARED\"}}";

        Directory directory = client.directories().initWithClientAppControl("app-1", Map.of(
                "workerId", "worker-1",
                "workspaceScope", "CLIENT_APP_SHARED",
                "path", "/workspace/app",
                "files", Map.of("CLAUDE.md", "# App")));

        assertEquals("dir-1", directory.getDirectoryId());
        assertEquals("POST", lastMethod);
        assertEquals("/api/v1/client-apps/app-1/directories/init", lastPath);
        assertEquals("control-key", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"workspaceScope\":\"CLIENT_APP_SHARED\""));
    }

    @Test
    void listWithClientAppControlPreservesFilters() {
        NavigatorClient client = clientAppControlClient();
        responseOverride = "{\"code\":0,\"data\":[{\"directoryId\":\"dir-1\"}]}";

        List<Directory> directories = client.directories().listWithClientAppControl(
                "app-1", "worker-1", "USER_PRIVATE", "up-user-1");

        assertEquals(1, directories.size());
        assertEquals("GET", lastMethod);
        assertEquals("/api/v1/client-apps/app-1/directories?workerId=worker-1"
                + "&workspaceScope=USER_PRIVATE&upstreamUserId=up-user-1", lastPath);
        assertEquals("control-key", lastClientAppControlKeyHeader);
        assertEquals("", lastBody);
    }

    private static void assertRemoved(Runnable call) {
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, call::run);
        assertTrue(ex.getMessage().contains("LEGACY_API_REMOVED"));
    }

    private NavigatorClient clientAppControlClient() {
        return NavigatorClient.builder()
                .baseUrl("http://localhost:" + port)
                .controlApiKey("control-key")
                .timeout(Duration.ofSeconds(5))
                .build();
    }
}
