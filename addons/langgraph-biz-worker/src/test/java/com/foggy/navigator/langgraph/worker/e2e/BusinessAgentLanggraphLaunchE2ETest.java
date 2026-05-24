package com.foggy.navigator.langgraph.worker.e2e;

import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionDTO;
import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;
import com.foggy.navigator.business.agent.repository.BusinessAgentTaskRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentDirectoryBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentModelBindingRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.business.agent.service.BusinessAgentSessionService;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskScopedTokenRuntimeStore;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.A2AgentResourceResolver;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
import com.foggy.navigator.business.agent.service.SkillRegistryService;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphApprovalRepository;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.langgraph.worker.service.LanggraphBusinessAgentWorkerTaskLauncher;
import com.foggy.navigator.langgraph.worker.service.LanggraphStreamRelay;
import com.foggy.navigator.langgraph.worker.service.LanggraphTaskService;
import com.foggy.navigator.langgraph.worker.service.LanggraphWorkerService;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Java-side E2E for upstream Business Agent task dispatch into LangGraph Biz Worker.
 *
 * Covers the boundary that worker-only tests cannot prove:
 * ClientApp-scoped task creation -> model/skill/user grants -> worker pool selection
 * -> LangGraph task creation -> WorkerTaskStartEvent providerConfig.
 */
@ExtendWith(MockitoExtension.class)
class BusinessAgentLanggraphLaunchE2ETest {

    private static final String TENANT = "tenant_e2e";
    private static final String ACTOR = "navigator_user_e2e";
    private static final String CLIENT_APP_ID = "capp_e2e";
    private static final String UPSTREAM_USER_ID = "upstream_user_e2e";
    private static final String SESSION_ID = "session_e2e";
    private static final String CONTEXT_ID = "context_e2e";
    private static final String AGENT_ID = "world-sim-root-agent";
    private static final String SKILL_ID = "world-sim.bug-coordinator.decision.v1";
    private static final String WORKER_POOL_ID = "pool_langgraph_e2e";
    private static final String WORKER_ID = "worker_langgraph_e2e";
    private static final String MODEL_CONFIG_ID = "model_e2e_scripted";
    private static final String DIRECTORY_ID = "dir_e2e";
    private static final String WORKDIR = "D:/workspace/world-sim";

    @Mock private BusinessAgentTaskRepository businessTaskRepository;
    @Mock private BusinessTaskScopedTokenRepository tokenRepository;
    @Mock private ClientAppService clientAppService;
    @Mock private BizWorkerPoolService bizWorkerPoolService;
    @Mock private ClientAppModelConfigGrantService modelGrantService;
    @Mock private LlmModelManager llmModelManager;
    @Mock private WorkingDirectoryRepository workingDirectoryRepository;
    @Mock private BusinessCodingAgentRepository agentRepository;
    @Mock private BizWorkerPoolRepository poolRepository;
    @Mock private BizWorkerIdentityRepository identityRepository;
    @Mock private BusinessAgentDirectoryBindingRepository agentDirectoryBindingRepository;
    @Mock private BusinessAgentModelBindingRepository agentModelBindingRepository;
    private A2AgentResourceResolver resourceResolver;
    @Mock private ClientAppUserGrantService userGrantService;
    @Mock private SkillRegistryService skillRegistryService;
    @Mock private BusinessAgentTaskScopedTokenRuntimeStore tokenRuntimeStore;
    @Mock private BusinessAgentSessionService businessAgentSessionService;

    @Mock private BizWorkerPoolMemberRepository poolMemberRepository;
    @Mock private LanggraphWorkerService langgraphWorkerService;
    @Mock private LanggraphTaskRepository langgraphTaskRepository;
    @Mock private LanggraphApprovalRepository approvalRepository;
    @Mock private SessionManager sessionManager;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SessionTaskRepository sessionTaskRepository;
    @Mock private SessionEntityRepository sessionEntityRepository;
    @Mock private SessionMessageRepository sessionMessageRepository;

    private BusinessAgentTaskService businessAgentTaskService;

    @BeforeEach
    void setUp() {
        LanggraphTaskService langgraphTaskService = new LanggraphTaskService(
                langgraphTaskRepository,
                approvalRepository,
                langgraphWorkerService,
                sessionManager,
                eventPublisher,
                sessionTaskRepository,
                sessionEntityRepository,
                sessionMessageRepository
        );
        LanggraphBusinessAgentWorkerTaskLauncher launcher = new LanggraphBusinessAgentWorkerTaskLauncher(
                poolMemberRepository,
                langgraphWorkerService,
                langgraphTaskService
        );
        resourceResolver = new A2AgentResourceResolver(
                modelGrantService,
                llmModelManager,
                clientAppService,
                workingDirectoryRepository,
                agentRepository,
                poolRepository,
                List.of(),
                agentDirectoryBindingRepository,
                agentModelBindingRepository);
        businessAgentTaskService = new BusinessAgentTaskService(
                businessTaskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                resourceResolver,
                userGrantService,
                skillRegistryService,
                tokenRuntimeStore,
                businessAgentSessionService,
                List.of(launcher)
        );

        when(businessTaskRepository.save(any(BusinessAgentTaskEntity.class))).thenAnswer(invocation -> {
            BusinessAgentTaskEntity task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId(1L);
            }
            return task;
        });
        when(tokenRepository.save(any(BusinessTaskScopedTokenEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTaskRepository.findByTaskId(anyString())).thenReturn(Optional.empty());
        when(sessionTaskRepository.save(any(SessionTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionEntityRepository.findById(anyString())).thenReturn(Optional.empty());
        when(langgraphTaskRepository.save(any(LanggraphTaskEntity.class))).thenAnswer(invocation -> {
            LanggraphTaskEntity task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId(11L);
            }
            if (task.getCreatedAt() == null) {
                task.setCreatedAt(LocalDateTime.now());
            }
            task.setUpdatedAt(LocalDateTime.now());
            return task;
        });
    }

    @Test
    void createBusinessAgentTask_launchesLanggraphWorker_withRuntimeTokenAndScopedContext() {
        stubBusinessAgentAccess();
        stubLanggraphWorkerPool();

        CreateBusinessAgentTaskForm form = makeTaskForm();

        CreatedBusinessAgentTaskDTO created = businessAgentTaskService.createTask(TENANT, ACTOR, form);

        assertEquals(TENANT, created.getTenantId());
        assertEquals(CLIENT_APP_ID, created.getClientAppId());
        assertEquals(UPSTREAM_USER_ID, created.getUpstreamUserId());
        assertEquals(AGENT_ID, created.getAgentId());
        assertEquals(SKILL_ID, created.getSkillId());
        assertEquals(MODEL_CONFIG_ID, created.getModelConfigId());
        assertEquals(DIRECTORY_ID, created.getDirectoryId());
        assertEquals(CONTEXT_ID, created.getContextId());
        assertEquals(WORKER_ID, created.getWorkerId());
        assertEquals(LanggraphTaskService.PROVIDER_TYPE, created.getWorkerProviderType());
        assertNotNull(created.getWorkerTaskId());
        assertTrue(created.getWorkerTaskId().startsWith("lgt_"));
        assertEquals(SESSION_ID, created.getWorkerSessionId());
        assertNotNull(created.getTaskScopedToken());
        assertTrue(created.getTaskScopedToken().startsWith("btt_"));

        ArgumentCaptor<WorkerTaskStartEvent> eventCaptor = ArgumentCaptor.forClass(WorkerTaskStartEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        WorkerTaskStartEvent event = eventCaptor.getValue();
        assertEquals(created.getWorkerTaskId(), event.getTaskId());
        assertEquals(SESSION_ID, event.getSessionId());
        assertEquals(WORKER_ID, event.getWorkerId());
        assertEquals(ACTOR, event.getUserId());
        assertEquals(TENANT, event.getTenantId());
        assertEquals(LanggraphTaskService.PROVIDER_TYPE, event.getProviderType());
        assertEquals(MODEL_CONFIG_ID, event.getProviderConfigString("modelConfigId"));
        assertNull(event.getProviderConfigString("skill_name"));
        assertNull(event.getProviderConfigString("skillName"));
        assertTrue(event.getPrompt().contains(created.getTaskId()));
        assertFalse(event.getPrompt().contains(SKILL_ID));

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) event.getProviderConfig().get("context");
        assertNotNull(context);
        assertEquals(created.getTaskId(), context.get("businessTaskId"));
        assertEquals(CONTEXT_ID, context.get("contextId"));
        assertEquals(CONTEXT_ID, context.get("context_id"));
        assertEquals(SESSION_ID, context.get("session_id"));
        assertEquals(CLIENT_APP_ID, context.get("clientAppId"));
        assertEquals(AGENT_ID, context.get("businessAgentId"));
        assertEquals(SKILL_ID, context.get("businessSkillId"));
        assertEquals(SKILL_ID, context.get("businessSkillName"));
        assertEquals(UPSTREAM_USER_ID, context.get("upstreamUserId"));
        assertEquals(UPSTREAM_USER_ID, context.get("accountId"));
        assertEquals(UPSTREAM_USER_ID, context.get("account_id"));
        assertEquals(DIRECTORY_ID, context.get("directoryId"));
        assertEquals(DIRECTORY_ID, context.get("workingDirectoryId"));
        assertEquals("USER_PRIVATE", context.get("workspaceScope"));
        assertEquals("DELEGATED", context.get("workspaceResolverType"));
        assertEquals(false, context.get("workspaceReadOnly"));
        assertFalse(context.containsKey("skillId"));
        assertFalse(context.containsKey("skill_name"));
        assertFalse(context.containsKey("skillName"));
        assertEquals(true, context.get("auto_inject_app_public_skills"));
        assertEquals(WORKER_POOL_ID, context.get("workerPoolId"));
        assertEquals(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND, context.get("workerBackend"));
        assertEquals("# World Sim\nUse deterministic E2E cursor.", context.get("skill_markdown"));
        assertFalse(context.containsKey("task_scoped_token"));

        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeContext = (Map<String, Object>) event.getProviderConfig().get("runtimeContext");
        assertNotNull(runtimeContext);
        assertEquals(created.getTaskScopedToken(), runtimeContext.get("task_scoped_token"));
        assertEquals(SKILL_ID, runtimeContext.get("skill_name"));
        Map<String, Object> executionPolicy = (Map<String, Object>) runtimeContext.get("execution_policy");
        assertNotNull(executionPolicy);
        assertEquals(DIRECTORY_ID, executionPolicy.get("directory_id"));
        assertEquals("USER_PRIVATE", executionPolicy.get("workspace_scope"));
        assertEquals("DELEGATED", executionPolicy.get("workspace_resolver_type"));
        assertEquals(false, executionPolicy.get("read_only"));
        assertEquals(WORKDIR, executionPolicy.get("workdir"));
        assertEquals(List.of("D:/workspace"), executionPolicy.get("allowed_dirs"));
        assertEquals(List.of("read_file", "invoke_business_function"), executionPolicy.get("allowed_tools"));
        assertNotNull(runtimeContext.get("current_time"));
        assertNotNull(runtimeContext.get("timezone"));
        assertNotNull(runtimeContext.get("business_date"));

        ArgumentCaptor<BusinessTaskScopedTokenEntity> tokenCaptor =
                ArgumentCaptor.forClass(BusinessTaskScopedTokenEntity.class);
        verify(tokenRepository, atLeast(2)).save(tokenCaptor.capture());
        BusinessTaskScopedTokenEntity finalToken =
                tokenCaptor.getAllValues().get(tokenCaptor.getAllValues().size() - 1);
        assertEquals(sha256(created.getTaskScopedToken()), finalToken.getTokenHash());
        assertEquals(created.getTaskId(), finalToken.getTaskId());
        assertEquals(created.getWorkerTaskId(), finalToken.getWorkerTaskId());
        assertEquals(created.getWorkerSessionId(), finalToken.getWorkerSessionId());

        verify(tokenRuntimeStore).registerToken(eq(TENANT), eq(SESSION_ID), eq(created.getTaskId()),
                eq(created.getTaskScopedToken()), any());
        verify(tokenRuntimeStore).registerToken(eq(TENANT), eq(SESSION_ID), eq(created.getWorkerTaskId()),
                eq(created.getTaskScopedToken()), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createBusinessAgentTask_secondTurnReusesContextWithoutRecentConversationByDefault() {
        stubBusinessAgentAccess();
        stubLanggraphWorkerPool();

        CreatedBusinessAgentTaskDTO first = businessAgentTaskService.createTask(TENANT, ACTOR, makeTaskForm());
        CreatedBusinessAgentTaskDTO second = businessAgentTaskService.createTask(TENANT, ACTOR, makeTaskForm());

        assertNotEquals(first.getTaskId(), second.getTaskId());
        assertEquals(CONTEXT_ID, second.getContextId());

        ArgumentCaptor<WorkerTaskStartEvent> eventCaptor = ArgumentCaptor.forClass(WorkerTaskStartEvent.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        WorkerTaskStartEvent secondEvent = eventCaptor.getAllValues().get(1);

        Map<String, Object> context = (Map<String, Object>) secondEvent.getProviderConfig().get("context");
        assertNotNull(context);
        assertEquals(second.getTaskId(), context.get("businessTaskId"));
        assertEquals(CONTEXT_ID, context.get("contextId"));
        assertEquals(CONTEXT_ID, context.get("context_id"));
        assertEquals(SESSION_ID, context.get("session_id"));
        assertFalse(context.containsKey("recentConversation"));
        verify(sessionMessageRepository, never()).findBySessionIdOrderByCreatedAtDesc(eq(SESSION_ID), any());

        verify(sessionManager, times(2)).addMessage(eq(SESSION_ID), argThat(message ->
                message.getRole() != null
                        && "USER".equals(message.getRole().name())
                        && message.getTaskId() != null
                        && message.getContent() != null
                        && message.getContent().contains("Business Agent task")));
    }

    private CreateBusinessAgentTaskForm makeTaskForm() {
        CreateBusinessAgentTaskForm form = new CreateBusinessAgentTaskForm();
        form.setClientAppId(CLIENT_APP_ID);
        form.setSessionId(SESSION_ID);
        form.setContextId(CONTEXT_ID);
        form.setAgentId(AGENT_ID);
        form.setSkillName(SKILL_ID);
        form.setUpstreamUserId(UPSTREAM_USER_ID);
        form.setDirectoryId(DIRECTORY_ID);
        form.setAllowedTools(List.of("read_file", "invoke_business_function"));
        return form;
    }

    private void stubBusinessAgentAccess() {
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId(WORKER_POOL_ID);
        pool.setTenantId(TENANT);
        pool.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        pool.setOwnerId("ups_e2e");
        pool.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        pool.setHealthStatus(BizWorkerPoolService.HEALTHY);
        pool.setWorkerBackend(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        when(bizWorkerPoolService.requireAvailablePool(TENANT, WORKER_POOL_ID)).thenReturn(pool);
        when(poolRepository.findByPoolIdAndTenantId(WORKER_POOL_ID, TENANT)).thenReturn(Optional.of(pool));

        when(modelGrantService.resolveEffectiveModelConfigId(TENANT, CLIENT_APP_ID, MODEL_CONFIG_ID, LlmModelCategory.GENERAL))
                .thenReturn(MODEL_CONFIG_ID);
        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setId(MODEL_CONFIG_ID);
        modelConfig.setTenantId(TENANT);
        modelConfig.setCategory(LlmModelCategory.GENERAL);
        modelConfig.setModelName("qwen-plus");
        modelConfig.setWorkerBackend(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        when(llmModelManager.getModelConfig(MODEL_CONFIG_ID)).thenReturn(Optional.of(modelConfig));
        ClientAppEntity clientApp = new ClientAppEntity();
        clientApp.setTenantId(TENANT);
        clientApp.setClientAppId(CLIENT_APP_ID);
        clientApp.setUpstreamSystemId("ups_e2e");
        when(clientAppService.requireActiveClientApp(TENANT, CLIENT_APP_ID)).thenReturn(clientApp);
        when(clientAppService.requireClientApp(TENANT, CLIENT_APP_ID)).thenReturn(clientApp);
        when(agentRepository.findByAgentIdAndTenantId(AGENT_ID, TENANT))
                .thenReturn(Optional.of(agent()));
        doNothing().when(userGrantService).checkUpstreamUserAccess(TENANT, CLIENT_APP_ID, UPSTREAM_USER_ID);
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(TENANT, CLIENT_APP_ID, SKILL_ID);
        when(workingDirectoryRepository.findByDirectoryId(DIRECTORY_ID)).thenReturn(Optional.of(userPrivateDirectory()));

        when(skillRegistryService.buildMaterializedPublicSkillMarkdown(TENANT, SKILL_ID, CLIENT_APP_ID))
                .thenReturn("# World Sim\nUse deterministic E2E cursor.");

        BusinessAgentSessionDTO session = new BusinessAgentSessionDTO();
        session.setContextId(CONTEXT_ID);
        when(businessAgentSessionService.resolveReusableContextId(
                TENANT, CLIENT_APP_ID, UPSTREAM_USER_ID, CONTEXT_ID, SESSION_ID))
                .thenReturn(CONTEXT_ID);
        when(businessAgentSessionService.bindTask(any(BusinessAgentTaskEntity.class), eq(CONTEXT_ID), isNull()))
                .thenReturn(session);
    }

    private WorkingDirectoryEntity userPrivateDirectory() {
        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(DIRECTORY_ID);
        entity.setTenantId(TENANT);
        entity.setOwnerType(ResourceOwnerType.UPSTREAM_USER);
        entity.setOwnerId(CLIENT_APP_ID + ":" + UPSTREAM_USER_ID);
        entity.setClientAppId(CLIENT_APP_ID);
        entity.setUpstreamUserId(UPSTREAM_USER_ID);
        entity.setWorkspaceScope(WorkspaceScope.USER_PRIVATE);
        entity.setResolverType(WorkingDirectoryResolverType.DELEGATED);
        entity.setEnabled(true);
        entity.setReadOnly(false);
        entity.setPath(WORKDIR);
        entity.setAllowedPathPrefixesJson("[\"D:/workspace\"]");
        return entity;
    }

    private CodingAgentEntity agent() {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId(AGENT_ID);
        entity.setTenantId(TENANT);
        entity.setOwnerType(ResourceOwnerType.CLIENT_APP);
        entity.setOwnerId(CLIENT_APP_ID);
        entity.setClientAppId(CLIENT_APP_ID);
        entity.setUserId(ACTOR);
        entity.setName("World Sim Root Agent");
        entity.setAgentType("LOCAL_LANGGRAPH_WORKER");
        entity.setWorkerId(WORKER_POOL_ID);
        entity.setDefaultModelConfigId(MODEL_CONFIG_ID);
        entity.setDefaultDirectoryId(DIRECTORY_ID);
        entity.setAgentProfile("{\"skillId\":\"" + SKILL_ID + "\"}");
        entity.setEnabled(true);
        return entity;
    }

    private void stubLanggraphWorkerPool() {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setPoolId(WORKER_POOL_ID);
        member.setWorkerId(WORKER_ID);
        member.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc(WORKER_POOL_ID)).thenReturn(List.of(member));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId(WORKER_ID);
        worker.setTenantId(TENANT);
        worker.setUserId(ACTOR);
        when(langgraphWorkerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);

        when(sessionManager.getSession(SESSION_ID)).thenReturn(Session.builder()
                .id(SESSION_ID)
                .userId(ACTOR)
                .tenantId(TENANT)
                .agentId(AGENT_ID)
                .build());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
