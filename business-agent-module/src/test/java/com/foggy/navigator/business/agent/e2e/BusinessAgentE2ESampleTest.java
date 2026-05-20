package com.foggy.navigator.business.agent.e2e;

import com.foggy.navigator.business.agent.model.dto.*;
import com.foggy.navigator.business.agent.model.entity.*;
import com.foggy.navigator.business.agent.model.form.*;
import com.foggy.navigator.business.agent.repository.*;
import com.foggy.navigator.business.agent.service.*;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.event.WorkerGatewayResumeEvent;
import com.foggy.navigator.spi.config.LlmModelManager;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Stage 6 E2E Orchestration Sample.
 *
 * Proves the full lifecycle:
 *   provisioning credential -> ClientApp -> credential/model/skill/function/user grants
 *   -> createTask -> task-scoped token -> Worker Gateway list/schema/invoke(SUSPENDED)
 *   -> control-plane resumeSuspension -> RESUME_DISPATCHED + event published
 *
 * Uses Mockito only around repositories and external interfaces (LlmModelManager, EventPublisher).
 * All real service method logic executes in this test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessAgentE2ESampleTest {

    // ---- constants ----
    static final String TENANT = "tenant_stage6";
    static final String ADMIN = "admin_stage6";
    static final String APP_ID = "capp_stage6_fixed";
    static final String USER_ID = "upstream_user_stage6";
    static final String SKILL_ID = "stage6_order_skill";
    static final String FUNCTION_ID = "stage6.order.close_apply.submit";
    static final String VERSION = "v1";
    static final String POOL_ID = "stage6_langgraph_pool";
    static final String MODEL_ID = "model_stage6";
    static final String INPUT_JSON = "{\"orderId\":\"ORD-0001\"}";

    // ---- repos ----
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

    // ---- external deps ----
    @Mock LlmModelManager llmModelManager;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ---- services wired manually ----
    ClientAppService clientAppService;
    ClientAppModelConfigGrantService modelGrantService;
    ClientAppUserGrantService userGrantService;
    BusinessObjectService businessObjectService;
    BusinessFunctionRegistryService functionRegistryService;
    SkillRegistryService skillRegistryService;
    BizWorkerPoolService bizWorkerPoolService;
    ClientAppUserGrantService clientAppUserGrantService;
    BusinessAgentTaskService taskService;
    BusinessFunctionAuthorizationService authorizationService;
    BusinessFunctionSuspensionService suspensionService;
    BusinessFunctionRuntimeAuditService auditService;
    WorkerGatewayService workerGatewayService;

    // ---- live entities persisted across steps ----
    ClientAppEntity appEntity;
    BusinessFunctionEntity functionEntity;
    BusinessFunctionVersionEntity versionEntity;
    ClientAppFunctionGrantEntity functionGrantEntity;
    SkillEntity skillEntity;
    SkillFunctionAllowlistEntity allowlistEntity;
    ClientAppSkillGrantEntity skillGrantEntity;
    ClientAppUpstreamUserGrantEntity userGrantEntity;
    ClientAppModelConfigGrantEntity modelGrantEntity;
    BusinessAgentTaskEntity taskEntity;
    BusinessTaskScopedTokenEntity tokenEntity;
    BusinessFunctionSuspensionEntity suspensionEntity;
    String plainToken;

    private BusinessAgentTaskScopedTokenRuntimeStore tokenRuntimeStore;

    @BeforeEach
    void wireServices() {
        tokenRuntimeStore = new BusinessAgentTaskScopedTokenRuntimeStore();
        clientAppService = new ClientAppService(clientAppRepository, provisioningCredentialRepository,
                runtimeCredentialRepository, controlCredentialRepository);
        modelGrantService = new ClientAppModelConfigGrantService(modelGrantRepository, clientAppService, llmModelManager);
        userGrantService = new ClientAppUserGrantService(userGrantRepository, clientAppService);
        businessObjectService = new BusinessObjectService(businessObjectRepository);
        functionRegistryService = new BusinessFunctionRegistryService(functionRepository, versionRepository, functionGrantRepository, clientAppService, businessObjectService);
        skillRegistryService = new SkillRegistryService(skillRepository, skillBundleRepository, allowlistRepository, skillGrantRepository, functionGrantRepository, functionRepository, versionRepository, clientAppService, userGrantService, objectMapper);
        bizWorkerPoolService = new BizWorkerPoolService(identityRepository, poolRepository, poolMemberRepository);
        clientAppUserGrantService = userGrantService;
        BusinessAgentSessionService businessAgentSessionService = mock(BusinessAgentSessionService.class);
        BusinessAgentSessionDTO sessionDTO = new BusinessAgentSessionDTO();
        sessionDTO.setContextId("bctx_20260520_cd_ctx_stage6");
        lenient().when(businessAgentSessionService.bindTask(any(BusinessAgentTaskEntity.class), any(), any()))
                .thenReturn(sessionDTO);
        taskService = new BusinessAgentTaskService(taskRepository, tokenRepository, clientAppService, bizWorkerPoolService, modelGrantService, userGrantService, skillRegistryService, tokenRuntimeStore, businessAgentSessionService, java.util.List.of());
        authorizationService = new BusinessFunctionAuthorizationService(clientAppService, userGrantService, skillRegistryService, functionRegistryService);
        auditService = new BusinessFunctionRuntimeAuditService(auditRepository);
        com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterInvoker adapterInvoker = new com.foggy.navigator.business.agent.service.adapter.LocalEchoBusinessFunctionAdapterInvoker(objectMapper);
        suspensionService = new BusinessFunctionSuspensionService(suspensionRepository, eventPublisher, auditService, authorizationService, adapterInvoker);
        workerGatewayService = new WorkerGatewayService(taskService, authorizationService, functionRegistryService, skillRegistryService, clientAppUserGrantService, suspensionService, adapterInvoker, objectMapper, auditService);
    }

    // ===== Helper: build active ClientApp entity =====
    private void stubActiveClientApp() {
        appEntity = new ClientAppEntity();
        appEntity.setClientAppId(APP_ID);
        appEntity.setTenantId(TENANT);
        appEntity.setName("Stage6 Order App");
        appEntity.setStatus("ACTIVE");
        when(clientAppRepository.findByClientAppIdAndTenantId(APP_ID, TENANT)).thenReturn(Optional.of(appEntity));
    }

    private void stubModelGrant() {
        LlmModelConfigDTO model = new LlmModelConfigDTO();
        model.setId(MODEL_ID);
        model.setTenantId(TENANT);
        model.setWorkerBackend("LANGGRAPH_BIZ");
        model.setName("Stage6 LangGraph Model");
        when(llmModelManager.getModelConfig(MODEL_ID)).thenReturn(Optional.of(model));

        modelGrantEntity = new ClientAppModelConfigGrantEntity();
        modelGrantEntity.setId(1L);
        modelGrantEntity.setClientAppId(APP_ID);
        modelGrantEntity.setTenantId(TENANT);
        modelGrantEntity.setModelConfigId(MODEL_ID);
        modelGrantEntity.setStatus("ENABLED");
        modelGrantEntity.setIsDefault(true);

        when(modelGrantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(APP_ID, "ENABLED"))
                .thenReturn(List.of(modelGrantEntity));
        when(modelGrantRepository.findByClientAppIdAndModelConfigIdAndStatus(APP_ID, MODEL_ID, "ENABLED"))
                .thenReturn(Optional.of(modelGrantEntity));
    }

    private void stubUserGrant() {
        userGrantEntity = new ClientAppUpstreamUserGrantEntity();
        userGrantEntity.setGrantId("ug_stage6");
        userGrantEntity.setTenantId(TENANT);
        userGrantEntity.setClientAppId(APP_ID);
        userGrantEntity.setUpstreamUserId(USER_ID);
        userGrantEntity.setStatus("ENABLED");
        when(userGrantRepository.findByTenantIdAndClientAppIdAndUpstreamUserId(TENANT, APP_ID, USER_ID))
                .thenReturn(Optional.of(userGrantEntity));
    }

    private void stubSkillAndAllowlist() {
        skillEntity = new SkillEntity();
        skillEntity.setSkillId(SKILL_ID);
        skillEntity.setTenantId(TENANT);
        skillEntity.setName("Stage6 Order Skill");
        skillEntity.setStatus("ENABLED");
        when(skillRepository.findByTenantIdAndSkillId(TENANT, SKILL_ID)).thenReturn(Optional.of(skillEntity));

        allowlistEntity = new SkillFunctionAllowlistEntity();
        allowlistEntity.setAllowlistId("al_stage6");
        allowlistEntity.setTenantId(TENANT);
        allowlistEntity.setSkillId(SKILL_ID);
        allowlistEntity.setFunctionId(FUNCTION_ID);
        allowlistEntity.setStatus("ENABLED");
        when(allowlistRepository.findByTenantIdAndSkillIdAndFunctionId(TENANT, SKILL_ID, FUNCTION_ID))
                .thenReturn(Optional.of(allowlistEntity));

        skillGrantEntity = new ClientAppSkillGrantEntity();
        skillGrantEntity.setGrantId("sg_stage6");
        skillGrantEntity.setTenantId(TENANT);
        skillGrantEntity.setClientAppId(APP_ID);
        skillGrantEntity.setSkillId(SKILL_ID);
        skillGrantEntity.setStatus("ENABLED");
        when(skillGrantRepository.findByTenantIdAndClientAppIdAndSkillId(TENANT, APP_ID, SKILL_ID))
                .thenReturn(Optional.of(skillGrantEntity));
    }

    private void stubFunctionAndGrant() {
        functionEntity = new BusinessFunctionEntity();
        functionEntity.setFunctionId(FUNCTION_ID);
        functionEntity.setTenantId(TENANT);
        functionEntity.setDomain("order");
        functionEntity.setName("Close Apply Submit");
        functionEntity.setRiskLevel("state_change");
        functionEntity.setApprovalRequired(true);
        functionEntity.setIdempotencyRequired(false);
        functionEntity.setStatus("ENABLED");
        functionEntity.setCurrentVersion(VERSION);
        when(functionRepository.findByTenantIdAndFunctionId(TENANT, FUNCTION_ID))
                .thenReturn(Optional.of(functionEntity));

        versionEntity = new BusinessFunctionVersionEntity();
        versionEntity.setFunctionId(FUNCTION_ID);
        versionEntity.setTenantId(TENANT);
        versionEntity.setVersion(VERSION);
        versionEntity.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}");
        versionEntity.setOutputSchemaJson("{\"type\":\"object\"}");
        versionEntity.setLlmVisibleSummary("Submits a close-apply for an order. Requires approval.");
        versionEntity.setSchemaVisibleSummary("orderId: string");
        versionEntity.setAdapterConfigJson("{\"url\":\"http://internal/api\"}");
        versionEntity.setStatus("ENABLED");
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion(TENANT, FUNCTION_ID, VERSION))
                .thenReturn(Optional.of(versionEntity));

        functionGrantEntity = new ClientAppFunctionGrantEntity();
        functionGrantEntity.setGrantId("fg_stage6");
        functionGrantEntity.setTenantId(TENANT);
        functionGrantEntity.setClientAppId(APP_ID);
        functionGrantEntity.setFunctionId(FUNCTION_ID);
        functionGrantEntity.setVersion(VERSION);
        functionGrantEntity.setStatus("ENABLED");
        when(functionGrantRepository.findByTenantIdAndClientAppIdAndFunctionIdAndVersion(TENANT, APP_ID, FUNCTION_ID, VERSION))
                .thenReturn(Optional.of(functionGrantEntity));
        when(functionGrantRepository.findByTenantIdAndClientAppId(TENANT, APP_ID))
                .thenReturn(List.of(functionGrantEntity));
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

    // Stub token resolution from a captured token
    private void stubTokenResolution() {
        when(tokenRepository.findByTokenHash(anyString())).thenAnswer(inv -> {
            if (tokenEntity != null && tokenEntity.getTokenHash().equals(inv.getArgument(0))) {
                return Optional.of(tokenEntity);
            }
            return Optional.empty();
        });
    }

    private void stubSuspensionSave() {
        when(suspensionRepository.save(any(BusinessFunctionSuspensionEntity.class))).thenAnswer(inv -> {
            suspensionEntity = inv.getArgument(0);
            return suspensionEntity;
        });
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ===== THE FULL LIFECYCLE TEST =====

    @Test
    void stage6_fullLifecycle_provisioningToResumeDispatch() throws Exception {
        // Arrange all stubs
        stubActiveClientApp();
        stubModelGrant();
        stubUserGrant();
        stubSkillAndAllowlist();
        stubFunctionAndGrant();
        stubPoolAndTask();
        stubTokenResolution();
        stubSuspensionSave();

        // --- Step 1: createTask - fixes modelConfigId, issues task-scoped token ---
        CreateBusinessAgentTaskForm taskForm = new CreateBusinessAgentTaskForm();
        taskForm.setClientAppId(APP_ID);
        taskForm.setSessionId("session_stage6");
        taskForm.setWorkerPoolId(POOL_ID);
        taskForm.setUpstreamUserId(USER_ID);
        taskForm.setSkillId(SKILL_ID);

        CreatedBusinessAgentTaskDTO created = taskService.createTask(TENANT, ADMIN, taskForm);

        assertNotNull(created.getTaskId(), "task id must be assigned");
        assertEquals(TENANT, created.getTenantId());
        assertEquals(APP_ID, created.getClientAppId());
        assertEquals(USER_ID, created.getUpstreamUserId());
        assertEquals(SKILL_ID, created.getSkillId());
        assertEquals(MODEL_ID, created.getModelConfigId(), "modelConfigId must be fixed at task creation");
        assertNotNull(created.getTaskScopedToken(), "task-scoped token must be returned exactly once");
        plainToken = created.getTaskScopedToken();

        // --- Step 2: resolve token - assert all binding fields present ---
        tokenEntity.setStatus("ACTIVE");
        tokenEntity.setExpiresAt(LocalDateTime.now().plusHours(2));

        BusinessTaskScopedTokenDTO resolvedToken = taskService.resolveTaskScopedToken(plainToken);
        assertEquals(TENANT, resolvedToken.getTenantId());
        assertEquals(APP_ID, resolvedToken.getClientAppId());
        assertEquals(USER_ID, resolvedToken.getUpstreamUserId());
        assertEquals(SKILL_ID, resolvedToken.getSkillId());
        assertEquals(POOL_ID, resolvedToken.getWorkerPoolId());
        assertNotNull(resolvedToken.getTaskId());
        assertNotNull(resolvedToken.getSessionId());

        // --- Step 3: list functions - ClientApp granted functions visible ---
        WorkerGatewayFunctionListDTO listResult = workerGatewayService.listBusinessFunctions(plainToken, null, null);
        assertNotNull(listResult.getFunctions());
        assertEquals(1, listResult.getFunctions().size(), "exactly one function should be visible");
        WorkerGatewayFunctionSummaryDTO summary = listResult.getFunctions().get(0);
        assertEquals(FUNCTION_ID, summary.getFunctionId());
        assertEquals(VERSION, summary.getVersion());
        assertNotNull(summary.getLlmVisibleSummary(), "LLM-visible summary must be present");

        // --- Step 4: get schema - no adapterConfigJson or manifestJson exposed ---
        WorkerGatewayFunctionSchemaDTO schema = workerGatewayService.getBusinessFunctionSchema(plainToken, FUNCTION_ID, VERSION);
        assertEquals(FUNCTION_ID, schema.getFunctionId());
        assertEquals(VERSION, schema.getVersion());
        assertTrue(schema.getApprovalRequired(), "function must be approval-required");
        assertNotNull(schema.getInputSchemaJson());
        assertNotNull(schema.getLlmVisibleSummary());
        // Sensitive fields must NOT appear on the DTO class at all
        assertFalse(hasField(schema, "adapterConfigJson"), "adapterConfigJson must not be on schema DTO");
        assertFalse(hasField(schema, "manifestJson"), "manifestJson must not be on schema DTO");

        // --- Step 5: invoke approval-required function -> SUSPENDED ---
        WorkerGatewayInvokeForm invokeForm = new WorkerGatewayInvokeForm();
        invokeForm.setVersion(VERSION);
        invokeForm.setInputJson(INPUT_JSON);
        invokeForm.setIdempotencyKey("idem_stage6_001");

        WorkerGatewayInvokeResponseDTO invokeResponse = workerGatewayService.invokeBusinessFunction(plainToken, FUNCTION_ID, invokeForm);

        assertEquals(WorkerGatewayInvokeResponseDTO.STATUS_SUSPENDED, invokeResponse.getStatus());
        assertTrue(invokeResponse.getApprovalRequired());
        assertNotNull(invokeResponse.getSuspendId(), "suspendId must be set when SUSPENDED");
        assertTrue(invokeResponse.getSuspendId().startsWith("sus_"), "suspendId must follow expected prefix");

        String suspendId = invokeResponse.getSuspendId();

        // --- Step 6: control-plane resume - strict binding validation + event dispatch ---
        suspensionEntity.setStatus("PENDING");
        suspensionEntity.setExpiresAt(LocalDateTime.now().plusHours(24));
        when(suspensionRepository.findBySuspendId(suspendId)).thenReturn(Optional.of(suspensionEntity));

        WorkerGatewayResumeForm resumeForm = new WorkerGatewayResumeForm();

        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId(suspensionEntity.getClientAppId());
        binding.setUpstreamUserId(suspensionEntity.getUpstreamUserId());
        binding.setTaskId(suspensionEntity.getTaskId());
        binding.setSessionId(suspensionEntity.getSessionId());
        binding.setFunctionId(suspensionEntity.getFunctionId());
        binding.setVersion(suspensionEntity.getVersion());
        binding.setInputHash(sha256(INPUT_JSON));
        resumeForm.setBindingContext(binding);

        WorkerGatewayResumeForm.ApprovalResult approval = new WorkerGatewayResumeForm.ApprovalResult();
        approval.setStatus("approved");
        approval.setComment("Stage6 approval LGTM");
        resumeForm.setApprovalResult(approval);

        suspensionService.resumeSuspension(TENANT, ADMIN, suspendId, resumeForm);

        // Verify final state — save is called twice: once during createSuspension, once during resumeSuspension
        ArgumentCaptor<BusinessFunctionSuspensionEntity> suspCaptor =
                ArgumentCaptor.forClass(BusinessFunctionSuspensionEntity.class);
        verify(suspensionRepository, atLeastOnce()).save(suspCaptor.capture());
        BusinessFunctionSuspensionEntity lastSaved = suspCaptor.getAllValues().get(suspCaptor.getAllValues().size() - 1);
        assertEquals("RESUME_DISPATCHED", lastSaved.getStatus());
        assertEquals(ADMIN, lastSaved.getApprovedBy());

        // Verify resume event was published
        ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        WorkerGatewayResumeEvent event = eventCaptor.getAllValues().stream()
                .filter(WorkerGatewayResumeEvent.class::isInstance)
                .map(WorkerGatewayResumeEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("WorkerGatewayResumeEvent not published"));
        assertEquals(suspensionEntity.getTaskId(), event.getTaskId());
        assertEquals(suspendId, event.getSuspendId());
        assertEquals("approved", event.getApprovalResult());
        assertEquals("Stage6 approval LGTM", event.getComment());
    }

    // ===== NEGATIVE EVIDENCE =====

    @Test
    void stage6_allowlistDisabled_functionStillVisibleWhenClientAppFunctionGrantExists() {
        stubActiveClientApp();
        stubModelGrant();
        stubUserGrant();
        stubSkillAndAllowlist();
        stubFunctionAndGrant();
        stubPoolAndTask();
        stubTokenResolution();

        // Create task to get a valid token
        CreateBusinessAgentTaskForm taskForm = new CreateBusinessAgentTaskForm();
        taskForm.setClientAppId(APP_ID);
        taskForm.setSessionId("session_neg1");
        taskForm.setWorkerPoolId(POOL_ID);
        taskForm.setUpstreamUserId(USER_ID);
        taskForm.setSkillId(SKILL_ID);
        CreatedBusinessAgentTaskDTO created = taskService.createTask(TENANT, ADMIN, taskForm);
        String token = created.getTaskScopedToken();
        tokenEntity.setStatus("ACTIVE");
        tokenEntity.setExpiresAt(LocalDateTime.now().plusHours(2));

        // Disable the allowlist entry
        allowlistEntity.setStatus("DISABLED");

        WorkerGatewayFunctionListDTO listResult = workerGatewayService.listBusinessFunctions(token, null, null);
        assertEquals(1, listResult.getFunctions().size(), "ClientApp function grant, not skill allowlist, controls runtime visibility");
        assertEquals(FUNCTION_ID, listResult.getFunctions().get(0).getFunctionId());
    }

    @Test
    void stage6_negative_resumeBinding_wrongClientApp_rejected() {
        when(suspensionRepository.findBySuspendId("sus_neg_1")).thenReturn(Optional.of(buildPendingSuspension()));

        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();
        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId("wrong_app");       // deliberate mismatch
        binding.setUpstreamUserId(USER_ID);
        binding.setTaskId("task_neg");
        binding.setSessionId("session_neg");
        binding.setFunctionId(FUNCTION_ID);
        binding.setVersion(VERSION);
        binding.setInputHash(sha256(INPUT_JSON));
        form.setBindingContext(binding);
        WorkerGatewayResumeForm.ApprovalResult result = new WorkerGatewayResumeForm.ApprovalResult();
        result.setStatus("approved");
        form.setApprovalResult(result);

        assertThrows(SecurityException.class, () ->
                suspensionService.resumeSuspension(TENANT, ADMIN, "sus_neg_1", form),
                "mismatched clientAppId must be rejected with SecurityException");
    }

    @Test
    void stage6_negative_resumeBinding_wrongInputHash_rejected() {
        when(suspensionRepository.findBySuspendId("sus_neg_2")).thenReturn(Optional.of(buildPendingSuspension()));

        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();
        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId(APP_ID);
        binding.setUpstreamUserId(USER_ID);
        binding.setTaskId("task_neg");
        binding.setSessionId("session_neg");
        binding.setFunctionId(FUNCTION_ID);
        binding.setVersion(VERSION);
        binding.setInputHash("00000000deadbeef_wrong_hash");   // deliberate hash mismatch
        form.setBindingContext(binding);
        WorkerGatewayResumeForm.ApprovalResult result = new WorkerGatewayResumeForm.ApprovalResult();
        result.setStatus("approved");
        form.setApprovalResult(result);

        assertThrows(SecurityException.class, () ->
                suspensionService.resumeSuspension(TENANT, ADMIN, "sus_neg_2", form),
                "wrong input hash must be rejected fail-closed");
    }

    @Test
    void stage6_negative_missingInputHash_rejected() {
        when(suspensionRepository.findBySuspendId("sus_neg_3")).thenReturn(Optional.of(buildPendingSuspension()));

        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();
        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId(APP_ID);
        binding.setUpstreamUserId(USER_ID);
        binding.setTaskId("task_neg");
        binding.setSessionId("session_neg");
        binding.setFunctionId(FUNCTION_ID);
        binding.setVersion(VERSION);
        // inputHash deliberately absent
        form.setBindingContext(binding);
        WorkerGatewayResumeForm.ApprovalResult result = new WorkerGatewayResumeForm.ApprovalResult();
        result.setStatus("approved");
        form.setApprovalResult(result);

        assertThrows(SecurityException.class, () ->
                suspensionService.resumeSuspension(TENANT, ADMIN, "sus_neg_3", form),
                "absent inputHash must be rejected fail-closed");
    }

    @Test
    void stage6_negative_invalidToken_rejectsGatewayAccess() {
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                workerGatewayService.listBusinessFunctions("btt_totally_invalid", null, null),
                "invalid token must be rejected by Worker Gateway");
    }

    // ===== Helpers =====

    private BusinessFunctionSuspensionEntity buildPendingSuspension() {
        BusinessFunctionSuspensionEntity e = new BusinessFunctionSuspensionEntity();
        e.setSuspendId("sus_neg");
        e.setTaskId("task_neg");
        e.setSessionId("session_neg");
        e.setTenantId(TENANT);
        e.setClientAppId(APP_ID);
        e.setUpstreamUserId(USER_ID);
        e.setFunctionId(FUNCTION_ID);
        e.setVersion(VERSION);
        e.setInputJson(INPUT_JSON);
        e.setStatus("PENDING");
        e.setExpiresAt(LocalDateTime.now().plusHours(1));
        return e;
    }

    private static boolean hasField(Object obj, String fieldName) {
        for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
            if (f.getName().equals(fieldName)) return true;
        }
        return false;
    }
}
