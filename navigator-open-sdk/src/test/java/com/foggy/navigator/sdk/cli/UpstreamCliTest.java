package com.foggy.navigator.sdk.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private static String lastClientRequestIdHeader;
    private static String lastPrincipalCredentialHeader;
    private static String lastTenantIdHeader;
    private static String responseOverride;
    private static int responseStatusOverride;
    private static List<String> requestPaths;
    private static List<String> requestBodies;
    private static List<String> requestClientRequestIds;

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
            lastClientRequestIdHeader = exchange.getRequestHeaders().getFirst("X-Navigator-Client-Request-Id");
            requestClientRequestIds.add(lastClientRequestIdHeader);
            lastPrincipalCredentialHeader = exchange.getRequestHeaders().getFirst("X-Navi-Principal-Credential");
            lastTenantIdHeader = exchange.getRequestHeaders().getFirst("X-Tenant-Id");

            String response;
            if ("__RUNTIME_TOKEN_THEN_SAFE_SMOKE_DROP__".equals(responseOverride)
                    && lastPath.contains("/safe-smoke")) {
                exchange.close();
                return;
            } else if ("__RUNTIME_TOKEN_THEN_SAFE_SMOKE__".equals(responseOverride)
                    || "__RUNTIME_TOKEN_THEN_SAFE_SMOKE_DROP__".equals(responseOverride)) {
                response = lastPath.contains("/runtime-token")
                        ? "{\"code\":200,\"data\":{\"accessToken\":\"cat-auto-secret\",\"appKey\":\"cak-test\",\"clientAppId\":\"app-1\",\"expiresInSeconds\":1800}}"
                        : "{\"code\":200,\"data\":{\"taskId\":\"smk-correlated\",\"status\":\"COMPLETED\",\"contextId\":\"ctx-1\",\"effectiveToolCount\":0,\"effectiveFunctionCount\":0,\"toolScopeSource\":\"SAFE_SMOKE_NO_RUNTIME\",\"toolScopeKind\":\"NO_RUNTIME_MODEL_TOOL_SURFACE\",\"functionScopeSource\":\"REQUEST_EXPLICIT_EMPTY\",\"taskTokenFunctionScopeEmpty\":true,\"runtimeDispatched\":false,\"taskTokenStatus\":\"REVOKED\",\"result\":\"SAFE_SMOKE_VERIFIED_NO_RUNTIME_DISPATCH\"}}";
            } else if ("__RUNTIME_AUDIT_EXACT__".equals(responseOverride)) {
                response = """
                        {"code":200,"data":{"count":1,"limit":20,"items":[{
                          "clientRequestId":"6a02be06-b9e9-4935-878d-063268031462",
                          "operation":"safe-ask",
                          "receivedAt":"2026-07-23T06:30:09Z",
                          "completedAt":null,
                          "terminal":false,
                          "result":"UNKNOWN",
                          "sanitizedErrorCode":null,
                          "httpRequestReceived":true,
                          "runtimeTokenRequestReceived":true,
                          "runtimeTokenIssued":null,
                          "safeSmokeRequestReceived":false,
                          "syntheticEvidenceCreated":false,
                          "taskId":null,
                          "status":"WAITING_FOR_SAFE_SMOKE",
                          "effectiveToolCount":null,
                          "toolScopeKind":"UNKNOWN",
                          "toolScopeSource":"UNKNOWN",
                          "effectiveFunctionCount":null,
                          "functionScopeSource":"UNKNOWN",
                          "taskTokenFunctionScopeEmpty":null,
                          "taskTokenStatus":"UNKNOWN",
                          "runtimeDispatched":null,
                          "stages":[{"stage":"RUNTIME_TOKEN_REQUEST_RECEIVED","status":"RECEIVED","sanitizedErrorCode":null,"occurredAt":"2026-07-23T06:30:09Z"}]
                        }]}}
                        """;
            } else if ("__MESSAGES_TERMINAL__".equals(responseOverride)) {
                response = lastPath.contains("/messages")
                        ? """
                        {"code":0,"data":{"messages":[{
                          "messageId":"m-1",
                          "role":"assistant",
                          "type":"RESULT",
                          "eventKind":"final_marker",
                          "progressType":"final",
                          "status":"COMPLETED",
                          "terminal":true,
                          "terminalStatus":"COMPLETED",
                          "content":"done cat-runtime-secret",
                          "reportRefs":[{"type":"frame_report","ref":"frame-report://task-1/frame-1","frameId":"frame-1"}],
                          "artifactRefs":[{"path":"outputs/result.json?token=cat-runtime-secret"}]
                        }]}}
                        """
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
            } else if ("__SYSTEM_ADMIN_SCOPE_AGENT_LIST__".equals(responseOverride)) {
                response = lastPath.endsWith("/scope")
                        ? """
                        {"code":0,"data":{"credentialLane":"UPSTREAM_SYSTEM_ADMIN","principalType":"UPSTREAM_SYSTEM_ADMIN","upstreamSystemId":"foggy-world-sim","tenantId":"nav_foggy-world-sim_sim","clientAppId":"capp-sim","clientAppNamespace":"foggy-world-sim","targetOwnerType":"CLIENT_APP","targetOwnerId":"capp-sim","authorizationChecks":["EXPLICIT_CLIENT_APP_TARGET","TENANT_AUTHORIZED","UPSTREAM_SYSTEM_MATCH","CLIENT_APP_NAMESPACE_MATCH","CLIENT_APP_ACTIVE"]}}
                        """
                        : """
                        {"code":0,"data":[{"tenantId":"nav_foggy-world-sim_sim","clientAppId":"capp-sim","agentId":"agent-sim","ownerType":"CLIENT_APP","ownerId":"capp-sim","name":"SIM Agent"}]}
                        """;
            } else {
                response = responseOverride != null ? responseOverride : "{\"code\":0,\"data\":{}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatusOverride, bytes.length);
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
        lastClientRequestIdHeader = null;
        lastPrincipalCredentialHeader = null;
        lastTenantIdHeader = null;
        requestPaths = new ArrayList<>();
        requestBodies = new ArrayList<>();
        requestClientRequestIds = new ArrayList<>();
        responseOverride = "{\"code\":0,\"data\":{}}";
        responseStatusOverride = 200;
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
    }

    @Test
    void modelAndAgentParentHelpAreAvailable() {
        int modelCode = run(new String[]{"upstream", "model", "--help"}, Map.of());
        String modelOutput = stdout.toString(StandardCharsets.UTF_8);

        assertEquals(0, modelCode);
        assertTrue(modelOutput.contains("Usage: navi upstream model <command> [options]"));
        assertTrue(modelOutput.contains("ClientApp model create/update/grant/default commands use NAVI_CONTROL_API_KEY"));
        assertTrue(modelOutput.contains("System model commands use NAVI_ADMIN_API_KEY"));
        assertTrue(requestPaths.isEmpty());

        stdout.reset();
        stderr.reset();
        int agentCode = run(new String[]{"upstream", "agent", "--help"}, Map.of());
        String agentOutput = stdout.toString(StandardCharsets.UTF_8);

        assertEquals(0, agentCode);
        assertTrue(agentOutput.contains("Usage: navi upstream agent <command> [options]"));
        assertTrue(agentOutput.contains("ClientApp agent sync/bind commands use NAVI_CONTROL_API_KEY"));
        assertTrue(agentOutput.contains("System agent commands use NAVI_ADMIN_API_KEY"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void clientAppHelpExplainsProvisioningAndTenantRuntimeCredentialBoundary() {
        int code = run(new String[]{"upstream", "client-app", "--help"}, Map.of());
        String output = stdout.toString(StandardCharsets.UTF_8);

        assertEquals(0, code);
        assertTrue(output.contains("require the upstream-admin lane (NAVI_ADMIN_API_KEY)"));
        assertTrue(output.contains("platform-control-profile"));
        assertTrue(output.contains("control and runtime credentials are written to separate private profiles"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void canonicalLaneHelpExplainsCurrentAuthorityWithoutClaimingTypedPlatform() {
        int platformCode = run(new String[]{"upstream", "platform", "--help"}, Map.of());
        String platformOutput = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, platformCode);
        assertTrue(platformOutput.contains("UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN"));
        assertTrue(platformOutput.contains("not typed SAAS_PLATFORM authority"));
        assertTrue(platformOutput.contains("platform-control-profile"));

        stdout.reset();
        stderr.reset();
        int appCode = run(new String[]{"upstream", "app", "--help"}, Map.of());
        assertEquals(0, appCode);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("requires exactly NAVI_CONTROL_API_KEY"));

        stdout.reset();
        stderr.reset();
        int runtimeCode = run(new String[]{"upstream", "runtime", "--help"}, Map.of());
        assertEquals(0, runtimeCode);
        String runtimeOutput = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(runtimeOutput.contains("rejects admin, control, and typed-management credentials"));
        assertTrue(runtimeOutput.contains("audit --request-id <clientRequestId>"));
        assertTrue(runtimeOutput.contains("--since <ISO-8601 offset time> --until <ISO-8601 offset time>"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void platformAppListHelpDoesNotExecuteAndTenantListNamesReturnedClientApps() {
        int helpCode = run(new String[]{"upstream", "platform", "app", "list", "--help"}, Map.of());
        assertEquals(0, helpCode);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("Usage: navi upstream platform app"));
        assertTrue(requestPaths.isEmpty());

        stdout.reset();
        responseOverride = "{\"code\":0,\"data\":[]}";
        int listCode = run(new String[]{"upstream", "platform", "tenant", "list", "--base-url", baseUrl()},
                env("NAVI_ADMIN_API_KEY", "naa-secret-admin-key"));
        assertEquals(0, listCode);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("resourceType=CLIENT_APP"));
        assertEquals("/api/v1/upstream-admin/client-apps", lastPath);
    }

    @Test
    void platformAppScopeUsesOnlySystemAdminCredentialAndRequiresExplicitTarget() {
        responseOverride = "__SYSTEM_ADMIN_SCOPE_AGENT_LIST__";
        int code = run(new String[]{"upstream", "platform", "app-scope", "agent-list", "--client-app-id", "capp-sim"},
                env("NAVI_BASE_URL", baseUrl(), "NAVI_UPSTREAM_SYSTEM_ID", "foggy-world-sim",
                        "NAVI_ADMIN_API_KEY", "naa-secret-admin-key"));

        assertEquals(0, code);
        assertEquals(List.of(
                "/api/v1/upstream-admin/client-apps/capp-sim/scope",
                "/api/v1/upstream-admin/client-apps/capp-sim/scope/agents"), requestPaths);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertNull(lastClientAppControlKeyHeader);
        assertNull(lastApiKeyHeader);
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("scopeCredentialLane=UPSTREAM_SYSTEM_ADMIN"));
        assertTrue(output.contains("scopeAuthorizationChecks=EXPLICIT_CLIENT_APP_TARGET,TENANT_AUTHORIZED"));

        stdout.reset();
        stderr.reset();
        int missingTarget = run(new String[]{"upstream", "platform", "app-scope", "inspect"},
                env("NAVI_BASE_URL", baseUrl(), "NAVI_UPSTREAM_SYSTEM_ID", "foggy-world-sim",
                        "NAVI_ADMIN_API_KEY", "naa-secret-admin-key"));
        assertEquals(2, missingTarget);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("requires explicit --client-app-id"));
    }

    @Test
    void platformTenantEnsureRejectsSameProfileBeforeProvisioning() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "platform", "tenant", "ensure",
                "--profile", ".navigator/upstream.env",
                "--source-system", "TMS",
                "--source-tenant-id", "3",
                "--platform-control-profile", ".navigator/tenant.env",
                "--tenant-runtime-profile", ".navigator/tenant.env",
                "--write-profile"}, Map.of());

        assertEquals(2, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("must be different"));
        assertNull(lastPath);
    }

    @Test
    void platformTenantEnsureDoesNotCreateCombinedProfileWhenRuntimeWriteFails() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        Files.writeString(profileDir.resolve("not-a-directory"), "occupied", StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{
                  "navigatorTenantId":"nav_tms_3",
                  "clientAppId":"capp-tms-3",
                  "clientAppKey":"cak-secret-key",
                  "clientAppSecret":"cas-secret-value",
                  "controlApiKey":"cac-secret-control-key"
                }}
                """;

        Path controlProfile = profileDir.resolve("platform/tms-3-control.env");
        int code = run(new String[]{"upstream", "platform", "tenant", "ensure",
                "--profile", ".navigator/upstream.env",
                "--source-system", "TMS",
                "--source-tenant-id", "3",
                "--platform-control-profile", ".navigator/platform/tms-3-control.env",
                "--tenant-runtime-profile", ".navigator/not-a-directory/tms-3-runtime.env",
                "--write-profile"}, Map.of());

        String error = stderr.toString(StandardCharsets.UTF_8);
        String control = Files.readString(controlProfile, StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertEquals("/api/v1/admin/upstream-tenants/client-apps/ensure", lastPath);
        assertTrue(error.contains("tenant runtime profile"));
        assertTrue(control.contains("NAVI_CONTROL_API_KEY=cac-secret-control-key"));
        assertFalse(control.contains("NAVI_CLIENT_APP_KEY="));
        assertFalse(control.contains("NAVI_CLIENT_APP_SECRET="));
        assertFalse(Files.exists(profileDir.resolve("not-a-directory/tms-3-runtime.env")));
        assertFalse(error.contains("naa-secret-admin-key"));
        assertFalse(error.contains("cac-secret-control-key"));
        assertFalse(error.contains("cak-secret-key"));
        assertFalse(error.contains("cas-secret-value"));
    }

    @Test
    void appControlCommandRejectsRuntimeMaterialBeforeRequest() {
        int code = run(new String[]{"upstream", "app", "ensure-grant",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--client-app-id", "app-1",
                "--upstream-user-id", "user-1",
                "--control-api-key", "cac-control-secret",
                "--client-app-key", "cak-runtime-secret"}, Map.of());

        assertEquals(2, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("ClientApp control lane refuses mixed credential material"));
        assertNull(lastPath);
    }

    @Test
    void workerHelpDistinguishesExistingCodexPhysicalWorkersFromWorkerPoolCompatibility() {
        int rootCode = run(new String[]{"upstream", "--help"}, Map.of());
        String rootOutput = stdout.toString(StandardCharsets.UTF_8);

        assertEquals(0, rootCode);
        assertTrue(rootOutput.contains("Do not use these commands to onboard OPENAI_CODEX"));
        assertTrue(rootOutput.contains("existing Physical Worker, use worker-host verify then update"));
        assertTrue(requestPaths.isEmpty());

        stdout.reset();
        stderr.reset();
        int workerHostCode = run(new String[]{"upstream", "worker-host", "--help"}, Map.of());
        String workerHostOutput = stdout.toString(StandardCharsets.UTF_8);

        assertEquals(0, workerHostCode);
        assertTrue(workerHostOutput.contains("existing Physical Worker only"));
        assertTrue(workerHostOutput.contains("Codex is Navi-routed through claudeCode.codexConfig"));
        assertTrue(requestPaths.isEmpty());

        stdout.reset();
        stderr.reset();
        int workerPoolCode = run(new String[]{"upstream", "worker-pool", "--help"}, Map.of());
        String workerPoolOutput = stdout.toString(StandardCharsets.UTF_8);

        assertEquals(0, workerPoolCode);
        assertTrue(workerPoolOutput.contains("not for OPENAI_CODEX or OPENAI_CODEX_APP_SERVER"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void workerPoolRegisterWorkerRejectsDirectCodexIdentitiesBeforeRequest() throws Exception {
        for (String workerBackend : List.of("OPENAI_CODEX", "OPENAI_CODEX_APP_SERVER")) {
            Path identityFile = tempDir.resolve("worker-identity-" + workerBackend + ".json");
            Files.writeString(identityFile, """
                    {
                      "workerId": "codex-direct",
                      "workerBackend": "%s",
                      "baseUrl": "http://127.0.0.1:3151"
                    }
                    """.formatted(workerBackend), StandardCharsets.UTF_8);

            int code = run(new String[]{"upstream", "worker-pool", "register-worker",
                    "--file", identityFile.toString()}, env(
                    "NAVI_BASE_URL", baseUrl(),
                    "NAVI_ADMIN_API_KEY", "naa-secret-admin-key"));

            assertEquals(2, code, workerBackend);
            assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("does not support " + workerBackend));
            assertTrue(requestPaths.isEmpty(), workerBackend);
            stdout.reset();
            stderr.reset();
        }
    }

    @Test
    void configCheckReportsOnlyLocalTriStateAndNeverAuthorizes() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navi-upstream.env\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".navi-upstream.env"),
                "NAVI_CLIENT_APP_SECRET=super-secret-value\nNAVI_CLIENT_APP_ACCESS_TOKEN=runtime-secret-value\n",
                StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "config", "check", "--profile", ".navi-upstream.env"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("""
                configState=UNVERIFIED
                profileSafety=VALID
                typedMetadata=UNVERIFIED
                typedCredentialSource=UNVERIFIED
                legacyPlatformLane=MIXED
                clientAppControlLane=MIXED
                runtimeLane=AVAILABLE
                typedManagementAuthority=NOT_CONFIGURED
                authorization=UNVERIFIED
                """, output);
        assertFalse(output.contains("super-secret-value"));
        assertFalse(output.contains("runtime-secret-value"));
        assertFalse(output.contains("ALLOW"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void configCheckReportsCompatibleTypedFixtureAsValidWithoutHttp() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path configDir = tempDir.resolve(".navigator");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("upstream.env"), """
                NAVI_NAVIGATOR_INSTANCE_ID=sim-navigator-local
                NAVI_ENVIRONMENT_PROFILE=LOCAL
                NAVI_EXPECTED_PRINCIPAL_TYPE=INSTANCE_ROOT
                NAVI_EXPECTED_CREDENTIAL_LANE=INSTANCE_ROOT_CONTROL
                NAVI_PRINCIPAL_CREDENTIAL=typed-fixture-secret
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "config", "check"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("""
                configState=VALID
                profileSafety=VALID
                typedMetadata=VALID
                typedCredentialSource=VALID
                legacyPlatformLane=MIXED
                clientAppControlLane=MIXED
                runtimeLane=MIXED
                typedManagementAuthority=LOCALLY_CONFIGURED_NOT_AUTHORIZED
                authorization=UNVERIFIED
                """, output);
        assertFalse(output.contains("typed-fixture-secret"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void configCheckFailsClosedForPartialTypedMetadata() {
        int code = run(new String[]{"upstream", "config", "check"}, env(
                "NAVI_PRINCIPAL_CREDENTIAL", "typed-fixture-secret",
                "NAVI_NAVIGATOR_INSTANCE_ID", "sim-navigator-local",
                "NAVI_EXPECTED_PRINCIPAL_TYPE", "INSTANCE_ROOT",
                "NAVI_EXPECTED_CREDENTIAL_LANE", "INSTANCE_ROOT_CONTROL"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertTrue(output.contains("configState=INVALID"));
        assertTrue(output.contains("typedMetadata=INVALID"));
        assertTrue(output.contains("authorization=UNVERIFIED"));
        assertFalse(output.contains("typed-fixture-secret"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void configCheckFailsClosedForMixedTypedAndLegacyCredentialLanes() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navi-upstream.env\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".navi-upstream.env"), """
                NAVI_NAVIGATOR_INSTANCE_ID=sim-navigator-local
                NAVI_ENVIRONMENT_PROFILE=LOCAL
                NAVI_EXPECTED_PRINCIPAL_TYPE=INSTANCE_ROOT
                NAVI_EXPECTED_CREDENTIAL_LANE=INSTANCE_ROOT_CONTROL
                NAVI_PRINCIPAL_CREDENTIAL=typed-fixture-secret
                NAVI_CONTROL_API_KEY=legacy-control-secret
                """, StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "config", "check", "--profile", ".navi-upstream.env"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertTrue(output.contains("configState=INVALID"));
        assertTrue(output.contains("typedCredentialSource=INVALID"));
        assertTrue(output.contains("authorization=UNVERIFIED"));
        assertFalse(output.contains("typed-fixture-secret"));
        assertFalse(output.contains("legacy-control-secret"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void configCheckMarksUnignoredProfileInvalidWithoutEchoingIt() throws Exception {
        Files.writeString(tempDir.resolve("local.properties"),
                "NAVI_CLIENT_APP_SECRET=super-secret-value\n", StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "config", "check", "--profile", "local.properties"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertTrue(output.contains("configState=INVALID"));
        assertTrue(output.contains("profileSafety=INVALID"));
        assertTrue(output.contains("authorization=UNVERIFIED"));
        assertFalse(output.contains("super-secret-value"));
        assertFalse(output.contains("local.properties"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void typedManagementWhoamiUsesOnlyPrincipalHeaderAndNeverEchoesSecrets() {
        String principalCredential = "p1c-principal-secret";
        responseOverride = """
                {"code":0,"data":{
                  "principalType":"INSTANCE_ROOT",
                  "principalId":"sim-root",
                  "sourceUpstreamSystemId":"foggy-world-sim",
                  "navigatorInstanceId":"navi-sim-local",
                  "environmentProfile":"LOCAL",
                  "credentialLane":"INSTANCE_ROOT_CONTROL",
                  "credentialStatus":"ACTIVE",
                  "credentialExpiresAt":"2030-01-01T00:00:00Z",
                  "credential":"server-echo-secret",
                  "credentialFingerprint":"server-fingerprint",
                  "authorityCeilingActions":["instance.manage","instance.security"],
                  "effectiveCredentialActions":["instance.manage"]
                }}
                """;

        int code = run(new String[]{"upstream", "auth", "whoami", "--base-url", baseUrl()}, env(
                "NAVI_PRINCIPAL_CREDENTIAL", principalCredential,
                "NAVI_TENANT_ID", "must-not-be-sent"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTypedManagementRequest("/api/v1/management/v1/auth/whoami", "GET", principalCredential);
        assertTrue(output.contains("typedManagement=whoami"));
        assertTrue(output.contains("schemaVersion=NOT_SUPPLIED_BY_SERVER"));
        assertTrue(output.contains("credentialFingerprint=NOT_SUPPLIED_BY_SERVER"));
        assertTrue(output.contains("authorityCeilingActions=instance.manage,instance.security"));
        assertTrue(output.contains("effectiveCredentialActions=instance.manage"));
        assertFalse(output.contains(principalCredential));
        assertFalse(output.contains("server-echo-secret"));
        assertFalse(output.contains("server-fingerprint"));
    }

    @Test
    void typedManagementPermissionsAcceptsOneExplicitCredentialSourceOnly() {
        String explicitCredential = "p1c-explicit-principal-secret";
        responseOverride = """
                {"code":0,"data":{
                  "principalType":"SAAS_PLATFORM",
                  "navigatorInstanceId":"navi-tms-local",
                  "credentialLane":"SAAS_PROVISIONING",
                  "authorityCeilingActions":["platform.manage","platform.security"],
                  "effectiveCredentialActions":["platform.manage"]
                }}
                """;

        int code = run(new String[]{"upstream", "inspect", "permissions", "--base-url", baseUrl(),
                "--principal-credential-env", "P1C_TYPED_CREDENTIAL"}, env(
                "P1C_TYPED_CREDENTIAL", explicitCredential,
                "NAVI_TENANT_ID", "must-not-be-sent"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTypedManagementRequest("/api/v1/management/v1/auth/permissions", "GET", explicitCredential);
        assertTrue(output.contains("typedManagement=permissions"));
        assertTrue(output.contains("authorityCeilingActions=platform.manage,platform.security"));
        assertTrue(output.contains("effectiveCredentialActions=platform.manage"));
        assertFalse(output.contains(explicitCredential));
    }

    @Test
    void typedManagementRejectsMissingCredentialBeforeHttpDispatch() {
        int code = run(new String[]{"upstream", "auth", "whoami", "--base-url", baseUrl()}, Map.of());

        assertEquals(2, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("TYPED_MANAGEMENT_CREDENTIAL_MISSING"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void typedManagementRejectsLegacyOnlyCredentialBeforeHttpDispatch() {
        String legacyCredential = "legacy-admin-secret";
        int code = run(new String[]{"upstream", "inspect", "permissions", "--base-url", baseUrl()},
                env("NAVI_ADMIN_API_KEY", legacyCredential));

        String error = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertTrue(error.contains("TYPED_MANAGEMENT_LEGACY_CREDENTIAL_ONLY"));
        assertFalse(error.contains(legacyCredential));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void typedManagementRejectsMixedLegacyAndTypedCredentialLanesBeforeHttpDispatch() {
        String principalCredential = "typed-principal-secret";
        String legacyCredential = "legacy-control-secret";
        int code = run(new String[]{"upstream", "auth", "whoami", "--base-url", baseUrl()}, env(
                "NAVI_PRINCIPAL_CREDENTIAL", principalCredential,
                "NAVI_CONTROL_API_KEY", legacyCredential));

        String error = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertTrue(error.contains("TYPED_MANAGEMENT_LEGACY_CREDENTIAL_CONFLICT"));
        assertFalse(error.contains(principalCredential));
        assertFalse(error.contains(legacyCredential));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void typedManagementRejectsRuntimeTaskAndWorkerCredentialEnvSourcesBeforeHttpDispatch() {
        for (String legacyKey : List.of(
                "NAVI_CLIENT_APP_RUNTIME_TOKEN",
                "NAVI_RUNTIME_CREDENTIAL",
                "NAVI_TASK_SCOPED_TOKEN",
                "NAVI_WORKER_CREDENTIAL")) {
            String principalCredential = "typed-principal-secret";
            String legacyCredential = "legacy-" + legacyKey + "-secret";

            int code = run(new String[]{"upstream", "auth", "whoami", "--base-url", baseUrl()}, env(
                    "NAVI_PRINCIPAL_CREDENTIAL", principalCredential,
                    legacyKey, legacyCredential));

            String error = stderr.toString(StandardCharsets.UTF_8);
            assertEquals(2, code, legacyKey);
            assertTrue(error.contains("TYPED_MANAGEMENT_LEGACY_CREDENTIAL_CONFLICT"), legacyKey);
            assertFalse(error.contains(principalCredential), legacyKey);
            assertFalse(error.contains(legacyCredential), legacyKey);
            assertTrue(requestPaths.isEmpty(), legacyKey);
            stdout.reset();
            stderr.reset();
        }
    }

    @Test
    void typedManagementRejectsAmbiguousOrMissingExplicitCredentialSourcesBeforeHttpDispatch() {
        String directCredential = "typed-direct-secret";
        String explicitCredential = "typed-explicit-secret";
        int ambiguousCode = run(new String[]{"upstream", "auth", "whoami", "--base-url", baseUrl(),
                "--principal-credential-env", "P1C_TYPED_CREDENTIAL"}, env(
                "NAVI_PRINCIPAL_CREDENTIAL", directCredential,
                "P1C_TYPED_CREDENTIAL", explicitCredential));

        String ambiguousError = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(2, ambiguousCode);
        assertTrue(ambiguousError.contains("TYPED_MANAGEMENT_CREDENTIAL_SOURCE_AMBIGUOUS"));
        assertFalse(ambiguousError.contains(directCredential));
        assertFalse(ambiguousError.contains(explicitCredential));
        assertTrue(requestPaths.isEmpty());

        stdout.reset();
        stderr.reset();
        int missingExplicitCode = run(new String[]{"upstream", "inspect", "permissions", "--base-url", baseUrl(),
                "--principal-credential-env", "P1C_TYPED_CREDENTIAL"}, Map.of());

        assertEquals(2, missingExplicitCode);
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("TYPED_MANAGEMENT_CREDENTIAL_SOURCE_MISSING"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void typedManagementExplainUsesRegisteredPreflightAndDoesNotEchoReferences() {
        String principalCredential = "p1c-principal-secret";
        responseOverride = """
                {"code":0,"data":{
                  "allowed":true,
                  "reasonCode":"AUTHZ_ALLOW",
                  "nonBinding":true,
                  "decisionId":"decision-internal-secret",
                  "correlationId":"correlation-internal-secret"
                }}
                """;

        int code = run(new String[]{"upstream", "inspect", "permissions", "--explain-auth",
                "--base-url", baseUrl(),
                "--route-id", "mvc:get:/api/v1/management/v1/auth/whoami",
                "--action-id", "auth.whoami",
                "--target-reference", "target-opaque-1",
                "--impact-reference", "impact-opaque-1",
                "--reason-reference", "reason-opaque-1"},
                env("NAVI_PRINCIPAL_CREDENTIAL", principalCredential));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTypedManagementRequest("/api/v1/management/v1/auth/explain", "POST", principalCredential);
        assertTrue(lastBody.contains("\"routeId\":\"mvc:get:/api/v1/management/v1/auth/whoami\""));
        assertTrue(lastBody.contains("\"actionId\":\"auth.whoami\""));
        assertTrue(lastBody.contains("\"targetReference\":\"target-opaque-1\""));
        assertTrue(output.contains("preflight=PREFLIGHT"));
        assertTrue(output.contains("nonBinding=true"));
        assertTrue(output.contains("targetOwnerGrantTenant=UNRESOLVED_SERVER_SIDE"));
        assertTrue(output.contains("mutationAuthorization=REAUTHORIZE_ON_SERVER"));
        assertFalse(output.contains(principalCredential));
        assertFalse(output.contains("target-opaque-1"));
        assertFalse(output.contains("impact-opaque-1"));
        assertFalse(output.contains("reason-opaque-1"));
        assertFalse(output.contains("decision-internal-secret"));
        assertFalse(output.contains("correlation-internal-secret"));
    }

    @Test
    void typedManagementExplainRejectsUnregisteredOrIncompleteReferencesBeforeHttpDispatch() {
        Map<String, String> typedEnv = env("NAVI_PRINCIPAL_CREDENTIAL", "p1c-principal-secret");
        int unregisteredCode = run(new String[]{"upstream", "inspect", "permissions", "--explain-auth",
                "--base-url", baseUrl(),
                "--route-id", "mvc:post:/api/v1/legacy/mutate",
                "--action-id", "legacy.mutate"}, typedEnv);

        assertEquals(2, unregisteredCode);
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("TYPED_MANAGEMENT_EXPLAIN_ROUTE_ACTION_UNREGISTERED"));
        assertTrue(requestPaths.isEmpty());

        stdout.reset();
        stderr.reset();
        int incompleteReferenceCode = run(new String[]{"upstream", "inspect", "permissions", "--explain-auth",
                "--base-url", baseUrl(),
                "--route-id", "mvc:get:/api/v1/management/v1/auth/whoami",
                "--action-id", "auth.whoami",
                "--target-reference", "target-opaque-1"}, typedEnv);

        assertEquals(2, incompleteReferenceCode);
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("TYPED_MANAGEMENT_EXPLAIN_REFERENCE_SET_INCOMPLETE"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void typedManagementExplainRejectsBindingResponse() {
        responseOverride = """
                {"code":0,"data":{"allowed":true,"reasonCode":"AUTHZ_ALLOW","nonBinding":false}}
                """;
        int code = run(new String[]{"upstream", "inspect", "permissions", "--explain-auth",
                "--base-url", baseUrl(),
                "--route-id", "mvc:get:/api/v1/management/v1/auth/permissions",
                "--action-id", "auth.permissions.inspect"},
                env("NAVI_PRINCIPAL_CREDENTIAL", "p1c-principal-secret"));

        assertEquals(2, code);
        assertEquals("/api/v1/management/v1/auth/explain", lastPath);
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("TYPED_MANAGEMENT_EXPLAIN_NON_BINDING_REQUIRED"));
        assertFalse(stdout.toString(StandardCharsets.UTF_8).contains("allowed="));
    }

    @Test
    void authLoginWritesAdminTokenWithoutPrintingSecrets() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{
                  "token":"jwt-secret-token",
                  "tokenType":"Bearer",
                  "expiresIn":86400,
                  "user":{
                    "id":"user-1",
                    "tenantId":"tenant-1",
                    "username":"tenant-admin",
                    "roles":["TENANT_ADMIN"]
                  }
                }}
                """;

        int code = run(new String[]{"upstream", "auth", "login",
                "--base-url", baseUrl(),
                "--username", "tenant-admin",
                "--password-env", "NAVI_LOGIN_PASSWORD",
                "--write-profile"}, env("NAVI_LOGIN_PASSWORD", "pw-secret-value"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(tempDir.resolve(".navigator").resolve("upstream.env"), StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/auth/login", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastAuthorizationHeader);
        assertTrue(lastBody.contains("\"username\":\"tenant-admin\""));
        assertTrue(lastBody.contains("\"password\":\"pw-secret-value\""));
        assertTrue(profile.contains("NAVI_BASE_URL=" + baseUrl()));
        assertTrue(profile.contains("NAVI_ADMIN_TOKEN=jwt-secret-token"));
        assertTrue(profile.contains("NAVI_TENANT_ID=tenant-1"));
        assertTrue(profile.contains("NAVI_ADMIN_USER_ID=user-1"));
        assertTrue(profile.contains("NAVI_ADMIN_USERNAME=tenant-admin"));
        assertTrue(output.contains("auth login ok"));
        assertTrue(output.contains("userId=user-1"));
        assertTrue(output.contains("tenantId=tenant-1"));
        assertTrue(output.contains("stored=NAVI_BASE_URL,NAVI_ADMIN_TOKEN,NAVI_TENANT_ID,NAVI_ADMIN_USER_ID,NAVI_ADMIN_USERNAME"));
        assertFalse(output.contains("jwt-secret-token"));
        assertFalse(output.contains("pw-secret-value"));
    }

    @Test
    void authLoginRequiresWriteProfileBeforeExchange() {
        int code = run(new String[]{"upstream", "auth", "login",
                "--base-url", baseUrl(),
                "--username", "tenant-admin",
                "--password-env", "NAVI_LOGIN_PASSWORD"}, env("NAVI_LOGIN_PASSWORD", "pw-secret-value"));

        assertEquals(2, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("auth login requires --write-profile"));
        assertTrue(requestPaths.isEmpty());
        assertFalse(stderr.toString(StandardCharsets.UTF_8).contains("pw-secret-value"));
    }

    @Test
    void canonicalRuntimeTokenUsesSecretHeaderAndMasksOutput() {
        responseOverride = "{\"accessToken\":\"cat-runtime-secret\",\"appKey\":\"cak-test\",\"clientAppId\":\"app-1\",\"expiresInSeconds\":1800}";
        Map<String, String> env = env("NAVI_SECRET_ENV", "cas-secret-value");

        int code = run(new String[]{"upstream", "runtime", "token",
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
        assertTrue(output.contains("runtimeToken.expiryStatus=OK"));
        assertTrue(output.contains("runtimeToken.refresh=automatic when NAVI_CLIENT_APP_SECRET is present"));
    }

    @Test
    void runtimeTokenRejectsAdminAndControlCredentialsFromProfile() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_CLIENT_APP_KEY=cak-runtime-key
                NAVI_CLIENT_APP_SECRET=cas-runtime-secret
                NAVI_ADMIN_API_KEY=naa-expired-admin-key
                NAVI_CONTROL_API_KEY=cac-expired-control-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        int code = run(new String[]{"upstream", "runtime-token"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertNull(lastPath);
        assertNull(lastUpstreamAdminKeyHeader);
        assertNull(lastClientAppControlKeyHeader);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("runtime lane refuses mixed credential material"));
        assertFalse(output.contains("runtime-token ok"));
        assertFalse(output.contains("naa-expired-admin-key"));
        assertFalse(output.contains("cac-expired-control-key"));
        assertFalse(output.contains("cas-runtime-secret"));
        assertFalse(output.contains("cat-runtime-secret"));
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
        assertTrue(output.contains("runtimeToken.expiryStatus=OK"));
        assertTrue(output.contains("runtimeToken.refresh=automatic when NAVI_CLIENT_APP_SECRET is present"));
        assertFalse(output.contains("cak-test"));
        assertFalse(output.contains("cas-secret-value"));
        assertFalse(output.contains("cat-written-secret"));
    }

    @Test
    void runtimeTokenWriteProfileAtomicallyReplacesGroupReadablePosixProfileAs0600() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(tempDir)
                .supportsFileAttributeView(PosixFileAttributeView.class));
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Path profile = profileDir.resolve("upstream.env");
        Files.writeString(profile, """
                NAVI_BASE_URL=%s
                NAVI_CLIENT_APP_KEY=cak-test
                NAVI_CLIENT_APP_SECRET=cas-secret-value
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(profile, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE));
        responseOverride = "{\"accessToken\":\"cat-written-secret\",\"appKey\":\"cak-test\",\"clientAppId\":\"app-1\"}";

        int code = run(new String[]{"upstream", "runtime-token", "--write-profile"}, Map.of());

        assertEquals(0, code);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(profile));
        try (var children = Files.list(profileDir)) {
            assertEquals(List.of("upstream.env"), children.map(path -> path.getFileName().toString()).sorted().toList());
        }
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
    void adminKeyInspectPrintsCurrentCredentialWithoutSecret() throws Exception {
        Files.writeString(tempDir.resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{
                  "credentialId":"ucaac-1",
                  "principalId":"TMS",
                  "credentialKeyPrefix":"naa_",
                  "credentialKeySuffix":"-key",
                  "upstreamSystemId":"TMS",
                  "authorizedTenantIds":["TMS"],
                  "authorizedClientAppNamespace":"TMS",
                  "scopes":["CLIENT_APP_MANAGE","CLIENT_APP_CONTROL_KEY_ISSUE"],
                  "status":"ACTIVE",
                  "expiresAt":"2099-06-01T00:00:00",
                  "sourceRequestId":"uabr-1"
                }}
                """;

        int code = run(new String[]{"upstream", "admin-key", "inspect",
                "--profile", "upstream.env"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-admin/admin-credential/current", lastPath);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(output.contains("admin-key inspect ok"));
        assertTrue(output.contains("credential credentialId=ucaac-1 principalId=TMS upstreamSystemId=TMS status=ACTIVE"));
        assertTrue(output.contains("credential authorizedTenantIds=TMS"));
        assertTrue(output.contains("credential scopes=CLIENT_APP_MANAGE,CLIENT_APP_CONTROL_KEY_ISSUE"));
        assertTrue(output.contains("credential expiryStatus=OK"));
        assertTrue(output.contains("credential sourceRequestId=uabr-1"));
        assertTrue(output.contains("rotation=use admin-key rotate --credential-id ucaac-1"));
        assertFalse(output.contains("naa-secret-admin-key"));
    }

    @Test
    void adminKeyInspectReportsExpiredCredentialWithoutSecret() throws Exception {
        Files.writeString(tempDir.resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{
                  "credentialId":"ucaac-expired",
                  "principalId":"TMS",
                  "credentialKeyPrefix":"naa_",
                  "credentialKeySuffix":"-key",
                  "upstreamSystemId":"TMS",
                  "authorizedTenantIds":["TMS"],
                  "authorizedClientAppNamespace":"TMS",
                  "scopes":["CLIENT_APP_MANAGE"],
                  "status":"ACTIVE",
                  "expiresAt":"2000-01-01T00:00:00",
                  "sourceRequestId":"uabr-expired"
                }}
                """;

        int code = run(new String[]{"upstream", "admin-key", "inspect",
                "--profile", "upstream.env"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(output.contains("admin-key inspect ok"));
        assertTrue(output.contains("credential expiryStatus=EXPIRED"));
        assertTrue(output.contains("credential expiryAction=rotate or re-issue provisioning credential before management operations"));
        assertFalse(output.contains("naa-secret-admin-key"));
    }

    @Test
    void adminKeyInspectReportsUnauthorizedWithoutLeakingExpiredAdminKey() throws Exception {
        Files.writeString(tempDir.resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-expired-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        responseStatusOverride = 401;
        responseOverride = """
                {"code":401,"msg":"credential expired for key=naa-expired-admin-key"}
                """;

        int code = run(new String[]{"upstream", "admin-key", "inspect",
                "--profile", "upstream.env"}, Map.of());

        String error = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(1, code);
        assertEquals("/api/v1/upstream-admin/admin-credential/current", lastPath);
        assertEquals("naa-expired-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(error.contains("HTTP 401"));
        assertTrue(error.contains("credential expired"));
        assertFalse(error.contains("naa-expired-admin-key"));
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
    void adminKeyApprovePassesNoExpirySentinel() {
        responseOverride = """
                {"code":0,"data":{
                  "requestId":"req-1",
                  "requestCodeSuffix":"code",
                  "upstreamSystemId":"x6-tms",
                  "requestedTenantId":"tenant-1",
                  "multiTenant":true,
                  "status":"APPROVED",
                  "authorizedTenantIds":["tenant-1"],
                  "authorizedClientAppNamespace":"tms",
                  "scopes":["CLIENT_APP_MANAGE"],
                  "claimExpiresAt":"2026-05-18T11:00:00",
                  "adminCredentialExpiresAt":null
                }}
                """;

        int code = run(new String[]{"upstream", "admin-key", "approve",
                "--base-url", baseUrl(),
                "--request-code", "nabr-secret-code",
                "--authorized-tenant-ids", "tenant-1",
                "--namespace", "tms",
                "--claim-ttl-minutes", "-1"}, env(
                "NAVI_OPERATOR_API_KEY", "operator-secret-key"));

        assertEquals(0, code);
        assertTrue(lastBody.contains("\"claimTtlMinutes\":-1"));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("admin-key approve ok"));
    }

    @Test
    void adminKeyHelpDocumentsNoExpirySentinel() {
        int code = run(new String[]{"upstream", "admin-key"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(output.contains("[--claim-ttl-minutes <minutes|0|-1>]"));
        assertTrue(output.contains("no-expiry NAVI_ADMIN_API_KEY"));
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
                "--scopes", "CLIENT_APP_MANAGE,BUSINESS_OBJECT_MANAGE,WORKING_DIRECTORY_MANAGE",
                "--write-profile"}, env("NAVI_OPERATOR_API_KEY", "operator-secret-key"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        String profile = Files.readString(profilePath, StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/admin/upstream-admin-credentials/ucaac-1/rotate", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"scopes\":[\"CLIENT_APP_MANAGE\",\"BUSINESS_OBJECT_MANAGE\",\"WORKING_DIRECTORY_MANAGE\"]"));
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
    void platformTenantEnsureUsesUpstreamAdminKeyAndSplitsOneShotCredentials() throws Exception {
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
                  "clientAppCapabilityDomain":"tms.ops",
                  "upstreamSystemId":"TMS",
                  "sourceTenantId":"3",
                  "upstreamRef":"TMS-3",
                  "upstreamNamespace":"TMS",
                  "clientAppKey":"cak-secret-key",
                  "clientAppSecret":"cas-secret-value",
                  "controlApiKey":"cac-secret-control-key",
                  "agentCode":"tms-root-agent",
                  "rootAgentId":"tms-root-agent",
                  "modelConfigId":"model-live",
                  "skillId":"tms.navigator.agent",
                  "workerPoolId":"pool-1",
                  "workerBackend":"LANGGRAPH_BIZ",
                  "physicalWorkerId":"worker-1",
                  "directoryId":"dir-1",
                  "bizWorkerBaseUrl":"http://127.0.0.1:3161",
                  "bindingVersion":"bind-v1",
                  "status":"READY",
                  "activationReady":true,
                  "credentialsReplayable":true,
                  "created":true,
                  "rotated":true,
                  "missingFields":[],
                  "requiredScopes":["CLIENT_APP_MANAGE","CLIENT_APP_CONTROL_KEY_ISSUE"],
                  "actualScopes":["CLIENT_APP_MANAGE","CLIENT_APP_CONTROL_KEY_ISSUE"],
                  "authorizedTenantIds":["TMS"],
                  "blockers":["worker route should be verified"]
                }}
                """;

        Path platformControlProfile = profileDir.resolve("platform").resolve("tms-3-control.env");
        Path tenantRuntimeProfile = profileDir.resolve("tenants").resolve("tms-3-runtime.env");
        Files.createDirectories(platformControlProfile.getParent());
        Files.createDirectories(tenantRuntimeProfile.getParent());
        Files.writeString(platformControlProfile, "NAVI_CLIENT_APP_SECRET=stale-runtime-secret\nNAVI_USER_API_KEY=stale-user-secret\n", StandardCharsets.UTF_8);
        Files.writeString(tenantRuntimeProfile, "NAVI_CONTROL_API_KEY=stale-control-secret\nNAVI_ADMIN_API_KEY=stale-admin-secret\n",
                StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "platform", "tenant", "ensure",
                "--profile", ".navigator/upstream.env",
                "--source-tenant-id", "3",
                "--name", "TMS 3",
                "--capability-domain", "tms.ops",
                "--upstream-ref", "TMS-3",
                "--model-config-id", "model-live",
                "--skill-id", "tms.navigator.agent",
                "--worker-pool-id", "pool-1",
                "--worker-backend", "LANGGRAPH_BIZ",
                "--physical-worker-id", "worker-1",
                "--directory-id", "dir-1",
                "--biz-worker-base-url", "http://127.0.0.1:3161",
                "--rotate-credentials",
                "--platform-control-profile", ".navigator/platform/tms-3-control.env",
                "--tenant-runtime-profile", ".navigator/tenants/tms-3-runtime.env",
                "--write-profile"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        String controlProfile = Files.readString(platformControlProfile, StandardCharsets.UTF_8);
        String runtimeProfile = Files.readString(tenantRuntimeProfile, StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/admin/upstream-tenants/client-apps/ensure", lastPath);
        assertEquals("POST", lastMethod);
        assertNull(lastApiKeyHeader);
        assertNull(lastAuthorizationHeader);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(lastBody.contains("\"sourceSystem\":\"TMS\""));
        assertTrue(lastBody.contains("\"sourceTenantId\":\"3\""));
        assertTrue(lastBody.contains("\"clientAppName\":\"TMS 3\""));
        assertTrue(lastBody.contains("\"upstreamRef\":\"TMS-3\""));
        assertTrue(lastBody.contains("\"workerBackend\":\"LANGGRAPH_BIZ\""));
        assertTrue(lastBody.contains("\"physicalWorkerId\":\"worker-1\""));
        assertTrue(lastBody.contains("\"directoryId\":\"dir-1\""));
        assertTrue(lastBody.contains("\"rotateCredentials\":true"));
        assertTrue(controlProfile.contains("NAVI_BASE_URL=" + baseUrl()));
        assertTrue(controlProfile.contains("NAVI_TENANT_ID=nav_tms_3"));
        assertTrue(controlProfile.contains("NAVI_CLIENT_APP_ID=capp-tms-3"));
        assertTrue(controlProfile.contains("NAVI_CONTROL_API_KEY=cac-secret-control-key"));
        assertFalse(controlProfile.contains("NAVI_CLIENT_APP_KEY="));
        assertFalse(controlProfile.contains("NAVI_CLIENT_APP_SECRET="));
        assertFalse(controlProfile.contains("stale-runtime-secret"));
        assertFalse(controlProfile.contains("NAVI_USER_API_KEY="));
        assertFalse(controlProfile.contains("stale-user-secret"));
        assertTrue(runtimeProfile.contains("NAVI_BASE_URL=" + baseUrl()));
        assertTrue(runtimeProfile.contains("NAVI_TENANT_ID=nav_tms_3"));
        assertTrue(runtimeProfile.contains("NAVI_CLIENT_APP_ID=capp-tms-3"));
        assertTrue(runtimeProfile.contains("NAVI_CLIENT_APP_KEY=cak-secret-key"));
        assertTrue(runtimeProfile.contains("NAVI_CLIENT_APP_SECRET=cas-secret-value"));
        assertFalse(runtimeProfile.contains("NAVI_CONTROL_API_KEY="));
        assertFalse(runtimeProfile.contains("NAVI_ADMIN_API_KEY="));
        assertFalse(runtimeProfile.contains("stale-control-secret"));
        assertFalse(runtimeProfile.contains("stale-admin-secret"));
        assertTrue(runtimeProfile.contains("NAVI_AGENT_CODE=tms-root-agent"));
        assertTrue(runtimeProfile.contains("NAVI_MODEL_CONFIG_ID=model-live"));
        assertTrue(runtimeProfile.contains("NAVI_SKILL_ID=tms.navigator.agent"));
        assertTrue(runtimeProfile.contains("NAVI_WORKER_POOL_ID=pool-1"));
        assertTrue(runtimeProfile.contains("NAVI_WORKER_BACKEND=LANGGRAPH_BIZ"));
        assertTrue(runtimeProfile.contains("NAVI_PHYSICAL_WORKER_ID=worker-1"));
        assertTrue(runtimeProfile.contains("NAVI_DIRECTORY_ID=dir-1"));
        assertTrue(runtimeProfile.contains("NAVI_BIZ_WORKER_BASE_URL=http://127.0.0.1:3161"));
        assertTrue(runtimeProfile.contains("NAVI_SOURCE_TENANT_ID=3"));
        assertTrue(runtimeProfile.contains("NAVI_UPSTREAM_REF=TMS-3"));
        assertTrue(runtimeProfile.contains("NAVI_UPSTREAM_NAMESPACE=TMS"));
        assertTrue(runtimeProfile.contains("NAVI_CLIENT_APP_CAPABILITY_DOMAIN=tms.ops"));
        assertTrue(output.contains("platform tenant ensure ok"));
        assertTrue(output.contains("platformControlStored=NAVI_BASE_URL"));
        assertTrue(output.contains("tenantRuntimeStored=NAVI_BASE_URL"));
        assertTrue(output.contains("created=true"));
        assertTrue(output.contains("rotated=true"));
        assertTrue(output.contains("status=READY"));
        assertTrue(output.contains("activationReady=true"));
        assertTrue(output.contains("workerBackend=LANGGRAPH_BIZ"));
        assertTrue(output.contains("physicalWorkerId=worker-1"));
        assertTrue(output.contains("directoryId=dir-1"));
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
                "--platform-control-profile", ".navigator/platform/tms-3-control.env",
                "--tenant-runtime-profile", ".navigator/tenants/tms-3.env",
                "--write-profile"}, Map.of());

        assertEquals(2, code);
        assertEquals("/api/v1/admin/upstream-tenants/client-apps/ensure", lastPath);
        assertNull(lastAuthorizationHeader);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("CREDENTIALS_NOT_REPLAYABLE"));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("migrationNotice=client-app ensure-tenant is a legacy alias"));
        assertFalse(Files.exists(profileDir.resolve("tenants").resolve("tms-3.env")));
    }

    @Test
    void clientAppEnsureTenantRejectsUnignoredTenantProfileBeforeProvisioning() throws Exception {
        Path upstreamProfile = tempDir.resolve("upstream.env");
        Files.writeString(upstreamProfile, """
                NAVI_BASE_URL=%s
                NAVI_ADMIN_API_KEY=naa-secret-admin-key
                """.formatted(baseUrl()), StandardCharsets.UTF_8);

        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/\n", StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "platform", "tenant", "ensure",
                "--profile", upstreamProfile.toString(),
                "--source-system", "TMS",
                "--source-tenant-id", "3",
                "--platform-control-profile", ".navigator/platform/control.env",
                "--tenant-runtime-profile", "tenant.env",
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
    void canonicalAppEnsureGrantUsesControlPlaneCredentialAndDoesNotPrintTokens() {
        responseOverride = "{\"clientAppId\":\"app-1\",\"upstreamUserId\":\"u-1\",\"status\":\"ENABLED\"}";
        Map<String, String> env = env("CONTROL_ENV", "control-key-secret", "USER_TOKEN_ENV", "staff-token-secret");

        int code = run(new String[]{"upstream", "app", "ensure-grant",
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
    void askSendsMaxTurnsWhenProvided() {
        responseOverride = "{\"code\":0,\"data\":{\"taskId\":\"task-1\",\"status\":\"SUBMITTED\",\"contextId\":\"ctx-1\"}}";

        int code = run(new String[]{"upstream", "ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "hello",
                "--max-turns", "1"}, Map.of());

        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/ask", lastPath);
        assertTrue(lastBody.contains("\"maxTurns\":1"));
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
    void askSendsCodexBizRuntimeOptionsTopLevel() {
        responseOverride = "{\"code\":0,\"data\":{\"taskId\":\"task-1\",\"status\":\"SUBMITTED\",\"contextId\":\"ctx-1\"}}";

        int code = run(new String[]{"upstream", "ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "hello",
                "--provider-type", "codex-biz-worker",
                "--directory-id", "dir-1",
                "--private-account-id", "tenant/world-sim/scenario-1/actor-1",
                "--sandbox-mode", "workspace-write",
                "--approval-policy", "never",
                "--network-access-enabled", "false",
                "--web-search-mode", "disabled",
                "--allowed-tools", "business.functions.schema,business.functions.invoke"}, Map.of());

        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/ask", lastPath);
        assertTrue(lastBody.contains("\"providerType\":\"codex-biz-worker\""));
        assertTrue(lastBody.contains("\"directoryId\":\"dir-1\""));
        assertTrue(lastBody.contains("\"privateAccountId\":\"tenant/world-sim/scenario-1/actor-1\""));
        assertTrue(lastBody.contains("\"sandboxMode\":\"workspace-write\""));
        assertTrue(lastBody.contains("\"approvalPolicy\":\"never\""));
        assertTrue(lastBody.contains("\"networkAccessEnabled\":false"));
        assertTrue(lastBody.contains("\"webSearchMode\":\"disabled\""));
        assertTrue(lastBody.contains("\"allowedTools\":[\"business.functions.schema\",\"business.functions.invoke\"]"));
        assertFalse(lastBody.contains("\"clientContext\""));
    }

    @Test
    void askPreservesExplicitEmptyToolAndFunctionAllowlistsAndPrintsSanitizedEffectiveScopes() {
        responseOverride = """
                {"code":0,"data":{"taskId":"task-1","status":"SUBMITTED","contextId":"ctx-1",
                "effectiveToolCount":0,"effectiveFunctionCount":0,
                "toolScopeSource":"REQUEST_EXPLICIT_EMPTY",
                "functionScopeSource":"REQUEST_EXPLICIT_EMPTY",
                "taskTokenFunctionScopeEmpty":true}}
                """;

        int code = run(new String[]{"upstream", "ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "safe smoke",
                "--max-turns", "1",
                "--allowed-tools", "",
                "--allowed-functions", "none"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(lastBody.contains("\"allowedTools\":[]"));
        assertTrue(lastBody.contains("\"allowedFunctions\":[]"));
        assertTrue(lastBody.contains("\"maxTurns\":1"));
        assertTrue(output.contains("effectiveToolCount=0"));
        assertTrue(output.contains("effectiveFunctionCount=0"));
        assertTrue(output.contains("toolScopeSource=REQUEST_EXPLICIT_EMPTY"));
        assertTrue(output.contains("functionScopeSource=REQUEST_EXPLICIT_EMPTY"));
        assertTrue(output.contains("taskTokenFunctionScopeEmpty=true"));
    }

    @Test
    void askOmitsToolAndFunctionAllowlistsWhenNeitherOptionNorConfigIsProvided() {
        responseOverride = "{\"code\":0,\"data\":{\"taskId\":\"task-1\",\"status\":\"SUBMITTED\",\"contextId\":\"ctx-1\"}}";

        int code = run(new String[]{"upstream", "ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "default scope"}, Map.of());

        assertEquals(0, code);
        assertFalse(lastBody.contains("\"allowedTools\""));
        assertFalse(lastBody.contains("\"allowedFunctions\""));
    }

    @Test
    void safeAskUsesDedicatedNoRuntimeEndpointAndForcesExactEmptyScopes() {
        responseOverride = """
                {"code":0,"data":{"taskId":"smk-1","status":"COMPLETED","contextId":"ctx-1",
                "effectiveToolCount":0,"effectiveFunctionCount":0,
                "toolScopeSource":"SAFE_SMOKE_NO_RUNTIME",
                "toolScopeKind":"NO_RUNTIME_MODEL_TOOL_SURFACE",
                "functionScopeSource":"REQUEST_EXPLICIT_EMPTY",
                "taskTokenFunctionScopeEmpty":true,
                "runtimeDispatched":false,"taskTokenStatus":"REVOKED",
                "result":"SAFE_SMOKE_VERIFIED_NO_RUNTIME_DISPATCH"}}
                """;

        int code = run(new String[]{"upstream", "runtime", "safe-ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "sim-safe-smoke",
                "--max-turns", "1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/safe-smoke", lastPath);
        assertTrue(lastBody.contains("\"maxTurns\":1"));
        assertTrue(lastBody.contains("\"allowedTools\":[]"));
        assertTrue(lastBody.contains("\"allowedFunctions\":[]"));
        assertTrue(output.contains("effectiveToolCount=0"));
        assertTrue(output.contains("effectiveFunctionCount=0"));
        assertTrue(output.contains("toolScopeKind=NO_RUNTIME_MODEL_TOOL_SURFACE"));
        assertTrue(output.contains("runtimeDispatched=false"));
        assertTrue(output.contains("taskTokenStatus=REVOKED"));
    }

    @Test
    void safeAskPrintsCorrelationBeforeNetworkAndCarriesItAcrossTokenAndSafeSmoke() {
        responseOverride = "__RUNTIME_TOKEN_THEN_SAFE_SMOKE__";

        int code = run(new String[]{"upstream", "runtime", "safe-ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "sim-safe-smoke"}, env(
                "NAVI_CLIENT_APP_SECRET", "cas-runtime-secret"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        String requestId = output.lines()
                .filter(line -> line.startsWith("clientRequestId="))
                .map(line -> line.substring("clientRequestId=".length()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, code);
        assertDoesNotThrow(() -> UUID.fromString(requestId));
        assertEquals(List.of(
                "/api/v1/open/client-apps/runtime-token",
                "/api/v1/open/agents/agent-1/safe-smoke"), requestPaths);
        assertEquals(List.of(requestId, requestId), requestClientRequestIds);
        assertEquals("cat-auto-secret", lastClientAppAccessTokenHeader);
        assertTrue(output.indexOf("clientRequestId=") < output.indexOf("taskId="));
        assertFalse(output.contains("cas-runtime-secret"));
    }

    @Test
    void safeAskTokenRejectionReturnsExitOneStableCodeAndNoRawBody() {
        responseOverride = """
                {"code":600,"msg":"RUNTIME_SECRET_MUST_NOT_LEAK RUNTIME_CREDENTIAL_INVALID raw-body cas-runtime-secret Authorization: Bearer leaked"}
                """;

        int code = run(new String[]{"upstream", "runtime", "safe-ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "sim-safe-smoke"}, env(
                "NAVI_CLIENT_APP_SECRET", "cas-runtime-secret"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        String error = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(1, code);
        assertEquals(1, requestPaths.size());
        assertEquals("/api/v1/open/client-apps/runtime-token", lastPath);
        assertTrue(output.matches("(?s).*clientRequestId=[0-9a-f-]{36}.*"));
        assertTrue(error.contains("sanitizedErrorCode=RUNTIME_CREDENTIAL_INVALID"));
        assertTrue(error.contains("clientRequestId="));
        assertFalse(error.contains("raw-body"));
        assertFalse(error.contains("Authorization"));
        assertFalse(error.contains("cas-runtime-secret"));
        assertFalse(error.contains("RUNTIME_SECRET_MUST_NOT_LEAK"));
    }

    @Test
    void safeAskDroppedResponseKeepsCorrelationAndDoesNotRetryOrFallBackToAsk() {
        responseOverride = "__RUNTIME_TOKEN_THEN_SAFE_SMOKE_DROP__";

        int code = run(new String[]{"upstream", "runtime", "safe-ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "sim-safe-smoke"}, env(
                "NAVI_CLIENT_APP_SECRET", "cas-runtime-secret"));

        String requestId = stdout.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("clientRequestId="))
                .map(line -> line.substring("clientRequestId=".length()))
                .findFirst()
                .orElseThrow();
        String error = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(1, code);
        assertEquals(List.of(
                "/api/v1/open/client-apps/runtime-token",
                "/api/v1/open/agents/agent-1/safe-smoke"), requestPaths);
        assertEquals(List.of(requestId, requestId), requestClientRequestIds);
        assertTrue(error.contains("sanitizedErrorCode=SAFE_ASK_RESPONSE_NOT_RECEIVED"));
        assertTrue(error.contains("clientRequestId=" + requestId));
        assertTrue(requestPaths.stream().noneMatch(path -> path.endsWith("/ask")));
        assertFalse(error.contains("cas-runtime-secret"));
    }

    @Test
    void safeAskClientPreconditionFailureStillReturnsStableCodeAndCorrelation() {
        int code = run(new String[]{"upstream", "runtime", "safe-ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "sim-safe-smoke"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        String requestId = output.lines()
                .filter(line -> line.startsWith("clientRequestId="))
                .map(line -> line.substring("clientRequestId=".length()))
                .findFirst()
                .orElseThrow();
        String error = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(1, code);
        assertDoesNotThrow(() -> UUID.fromString(requestId));
        assertTrue(error.contains("sanitizedErrorCode=SAFE_ASK_CLIENT_FAILURE"));
        assertTrue(error.contains("clientRequestId=" + requestId));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void runtimeAuditExactWorksWithoutTaskIdAndPreservesUnknownTriState() {
        responseOverride = "__RUNTIME_AUDIT_EXACT__";
        String requestId = "6a02be06-b9e9-4935-878d-063268031462";

        int code = run(new String[]{"upstream", "runtime", "audit",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--request-id", requestId}, env(
                "NAVI_CLIENT_APP_SECRET", "cas-runtime-secret"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("GET", lastMethod);
        assertTrue(lastPath.startsWith("/api/v1/open/runtime-audits?"));
        assertTrue(lastPath.contains("requestId=" + requestId));
        assertEquals("cak-test", lastClientAppKeyHeader);
        assertEquals("cas-runtime-secret", lastClientAppSecretHeader);
        assertNull(lastClientAppAccessTokenHeader);
        assertNull(lastClientAppControlKeyHeader);
        assertNull(lastUpstreamAdminKeyHeader);
        assertNull(lastAuthorizationHeader);
        assertNull(lastTenantIdHeader);
        assertNull(lastClientRequestIdHeader);
        assertTrue(output.contains("audit[0].taskId=null"));
        assertTrue(output.contains("audit[0].runtimeTokenIssued=UNKNOWN"));
        assertTrue(output.contains("audit[0].runtimeDispatched=UNKNOWN"));
        assertTrue(output.contains("audit[0].safeSmokeRequestReceived=false"));
        assertFalse(output.contains("cas-runtime-secret"));
    }

    @Test
    void runtimeAuditWindowCarriesBoundedFiltersAndLimit() {
        responseOverride = "__RUNTIME_AUDIT_EXACT__";

        int code = run(new String[]{"upstream", "runtime", "audit",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--since", "2026-07-23T14:29:30+08:00",
                "--until", "2026-07-23T14:31:00+08:00",
                "--operation", "safe-ask",
                "--agent-code", "world-sim-order-clerk-v2-dev-20260716-a",
                "--upstream-user-id", "sim-upstream-user-local",
                "--limit", "12"}, env(
                "NAVI_CLIENT_APP_SECRET", "cas-runtime-secret"));

        assertEquals(0, code);
        assertTrue(lastPath.contains("since=2026-07-23T14%3A29%3A30%2B08%3A00"));
        assertTrue(lastPath.contains("until=2026-07-23T14%3A31%3A00%2B08%3A00"));
        assertTrue(lastPath.contains("operation=safe-ask"));
        assertTrue(lastPath.contains("agentCode=world-sim-order-clerk-v2-dev-20260716-a"));
        assertTrue(lastPath.contains("upstreamUserId=sim-upstream-user-local"));
        assertTrue(lastPath.contains("limit=12"));
    }

    @Test
    void runtimeAuditRejectsAdminCredentialBeforeNetwork() {
        int code = run(new String[]{"upstream", "runtime", "audit",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--request-id", "6a02be06-b9e9-4935-878d-063268031462"}, env(
                "NAVI_CLIENT_APP_SECRET", "cas-runtime-secret",
                "NAVI_ADMIN_API_KEY", "naa-admin-secret"));

        assertEquals(2, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("runtime lane refuses mixed credential material: NAVI_ADMIN_API_KEY"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void safeAskRejectsNonEmptyScopeOverrideBeforeNetworkCall() {
        int code = run(new String[]{"upstream", "runtime", "safe-ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "sim-safe-smoke",
                "--allowed-functions", "tms.vehicle.list"}, Map.of());

        assertEquals(2, code);
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("--allowed-functions must be none for runtime safe-ask"));
    }

    @Test
    void askSendsCodexBizRuntimeOptionsFromEnv() {
        responseOverride = "{\"code\":0,\"data\":{\"taskId\":\"task-1\",\"status\":\"SUBMITTED\",\"contextId\":\"ctx-1\"}}";

        int code = run(new String[]{"upstream", "ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "hello"}, env(
                "NAVI_PROVIDER_TYPE", "codex-biz-worker",
                "NAVI_DIRECTORY_ID", "dir-env",
                "NAVI_CODEX_HOME_KEY", "actor-home-env",
                "NAVI_CODEX_SANDBOX_MODE", "workspace-write",
                "NAVI_CODEX_APPROVAL_POLICY", "never",
                "NAVI_CODEX_NETWORK_ACCESS_ENABLED", "false",
                "NAVI_CODEX_WEB_SEARCH_MODE", "disabled",
                "NAVI_ALLOWED_TOOLS", "business.functions.invoke, submit_skill_result"));

        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/ask", lastPath);
        assertTrue(lastBody.contains("\"providerType\":\"codex-biz-worker\""));
        assertTrue(lastBody.contains("\"directoryId\":\"dir-env\""));
        assertTrue(lastBody.contains("\"codexHomeKey\":\"actor-home-env\""));
        assertTrue(lastBody.contains("\"networkAccessEnabled\":false"));
        assertTrue(lastBody.contains("\"allowedTools\":[\"business.functions.invoke\",\"submit_skill_result\"]"));
    }

    @Test
    void askRejectsUnknownOptionBeforeHttpCall() {
        int code = run(new String[]{"upstream", "ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "hello",
                "--allowed-tool", "business.functions.invoke"}, Map.of());

        String error = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertNull(lastPath);
        assertTrue(error.contains("Unknown option: --allowed-tool"));
        assertFalse(error.contains("cat-runtime-secret"));
    }

    @Test
    void askTaskDirectoryRequiredErrorSuggestsDirectoryId() {
        responseOverride = "{\"code\":600,\"msg\":\"TASK_DIRECTORY_REQUIRED: directoryId is required for Actor-owned BizWorker task\"}";

        int code = run(new String[]{"upstream", "ask",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--message", "hello"}, Map.of());

        String error = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertEquals("/api/v1/open/agents/agent-1/ask", lastPath);
        assertTrue(error.contains("TASK_DIRECTORY_REQUIRED"));
        assertTrue(error.contains("--directory-id <id>"));
        assertFalse(error.contains("cat-runtime-secret"));
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
        assertTrue(output.contains("eventKind=final_marker"));
        assertTrue(output.contains("progressType=final"));
        assertTrue(output.contains("terminalStatus=COMPLETED"));
        assertTrue(output.contains("messageReportRef messageId=m-1 type=frame_report ref=frame-report://task-1/frame-1"));
        assertTrue(output.contains("messageArtifactRef messageId=m-1 path=outputs/result.json?token=[REDACTED]"));
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
    void diagnosticsUsesRuntimeHeadersAndRedactsSecrets() {
        responseOverride = """
                {"code":0,"data":{
                  "taskId":"task-1",
                  "agentId":"agent-1",
                  "contextId":"ctx-1",
                  "status":"FAILED",
                  "terminal":true,
                  "terminalStatus":"FAILED",
                  "submittedAt":"2026-05-27T08:00:00",
                  "lastObservedAt":"2026-05-27T08:01:00",
                  "messagesCount":3,
                  "providerTaskId":"wt-1",
                  "workerTaskId":"wt-1",
                  "lastAckedSeq":2,
                  "workerBackend":"OPENAI_CODEX",
                  "providerType":"codex-worker",
                  "failureStage":"PROVIDER_API",
                  "failureSummary":"Provider rejected token=cat-runtime-secret",
                  "cancelCapability":{
                    "cancelSupported":false,
                    "cancelMode":"admin_only",
                    "cleanupSupported":false,
                    "backendLimitations":["runtime_client_app_cancel_not_exposed"]
                  },
                  "correlation":{"originalTaskId":"task-0","attemptNumber":2}
                }}
                """;

        int code = run(new String[]{"upstream", "diagnostics",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--task-id", "task-1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/tasks/task-1/diagnostics", lastPath);
        assertEquals("GET", lastMethod);
        assertEquals("cak-test", lastClientAppKeyHeader);
        assertEquals("cat-runtime-secret", lastClientAppAccessTokenHeader);
        assertEquals("u-1", lastUpstreamUserIdHeader);
        assertTrue(output.contains("taskId=task-1"));
        assertTrue(output.contains("messagesCount=3"));
        assertTrue(output.contains("cancelMode=admin_only"));
        assertTrue(output.contains("backendLimitations=runtime_client_app_cancel_not_exposed"));
        assertTrue(output.contains("failureSummary=Provider rejected token=[REDACTED]"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void diagnosticsSessionDirHelpDocumentsInputsAndSafety() {
        int code = run(new String[]{"upstream", "diagnostics", "session-dir", "--help"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertNull(lastPath);
        assertTrue(output.contains("Usage: navi upstream diagnostics"));
        assertTrue(output.contains("diagnostics session-dir --context-id <contextId>"));
        assertTrue(output.contains("[--data-root <bizWorkerDataRoot>]"));
        assertTrue(output.contains("[--codex-workspace-root <path>]"));
        assertTrue(output.contains("OPENAI_CODEX resolves the Codex navigator_business MCP debug log"));
        assertTrue(output.contains("does not print tokens, headers, credentials, or log contents"));
    }

    @Test
    void diagnosticsSessionDirLocatesLocalBizWorkerSession() throws Exception {
        String contextId = "bctx_20260531_f1_f14f789ea7034a99967b98fce27e2b81";
        String taskId = "lgt_bf909fc73fa64e90";
        Path dataRoot = tempDir.resolve("biz-worker-data");
        Path sessionDir = dataRoot.resolve("runtime").resolve("sessions").resolve("by-date")
                .resolve("2026").resolve("05").resolve("31").resolve("f1").resolve(contextId);
        Path skillToolCallsFile = sessionDir.resolve("logs").resolve("skill-tool-calls")
                .resolve(taskId + ".jsonl");
        Path runtimeMessageEventsFile = sessionDir.resolve("logs").resolve("runtime-message-events")
                .resolve(taskId + ".jsonl");
        Files.createDirectories(skillToolCallsFile.getParent());
        Files.createDirectories(runtimeMessageEventsFile.getParent());
        Files.createDirectories(sessionDir.resolve("logs").resolve("llm-submissions"));
        Files.writeString(skillToolCallsFile, "{\"toolName\":\"tms.dataset.queryModel\"}\n", StandardCharsets.UTF_8);
        Files.writeString(runtimeMessageEventsFile, "{\"event\":\"tool_result\"}\n", StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "diagnostics", "session-dir",
                "--context-id", contextId,
                "--task-id", taskId,
                "--data-root", dataRoot.toString(),
                "--physical-worker-id", "worker-biz-1"}, env("NAVI_CLIENT_APP_ACCESS_TOKEN", "cat-runtime-secret"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertNull(lastPath);
        assertTrue(output.contains("contextId=" + contextId));
        assertTrue(output.contains("taskId=" + taskId));
        assertTrue(output.contains("exists=true"));
        assertTrue(output.contains("workerBackend=LANGGRAPH_BIZ"));
        assertTrue(output.contains("physicalWorkerId=worker-biz-1"));
        assertTrue(output.contains("sessionDirectory=" + sessionDir.toAbsolutePath().normalize()));
        assertTrue(output.contains("logsDirectory=" + sessionDir.resolve("logs").toAbsolutePath().normalize()));
        assertTrue(output.contains("skillToolCallsDirectory="
                + sessionDir.resolve("logs").resolve("skill-tool-calls").toAbsolutePath().normalize()));
        assertTrue(output.contains("skillToolCallsFile=" + skillToolCallsFile.toAbsolutePath().normalize()));
        assertTrue(output.contains("runtimeMessageEventsDirectory="
                + sessionDir.resolve("logs").resolve("runtime-message-events").toAbsolutePath().normalize()));
        assertTrue(output.contains("runtimeMessageEventsFile=" + runtimeMessageEventsFile.toAbsolutePath().normalize()));
        assertTrue(output.contains("llmSubmissionsDirectory="
                + sessionDir.resolve("logs").resolve("llm-submissions").toAbsolutePath().normalize()));
        assertTrue(output.contains("accessHint=local"));
        assertFalse(output.contains("notFoundReason="));
        assertFalse(output.contains("cat-runtime-secret"));
        assertFalse(output.contains("tms.dataset.queryModel"));
        assertFalse(output.contains("tool_result"));
    }

    @Test
    void diagnosticsSessionDirReportsMissingContextWithReason() throws Exception {
        String contextId = "bctx_20260531_f1_f14f789ea7034a99967b98fce27e2b81";
        String taskId = "lgt_bf909fc73fa64e90";
        Path dataRoot = tempDir.resolve("biz-worker-data");
        Path shardDir = dataRoot.resolve("runtime").resolve("sessions").resolve("by-date")
                .resolve("2026").resolve("05").resolve("31").resolve("f1");
        Files.createDirectories(shardDir);
        Path expectedSessionDir = shardDir.resolve(contextId);

        int code = run(new String[]{"upstream", "diagnostics", "session-dir",
                "--context-id", contextId,
                "--task-id", taskId,
                "--data-root", dataRoot.toString()}, env("NAVI_CLIENT_APP_ACCESS_TOKEN", "cat-runtime-secret"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertNull(lastPath);
        assertTrue(output.contains("exists=false"));
        assertTrue(output.contains("sessionDirectory=" + expectedSessionDir.toAbsolutePath().normalize()));
        assertTrue(output.contains("skillToolCallsFile="
                + expectedSessionDir.resolve("logs").resolve("skill-tool-calls").resolve(taskId + ".jsonl")
                .toAbsolutePath().normalize()));
        assertTrue(output.contains("runtimeMessageEventsFile="
                + expectedSessionDir.resolve("logs").resolve("runtime-message-events").resolve(taskId + ".jsonl")
                .toAbsolutePath().normalize()));
        assertTrue(output.contains("accessHint=unavailable"));
        assertTrue(output.contains("notFoundReason=context-not-found"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void diagnosticsSessionDirLocatesCodexBusinessMcpDebugLog() throws Exception {
        String contextId = "bctx_20260701_d3_d30e09334a674aabbf5e0f26d395e073";
        String taskId = "20260701-8bc8";
        String providerTaskId = "7314034b-d9b4-4231-9781-beb5f6f6c349";
        Path workspaceRoot = tempDir.resolve("codex-workspace");
        Path debugLogFile = workspaceRoot.resolve("temp").resolve("codex-worker-3070")
                .resolve("business-mcp-" + providerTaskId + ".log");
        Files.createDirectories(debugLogFile.getParent());
        Files.writeString(debugLogFile, "tool_start name=invoke_business_function\n", StandardCharsets.UTF_8);

        int code = run(new String[]{"upstream", "diagnostics", "session-dir",
                "--context-id", contextId,
                "--task-id", taskId,
                "--provider-task-id", providerTaskId,
                "--worker-backend", "OPENAI_CODEX",
                "--codex-workspace-root", workspaceRoot.toString(),
                "--physical-worker-id", "worker-codex-1"}, env("NAVI_CLIENT_APP_ACCESS_TOKEN", "cat-runtime-secret"));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertNull(lastPath);
        assertTrue(output.contains("contextId=" + contextId));
        assertTrue(output.contains("taskId=" + taskId));
        assertTrue(output.contains("providerTaskId=" + providerTaskId));
        assertTrue(output.contains("exists=true"));
        assertTrue(output.contains("workerBackend=OPENAI_CODEX"));
        assertTrue(output.contains("diagnosticMode=codex-business-mcp"));
        assertTrue(output.contains("physicalWorkerId=worker-codex-1"));
        assertTrue(output.contains("businessMcpDebugLogFile=" + debugLogFile.toAbsolutePath().normalize()));
        assertTrue(output.contains("accessHint=local"));
        assertFalse(output.contains("notFoundReason="));
        assertFalse(output.contains("skillToolCallsFile=" + taskId + ".jsonl"));
        assertFalse(output.contains("tool_start name=invoke_business_function"));
        assertFalse(output.contains("cat-runtime-secret"));
    }

    @Test
    void evidenceUsesRuntimeHeadersAndPrintsRefs() {
        responseOverride = """
                {"code":0,"data":{
                  "taskId":"task-1",
                  "agentId":"agent-1",
                  "contextId":"ctx-1",
                  "status":"COMPLETED",
                  "terminal":true,
                  "terminalStatus":"COMPLETED",
                  "finalAnswer":{"available":true,"summary":"done cat-runtime-secret","source":"task_result"},
                  "structuredOutput":{"available":true,"source":"task_state","value":{"ok":true}},
                  "reportRefs":[{"type":"frame_report","ref":"frame-report://task-1/frame-1","frameId":"frame-1"}],
                  "artifactRefs":[{"path":"outputs/result.json","hash":"abc"}]
                }}
                """;

        int code = run(new String[]{"upstream", "evidence",
                "--base-url", baseUrl(),
                "--client-app-key", "cak-test",
                "--client-app-access-token", "cat-runtime-secret",
                "--agent", "agent-1",
                "--upstream-user-id", "u-1",
                "--task-id", "task-1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/open/agents/agent-1/tasks/task-1/evidence", lastPath);
        assertEquals("GET", lastMethod);
        assertEquals("cat-runtime-secret", lastClientAppAccessTokenHeader);
        assertEquals("u-1", lastUpstreamUserIdHeader);
        assertTrue(output.contains("finalAnswer.available=true"));
        assertTrue(output.contains("finalAnswer.summary=done [REDACTED]"));
        assertTrue(output.contains("structuredOutput.value={\"ok\":true}"));
        assertTrue(output.contains("reportRef type=frame_report ref=frame-report://task-1/frame-1"));
        assertTrue(output.contains("artifactRef path=outputs/result.json"));
        assertFalse(output.contains("cat-runtime-secret"));
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
    void ownerSmokeReturnsNonZeroWhenReadinessFailsDespiteCompleteResources() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), ".navigator/upstream.env\n", StandardCharsets.UTF_8);
        Path profileDir = tempDir.resolve(".navigator");
        Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve("upstream.env"), """
                NAVI_BASE_URL=%s
                NAVI_CLIENT_APP_KEY=cak-test
                NAVI_CLIENT_APP_ACCESS_TOKEN=cat-runtime-secret
                NAVI_AGENT_CODE=agent-1
                NAVI_UPSTREAM_USER_ID=u-1
                NAVI_MODEL_CONFIG_ID=model-env
                NAVI_DIRECTORY_ID=dir-env
                """.formatted(baseUrl()), StandardCharsets.UTF_8);
        responseOverride = """
                {"code":0,"data":{
                  "overallStatus":"FAIL",
                  "agentCode":"agent-1",
                  "upstreamUserId":"u-1",
                  "effectiveModelConfigId":"model-env",
                  "effectiveWorkerBackend":"LANGGRAPH_BIZ",
                  "agentId":"agent-1",
                  "effectiveDirectoryId":"dir-env",
                  "effectivePhysicalWorkerId":"worker-1",
                  "physicalWorkerDiagnostics":[{
                    "role":"biz",
                    "physicalWorkerId":"worker-1",
                    "workerBackend":"LANGGRAPH_BIZ",
                    "source":"BIZ_WORKER_IDENTITY",
                    "executionWorker":true
                  }],
                  "checks":[{
                    "code":"WORKER_POOL_MEMBERSHIP",
                    "status":"FAIL",
                    "message":"execution worker is not an enabled pool member",
                    "action":"Add worker-1 as an enabled pool member"
                  }]
                }}
                """;

        int code = run(new String[]{"upstream", "owner-smoke"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertTrue(output.contains("owner-smoke readiness FAIL"));
        assertTrue(output.contains("check WORKER_POOL_MEMBERSHIP=FAIL"));
        assertTrue(output.contains("owner-smoke resources SKIPPED readiness=FAIL"));
        assertFalse(output.contains("owner-smoke resources FAIL missing="));
        assertFalse(output.contains("cat-runtime-secret"));
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
                    {"code":"ROOT_AGENT_BINDING","status":"FAIL","errorCode":"ROOT_AGENT_CLIENT_APP_MISMATCH","message":"Agent ClientApp binding mismatch: agentId=agent-1 expectedClientAppId=app-1 ownerType=CLIENT_APP ownerId=app-2 agentClientAppId=app-2","action":"Use the profile whose NAVI_CLIENT_APP_ID owns this agent, or resync/register this root agent for the current ClientApp with `upstream agent sync --manifest <agent-manifest.json>`."},
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
        assertTrue(output.contains("check ROOT_AGENT_BINDING=FAIL errorCode=ROOT_AGENT_CLIENT_APP_MISMATCH"));
        assertTrue(output.contains("expectedClientAppId=app-1"));
        assertTrue(output.contains("action=Use the profile whose NAVI_CLIENT_APP_ID owns this agent"));
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
    void modelGrantReportsForbiddenWithoutLeakingControlCredential() {
        responseStatusOverride = 403;
        responseOverride = """
                {"code":403,"msg":"insufficient scope for token=control-key-secret"}
                """;

        int code = run(new String[]{"upstream", "model", "grant",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--model-config-id", "model-live"}, Map.of());

        String error = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(1, code);
        assertEquals("/api/v1/client-apps/app-1/model-config-grants", lastPath);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(error.contains("HTTP 403"));
        assertTrue(error.contains("insufficient scope"));
        assertFalse(error.contains("control-key-secret"));
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
                "--model-name", "codex-latest",
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
    void modelCreateAcceptsAppServerSubscriptionWithoutManagedCredentials() {
        responseOverride = """
                {"code":0,"data":{
                  "id":44,
                  "clientAppId":"app-1",
                  "modelConfigId":"model-app-server",
                  "modelConfigName":"App Server GPT-5.6",
                  "workerBackend":"OPENAI_CODEX_APP_SERVER",
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
                "--name", "App Server GPT-5.6",
                "--model-name", "codex-ultra",
                "--available-models", "codex-latest,codex-ultra",
                "--worker-backend", "OPENAI_CODEX_APP_SERVER",
                "--set-default"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/model-configs", lastPath);
        assertTrue(lastBody.contains("\"workerBackend\":\"OPENAI_CODEX_APP_SERVER\""));
        assertTrue(lastBody.contains("\"modelName\":\"codex-ultra\""));
        assertTrue(lastBody.contains("\"availableModels\":[\"codex-latest\",\"codex-ultra\"]"));
        assertTrue(lastBody.contains("\"baseUrl\":null"));
        assertTrue(lastBody.contains("\"apiKey\":null"));
        assertTrue(output.contains("model create ok"));
    }

    @Test
    void modelTestProbesAppServerWorker() {
        responseOverride = """
                {"code":0,"data":"Codex App Server READY"}
                """;

        int code = run(new String[]{"upstream", "model", "test",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--worker-backend", "OPENAI_CODEX_APP_SERVER",
                "--worker-id", "worker-1",
                "--model-name", "gpt-5.6-sol:ultra"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/model-configs/test-connection", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"workerBackend\":\"OPENAI_CODEX_APP_SERVER\""));
        assertTrue(lastBody.contains("\"workerId\":\"worker-1\""));
        assertTrue(lastBody.contains("\"modelName\":\"gpt-5.6-sol:ultra\""));
        assertTrue(lastBody.contains("\"apiKey\":null"));
        assertTrue(output.contains("model test ok"));
        assertTrue(output.contains("reply=Codex App Server READY"));
    }

    @Test
    void modelTestSavedUsesSelectedWorker() {
        responseOverride = """
                {"code":0,"data":"READY"}
                """;

        int code = run(new String[]{"upstream", "model", "test-saved",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--model-config-id", "model-app-server",
                "--worker-id", "worker-1"}, Map.of());

        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/model-configs/model-app-server/test-connection?workerId=worker-1", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("model test-saved ok"));
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
    void modelClearKeyUsesControlCredentialWithoutApiKey() {
        responseOverride = """
                {"code":0,"data":{
                  "id":43,
                  "clientAppId":"app-1",
                  "modelConfigId":"model-owned",
                  "modelConfigName":"Upstream Codex",
                  "workerBackend":"OPENAI_CODEX",
                  "status":"ENABLED",
                  "isDefault":true,
                  "grantScope":"CLIENT_APP_OWNED"
                }}
                """;

        int code = run(new String[]{"upstream", "model", "clear-key",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--control-api-key", "control-key-secret",
                "--client-app-id", "app-1",
                "--model-config-id", "model-owned"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/client-apps/app-1/model-configs/model-owned/key", lastPath);
        assertEquals("PUT", lastMethod);
        assertEquals("control-key-secret", lastClientAppControlKeyHeader);
        assertTrue(lastBody.contains("\"clearApiKey\":true"));
        assertFalse(lastBody.contains("apiKey"));
        assertTrue(output.contains("model clear-key ok"));
        assertFalse(output.contains("control-key-secret"));
    }

    @Test
    void modelSystemClearKeyUsesUpstreamAdminKey() {
        responseOverride = """
                {"code":0,"data":{
                  "id":"model-upstream",
                  "tenantId":"tenant-1",
                  "name":"Upstream Codex",
                  "modelName":"codex-latest",
                  "workerBackend":"OPENAI_CODEX",
                  "ownerType":"UPSTREAM_SYSTEM",
                  "ownerId":"ups-1",
                  "enabled":true
                }}
                """;

        int code = run(new String[]{"upstream", "model", "system-clear-key",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--admin-api-key", "naa-secret-admin-key",
                "--target-tenant-id", "tenant-1",
                "--model-config-id", "model-upstream"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-admin/model-configs/model-upstream/key?targetTenantId=tenant-1", lastPath);
        assertEquals("PUT", lastMethod);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(lastBody.contains("\"clearApiKey\":true"));
        assertFalse(lastBody.contains("apiKey"));
        assertTrue(output.contains("model system-clear-key ok"));
        assertFalse(output.contains("naa-secret-admin-key"));
    }

    @Test
    void modelSystemListPrintsAuditFieldsWithoutSecret() {
        responseOverride = """
                {"code":0,"data":[{
                  "id":"model-app-server",
                  "tenantId":"tenant-1",
                  "name":"App Server GPT-5.6",
                  "baseUrl":null,
                  "modelName":"codex-ultra",
                  "isDefault":true,
                  "hasApiKey":false,
                  "scope":"RESTRICTED",
                  "allowedWorkerIds":["worker-1","worker-2"],
                  "workerBackend":"OPENAI_CODEX_APP_SERVER",
                  "availableModels":["codex-latest","codex-ultra"],
                  "runtimeBudgetPresetKey":"codex-large",
                  "runtimeBudgetOverrideJson":"{\\"maxOutputTokens\\":8192}",
                  "ownerType":"UPSTREAM_SYSTEM",
                  "ownerId":"ups-1",
                  "enabled":true,
                  "sortOrder":5,
                  "apiKey":"must-not-print"
                }]}
                """;

        int code = run(new String[]{"upstream", "model", "system-list",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--admin-api-key", "naa-secret-admin-key",
                "--target-tenant-id", "tenant-1"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-admin/model-configs?targetTenantId=tenant-1", lastPath);
        assertEquals("GET", lastMethod);
        assertTrue(output.contains("modelConfigCount=1"));
        assertTrue(output.contains("modelConfig.workerBackend=OPENAI_CODEX_APP_SERVER"));
        assertTrue(output.contains("modelConfig.availableModels=codex-latest,codex-ultra"));
        assertTrue(output.contains("modelConfig.allowedWorkerIds=worker-1,worker-2"));
        assertTrue(output.contains("modelConfig.runtimeBudgetPresetKey=codex-large"));
        assertTrue(output.contains("modelConfig.isDefault=true"));
        assertTrue(output.contains("modelConfig.hasApiKey=false"));
        assertFalse(output.contains("must-not-print"));
        assertFalse(output.contains("naa-secret-admin-key"));
    }

    @Test
    void modelSystemGetPrintsOneAuditableConfiguration() {
        responseOverride = """
                {"code":0,"data":{
                  "id":"model-app-server",
                  "tenantId":"tenant-1",
                  "name":"App Server GPT-5.6",
                  "modelName":"gpt-5.6-sol:ultra",
                  "workerBackend":"OPENAI_CODEX_APP_SERVER",
                  "availableModels":["gpt-5.6-sol:ultra"],
                  "allowedWorkerIds":["worker-1"],
                  "enabled":true
                }}
                """;

        int code = run(new String[]{"upstream", "model", "system-get",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--admin-api-key", "naa-secret-admin-key",
                "--target-tenant-id", "tenant-1",
                "--model-config-id", "model-app-server"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("/api/v1/upstream-admin/model-configs/model-app-server?targetTenantId=tenant-1", lastPath);
        assertEquals("GET", lastMethod);
        assertTrue(output.contains("modelConfig.id=model-app-server"));
        assertTrue(output.contains("modelConfig.availableModels=gpt-5.6-sol:ultra"));
        assertTrue(output.contains("modelConfig.allowedWorkerIds=worker-1"));
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
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
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

        String originalOsName = System.getProperty("os.name");
        int code;
        try {
            System.setProperty("os.name", "Windows 11");
            code = run(new String[]{"upstream", "worker-host", "install",
                    "--file", ".navigator/worker-host.json",
                    "--install-shell", "wsl",
                    "--wsl-distro", "Ubuntu",
                    "--wsl-user", "navigator",
                    "--timeout-seconds", "7"}, Map.of(), (command, timeout) -> {
                commands.add(command);
                timeouts.add(timeout.toSeconds());
                return new UpstreamCli.CommandResult(0, "installed token=installer-secret\n");
            });
        } finally {
            if (originalOsName == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOsName);
            }
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
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
        assertTrue(output.contains("Legacy internal compatibility only: worker-pool list/create/register-worker/add-member/status"));
        assertTrue(output.contains("model system-list/system-get/system-create/system-update/system-test"));
        assertTrue(output.contains("[--max-turns <n>]"));
        assertTrue(output.contains("[--allowed-tools <csv|none>]"));
        assertTrue(output.contains("[--allowed-functions <csv|none>]"));
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
                  "path": "/home/sa/workspace/app-shared",
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
                  "path":"/home/sa/workspace/app-shared"
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
        assertTrue(lastBody.contains("\"path\":\"/home/sa/workspace/app-shared\""));
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
                  "modelName":"codex-latest",
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
                "--model-name", "codex-latest",
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

    @Test
    void modelSystemTestSavedUsesAdminCredentialAndTargetTenant() {
        responseOverride = """
                {"code":0,"data":"READY"}
                """;

        int code = run(new String[]{"upstream", "model", "system-test-saved",
                "--base-url", baseUrl(),
                "--tenant-id", "tenant-1",
                "--admin-api-key", "naa-secret-admin-key",
                "--target-tenant-id", "tenant-1",
                "--model-config-id", "model-app-server",
                "--worker-id", "worker-1"}, Map.of());

        assertEquals(0, code);
        assertEquals("/api/v1/upstream-admin/model-configs/model-app-server/test-connection?workerId=worker-1&targetTenantId=tenant-1", lastPath);
        assertEquals("naa-secret-admin-key", lastUpstreamAdminKeyHeader);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("model system-test-saved ok"));
    }

    @Test
    void modelHelpDocumentsAppServerAndGpt56Boundaries() {
        int code = run(new String[]{"upstream", "model", "help"}, Map.of());

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(output.contains("OPENAI_CODEX_APP_SERVER"));
        assertTrue(output.contains("codex-ultra requires OPENAI_CODEX_APP_SERVER"));
        assertTrue(output.contains("test-saved"));
        assertTrue(output.contains("available-models"));
    }

    @Test
    void p1cHardBoundaryHelpMatchesSnapshotAndTypedHelpDoesNotDispatchHttp() throws Exception {
        int rootCode = run(new String[]{"upstream", "--help"}, Map.of());
        String rootOutput = stdout.toString(StandardCharsets.UTF_8);

        assertEquals(0, rootCode);
        assertEquals(readTestResource("/com/foggy/navigator/sdk/cli/p1c-hard-boundary-help-snapshot.txt"),
                String.join("\n", hardBoundaryLines(rootOutput)) + "\n");
        assertTrue(requestPaths.isEmpty());

        stdout.reset();
        stderr.reset();
        int whoamiHelpCode = run(new String[]{"upstream", "auth", "whoami", "--help"},
                env("NAVI_ADMIN_API_KEY", "legacy-secret"));
        String whoamiHelp = stdout.toString(StandardCharsets.UTF_8);

        assertEquals(0, whoamiHelpCode);
        assertTrue(whoamiHelp.contains("X-Navi-Principal-Credential"));
        assertTrue(whoamiHelp.contains("never falls back to NAVI_ADMIN_API_KEY"));
        assertFalse(whoamiHelp.contains("legacy-secret"));
        assertTrue(requestPaths.isEmpty());

        stdout.reset();
        stderr.reset();
        int permissionsHelpCode = run(new String[]{"upstream", "inspect", "permissions", "--help"}, Map.of());
        String permissionsHelp = stdout.toString(StandardCharsets.UTF_8);

        assertEquals(0, permissionsHelpCode);
        assertTrue(permissionsHelp.contains("--explain-auth"));
        assertTrue(permissionsHelp.contains("all-or-none"));
        assertTrue(permissionsHelp.contains("re-authorized by the server"));
        assertTrue(requestPaths.isEmpty());
    }

    @Test
    void p1cProvenanceMatchesCanonicalManifestAndPublishedRelease() throws Exception {
        CliProvenance provenance = CliProvenance.load();
        Path root = repositoryRoot();
        Path manifest = root.resolve("navigator-common/src/main/resources/authorization/route-manifest-v1.csv");
        List<String> manifestLines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        Set<String> routeIds = new HashSet<>();

        assertEquals("1.0.25", provenance.sourceVersion());
        assertEquals("1.0.25", provenance.publishedVersion());
        assertEquals("SOURCE_MATCHES_PUBLISHED", provenance.artifactDrift());
        assertEquals(provenance.sourceVersion(), provenance.publishedVersion());
        assertTrue(Files.readString(root.resolve("navigator-open-sdk/pom.xml"), StandardCharsets.UTF_8)
                .contains("<version>" + provenance.sourceVersion() + "</version>"));
        assertEquals(provenance.manifestEntryCount() + 1, manifestLines.size());
        assertEquals(provenance.manifestSha256(), sha256(manifest));
        for (String line : manifestLines.subList(1, manifestLines.size())) {
            int firstComma = line.indexOf(',');
            assertTrue(firstComma > 0);
            assertTrue(routeIds.add(line.substring(0, firstComma)), "duplicate routeId: " + line);
        }
    }

    @Test
    void p1cExplainInputGuardIsGeneratedFromTheCanonicalManifestAndFailsClosedWhenInvalid() throws Exception {
        Path root = repositoryRoot();
        Path sourceManifest = root.resolve("navigator-common/src/main/resources/authorization/route-manifest-v1.csv");
        byte[] sourceBytes = Files.readAllBytes(sourceManifest);
        byte[] packagedBytes;
        try (var input = UpstreamCliTest.class.getResourceAsStream(TypedManagementExplainCatalog.MANIFEST_RESOURCE)) {
            assertNotNull(input, "missing packaged canonical route manifest");
            packagedBytes = input.readAllBytes();
        }

        assertArrayEquals(sourceBytes, packagedBytes,
                "the SDK explain guard must consume the build-time packaged canonical manifest");
        Map<String, String> canonicalPairs = typedManagementCanonicalPairs(sourceManifest);
        assertEquals(Map.of(
                "mvc:post:/api/v1/management/v1/auth/exchange", "auth.exchange",
                "mvc:post:/api/v1/management/v1/auth/security-actions/authorize", "auth.security-authorize",
                "mvc:get:/api/v1/management/v1/auth/whoami", "auth.whoami",
                "mvc:get:/api/v1/management/v1/auth/permissions", "auth.permissions.inspect",
                "mvc:post:/api/v1/management/v1/auth/explain", "auth.decision.explain"), canonicalPairs,
                "P1B-A exposes exactly the five canonical typed-management route/action pairs");

        TypedManagementExplainCatalog catalog = TypedManagementExplainCatalog.load();
        assertEquals(canonicalPairs, catalog.actionsByRouteId());
        assertTrue(catalog.matches("mvc:get:/api/v1/management/v1/auth/whoami", "auth.whoami"));
        assertFalse(catalog.matches("mvc:get:/api/v1/management/v1/auth/whoami", "auth.exchange"));
        assertFalse(catalog.matches("mvc:post:/api/v1/legacy/mutate", "legacy.mutate"));
        assertFalse(Files.readString(root.resolve(
                        "navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java"),
                StandardCharsets.UTF_8).contains("TYPED_MANAGEMENT_EXPLAIN_ACTIONS"));

        byte[] checksumMismatch = Arrays.copyOf(packagedBytes, packagedBytes.length);
        checksumMismatch[checksumMismatch.length - 1] ^= 1;
        IllegalStateException checksumFailure = assertThrows(IllegalStateException.class,
                () -> TypedManagementExplainCatalog.fromCanonicalManifestBytes(checksumMismatch));
        assertTrue(checksumFailure.getMessage().contains("checksum mismatch"));

        IllegalStateException malformedFailure = assertThrows(IllegalStateException.class,
                () -> TypedManagementExplainCatalog.parseTypedManagementActions(
                        "not,a,canonical,manifest\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(malformedFailure.getMessage().contains("header changed unexpectedly"));
    }

    @Test
    void p1cSkillAndRunbookPreserveTypedCredentialAndTrustBoundaryFaq() throws Exception {
        Path root = repositoryRoot();
        String skill = Files.readString(root.resolve(".agents/skills/navigator-runtime-provisioning/SKILL.md"),
                StandardCharsets.UTF_8);
        String runbook = Files.readString(root.resolve(
                "docs/version-tracker/1.4.3-SNAPSHOT/runbooks/GOV-001-p1c-typed-management-cli-operator-ux.md"),
                StandardCharsets.UTF_8);

        assertAll(
                () -> assertTrue(skill.contains("X-Navi-Principal-Credential")),
                () -> assertTrue(skill.contains("NAVI_ADMIN_API_KEY") && skill.contains("not S1 root or S2 platform/security typed authority")),
                () -> assertTrue(skill.contains("NAVIGATOR_EXTERNAL_ENABLED") && skill.contains("/api/v1/open/**")),
                () -> assertTrue(skill.contains("NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED") && skill.contains("not a bind address")),
                () -> assertTrue(skill.contains("worker-host verify") && skill.contains("BizWorkerIdentity") && skill.contains("WorkerPool")),
                () -> assertTrue(runbook.contains("authorization=UNVERIFIED")),
                () -> assertTrue(runbook.contains("NOT_SUPPLIED_BY_SERVER")),
                () -> assertTrue(runbook.contains("NAVIGATOR_EXTERNAL_ENABLED") && runbook.contains("/api/v1/open/**")),
                () -> assertTrue(runbook.contains("NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED") && runbook.contains("network-exposure")),
                () -> assertTrue(runbook.contains("worker-host update --worker-id <physicalWorkerId>")
                        && runbook.contains("BizWorkerIdentity") && runbook.contains("WorkerPool")),
                () -> assertTrue(runbook.contains("SOURCE_MATCHES_PUBLISHED"))
        );
    }

    private int run(String[] args, Map<String, String> env) {
        return new UpstreamCli(
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                tempDir)
                .run(args, env);
    }

    private void assertTypedManagementRequest(String expectedPath, String expectedMethod, String expectedPrincipalCredential) {
        assertEquals(expectedPath, lastPath);
        assertEquals(expectedMethod, lastMethod);
        assertEquals(expectedPrincipalCredential, lastPrincipalCredentialHeader);
        assertNull(lastApiKeyHeader);
        assertNull(lastAuthorizationHeader);
        assertNull(lastOperatorKeyHeader);
        assertNull(lastUpstreamAdminKeyHeader);
        assertNull(lastClientAppKeyHeader);
        assertNull(lastClientAppSecretHeader);
        assertNull(lastClientAppAccessTokenHeader);
        assertNull(lastClientAppControlKeyHeader);
        assertNull(lastUpstreamUserIdHeader);
        assertNull(lastTenantIdHeader);
    }

    private static List<String> hardBoundaryLines(String output) {
        return output.lines()
                .filter(line -> line.startsWith("For an existing Physical Worker,")
                        || line.startsWith("Typed-management introspection requires exactly one")
                        || line.startsWith("NAVIGATOR_EXTERNAL_ENABLED gates only")
                        || line.startsWith("NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED is Worker-principal strictness"))
                .toList();
    }

    private static String readTestResource(String path) throws Exception {
        try (var input = UpstreamCliTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "missing test resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("CLAUDE.md"))
                    && Files.isDirectory(current.resolve("navigator-open-sdk"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Foggy Navigator repository root is unavailable");
    }

    private static Map<String, String> typedManagementCanonicalPairs(Path manifest) throws Exception {
        Map<String, String> pairs = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            List<String> columns = parseManifestCsvLine(line);
            if (columns.size() != 18) {
                throw new IllegalStateException("Malformed canonical manifest test fixture");
            }
            if ("TYPED_MANAGEMENT_AUTH".equals(columns.get(4))
                    && "CANONICAL_ENFORCE".equals(columns.get(13))
                    && "KEEP".equals(columns.get(14))) {
                String previous = pairs.putIfAbsent(columns.get(0), columns.get(9));
                if (previous != null) {
                    throw new IllegalStateException("Duplicate typed-management route in canonical manifest");
                }
            }
        }
        return Map.copyOf(pairs);
    }

    private static List<String> parseManifestCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        if (quoted) {
            throw new IllegalStateException("Unclosed quote in canonical manifest test fixture");
        }
        values.add(value.toString());
        return values;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
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
