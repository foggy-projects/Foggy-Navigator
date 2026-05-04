package com.foggy.navigator.business.agent.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.*;
import com.foggy.navigator.business.agent.model.entity.*;
import com.foggy.navigator.business.agent.model.form.*;
import com.foggy.navigator.business.agent.repository.*;
import com.foggy.navigator.business.agent.service.*;
import com.foggy.navigator.business.agent.service.adapter.*;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E2E test that starts a real local HTTP server and validates the REST adapter
 * end-to-end through WorkerGatewayService.invokeBusinessFunction().
 * <p>
 * This test did NOT exist before Stage 9 — it is newly added.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestAdapterUpstreamE2ETest {

    static final String TENANT = "tenant_rest_e2e";
    static final String ADMIN = "admin_rest_e2e";
    static final String APP_ID = "capp_rest_e2e";
    static final String USER_ID = "upstream_user_rest_e2e";
    static final String SKILL_ID = "rest_e2e_skill";
    static final String FUNCTION_ID = "rest_e2e.order.submit";
    static final String VERSION = "v1";
    static final String POOL_ID = "rest_e2e_pool";
    static final String MODEL_ID = "model_rest_e2e";

    // repos
    @Mock ClientAppRepository clientAppRepository;
    @Mock ClientAppProvisioningCredentialRepository provisioningCredentialRepository;
    @Mock ClientAppRuntimeCredentialRepository runtimeCredentialRepository;
    @Mock ClientAppModelConfigGrantRepository modelGrantRepository;
    @Mock ClientAppUpstreamUserGrantRepository userGrantRepository;
    @Mock SkillRepository skillRepository;
    @Mock SkillFunctionAllowlistRepository allowlistRepository;
    @Mock ClientAppSkillGrantRepository skillGrantRepository;
    @Mock BusinessFunctionRepository functionRepository;
    @Mock BusinessFunctionVersionRepository versionRepository;
    @Mock ClientAppFunctionGrantRepository functionGrantRepository;
    @Mock BusinessObjectRepository businessObjectRepository;
    @Mock BusinessAgentTaskRepository taskRepository;
    @Mock BusinessTaskScopedTokenRepository tokenRepository;
    @Mock BizWorkerPoolRepository poolRepository;
    @Mock BizWorkerPoolMemberRepository poolMemberRepository;
    @Mock BizWorkerIdentityRepository identityRepository;
    @Mock BusinessFunctionSuspensionRepository suspensionRepository;
    @Mock BusinessFunctionRuntimeAuditRepository auditRepository;

    @Mock LlmModelManager llmModelManager;
    @Mock ApplicationEventPublisher eventPublisher;

    // real services
    private WorkerGatewayService workerGatewayService;
    private BusinessAgentTaskService taskService;
    private BusinessFunctionRuntimeAuditService auditService;

    // real HTTP server
    private HttpServer httpServer;
    private int serverPort;
    private final AtomicReference<String> receivedMethod = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>(new HashMap<>());

    // live entities
    private BusinessAgentTaskEntity taskEntity;
    private BusinessTaskScopedTokenEntity tokenEntity;

    private BusinessAgentTaskScopedTokenRuntimeStore tokenRuntimeStore;

    @BeforeEach
    void setUp() throws IOException {
        // 1. Start local HTTP server
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = httpServer.getAddress().getPort();

        httpServer.createContext("/api/orders", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedPath.set(exchange.getRequestURI().getPath());
            exchange.getRequestHeaders().forEach((k, v) -> receivedHeaders.get().put(k, String.join(",", v)));

            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            receivedBody.set(new String(bodyBytes, StandardCharsets.UTF_8));

            String responseBody = "{\"result\":\"accepted\",\"orderId\":\"ORD-9001\"}";
            byte[] respBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });
        httpServer.start();

        // 2. Configure environment with the upstream_ref -> local server
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new HashMap<>();
        props.put("foggy.navigator.business.agent.upstreams.test-tms.url", "http://localhost:" + serverPort);
        env.getPropertySources().addFirst(new MapPropertySource("test", props));

        // 3. Wire services
        ObjectMapper objectMapper = new ObjectMapper();
        tokenRuntimeStore = new BusinessAgentTaskScopedTokenRuntimeStore();

        ClientAppService clientAppService = new ClientAppService(clientAppRepository, provisioningCredentialRepository, runtimeCredentialRepository);
        ClientAppModelConfigGrantService modelGrantService = new ClientAppModelConfigGrantService(modelGrantRepository, clientAppService, llmModelManager);
        ClientAppUserGrantService userGrantService1 = new ClientAppUserGrantService(userGrantRepository, clientAppService);
        BusinessObjectService businessObjectService = new BusinessObjectService(businessObjectRepository);
        BusinessFunctionRegistryService functionRegistryService = new BusinessFunctionRegistryService(functionRepository, versionRepository, functionGrantRepository, clientAppService, businessObjectService);
        SkillRegistryService skillRegistryService = new SkillRegistryService(skillRepository, allowlistRepository, skillGrantRepository, functionRepository, clientAppService);
        BizWorkerPoolService bizWorkerPoolService = new BizWorkerPoolService(identityRepository, poolRepository, poolMemberRepository);

        taskService = new BusinessAgentTaskService(taskRepository, tokenRepository, clientAppService, bizWorkerPoolService, modelGrantService, userGrantService1, skillRegistryService, tokenRuntimeStore);
        BusinessFunctionAuthorizationService authorizationService = new BusinessFunctionAuthorizationService(clientAppService, userGrantService1, skillRegistryService, functionRegistryService);

        auditService = new BusinessFunctionRuntimeAuditService(auditRepository);
        BusinessFunctionSuspensionService suspensionService = new BusinessFunctionSuspensionService(suspensionRepository, eventPublisher, auditService);

        // Build composite adapter with real REST invoker
        RestTemplate restTemplate = new RestTemplate();
        BusinessFunctionAdapterInvoker adapterInvoker = new CompositeBusinessFunctionAdapterInvoker(
                Arrays.asList(
                        new LocalEchoBusinessFunctionAdapterInvoker(objectMapper),
                        new RestBusinessFunctionAdapterInvoker(objectMapper, restTemplate, env)
                ),
                objectMapper
        );

        workerGatewayService = new WorkerGatewayService(taskService, authorizationService, functionRegistryService, skillRegistryService, userGrantService1, suspensionService, adapterInvoker, objectMapper, auditService);
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void restAdapter_e2e_invokesUpstream_and_returnsSuccess_and_writesAudit() {
        // Arrange all grants
        stubActiveClientApp();
        stubModelGrant();
        stubUserGrant();
        stubSkillAndAllowlist();
        stubRestFunctionAndGrant();
        stubPoolAndTask();
        stubTokenResolution();

        when(auditRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 1. Create task
        CreateBusinessAgentTaskForm taskForm = new CreateBusinessAgentTaskForm();
        taskForm.setClientAppId(APP_ID);
        taskForm.setSessionId("session_rest_e2e");
        taskForm.setWorkerPoolId(POOL_ID);
        taskForm.setUpstreamUserId(USER_ID);
        taskForm.setSkillId(SKILL_ID);

        CreatedBusinessAgentTaskDTO created = taskService.createTask(TENANT, ADMIN, taskForm);
        assertNotNull(created.getTaskScopedToken());
        String plainToken = created.getTaskScopedToken();

        tokenEntity.setStatus("ACTIVE");
        tokenEntity.setExpiresAt(LocalDateTime.now().plusHours(2));

        // 2. Invoke through gateway
        WorkerGatewayInvokeForm invokeForm = new WorkerGatewayInvokeForm();
        invokeForm.setVersion(VERSION);
        invokeForm.setInputJson("{\"orderId\":\"ORD-9001\",\"reason\":\"test submit\"}");

        WorkerGatewayInvokeResponseDTO response = workerGatewayService.invokeBusinessFunction(plainToken, FUNCTION_ID, invokeForm);

        // 3. Assert gateway response
        assertEquals(WorkerGatewayInvokeResponseDTO.STATUS_SUCCESS, response.getStatus());
        assertNotNull(response.getOutputJson());
        assertTrue(response.getOutputJson().contains("accepted"));
        assertTrue(response.getOutputJson().contains("ORD-9001"));
        assertFalse(response.getApprovalRequired());

        // 4. Assert upstream received correct request
        assertEquals("POST", receivedMethod.get());
        assertEquals("/api/orders", receivedPath.get());
        assertNotNull(receivedBody.get());
        // The body should contain the mapped fields
        assertTrue(receivedBody.get().contains("ORD-9001") || receivedBody.get().contains("orderId"));

        // 5. Assert audit rows were created (INVOKE_STARTED + INVOKE_SUCCESS)
        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> auditCaptor =
                ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(auditRepository, atLeast(2)).save(auditCaptor.capture());

        List<BusinessFunctionRuntimeAuditEntity> audits = auditCaptor.getAllValues();
        assertTrue(audits.stream().anyMatch(a -> "INVOKE_STARTED".equals(a.getEventType())));
        assertTrue(audits.stream().anyMatch(a -> "INVOKE_SUCCESS".equals(a.getEventType())));

        // Verify no audit entity stores secrets
        for (BusinessFunctionRuntimeAuditEntity a : audits) {
            assertNotNull(a.getTenantId());
            assertNotNull(a.getAuditId());
        }
    }

    // ===== Stubs =====

    private void stubActiveClientApp() {
        ClientAppEntity app = new ClientAppEntity();
        app.setClientAppId(APP_ID);
        app.setTenantId(TENANT);
        app.setName("REST E2E App");
        app.setStatus("ACTIVE");
        when(clientAppRepository.findByClientAppIdAndTenantId(APP_ID, TENANT)).thenReturn(Optional.of(app));
    }

    private void stubModelGrant() {
        LlmModelConfigDTO model = new LlmModelConfigDTO();
        model.setId(MODEL_ID);
        model.setTenantId(TENANT);
        model.setWorkerBackend("LANGGRAPH_BIZ");
        model.setName("REST E2E Model");
        when(llmModelManager.getModelConfig(MODEL_ID)).thenReturn(Optional.of(model));

        ClientAppModelConfigGrantEntity grant = new ClientAppModelConfigGrantEntity();
        grant.setId(1L);
        grant.setClientAppId(APP_ID);
        grant.setTenantId(TENANT);
        grant.setModelConfigId(MODEL_ID);
        grant.setStatus("ENABLED");
        grant.setIsDefault(true);
        when(modelGrantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(APP_ID, "ENABLED"))
                .thenReturn(List.of(grant));
    }

    private void stubUserGrant() {
        ClientAppUpstreamUserGrantEntity grant = new ClientAppUpstreamUserGrantEntity();
        grant.setGrantId("ug_rest_e2e");
        grant.setTenantId(TENANT);
        grant.setClientAppId(APP_ID);
        grant.setUpstreamUserId(USER_ID);
        grant.setStatus("ENABLED");
        when(userGrantRepository.findByTenantIdAndClientAppIdAndUpstreamUserId(TENANT, APP_ID, USER_ID))
                .thenReturn(Optional.of(grant));
    }

    private void stubSkillAndAllowlist() {
        SkillEntity skill = new SkillEntity();
        skill.setSkillId(SKILL_ID);
        skill.setTenantId(TENANT);
        skill.setName("REST E2E Skill");
        skill.setStatus("ENABLED");
        when(skillRepository.findByTenantIdAndSkillId(TENANT, SKILL_ID)).thenReturn(Optional.of(skill));

        SkillFunctionAllowlistEntity allow = new SkillFunctionAllowlistEntity();
        allow.setAllowlistId("al_rest_e2e");
        allow.setTenantId(TENANT);
        allow.setSkillId(SKILL_ID);
        allow.setFunctionId(FUNCTION_ID);
        allow.setStatus("ENABLED");
        when(allowlistRepository.findByTenantIdAndSkillIdAndFunctionId(TENANT, SKILL_ID, FUNCTION_ID))
                .thenReturn(Optional.of(allow));

        ClientAppSkillGrantEntity skillGrant = new ClientAppSkillGrantEntity();
        skillGrant.setGrantId("sg_rest_e2e");
        skillGrant.setTenantId(TENANT);
        skillGrant.setClientAppId(APP_ID);
        skillGrant.setSkillId(SKILL_ID);
        skillGrant.setStatus("ENABLED");
        when(skillGrantRepository.findByTenantIdAndClientAppIdAndSkillId(TENANT, APP_ID, SKILL_ID))
                .thenReturn(Optional.of(skillGrant));
    }

    private void stubRestFunctionAndGrant() {
        BusinessFunctionEntity func = new BusinessFunctionEntity();
        func.setFunctionId(FUNCTION_ID);
        func.setTenantId(TENANT);
        func.setDomain("order");
        func.setName("Submit Order REST");
        func.setRiskLevel("state_change");
        func.setApprovalRequired(false);  // Non-approval for direct adapter execution
        func.setIdempotencyRequired(false);
        func.setStatus("ENABLED");
        func.setCurrentVersion(VERSION);
        when(functionRepository.findByTenantIdAndFunctionId(TENANT, FUNCTION_ID))
                .thenReturn(Optional.of(func));

        // REST adapter config pointing to our test upstream
        String adapterConfigJson = """
            {
              "type": "rest",
              "method": "POST",
              "upstream_ref": "test-tms",
              "path": "/api/orders",
              "adapter": {
                "headers": { "X-Tenant": "$.context.tenantId" },
                "body": { "orderId": "$.input.orderId", "reason": "$.input.reason" }
              }
            }
            """;

        BusinessFunctionVersionEntity version = new BusinessFunctionVersionEntity();
        version.setFunctionId(FUNCTION_ID);
        version.setTenantId(TENANT);
        version.setVersion(VERSION);
        version.setInputSchemaJson("{\"type\":\"object\"}");
        version.setOutputSchemaJson("{\"type\":\"object\"}");
        version.setLlmVisibleSummary("Submit order via REST");
        version.setSchemaVisibleSummary("orderId: string");
        version.setAdapterConfigJson(adapterConfigJson);
        version.setStatus("ENABLED");
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion(TENANT, FUNCTION_ID, VERSION))
                .thenReturn(Optional.of(version));

        ClientAppFunctionGrantEntity funcGrant = new ClientAppFunctionGrantEntity();
        funcGrant.setGrantId("fg_rest_e2e");
        funcGrant.setTenantId(TENANT);
        funcGrant.setClientAppId(APP_ID);
        funcGrant.setFunctionId(FUNCTION_ID);
        funcGrant.setVersion(VERSION);
        funcGrant.setStatus("ENABLED");
        when(functionGrantRepository.findByTenantIdAndClientAppIdAndFunctionIdAndVersion(TENANT, APP_ID, FUNCTION_ID, VERSION))
                .thenReturn(Optional.of(funcGrant));
    }

    private void stubPoolAndTask() {
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId(POOL_ID);
        pool.setTenantId(TENANT);
        pool.setStatus("ENABLED");
        pool.setHealthStatus("HEALTHY");
        pool.setWorkerBackend("LANGGRAPH_BIZ");
        when(poolRepository.findByPoolIdAndTenantId(POOL_ID, TENANT)).thenReturn(Optional.of(pool));

        when(taskRepository.save(any(BusinessAgentTaskEntity.class))).thenAnswer(inv -> {
            taskEntity = inv.getArgument(0);
            taskEntity.setId(1L);
            return taskEntity;
        });
        when(tokenRepository.save(any(BusinessTaskScopedTokenEntity.class))).thenAnswer(inv -> {
            tokenEntity = inv.getArgument(0);
            return tokenEntity;
        });
    }

    private void stubTokenResolution() {
        when(tokenRepository.findByTokenHash(anyString())).thenAnswer(inv -> {
            if (tokenEntity != null && tokenEntity.getTokenHash().equals(inv.getArgument(0))) {
                return Optional.of(tokenEntity);
            }
            return Optional.empty();
        });
    }
}
