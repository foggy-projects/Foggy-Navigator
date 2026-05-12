package com.foggy.navigator.sdk.api;

import com.foggy.navigator.sdk.NavigatorClient;
import com.foggy.navigator.sdk.model.businessagent.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BusinessAgentApiSmokeTest {

    private static HttpServer server;
    private static int port;
    private static String lastPath;
    private static String lastMethod;
    private static String lastAuthHeader;
    private static String lastAuthorizationHeader;
    private static String lastClientAppKeyHeader;
    private static String lastClientAppSecretHeader;
    private static String lastClientAppAccessTokenHeader;
    private static String lastUpstreamUserIdHeader;
    private static String lastBody;
    private static String responseOverride;
    private NavigatorClient client;

    @BeforeAll
    public static void setUpServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().toString();
            lastMethod = exchange.getRequestMethod();
            lastAuthHeader = exchange.getRequestHeaders().getFirst("X-API-Key");
            lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastClientAppKeyHeader = exchange.getRequestHeaders().getFirst("X-Client-App-Key");
            lastClientAppSecretHeader = exchange.getRequestHeaders().getFirst("X-Client-App-Secret");
            lastClientAppAccessTokenHeader = exchange.getRequestHeaders().getFirst("X-Client-App-Access-Token");
            lastUpstreamUserIdHeader = exchange.getRequestHeaders().getFirst("X-Upstream-User-Id");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            String responseStr = responseOverride != null ? responseOverride : "{\"code\":0, \"data\":{}}";

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseStr.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(responseStr.getBytes());
            os.close();
        });

        server.start();
    }

    @AfterAll
    public static void tearDownServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    public void setUpClient() {
        client = NavigatorClient.builder()
                .baseUrl("http://localhost:" + port)
                .apiKey("sk-test-admin-key")
                .timeout(Duration.ofSeconds(5))
                .build();
        lastPath = null;
        lastMethod = null;
        lastAuthHeader = null;
        lastAuthorizationHeader = null;
        lastClientAppKeyHeader = null;
        lastClientAppSecretHeader = null;
        lastClientAppAccessTokenHeader = null;
        lastUpstreamUserIdHeader = null;
        lastBody = null;
        responseOverride = "{\"code\":0, \"data\":{}}";
    }

    private void assertCommon() {
        assertEquals("sk-test-admin-key", lastAuthHeader, "X-API-Key must be present");
        assertNull(lastAuthorizationHeader, "Authorization must not be present for apiKey auth");
    }

    @Test
    public void testBearerTokenAuthHeader() {
        NavigatorClient bearerClient = NavigatorClient.builder()
                .baseUrl("http://localhost:" + port)
                .bearerToken("tenant-admin-jwt")
                .timeout(Duration.ofSeconds(5))
                .build();

        responseOverride = "[]";
        List<ClientAppDTO> apps = bearerClient.businessAgent().listClientApps();

        assertNotNull(apps);
        assertEquals("/api/v1/client-apps", lastPath);
        assertEquals("GET", lastMethod);
        assertNull(lastAuthHeader, "X-API-Key must not be present for bearer auth");
        assertEquals("Bearer tenant-admin-jwt", lastAuthorizationHeader);
    }

    @Test
    public void testAdminTokenAliasKeepsBearerPrefix() {
        NavigatorClient bearerClient = NavigatorClient.builder()
                .baseUrl("http://localhost:" + port)
                .adminToken("Bearer tenant-admin-jwt")
                .timeout(Duration.ofSeconds(5))
                .build();

        responseOverride = "[]";
        bearerClient.businessAgent().listClientApps();

        assertNull(lastAuthHeader, "X-API-Key must not be present for bearer auth");
        assertEquals("Bearer tenant-admin-jwt", lastAuthorizationHeader);
    }

    @Test
    public void testListClientApps() {
        responseOverride = "[]"; // naked array
        List<ClientAppDTO> apps = client.businessAgent().listClientApps();
        assertNotNull(apps);
        assertEquals("/api/v1/client-apps", lastPath);
        assertEquals("GET", lastMethod);
        assertCommon();
    }

    @Test
    public void testRxCode200IsSuccess() {
        responseOverride = "{\"code\":200,\"data\":[{\"clientAppId\":\"app-rx-200\",\"name\":\"RX 200\",\"createdAt\":\"2026-05-04T12:35:47.460353+08:00\"}]}";
        List<ClientAppDTO> apps = client.businessAgent().listClientApps();

        assertNotNull(apps);
        assertEquals(1, apps.size());
        assertEquals("app-rx-200", apps.get(0).getClientAppId());
        assertNotNull(apps.get(0).getCreatedAt());
        assertCommon();
    }

    @Test
    public void testCreateClientApp() {
        CreateClientAppForm form = new CreateClientAppForm();
        form.setName("Test App");
        form.setCapabilityDomain("domain");
        form.setOwnerUserId("user-1");

        responseOverride = "{\"clientAppId\":\"app-new-123\", \"name\":\"Test App\"}"; // naked object
        ClientAppDTO app = client.businessAgent().createClientApp(form);
        assertNotNull(app);
        assertEquals("app-new-123", app.getClientAppId());
        assertEquals("/api/v1/client-apps", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"capabilityDomain\":\"domain\""), "Payload must have capabilityDomain");
        assertTrue(lastBody.contains("\"ownerUserId\":\"user-1\""), "Payload must have ownerUserId");
        assertCommon();
    }

    @Test
    public void testIssueProvisioningCredential() {
        IssueProvisioningCredentialForm form = new IssueProvisioningCredentialForm();
        form.setTargetTenantId("tenant-x");
        form.setCapabilityDomain("domain");
        form.setExpiresAt(LocalDateTime.of(2026, 6, 1, 0, 0));

        responseOverride = "{\"credentialId\":\"cred-001\", \"appKey\":\"key-abc\"}"; // naked object
        IssuedCredentialDTO cred = client.businessAgent().issueProvisioningCredential(form);
        assertNotNull(cred);
        assertEquals("cred-001", cred.getCredentialId());
        assertEquals("/api/v1/admin/client-apps/provisioning-credentials", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"targetTenantId\":\"tenant-x\""), "Payload must have targetTenantId");
        assertTrue(lastBody.contains("\"expiresAt\":\"2026-06-01T00:00:00\""), "LocalDateTime must be serialized as ISO text");
        assertCommon();
    }

    @Test
    public void testExchangeRuntimeAccessToken() {
        responseOverride = "{\"accessToken\":\"cat-runtime\",\"tokenType\":\"Bearer\",\"expiresInSeconds\":1800,\"appKey\":\"cak-test\"}";
        ClientAppRuntimeAccessTokenDTO token = client.businessAgent()
                .exchangeRuntimeAccessToken("cak-test", "cas-secret");

        assertNotNull(token);
        assertEquals("cat-runtime", token.getAccessToken());
        assertEquals("/api/v1/open/client-apps/runtime-token", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("cak-test", lastClientAppKeyHeader);
        assertEquals("cas-secret", lastClientAppSecretHeader);
        assertCommon();
    }

    @Test
    public void testAskWithClientAppAccessToken() {
        responseOverride = "{\"taskId\":\"task-openapi\",\"status\":\"SUBMITTED\"}";
        var task = client.agents().askWithClientAppAccessToken(
                "tms_skill",
                "提交运单",
                "ctx-1",
                3,
                "cak-test",
                "cat-runtime",
                "tms-user-001");

        assertNotNull(task);
        assertEquals("/api/v1/open/agents/tms_skill/ask", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("cak-test", lastClientAppKeyHeader);
        assertEquals("cat-runtime", lastClientAppAccessTokenHeader);
        assertEquals("tms-user-001", lastUpstreamUserIdHeader);
        assertTrue(lastBody.contains("\"message\":\"提交运单\""));
        assertTrue(lastBody.contains("\"contextId\":\"ctx-1\""));
        assertFalse(lastBody.contains("cas-secret"));
        assertCommon();
    }

    @Test
    public void testCreateSkill() {
        CreateSkillForm form = new CreateSkillForm();
        form.setName("test-skill");

        responseOverride = "{\"skillId\":\"skill-123\", \"name\":\"test-skill\"}"; // naked object
        SkillDTO skill = client.businessAgent().createSkill(form);
        assertNotNull(skill);
        assertEquals("skill-123", skill.getSkillId());
        assertEquals("/api/v1/business-agent/skills", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"name\":\"test-skill\""));
        assertCommon();
    }

    @Test
    public void testMaterializePublicSkill() {
        responseOverride = "{\"skillId\":\"tms_skill\",\"scope\":\"public\",\"status\":\"MATERIALIZED\",\"workerStatusCode\":200}";
        SkillMaterializeResultDTO result = client.businessAgent().materializePublicSkill("tms_skill");
        assertNotNull(result);
        assertEquals("MATERIALIZED", result.getStatus());
        assertEquals("/api/v1/business-agent/skills/tms_skill/materialize", lastPath);
        assertEquals("POST", lastMethod);
        assertEquals("", lastBody);
        assertCommon();
    }

    @Test
    public void testGrantModelConfig() {
        GrantModelConfigForm form = new GrantModelConfigForm();
        form.setModelConfigId("cfg-123");
        form.setIsDefault(true);

        responseOverride = "{\"id\":456, \"status\":\"GRANTED\"}"; // naked object
        ClientAppModelConfigGrantDTO grant = client.businessAgent().grantModelConfig("app-123", form);
        assertNotNull(grant);
        assertEquals(456L, grant.getId());
        assertEquals("/api/v1/client-apps/app-123/model-config-grants", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"modelConfigId\":\"cfg-123\""), "Payload must have modelConfigId");
        assertTrue(lastBody.contains("\"isDefault\":true"), "Payload must have isDefault");
        assertCommon();
    }

    @Test
    public void testGrantUpstreamUserAccess_withServerSideToken() {
        GrantUpstreamUserForm form = new GrantUpstreamUserForm();
        form.setUpstreamUserId("tms-user-001");
        form.setUpstreamUserToken("tms-user-token-secret");
        form.setStatus("ENABLED");

        responseOverride = "{\"grantId\":\"ug-001\", \"upstreamUserId\":\"tms-user-001\", \"status\":\"ENABLED\"}";
        ClientAppUpstreamUserGrantDTO grant = client.businessAgent().grantUpstreamUserAccess("app-123", form);
        assertNotNull(grant);
        assertEquals("ug-001", grant.getGrantId());
        assertEquals("tms-user-001", grant.getUpstreamUserId());
        assertEquals("/api/v1/business-agent/client-apps/app-123/upstream-users", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"upstreamUserId\":\"tms-user-001\""));
        assertTrue(lastBody.contains("\"upstreamUserToken\":\"tms-user-token-secret\""));
        assertCommon();
    }

    @Test
    public void testCreateBusinessAgentTask() {
        CreateBusinessAgentTaskForm form = new CreateBusinessAgentTaskForm();
        form.setClientAppId("app-456");
        form.setWorkerPoolId("pool-789");
        form.setSkillId("skill-001");

        responseOverride = "{\"taskId\":\"task-123\", \"taskScopedToken\":\"token-xyz\"}"; // naked object
        CreatedBusinessAgentTaskDTO task = client.businessAgent().createBusinessAgentTask(form);
        assertNotNull(task);
        assertEquals("task-123", task.getTaskId());
        assertEquals("token-xyz", task.getTaskScopedToken());
        assertEquals("/api/v1/business-agent/tasks", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"clientAppId\":\"app-456\""));
        assertTrue(lastBody.contains("\"skillId\":\"skill-001\""));
        assertCommon();
    }

    @Test
    public void testSyncBusinessAgentBundle() {
        SyncBusinessAgentBundleForm form = new SyncBusinessAgentBundleForm();
        form.setClientAppId("app-456");
        form.setAgentId("world-sim.bug-coordinator.decision.v1");
        form.setName("World Sim");
        form.setWorkerId("worker-001");
        form.setDefaultModelConfigId("model-001");

        responseOverride = "{\"agentId\":\"world-sim.bug-coordinator.decision.v1\", \"skillId\":\"world-sim.bug-coordinator.decision.v1\"}";
        BusinessAgentBundleDTO result = client.businessAgent().syncBusinessAgentBundle(form);

        assertNotNull(result);
        assertEquals("world-sim.bug-coordinator.decision.v1", result.getAgentId());
        assertEquals("/api/v1/business-agent/agent-bundles/sync", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"agentId\":\"world-sim.bug-coordinator.decision.v1\""));
        assertTrue(lastBody.contains("\"workerId\":\"worker-001\""));
        assertCommon();
    }

    @Test
    public void testResumeSuspension() {
        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();

        WorkerGatewayResumeForm.ApprovalResult ar = new WorkerGatewayResumeForm.ApprovalResult();
        ar.setStatus("APPROVED");
        ar.setApprovalId("appr-123");
        form.setApprovalResult(ar);

        WorkerGatewayResumeForm.BindingContext bc = new WorkerGatewayResumeForm.BindingContext();
        bc.setInputHash("hash123");
        bc.setTaskId("task-xxx");
        form.setBindingContext(bc);

        responseOverride = "{\"status\":\"resume_dispatched\", \"suspend_id\":\"susp-001\", \"resume_ref\":\"ref-001\"}"; // naked object

        WorkerGatewayResumeResponseDTO response = client.businessAgent().resumeSuspension("susp-001", form);
        assertNotNull(response);
        assertEquals("susp-001", response.getSuspendId());
        assertEquals("ref-001", response.getResumeRef());
        assertEquals("resume_dispatched", response.getStatus());

        assertEquals("/api/v1/business-agent/suspensions/susp-001/resume", lastPath);
        assertEquals("POST", lastMethod);
        // Ensure snake_case serialization is applied via Jackson
        assertTrue(lastBody.contains("\"approval_result\""), "Must have approval_result snake_case");
        assertTrue(lastBody.contains("\"approval_id\":\"appr-123\""), "Must have approval_id");
        assertTrue(lastBody.contains("\"binding_context\""), "Must have binding_context snake_case");
        assertTrue(lastBody.contains("\"input_hash\":\"hash123\""), "Must have input_hash");
        assertCommon();
    }

    @Test
    public void testTmsOnboardingSequence_usesOrderIdentifierAndCreatesTask() {
        CreateBusinessObjectForm objectForm = new CreateBusinessObjectForm();
        objectForm.setObjectId("tms_order");
        objectForm.setDomain("tms.order");
        objectForm.setName("TMS Order");
        objectForm.setStatus("ENABLED");

        responseOverride = "{\"objectId\":\"tms_order\", \"domain\":\"tms.order\"}";
        client.businessAgent().createBusinessObject(objectForm);
        assertEquals("/api/v1/business-agent/business-objects", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"objectId\":\"tms_order\""));
        assertCommon();

        ImportBusinessFunctionManifestForm functionForm = new ImportBusinessFunctionManifestForm();
        functionForm.setFunctionId("tms.order.submit");
        functionForm.setBusinessObjectId("tms_order");
        functionForm.setVersion("v1");
        functionForm.setDomain("tms.order");
        functionForm.setName("Submit TMS Order");
        functionForm.setRiskLevel("state_change");
        functionForm.setApprovalRequired(false);
        functionForm.setIdempotencyRequired(true);
        functionForm.setStatus("ENABLED");
        functionForm.setManifestJson("""
                {"function_id":"tms.order.submit","input":{"orderIdentifier":"string"}}
                """);
        functionForm.setInputSchemaJson("""
                {"type":"object","required":["orderIdentifier"],"properties":{"orderIdentifier":{"type":"string"}}}
                """);
        functionForm.setOutputSchemaJson("""
                {"type":"object","properties":{"orderIdentifier":{"type":"string"},"result":{"type":"string"}}}
                """);
        functionForm.setLlmVisibleSummary("Submit a TMS order by orderIdentifier.");
        functionForm.setSchemaVisibleSummary("orderIdentifier: string");
        functionForm.setAdapterConfigJson("""
                {"type":"rest","upstream_ref":"tms","method":"POST","path":"/api/orders","adapter":{"body":{"orderIdentifier":"$.input.orderIdentifier"}}}
                """);

        responseOverride = "{\"code\":0,\"data\":null}";
        client.businessAgent().importBusinessFunctionManifest(functionForm);
        assertEquals("/api/v1/business-agent/functions/import", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"functionId\":\"tms.order.submit\""));
        assertTrue(lastBody.contains("orderIdentifier"));
        assertFalse(lastBody.contains("expressOrderId"));
        assertCommon();

        AddFunctionToSkillForm allowForm = new AddFunctionToSkillForm();
        allowForm.setFunctionId("tms.order.submit");
        allowForm.setStatus("ENABLED");
        responseOverride = "{\"allowlistId\":\"allow-001\",\"functionId\":\"tms.order.submit\"}";
        client.businessAgent().addFunctionToSkillAllowlist("tms_skill", allowForm);
        assertEquals("/api/v1/business-agent/skills/tms_skill/functions", lastPath);
        assertTrue(lastBody.contains("\"functionId\":\"tms.order.submit\""));
        assertCommon();

        GrantBusinessFunctionForm functionGrantForm = new GrantBusinessFunctionForm();
        functionGrantForm.setFunctionId("tms.order.submit");
        functionGrantForm.setVersion("v1");
        functionGrantForm.setStatus("ENABLED");
        responseOverride = "{\"grantId\":\"fg-001\",\"functionId\":\"tms.order.submit\",\"version\":\"v1\"}";
        client.businessAgent().grantFunctionToClientApp("app-tms", functionGrantForm);
        assertEquals("/api/v1/business-agent/client-apps/app-tms/function-grants", lastPath);
        assertTrue(lastBody.contains("\"version\":\"v1\""));
        assertCommon();

        GrantSkillToClientAppForm skillGrantForm = new GrantSkillToClientAppForm();
        skillGrantForm.setSkillId("tms_skill");
        skillGrantForm.setStatus("ENABLED");
        responseOverride = "{\"grantId\":\"sg-001\",\"skillId\":\"tms_skill\"}";
        client.businessAgent().grantSkillToClientApp("app-tms", skillGrantForm);
        assertEquals("/api/v1/business-agent/client-apps/app-tms/skill-grants", lastPath);
        assertTrue(lastBody.contains("\"skillId\":\"tms_skill\""));
        assertCommon();

        GrantUpstreamUserForm userGrantForm = new GrantUpstreamUserForm();
        userGrantForm.setUpstreamUserId("tms-user-001");
        userGrantForm.setUpstreamUserToken("tms-user-token-secret");
        userGrantForm.setStatus("ENABLED");
        responseOverride = "{\"grantId\":\"ug-001\",\"upstreamUserId\":\"tms-user-001\"}";
        client.businessAgent().grantUpstreamUserAccess("app-tms", userGrantForm);
        assertEquals("/api/v1/business-agent/client-apps/app-tms/upstream-users", lastPath);
        assertTrue(lastBody.contains("\"upstreamUserToken\":\"tms-user-token-secret\""));
        assertCommon();

        CreateBusinessAgentTaskForm taskForm = new CreateBusinessAgentTaskForm();
        taskForm.setClientAppId("app-tms");
        taskForm.setSessionId("session-tms-001");
        taskForm.setUpstreamUserId("tms-user-001");
        taskForm.setSkillId("tms_skill");
        taskForm.setWorkerPoolId("langgraph-biz-pool");
        responseOverride = "{\"taskId\":\"task-tms-001\",\"taskScopedToken\":\"token-runtime-only\"}";
        CreatedBusinessAgentTaskDTO task = client.businessAgent().createBusinessAgentTask(taskForm);
        assertEquals("task-tms-001", task.getTaskId());
        assertEquals("/api/v1/business-agent/tasks", lastPath);
        assertTrue(lastBody.contains("\"upstreamUserId\":\"tms-user-001\""));
        assertFalse(lastBody.contains("orderIdentifier"), "Task creation must not carry business inputs");
        assertCommon();
    }
}
