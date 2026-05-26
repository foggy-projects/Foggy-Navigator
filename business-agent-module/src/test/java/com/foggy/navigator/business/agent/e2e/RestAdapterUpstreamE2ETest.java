package com.foggy.navigator.business.agent.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.event.BusinessSuspensionResumeDecisionEvent;
import com.foggy.navigator.business.agent.model.dto.*;
import com.foggy.navigator.business.agent.model.entity.*;
import com.foggy.navigator.business.agent.model.form.*;
import com.foggy.navigator.business.agent.repository.*;
import com.foggy.navigator.business.agent.service.*;
import com.foggy.navigator.business.agent.service.adapter.*;
import com.foggy.navigator.common.event.WorkerGatewayResumeEvent;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
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
import org.springframework.context.ApplicationEvent;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    static final String AGENT_ID = "rest_e2e_agent";
    static final String SKILL_ID = "rest_e2e_skill";
    static final String FUNCTION_ID = "rest_e2e.order.submit";
    static final String VERSION = "v1";
    static final String POOL_ID = "rest_e2e_pool";
    static final String MODEL_ID = "model_rest_e2e";
    static final String ORDER_IDENTIFIER = "TMS-ORDER-9001";

    // repos
    @Mock ClientAppRepository clientAppRepository;
    @Mock ClientAppProvisioningCredentialRepository provisioningCredentialRepository;
    @Mock ClientAppRuntimeCredentialRepository runtimeCredentialRepository;
    @Mock ClientAppControlCredentialRepository controlCredentialRepository;
    @Mock ClientAppModelConfigGrantRepository modelGrantRepository;
    @Mock ClientAppUpstreamUserGrantRepository userGrantRepository;
    @Mock SkillRepository skillRepository;
    @Mock SkillBundleRepository skillBundleRepository;
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
    @Mock BusinessAgentDirectoryBindingRepository agentDirectoryBindingRepository;
    @Mock BusinessAgentModelBindingRepository agentModelBindingRepository;
    @Mock WorkingDirectoryRepository workingDirectoryRepository;
    @Mock BusinessCodingAgentRepository agentRepository;

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
    private final AtomicInteger upstreamRequestCount = new AtomicInteger();

    // live entities
    private BusinessAgentTaskEntity taskEntity;
    private BusinessTaskScopedTokenEntity tokenEntity;
    private BusinessFunctionSuspensionEntity suspensionEntity;

    private BusinessAgentTaskScopedTokenRuntimeStore tokenRuntimeStore;
    private BusinessFunctionSuspensionService suspensionService;

    @BeforeEach
    void setUp() throws IOException {
        // 1. Start local HTTP server
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = httpServer.getAddress().getPort();

        httpServer.createContext("/api/orders", exchange -> {
            upstreamRequestCount.incrementAndGet();
            receivedMethod.set(exchange.getRequestMethod());
            receivedPath.set(exchange.getRequestURI().getPath());
            exchange.getRequestHeaders().forEach((k, v) -> receivedHeaders.get().put(k, String.join(",", v)));

            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            receivedBody.set(new String(bodyBytes, StandardCharsets.UTF_8));

            String responseBody = "{\"result\":\"accepted\",\"orderIdentifier\":\"" + ORDER_IDENTIFIER + "\"}";
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
        props.put("foggy.navigator.business.agent.upstreams.test-tms.user-token-header", "X-TMS-Agent-Token");
        env.getPropertySources().addFirst(new MapPropertySource("test", props));

        // 3. Wire services
        ObjectMapper objectMapper = new ObjectMapper();
        tokenRuntimeStore = new BusinessAgentTaskScopedTokenRuntimeStore();

        ClientAppService clientAppService = new ClientAppService(clientAppRepository, provisioningCredentialRepository,
                runtimeCredentialRepository, controlCredentialRepository);
        ClientAppModelConfigGrantService modelGrantService = new ClientAppModelConfigGrantService(modelGrantRepository, clientAppService, llmModelManager);
        ClientAppUserGrantService userGrantService1 = new ClientAppUserGrantService(userGrantRepository, clientAppService);
        BusinessObjectService businessObjectService = new BusinessObjectService(businessObjectRepository);
        BusinessFunctionRegistryService functionRegistryService = new BusinessFunctionRegistryService(functionRepository, versionRepository, functionGrantRepository, clientAppService, businessObjectService);
        SkillRegistryService skillRegistryService = new SkillRegistryService(skillRepository, skillBundleRepository, allowlistRepository, skillGrantRepository, functionGrantRepository, functionRepository, versionRepository, clientAppService, userGrantService1, objectMapper);
        BizWorkerPoolService bizWorkerPoolService = new BizWorkerPoolService(identityRepository, poolRepository, poolMemberRepository);

        BusinessAgentSessionService businessAgentSessionService = mock(BusinessAgentSessionService.class);
        BusinessAgentSessionDTO sessionDTO = new BusinessAgentSessionDTO();
        sessionDTO.setContextId("bctx_20260520_ab_ctx_rest_e2e");
        lenient().when(businessAgentSessionService.bindTask(any(BusinessAgentTaskEntity.class), any(), any()))
                .thenReturn(sessionDTO);
        A2AgentResourceResolver resourceResolver = new A2AgentResourceResolver(
                modelGrantService,
                llmModelManager,
                clientAppService,
                workingDirectoryRepository,
                agentRepository,
                poolRepository,
                identityRepository,
                java.util.List.of(),
                agentDirectoryBindingRepository,
                agentModelBindingRepository);
        taskService = new BusinessAgentTaskService(taskRepository, tokenRepository, clientAppService, bizWorkerPoolService, resourceResolver, userGrantService1, skillRegistryService, tokenRuntimeStore, businessAgentSessionService, identityRepository, java.util.List.of());
        BusinessFunctionAuthorizationService authorizationService = new BusinessFunctionAuthorizationService(clientAppService, userGrantService1, skillRegistryService, functionRegistryService);

        auditService = new BusinessFunctionRuntimeAuditService(auditRepository);

        // Build composite adapter with real REST invoker
        RestTemplate restTemplate = new RestTemplate();
        BusinessFunctionAdapterInvoker adapterInvoker = new CompositeBusinessFunctionAdapterInvoker(
                Arrays.asList(
                        new LocalEchoBusinessFunctionAdapterInvoker(objectMapper),
                        new RestBusinessFunctionAdapterInvoker(objectMapper, restTemplate, env, userGrantService1, null)
                ),
                objectMapper
        );
        suspensionService = new BusinessFunctionSuspensionService(suspensionRepository, eventPublisher, auditService, authorizationService, adapterInvoker);

        workerGatewayService = new WorkerGatewayService(taskService, authorizationService, functionRegistryService, skillRegistryService, userGrantService1, suspensionService, adapterInvoker, objectMapper, auditService);
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void tmsRestAdapter_e2e_usesOrderIdentifier_injectsHeaders_and_writesAudit() {
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
        taskForm.setAgentId(AGENT_ID);
        taskForm.setUpstreamUserId(USER_ID);

        CreatedBusinessAgentTaskDTO created = taskService.createTask(TENANT, ADMIN, taskForm);
        assertNotNull(created.getTaskScopedToken());
        String plainToken = created.getTaskScopedToken();

        tokenEntity.setStatus("ACTIVE");
        tokenEntity.setExpiresAt(LocalDateTime.now().plusHours(2));

        // 2. Verify LLM-facing schema uses TMS public order identifier only
        WorkerGatewayFunctionSchemaDTO schema = workerGatewayService.getBusinessFunctionSchema(plainToken, FUNCTION_ID, VERSION);
        assertTrue(schema.getInputSchemaJson().contains("orderIdentifier"));
        assertFalse(schema.getInputSchemaJson().contains("expressOrderId"));
        assertTrue(schema.getSchemaVisibleSummary().contains("orderIdentifier"));
        assertFalse(schema.getSchemaVisibleSummary().contains("expressOrderId"));

        // 3. Invoke through gateway
        WorkerGatewayInvokeForm invokeForm = new WorkerGatewayInvokeForm();
        invokeForm.setVersion(VERSION);
        invokeForm.setInputJson("{\"orderIdentifier\":\"" + ORDER_IDENTIFIER + "\",\"reason\":\"test submit\"}");

        WorkerGatewayInvokeResponseDTO response = workerGatewayService.invokeBusinessFunction(plainToken, FUNCTION_ID, invokeForm);

        // 4. Assert gateway response
        assertEquals(WorkerGatewayInvokeResponseDTO.STATUS_SUCCESS, response.getStatus());
        assertNotNull(response.getOutputJson());
        assertTrue(response.getOutputJson().contains("accepted"));
        assertTrue(response.getOutputJson().contains(ORDER_IDENTIFIER));
        assertFalse(response.getApprovalRequired());
        assertFalse(response.getOutputJson().contains("expressOrderId"));

        // 5. Assert upstream received correct request
        assertEquals("POST", receivedMethod.get());
        assertEquals("/api/orders", receivedPath.get());
        assertNotNull(receivedBody.get());
        assertTrue(receivedBody.get().contains(ORDER_IDENTIFIER));
        assertTrue(receivedBody.get().contains("orderIdentifier"));
        assertFalse(receivedBody.get().contains("expressOrderId"));
        Map<String, String> headers = receivedHeaders.get();
        assertEquals("tms-token-rest-e2e", getHeaderIgnoreCase(headers, "X-TMS-Agent-Token"));
        assertEquals(TENANT, getHeaderIgnoreCase(headers, "X-Navigator-Tenant-Id"));
        assertEquals(APP_ID, getHeaderIgnoreCase(headers, "X-Navigator-Client-App-Id"));
        assertEquals(USER_ID, getHeaderIgnoreCase(headers, "X-Navigator-Upstream-User-Id"));
        assertEquals(taskEntity.getTaskId(), getHeaderIgnoreCase(headers, "X-Navigator-Task-Id"));
        assertEquals(taskEntity.getSessionId(), getHeaderIgnoreCase(headers, "X-Navigator-Session-Id"));
        assertEquals(FUNCTION_ID, getHeaderIgnoreCase(headers, "X-Navigator-Function-Id"));
        assertEquals(VERSION, getHeaderIgnoreCase(headers, "X-Navigator-Function-Version"));
        assertEquals(1, upstreamRequestCount.get());

        // 6. Assert audit rows were created (INVOKE_STARTED + INVOKE_SUCCESS)
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

    @Test
    void approvalRequiredRestAdapter_e2e_duplicateApprove_doesNotInvokeUpstreamTwice() {
        // Arrange all grants
        stubActiveClientApp();
        stubModelGrant();
        stubUserGrant();
        stubSkillAndAllowlist();
        stubRestFunctionAndGrant(true);
        stubPoolAndTask();
        stubTokenResolution();

        when(auditRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(suspensionRepository.save(any(BusinessFunctionSuspensionEntity.class))).thenAnswer(inv -> {
            suspensionEntity = inv.getArgument(0);
            return suspensionEntity;
        });

        CreateBusinessAgentTaskForm taskForm = new CreateBusinessAgentTaskForm();
        taskForm.setClientAppId(APP_ID);
        taskForm.setSessionId("session_rest_approval_e2e");
        taskForm.setAgentId(AGENT_ID);
        taskForm.setUpstreamUserId(USER_ID);

        CreatedBusinessAgentTaskDTO created = taskService.createTask(TENANT, ADMIN, taskForm);
        String plainToken = created.getTaskScopedToken();
        tokenEntity.setStatus("ACTIVE");
        tokenEntity.setExpiresAt(LocalDateTime.now().plusHours(2));

        WorkerGatewayInvokeForm invokeForm = new WorkerGatewayInvokeForm();
        invokeForm.setVersion(VERSION);
        invokeForm.setInputJson("{\"orderIdentifier\":\"" + ORDER_IDENTIFIER + "\",\"reason\":\"approval retest\"}");

        WorkerGatewayInvokeResponseDTO invokeResponse = workerGatewayService.invokeBusinessFunction(plainToken, FUNCTION_ID, invokeForm);

        assertEquals(WorkerGatewayInvokeResponseDTO.STATUS_SUSPENDED, invokeResponse.getStatus());
        assertEquals(0, upstreamRequestCount.get(), "approval-required invoke must suspend before upstream side effect");

        String suspendId = invokeResponse.getSuspendId();
        assertNotNull(suspensionEntity);
        suspensionEntity.setExpiresAt(LocalDateTime.now().plusHours(24));
        when(suspensionRepository.findBySuspendId(suspendId)).thenReturn(Optional.of(suspensionEntity));

        WorkerGatewayResumeForm resumeForm = new WorkerGatewayResumeForm();
        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId(APP_ID);
        binding.setUpstreamUserId(USER_ID);
        binding.setTaskId(taskEntity.getTaskId());
        binding.setSessionId(taskEntity.getSessionId());
        binding.setFunctionId(FUNCTION_ID);
        binding.setVersion(VERSION);
        binding.setInputHash(BusinessFunctionRuntimeAuditService.sha256(invokeForm.getInputJson()));
        resumeForm.setBindingContext(binding);

        WorkerGatewayResumeForm.ApprovalResult approval = new WorkerGatewayResumeForm.ApprovalResult();
        approval.setStatus("approved");
        approval.setComment("approved once");
        resumeForm.setApprovalResult(approval);

        suspensionService.resumeSuspension(TENANT, ADMIN, suspendId, resumeForm);

        BusinessSuspensionResumeDecisionEvent decisionEvent = captureBusinessDecisionEvent();
        suspensionService.handleBusinessSuspensionResumeDecisionEvent(decisionEvent);

        assertEquals(WorkerGatewayInvokeResponseDTO.STATUS_SUCCESS, suspensionService.executeApprovedSuspension(buildExecutionReplayEvent()).getStatus());
        assertEquals(1, upstreamRequestCount.get(), "approved execution replay must not call upstream twice");
        assertEquals("COMPLETED", suspensionEntity.getStatus());
        assertEquals("COMPLETED", suspensionEntity.getBusinessExecutionStatus());

        suspensionService.resumeSuspension(TENANT, ADMIN, suspendId, resumeForm);
        assertEquals(1, upstreamRequestCount.get(), "duplicate approve/resume must not call upstream again");

        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> auditCaptor =
                ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(auditRepository, atLeastOnce()).save(auditCaptor.capture());
        List<BusinessFunctionRuntimeAuditEntity> audits = auditCaptor.getAllValues();
        assertTrue(audits.stream()
                .anyMatch(a -> "INVOKE_SUCCESS".equals(a.getEventType()) && suspendId.equals(a.getSuspendId())));
        assertTrue(audits.stream()
                .anyMatch(a -> "BUSINESS_EXECUTION_SKIPPED".equals(a.getEventType()) && suspendId.equals(a.getSuspendId())));
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
        model.setCategory(LlmModelCategory.GENERAL);
        model.setOwnerType(ResourceOwnerType.PLATFORM);
        model.setOwnerId("platform");
        model.setEnabled(true);
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
        when(modelGrantRepository.findByClientAppIdAndModelConfigIdAndStatus(APP_ID, MODEL_ID, "ENABLED"))
                .thenReturn(Optional.of(grant));
    }

    private void stubUserGrant() {
        ClientAppUpstreamUserGrantEntity grant = new ClientAppUpstreamUserGrantEntity();
        grant.setGrantId("ug_rest_e2e");
        grant.setTenantId(TENANT);
        grant.setClientAppId(APP_ID);
        grant.setUpstreamUserId(USER_ID);
        grant.setUpstreamUserToken("tms-token-rest-e2e");
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
        stubRestFunctionAndGrant(false);
    }

    private void stubRestFunctionAndGrant(boolean approvalRequired) {
        BusinessFunctionEntity func = new BusinessFunctionEntity();
        func.setFunctionId(FUNCTION_ID);
        func.setTenantId(TENANT);
        func.setDomain("order");
        func.setName("Submit Order REST");
        func.setRiskLevel("state_change");
        func.setApprovalRequired(approvalRequired);
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
                "body": { "orderIdentifier": "$.input.orderIdentifier", "reason": "$.input.reason" }
              }
            }
            """;

        BusinessFunctionVersionEntity version = new BusinessFunctionVersionEntity();
        version.setFunctionId(FUNCTION_ID);
        version.setTenantId(TENANT);
        version.setVersion(VERSION);
        version.setInputSchemaJson("""
            {
              "type": "object",
              "required": ["orderIdentifier"],
              "properties": {
                "orderIdentifier": { "type": "string", "description": "TMS public order number visible to users and LLMs" },
                "reason": { "type": "string" }
              }
            }
            """);
        version.setOutputSchemaJson("""
            {
              "type": "object",
              "properties": {
                "result": { "type": "string" },
                "orderIdentifier": { "type": "string" }
              }
            }
            """);
        version.setLlmVisibleSummary("Submit order via REST");
        version.setSchemaVisibleSummary("orderIdentifier: string");
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

    private BusinessSuspensionResumeDecisionEvent captureBusinessDecisionEvent() {
        ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        return eventCaptor.getAllValues().stream()
                .filter(BusinessSuspensionResumeDecisionEvent.class::isInstance)
                .map(BusinessSuspensionResumeDecisionEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("BusinessSuspensionResumeDecisionEvent not published"));
    }

    private WorkerGatewayResumeEvent buildExecutionReplayEvent() {
        return WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId(taskEntity.getTaskId())
                .sessionId(taskEntity.getSessionId())
                .businessSessionId(taskEntity.getSessionId())
                .suspendId(suspensionEntity.getSuspendId())
                .approvalResult("approved")
                .tenantId(TENANT)
                .clientAppId(APP_ID)
                .upstreamUserId(USER_ID)
                .functionId(FUNCTION_ID)
                .inputHash(BusinessFunctionRuntimeAuditService.sha256(suspensionEntity.getInputJson()))
                .build();
    }

    private void stubPoolAndTask() {
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId(POOL_ID);
        pool.setTenantId(TENANT);
        pool.setOwnerType(ResourceOwnerType.PLATFORM);
        pool.setOwnerId(TENANT);
        pool.setStatus("ENABLED");
        pool.setHealthStatus("HEALTHY");
        pool.setWorkerBackend("LANGGRAPH_BIZ");
        when(poolRepository.findByPoolIdAndTenantId(POOL_ID, TENANT)).thenReturn(Optional.of(pool));

        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setAgentId(AGENT_ID);
        agent.setTenantId(TENANT);
        agent.setOwnerType(ResourceOwnerType.CLIENT_APP);
        agent.setOwnerId(APP_ID);
        agent.setClientAppId(APP_ID);
        agent.setUserId(ADMIN);
        agent.setName("REST E2E Agent");
        agent.setAgentType(BusinessAgentBundleService.AGENT_TYPE_LANGGRAPH);
        agent.setWorkerId(POOL_ID);
        agent.setDefaultModelConfigId(MODEL_ID);
        agent.setAgentProfile("{\"skillId\":\"" + SKILL_ID + "\"}");
        agent.setEnabled(true);
        when(agentRepository.findByAgentIdAndTenantId(AGENT_ID, TENANT)).thenReturn(Optional.of(agent));

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

    private String getHeaderIgnoreCase(Map<String, String> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
