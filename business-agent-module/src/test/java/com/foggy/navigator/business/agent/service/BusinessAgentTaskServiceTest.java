package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionDTO;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.SkillEntity;
import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;
import com.foggy.navigator.business.agent.repository.BusinessAgentTaskRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLauncher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private ClientAppModelConfigGrantService grantService;
    @Mock
    private ClientAppUserGrantService userGrantService;
    @Mock
    private SkillRegistryService skillRegistryService;
    @Mock
    private BusinessAgentTaskScopedTokenRuntimeStore tokenRuntimeStore;
    @Mock
    private BusinessAgentSessionService businessAgentSessionService;
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
        form.setWorkerPoolId("pool_01");
        form.setSkillId("skill_01");
        form.setUpstreamUserId("user_01");
        lenient().when(tokenRepository.save(any(BusinessTaskScopedTokenEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(businessAgentSessionService.bindTask(any(BusinessAgentTaskEntity.class), any(), any()))
                .thenAnswer(invocation -> {
                    BusinessAgentSessionDTO dto = new BusinessAgentSessionDTO();
                    dto.setContextId("ctx_01");
                    return dto;
                });
    }

    @Test
    void createTask_success() {
        when(grantService.resolveEffectiveModelConfigId("tenant_01", "app_01", null)).thenReturn("model_01");
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
        assertEquals("skill_01", result.getSkillId());
        assertEquals("pool_01", result.getWorkerPoolId());
        assertEquals("model_01", result.getModelConfigId());
        assertEquals(BusinessAgentTaskService.STATUS_CREATED, result.getStatus());
        assertNotNull(result.getTaskScopedToken());
        assertTrue(result.getTaskScopedToken().startsWith("btt_"));

        verify(clientAppService).requireActiveClientApp("tenant_01", "app_01");
        verify(bizWorkerPoolService).requireAvailablePool("tenant_01", "pool_01");

        ArgumentCaptor<BusinessTaskScopedTokenEntity> tokenCaptor = ArgumentCaptor.forClass(BusinessTaskScopedTokenEntity.class);
        verify(tokenRepository).save(tokenCaptor.capture());

        BusinessTaskScopedTokenEntity savedToken = tokenCaptor.getValue();
        assertEquals(SecretTokenSupport.sha256(result.getTaskScopedToken()), savedToken.getTokenHash());
        assertEquals(result.getTaskId(), savedToken.getTaskId());
        assertEquals("model_01", savedToken.getModelConfigId());
        assertEquals(BusinessAgentTaskService.STATUS_ACTIVE, savedToken.getStatus());

        verify(tokenRuntimeStore).registerToken("tenant_01", "session_01", result.getTaskId(), result.getTaskScopedToken(), savedToken.getExpiresAt());
    }

    @Test
    void createTask_withWorkerTaskLauncher_bindsWorkerTaskAndRuntimeTokenAlias() {
        BusinessAgentTaskService serviceWithLauncher = new BusinessAgentTaskService(
                taskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                grantService,
                userGrantService,
                skillRegistryService,
                tokenRuntimeStore,
                businessAgentSessionService,
                List.of(workerTaskLauncher));

        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId("pool_01");
        pool.setWorkerBackend("LANGGRAPH_BIZ");

        when(bizWorkerPoolService.requireAvailablePool("tenant_01", "pool_01")).thenReturn(pool);
        when(grantService.resolveEffectiveModelConfigId("tenant_01", "app_01", null)).thenReturn("model_01");
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(anyString(), anyString(), anyString());
        SkillEntity skill = new SkillEntity();
        skill.setMarkdownBody("skill body");
        when(skillRegistryService.getSkill("tenant_01", "skill_01")).thenReturn(skill);
        when(workerTaskLauncher.getWorkerBackend()).thenReturn("LANGGRAPH_BIZ");
        when(workerTaskLauncher.launch(any(BusinessAgentWorkerTaskLaunchRequest.class))).thenReturn(
                BusinessAgentWorkerTaskLaunchResult.builder()
                        .workerTaskId("lgt_123")
                        .workerSessionId("worker_session_123")
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

        ArgumentCaptor<BusinessAgentWorkerTaskLaunchRequest> requestCaptor =
                ArgumentCaptor.forClass(BusinessAgentWorkerTaskLaunchRequest.class);
        verify(workerTaskLauncher).launch(requestCaptor.capture());
        assertEquals(result.getTaskScopedToken(), requestCaptor.getValue().getTaskScopedToken());
        assertEquals("ctx_01", requestCaptor.getValue().getContextId());

        ArgumentCaptor<BusinessTaskScopedTokenEntity> tokenCaptor = ArgumentCaptor.forClass(BusinessTaskScopedTokenEntity.class);
        verify(tokenRepository, atLeastOnce()).save(tokenCaptor.capture());
        BusinessTaskScopedTokenEntity finalSavedToken = tokenCaptor.getAllValues().get(tokenCaptor.getAllValues().size() - 1);
        assertEquals("lgt_123", finalSavedToken.getWorkerTaskId());
        assertEquals("worker_session_123", finalSavedToken.getWorkerSessionId());

        verify(tokenRuntimeStore).registerToken(eq("tenant_01"), eq("session_01"), eq(result.getTaskId()), eq(result.getTaskScopedToken()), any());
        verify(tokenRuntimeStore).registerToken(eq("tenant_01"), eq("session_01"), eq("lgt_123"), eq(result.getTaskScopedToken()), any());
    }

    @Test
    void bindOpenApiTaskScopedTokenToWorkerTask_persistsMappingAndRegistersWorkerAlias() {
        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setTokenId("tst_open_api");
        token.setTokenHash(SecretTokenSupport.sha256("btt_open_api"));
        token.setTaskId("obt_123");
        token.setSessionId("ctx_123");
        token.setTenantId("tenant_01");
        token.setStatus(BusinessAgentTaskService.STATUS_ACTIVE);
        token.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));

        when(tokenRepository.findByTokenHash(SecretTokenSupport.sha256("btt_open_api"))).thenReturn(Optional.of(token));

        taskService.bindOpenApiTaskScopedTokenToWorkerTask(
                "tenant_01",
                "btt_open_api",
                "lgt_123",
                "worker_session_123");

        assertEquals("lgt_123", token.getWorkerTaskId());
        assertEquals("worker_session_123", token.getWorkerSessionId());
        verify(tokenRepository).save(token);
        verify(tokenRuntimeStore).registerToken("tenant_01", "ctx_123", "lgt_123", "btt_open_api", token.getExpiresAt());
        verify(tokenRuntimeStore).registerToken("tenant_01", "worker_session_123", "lgt_123", "btt_open_api", token.getExpiresAt());
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
        existingTask.setModelConfigId("model_01");

        when(taskRepository.findByTaskId("bt_old123")).thenReturn(Optional.of(existingTask));
        doNothing().when(userGrantService).checkUpstreamUserAccess(anyString(), anyString(), anyString());
        doNothing().when(skillRegistryService).checkClientAppSkillAccess(anyString(), anyString(), anyString());
        when(taskRepository.save(any(BusinessAgentTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreatedBusinessAgentTaskDTO result = taskService.createTask("tenant_01", "actor_01", form);

        assertNotNull(result);
        assertEquals("model_01", result.getModelConfigId());
        verify(grantService, never()).resolveEffectiveModelConfigId(any(), any(), any());
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
        doThrow(new IllegalStateException("pool not available")).when(bizWorkerPoolService).requireAvailablePool("tenant_01", "pool_01");
        assertThrows(IllegalStateException.class, () -> taskService.createTask("tenant_01", "actor_01", form));
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
    void createTask_rejects_blank_upstreamUserId() {
        form.setUpstreamUserId(null);
        assertThrows(IllegalArgumentException.class, () -> taskService.createTask("tenant_01", "actor_01", form));
    }

    @Test
    void createTask_rejects_blank_skillId() {
        form.setSkillId(null);
        assertThrows(IllegalArgumentException.class, () -> taskService.createTask("tenant_01", "actor_01", form));
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
