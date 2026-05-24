package com.foggy.navigator.sdk.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class E2eCliTest {
    private static HttpServer server;
    private static int port;
    private static String lastPath;
    private static String lastMethod;
    private static String lastBody;
    private static String lastApiKey;
    private static String lastControlKey;
    private static String lastTenantId;
    private static String lastContentLength;

    @TempDir
    Path tempDir;

    private ByteArrayOutputStream stdout;
    private ByteArrayOutputStream stderr;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().toString();
            lastMethod = exchange.getRequestMethod();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastApiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            lastControlKey = exchange.getRequestHeaders().getFirst("X-Client-App-Control-Key");
            lastTenantId = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
            lastContentLength = exchange.getRequestHeaders().getFirst("Content-Length");

            String response;
            if (lastPath.startsWith("/api/v1/business-agent/client-apps/capp-1/e2e-model-config/ensure")
                    && "POST".equals(lastMethod)) {
                response = """
                        {
                          "code": 0,
                          "data": {
                            "clientAppId": "capp-1",
                            "standard": "biz-worker",
                            "mockBaseUrl": "http://127.0.0.1:%d",
                            "modelConfigId": "cfg-e2e",
                            "modelConfigName": "Navigator E2E Test Model - capp-1",
                            "modelCreated": true,
                            "modelUpdated": false,
                            "grantId": 7,
                            "grantCreated": true,
                            "grantStatus": "ENABLED",
                            "isDefault": true
                          }
                        }
                        """.formatted(port);
            } else if (lastPath.startsWith("/__e2e/scripts") && "POST".equals(lastMethod)) {
                response = "{\"traceId\":\"e2e-uuid-001\",\"scenarioId\":\"biz-worker\",\"turns\":2,\"expiresAt\":123.45}";
            } else if (lastPath.startsWith("/__e2e/scripts") && "DELETE".equals(lastMethod)) {
                response = "{\"traceId\":\"e2e-uuid-001\",\"removed\":true}";
            } else if (lastPath.startsWith("/__debug/requests")) {
                response = "[{\"traceId\":\"e2e-uuid-001\",\"cursor\":\"next:e2e-uuid-001:001\",\"matched\":true}]";
            } else {
                response = "{}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
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
    void reset() {
        lastPath = null;
        lastMethod = null;
        lastBody = null;
        lastApiKey = null;
        lastControlKey = null;
        lastTenantId = null;
        lastContentLength = null;
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
    }

    @Test
    void configCheckLoadsMockUrlFromProfile() throws Exception {
        writeProfile("NAVI_E2E_MOCK_LLM_URL=http://127.0.0.1:" + port + "\n");

        int code = run(new String[]{"config", "check"});

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(output.contains("profileGitIgnored=true"));
        assertTrue(output.contains("NAVI_E2E_MOCK_LLM_URL=http://127.0.0.1:" + port));
    }

    @Test
    void scriptRegisterPostsJsonFileToMockLlm() throws Exception {
        Path script = tempDir.resolve("script.json");
        Files.writeString(script, """
                {
                  "traceId": "e2e-uuid-001",
                  "turns": [
                    {"cursor": "next:e2e-uuid-001:001", "response": {"content": "ok"}}
                  ]
                }
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{
                "script", "register",
                "--file", script.toString(),
                "--mock-url", "http://127.0.0.1:" + port
        });

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("POST", lastMethod);
        assertEquals("/__e2e/scripts", lastPath);
        assertTrue(Integer.parseInt(lastContentLength) > 0);
        assertTrue(lastBody.contains("\"traceId\" : \"e2e-uuid-001\""));
        assertTrue(output.contains("script register ok"));
        assertTrue(output.contains("traceId=e2e-uuid-001"));
        assertTrue(output.contains("turns=2"));
    }

    @Test
    void scriptRegisterNormalizesJsonFileBeforePosting() throws Exception {
        Path script = tempDir.resolve("script-with-bom.json");
        Files.write(script, ("\uFEFF" + """
                {
                  "traceId": "e2e-uuid-001",
                  "turns": [
                    {"cursor": "next:e2e-uuid-001:001", "response": {"content": "ok"}}
                  ]
                }
                """).getBytes(StandardCharsets.UTF_8));

        int code = run(new String[]{
                "script", "register",
                "--file", script.toString(),
                "--mock-url", "http://127.0.0.1:" + port
        });

        assertEquals(0, code);
        assertEquals("POST", lastMethod);
        assertEquals("/__e2e/scripts", lastPath);
        assertTrue(lastBody.startsWith("{"), "posted body should be canonical JSON without BOM");
        assertTrue(lastBody.contains("\"traceId\" : \"e2e-uuid-001\""));
    }

    @Test
    void debugRequestsReadsTraceRequests() {
        int code = run(new String[]{
                "debug", "requests",
                "--trace-id", "e2e-uuid-001",
                "--mock-url", "http://127.0.0.1:" + port
        });

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("GET", lastMethod);
        assertEquals("/__debug/requests?traceId=e2e-uuid-001", lastPath);
        assertTrue(output.contains("\"cursor\" : \"next:e2e-uuid-001:001\""));
    }

    @Test
    void scriptCleanupDeletesTraceScript() {
        int code = run(new String[]{
                "script", "cleanup",
                "--trace-id", "e2e-uuid-001",
                "--mock-url", "http://127.0.0.1:" + port
        });

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("DELETE", lastMethod);
        assertEquals("/__e2e/scripts/e2e-uuid-001", lastPath);
        assertTrue(output.contains("script cleanup ok"));
        assertTrue(output.contains("removed=true"));
    }

    @Test
    void modelEnsureCallsNavigatorAndWritesProfile() throws Exception {
        writeProfile("""
                NAVI_BASE_URL=http://127.0.0.1:%d
                NAVI_TENANT_ID=tenant-1
                NAVI_CLIENT_APP_ID=capp-1
                NAVI_CONTROL_API_KEY=control-secret
                NAVI_E2E_MOCK_LLM_URL=http://127.0.0.1:%d
                """.formatted(port, port));

        int code = run(new String[]{
                "model", "ensure",
                "--set-default",
                "--write-profile"
        });

        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(tempDir.resolve(".navigator").resolve("upstream.env"), StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("POST", lastMethod);
        assertEquals("/api/v1/business-agent/client-apps/capp-1/e2e-model-config/ensure", lastPath);
        assertNull(lastApiKey);
        assertEquals("control-secret", lastControlKey);
        assertEquals("tenant-1", lastTenantId);
        assertTrue(lastBody.contains("\"mockBaseUrl\":\"http://127.0.0.1:" + port + "\""));
        assertTrue(lastBody.contains("\"setDefault\":true"));
        assertTrue(output.contains("e2e model ensure ok"));
        assertTrue(output.contains("modelConfigId=cfg-e2e"));
        assertTrue(output.contains("stored=NAVI_MODEL_CONFIG_ID"));
        assertTrue(profile.contains("NAVI_MODEL_CONFIG_ID=cfg-e2e"));
    }

    @Test
    void modelEnsureRequiresClientAppControlKey() throws Exception {
        writeProfile("""
                NAVI_BASE_URL=http://127.0.0.1:%d
                NAVI_TENANT_ID=tenant-1
                NAVI_CLIENT_APP_ID=capp-1
                NAVI_ADMIN_TOKEN=platform-admin-token
                NAVI_E2E_MOCK_LLM_URL=http://127.0.0.1:%d
                """.formatted(port, port));

        int code = run(new String[]{
                "model", "ensure",
                "--set-default"
        });

        String error = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertNull(lastPath);
        assertTrue(error.contains("client app control credential is required"));
        assertTrue(!error.contains("platform-admin-token"));
    }

    private int run(String[] args) {
        return new E2eCli(
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                tempDir).run(args, Map.of());
    }

    private void writeProfile(String content) throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path navigatorDir = tempDir.resolve(".navigator");
        Files.createDirectories(navigatorDir);
        Files.writeString(navigatorDir.resolve("upstream.env"), content, StandardCharsets.UTF_8);
    }
}
