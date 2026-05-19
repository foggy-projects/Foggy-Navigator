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
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.service.BusinessAgentSessionService;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskScopedTokenRuntimeStore;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
import com.foggy.navigator.business.agent.service.SkillRegistryService;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphApprovalRepository;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.langgraph.worker.service.LanggraphBusinessAgentWorkerTaskLauncher;
import com.foggy.navigator.langgraph.worker.service.LanggraphStreamRelay;
import com.foggy.navigator.langgraph.worker.service.LanggraphTaskService;
import com.foggy.navigator.langgraph.worker.service.LanggraphWorkerService;
import com.foggy.navigator.session.repository.SessionMessageRepository;
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
    private static final String SKILL_ID = "world-sim.bug-coordinator.decision.v1";
    private static final String WORKER_POOL_ID = "pool_langgraph_e2e";
    private static final String WORKER_ID = "worker_langgraph_e2e";
    private static final String MODEL_CONFIG_ID = "model_e2e_scripted";
    private static final String WORKDIR = "D:/workspace/world-sim";

    @Mock private BusinessAgentTaskRepository businessTaskRepository;
    @Mock private BusinessTaskScopedTokenRepository tokenRepository;
    @Mock private ClientAppService clientAppService;
    @Mock private BizWorkerPoolService bizWorkerPoolService;
    @Mock private ClientAppModelConfigGrantService modelGrantService;
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
        businessAgentTaskService = new BusinessAgentTaskService(
                businessTaskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                modelGrantService,
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
        assertEquals(SKILL_ID, created.getSkillId());
        assertEquals(MODEL_CONFIG_ID, created.getModelConfigId());
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
        assertEquals(SKILL_ID, event.getProviderConfigString("skillName"));
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
        assertEquals(UPSTREAM_USER_ID, context.get("upstreamUserId"));
        assertEquals(UPSTREAM_USER_ID, context.get("accountId"));
        assertEquals(UPSTREAM_USER_ID, context.get("account_id"));
        assertFalse(context.containsKey("skillId"));
        assertFalse(context.containsKey("skill_name"));
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
    void createBusinessAgentTask_secondTurnReusesContextAndForwardsRecentConversation() {
        stubBusinessAgentAccess();
        stubLanggraphWorkerPool();
        when(sessionMessageRepository.findBySessionIdOrderByCreatedAtDesc(eq(SESSION_ID), any()))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        sessionMessage("message-3", "assistant", "Opening frame should be ignored",
                                LocalDateTime.now().minusMinutes(1), "{\"type\":\"STATE_SYNC\"}"),
                        sessionMessage("message-2", "assistant", "Ticket A is available",
                                LocalDateTime.now().minusMinutes(2), "{\"type\":\"TEXT_COMPLETE\"}"),
                        sessionMessage("message-1", "user", "Look up ticket A",
                                LocalDateTime.now().minusMinutes(3), "{\"type\":\"USER\"}")));

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

        List<Map<String, Object>> recentConversation = (List<Map<String, Object>>) context.get("recentConversation");
        assertNotNull(recentConversation);
        assertEquals(2, recentConversation.size());
        assertEquals("user", recentConversation.get(0).get("role"));
        assertEquals("Look up ticket A", recentConversation.get(0).get("content"));
        assertEquals("assistant", recentConversation.get(1).get("role"));
        assertEquals("Ticket A is available", recentConversation.get(1).get("content"));
        assertFalse(recentConversation.toString().contains("Opening frame should be ignored"));

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
        form.setWorkerPoolId(WORKER_POOL_ID);
        form.setSkillId(SKILL_ID);
        form.setSkillName(SKILL_ID);
        form.setUpstreamUserId(UPSTREAM_USER_ID);
        form.setWorkdir(WORKDIR);
        form.setAllowedDirs(List.of("D:/workspace"));
        form.setAllowedTools(List.of("read_file", "invoke_business_function"));
        return form;
    }

    private void stubBusinessAgentAccess() {
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId(WORKER_POOL_ID);
        pool.setTenantId(TENANT);
        pool.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        pool.setHealthStatus(BizWorkerPoolService.HEALTHY);
        pool.setWorkerBackend(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        when(bizWorkerPoolService.requireAvailablePool(TENANT, WORKER_POOL_ID)).thenReturn(pool);

        when(modelGrantService.resolveEffectiveModelConfigId(TENANT, CLIENT_APP_ID, null))
                .thenReturn(MODEL_CONFIG_ID);
        ClientAppEntity clientApp = new ClientAppEntity();
        clientApp.setTenantId(TENANT);
        clientApp.setClientAppId(CLIENT_APP_ID);
        when(clientAppService.requireActiveClientApp(TENANT, CLIENT_APP_ID)).thenReturn(clientApp);
        doNothing().when(userGrantService).checkUpstreamUserAccess(TENANT, CLIENT_APP_ID, UPSTREAM_USER_ID);
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(TENANT, CLIENT_APP_ID, SKILL_ID);

        when(skillRegistryService.buildMaterializedPublicSkillMarkdown(TENANT, SKILL_ID, CLIENT_APP_ID))
                .thenReturn("# World Sim\nUse deterministic E2E cursor.");

        BusinessAgentSessionDTO session = new BusinessAgentSessionDTO();
        session.setContextId(CONTEXT_ID);
        when(businessAgentSessionService.bindTask(any(BusinessAgentTaskEntity.class), eq(CONTEXT_ID), isNull()))
                .thenReturn(session);
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
                .agentId(SKILL_ID)
                .build());
    }

    private static SessionMessageEntity sessionMessage(String id, String role, String content, LocalDateTime createdAt,
                                                       String metadata) {
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId(id);
        entity.setSessionId(SESSION_ID);
        entity.setTaskId("lgt_previous");
        entity.setRole(role);
        entity.setContent(content);
        entity.setMetadata(metadata);
        entity.setCreatedAt(createdAt);
        return entity;
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
