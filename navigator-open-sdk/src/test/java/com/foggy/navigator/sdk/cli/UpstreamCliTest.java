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
import java.util.Base64;
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
    private static String lastApiKeyHeader;
    private static String lastAuthorizationHeader;
    private static String lastOperatorKeyHeader;
    private static String lastUpstreamAdminKeyHeader;
    private static String lastClientAppKeyHeader;
    private static String lastClientAppSecretHeader;
    private static String lastClientAppAccessTokenHeader;
    private static String lastClientAppControlKeyHeader;
    private static String lastUpstreamUserIdHeader;
    private static String responseOverride;
    private static List<String> requestPaths;
    private static List<String> requestBodies;

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
            requestBodies.add(lastBody);
            lastApiKeyHeader = exchange.getRequestHeaders().getFirst("X-API-Key");
            lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastOperatorKeyHeader = exchange.getRequestHeaders().getFirst("X-Navi-Operator-Key");
            lastUpstreamAdminKeyHeader = exchange.getRequestHeaders().getFirst("X-Navi-Admin-Key");
            lastClientAppKeyHeader = exchange.getRequestHeaders().getFirst("X-Client-App-Key");
            lastClientAppSecretHeader = exchange.getRequestHeaders().getFirst("X-Client-App-Secret");
            lastClientAppAccessTokenHeader = exchange.getRequestHeaders().getFirst("X-Client-App-Access-Token");
            lastClientAppControlKeyHeader = exchange.getRequestHeaders().getFirst("X-Client-App-Control-Key");
            lastUpstreamUserIdHeader = exchange.getRequestHeaders().getFirst("X-Upstream-User-Id");

            String response;
            if ("__MESSAGES_TERMINAL__".equals(responseOverride)) {
                response = lastPath.contains("/messages")
                        ? "{\"code\":0,\"data\":{\"messages\":[{\"messageId\":\"m-1\",\"role\":\"assistant\",\"type\":\"text\",\"content\":\"done cat-runtime-secret\"}]}}"
                        : "{\"code\":0,\"data\":{\"taskId\":\"task-1\",\"status\":\"COMPLETED\"}}";
            } else if ("__MESSAGES_FAILED_DIAGNOSTICS__".equals(responseOverride)) {
                response = lastPath.contains("/messages")
                        ? """
                        {"code":0,"data":{
                          "taskId":"task-1",
                          "status":"FAILED",
                          "terminal":true,
                          "terminalStatus":"FAILED",
                          "providerTaskId":"wt-1",
                          "workerTaskId":"wt-1",
                          "lastAckedSeq":0,
                          "modelConfigId":"model-codex",
                          "modelConfigSource":"REQUESTED_MODEL_GRANT",
                          "workerBackend":"OPENAI_CODEX",
                          "providerType":"codex-worker",
                          "taskSource":"PLATFORM",
                          "workerSource":"WORKING_DIRECTORY:USER_PRIVATE",
                          "backendSource":"MODEL_CONFIG_GRANT",
                          "failureStage":"PROVIDER_API",
                          "failureSummary":"Provider API rejected api_key=cat-runtime-secret",
                          "messages":[]
                        }}
                        """
                        : """
                        {"code":0,"data":{
                          "taskId":"task-1",
                          "status":"FAILED",
                          "providerTaskId":"wt-1",
                          "workerTaskId":"wt-1",
                          "lastAckedSeq":0,
                          "modelConfigId":"model-codex",
                          "modelConfigSource":"REQUESTED_MODEL_GRANT",
                          "workerBackend":"OPENAI_CODEX",
                          "providerType":"codex-worker",
                          "taskSource":"PLATFORM",
                          "workerSource":"WORKING_DIRECTORY:USER_PRIVATE",
                          "backendSource":"MODEL_CONFIG_GRANT",
                          "failureStage":"PROVIDER_API",
                          "failureSummary":"Provider API rejected api_key=cat-runtime-secret"
                        }}
                        """;
            } else if ("__WORKER_HOST_APPLY__".equals(responseOverride)) {
                response = lastPath.contains("/worker-identities")
                        ? "{\"code\":0,\"data\":{\"workerId\":\"school-sim-wsl-biz\",\"ownerType\":\"UPSTREAM_SYSTEM\",\"workerBackend\":\"LANGGRAPH_BIZ\",\"baseUrl\":\"http://127.0.0.1:3161\",\"status\":\"ENABLED\"}}"
                        : "{\"code\":0,\"data\":{\"workerId\":\"school-sim-wsl-claude\",\"name\":\"school-sim-wsl Claude Code Worker\",\"baseUrl\":\"http://127.0.0.1:3131\",\"status\":\"ONLINE\"}}";
            } else if ("__RUNTIME_THEN_READINESS__".equals(responseOverride)) {
                response = lastPath.contains("/runtime-token")
                        ? "{\"accessToken\":\"cat-auto-secret\",\"appKey\":\"cak-test\",\"clientAppId\":\"app-1\",\"expiresInSeconds\":1800}"
                        : """
                        {"code":0,"data":{
                          "overallStatus":"OK",
                          "baseUrl":"http://localhost:8112",
                          "clientAppId":"app-1",
                          "agentCode":"agent-1",
                          "upstreamUserId":"u-1",
                          "requestedModelConfigId":"model-env",
                          "effectiveModelConfigId":"model-env",
                          "effectiveModelName":"qwen-plus",
                          "effectiveWorkerBackend":"LANGGRAPH_BIZ",
                          "modelConfigSource":"REQUESTED_MODEL_GRANT",
                          "agentId":"agent-1",
                          "agentOwnerType":"CLIENT_APP",
                          "agentOwnerId":"app-1",
                          "agentSource":"AGENT:CLIENT_APP",
                          "workerPoolId":"pool-1",
                          "workerPoolOwnerType":"UPSTREAM_SYSTEM",
                          "workerPoolOwnerId":"usys-1",
                          "workerPoolSource":"WORKER_POOL:UPSTREAM_SYSTEM",
                          "internalWorkerPoolId":"pool-1",
                          "internalWorkerPoolOwnerType":"UPSTREAM_SYSTEM",
                          "internalWorkerPoolOwnerId":"usys-1",
                          "internalWorkerPoolSource":"WORKER_POOL:UPSTREAM_SYSTEM",
                          "requestedDirectoryId":"dir-env",
                          "effectiveDirectoryId":"dir-env",
                          "effectivePhysicalWorkerId":"worker-1",
                          "workspaceScope":"USER_PRIVATE",
                          "workspaceSource":"WORKING_DIRECTORY:USER_PRIVATE",
                          "physicalWorkerDiagnostics":[
                            {
                              "role":"biz",
                              "physicalWorkerId":"worker-1",
                              "workerBackend":"LANGGRAPH_BIZ",
                              "source":"BIZ_WORKER_IDENTITY",
                              "executionWorker":true,
                              "directoryWorker":false
                            }
                          ],
                          "checks":[
                            {"code":"AGENT_REGISTERED","status":"OK","message":"agent registered"}
                          ]
                        }}
                        """;
            } else if ("__MODEL_GRANTS_THEN_DEFAULT__".equals(responseOverride)) {
                response = lastPath.endsWith("/default")
                        ? """
                        {"code":0,"data":{
                          "id":31,
                          "clientAppId":"app-1",
                          "modelConfigId":"model-target",
                          "modelConfigName":"Target Model",
                          "workerBackend":"LANGGRAPH_BIZ",
                          "status":"ENABLED",
                          "isDefault":true,
                          "grantScope":"CLIENT_APP"
                        }}
                        """
                        : """
                        {"code":0,"data":[
                          {
                            "id":31,
                            "clientAppId":"app-1",
                            "modelConfigId":"model-target",
                            "modelConfigName":"Target Model",
                            "workerBackend":"LANGGRAPH_BIZ",
                            "status":"ENABLED",
                            "isDefault":false,
                            "grantScope":"CLIENT_APP"
                          }
                        ]}
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
        lastApiKeyHeader = null;
        lastAuthorizationHeader = null;
        lastOperatorKeyHeader = null;
        lastUpstreamAdminKeyHeader = null;
        lastClientAppKeyHeader = null;
        lastClientAppSecretHeader = null;
        lastClientAppAccessTokenHeader = null;
        lastClientAppControlKeyHeader = null;
        lastUpstreamUserIdHeader = null;
        requestPaths = new ArrayList<>();
        requestBodies = new ArrayList<>();
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
                NAVIGATOR_OPERATOR_API_KEY=operator-sensitive-key
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
        assertTrue(output.contains("NAVI_OPERATOR_API_KEY="));
        assertFalse(output.contains("cak-sensitive-key"));
        assertFalse(output.contains("cas-sensitive-secret"));
        assertFalse(output.contains("cat-sensitive-token"));
        assertFalse(output.contains("admin-sensitive-token"));
        assertFalse(output.contains("operator-sensitive-key"));
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
    void adminKeyRequestWritesClaimTokenWithoutPrintingIt() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{
                  "requestCode":"nabr-secret-code",
                  "requestCodeSuffix":"code",
                  "claimToken":"nabt-secret-claim-token",
                  "status":"PENDING",
                  "requestExpiresAt":"2026-05-18T10:00:00"
                }}
                """;

        int code = run(new String[]{"upstream", "admin-key", "request",
                "--base-url", baseUrl(),
                "--upstream-system-id", "x6-tms",
                "--requested-tenant-id", "tenant-1",
                "--multi-tenant",
                "--reason", "tenant bootstrap",
                "--write-profile"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(tempDir.resolve(".navigator").resolve("upstream.env"), StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-bootstrap/admin-key-requests", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastApiKeyHeader);
        assertTrue(lastBody.contains("\"upstreamSystemId\":\"x6-tms\""));
        assertTrue(lastBody.contains("\"requestedTenantId\":\"tenant-1\""));
        assertTrue(lastBody.contains("\"multiTenant\":true"));
        assertTrue(profile.contains("NAVI_ADMIN_KEY_REQUEST_CODE=nabr-secret-code"));
        assertTrue(profile.contains("NAVI_ADMIN_KEY_CLAIM_TOKEN=nabt-secret-claim-token"));
        assertTrue(profile.contains("NAVI_BASE_URL=" + baseUrl()));
        assertTrue(output.contains("admin-key request ok"));
        assertFalse(output.contains("nabt-secret-claim-token"));
    }

    @Test
    void adminKeyClaimWritesAdminKeyAndClearsClaimTokenWithoutPrintingIt() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Path profilePath = profileDir.resolve("upstream.env");
        Files.writeString(profilePath, """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_KEY_REQUEST_CODE=nabr-secret-code
                NAVI_ADMIN_KEY_CLAIM_TOKEN=nabt-secret-claim-token
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{
                  "credentialId":"uac-1",
                  "naviAdminApiKey":"naa-secret-admin-key",
                  "upstreamSystemId":"x6-tms",
                  "authorizedTenantIds":["tenant-1"],
                  "authorizedClientAppNamespace":"tms",
                  "scopes":["CLIENT_APP_ADMIN"],
                  "expiresAt":"2026-05-19T10:00:00"
                }}
                """;

        int code = run(new String[]{"upstream", "admin-key", "claim", "--write-profile"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(profilePath, StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-bootstrap/admin-key-requests/nabr-secret-code/claim", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastApiKeyHeader);
        assertTrue(lastBody.contains("\"claimToken\":\"nabt-secret-claim-token\""));
        assertTrue(profile.contains("NAVI_ADMIN_API_KEY=naa-secret-admin-key"));
        assertTrue(profile.contains("NAVI_ADMIN_KEY_CLAIM_TOKEN="));
        assertFalse(output.contains("naa-secret-admin-key"));
        assertFalse(output.contains("nabt-secret-claim-token"));
        assertTrue(output.contains("stored=NAVI_ADMIN_API_KEY"));
    }

    @Test
    void adminKeyApproveUsesOperatorKeyAndNotUpstreamAdminKey() {
        responseOverride = """
                {"code":0,"data":{
                  "requestId":"req-1",
                  "requestCodeSuffix":"code",
                  "upstreamSystemId":"x6-tms",
                  "requestedTenantId":"tenant-1",
                  "multiTenant":true,
                  "status":"APPROVED",
                  "authorizedTenantIds":["tenant-1","tenant-2"],
                  "authorizedClientAppNamespace":"tms",
                  "scopes":["CLIENT_APP_ADMIN","CONTROL_KEY_ISSUE"],
                  "claimExpiresAt":"2026-05-18T11:00:00"
                }}
                """;

        int code = run(new String[]{"upstream", "admin-key", "approve",
                "--base-url", baseUrl(),
                "--tenant-id", "operator-tenant",
                "--request-code", "nabr-secret-code",
                "--authorized-tenant-ids", "tenant-1,tenant-2",
                "--namespace", "tms",
                "--scopes", "CLIENT_APP_ADMIN,CONTROL_KEY_ISSUE",
                "--claim-ttl-minutes", "45"}, env(
                "NAVI_OPERATOR_API_KEY", "operator-secret-key",
                "NAVI_ADMIN_API_KEY", "upstream-admin-key-must-not-approve"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/admin/upstream-bootstrap-requests/nabr-secret-code/approve", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastApiKeyHeader);
        assertNull(lastAuthorizationHeader);
        assertEquals("operator-secret-key", lastOperatorKeyHeader);
        assertTrue(lastBody.contains("\"authorizedTenantIds\":[\"tenant-1\",\"tenant-2\"]"));
        assertTrue(lastBody.contains("\"authorizedClientAppNamespace\":\"tms\""));
        assertTrue(lastBody.contains("\"claimTtlMinutes\":45"));
        assertTrue(output.contains("admin-key approve ok"));
        assertFalse(output.contains("operator-secret-key"));
        assertFalse(output.contains("upstream-admin-key-must-not-approve"));
    }

    @Test
    void adminKeyRevokeUsesOperatorKeyAndNotUpstreamAdminKey() {
        responseOverride = """
                {"code":0,"data":{
                  "credentialId":"ucaac-1",
                  "upstreamSystemId":"x6-tms",
                  "authorizedTenantIds":["tenant-1"],
                  "authorizedClientAppNamespace":"tms",
                  "scopes":["CLIENT_APP_MANAGE"],
                  "status":"REVOKED",
                  "expiresAt":"2026-05-19T10:00:00",
                  "revokedAt":"2026-05-18T10:00:00"
                }}
                """;

        int code = run(new String[]{"upstream", "admin-key", "revoke",
                "--base-url", baseUrl(),
                "--credential-id", "ucaac-1"}, env(
                "NAVI_OPERATOR_API_KEY", "operator-secret-key",
                "NAVI_ADMIN_API_KEY", "upstream-admin-key-must-not-manage-lifecycle"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/admin/upstream-admin-credentials/ucaac-1/revoke", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastApiKeyHeader);
        assertNull(lastAuthorizationHeader);
        assertEquals("operator-secret-key", lastOperatorKeyHeader);
        assertNull(lastUpstreamAdminKeyHeader);
        assertTrue(output.contains("admin-key revoke ok"));
        assertTrue(output.contains("status=REVOKED"));
        assertFalse(output.contains("operator-secret-key"));
        assertFalse(output.contains("upstream-admin-key-must-not-manage-lifecycle"));
    }

    @Test
    void adminKeyRotateWritesNewAdminKeyWithoutPrintingIt() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Path profilePath = profileDir.resolve("upstream.env");
        Files.writeString(profilePath, """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-old-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{
                  "credentialId":"ucaac-2",
                  "naviAdminApiKey":"naa-new-admin-key",
                  "upstreamSystemId":"x6-tms",
                  "authorizedTenantIds":["tenant-1"],
                  "authorizedClientAppNamespace":"tms",
                  "scopes":["CLIENT_APP_MANAGE","CLIENT_APP_CONTROL_KEY_ISSUE"],
                  "expiresAt":"2026-05-19T10:00:00"
                }}
                """;

        int code = run(new String[]{"upstream", "admin-key", "rotate",
                "--profile", ".navigator/upstream.env",
                "--credential-id", "ucaac-1",
                "--write-profile"}, env("NAVI_OPERATOR_API_KEY", "operator-secret-key"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(profilePath, StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/admin/upstream-admin-credentials/ucaac-1/rotate", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastApiKeyHeader);
        assertNull(lastAuthorizationHeader);
        assertEquals("operator-secret-key", lastOperatorKeyHeader);
        assertNull(lastUpstreamAdminKeyHeader);
        assertTrue(profile.contains("NAVI_ADMIN_API_KEY=naa-new-admin-key"));
        assertFalse(profile.contains("naa-old-admin-key"));
        assertTrue(output.contains("admin-key rotate ok"));
        assertTrue(output.contains("stored=NAVI_ADMIN_API_KEY"));
        assertFalse(output.contains("naa-new-admin-key"));
        assertFalse(output.contains("naa-old-admin-key"));
        assertFalse(output.contains("operator-secret-key"));
    }

    @Test
    void clientAppEnsureUsesUpstreamAdminKeyAndWritesTenantProfile() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Path upstreamProfile = profileDir.resolve("upstream.env");
        Files.writeString(upstreamProfile, """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);

        responseOverride = """
                {"code":0,"data":{
                  "clientAppId":"capp-tenant-a",
                  "tenantId":"tenant-a",
                  "name":"Orders A",
                  "upstreamSystemId":"x6-tms",
                  "upstreamClientAppNamespace":"x6",
                  "upstreamRef":"tms-a",
                  "status":"ACTIVE"
                }}
                """;

        int code = run(new String[]{"upstream", "client-app", "ensure",
                "--profile", ".navigator/upstream.env",
                "--target-tenant-id", "tenant-a",
                "--upstream-ref", "tms-a",
                "--name", "Orders A",
                "--tenant-profile", ".navigator/tenants/tms-a.env",
                "--write-profile"}, Map.of());

        Path tenantProfile = profileDir.resolve("tenants").resolve("tms-a.env");
        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(tenantProfile, StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-admin/client-apps/ensure", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastApiKeyHeader);
        assertNull(lastAuthorizationHeader);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(lastBody.contains("\"targetTenantId\":\"tenant-a\""));
        assertTrue(lastBody.contains("\"upstreamRef\":\"tms-a\""));
        assertTrue(profile.contains("NAVI_BASE_URL=" + baseUrl()));
        assertTrue(profile.contains("NAVI_TENANT_ID=tenant-a"));
        assertTrue(profile.contains("NAVI_CLIENT_APP_ID=capp-tenant-a"));
        assertTrue(profile.contains("NAVI_UPSTREAM_SYSTEM_ID=x6-tms"));
        assertTrue(profile.contains("NAVI_UPSTREAM_REF=tms-a"));
        assertTrue(output.contains("client-app ensure ok"));
        assertFalse(output.contains("naa-secret-admin-key"));
    }

    @Test
    void clientAppEnsureTenantUsesUpstreamAdminKeyAndStoresOneShotCredentials() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Path upstreamProfile = profileDir.resolve("upstream.env");
        Files.writeString(upstreamProfile, """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                NAVI_UPSTREAM_SYSTEM_ID=TMS
                """.formatted(baseUrl()), StandardCharsets.UTF_8);

        responseOverride = """
                {"code":0,"data":{
                  "navigatorTenantId":"nav_tms_3",
                  "clientAppId":"capp-tms-3",
                  "clientAppName":"TMS 3",
                  "capabilityDomain":"tms.ops",
                  "clientAppKey":"cak-secret-key",
                  "clientAppSecret":"cas-secret-value",
                  "controlApiKey":"cac-secret-control-key",
                  "rootAgentId":"tms-root-agent",
                  "modelConfigId":"model-live",
                  "skillId":"tms.navigator.agent",
                  "workerPoolId":"pool-1",
                  "bindingVersion":"bind-v1",
                  "status":"READY",
                  "credentialsReplayable":true,
                  "created":true,
                  "rotated":true,
                  "blockers":["worker route should be verified"]
                }}
                """;

        int code = run(new String[]{"upstream", "client-app", "ensure-tenant",
                "--profile", ".navigator/upstream.env",
                "--source-tenant-id", "3",
                "--name", "TMS 3",
                "--capability-domain", "tms.ops",
                "--model-config-id", "model-live",
                "--skill-id", "tms.navigator.agent",
                "--worker-pool-id", "pool-1",
                "--rotate-credentials",
                "--tenant-profile", ".navigator/tenants/tms-3.env",
                "--write-profile"}, Map.of());

        Path tenantProfile = profileDir.resolve("tenants").resolve("tms-3.env");
        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(tenantProfile, StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/admin/upstream-tenants/client-apps/ensure", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastApiKeyHeader);
        assertNull(lastAuthorizationHeader);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(lastBody.contains("\"sourceSystem\":\"TMS\""));
        assertTrue(lastBody.contains("\"sourceTenantId\":\"3\""));
        assertTrue(lastBody.contains("\"clientAppName\":\"TMS 3\""));
        assertTrue(lastBody.contains("\"rotateCredentials\":true"));
        assertTrue(profile.contains("NAVI_BASE_URL=" + baseUrl()));
        assertTrue(profile.contains("NAVI_TENANT_ID=nav_tms_3"));
        assertTrue(profile.contains("NAVI_CLIENT_APP_ID=capp-tms-3"));
        assertTrue(profile.contains("NAVI_CLIENT_APP_KEY=cak-secret-key"));
        assertTrue(profile.contains("NAVI_CLIENT_APP_SECRET=cas-secret-value"));
        assertTrue(profile.contains("NAVI_CONTROL_API_KEY=cac-secret-control-key"));
        assertTrue(profile.contains("NAVI_AGENT_CODE=tms-root-agent"));
        assertTrue(profile.contains("NAVI_MODEL_CONFIG_ID=model-live"));
        assertTrue(profile.contains("NAVI_SKILL_ID=tms.navigator.agent"));
        assertTrue(profile.contains("NAVI_WORKER_POOL_ID=pool-1"));
        assertTrue(profile.contains("NAVI_SOURCE_TENANT_ID=3"));
        assertTrue(profile.contains("NAVI_UPSTREAM_REF=3"));
        assertTrue(output.contains("client-app ensure-tenant ok"));
        assertTrue(output.contains("stored=NAVI_BASE_URL"));
        assertTrue(output.contains("created=true"));
        assertTrue(output.contains("rotated=true"));
        assertTrue(output.contains("status=READY"));
        assertTrue(output.contains("credentialsReplayable=true"));
        assertTrue(output.contains("blocker=worker route should be verified"));
        assertFalse(output.contains("naa-secret-admin-key"));
        assertFalse(output.contains("cak-secret-key"));
        assertFalse(output.contains("cas-secret-value"));
        assertFalse(output.contains("cac-secret-control-key"));
    }

    @Test
    void clientAppEnsureTenantRejectsCredentialsNotReplayableWithoutWritingProfile() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Path upstreamProfile = profileDir.resolve("upstream.env");
        Files.writeString(upstreamProfile, """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                NAVI_UPSTREAM_SYSTEM_ID=TMS
                """.formatted(baseUrl()), StandardCharsets.UTF_8);

        responseOverride = """
                {"code":0,"data":{
                  "navigatorTenantId":"nav_tms_3",
                  "clientAppId":"capp-tms-3",
                  "clientAppName":"TMS 3",
                  "capabilityDomain":"tms.ops",
                  "rootAgentId":"tms-root-agent",
                  "bindingVersion":"bind-v1",
                  "status":"CREDENTIALS_NOT_REPLAYABLE",
                  "errorCode":"CREDENTIALS_NOT_REPLAYABLE",
                  "message":"binding secrets are one-time credentials; call again with rotateCredentials=true to issue new credentials",
                  "credentialsReplayable":false,
                  "created":false,
                  "rotated":false,
                  "blockers":[]
                }}
                """;

        int code = run(new String[]{"upstream", "client-app", "ensure-tenant",
                "--profile", ".navigator/upstream.env",
                "--source-tenant-id", "3",
                "--tenant-profile", ".navigator/tenants/tms-3.env",
                "--write-profile"}, Map.of());

        assertEquals(2, code);
        assertEquals("/api/v1/admin/upstream-tenants/client-apps/ensure", lastPath);
        assertNull(lastAuthorizationHeader);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("CREDENTIALS_NOT_REPLAYABLE"));
        assertFalse(Files.exists(profileDir.resolve("tenants").resolve("tms-3.env")));
    }

    @Test
    void clientAppEnsureTenantRejectsUnignoredTenantProfileBeforeProvisioning() throws Exception {
        Path upstreamProfile = tempDir.resolve("upstream.env");
        Files.writeString(upstreamProfile, """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "client-app", "ensure-tenant",
                "--profile", upstreamProfile.toString(),
                "--source-system", "TMS",
                "--source-tenant-id", "3",
                "--tenant-profile", "tenant.env",
                "--write-profile"}, Map.of());

        assertEquals(2, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("not git-ignored"));
        assertNull(lastPath);
    }

    @Test
    void clientAppIssueControlKeyUsesUpstreamAdminKeyAndStoresSecretOnlyInTenantProfile() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Path upstreamProfile = profileDir.resolve("upstream.env");
        Files.writeString(upstreamProfile, """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);

        responseOverride = """
                {"code":0,"data":{
                  "credentialId":"cred-1",
                  "clientAppId":"capp-tenant-a",
                  "tenantId":"tenant-a",
                  "controlApiKey":"cac-secret-control-key",
                  "scopes":["SKILL_SYNC","MODEL_GRANT"],
                  "expiresAt":"2026-06-01T00:00:00"
                }}
                """;

        int code = run(new String[]{"upstream", "client-app", "issue-control-key",
                "--profile", ".navigator/upstream.env",
                "--client-app-id", "capp-tenant-a",
                "--scopes", "SKILL_SYNC,MODEL_GRANT",
                "--description", "tenant bootstrap",
                "--tenant-profile", ".navigator/tenants/tms-a.env",
                "--write-profile"}, Map.of());

        Path tenantProfile = profileDir.resolve("tenants").resolve("tms-a.env");
        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(tenantProfile, StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-admin/client-apps/capp-tenant-a/control-credentials", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastApiKeyHeader);
        assertNull(lastAuthorizationHeader);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(lastBody.contains("\"scopes\":[\"SKILL_SYNC\",\"MODEL_GRANT\"]"));
        assertTrue(lastBody.contains("\"description\":\"tenant bootstrap\""));
        assertTrue(profile.contains("NAVI_BASE_URL=" + baseUrl()));
        assertTrue(profile.contains("NAVI_TENANT_ID=tenant-a"));
        assertTrue(profile.contains("NAVI_CLIENT_APP_ID=capp-tenant-a"));
        assertTrue(profile.contains("NAVI_CONTROL_API_KEY=cac-secret-control-key"));
        assertTrue(output.contains("client-app issue-control-key ok"));
        assertTrue(output.contains("stored=NAVI_CONTROL_API_KEY"));
        assertTrue(output.contains("controlApiKey=cac-...-key sha256="));
        assertFalse(output.contains("cac-secret-control-key"));
        assertFalse(output.contains("naa-secret-admin-key"));
    }

    @Test
    void clientAppIssueRuntimeKeyUsesUpstreamAdminKeyAndStoresSecretsOnlyInTenantProfile() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Path upstreamProfile = profileDir.resolve("upstream.env");
        Files.writeString(upstreamProfile, """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);

        responseOverride = """
                {"code":0,"data":{
                  "credentialId":"runtime-cred-1",
                  "clientAppId":"capp-tenant-a",
                  "tenantId":"tenant-a",
                  "appKey":"cak-secret-runtime-key",
                  "secret":"cas-secret-runtime-secret",
                  "expiresAt":"2026-06-01T00:00:00"
                }}
                """;

        int code = run(new String[]{"upstream", "client-app", "issue-runtime-key",
                "--profile", ".navigator/upstream.env",
                "--client-app-id", "capp-tenant-a",
                "--description", "tenant runtime bootstrap",
                "--tenant-profile", ".navigator/tenants/tms-a.env",
                "--rotate-runtime-credential",
                "--write-profile"}, Map.of());

        Path tenantProfile = profileDir.resolve("tenants").resolve("tms-a.env");
        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(tenantProfile, StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-admin/client-apps/capp-tenant-a/runtime-credentials", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastApiKeyHeader);
        assertNull(lastAuthorizationHeader);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(lastBody.contains("\"description\":\"tenant runtime bootstrap\""));
        assertTrue(profile.contains("NAVI_BASE_URL=" + baseUrl()));
        assertTrue(profile.contains("NAVI_TENANT_ID=tenant-a"));
        assertTrue(profile.contains("NAVI_CLIENT_APP_ID=capp-tenant-a"));
        assertTrue(profile.contains("NAVI_CLIENT_APP_KEY=cak-secret-runtime-key"));
        assertTrue(profile.contains("NAVI_CLIENT_APP_SECRET=cas-secret-runtime-secret"));
        assertTrue(profile.contains("NAVI_CLIENT_APP_ACCESS_TOKEN="));
        assertTrue(output.contains("client-app issue-runtime-key ok"));
        assertTrue(output.contains("stored=NAVI_CLIENT_APP_KEY,NAVI_CLIENT_APP_SECRET,NAVI_CLIENT_APP_ACCESS_TOKEN"));
        assertTrue(output.contains("credentialId=runtime-cred-1"));
        assertTrue(output.contains("clientAppKey=cak-...-key sha256="));
        assertTrue(output.contains("clientAppKeySha256="));
        assertTrue(output.contains("clientAppSecretSha256="));
        assertTrue(output.contains("rotateRuntimeCredential=true"));
        assertFalse(output.contains("cak-secret-runtime-key"));
        assertFalse(output.contains("cas-secret-runtime-secret"));
        assertFalse(output.contains("naa-secret-admin-key"));
    }

    @Test
    void clientAppIssueRuntimeKeyRejectsUnignoredTenantProfileBeforeIssuing() throws Exception {
        Path upstreamProfile = tempDir.resolve("upstream.env");
        Files.writeString(upstreamProfile, """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "client-app", "issue-runtime-key",
                "--profile", upstreamProfile.toString(),
                "--client-app-id", "capp-tenant-a",
                "--tenant-profile", "tenant.env",
                "--write-profile"}, Map.of());

        assertEquals(2, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("not git-ignored"));
        assertNull(lastPath);
    }

    @Test
    void ensureGrantUsesControlPlaneCredentialAndDoesNotPrintTokens() {
        responseOverride = "{\"clientAppId\":\"app-1\",\"upstreamUserId\":\"u-1\",\"status\":\"ENABLED\"}";
        Map<String, String> env = env("CONTROL_ENV", "control-key-secret", "USER_TOKEN_ENV", "staff-token-secret");

        int code = run(new String[]{"upstream", "ensure-grant",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--client-app-id", "app-1",
                "--upstream-user-id", "u-1",
                "--control-api-key-env", "CONTROL_ENV",
                "--upstream-user-token-env", "USER_TOKEN_ENV"}, env);

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertNull(lastAuthorizationHeader);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertEquals("/api/v1/business-agent/client-apps/app-1/upstream-users", lastPath);
        assertTrue(lastBody.contains("\"upstreamUserToken\":\"staff-token-secret\""));
        assertFalse(output.contains("control-key-secret"));
        assertFalse(output.contains("staff-token-secret"));
        assertTrue(output.contains("ensure-grant ok"));
    }

    @Test
    void ensureGrantAllowsMissingUpstreamUserToken() {
        responseOverride = "{\"clientAppId\":\"app-1\",\"upstreamUserId\":\"u-1\",\"status\":\"ENABLED\"}";
        Map<String, String> env = env("NAVI_CONTROL_API_KEY", "control-key-secret");

        int code = run(new String[]{"upstream", "ensure-grant",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--client-app-id", "app-1",
                "--upstream-user-id", "u-1"}, env);

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertNull(lastAuthorizationHeader);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertEquals("/api/v1/business-agent/client-apps/app-1/upstream-users", lastPath);
        assertTrue(lastBody.contains("\"upstreamUserId\":\"u-1\""));
        assertFalse(output.contains("control-key-secret"));
        assertTrue(output.contains("ensure-grant ok"));
    }

    @Test
    void ensureGrantAcceptsLegacyTmsStaffTokenAlias() {
        responseOverride = "{\"clientAppId\":\"app-1\",\"upstreamUserId\":\"u-1\",\"status\":\"ENABLED\"}";
        Map<String, String> env = env(
                "NAVI_CONTROL_API_KEY", "control-key-secret",
                "TMS_STAFF_SESSION_TOKEN", "legacy-staff-token-secret");

        int code = run(new String[]{"upstream", "ensure-grant",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--client-app-id", "app-1",
                "--upstream-user-id", "u-1"}, env);

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"upstreamUserToken\":\"legacy-staff-token-secret\""));
        assertFalse(output.contains("control-key-secret"));
        assertFalse(output.contains("legacy-staff-token-secret"));
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
    void askSendsModelConfigIdFromEnvInTopLevelAndMetadata() {
        responseOverride = "{\"code\":0,\"data\":{\"taskId\":\"task-1\",\"status\":\"SUBMITTED\",\"contextId\":\"ctx-1\"}}";

        int code = run(new String[]{"upstream", "ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "hello"}, env("NAVI_MODEL_CONFIG_ID", "model-e2e"));

        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/ask", lastPath);
        assertTrue(lastBody.contains("\"modelConfigId\":\"model-e2e\""));
        assertTrue(lastBody.contains("\"metadata\""));
    }

    @Test
    void askSendsModelVariantFromEnvInTopLevelAndMetadata() {
        responseOverride = "{\"code\":0,\"data\":{\"taskId\":\"task-1\",\"status\":\"SUBMITTED\",\"contextId\":\"ctx-1\"}}";

        int code = run(new String[]{"upstream", "ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "hello"}, env("NAVI_MODEL_VARIANT", "opus"));

        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/ask", lastPath);
        assertTrue(lastBody.contains("\"modelVariant\":\"opus\""));
        assertTrue(lastBody.contains("\"metadata\""));
        assertTrue(lastBody.contains("\"model\":\"opus\""));
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
    void messagesPollPrintsFailedDiagnosticsAndRedactsSecrets() {
        responseOverride = "__MESSAGES_FAILED_DIAGNOSTICS__";

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
        assertTrue(output.contains("messages=0"));
        assertTrue(output.contains("taskStatus=FAILED"));
        assertTrue(output.contains("providerTaskId=wt-1"));
        assertTrue(output.contains("workerTaskId=wt-1"));
        assertTrue(output.contains("lastAckedSeq=0"));
        assertTrue(output.contains("modelConfigId=model-codex"));
        assertTrue(output.contains("modelConfigSource=REQUESTED_MODEL_GRANT"));
        assertTrue(output.contains("workerBackend=OPENAI_CODEX"));
        assertTrue(output.contains("providerType=codex-worker"));
        assertTrue(output.contains("taskSource=PLATFORM"));
        assertTrue(output.contains("workerSource=WORKING_DIRECTORY:USER_PRIVATE"));
        assertTrue(output.contains("backendSource=MODEL_CONFIG_GRANT"));
        assertTrue(output.contains("failureStage=PROVIDER_API"));
        assertTrue(output.contains("failureSummary=Provider API rejected api_key=[REDACTED]"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void messagesRejectsImplicitProfileAgentCode() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve("upstream.env"), """
                NAVI_AGENT_CODE=tms-agent-v305
                NAVI_CLIENT_APP_KEY=cak-test
                NAVI_CLIENT_APP_ACCESS_TOKEN=cat-runtime-secret
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "messages",
                "--base-url", baseUrl(),
                "--task-id", "task-1"}, Map.of());

        assertEquals(2, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("messages requires --agent-code"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void sessionsUseBusinessAgentEndpointAndProfileUpstreamUserId() throws Exception {
        responseOverride = """
                {"code":0,"data":{
                  "sessions":[{"contextId":"ctx-1","status":"ACTIVE","latestTaskId":"task-1","agentId":"agent-1"}],
                  "hasMore":false
                }}
                """;
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve("upstream.env"), "NAVI_UPSTREAM_USER_ID=u-1\n", StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "sessions",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--limit", "10"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/business-agent/sessions?limit=10", lastPath);
        assertEquals("GET", lastMethod);
        assertEquals("u-1", lastUpstreamUserIdHeader);
        assertTrue(output.contains("session contextId=ctx-1"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void sessionMessagesUseBusinessAgentEndpointAndParseStringMetadata() {
        responseOverride = """
                {"code":0,"data":{
                  "contextId":"ctx-1",
                  "messages":[{"messageId":"m-1","contextId":"ctx-1","role":"ASSISTANT","content":"done","metadata":"{\\"type\\":\\"TEXT_COMPLETE\\"}"}],
                  "hasMore":false
                }}
                """;

        int code = run(new String[]{"upstream", "session-messages",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--upstream-user-id", "u-1",
                "--context-id", "ctx-1",
                "--limit", "5"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/business-agent/sessions/ctx-1/messages?limit=5", lastPath);
        assertEquals("u-1", lastUpstreamUserIdHeader);
        assertTrue(output.contains("message id=m-1"));
        assertTrue(output.contains("content=done"));
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
                  "defaultModelConfigId":"model-default",
                  "effectiveModelConfigId":"model-1",
                  "effectiveModelName":"qwen-plus",
                  "effectiveWorkerBackend":"LANGGRAPH_BIZ",
                  "modelConfigSource":"REQUESTED_MODEL_GRANT",
                  "modelCategory":"GENERAL",
                  "agentId":"agent-1",
                  "agentOwnerType":"CLIENT_APP",
                  "agentOwnerId":"app-1",
                  "agentSource":"AGENT:CLIENT_APP",
                  "skillId":"agent-1",
                  "workerPoolId":"pool-1",
                  "workerPoolOwnerType":"UPSTREAM_SYSTEM",
                  "workerPoolOwnerId":"usys-1",
                  "workerPoolSource":"WORKER_POOL:UPSTREAM_SYSTEM",
                  "internalWorkerPoolId":"pool-1",
                  "internalWorkerPoolOwnerType":"UPSTREAM_SYSTEM",
                  "internalWorkerPoolOwnerId":"usys-1",
                  "internalWorkerPoolSource":"WORKER_POOL:UPSTREAM_SYSTEM",
                  "requestedDirectoryId":"dir-override",
                  "defaultDirectoryId":"dir-default",
                  "effectiveDirectoryId":"dir-override",
                  "effectivePhysicalWorkerId":"worker-1",
                  "workspaceScope":"USER_PRIVATE",
                  "workspaceResolverType":"STATIC_ROOT",
                  "workspaceReadOnly":false,
                  "workspaceSource":"WORKING_DIRECTORY:USER_PRIVATE",
                  "physicalWorkerDiagnostic":{
                    "physicalWorkerId":"worker-1",
                    "workerName":"wsl-codex-worker",
                    "workerBackend":"LANGGRAPH_BIZ",
                    "baseUrl":"http://127.0.0.1:3065/runtime",
                    "status":"ENABLED",
                    "healthStatus":"HEALTHY",
                    "version":"1.2.3",
                    "hostname":"dev-wsl",
                    "lastHeartbeat":"2026-05-25T10:00:00",
                    "source":"WORKING_DIRECTORY:USER_PRIVATE",
                    "executionWorker":true,
                    "directoryWorker":true
                  },
                  "physicalWorkerDiagnostics":[
                    {
                      "role":"biz",
                      "physicalWorkerId":"worker-1",
                      "workerName":"wsl-biz-worker",
                      "workerBackend":"LANGGRAPH_BIZ",
                      "baseUrl":"http://127.0.0.1:3161/runtime",
                      "status":"ENABLED",
                      "healthStatus":"HEALTHY",
                      "version":"1.2.3",
                      "hostname":"dev-wsl",
                      "lastHeartbeat":"2026-05-25T10:00:00",
                      "source":"BIZ_WORKER_IDENTITY",
                      "executionWorker":true,
                      "directoryWorker":false
                    },
                    {
                      "role":"claudeCode",
                      "physicalWorkerId":"worker-claude",
                      "workerName":"wsl-claude-worker",
                      "workerBackend":"CLAUDE_CODE",
                      "baseUrl":"http://127.0.0.1:3131",
                      "status":"ONLINE",
                      "version":"1.0.8",
                      "hostname":"dev-wsl",
                      "source":"WORKING_DIRECTORY:USER_PRIVATE",
                      "executionWorker":false,
                      "directoryWorker":true
                    },
                    {
                      "role":"codex",
                      "physicalWorkerId":"worker-claude",
                      "workerName":"wsl-claude-worker",
                      "workerBackend":"OPENAI_CODEX",
                      "baseUrl":"http://127.0.0.1:3151/runtime",
                      "status":"ONLINE",
                      "version":"1.0.8",
                      "hostname":"dev-wsl",
                      "source":"CLAUDE_WORKER_CODEX_CONFIG",
                      "executionWorker":true,
                      "directoryWorker":false
                    }
                  ],
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
                "--model-config-id", "model-1",
                "--directory-id", "dir-override"}, env);

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/preflight", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("cak-test", lastClientAppKeyHeader);
        assertEquals("cat-runtime-secret", lastClientAppAccessTokenHeader);
        assertTrue(lastBody.contains("\"upstreamUserId\":\"u-1\""));
        assertTrue(lastBody.contains("\"modelConfigId\":\"model-1\""));
        assertTrue(lastBody.contains("\"directoryId\":\"dir-override\""));
        assertTrue(output.contains("verify-agent-readiness OK"));
        assertTrue(output.contains("defaultModelConfigId=model-default"));
        assertTrue(output.contains("effectiveModelName=qwen-plus"));
        assertTrue(output.contains("effectiveWorkerBackend=LANGGRAPH_BIZ"));
        assertTrue(output.contains("modelConfigSource=REQUESTED_MODEL_GRANT"));
        assertTrue(output.contains("agent agentId=agent-1 ownerType=CLIENT_APP ownerId=app-1 source=AGENT:CLIENT_APP skillId=agent-1"));
        assertTrue(output.contains("physicalWorker physicalWorkerId=worker-1 workerBackend=LANGGRAPH_BIZ source=WORKING_DIRECTORY:USER_PRIVATE"));
        assertTrue(output.contains("workerName=wsl-codex-worker"));
        assertTrue(output.contains("baseUrl=http://127.0.0.1:3065/runtime"));
        assertTrue(output.contains("healthStatus=HEALTHY"));
        assertTrue(output.contains("version=1.2.3"));
        assertTrue(output.contains("hostname=dev-wsl"));
        assertTrue(output.contains("usedAs=execution,directory"));
        assertTrue(output.contains("workerRole role=biz physicalWorkerId=worker-1 workerBackend=LANGGRAPH_BIZ source=BIZ_WORKER_IDENTITY"));
        assertTrue(output.contains("workerRole role=claudeCode physicalWorkerId=worker-claude workerBackend=CLAUDE_CODE source=WORKING_DIRECTORY:USER_PRIVATE"));
        assertTrue(output.contains("workerRole role=codex physicalWorkerId=worker-claude workerBackend=OPENAI_CODEX source=CLAUDE_WORKER_CODEX_CONFIG"));
        assertTrue(output.contains("baseUrl=http://127.0.0.1:3151/runtime"));
        assertTrue(output.contains("baseUrl=http://127.0.0.1:3131"));
        assertTrue(output.contains("internalRoute workerPoolId=pool-1 ownerType=UPSTREAM_SYSTEM ownerId=usys-1 source=WORKER_POOL:UPSTREAM_SYSTEM"));
        assertTrue(output.contains("workspace requestedDirectoryId=dir-override defaultDirectoryId=dir-default effectiveDirectoryId=dir-override physicalWorkerId=worker-1 scope=USER_PRIVATE resolverType=STATIC_ROOT readOnly=false source=WORKING_DIRECTORY:USER_PRIVATE"));
        assertTrue(output.contains("check AGENT_REGISTERED=OK"));
        assertTrue(output.contains("skillArtifactTreeUrl=http://localhost:8112/api/v1/open/skills/agent-1/files/tree"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void inspectRuntimeUsesPreflightAndPrintsResolvedResourceSources() {
        responseOverride = """
                {"code":0,"data":{
                  "overallStatus":"OK",
                  "baseUrl":"http://localhost:8112",
                  "clientAppId":"app-1",
                  "agentCode":"agent-1",
                  "upstreamUserId":"u-1",
                  "effectiveModelConfigId":"model-default",
                  "effectiveModelName":"qwen-plus",
                  "effectiveWorkerBackend":"LANGGRAPH_BIZ",
                  "modelConfigSource":"DEFAULT_MODEL_GRANT",
                  "agentId":"agent-1",
                  "agentOwnerType":"CLIENT_APP",
                  "agentOwnerId":"app-1",
                  "agentSource":"AGENT:CLIENT_APP",
                  "skillId":"agent-1",
                  "workerPoolId":"pool-1",
                  "workerPoolOwnerType":"PLATFORM",
                  "workerPoolOwnerId":"platform",
                  "workerPoolSource":"WORKER_POOL:PLATFORM",
                  "internalWorkerPoolId":"pool-1",
                  "internalWorkerPoolOwnerType":"PLATFORM",
                  "internalWorkerPoolOwnerId":"platform",
                  "internalWorkerPoolSource":"WORKER_POOL:PLATFORM",
                  "checks":[
                    {"code":"RUNTIME_AGENT_RESOURCE","status":"OK"},
                    {"code":"MODEL_CONFIG_GRANT","status":"OK"}
                  ]
                }}
                """;

        int code = run(new String[]{"upstream", "inspect", "runtime",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent-code", "agent-1",
                "--upstream-user-id", "u-1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/preflight", lastPath);
        assertTrue(output.contains("inspect runtime OK"));
        assertTrue(output.contains("modelConfigSource=DEFAULT_MODEL_GRANT"));
        assertTrue(output.contains("internalRoute workerPoolId=pool-1 ownerType=PLATFORM ownerId=platform source=WORKER_POOL:PLATFORM"));
        assertTrue(output.contains("check RUNTIME_AGENT_RESOURCE=OK"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void ownerSmokeValidatesProfileReadinessAndResolvedRuntimeResources() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_CLIENT_APP_KEY=cak-test
                NAVI_CLIENT_APP_SECRET=cas-secret-value
                NAVI_AGENT_CODE=agent-1
                NAVI_UPSTREAM_USER_ID=u-1
                NAVI_MODEL_CONFIG_ID=model-env
                NAVI_DIRECTORY_ID=dir-env
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        responseOverride = "__RUNTIME_THEN_READINESS__";

        int code = run(new String[]{"upstream", "owner-smoke"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/preflight", lastPath);
        assertTrue(lastBody.contains("\"upstreamUserId\":\"u-1\""));
        assertTrue(lastBody.contains("\"modelConfigId\":\"model-env\""));
        assertTrue(lastBody.contains("\"directoryId\":\"dir-env\""));
        assertTrue(output.contains("owner-smoke profileGitIgnored=true"));
        assertTrue(output.contains("owner-smoke readiness OK"));
        assertTrue(output.contains("modelConfigSource=REQUESTED_MODEL_GRANT"));
        assertTrue(output.contains("agent agentId=agent-1 ownerType=CLIENT_APP ownerId=app-1 source=AGENT:CLIENT_APP"));
        assertTrue(output.contains("physicalWorker physicalWorkerId=worker-1 workerBackend=LANGGRAPH_BIZ"));
        assertTrue(output.contains("internalRoute workerPoolId=pool-1 ownerType=UPSTREAM_SYSTEM ownerId=usys-1 source=WORKER_POOL:UPSTREAM_SYSTEM"));
        assertTrue(output.contains("workspace requestedDirectoryId=dir-env defaultDirectoryId=(empty) effectiveDirectoryId=dir-env physicalWorkerId=worker-1"));
        assertTrue(output.contains("owner-smoke resources OK"));
        assertTrue(output.contains("owner-smoke ready"));
        assertFalse(output.contains("cas-secret-value"));
    }

    @Test
    void ownerSmokeRequiresDirectoryUnlessExplicitlyDisabled() {
        responseOverride = """
                {"code":0,"data":{
                  "overallStatus":"OK",
                  "agentCode":"agent-1",
                  "upstreamUserId":"u-1",
                  "effectiveModelConfigId":"model-env",
                  "effectiveWorkerBackend":"LANGGRAPH_BIZ",
                  "agentId":"agent-1",
                  "workerPoolId":"pool-1",
                  "checks":[{"code":"AGENT_REGISTERED","status":"OK"}]
                }}
                """;

        int failed = run(new String[]{"upstream", "owner-smoke",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent-code", "agent-1",
                "--upstream-user-id", "u-1"}, Map.of());
        assertEquals(2, failed);
        assertTrue(stdout.toString(StandardCharsets.UTF_8)
                .contains("owner-smoke resources FAIL missing=effectiveDirectoryId,effectivePhysicalWorkerId"));

        reset();
        responseOverride = """
                {"code":0,"data":{
                  "overallStatus":"OK",
                  "agentCode":"agent-1",
                  "upstreamUserId":"u-1",
                  "effectiveModelConfigId":"model-env",
                  "effectiveWorkerBackend":"LANGGRAPH_BIZ",
                  "agentId":"agent-1",
                  "workerPoolId":"pool-1",
                  "checks":[{"code":"AGENT_REGISTERED","status":"OK"}]
                }}
                """;
        int passed = run(new String[]{"upstream", "owner-smoke",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent-code", "agent-1",
                "--upstream-user-id", "u-1",
                "--no-directory-required"}, Map.of());

        assertEquals(0, passed);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("owner-smoke resources OK"));
    }

    @Test
    void ownerSmokeFailsWhenWorkerHostExecutionRoleDoesNotMatchBackend() {
        responseOverride = """
                {"code":0,"data":{
                  "overallStatus":"OK",
                  "agentCode":"agent-1",
                  "upstreamUserId":"u-1",
                  "effectiveModelConfigId":"model-env",
                  "effectiveWorkerBackend":"LANGGRAPH_BIZ",
                  "agentId":"agent-1",
                  "effectiveDirectoryId":"dir-env",
                  "effectivePhysicalWorkerId":"worker-1",
                  "physicalWorkerDiagnostics":[
                    {
                      "role":"biz",
                      "physicalWorkerId":"worker-1",
                      "workerBackend":"LANGGRAPH_BIZ",
                      "source":"AGENT_DEFAULT_DIRECTORY:CLIENT_APP_SHARED",
                      "executionWorker":true,
                      "directoryWorker":true
                    }
                  ],
                  "checks":[{"code":"AGENT_REGISTERED","status":"OK"}]
                }}
                """;

        int code = run(new String[]{"upstream", "owner-smoke",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent-code", "agent-1",
                "--upstream-user-id", "u-1"}, Map.of());

        assertEquals(2, code);
        assertTrue(stdout.toString(StandardCharsets.UTF_8)
                .contains("owner-smoke resources FAIL missing=workerRole:biz:BIZ_WORKER_IDENTITY"));
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
                  "contextVisibility":"summary",
                  "markdownBody":"# Agent One"
                }
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "skill", "sync",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--scope", "client-app-public",
                "--manifest", manifest.getFileName().toString()}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/business-agent/skill-bundles/sync", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastAuthorizationHeader);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"clientAppId\":\"app-1\""));
        assertTrue(lastBody.contains("\"scope\":\"CLIENT_APP_PUBLIC\""));
        assertTrue(lastBody.contains("\"contextVisibility\":\"summary\""));
        assertTrue(output.contains("skill sync ok"));
        assertTrue(output.contains("scope=CLIENT_APP_PUBLIC"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void agentSyncUsesControlPlaneCredentialAndManifest() throws Exception {
        responseOverride = """
                {"code":0,"data":{
                  "tenantId":"tenant-1",
                  "clientAppId":"app-1",
                  "agentId":"agent-1",
                  "skillId":"agent-1",
                  "workerId":"worker-1",
                  "defaultModelConfigId":"model-1",
                  "skillBundle":{"status":"ENABLED"}
                }}
                """;
        Path manifest = tempDir.resolve("agent-bundle.json");
        Files.writeString(manifest, """
                {
                  "agentId":"agent-1",
                  "name":"Agent One",
                  "workerId":"worker-1",
                  "defaultModelConfigId":"model-1",
                  "contextVisibility":"summary",
                  "markdownBody":"# Agent One"
                }
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "agent", "sync",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--manifest", manifest.getFileName().toString()}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/business-agent/agent-bundles/sync", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastAuthorizationHeader);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"clientAppId\":\"app-1\""));
        assertTrue(lastBody.contains("\"workerId\":\"worker-1\""));
        assertTrue(lastBody.contains("\"contextVisibility\":\"summary\""));
        assertTrue(output.contains("agent sync ok"));
        assertTrue(output.contains("agentId=agent-1"));
        assertTrue(output.contains("skillBundleStatus=ENABLED"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void agentBindWorkerUsesControlPlaneCredential() {
        responseOverride = """
                {"code":0,"data":{
                  "id":51,
                  "tenantId":"tenant-1",
                  "clientAppId":"app-1",
                  "agentId":"agent-1",
                  "workerPoolId":"pool-1",
                  "workerPoolName":"LangGraph Pool",
                  "workerBackend":"LANGGRAPH_BIZ",
                  "workerPoolOwnerType":"UPSTREAM_SYSTEM",
                  "defaultWorkerPool":false,
                  "status":"ENABLED"
                }}
                """;

        int code = run(new String[]{"upstream", "agent", "bind-worker",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--agent-code", "agent-1",
                "--worker-pool-id", "pool-1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/agents/agent-1/worker-bindings", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"workerPoolId\":\"pool-1\""));
        assertTrue(output.contains("agent bind-worker ok"));
        assertTrue(output.contains("\"workerPoolId\""));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void agentSystemSetDefaultWorkerUsesUpstreamAdminCredential() {
        responseOverride = """
                {"code":0,"data":{
                  "id":52,
                  "tenantId":"tenant-2",
                  "agentId":"agent-1",
                  "workerPoolId":"pool-system",
                  "workerPoolName":"System Pool",
                  "workerBackend":"LANGGRAPH_BIZ",
                  "workerPoolOwnerType":"UPSTREAM_SYSTEM",
                  "defaultWorkerPool":true,
                  "status":"ENABLED"
                }}
                """;

        int code = run(new String[]{"upstream", "agent", "system-set-default-worker",
                "--base-url", baseUrl(),
                "--admin-api-key", "admin-key-secret",
                "--target-tenant-id", "tenant-2",
                "--agent-code", "agent-1",
                "--worker-pool-id", "pool-system"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-admin/agents/agent-1/worker-bindings/default?targetTenantId=tenant-2", lastPath);
        assertEquals("PUT", lastMethod);
        assertEquals("admin-key-secret", lastUpstreamAdminKeyHeader);
        assertTrue(lastBody.contains("\"workerPoolId\":\"pool-system\""));
        assertTrue(output.contains("agent system-set-default-worker ok"));
        assertTrue(output.contains("\"defaultWorkerPool\""));
        assertFalse(output.contains("admin-key-secret"));
    }

    @Test
    void agentSetDefaultModelSerializesJavaTimeResponse() {
        responseOverride = """
                {"code":0,"data":{
                  "id":61,
                  "tenantId":"tenant-1",
                  "clientAppId":"app-1",
                  "agentId":"agent-1",
                  "modelConfigId":"model-1",
                  "modelConfigName":"Qwen Plus",
                  "workerBackend":"LANGGRAPH_BIZ",
                  "defaultModel":true,
                  "createdAt":"2026-05-24T12:34:56"
                }}
                """;

        int code = run(new String[]{"upstream", "agent", "set-default-model",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--agent-code", "agent-1",
                "--model-config-id", "model-1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/agents/agent-1/model-bindings/default", lastPath);
        assertEquals("PUT", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(output.contains("agent set-default-model ok"));
        assertTrue(output.contains("\"createdAt\" : \"2026-05-24T12:34:56\""));
        assertFalse(output.contains("Java 8 date/time type"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void agentSetDefaultWorkspaceSerializesJavaTimeResponse() {
        responseOverride = """
                {"code":0,"data":{
                  "id":62,
                  "tenantId":"tenant-1",
                  "clientAppId":"app-1",
                  "agentId":"agent-1",
                  "directoryId":"dir-1",
                  "projectName":"School Sim",
                  "workspaceScope":"CLIENT_APP_SHARED",
                  "defaultDirectory":true,
                  "enabled":true,
                  "createdAt":"2026-05-24T12:34:56"
                }}
                """;

        int code = run(new String[]{"upstream", "agent", "set-default-workspace",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--agent-code", "agent-1",
                "--directory-id", "dir-1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/agents/agent-1/workspace-bindings/default", lastPath);
        assertEquals("PUT", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(output.contains("agent set-default-workspace ok"));
        assertTrue(output.contains("\"createdAt\" : \"2026-05-24T12:34:56\""));
        assertFalse(output.contains("Java 8 date/time type"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void functionImportUsesControlPlaneCredentialAndManifest() throws Exception {
        responseOverride = "{\"code\":0,\"data\":{}}";
        Path manifest = tempDir.resolve("function-manifest.json");
        Files.writeString(manifest, """
                {
                  "functionId":"order.close.apply",
                  "version":"v1",
                  "domain":"tms",
                  "name":"Apply close order",
                  "riskLevel":"HIGH",
                  "approvalRequired":true,
                  "inputSchemaJson":"{}"
                }
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "function", "import",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--manifest", manifest.getFileName().toString()}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/business-agent/functions/import", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastAuthorizationHeader);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"functionId\":\"order.close.apply\""));
        assertTrue(output.contains("function import ok"));
        assertTrue(output.contains("functionId=order.close.apply"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void functionGrantUsesClientAppScopedControlCredential() throws Exception {
        responseOverride = """
                {"code":0,"data":{
                  "grantId":"fg-1",
                  "clientAppId":"app-1",
                  "functionId":"order.close.apply",
                  "version":"v1",
                  "status":"ENABLED"
                }}
                """;

        int code = run(new String[]{"upstream", "function", "grant",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--function-id", "order.close.apply",
                "--version", "v1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/business-agent/client-apps/app-1/function-grants", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"functionId\":\"order.close.apply\""));
        assertTrue(lastBody.contains("\"version\":\"v1\""));
        assertTrue(output.contains("function grant ok"));
        assertTrue(output.contains("grantId=fg-1"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void functionVisibleListsGrantedFunctions() throws Exception {
        responseOverride = """
                {"code":0,"data":[
                  {
                    "functionId":"order.close.apply",
                    "version":"v1",
                    "domain":"tms",
                    "name":"Apply close order",
                    "riskLevel":"HIGH",
                    "approvalRequired":true,
                    "idempotencyRequired":true
                  }
                ]}
                """;

        int code = run(new String[]{"upstream", "function", "visible",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/business-agent/client-apps/app-1/visible-functions", lastPath);
        assertEquals("GET", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(output.contains("functionVisibleCount=1"));
        assertTrue(output.contains("functionId=order.close.apply"));
        assertTrue(output.contains("approvalRequired=true"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void routeSetUsesClientAppScopedControlCredential() {
        responseOverride = """
                {"code":0,"data":{
                  "id":7,
                  "clientAppId":"app-1",
                  "upstreamRef":"world-sim",
                  "baseUrl":"http://localhost:13080",
                  "userTokenHeader":"X-World-Sim-Token",
                  "status":"ENABLED"
                }}
                """;

        int code = run(new String[]{"upstream", "route", "set",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--upstream-ref", "world-sim",
                "--url", "http://localhost:13080",
                "--user-token-header", "X-World-Sim-Token"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/business-agent/client-apps/app-1/upstream-routes/world-sim", lastPath);
        assertEquals("PUT", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"baseUrl\":\"http://localhost:13080\""));
        assertTrue(lastBody.contains("\"userTokenHeader\":\"X-World-Sim-Token\""));
        assertTrue(output.contains("route set ok"));
        assertTrue(output.contains("upstreamRef=world-sim"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void routeListPrintsRegisteredRoutes() {
        responseOverride = """
                {"code":0,"data":[
                  {
                    "id":7,
                    "clientAppId":"app-1",
                    "upstreamRef":"world-sim",
                    "baseUrl":"http://localhost:13080",
                    "userTokenHeader":"X-World-Sim-Token",
                    "status":"ENABLED"
                  }
                ]}
                """;

        int code = run(new String[]{"upstream", "route", "list",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/business-agent/client-apps/app-1/upstream-routes", lastPath);
        assertEquals("GET", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(output.contains("upstreamRouteCount=1"));
        assertTrue(output.contains("upstreamRef=world-sim"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void routeStatusDisablesRoute() {
        responseOverride = """
                {"code":0,"data":{
                  "id":7,
                  "clientAppId":"app-1",
                  "upstreamRef":"world-sim",
                  "baseUrl":"http://localhost:13080",
                  "status":"DISABLED"
                }}
                """;

        int code = run(new String[]{"upstream", "route", "status",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--upstream-ref", "world-sim",
                "--status", "DISABLED"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/business-agent/client-apps/app-1/upstream-routes/world-sim/status?status=DISABLED", lastPath);
        assertEquals("PUT", lastMethod);
        assertTrue(output.contains("route status ok"));
        assertTrue(output.contains("status=DISABLED"));
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
                  "contextVisibility":"summary",
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
        assertTrue(lastBody.contains("\"contextVisibility\":\"summary\""));
        assertTrue(output.contains("accountId=staff-1"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void skillClearPublicUsesControlPlaneCredentialAndDryRun() {
        responseOverride = """
                {"code":0,"data":{
                  "scope":"CLIENT_APP_PUBLIC",
                  "clientAppId":"app-1",
                  "dryRun":true,
                  "executed":false,
                  "skillIds":["old-skill"],
                  "matchedSkillCount":1,
                  "skillBundleCount":1,
                  "legacySkillCount":1,
                  "clientAppSkillGrantCount":1,
                  "skillFunctionAllowlistCount":2,
                  "materializedBundleCount":1,
                  "cacheCount":0,
                  "workerClearStatus":"SKIPPED_DRY_RUN"
                }}
                """;

        int code = run(new String[]{"upstream", "skill", "clear-public",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--skill-id", "old-skill",
                "--dry-run"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/business-agent/skill-bundles/clear-public", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"clientAppId\":\"app-1\""));
        assertTrue(lastBody.contains("\"skillId\":\"old-skill\""));
        assertTrue(lastBody.contains("\"dryRun\":true"));
        assertTrue(output.contains("skill clear-public ok"));
        assertTrue(output.contains("matchedSkillCount=1"));
        assertTrue(output.contains("matchedSkillId=old-skill"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void skillClearAccountRequiresYesWhenNotDryRun() {
        int code = run(new String[]{"upstream", "skill", "clear-account",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--account-id", "staff-1",
                "--skill-id", "old-skill"}, Map.of());

        assertEquals(2, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("requires --dry-run or --yes"));
        assertNull(lastPath);
    }

    @Test
    void skillClearAccountUsesControlPlaneCredentialAndYes() {
        responseOverride = """
                {"code":0,"data":{
                  "scope":"ACCOUNT_PRIVATE",
                  "clientAppId":"app-1",
                  "accountId":"staff-1",
                  "skillId":"old-skill",
                  "dryRun":false,
                  "executed":true,
                  "skillIds":["old-skill"],
                  "matchedSkillCount":1,
                  "skillBundleCount":1,
                  "legacySkillCount":0,
                  "clientAppSkillGrantCount":0,
                  "skillFunctionAllowlistCount":0,
                  "materializedBundleCount":1,
                  "cacheCount":0,
                  "workerClearStatus":"CLEARED",
                  "workerStatusCode":200
                }}
                """;

        int code = run(new String[]{"upstream", "skill", "clear-account",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--account-id", "staff-1",
                "--skill-id", "old-skill",
                "--yes"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/business-agent/skill-bundles/clear-account", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"accountId\":\"staff-1\""));
        assertTrue(lastBody.contains("\"dryRun\":false"));
        assertTrue(output.contains("skill clear-account ok"));
        assertTrue(output.contains("executed=true"));
        assertTrue(output.contains("workerClearStatus=CLEARED"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void modelGrantsListsClientAppModelGrantsWithControlCredential() {
        responseOverride = """
                {"code":0,"data":[
                  {
                    "id":11,
                    "clientAppId":"app-1",
                    "modelConfigId":"model-live",
                    "modelConfigName":"Live Model",
                    "workerBackend":"LANGGRAPH_BIZ",
                    "status":"ENABLED",
                    "isDefault":true,
                    "grantScope":"CLIENT_APP"
                  },
                  {
                    "id":12,
                    "clientAppId":"app-1",
                    "modelConfigId":"model-e2e",
                    "modelConfigName":"E2E Model",
                    "workerBackend":"LANGGRAPH_BIZ",
                    "status":"ENABLED",
                    "isDefault":false,
                    "grantScope":"CLIENT_APP"
                  }
                ]}
                """;

        int code = run(new String[]{"upstream", "model", "grants",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/model-config-grants", lastPath);
        assertEquals("GET", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(output.contains("modelGrantCount=2"));
        assertTrue(output.contains("modelConfigId=model-live"));
        assertTrue(output.contains("default=true"));
        assertTrue(output.contains("modelConfigId=model-e2e"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void modelGrantUsesControlCredentialAndCanWriteProfile() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Path profile = profileDir.resolve("upstream.env");
        Files.writeString(profile, "NAVI_BASE_URL=" + baseUrl() + "\nNAVI_CLIENT_APP_ID=app-1\n", StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{
                  "id":21,
                  "clientAppId":"app-1",
                  "modelConfigId":"model-live",
                  "modelConfigName":"Live Model",
                  "workerBackend":"LANGGRAPH_BIZ",
                  "status":"ENABLED",
                  "isDefault":true,
                  "grantScope":"CLIENT_APP"
                }}
                """;

        int code = run(new String[]{"upstream", "model", "grant",
                "--control-api-key", "control-key-secret",
                "--model-config-id", "model-live",
                "--set-default",
                "--grant-scope", "CLIENT_APP",
                "--write-profile"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        String profileContent = Files.readString(profile, StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/model-config-grants", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"modelConfigId\":\"model-live\""));
        assertTrue(lastBody.contains("\"isDefault\":true"));
        assertTrue(lastBody.contains("\"grantScope\":\"CLIENT_APP\""));
        assertTrue(output.contains("model grant ok"));
        assertTrue(output.contains("modelConfigId=model-live"));
        assertTrue(output.contains("stored=NAVI_MODEL_CONFIG_ID"));
        assertTrue(profileContent.contains("NAVI_MODEL_CONFIG_ID=model-live"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void modelSetDefaultCanResolveGrantIdByModelConfigId() {
        responseOverride = "__MODEL_GRANTS_THEN_DEFAULT__";

        int code = run(new String[]{"upstream", "model", "set-default",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--model-config-id", "model-target"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals(List.of(
                "/api/v1/client-apps/app-1/model-config-grants",
                "/api/v1/client-apps/app-1/model-config-grants/31/default"
        ), requestPaths);
        assertEquals("PUT", lastMethod);
        assertTrue(output.contains("model set-default ok"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void modelCreateUsesControlCredentialAndApiKeyEnv() {
        responseOverride = """
                {"code":0,"data":{
                  "id":41,
                  "clientAppId":"app-1",
                  "modelConfigId":"model-owned",
                  "modelConfigName":"Upstream GPT",
                  "workerBackend":"LANGGRAPH_BIZ",
                  "status":"ENABLED",
                  "isDefault":true,
                  "grantScope":"CLIENT_APP_OWNED"
                }}
                """;

        int code = run(new String[]{"upstream", "model", "create",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--name", "Upstream GPT",
                "--model-base-url", "https://llm.example/v1",
                "--model-name", "gpt-test",
                "--provider", "openai",
                "--runtime-budget-preset", "generic.128k",
                "--runtime-budget-override-json", "{\"maxOutputTokens\":6144}",
                "--api-key-env", "UPSTREAM_LLM_KEY",
                "--set-default"}, env("UPSTREAM_LLM_KEY", "llm-secret"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/model-configs", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"name\":\"Upstream GPT\""));
        assertTrue(lastBody.contains("\"baseUrl\":\"https://llm.example/v1\""));
        assertTrue(lastBody.contains("\"modelName\":\"gpt-test\""));
        assertTrue(lastBody.contains("\"apiKey\":\"llm-secret\""));
        assertTrue(lastBody.contains("\"NAVI_LLM_PROVIDER\":\"openai\""));
        assertTrue(lastBody.contains("\"runtimeBudgetPresetKey\":\"generic.128k\""));
        assertTrue(lastBody.contains("\"runtimeBudgetOverrideJson\":\"{\\\"maxOutputTokens\\\":6144}\""));
        assertTrue(lastBody.contains("\"setDefault\":true"));
        assertTrue(output.contains("model create ok"));
        assertTrue(output.contains("modelConfigId=model-owned"));
        assertFalse(output.contains("control-key-secret"));
        assertFalse(output.contains("llm-secret"));
    }

    @Test
    void modelCreateAcceptsOpenAiCodexWorkerBackend() {
        responseOverride = """
                {"code":0,"data":{
                  "id":41,
                  "clientAppId":"app-1",
                  "modelConfigId":"model-codex",
                  "modelConfigName":"Upstream Codex",
                  "workerBackend":"OPENAI_CODEX",
                  "status":"ENABLED",
                  "isDefault":true,
                  "grantScope":"CLIENT_APP_OWNED"
                }}
                """;

        int code = run(new String[]{"upstream", "model", "create",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--name", "Upstream Codex",
                "--model-base-url", "https://codex.example/v1",
                "--model-name", "codex-mini",
                "--worker-backend", "OPENAI_CODEX",
                "--api-key-env", "UPSTREAM_LLM_KEY",
                "--set-default"}, env("UPSTREAM_LLM_KEY", "llm-secret"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/model-configs", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"workerBackend\":\"OPENAI_CODEX\""));
        assertTrue(output.contains("model create ok"));
        assertTrue(output.contains("workerBackend=OPENAI_CODEX"));
        assertFalse(output.contains("control-key-secret"));
        assertFalse(output.contains("llm-secret"));
    }

    @Test
    void modelRotateKeyUsesApiKeyEnvWithoutPrintingSecret() {
        responseOverride = """
                {"code":0,"data":{
                  "id":42,
                  "clientAppId":"app-1",
                  "modelConfigId":"model-owned",
                  "modelConfigName":"Upstream GPT",
                  "workerBackend":"LANGGRAPH_BIZ",
                  "status":"ENABLED",
                  "isDefault":true,
                  "grantScope":"CLIENT_APP_OWNED"
                }}
                """;

        int code = run(new String[]{"upstream", "model", "rotate-key",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--model-config-id", "model-owned",
                "--api-key-env", "UPSTREAM_LLM_KEY"}, env("UPSTREAM_LLM_KEY", "new-llm-secret"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/model-configs/model-owned/key", lastPath);
        assertEquals("PUT", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"apiKey\":\"new-llm-secret\""));
        assertTrue(output.contains("model rotate-key ok"));
        assertFalse(output.contains("control-key-secret"));
        assertFalse(output.contains("new-llm-secret"));
    }

    @Test
    void workerCreateUsesUpstreamAdminKeyAndStoresWorkerId() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve(".navigator"));
        Files.writeString(tempDir.resolve(".navigator").resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".navigator").resolve("worker.json"), """
                {"name":"Codex Worker","baseUrl":"http://127.0.0.1:3031","authToken":"worker-secret"}
                """, StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{"workerId":"w-1","name":"Codex Worker","baseUrl":"http://127.0.0.1:3031","status":"ONLINE"}}
                """;

        int code = run(new String[]{"upstream", "worker", "create",
                "--profile", ".navigator/upstream.env",
                "--file", ".navigator/worker.json",
                "--target-tenant-id", "tenant-a",
                "--write-profile"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(tempDir.resolve(".navigator").resolve("upstream.env"), StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-admin/workers?targetTenantId=tenant-a", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(lastBody.contains("\"authToken\":\"worker-secret\""));
        assertTrue(profile.contains("NAVI_WORKER_ID=w-1"));
        assertTrue(output.contains("worker create ok"));
        assertFalse(output.contains("naa-secret-admin-key"));
        assertFalse(output.contains("worker-secret"));
    }

    @Test
    void workerHostApplyCreatesClaudeSuiteAndRegistersBizIdentity() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve(".navigator"));
        Files.writeString(tempDir.resolve(".navigator").resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".navigator").resolve("worker-host.json"), """
                {
                  "workerHostId": "school-sim-wsl",
                  "hostUrl": "http://127.0.0.1",
                  "port": 3131,
                  "install": "ensure",
                  "workers": {
                    "claudeCode": {
                      "enabled": true,
                      "authTokenEnv": "CLAUDE_WORKER_TOKEN"
                    },
                    "codex": {
                      "enabled": true,
                      "port": 3151,
                      "authTokenEnv": "CODEX_WORKER_TOKEN",
                      "model": "gpt-5.5"
                    },
                    "biz": {
                      "enabled": true,
                      "port": 3161,
                      "identityTokenEnv": "BIZ_WORKER_TOKEN",
                      "version": "1.0.2"
                    }
                  }
                }
                """, StandardCharsets.UTF_8);
        responseOverride = "__WORKER_HOST_APPLY__";

        int code = run(new String[]{"upstream", "worker-host", "apply",
                "--profile", ".navigator/upstream.env",
                "--file", ".navigator/worker-host.json",
                "--target-tenant-id", "tenant-a",
                "--write-profile"}, env(
                "CLAUDE_WORKER_TOKEN", "claude-worker-secret",
                "CODEX_WORKER_TOKEN", "codex-worker-secret",
                "BIZ_WORKER_TOKEN", "biz-worker-secret"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(tempDir.resolve(".navigator").resolve("upstream.env"), StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals(List.of(
                "/api/v1/upstream-admin/workers?targetTenantId=tenant-a",
                "/api/v1/upstream-admin/worker-identities"), requestPaths);
        assertTrue(requestBodies.get(0).contains("\"baseUrl\":\"http://127.0.0.1:3131\""));
        assertTrue(requestBodies.get(0).contains("\"codexConfig\""));
        assertTrue(requestBodies.get(0).contains("\"baseUrl\":\"http://127.0.0.1:3151\""));
        assertTrue(requestBodies.get(0).contains("\"authToken\":\"claude-worker-secret\""));
        assertTrue(requestBodies.get(0).contains("\"authToken\":\"codex-worker-secret\""));
        assertTrue(requestBodies.get(1).contains("\"workerId\":\"school-sim-wsl-biz\""));
        assertTrue(requestBodies.get(1).contains("\"workerBackend\":\"LANGGRAPH_BIZ\""));
        assertTrue(requestBodies.get(1).contains("\"baseUrl\":\"http://127.0.0.1:3161\""));
        assertTrue(requestBodies.get(1).contains("\"identityToken\":\"biz-worker-secret\""));
        assertFalse(String.join("\n", requestBodies).contains("OPENAI_CODEX"));
        assertTrue(profile.contains("NAVI_WORKER_HOST_ID=school-sim-wsl"));
        assertTrue(profile.contains("NAVI_WORKER_ID=school-sim-wsl-claude"));
        assertTrue(profile.contains("NAVI_BIZ_WORKER_ID=school-sim-wsl-biz"));
        assertTrue(output.contains("worker-host apply ok"));
        assertTrue(output.contains("workerRole role=claudeCode"));
        assertTrue(output.contains("workerRole role=codex workerId=school-sim-wsl-claude baseUrl=http://127.0.0.1:3151 source=CLAUDE_WORKER_CODEX_CONFIG"));
        assertTrue(output.contains("workerRole role=biz"));
        assertFalse(output.contains("naa-secret-admin-key"));
        assertFalse(output.contains("claude-worker-secret"));
        assertFalse(output.contains("codex-worker-secret"));
        assertFalse(output.contains("biz-worker-secret"));
    }

    @Test
    void workerHostVerifyReportsCodexThroughClaudeCodexConfig() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve(".navigator"));
        Files.writeString(tempDir.resolve(".navigator").resolve("upstream.env"), """
                NAVI_WORKER_ID=school-sim-wsl-claude
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".navigator").resolve("worker-host.json"), """
                {
                  "workerHostId": "school-sim-wsl",
                  "hostUrl": "http://127.0.0.1",
                  "port": 3131,
                  "workers": {
                    "claudeCode": {"enabled": true},
                    "codex": {"enabled": true, "port": 3151},
                    "biz": {"enabled": true, "port": 3161}
                  }
                }
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "worker-host", "verify",
                "--profile", ".navigator/upstream.env",
                "--file", ".navigator/worker-host.json"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertNull(lastPath);
        assertTrue(output.contains("workerRole role=claudeCode workerId=school-sim-wsl-claude baseUrl=http://127.0.0.1:3131 source=CLAUDE_WORKER"));
        assertTrue(output.contains("workerRole role=codex workerId=school-sim-wsl-claude baseUrl=http://127.0.0.1:3151 source=CLAUDE_WORKER_CODEX_CONFIG"));
        assertTrue(output.contains("workerRole role=biz workerId=school-sim-wsl-biz baseUrl=http://127.0.0.1:3161 source=BIZ_WORKER_IDENTITY"));
    }

    @Test
    void workerHostInstallDryRunPrintsInstallerCommandsWithoutRunningThem() throws Exception {
        Files.createDirectories(tempDir.resolve(".navigator"));
        Files.writeString(tempDir.resolve(".navigator").resolve("worker-host.json"), """
                {
                  "workerHostId": "school-sim-wsl",
                  "hostUrl": "http://127.0.0.1",
                  "port": 3131,
                  "install": "ensure",
                  "workers": {
                    "claudeCode": {"enabled": true},
                    "codex": {"enabled": true, "port": 3151},
                    "biz": {"enabled": true, "port": 3161}
                  }
                }
                """, StandardCharsets.UTF_8);
        boolean[] invoked = {false};

        int code = run(new String[]{"upstream", "worker-host", "install",
                "--file", ".navigator/worker-host.json",
                "--install-shell", "bash",
                "--dry-run"}, Map.of(), (command, timeout) -> {
            invoked[0] = true;
            return new UpstreamCli.CommandResult(0, "");
        });

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertFalse(invoked[0]);
        assertNull(lastPath);
        assertTrue(output.contains("worker-host install plan ok"));
        assertTrue(output.contains("installShell=bash"));
        assertTrue(output.contains("startAfterInstall=true"));
        assertTrue(output.contains("installer role=claudeCode"));
        assertTrue(output.contains("claude-worker/install.sh"));
        assertTrue(output.contains("AGENT_WORKER_PORT=3131"));
        assertTrue(output.contains("starter role=claudeCode"));
        assertTrue(output.contains(".claude-worker}/bin/claude-worker"));
        assertTrue(output.contains("installer role=codex"));
        assertTrue(output.contains("codex-worker/install.sh"));
        assertTrue(output.contains("CODEX_WORKER_PORT=3151"));
        assertTrue(output.contains("starter role=codex"));
        assertTrue(output.contains("Codex Worker READY"));
        assertTrue(output.contains("installer role=biz"));
        assertTrue(output.contains("langgraph-biz-worker/install.sh"));
        assertTrue(output.contains("BIZ_WORKER_PORT=3161"));
        assertTrue(output.contains("starter role=biz"));
        assertTrue(output.contains("LangGraph BizWorker READY"));
        assertTrue(output.contains("automaticInstall=false"));
    }

    @Test
    void workerHostInstallRunsEnabledInstallersAndStartersWithRequestedWslUser() throws Exception {
        Files.createDirectories(tempDir.resolve(".navigator"));
        Files.writeString(tempDir.resolve(".navigator").resolve("worker-host.json"), """
                {
                  "workerHostId": "school-sim-wsl",
                  "hostUrl": "http://127.0.0.1",
                  "port": 3131,
                  "install": "ensure",
                  "workers": {
                    "claudeCode": {"enabled": true},
                    "codex": {"enabled": true, "port": 3151},
                    "biz": {"enabled": true, "port": 3161}
                  }
                }
                """, StandardCharsets.UTF_8);
        List<List<String>> commands = new ArrayList<>();
        List<Long> timeouts = new ArrayList<>();

        int code = run(new String[]{"upstream", "worker-host", "install",
                "--file", ".navigator/worker-host.json",
                "--install-shell", "wsl",
                "--wsl-distro", "Ubuntu",
                "--wsl-user", "navigator",
                "--timeout-seconds", "7"}, Map.of(), (command, timeout) -> {
            commands.add(command);
            timeouts.add(timeout.toSeconds());
            return new UpstreamCli.CommandResult(0, "installed token=installer-secret\n");
        });

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertNull(lastPath);
        assertEquals(6, commands.size());
        assertEquals(List.of(7L, 7L, 7L, 7L, 7L, 7L), timeouts);
        assertEquals("wsl.exe", commands.get(0).get(0));
        assertTrue(commands.get(0).contains("--distribution"));
        assertTrue(commands.get(0).contains("Ubuntu"));
        assertTrue(commands.get(0).contains("--user"));
        assertTrue(commands.get(0).contains("navigator"));
        assertTrue(commands.get(0).contains("--exec"));
        assertTrue(commands.get(0).get(commands.get(0).size() - 1).contains("base64 -d | bash"));
        assertTrue(decodedWslScript(commands.get(0)).contains("claude-worker/install.sh"));
        assertTrue(decodedWslScript(commands.get(0)).contains("AGENT_WORKER_PORT=3131"));
        assertTrue(decodedWslScript(commands.get(1)).contains("codex-worker/install.sh"));
        assertTrue(decodedWslScript(commands.get(1)).contains("CODEX_WORKER_PORT=3151"));
        assertTrue(decodedWslScript(commands.get(2)).contains("langgraph-biz-worker/install.sh"));
        assertTrue(decodedWslScript(commands.get(2)).contains("BIZ_WORKER_PORT=3161"));
        assertTrue(decodedWslScript(commands.get(3)).contains(".claude-worker}/bin/claude-worker"));
        assertTrue(decodedWslScript(commands.get(4)).contains("node dist/index.js"));
        assertTrue(decodedWslScript(commands.get(4)).contains("setsid -f"));
        assertTrue(decodedWslScript(commands.get(4)).contains("logs/worker.pid"));
        assertTrue(decodedWslScript(commands.get(4)).contains("Codex Worker READY http://localhost:3151"));
        assertTrue(decodedWslScript(commands.get(4)).contains("sleep 3"));
        assertTrue(decodedWslScript(commands.get(5)).contains("LangGraph BizWorker READY http://localhost:3161"));
        assertFalse(decodedWslScript(commands.get(5)).contains("&;"));
        assertTrue(output.contains("script=set -e; curl -fsSL"));
        assertTrue(output.contains("wslDistro=Ubuntu"));
        assertTrue(output.contains("wslUser=navigator"));
        assertTrue(output.contains("automaticInstall=true"));
        assertTrue(output.contains("install role=claudeCode status=OK exitCode=0"));
        assertTrue(output.contains("install role=codex status=OK exitCode=0"));
        assertTrue(output.contains("install role=biz status=OK exitCode=0"));
        assertTrue(output.contains("start role=claudeCode status=OK exitCode=0"));
        assertTrue(output.contains("start role=codex status=OK exitCode=0"));
        assertTrue(output.contains("start role=biz status=OK exitCode=0"));
        assertTrue(output.contains("worker-host install ok"));
        assertFalse(output.contains("installer-secret"));
    }

    @Test
    void workerHostInstallNoStartSkipsStarterCommands() throws Exception {
        Files.createDirectories(tempDir.resolve(".navigator"));
        Files.writeString(tempDir.resolve(".navigator").resolve("worker-host.json"), """
                {
                  "workerHostId": "school-sim-wsl",
                  "hostUrl": "http://127.0.0.1",
                  "port": 3131,
                  "workers": {
                    "claudeCode": {"enabled": true},
                    "codex": {"enabled": true, "port": 3151}
                  }
                }
                """, StandardCharsets.UTF_8);
        List<List<String>> commands = new ArrayList<>();

        int code = run(new String[]{"upstream", "worker-host", "install",
                "--file", ".navigator/worker-host.json",
                "--install-shell", "bash",
                "--no-start"}, Map.of(), (command, timeout) -> {
            commands.add(command);
            return new UpstreamCli.CommandResult(0, "installed\n");
        });

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals(2, commands.size());
        assertTrue(output.contains("startAfterInstall=false"));
        assertFalse(output.contains("starter role="));
        assertFalse(output.contains("start role="));
    }

    @Test
    void workerHostVerifyRejectsUnknownWorkerKeyBeforeHttpCall() throws Exception {
        Files.createDirectories(tempDir.resolve(".navigator"));
        Files.writeString(tempDir.resolve(".navigator").resolve("worker-host.json"), """
                {
                  "workerHostId": "school-sim-wsl",
                  "hostUrl": "http://127.0.0.1",
                  "port": 3131,
                  "workers": {
                    "claudeCode": {"enabled": true},
                    "custom": {"enabled": true, "port": 3999}
                  }
                }
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "worker-host", "verify",
                "--file", ".navigator/worker-host.json"}, Map.of());

        assertEquals(2, code);
        assertNull(lastPath);
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("unsupported worker-host worker key: custom"));
    }

    @Test
    void workerHostVerifyRejectsCodexWorkerIdBeforeHttpCall() throws Exception {
        Files.createDirectories(tempDir.resolve(".navigator"));
        Files.writeString(tempDir.resolve(".navigator").resolve("worker-host.json"), """
                {
                  "workerHostId": "school-sim-wsl",
                  "hostUrl": "http://127.0.0.1",
                  "port": 3131,
                  "workers": {
                    "claudeCode": {"enabled": true},
                    "codex": {"enabled": true, "port": 3151, "workerId": "school-sim-wsl-codex"}
                  }
                }
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "worker-host", "verify",
                "--file", ".navigator/worker-host.json"}, Map.of());

        assertEquals(2, code);
        assertNull(lastPath);
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("workers.codex.workerId is not supported in Navi-routed mode"));
    }

    @Test
    void upstreamUsageAdvertisesProgrammingProjectOrchestrationCommands() {
        int code = run(new String[]{"upstream", "--help"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(output.contains("worker-host apply/update/verify/install"));
        assertTrue(output.contains("worker list/create/get/update/delete/health/processes/kill"));
        assertTrue(output.contains("directory list/init/get/delete/env/files"));
        assertTrue(output.contains("Internal compatibility: worker-pool list/create/register-worker/add-member/status"));
        assertTrue(output.contains("model system-list/system-create/system-update/system-rotate-key"));
    }

    @Test
    void programmingProjectOrchestrationCommandsUseUpstreamAdminEndpointsAndProfileWriteback() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve(".navigator"));
        Files.writeString(tempDir.resolve(".navigator").resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".navigator").resolve("directory-init.json"), """
                {"workerId":"w-1","path":"D:/work/project","projectName":"project","files":{"README.md":"hello"}}
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".navigator").resolve("worker-pool.json"), """
                {"poolId":"pool-1","name":"Coding Pool","workerBackend":"CODEX"}
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".navigator").resolve("biz-worker.json"), """
                {"workerId":"lgw-1","workerBackend":"CODEX","baseUrl":"http://127.0.0.1:3061","version":"test"}
                """, StandardCharsets.UTF_8);

        responseOverride = """
                {"code":0,"data":[{"workerId":"w-1","name":"Codex Worker","status":"ONLINE"}]}
                """;
        assertEquals(0, run(new String[]{"upstream", "worker", "list",
                "--profile", ".navigator/upstream.env",
                "--target-tenant-id", "tenant-a"}, Map.of()));
        assertEquals("/api/v1/upstream-admin/workers?targetTenantId=tenant-a", lastPath);
        assertEquals("GET", lastMethod);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);

        responseOverride = """
                {"code":0,"data":{"workerId":"w-1","name":"Codex Worker","status":"ONLINE"}}
                """;
        assertEquals(0, run(new String[]{"upstream", "worker", "health",
                "--profile", ".navigator/upstream.env",
                "--worker-id", "w-1"}, Map.of()));
        assertEquals("/api/v1/upstream-admin/workers/w-1/health-check", lastPath);
        assertEquals("POST", lastMethod);

        responseOverride = """
                {"code":0,"data":{"processes":[{"pid":1234,"command":"codex","taskId":"task-1"}]}}
                """;
        assertEquals(0, run(new String[]{"upstream", "worker", "processes",
                "--profile", ".navigator/upstream.env",
                "--worker-id", "w-1"}, Map.of()));
        assertEquals("/api/v1/upstream-admin/workers/w-1/processes", lastPath);
        assertEquals("GET", lastMethod);

        responseOverride = """
                {"code":0,"data":[{"directoryId":"dir-1","workerId":"w-1","projectName":"project","path":"D:/work/project"}]}
                """;
        assertEquals(0, run(new String[]{"upstream", "directory", "list",
                "--profile", ".navigator/upstream.env",
                "--worker-id", "w-1"}, Map.of()));
        assertEquals("/api/v1/upstream-admin/directories?workerId=w-1", lastPath);
        assertEquals("GET", lastMethod);

        responseOverride = """
                {"code":0,"data":{"directoryId":"dir-1","workerId":"w-1","projectName":"project","path":"D:/work/project"}}
                """;
        assertEquals(0, run(new String[]{"upstream", "directory", "init",
                "--profile", ".navigator/upstream.env",
                "--file", ".navigator/directory-init.json",
                "--write-profile"}, Map.of()));
        assertEquals("/api/v1/upstream-admin/directories/init", lastPath);
        assertEquals("POST", lastMethod);

        responseOverride = """
                {"code":0,"data":{"poolId":"pool-1","name":"Coding Pool","workerBackend":"CODEX","status":"ENABLED"}}
                """;
        assertEquals(0, run(new String[]{"upstream", "worker-pool", "create",
                "--profile", ".navigator/upstream.env",
                "--file", ".navigator/worker-pool.json",
                "--target-tenant-id", "tenant-a",
                "--write-profile"}, Map.of()));
        assertEquals("/api/v1/upstream-admin/worker-pools?targetTenantId=tenant-a", lastPath);
        assertEquals("POST", lastMethod);

        responseOverride = """
                {"code":0,"data":{"workerId":"lgw-1","ownerType":"UPSTREAM_SYSTEM","ownerId":"ups-1","workerBackend":"CODEX","baseUrl":"http://127.0.0.1:3061","status":"ENABLED"}}
                """;
        assertEquals(0, run(new String[]{"upstream", "worker-pool", "register-worker",
                "--profile", ".navigator/upstream.env",
                "--file", ".navigator/biz-worker.json",
                "--write-profile"}, Map.of()));
        assertEquals("/api/v1/upstream-admin/worker-identities", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"workerId\":\"lgw-1\""));
        assertTrue(lastBody.contains("\"workerBackend\":\"CODEX\""));

        responseOverride = "{\"code\":0,\"data\":null}";
        assertEquals(0, run(new String[]{"upstream", "worker-pool", "add-member",
                "--profile", ".navigator/upstream.env",
                "--pool-id", "pool-1",
                "--target-tenant-id", "tenant-a"}, Map.of()));
        assertEquals("/api/v1/upstream-admin/worker-pools/pool-1/members?targetTenantId=tenant-a", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"workerId\":\"lgw-1\""));

        String profile = Files.readString(tempDir.resolve(".navigator").resolve("upstream.env"), StandardCharsets.UTF_8);
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(profile.contains("NAVI_DIRECTORY_ID=dir-1"));
        assertTrue(profile.contains("NAVI_WORKER_POOL_ID=pool-1"));
        assertTrue(profile.contains("NAVI_BIZ_WORKER_ID=lgw-1"));
        assertFalse(output.contains("naa-secret-admin-key"));
    }

    @Test
    void directoryClientInitUsesClientAppControlKeyAndStoresDirectoryId() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve(".navigator"));
        Files.writeString(tempDir.resolve(".navigator").resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_CLIENT_APP_ID=app-1
                NAVI_CONTROL_API_KEY=control-key-secret
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".navigator").resolve("client-directory.json"), """
                {
                  "workerId": "worker-1",
                  "workspaceScope": "CLIENT_APP_SHARED",
                  "path": "D:/workspace/app-shared",
                  "projectName": "app-shared",
                  "files": {
                    "CLAUDE.md": "# App"
                  }
                }
                """, StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{
                  "directoryId":"dir-client-1",
                  "clientAppId":"app-1",
                  "workerId":"worker-1",
                  "workspaceScope":"CLIENT_APP_SHARED",
                  "path":"D:/workspace/app-shared"
                }}
                """;

        int code = run(new String[]{"upstream", "directory", "client-init",
                "--profile", ".navigator/upstream.env",
                "--file", ".navigator/client-directory.json",
                "--write-profile"}, Map.of());

        String profile = Files.readString(tempDir.resolve(".navigator").resolve("upstream.env"), StandardCharsets.UTF_8);
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/directories/init", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"workspaceScope\":\"CLIENT_APP_SHARED\""));
        assertTrue(lastBody.contains("\"path\":\"D:/workspace/app-shared\""));
        assertTrue(profile.contains("NAVI_DIRECTORY_ID=dir-client-1"));
        assertTrue(output.contains("directory client-init ok"));
        assertTrue(output.contains("stored=NAVI_DIRECTORY_ID"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void directoryClientListUsesClientAppControlCredentialAndExplicitFiltersOnly() {
        responseOverride = """
                {"code":0,"data":[{"directoryId":"dir-client-1","workspaceScope":"USER_PRIVATE"}]}
                """;
        Map<String, String> env = env(
                "NAVI_CONTROL_API_KEY", "control-key-secret",
                "NAVI_UPSTREAM_USER_ID", "profile-user");

        int code = run(new String[]{"upstream", "directory", "client-list",
                "--base-url", baseUrl(),
                "--client-app-id", "app-1",
                "--worker-id", "worker-1",
                "--workspace-scope", "USER_PRIVATE",
                "--upstream-user-id", "explicit-user"}, env);

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/directories?workerId=worker-1"
                + "&workspaceScope=USER_PRIVATE&upstreamUserId=explicit-user", lastPath);
        assertEquals("GET", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(output.contains("directoryCount=1"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void directoryClientListDoesNotImplicitlyFilterByProfileUpstreamUser() {
        responseOverride = """
                {"code":0,"data":[{"directoryId":"dir-client-1","workspaceScope":"CLIENT_APP_SHARED"}]}
                """;
        Map<String, String> env = env(
                "NAVI_CONTROL_API_KEY", "control-key-secret",
                "NAVI_UPSTREAM_USER_ID", "profile-user");

        int code = run(new String[]{"upstream", "directory", "client-list",
                "--base-url", baseUrl(),
                "--client-app-id", "app-1",
                "--worker-id", "worker-1",
                "--workspace-scope", "CLIENT_APP_SHARED"}, env);

        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/directories?workerId=worker-1"
                + "&workspaceScope=CLIENT_APP_SHARED", lastPath);
        assertEquals("GET", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
    }

    @Test
    void modelCreateRequiresClientAppControlKey() {
        int code = run(new String[]{"upstream", "model", "create",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--admin-api-key", "naa-secret-admin-key",
                "--client-app-id", "app-1",
                "--name", "Upstream GPT",
                "--model-base-url", "https://llm.example/v1",
                "--model-name", "gpt-test",
                "--api-key-env", "UPSTREAM_LLM_KEY"}, env("UPSTREAM_LLM_KEY", "llm-secret"));

        String error = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertNull(lastPath);
        assertTrue(error.contains("client app control credential is required"));
        assertFalse(error.contains("naa-secret-admin-key"));
        assertFalse(error.contains("llm-secret"));
    }

    @Test
    void modelSystemCreateUsesUpstreamAdminKeyAndStoresModelId() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve(".navigator"));
        Files.writeString(tempDir.resolve(".navigator").resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{
                  "id":"model-shared",
                  "tenantId":"tenant-1",
                  "name":"Upstream GPT",
                  "modelName":"gpt-test",
                  "workerBackend":"LANGGRAPH_BIZ",
                  "ownerType":"UPSTREAM_SYSTEM",
                  "ownerId":"ups-1",
                  "enabled":true
                }}
                """;

        int code = run(new String[]{"upstream", "model", "system-create",
                "--profile", ".navigator/upstream.env",
                "--target-tenant-id", "tenant-1",
                "--name", "Upstream GPT",
                "--model-base-url", "https://llm.example/v1",
                "--model-name", "gpt-test",
                "--api-key-env", "UPSTREAM_LLM_KEY",
                "--write-profile"}, env("UPSTREAM_LLM_KEY", "llm-secret"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(tempDir.resolve(".navigator").resolve("upstream.env"), StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-admin/model-configs?targetTenantId=tenant-1", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(lastBody.contains("\"apiKey\":\"llm-secret\""));
        assertTrue(profile.contains("NAVI_MODEL_CONFIG_ID=model-shared"));
        assertTrue(output.contains("model system-create ok"));
        assertTrue(output.contains("modelConfig.id=model-shared"));
        assertTrue(output.contains("modelConfig.ownerType=UPSTREAM_SYSTEM"));
        assertFalse(output.contains("naa-secret-admin-key"));
        assertFalse(output.contains("llm-secret"));
    }

    @Test
    void modelSystemCreateAcceptsOpenAiCodexWorkerBackend() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve(".navigator"));
        Files.writeString(tempDir.resolve(".navigator").resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{
                  "id":"model-shared-codex",
                  "tenantId":"tenant-1",
                  "name":"Upstream Codex",
                  "modelName":"codex-mini",
                  "workerBackend":"OPENAI_CODEX",
                  "ownerType":"UPSTREAM_SYSTEM",
                  "ownerId":"ups-1",
                  "enabled":true
                }}
                """;

        int code = run(new String[]{"upstream", "model", "system-create",
                "--profile", ".navigator/upstream.env",
                "--target-tenant-id", "tenant-1",
                "--name", "Upstream Codex",
                "--model-base-url", "https://codex.example/v1",
                "--model-name", "codex-mini",
                "--worker-backend", "OPENAI_CODEX",
                "--api-key-env", "UPSTREAM_LLM_KEY",
                "--write-profile"}, env("UPSTREAM_LLM_KEY", "llm-secret"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(tempDir.resolve(".navigator").resolve("upstream.env"), StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-admin/model-configs?targetTenantId=tenant-1", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"workerBackend\":\"OPENAI_CODEX\""));
        assertTrue(profile.contains("NAVI_MODEL_CONFIG_ID=model-shared-codex"));
        assertTrue(output.contains("model system-create ok"));
        assertTrue(output.contains("modelConfig.workerBackend=OPENAI_CODEX"));
        assertFalse(output.contains("naa-secret-admin-key"));
        assertFalse(output.contains("llm-secret"));
    }

    private int run(String[] args, Map<String, String> env) {
        return new UpstreamCli(
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                tempDir)
                .run(args, env);
    }

    private int run(String[] args, Map<String, String> env, UpstreamCli.CommandRunner commandRunner) {
        return new UpstreamCli(
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                tempDir,
                commandRunner)
                .run(args, env);
    }

    private String decodedWslScript(List<String> command) {
        String wrapper = command.get(command.size() - 1);
        String prefix = "printf %s '";
        String suffix = "' | base64 -d | bash";
        assertTrue(wrapper.startsWith(prefix));
        assertTrue(wrapper.endsWith(suffix));
        String encoded = wrapper.substring(prefix.length(), wrapper.length() - suffix.length());
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
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
