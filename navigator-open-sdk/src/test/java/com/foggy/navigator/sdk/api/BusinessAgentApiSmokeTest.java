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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BusinessAgentApiSmokeTest {

    private static HttpServer server;
    private static int port;
    private static String lastPath;
    private static String lastMethod;
    private static String lastAuthHeader;
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
        lastBody = null;
        responseOverride = "{\"code\":0, \"data\":{}}";
    }

    private void assertCommon() {
        assertEquals("sk-test-admin-key", lastAuthHeader, "X-API-Key must be present");
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

        responseOverride = "{\"credentialId\":\"cred-001\", \"appKey\":\"key-abc\"}"; // naked object
        IssuedCredentialDTO cred = client.businessAgent().issueProvisioningCredential(form);
        assertNotNull(cred);
        assertEquals("cred-001", cred.getCredentialId());
        assertEquals("/api/v1/admin/client-apps/provisioning-credentials", lastPath);
        assertEquals("POST", lastMethod);
        assertTrue(lastBody.contains("\"targetTenantId\":\"tenant-x\""), "Payload must have targetTenantId");
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
}
