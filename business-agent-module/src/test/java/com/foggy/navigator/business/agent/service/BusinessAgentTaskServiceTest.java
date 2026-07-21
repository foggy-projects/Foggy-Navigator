package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionDTO;
import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.SkillEntity;
import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentTaskRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLauncher;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessAgentTaskServiceTest {

    @Mock
    private BusinessAgentTaskRepository taskRepository;

    @Mock
    private BusinessTaskScopedTokenRepository tokenRepository;

    @Mock
    private ClientAppService clientAppService;

    @Mock
    private BizWorkerPoolService bizWorkerPoolService;

    @Mock
    private A2AgentResourceResolver resourceResolver;
    @Mock
    private ClientAppUserGrantService userGrantService;
    @Mock
    private SkillRegistryService skillRegistryService;
    @Mock
    private BusinessAgentSessionService businessAgentSessionService;
    @Mock
    private BizWorkerIdentityRepository workerIdentityRepository;
    @Mock
    private BusinessTaskScopedTokenLifecycleService tokenLifecycleService;
    @Mock
    private BusinessAgentWorkerTaskLauncher workerTaskLauncher;

    @InjectMocks
    private BusinessAgentTaskService taskService;

    private CreateBusinessAgentTaskForm form;

    @BeforeEach
    void setUp() {
        form = new CreateBusinessAgentTaskForm();
        form.setClientAppId("app_01");
        form.setSessionId("session_01");
        form.setAgentId("agent_01");
        form.setUpstreamUserId("user_01");
        lenient().when(tokenLifecycleService.issueNewToken(
                any(BusinessTaskScopedTokenEntity.class), anyString())).thenAnswer(invocation -> {
            BusinessTaskScopedTokenEntity token = invocation.getArgument(0);
            token.setTokenVersion(BusinessTaskScopedTokenPolicyService.CURRENT_TOKEN_VERSION);
            token.setGeneration(BusinessTaskScopedTokenPolicyService.INITIAL_GENERATION);
            token.setAudience(BusinessTaskScopedTokenPolicyService.AUDIENCE_WORKER_GATEWAY);
            token.setIdentityAssurance(BusinessTaskScopedTokenPolicyService.IDENTITY_ASSURANCE_CLIENT_APP_DELEGATED);
            token.setFunctionScopeJson("[]");
            token.setIssuedAt(LocalDateTime.now());
            token.setExpiresAt(LocalDateTime.now().plusMinutes(30));
            return token;
        });
        lenient().when(tokenLifecycleService.issuePreboundToken(
                any(BusinessTaskScopedTokenEntity.class), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    BusinessTaskScopedTokenEntity token = invocation.getArgument(0);
                    token.setWorkerId(invocation.getArgument(2));
                    token.setWorkerLeaseId(invocation.getArgument(3));
                    token.setTokenVersion(BusinessTaskScopedTokenPolicyService.CURRENT_TOKEN_VERSION);
                    token.setGeneration(BusinessTaskScopedTokenPolicyService.INITIAL_GENERATION);
                    token.setAudience(BusinessTaskScopedTokenPolicyService.AUDIENCE_WORKER_GATEWAY);
                    token.setIdentityAssurance(BusinessTaskScopedTokenPolicyService.IDENTITY_ASSURANCE_CLIENT_APP_DELEGATED);
                    token.setFunctionScopeJson("[]");
                    token.setIssuedAt(LocalDateTime.now());
                    token.setExpiresAt(LocalDateTime.now().plusMinutes(30));
                    return token;
                });
        lenient().when(workerTaskLauncher.resolveWorkerId(any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenAnswer(invocation -> {
                    BusinessAgentWorkerTaskLaunchRequest request = invocation.getArgument(0);
                    return request.getPhysicalWorkerId() != null
                            ? request.getPhysicalWorkerId()
                            : "worker_01";
                });
        lenient().when(businessAgentSessionService.bindTask(any(BusinessAgentTaskEntity.class), any(), any()))
                .thenAnswer(invocation -> {
                    BusinessAgentSessionDTO dto = new BusinessAgentSessionDTO();
                    dto.setContextId("bctx_20260520_ab_ctx_01");
                    return dto;
                });
        lenient().when(businessAgentSessionService.resolveReusableContextId(
                        anyString(), anyString(), anyString(), nullable(String.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        lenient().when(resourceResolver.resolveOptionalModelForAgent(
                anyString(), anyString(), any(), eq(LlmModelCategory.VISION)))
                .thenReturn(Optional.empty());
        lenient().when(resourceResolver.resolveOptionalWorkspaceForAgent(
                anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir_01",
                        "worker_01",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.DELEGATED,
                        "/home/sa/workspace/app",
                        List.of("/home/sa/workspace"),
                        false,
                        null,
                        null,
                        null,
                        "WORKING_DIRECTORY:USER_PRIVATE"
                )));
        lenient().when(resourceResolver.resolveRequiredAgent(
                        "tenant_01", "app_01", "user_01", "agent_01"))
                .thenReturn(new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent_01",
                        com.foggy.navigator.common.enums.ResourceOwnerType.CLIENT_APP,
                        "app_01",
                        "app_01",
                        "skill_01",
                        "pool_01",
                        com.foggy.navigator.common.enums.ResourceOwnerType.PLATFORM,
                        "tenant_01",
                        "WORKER_POOL:PLATFORM",
                        "LANGGRAPH_BIZ",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "dir_01",
                        "AGENT:CLIENT_APP"
                ));
    }

    @Test
    void createTask_success() {
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant_01"), eq("app_01"), any(), any(), nullable(String.class), eq(LlmModelCategory.GENERAL)))
                .thenReturn(modelResource("model_01", null));
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(anyString(), anyString(), anyString());
        when(taskRepository.save(any(BusinessAgentTaskEntity.class))).thenAnswer(invocation -> {
            BusinessAgentTaskEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });

        CreatedBusinessAgentTaskDTO result = taskService.createTask("tenant_01", "actor_01", form);

        assertNotNull(result);
        assertEquals("session_01", result.getSessionId());
        assertEquals("tenant_01", result.getTenantId());
        assertEquals("app_01", result.getClientAppId());
        assertEquals("user_01", result.getUpstreamUserId());
        assertEquals("actor_01", result.getNavigatorEffectiveUserId());
        assertEquals("agent_01", result.getAgentId());
        assertEquals("skill_01", result.getSkillId());
        assertEquals("pool_01", result.getWorkerPoolId());
        assertEquals("model_01", result.getModelConfigId());
        assertEquals(BusinessAgentTaskService.STATUS_CREATED, result.getStatus());
        assertNotNull(result.getTaskScopedToken());
        assertTrue(result.getTaskScopedToken().matches("btt_[A-Za-z0-9_-]{43}"));

        verify(clientAppService).requireActiveClientApp("tenant_01", "app_01");
        verify(bizWorkerPoolService).requireAvailablePool(
                "tenant_01", ResourceOwnerType.PLATFORM, "tenant_01", "pool_01");
        verify(businessAgentSessionService).validateContextResourceCompatibility(
                "tenant_01", "app_01", "user_01", null,
                "agent_01", "skill_01", "dir_01", "model_01");

        ArgumentCaptor<BusinessTaskScopedTokenEntity> tokenCaptor = ArgumentCaptor.forClass(BusinessTaskScopedTokenEntity.class);
        verify(tokenLifecycleService).issueNewToken(tokenCaptor.capture(), eq(result.getTaskScopedToken()));

        BusinessTaskScopedTokenEntity savedToken = tokenCaptor.getValue();
        assertEquals(SecretTokenSupport.sha256(result.getTaskScopedToken()), savedToken.getTokenHash());
        assertEquals(result.getTaskId(), savedToken.getTaskId());
        assertEquals("model_01", savedToken.getModelConfigId());
        assertEquals(BusinessAgentTaskService.STATUS_ACTIVE, savedToken.getStatus());
    }

    @Test
    void createTask_whenOuterTransactionRollsBack_delegatesTokenRevocation() {
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant_01"), eq("app_01"), any(), any(), nullable(String.class),
                eq(LlmModelCategory.GENERAL)))
                .thenReturn(modelResource("model_01", null));
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(anyString(), anyString(), anyString());
        when(taskRepository.save(any(BusinessAgentTaskEntity.class))).thenAnswer(invocation -> {
            BusinessAgentTaskEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });
        TransactionSynchronizationManager.initSynchronization();

        try {
            CreatedBusinessAgentTaskDTO result = taskService.createTask("tenant_01", "actor_01", form);
            ArgumentCaptor<BusinessTaskScopedTokenEntity> tokenCaptor =
                    ArgumentCaptor.forClass(BusinessTaskScopedTokenEntity.class);
            verify(tokenLifecycleService).issueNewToken(
                    tokenCaptor.capture(), eq(result.getTaskScopedToken()));

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(tokenLifecycleService).revokeTaskScopedToken(
                    "tenant_01",
                    tokenCaptor.getValue().getTokenId(),
                    "system",
                    "task creation transaction rolled back");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createTask_withWorkerTaskLauncher_bindsWorkerTaskAndRuntimeTokenAlias() {
        form.setDirectoryId("dir_01");
        form.setAllowedTools(List.of("read_file", "invoke_business_function"));

        BusinessAgentTaskService serviceWithLauncher = new BusinessAgentTaskService(
                taskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                resourceResolver,
                userGrantService,
                skillRegistryService,
                businessAgentSessionService,
                workerIdentityRepository,
                tokenLifecycleService,
                List.of(workerTaskLauncher));

        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId("pool_01");
        pool.setWorkerBackend("LANGGRAPH_BIZ");

        when(bizWorkerPoolService.requireAvailablePool(
                "tenant_01", ResourceOwnerType.PLATFORM, "tenant_01", "pool_01"))
                .thenReturn(pool);
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant_01"), eq("app_01"), any(), any(), nullable(String.class), eq(LlmModelCategory.GENERAL)))
                .thenReturn(modelResource("model_01", null));
        when(resourceResolver.resolveOptionalWorkspaceForAgent(
                eq("tenant_01"), eq("app_01"), eq("user_01"), any(), eq("dir_01")))
                .thenReturn(Optional.of(new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir_01",
                        "worker_01",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.DELEGATED,
                        "/home/sa/workspace/app",
                        List.of("/home/sa/workspace"),
                        false,
                        null,
                        null,
                        null,
                        "WORKING_DIRECTORY:USER_PRIVATE"
                )));
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(anyString(), anyString(), anyString());
        when(workerTaskLauncher.getWorkerBackend()).thenReturn("LANGGRAPH_BIZ");
        when(workerTaskLauncher.launch(any(BusinessAgentWorkerTaskLaunchRequest.class))).thenReturn(
                BusinessAgentWorkerTaskLaunchResult.builder()
                        .workerTaskId("lgt_123")
                        .workerSessionId("worker_session_123")
                        .contextId("bctx_20260520_ab_ctx_01")
                        .workerId("worker_01")
                        .providerType("langgraph-biz-worker")
                        .build());
        when(taskRepository.save(any(BusinessAgentTaskEntity.class))).thenAnswer(invocation -> {
            BusinessAgentTaskEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });

        CreatedBusinessAgentTaskDTO result = serviceWithLauncher.createTask("tenant_01", "actor_01", form);

        assertEquals("lgt_123", result.getWorkerTaskId());
        assertEquals("worker_session_123", result.getWorkerSessionId());
        assertEquals("worker_01", result.getWorkerId());
        assertEquals("langgraph-biz-worker", result.getWorkerProviderType());

        ArgumentCaptor<BusinessAgentTaskEntity> bindTaskCaptor = ArgumentCaptor.forClass(BusinessAgentTaskEntity.class);
        verify(businessAgentSessionService).bindTask(bindTaskCaptor.capture(), eq("bctx_20260520_ab_ctx_01"), isNull());
        assertEquals("lgt_123", bindTaskCaptor.getValue().getWorkerTaskId());
        assertEquals("worker_01", bindTaskCaptor.getValue().getWorkerId());
        assertEquals("langgraph-biz-worker", bindTaskCaptor.getValue().getWorkerProviderType());

        ArgumentCaptor<BusinessAgentWorkerTaskLaunchRequest> requestCaptor =
                ArgumentCaptor.forClass(BusinessAgentWorkerTaskLaunchRequest.class);
        verify(workerTaskLauncher).launch(requestCaptor.capture());
        assertEquals(result.getTaskScopedToken(), requestCaptor.getValue().getTaskScopedToken());
        assertNull(requestCaptor.getValue().getContextId());
        assertEquals("agent_01", requestCaptor.getValue().getAgentId());
        assertEquals("skill_01", requestCaptor.getValue().getSkillName());
        assertEquals("pool_01", requestCaptor.getValue().getWorkerPoolId());
        assertNull(requestCaptor.getValue().getPhysicalWorkerId());
        assertEquals("dir_01", requestCaptor.getValue().getDirectoryId());
        assertEquals("USER_PRIVATE", requestCaptor.getValue().getWorkspaceScope());
        assertEquals("DELEGATED", requestCaptor.getValue().getWorkspaceResolverType());
        assertEquals(false, requestCaptor.getValue().getWorkspaceReadOnly());
        assertEquals("/home/sa/workspace/app", requestCaptor.getValue().getWorkdir());
        assertEquals(List.of("/home/sa/workspace"), requestCaptor.getValue().getAllowedDirs());
        assertEquals(List.of("read_file", "invoke_business_function"), requestCaptor.getValue().getAllowedTools());

        ArgumentCaptor<BusinessTaskScopedTokenEntity> tokenCaptor =
                ArgumentCaptor.forClass(BusinessTaskScopedTokenEntity.class);
        verify(tokenLifecycleService).issuePreboundToken(
                tokenCaptor.capture(), eq(result.getTaskScopedToken()), eq("worker_01"), anyString());
        BusinessTaskScopedTokenEntity issuedToken = tokenCaptor.getValue();
        assertEquals("worker_01", issuedToken.getWorkerId());
        assertTrue(issuedToken.getWorkerLeaseId().matches("bwl_[A-Za-z0-9_-]{43}"));
        assertEquals("worker_01", requestCaptor.getValue().getSelectedWorkerId());
        assertEquals(issuedToken.getWorkerLeaseId(), requestCaptor.getValue().getWorkerLeaseId());
        verify(tokenLifecycleService).bindIssuedTokenToWorkerTask(
                "tenant_01",
                issuedToken.getTokenId(),
                result.getTaskScopedToken(),
                "lgt_123",
                "worker_session_123",
                "worker_01",
                issuedToken.getWorkerLeaseId());
        InOrder dispatchOrder = inOrder(workerTaskLauncher, tokenLifecycleService);
        dispatchOrder.verify(workerTaskLauncher)
                .resolveWorkerId(any(BusinessAgentWorkerTaskLaunchRequest.class));
        dispatchOrder.verify(tokenLifecycleService).issuePreboundToken(
                any(BusinessTaskScopedTokenEntity.class),
                eq(result.getTaskScopedToken()),
                eq("worker_01"),
                eq(issuedToken.getWorkerLeaseId()));
        dispatchOrder.verify(workerTaskLauncher)
                .launch(any(BusinessAgentWorkerTaskLaunchRequest.class));
    }

    @Test
    void createTask_whenWorkerLauncherFails_revokesIssuedToken() {
        BusinessAgentTaskService serviceWithLauncher = new BusinessAgentTaskService(
                taskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                resourceResolver,
                userGrantService,
                skillRegistryService,
                businessAgentSessionService,
                workerIdentityRepository,
                tokenLifecycleService,
                List.of(workerTaskLauncher));

        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId("pool_01");
        pool.setWorkerBackend("LANGGRAPH_BIZ");
        when(bizWorkerPoolService.requireAvailablePool(
                "tenant_01", ResourceOwnerType.PLATFORM, "tenant_01", "pool_01"))
                .thenReturn(pool);
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant_01"), eq("app_01"), any(), any(), nullable(String.class), eq(LlmModelCategory.GENERAL)))
                .thenReturn(modelResource("model_01", null));
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(anyString(), anyString(), anyString());
        when(workerTaskLauncher.getWorkerBackend()).thenReturn("LANGGRAPH_BIZ");
        when(workerTaskLauncher.launch(any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenThrow(new IllegalStateException("worker launch failed"));
        when(taskRepository.save(any(BusinessAgentTaskEntity.class))).thenAnswer(invocation -> {
            BusinessAgentTaskEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> serviceWithLauncher.createTask("tenant_01", "actor_01", form));

        assertEquals("worker launch failed", error.getMessage());
        ArgumentCaptor<BusinessTaskScopedTokenEntity> tokenCaptor =
                ArgumentCaptor.forClass(BusinessTaskScopedTokenEntity.class);
        verify(tokenLifecycleService).issuePreboundToken(
                tokenCaptor.capture(), anyString(), eq("worker_01"), anyString());
        verify(tokenLifecycleService).revokeTaskScopedToken(
                "tenant_01", tokenCaptor.getValue().getTokenId(), "system", "worker dispatch failed");
    }

    @Test
    void createTask_whenLauncherReturnsDifferentWorker_failsClosedAndRevokesToken() {
        BusinessAgentTaskService serviceWithLauncher = new BusinessAgentTaskService(
                taskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                resourceResolver,
                userGrantService,
                skillRegistryService,
                businessAgentSessionService,
                workerIdentityRepository,
                tokenLifecycleService,
                List.of(workerTaskLauncher));
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId("pool_01");
        pool.setWorkerBackend("LANGGRAPH_BIZ");
        when(bizWorkerPoolService.requireAvailablePool(
                "tenant_01", ResourceOwnerType.PLATFORM, "tenant_01", "pool_01"))
                .thenReturn(pool);
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant_01"), eq("app_01"), any(), any(), nullable(String.class),
                eq(LlmModelCategory.GENERAL)))
                .thenReturn(modelResource("model_01", null));
        when(workerTaskLauncher.getWorkerBackend()).thenReturn("LANGGRAPH_BIZ");
        when(workerTaskLauncher.resolveWorkerId(any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn("worker_01");
        when(workerTaskLauncher.launch(any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn(BusinessAgentWorkerTaskLaunchResult.builder()
                        .workerTaskId("worker_task_02")
                        .workerId("worker_02")
                        .providerType("langgraph-biz-worker")
                        .build());
        when(taskRepository.save(any(BusinessAgentTaskEntity.class))).thenAnswer(invocation -> {
            BusinessAgentTaskEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> serviceWithLauncher.createTask("tenant_01", "actor_01", form));

        assertEquals("worker task launcher returned a different worker", error.getMessage());
        ArgumentCaptor<BusinessTaskScopedTokenEntity> tokenCaptor =
                ArgumentCaptor.forClass(BusinessTaskScopedTokenEntity.class);
        verify(tokenLifecycleService).issuePreboundToken(
                tokenCaptor.capture(), anyString(), eq("worker_01"), anyString());
        verify(tokenLifecycleService).revokeTaskScopedToken(
                "tenant_01",
                tokenCaptor.getValue().getTokenId(),
                "system",
                "worker dispatch failed");
        verify(tokenLifecycleService, never()).bindIssuedTokenToWorkerTask(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createTask_withDirectPhysicalWorkerAgent_launchesWithoutWorkerPoolLookup() {
        form.setDirectoryId("dir_01");

        ClientAppEntity clientApp = new ClientAppEntity();
        clientApp.setClientAppId("app_01");
        clientApp.setTenantId("tenant_01");
        clientApp.setUpstreamSystemId("foggy-world-sim");
        when(clientAppService.requireActiveClientApp("tenant_01", "app_01")).thenReturn(clientApp);

        BusinessAgentTaskService serviceWithLauncher = new BusinessAgentTaskService(
                taskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                resourceResolver,
                userGrantService,
                skillRegistryService,
                businessAgentSessionService,
                workerIdentityRepository,
                tokenLifecycleService,
                List.of(workerTaskLauncher));

        when(resourceResolver.resolveRequiredAgent(
                        "tenant_01", "app_01", "user_01", "agent_01"))
                .thenReturn(new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent_01",
                        com.foggy.navigator.common.enums.ResourceOwnerType.CLIENT_APP,
                        "app_01",
                        "app_01",
                        "skill_01",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "worker_01",
                        com.foggy.navigator.common.enums.ResourceOwnerType.UPSTREAM_SYSTEM,
                        "foggy-world-sim",
                        "PHYSICAL_WORKER:UPSTREAM_SYSTEM",
                        "model_01",
                        null,
                        "dir_01",
                        "AGENT:CLIENT_APP"
                ));
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant_01"), eq("app_01"), any(), any(), nullable(String.class), eq(LlmModelCategory.GENERAL)))
                .thenReturn(modelResource("model_01", null));
        when(resourceResolver.resolveOptionalWorkspaceForAgent(
                eq("tenant_01"), eq("app_01"), eq("user_01"), any(), eq("dir_01")))
                .thenReturn(Optional.of(new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir_01",
                        "directory_worker_01",
                        WorkspaceScope.CLIENT_APP_SHARED,
                        WorkingDirectoryResolverType.DELEGATED,
                        "/home/sa/workspace/app",
                        List.of("/home/sa/workspace"),
                        false,
                        null,
                        null,
                        null,
                        "WORKING_DIRECTORY:CLIENT_APP_SHARED"
                )));
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(anyString(), anyString(), anyString());
        when(workerTaskLauncher.getWorkerBackend()).thenReturn("LANGGRAPH_BIZ");
        when(workerTaskLauncher.launch(any(BusinessAgentWorkerTaskLaunchRequest.class))).thenReturn(
                BusinessAgentWorkerTaskLaunchResult.builder()
                        .workerTaskId("lgt_456")
                        .workerSessionId("worker_session_456")
                        .contextId("bctx_20260524_ab_ctx_02")
                        .workerId("worker_01")
                        .providerType("langgraph-biz-worker")
                        .build());
        when(taskRepository.save(any(BusinessAgentTaskEntity.class))).thenAnswer(invocation -> {
            BusinessAgentTaskEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });

        CreatedBusinessAgentTaskDTO result = serviceWithLauncher.createTask("tenant_01", "actor_01", form);

        assertEquals("lgt_456", result.getWorkerTaskId());
        assertEquals("worker_01", result.getWorkerPoolId());
        assertEquals("worker_01", result.getWorkerId());
        verify(bizWorkerPoolService, never()).requireAvailablePool(
                anyString(), any(ResourceOwnerType.class), anyString(), anyString());

        ArgumentCaptor<BusinessAgentWorkerTaskLaunchRequest> requestCaptor =
                ArgumentCaptor.forClass(BusinessAgentWorkerTaskLaunchRequest.class);
        verify(workerTaskLauncher).launch(requestCaptor.capture());
        assertEquals("worker_01", requestCaptor.getValue().getWorkerPoolId());
        assertEquals("worker_01", requestCaptor.getValue().getPhysicalWorkerId());
        assertEquals("foggy-world-sim", requestCaptor.getValue().getUpstreamSystemId());
        assertEquals("LANGGRAPH_BIZ", requestCaptor.getValue().getWorkerBackend());
        assertEquals("dir_01", requestCaptor.getValue().getDirectoryId());
        assertEquals("CLIENT_APP_SHARED", requestCaptor.getValue().getWorkspaceScope());

        ArgumentCaptor<BusinessTaskScopedTokenEntity> tokenCaptor =
                ArgumentCaptor.forClass(BusinessTaskScopedTokenEntity.class);
        verify(tokenLifecycleService).issuePreboundToken(
                tokenCaptor.capture(), anyString(), eq("worker_01"), anyString());
        assertEquals("worker_01", tokenCaptor.getValue().getWorkerPoolId());
    }

    @Test
    void createTask_withStaleBizPhysicalWorker_prefersWorkerHostBizIdentityForLaunch() {
        form.setDirectoryId("dir_01");

        BusinessAgentTaskService serviceWithLauncher = new BusinessAgentTaskService(
                taskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                resourceResolver,
                userGrantService,
                skillRegistryService,
                businessAgentSessionService,
                workerIdentityRepository,
                tokenLifecycleService,
                List.of(workerTaskLauncher));

        ClientAppEntity clientApp = new ClientAppEntity();
        clientApp.setClientAppId("app_01");
        clientApp.setTenantId("tenant_01");
        clientApp.setUpstreamSystemId("school-sim");
        when(clientAppService.requireActiveClientApp("tenant_01", "app_01")).thenReturn(clientApp);
        when(resourceResolver.resolveRequiredAgent(
                        "tenant_01", "app_01", "user_01", "agent_01"))
                .thenReturn(new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent_01",
                        ResourceOwnerType.CLIENT_APP,
                        "app_01",
                        "app_01",
                        "skill_01",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "directory_worker_01",
                        ResourceOwnerType.CLIENT_APP,
                        "app_01",
                        "AGENT_DEFAULT_DIRECTORY:CLIENT_APP_SHARED",
                        "model_01",
                        null,
                        "dir_01",
                        "AGENT:CLIENT_APP"
                ));
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant_01"), eq("app_01"), any(), any(), nullable(String.class), eq(LlmModelCategory.GENERAL)))
                .thenReturn(modelResource("model_01", null));
        when(resourceResolver.resolveOptionalWorkspaceForAgent(
                eq("tenant_01"), eq("app_01"), eq("user_01"), any(), eq("dir_01")))
                .thenReturn(Optional.of(new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir_01",
                        "directory_worker_01",
                        WorkspaceScope.CLIENT_APP_SHARED,
                        WorkingDirectoryResolverType.DELEGATED,
                        "/home/sa/workspace/app",
                        List.of("/home/sa/workspace"),
                        false,
                        null,
                        null,
                        null,
                        "WORKING_DIRECTORY:CLIENT_APP_SHARED"
                )));
        BizWorkerIdentityEntity identity = new BizWorkerIdentityEntity();
        identity.setWorkerId("school-sim-wsl-biz");
        identity.setWorkerBackend("LANGGRAPH_BIZ");
        identity.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        identity.setHealthStatus(BizWorkerPoolService.HEALTHY);
        when(workerIdentityRepository.findByOwnerTypeAndOwnerIdAndWorkerBackendAndStatusAndHealthStatusOrderByUpdatedAtDesc(
                ResourceOwnerType.UPSTREAM_SYSTEM,
                "school-sim",
                "LANGGRAPH_BIZ",
                BizWorkerPoolService.STATUS_ENABLED,
                BizWorkerPoolService.HEALTHY))
                .thenReturn(List.of(identity));
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(anyString(), anyString(), anyString());
        when(workerTaskLauncher.getWorkerBackend()).thenReturn("LANGGRAPH_BIZ");
        when(workerTaskLauncher.launch(any(BusinessAgentWorkerTaskLaunchRequest.class))).thenReturn(
                BusinessAgentWorkerTaskLaunchResult.builder()
                        .workerTaskId("lgt_789")
                        .workerSessionId("worker_session_789")
                        .contextId("bctx_20260524_ab_ctx_03")
                        .workerId("school-sim-wsl-biz")
                        .providerType("langgraph-biz-worker")
                        .build());
        when(taskRepository.save(any(BusinessAgentTaskEntity.class))).thenAnswer(invocation -> {
            BusinessAgentTaskEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });

        CreatedBusinessAgentTaskDTO result = serviceWithLauncher.createTask("tenant_01", "actor_01", form);

        assertEquals("lgt_789", result.getWorkerTaskId());
        assertEquals("directory_worker_01", result.getWorkerPoolId());
        assertEquals("school-sim-wsl-biz", result.getWorkerId());
        ArgumentCaptor<BusinessAgentWorkerTaskLaunchRequest> requestCaptor =
                ArgumentCaptor.forClass(BusinessAgentWorkerTaskLaunchRequest.class);
        verify(workerTaskLauncher).launch(requestCaptor.capture());
        assertEquals("directory_worker_01", requestCaptor.getValue().getWorkerPoolId());
        assertEquals("school-sim-wsl-biz", requestCaptor.getValue().getPhysicalWorkerId());
        assertEquals("dir_01", requestCaptor.getValue().getDirectoryId());
        assertEquals("CLIENT_APP_SHARED", requestCaptor.getValue().getWorkspaceScope());
        verify(bizWorkerPoolService, never()).requireAvailablePool(
                anyString(), any(ResourceOwnerType.class), anyString(), anyString());
    }

    @Test
    void createTask_rejects_legacy_runtime_workspace_selectors() {
        form.setWorkdir("/home/sa/workspace/app");
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> taskService.createTask("tenant_01", "actor_01", form));

        assertTrue(error.getMessage().contains("workdir/allowedDirs"));
        verify(resourceResolver, never()).resolveRequiredModelForAgent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createTask_langgraphBizTaskMissingDirectory_rejected() {
        when(resourceResolver.resolveRequiredAgent(
                        "tenant_01", "app_01", "user_01", "agent_01"))
                .thenReturn(new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent_01",
                        ResourceOwnerType.CLIENT_APP,
                        "app_01",
                        "app_01",
                        "skill_01",
                        "pool_01",
                        ResourceOwnerType.PLATFORM,
                        "tenant_01",
                        "WORKER_POOL:PLATFORM",
                        "LANGGRAPH_BIZ",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "AGENT:CLIENT_APP"
                ));
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant_01"), eq("app_01"), any(), any(), nullable(String.class), eq(LlmModelCategory.GENERAL)))
                .thenReturn(modelResource("model_01", null));
        when(resourceResolver.resolveOptionalWorkspaceForAgent(
                eq("tenant_01"), eq("app_01"), eq("user_01"), any(), isNull()))
                .thenReturn(Optional.empty());
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(anyString(), anyString(), anyString());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> taskService.createTask("tenant_01", "actor_01", form));

        assertTrue(error.getMessage().contains(BusinessAgentTaskService.TASK_DIRECTORY_REQUIRED));
        verify(taskRepository, never()).save(any());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void createTask_contextResourceMismatchRejectedBeforeTaskCreated() {
        form.setContextId("bctx_20260520_ab_ctx_01");
        form.setDirectoryId("dir_02");

        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant_01"), eq("app_01"), any(), any(), nullable(String.class), eq(LlmModelCategory.GENERAL)))
                .thenReturn(modelResource("model_01", null));
        when(resourceResolver.resolveOptionalWorkspaceForAgent(
                eq("tenant_01"), eq("app_01"), eq("user_01"), any(), eq("dir_02")))
                .thenReturn(Optional.of(new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir_02",
                        "worker_01",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.DELEGATED,
                        "/home/sa/workspace/app-2",
                        List.of("/home/sa/workspace"),
                        false,
                        null,
                        null,
                        null,
                        "WORKING_DIRECTORY:USER_PRIVATE"
                )));
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(anyString(), anyString(), anyString());
        doThrow(new IllegalArgumentException(BusinessAgentSessionService.CONTEXT_WORKER_MISMATCH
                + ": directoryId dir_02 conflicts with context-bound directoryId dir_01"))
                .when(businessAgentSessionService)
                .validateContextResourceCompatibility(
                        "tenant_01", "app_01", "user_01", "bctx_20260520_ab_ctx_01",
                        "agent_01", "skill_01", "dir_02", "model_01");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> taskService.createTask("tenant_01", "actor_01", form));

        assertTrue(error.getMessage().contains(BusinessAgentSessionService.CONTEXT_WORKER_MISMATCH));
        verify(taskRepository, never()).save(any());
        verify(tokenLifecycleService, never()).issueNewToken(any(), anyString());
    }

    @Test
    void bindOpenApiTaskScopedTokenToWorkerTask_delegatesToLifecycle() {
        taskService.bindOpenApiTaskScopedTokenToWorkerTask(
                "tenant_01",
                "btt_open_api",
                "lgt_123",
                "worker_session_123");

        verify(tokenLifecycleService).bindOpenApiTokenToWorkerTask(
                "tenant_01", "btt_open_api", "lgt_123", "worker_session_123");
    }

    @Test
    void issueOpenApiTaskScopedToken_initializesPolicyAndReturnsSecureRandomTokenShape() {
        when(resourceResolver.resolveRequiredModelConfigId(
                "tenant_01", "app_01", "model_01", LlmModelCategory.GENERAL))
                .thenReturn("model_01");

        String plainToken = taskService.issueOpenApiTaskScopedToken(
                "tenant_01",
                "actor_01",
                "app_01",
                "user_01",
                "skill_01",
                "session_01",
                "model_01");

        assertTrue(plainToken.matches("btt_[A-Za-z0-9_-]{43}"));
        ArgumentCaptor<BusinessTaskScopedTokenEntity> tokenCaptor =
                ArgumentCaptor.forClass(BusinessTaskScopedTokenEntity.class);
        verify(tokenLifecycleService).issueNewToken(tokenCaptor.capture(), eq(plainToken));
        BusinessTaskScopedTokenEntity savedToken = tokenCaptor.getValue();
        assertEquals(SecretTokenSupport.sha256(plainToken), savedToken.getTokenHash());
        assertTrue(savedToken.getTaskId().matches("obt_[a-f0-9]{32}"));
        assertEquals(BusinessAgentTaskService.STATUS_ACTIVE, savedToken.getStatus());
    }

    @Test
    void prepareOpenApiTaskScopedToken_resolvesAndPersistsExactWorkerBeforeDispatch() {
        BusinessAgentTaskService serviceWithLauncher = new BusinessAgentTaskService(
                taskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                resourceResolver,
                userGrantService,
                skillRegistryService,
                businessAgentSessionService,
                workerIdentityRepository,
                tokenLifecycleService,
                List.of(workerTaskLauncher));
        ClientAppEntity activeClientApp = new ClientAppEntity();
        activeClientApp.setUpstreamSystemId("  trusted-upstream  ");
        when(clientAppService.requireActiveClientApp("tenant_01", "app_01"))
                .thenReturn(activeClientApp);
        when(resourceResolver.resolveRequiredModelConfigId(
                "tenant_01", "app_01", "model_01", LlmModelCategory.GENERAL))
                .thenReturn("model_01");
        when(workerTaskLauncher.getWorkerBackend()).thenReturn("LANGGRAPH_BIZ");
        when(workerTaskLauncher.resolveWorkerId(any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn("worker_01");
        BusinessAgentWorkerTaskLaunchRequest selectionRequest =
                BusinessAgentWorkerTaskLaunchRequest.builder()
                        .workerPoolId("pool_01")
                        .workerPoolOwnerType(ResourceOwnerType.PLATFORM)
                        .workerPoolOwnerId("tenant_01")
                        .workerBackend("LANGGRAPH_BIZ")
                        .upstreamSystemId("caller-controlled-upstream")
                        .build();

        BusinessAgentTaskService.PreparedOpenApiTaskScopedToken prepared =
                serviceWithLauncher.prepareOpenApiTaskScopedToken(
                        "tenant_01",
                        "actor_01",
                        "app_01",
                        "user_01",
                        "skill_01",
                        "session_01",
                        "model_01",
                        selectionRequest);

        assertTrue(prepared.plainToken().matches("btt_[A-Za-z0-9_-]{43}"));
        assertTrue(prepared.tokenId().matches("tst_[a-f0-9]{32}"));
        assertEquals("worker_01", prepared.workerId());
        assertTrue(prepared.workerLeaseId().matches("bwl_[A-Za-z0-9_-]{43}"));
        assertEquals("pool_01", prepared.workerPoolId());
        assertEquals("LANGGRAPH_BIZ", prepared.workerBackend());
        assertEquals("worker_01", selectionRequest.getSelectedWorkerId());
        assertEquals(prepared.workerLeaseId(), selectionRequest.getWorkerLeaseId());

        ArgumentCaptor<BusinessAgentWorkerTaskLaunchRequest> requestCaptor =
                ArgumentCaptor.forClass(BusinessAgentWorkerTaskLaunchRequest.class);
        ArgumentCaptor<BusinessTaskScopedTokenEntity> tokenCaptor =
                ArgumentCaptor.forClass(BusinessTaskScopedTokenEntity.class);
        InOrder preparationOrder = inOrder(workerTaskLauncher, tokenLifecycleService);
        preparationOrder.verify(workerTaskLauncher).resolveWorkerId(requestCaptor.capture());
        preparationOrder.verify(tokenLifecycleService).issuePreboundToken(
                tokenCaptor.capture(),
                eq(prepared.plainToken()),
                eq("worker_01"),
                eq(prepared.workerLeaseId()));
        assertEquals("trusted-upstream", requestCaptor.getValue().getUpstreamSystemId());
        assertEquals("trusted-upstream", selectionRequest.getUpstreamSystemId());
        BusinessTaskScopedTokenEntity persisted = tokenCaptor.getValue();
        assertEquals("worker_01", persisted.getWorkerId());
        assertEquals(prepared.workerLeaseId(), persisted.getWorkerLeaseId());
        assertEquals("pool_01", persisted.getWorkerPoolId());
        verify(workerTaskLauncher, never()).launch(any());
    }

    @Test
    void prepareOpenApiTaskScopedToken_doesNotPersistOrDispatchWhenWorkerResolutionFails() {
        BusinessAgentTaskService serviceWithLauncher = new BusinessAgentTaskService(
                taskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                resourceResolver,
                userGrantService,
                skillRegistryService,
                businessAgentSessionService,
                workerIdentityRepository,
                tokenLifecycleService,
                List.of(workerTaskLauncher));
        ClientAppEntity activeClientApp = new ClientAppEntity();
        activeClientApp.setUpstreamSystemId("trusted-upstream");
        when(clientAppService.requireActiveClientApp("tenant_01", "app_01"))
                .thenReturn(activeClientApp);
        when(resourceResolver.resolveRequiredModelConfigId(
                "tenant_01", "app_01", "model_01", LlmModelCategory.GENERAL))
                .thenReturn("model_01");
        when(workerTaskLauncher.getWorkerBackend()).thenReturn("LANGGRAPH_BIZ");
        when(workerTaskLauncher.resolveWorkerId(any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenThrow(new SecurityException("worker owner scope rejected"));
        BusinessAgentWorkerTaskLaunchRequest selectionRequest =
                BusinessAgentWorkerTaskLaunchRequest.builder()
                        .workerPoolId("pool_01")
                        .workerBackend("LANGGRAPH_BIZ")
                        .upstreamSystemId("caller-controlled-upstream")
                        .build();

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> serviceWithLauncher.prepareOpenApiTaskScopedToken(
                        "tenant_01",
                        "actor_01",
                        "app_01",
                        "user_01",
                        "skill_01",
                        "session_01",
                        "model_01",
                        selectionRequest));

        assertEquals("worker owner scope rejected", error.getMessage());
        ArgumentCaptor<BusinessAgentWorkerTaskLaunchRequest> requestCaptor =
                ArgumentCaptor.forClass(BusinessAgentWorkerTaskLaunchRequest.class);
        verify(workerTaskLauncher).resolveWorkerId(requestCaptor.capture());
        assertEquals("trusted-upstream", requestCaptor.getValue().getUpstreamSystemId());
        verifyNoInteractions(tokenRepository);
        verify(tokenLifecycleService, never()).issuePreboundToken(
                any(BusinessTaskScopedTokenEntity.class), anyString(), anyString(), anyString());
        verify(workerTaskLauncher, never()).launch(any());
    }

    @Test
    void prepareOpenApiTaskScopedToken_returnsNoCapabilityForNonBizProvider() {
        BusinessAgentTaskService serviceWithLauncher = new BusinessAgentTaskService(
                taskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                resourceResolver,
                userGrantService,
                skillRegistryService,
                businessAgentSessionService,
                workerIdentityRepository,
                tokenLifecycleService,
                List.of(workerTaskLauncher));
        when(workerTaskLauncher.getWorkerBackend()).thenReturn("LANGGRAPH_BIZ");
        BusinessAgentWorkerTaskLaunchRequest selectionRequest =
                BusinessAgentWorkerTaskLaunchRequest.builder()
                        .workerBackend("CLAUDE_CODE")
                        .build();

        BusinessAgentTaskService.PreparedOpenApiTaskScopedToken prepared =
                serviceWithLauncher.prepareOpenApiTaskScopedToken(
                        "tenant_01",
                        "actor_01",
                        "app_01",
                        "user_01",
                        "skill_01",
                        "session_01",
                        "model_01",
                        selectionRequest);

        assertNull(prepared);
        verify(clientAppService, never()).requireActiveClientApp(anyString(), anyString());
        verify(tokenLifecycleService, never()).issuePreboundToken(any(), anyString(), anyString(), anyString());
    }

    @Test
    void createTask_resumeFromTaskId_success() {
        form.setResumeFromTaskId("bt_old123");
        form.setRequestedModelConfigId("model_01");

        BusinessAgentTaskEntity existingTask = new BusinessAgentTaskEntity();
        existingTask.setTaskId("bt_old123");
        existingTask.setTenantId("tenant_01");
        existingTask.setClientAppId("app_01");
        existingTask.setSessionId("session_01");
        existingTask.setAgentId("agent_01");
        existingTask.setModelConfigId("model_01");

        when(taskRepository.findByTaskId("bt_old123")).thenReturn(Optional.of(existingTask));
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant_01"), eq("app_01"), any(), eq("model_01"), nullable(String.class), eq(LlmModelCategory.GENERAL)))
                .thenReturn(modelResource("model_01", null));
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(anyString(), anyString(), anyString());
        when(taskRepository.save(any(BusinessAgentTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreatedBusinessAgentTaskDTO result = taskService.createTask("tenant_01", "actor_01", form);

        assertNotNull(result);
        assertEquals("model_01", result.getModelConfigId());
        verify(resourceResolver).resolveRequiredModelForAgent(
                eq("tenant_01"), eq("app_01"), any(), eq("model_01"), nullable(String.class), eq(LlmModelCategory.GENERAL));
    }

    private A2AgentResourceResolver.ResolvedModelResource modelResource(
            String modelConfigId,
            String requestedModelVariant) {
        return new A2AgentResourceResolver.ResolvedModelResource(
                modelConfigId,
                modelConfigId,
                requestedModelVariant,
                LlmModelCategory.GENERAL,
                requestedModelVariant != null ? requestedModelVariant : "qwen-plus",
                requestedModelVariant != null ? "REQUESTED_MODEL_VARIANT" : "MODEL_CONFIG_DEFAULT",
                "LANGGRAPH_BIZ",
                "AGENT_DEFAULT_MODEL:REQUESTED_MODEL_GRANT");
    }

    @Test
    void createTask_resumeFromTaskId_modelDrift_rejected() {
        form.setResumeFromTaskId("bt_old123");
        form.setRequestedModelConfigId("model_02");

        BusinessAgentTaskEntity existingTask = new BusinessAgentTaskEntity();
        existingTask.setTaskId("bt_old123");
        existingTask.setTenantId("tenant_01");
        existingTask.setClientAppId("app_01");
        existingTask.setSessionId("session_01");
        existingTask.setAgentId("agent_01");
        existingTask.setModelConfigId("model_01"); // different model

        when(taskRepository.findByTaskId("bt_old123")).thenReturn(Optional.of(existingTask));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                taskService.createTask("tenant_01", "actor_01", form));
        assertTrue(ex.getMessage().contains("cannot change modelConfigId"));
    }

    @Test
    void createTask_nullForm_rejected() {
        assertThrows(IllegalArgumentException.class, () -> taskService.createTask("tenant_01", "actor", null));
    }

    @Test
    void createTask_nullActorUserId_rejected() {
        assertThrows(IllegalArgumentException.class, () -> taskService.createTask("tenant_01", null, form));
    }

    @Test
    void createTask_invalidClientApp_rejected() {
        doThrow(new IllegalStateException("client app is not active")).when(clientAppService).requireActiveClientApp("tenant_01", "app_01");
        assertThrows(IllegalStateException.class, () -> taskService.createTask("tenant_01", "actor_01", form));
    }

    @Test
    void createTask_invalidWorkerPool_rejected() {
        doThrow(new IllegalStateException("pool not available"))
                .when(bizWorkerPoolService)
                .requireAvailablePool(
                        "tenant_01", ResourceOwnerType.PLATFORM, "tenant_01", "pool_01");
        assertThrows(IllegalStateException.class, () -> taskService.createTask("tenant_01", "actor_01", form));
    }

    @Test
    void createTask_workerPoolOwnerMismatchRejectedBeforeDispatch() {
        when(resourceResolver.resolveRequiredAgent(
                "tenant_01", "app_01", "user_01", "agent_01"))
                .thenReturn(new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent_01",
                        ResourceOwnerType.CLIENT_APP,
                        "app_01",
                        "app_01",
                        "skill_01",
                        "pool_01",
                        ResourceOwnerType.UPSTREAM_SYSTEM,
                        "ups-a",
                        "WORKER_POOL:UPSTREAM_SYSTEM",
                        "LANGGRAPH_BIZ",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "dir_01",
                        "AGENT:CLIENT_APP"));
        doThrow(new SecurityException("worker pool owner mismatch"))
                .when(bizWorkerPoolService)
                .requireAvailablePool(
                        "tenant_01", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-a", "pool_01");

        assertThrows(SecurityException.class,
                () -> taskService.createTask("tenant_01", "actor_01", form));

        verify(taskRepository, never()).save(any());
        verify(tokenLifecycleService, never()).issueNewToken(any(), anyString());
        verify(workerTaskLauncher, never()).launch(any());
    }

    @Test
    void resolveTaskScopedToken_success() {
        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setTokenId("tst_01");
        token.setStatus(BusinessAgentTaskService.STATUS_ACTIVE);
        token.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));

        when(tokenRepository.findByTokenHash(SecretTokenSupport.sha256("plain_token"))).thenReturn(Optional.of(token));

        com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO result = taskService.resolveTaskScopedToken("plain_token");
        assertNotNull(result);
        assertEquals("tst_01", result.getTokenId());
        verify(tokenLifecycleService).requireNotTerminal(token);
    }

    @Test
    void resolveTaskScopedToken_terminalTombstoneFailurePropagatesFromGatewayResolvePath() {
        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setTokenId("tst_terminal");
        token.setTenantId("tenant_01");
        token.setTaskId("bt_terminal");
        token.setWorkerTaskId("worker_task_terminal");
        token.setStatus(BusinessAgentTaskService.STATUS_ACTIVE);
        token.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
        when(tokenRepository.findByTokenHash(SecretTokenSupport.sha256("plain_token")))
                .thenReturn(Optional.of(token));
        doThrow(new IllegalStateException("task token belongs to a terminal task"))
                .when(tokenLifecycleService).requireNotTerminal(token);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> taskService.resolveTaskScopedToken("plain_token"));

        assertEquals("task token belongs to a terminal task", error.getMessage());
        verify(tokenLifecycleService).requireNotTerminal(token);
    }

    @Test
    void resolveTaskScopedToken_expired() {
        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setStatus(BusinessAgentTaskService.STATUS_ACTIVE);
        token.setExpiresAt(java.time.LocalDateTime.now().minusHours(1));

        when(tokenRepository.findByTokenHash(SecretTokenSupport.sha256("plain_token"))).thenReturn(Optional.of(token));

        assertThrows(IllegalStateException.class, () -> taskService.resolveTaskScopedToken("plain_token"));
    }

    @Test
    void revokeTaskScopedToken_delegatesToLifecycle() {
        taskService.revokeTaskScopedToken("tenant_01", "tst_01", " operator_01 ", " manual revoke ");
        verify(tokenLifecycleService).revokeTaskScopedToken(
                "tenant_01", "tst_01", " operator_01 ", " manual revoke ");
    }

    @Test
    void revokeOpenApiTaskScopedToken_delegatesPlainTokenToLifecycle() {
        taskService.revokeOpenApiTaskScopedToken(
                "tenant_01", "btt_plain", "system", "open api submit failed");

        verify(tokenLifecycleService).revokeTaskScopedTokenByPlainToken(
                "tenant_01", "btt_plain", "system", "open api submit failed");
    }

    @Test
    void revokeTaskScopedTokensForTask_delegatesToLifecycle() {
        when(tokenLifecycleService.revokeTaskScopedTokensForTask(
                "tenant_01", "bt_123", "system", "task terminal")).thenReturn(1);

        int revokedCount = taskService.revokeTaskScopedTokensForTask(
                "tenant_01", "bt_123", "system", "task terminal");

        assertEquals(1, revokedCount);
        verify(tokenLifecycleService).revokeTaskScopedTokensForTask(
                "tenant_01", "bt_123", "system", "task terminal");
    }

    @Test
    void createTask_rejects_blank_upstreamUserId() {
        form.setUpstreamUserId(null);
        assertThrows(IllegalArgumentException.class, () -> taskService.createTask("tenant_01", "actor_01", form));
    }

    @Test
    void createTask_rejects_blank_agentId() {
        form.setAgentId(null);
        assertThrows(IllegalArgumentException.class, () -> taskService.createTask("tenant_01", "actor_01", form));
    }

    @Test
    void createTask_rejectsConflictingAgentBoundSkillAlias() {
        form.setSkillName("other_skill");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> taskService.createTask("tenant_01", "actor_01", form));
        assertTrue(error.getMessage().contains("agent-bound skillId"));
    }

    @Test
    void createTask_rejects_unauthorized_upstream_user() {
        doThrow(new IllegalStateException("Unauthorized user"))
                .when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());

        assertThrows(IllegalStateException.class, () -> taskService.createTask("tenant_01", "actor_01", form));
    }

    @Test
    void createTask_rejects_unauthorized_skill() {
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());
        doThrow(new IllegalStateException("Unauthorized skill"))
                .when(skillRegistryService).checkClientAppSkillAccess(anyString(), anyString(), anyString());

        assertThrows(IllegalStateException.class, () -> taskService.createTask("tenant_01", "actor_01", form));
    }

}
