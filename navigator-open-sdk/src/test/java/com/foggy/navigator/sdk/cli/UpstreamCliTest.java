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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UpstreamCliTest {
    private static HttpServer server;
    private static int port;
    private static String lastPath;
    private static String lastMethod;
    private static String lastBody;
    private static String lastAuthorizationHeader;
    private static String lastClientAppKeyHeader;
    private static String lastClientAppSecretHeader;
    private static String lastClientAppAccessTokenHeader;
    private static String lastUpstreamUserIdHeader;
    private static String responseOverride;
    private static List<String> requestPaths;

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
            requestPaths.add(lastPath);
            lastMethod = exchange.getRequestMethod();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastClientAppKeyHeader = exchange.getRequestHeaders().getFirst("X-Client-App-Key");
            lastClientAppSecretHeader = exchange.getRequestHeaders().getFirst("X-Client-App-Secret");
            lastClientAppAccessTokenHeader = exchange.getRequestHeaders().getFirst("X-Client-App-Access-Token");
            lastUpstreamUserIdHeader = exchange.getRequestHeaders().getFirst("X-Upstream-User-Id");

            String response;
            if ("__MESSAGES_TERMINAL__".equals(responseOverride)) {
                response = lastPath.contains("/messages")
                        ? "{\"code\":0,\"data\":{\"messages\":[{\"messageId\":\"m-1\",\"role\":\"assistant\",\"type\":\"text\",\"content\":\"done cat-runtime-secret\"}]}}"
                        : "{\"code\":0,\"data\":{\"taskId\":\"task-1\",\"status\":\"COMPLETED\"}}";
            } else if ("__RUNTIME_THEN_READINESS__".equals(responseOverride)) {
                response = lastPath.contains("/runtime-token")
                        ? "{\"accessToken\":\"cat-auto-secret\",\"appKey\":\"cak-test\",\"clientAppId\":\"app-1\",\"expiresInSeconds\":1800}"
                        : """
                        {"code":0,"data":{
                          "overallStatus":"OK",
                          "agentCode":"agent-1",
                          "upstreamUserId":"u-1",
                          "requestedModelConfigId":"model-env",
                          "effectiveModelConfigId":"model-env",
                          "checks":[
                            {"code":"AGENT_REGISTERED","status":"OK","message":"agent registered"}
                          ]
                        }}
                        """;
            } else {
                response = responseOverride != null ? responseOverride : "{\"code\":0,\"data\":{}}";
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
        lastAuthorizationHeader = null;
        lastClientAppKeyHeader = null;
        lastClientAppSecretHeader = null;
        lastClientAppAccessTokenHeader = null;
        lastUpstreamUserIdHeader = null;
        requestPaths = new ArrayList<>();
        responseOverride = "{\"code\":0,\"data\":{}}";
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
    }

    @Test
    void configCheckMasksSecretsAndRequiresIgnoredProfile() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navi-upstream.env\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".navi-upstream.env"),
                "NAVI_CLIENT_APP_SECRET=super-secret-value\nNAVI_CLIENT_APP_ACCESS_TOKEN=runtime-secret-value\n",
                StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "config", "check", "--profile", ".navi-upstream.env"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(output.contains("profileGitIgnored=true"));
        assertFalse(output.contains("super-secret-value"));
        assertFalse(output.contains("runtime-secret-value"));
    }

    @Test
    void configCheckUsesProjectLocalProfileByDefault() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path configDir = tempDir.resolve(".navigator");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("upstream.env"), """
                NAVI_BASE_URL=http://navigator.local
                NAVI_CLIENT_APP_ID=app-project
                NAVI_CLIENT_APP_KEY=cak-project-secret
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "config", "check"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(output.contains(".navigator"));
        assertTrue(output.contains("profileExists=true"));
        assertTrue(output.contains("profileGitIgnored=true"));
        assertTrue(output.contains("NAVI_BASE_URL=http://navigator.local"));
        assertTrue(output.contains("NAVI_CLIENT_APP_ID=app-project"));
        assertFalse(output.contains("cak-project-secret"));
    }

    @Test
    void configCheckUsesProfileFromEnvironmentWhenNoCliProfile() throws Exception {
        Path externalDir = Files.createTempDirectory("navi-upstream-profile-env");
        Path externalProfile = externalDir.resolve("upstream-a.env");
        Files.writeString(externalProfile, "NAVI_CLIENT_APP_ID=app-env-profile\n", StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "config", "check"},
                env("NAVI_UPSTREAM_PROFILE", externalProfile.toString()));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(output.contains("upstream-a.env"));
        assertTrue(output.contains("NAVI_CLIENT_APP_ID=app-env-profile"));
    }

    @Test
    void configCheckRejectsUnignoredProfile() throws Exception {
        Files.writeString(tempDir.resolve("local.properties"),
                "NAVI_CLIENT_APP_SECRET=super-secret-value\n", StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "config", "check", "--profile", "local.properties"}, Map.of());

        assertEquals(2, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("not git-ignored"));
    }

    @Test
    void configCheckAllowsProfileOutsideWorkspace() throws Exception {
        Path externalDir = Files.createTempDirectory("navi-upstream-external");
        Path externalProfile = externalDir.resolve("sandbox.local.env");
        Files.writeString(externalProfile, "NAVI_CLIENT_APP_SECRET=super-secret-value\n", StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "config", "check", "--profile", externalProfile.toString()}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(output.contains("profileGitIgnored=true"));
        assertFalse(output.contains("super-secret-value"));
    }

    @Test
    void configCheckMapsSandboxProfileAliasesAndMasksClientAppKey() throws Exception {
        Path externalDir = Files.createTempDirectory("navi-upstream-sandbox");
        Path externalProfile = externalDir.resolve("current-dev-sandbox.local.env");
        Files.writeString(externalProfile, """
                NAVIGATOR_BASE_URL=http://localhost:8112
                NAVIGATOR_TENANT_ID=tenant-1
                CLIENT_APP_ID=app-1
                CLIENT_APP_KEY=cak-sensitive-key
                CLIENT_APP_SECRET=cas-sensitive-secret
                CLIENT_APP_RUNTIME_TOKEN=cat-sensitive-token
                NAVIGATOR_ADMIN_TOKEN=admin-sensitive-token
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "config", "check", "--profile", externalProfile.toString()}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(output.contains("NAVI_BASE_URL=http://localhost:8112"));
        assertTrue(output.contains("NAVI_TENANT_ID=tenant-1"));
        assertTrue(output.contains("NAVI_CLIENT_APP_ID=app-1"));
        assertTrue(output.contains("NAVI_CLIENT_APP_KEY="));
        assertTrue(output.contains("NAVI_CLIENT_APP_SECRET="));
        assertTrue(output.contains("NAVI_CLIENT_APP_ACCESS_TOKEN="));
        assertTrue(output.contains("NAVI_ADMIN_TOKEN="));
        assertFalse(output.contains("cak-sensitive-key"));
        assertFalse(output.contains("cas-sensitive-secret"));
        assertFalse(output.contains("cat-sensitive-token"));
        assertFalse(output.contains("admin-sensitive-token"));
    }

    @Test
    void runtimeTokenUsesSecretHeaderAndMasksOutput() {
        responseOverride = "{\"accessToken\":\"cat-runtime-secret\",\"appKey\":\"cak-test\",\"clientAppId\":\"app-1\",\"expiresInSeconds\":1800}";
        Map<String, String> env = env("NAVI_SECRET_ENV", "cas-secret-value");

        int code = run(new String[]{"upstream", "runtime-token",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-secret-env", "NAVI_SECRET_ENV"}, env);

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/client-apps/runtime-token", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("cak-test", lastClientAppKeyHeader);
        assertEquals("cas-secret-value", lastClientAppSecretHeader);
        assertFalse(output.contains("cak-test"));
        assertFalse(output.contains("cas-secret-value"));
        assertFalse(output.contains("cat-runtime-secret"));
        assertTrue(output.contains("runtime-token ok"));
    }

    @Test
    void runtimeTokenWriteProfileStoresAccessTokenWithoutPrintingIt() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Path profile = profileDir.resolve("upstream.env");
        Files.writeString(profile, """
                NAVI_BASE_URL=%s
                NAVI_CLIENT_APP_KEY=cak-test
                NAVI_CLIENT_APP_SECRET=cas-secret-value
                NAVI_CLIENT_APP_ACCESS_TOKEN=cat-old-secret
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        responseOverride = "{\"accessToken\":\"cat-written-secret\",\"appKey\":\"cak-test\",\"clientAppId\":\"app-1\",\"expiresInSeconds\":1800}";

        int code = run(new String[]{"upstream", "runtime-token", "--write-profile"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        String profileText = Files.readString(profile, StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(profileText.contains("NAVI_CLIENT_APP_ACCESS_TOKEN=cat-written-secret"));
        assertFalse(profileText.contains("cat-old-secret"));
        assertTrue(output.contains("runtime-token ok"));
        assertTrue(output.contains("profileUpdated="));
        assertFalse(output.contains("cak-test"));
        assertFalse(output.contains("cas-secret-value"));
        assertFalse(output.contains("cat-written-secret"));
    }

    @Test
    void runtimeTokenWriteProfileRejectsUnignoredProfileBeforeExchange() throws Exception {
        Path profile = tempDir.resolve("upstream.env");
        Files.writeString(profile, """
                NAVI_BASE_URL=%s
                NAVI_CLIENT_APP_KEY=cak-test
                NAVI_CLIENT_APP_SECRET=cas-secret-value
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        responseOverride = "{\"accessToken\":\"cat-written-secret\",\"appKey\":\"cak-test\",\"clientAppId\":\"app-1\"}";

        int code = run(new String[]{"upstream", "runtime-token", "--profile", "upstream.env", "--write-profile"}, Map.of());

        assertEquals(2, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("not git-ignored"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void ensureGrantRequiresAdminCredentialAndDoesNotPrintTokens() {
        responseOverride = "{\"clientAppId\":\"app-1\",\"upstreamUserId\":\"u-1\",\"status\":\"ENABLED\"}";
        Map<String, String> env = env("ADMIN_ENV", "admin-token-secret", "TMS_ENV", "staff-token-secret");

        int code = run(new String[]{"upstream", "ensure-grant",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--client-app-id", "app-1",
                "--upstream-user-id", "u-1",
                "--admin-token-env", "ADMIN_ENV",
                "--upstream-user-token-env", "TMS_ENV"}, env);

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("Bearer admin-token-secret", lastAuthorizationHeader);
        assertEquals("/api/v1/business-agent/client-apps/app-1/upstream-users", lastPath);
        assertTrue(lastBody.contains("\"upstreamUserToken\":\"staff-token-secret\""));
        assertFalse(output.contains("admin-token-secret"));
        assertFalse(output.contains("staff-token-secret"));
        assertTrue(output.contains("ensure-grant ok"));
    }

    @Test
    void askSendsClientContextJsonTopLevel() {
        responseOverride = "{\"code\":0,\"data\":{\"taskId\":\"task-1\",\"status\":\"SUBMITTED\",\"contextId\":\"ctx-1\"}}";

        int code = run(new String[]{"upstream", "ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "hello",
                "--context-id", "ctx-1",
                "--client-context-json", "{\"upstreamConversationId\":\"tms-1\",\"bizObjectId\":\"SO-1\"}"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/ask", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("cak-test", lastClientAppKeyHeader);
        assertEquals("cat-runtime-secret", lastClientAppAccessTokenHeader);
        assertEquals("u-1", lastUpstreamUserIdHeader);
        assertTrue(lastBody.contains("\"contextId\":\"ctx-1\""));
        assertTrue(lastBody.contains("\"clientContext\""));
        assertTrue(lastBody.contains("\"upstreamConversationId\":\"tms-1\""));
        assertTrue(lastBody.contains("\"bizObjectId\":\"SO-1\""));
        assertTrue(output.contains("taskId=task-1"));
        assertTrue(output.contains("contextId=ctx-1"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void messagesPollStopsOnTaskTerminalStatus() {
        responseOverride = "__MESSAGES_TERMINAL__";

        Map<String, String> env = env("TOKEN_ENV", "cat-runtime-secret");
        int code = run(new String[]{"upstream", "messages",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token-env", "TOKEN_ENV",
                "--agent", "agent-1",
                "--task-id", "task-1",
                "--poll",
                "--interval", "1"}, env);

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("cat-runtime-secret", lastClientAppAccessTokenHeader);
        assertTrue(output.contains("taskStatus=COMPLETED"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void verifyAgentReadinessPrintsChecksAndUsesClientAppRuntimeHeaders() {
        responseOverride = """
                {"code":0,"data":{
                  "overallStatus":"OK",
                  "baseUrl":"http://localhost:8112",
                  "clientAppId":"app-1",
                  "clientAppName":"Sensitive App",
                  "agentCode":"agent-1",
                  "upstreamUserId":"u-1",
                  "requestedModelConfigId":"model-1",
                  "effectiveModelConfigId":"model-1",
                  "checks":[
                    {"code":"AGENT_REGISTERED","status":"OK","message":"agent registered"},
                    {"code":"UPSTREAM_USER_GRANT","status":"OK","message":"grant enabled"}
                  ],
                  "skillArtifact":{"available":true,"treeUrl":"http://localhost:8112/api/v1/open/skills/agent-1/files/tree"}
                }}
                """;
        Map<String, String> env = env("TOKEN_ENV", "cat-runtime-secret");

        int code = run(new String[]{"upstream", "verify-agent-readiness",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token-env", "TOKEN_ENV",
                "--agent-code", "agent-1",
                "--upstream-user-id", "u-1",
                "--model-config-id", "model-1"}, env);

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/preflight", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("cak-test", lastClientAppKeyHeader);
        assertEquals("cat-runtime-secret", lastClientAppAccessTokenHeader);
        assertTrue(lastBody.contains("\"upstreamUserId\":\"u-1\""));
        assertTrue(lastBody.contains("\"modelConfigId\":\"model-1\""));
        assertTrue(output.contains("verify-agent-readiness OK"));
        assertTrue(output.contains("check AGENT_REGISTERED=OK"));
        assertTrue(output.contains("skillArtifactTreeUrl=http://localhost:8112/api/v1/open/skills/agent-1/files/tree"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void verifyAgentReadinessAutoExchangesRuntimeTokenAndUsesModelConfigFromEnv() {
        responseOverride = "__RUNTIME_THEN_READINESS__";

        int code = run(new String[]{"upstream", "verify-agent-readiness",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-secret", "cas-secret-value",
                "--agent-code", "agent-1",
                "--upstream-user-id", "u-1"}, env("NAVI_MODEL_CONFIG_ID", "model-env"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(requestPaths.contains("/api/v1/open/client-apps/runtime-token"));
        assertEquals("/api/v1/open/agents/agent-1/preflight", lastPath);
        assertEquals("cak-test", lastClientAppKeyHeader);
        assertEquals("cat-auto-secret", lastClientAppAccessTokenHeader);
        assertTrue(lastBody.contains("\"modelConfigId\":\"model-env\""));
        assertTrue(output.contains("verify-agent-readiness OK"));
        assertFalse(output.contains("cak-test"));
        assertFalse(output.contains("cas-secret-value"));
        assertFalse(output.contains("cat-auto-secret"));
    }

    @Test
    void verifyAgentReadinessReturnsNonZeroWhenAnyCheckFails() {
        responseOverride = """
                {"code":0,"data":{
                  "overallStatus":"FAIL",
                  "agentCode":"agent-1",
                  "upstreamUserId":"u-1",
                  "checks":[
                    {"code":"UPSTREAM_USER_GRANT","status":"FAIL","message":"grant missing"}
                  ]
                }}
                """;

        int code = run(new String[]{"upstream", "verify-agent-readiness",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent-code", "agent-1",
                "--upstream-user-id", "u-1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertTrue(output.contains("verify-agent-readiness FAIL"));
        assertTrue(output.contains("check UPSTREAM_USER_GRANT=FAIL message=grant missing"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void skillReadEncodesPathAndPrintsContinuationCommand() {
        responseOverride = """
                {"code":0,"data":{
                  "skillId":"agent-1",
                  "path":"references/runtime.md",
                  "encoding":"UTF-8",
                  "lineEnding":"LF",
                  "startLine":2,
                  "startColumn":3,
                  "endLine":2,
                  "endColumn":5,
                  "nextLine":2,
                  "nextColumn":5,
                  "maxChars":2,
                  "truncated":true,
                  "totalLines":5,
                  "content":"甲乙"
                }}
                """;

        int code = run(new String[]{"upstream", "skill", "read",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent-code", "agent-1",
                "--path", "references/runtime.md",
                "--start-line", "2",
                "--start-column", "3",
                "--max-chars", "2"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/skills/agent-1/files/slice?path=references%2Fruntime.md&startLine=2&startColumn=3&maxChars=2", lastPath);
        assertEquals("GET", lastMethod);
        assertTrue(output.contains("range=2:3-2:5"));
        assertTrue(output.contains("truncated=true"));
        assertTrue(output.contains("甲乙"));
        assertTrue(output.contains("continueCommand=upstream skill read --agent-code agent-1 --path references/runtime.md --start-line 2 --start-column 5 --max-chars 2"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void skillSyncPublicUsesControlPlaneCredentialAndManifest() throws Exception {
        responseOverride = """
                {"code":0,"data":{
                  "scope":"CLIENT_APP_PUBLIC",
                  "clientAppId":"app-1",
                  "accountId":"",
                  "skillId":"agent-1",
                  "status":"ENABLED"
                }}
                """;
        Path manifest = tempDir.resolve("skill-bundle.json");
        Files.writeString(manifest, """
                {
                  "skillId":"agent-1",
                  "name":"Agent One",
                  "markdownBody":"# Agent One"
                }
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "skill", "sync",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--admin-token", "admin-secret",
                "--client-app-id", "app-1",
                "--scope", "client-app-public",
                "--manifest", manifest.getFileName().toString()}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/business-agent/skill-bundles/sync", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("Bearer admin-secret", lastAuthorizationHeader);
        assertTrue(lastBody.contains("\"clientAppId\":\"app-1\""));
        assertTrue(lastBody.contains("\"scope\":\"CLIENT_APP_PUBLIC\""));
        assertTrue(output.contains("skill sync ok"));
        assertTrue(output.contains("scope=CLIENT_APP_PUBLIC"));
        assertFalse(output.contains("admin-secret"));
    }

    @Test
    void agentSyncHelpPrintsUsageWithoutManifest() {
        int code = run(new String[]{"upstream", "agent", "sync", "--help"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(output.contains("Usage: navi upstream agent sync --manifest <agent-bundle.json>"));
        assertTrue(output.contains("--admin-token <token>"));
        assertNull(lastPath);
    }

    @Test
    void agentSyncUsesControlPlaneCredentialManifestAndEnvDefaults() throws Exception {
        responseOverride = """
                {"code":0,"data":{
                  "clientAppId":"app-env",
                  "agentId":"agent-env",
                  "skillId":"agent-env",
                  "agentType":"LOCAL_LANGGRAPH_WORKER",
                  "workerId":"worker-1",
                  "defaultModelConfigId":"model-env",
                  "skillBundle":{"status":"ENABLED"}
                }}
                """;
        Path manifest = tempDir.resolve("agent-bundle.json");
        Files.writeString(manifest, """
                {
                  "name":"Agent Env",
                  "workerId":"worker-1",
                  "markdownBody":"# Agent Env"
                }
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "agent", "sync",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--admin-token", "admin-secret",
                "--manifest", manifest.getFileName().toString()}, env(
                "NAVI_CLIENT_APP_ID", "app-env",
                "NAVI_AGENT_CODE", "agent-env",
                "NAVI_MODEL_CONFIG_ID", "model-env"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/business-agent/agent-bundles/sync", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("Bearer admin-secret", lastAuthorizationHeader);
        assertTrue(lastBody.contains("\"clientAppId\":\"app-env\""));
        assertTrue(lastBody.contains("\"agentId\":\"agent-env\""));
        assertTrue(lastBody.contains("\"defaultModelConfigId\":\"model-env\""));
        assertTrue(output.contains("agent sync ok"));
        assertTrue(output.contains("agentType=LOCAL_LANGGRAPH_WORKER"));
        assertFalse(output.contains("admin-secret"));
    }

    @Test
    void skillSyncAccountPrivateUsesRuntimeHeaders() throws Exception {
        responseOverride = """
                {"code":0,"data":{
                  "scope":"ACCOUNT_PRIVATE",
                  "clientAppId":"app-1",
                  "accountId":"staff-1",
                  "skillId":"personal-agent",
                  "status":"ENABLED"
                }}
                """;
        Path manifest = tempDir.resolve("account-skill.json");
        Files.writeString(manifest, """
                {
                  "skillId":"personal-agent",
                  "name":"Personal Agent",
                  "markdownBody":"# Personal Agent"
                }
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "skill", "sync",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--scope", "account-private",
                "--manifest", manifest.getFileName().toString(),
                "--upstream-user-id", "staff-1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/accounts/me/skill-bundles/sync", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("cak-test", lastClientAppKeyHeader);
        assertEquals("cat-runtime-secret", lastClientAppAccessTokenHeader);
        assertEquals("staff-1", lastUpstreamUserIdHeader);
        assertFalse(lastBody.contains("accountId"));
        assertTrue(output.contains("accountId=staff-1"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    private int run(String[] args, Map<String, String> env) {
        return new UpstreamCli(
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                tempDir)
                .run(args, env);
    }

    private static String baseUrl() {
        return "http://localhost:" + port;
    }

    private static Map<String, String> env(String... values) {
        Map<String, String> env = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            env.put(values[i], values[i + 1]);
        }
        return env;
    }
}
