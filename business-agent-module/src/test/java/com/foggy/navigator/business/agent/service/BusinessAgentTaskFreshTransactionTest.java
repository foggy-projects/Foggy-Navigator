package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionDTO;
import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentTaskRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskTerminalStateRepository;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLauncher;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(BusinessAgentTaskFreshTransactionTest.TestConfig.class)
class BusinessAgentTaskFreshTransactionTest {

    @jakarta.annotation.Resource
    private BusinessAgentTaskService taskService;
    @jakarta.annotation.Resource
    private PlatformTransactionManager transactionManager;
    @jakarta.annotation.Resource
    private DataSource dataSource;
    @jakarta.annotation.Resource
    private BusinessAgentTaskRepository taskRepository;
    @jakarta.annotation.Resource
    private BusinessTaskScopedTokenRepository tokenRepository;
    @jakarta.annotation.Resource
    private ClientAppService clientAppService;
    @jakarta.annotation.Resource
    private BizWorkerPoolService bizWorkerPoolService;
    @jakarta.annotation.Resource
    private A2AgentResourceResolver resourceResolver;
    @jakarta.annotation.Resource
    private ClientAppUserGrantService userGrantService;
    @jakarta.annotation.Resource
    private SkillRegistryService skillRegistryService;
    @jakarta.annotation.Resource
    private BusinessAgentSessionService businessAgentSessionService;
    @jakarta.annotation.Resource
    private BizWorkerIdentityRepository workerIdentityRepository;
    @jakarta.annotation.Resource
    private ProbeTokenLifecycleService tokenLifecycleService;
    @jakarta.annotation.Resource
    private BusinessTaskScopedCallerAuthorityService callerAuthorityService;
    @jakarta.annotation.Resource
    private BusinessAgentWorkerTaskLauncher workerTaskLauncher;

    private final AtomicReference<Object> providerTransactionResource = new AtomicReference<>();
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        reset(
                taskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                resourceResolver,
                userGrantService,
                skillRegistryService,
                businessAgentSessionService,
                workerIdentityRepository,
                callerAuthorityService,
                workerTaskLauncher);
        tokenLifecycleService.resetProbe();
        providerTransactionResource.set(null);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists business_task_tx_probe");
        jdbc.execute("drop table if exists business_outer_tx_probe");
        jdbc.execute("drop table if exists business_token_tx_probe");
        jdbc.execute("create table business_task_tx_probe (task_id varchar(80) primary key)");
        jdbc.execute("create table business_outer_tx_probe (marker varchar(40) primary key)");
        jdbc.execute("create table business_token_tx_probe ("
                + "token_id varchar(80) primary key, "
                + "tenant_id varchar(80) not null, "
                + "status varchar(20) not null, "
                + "revoked_reason varchar(255), "
                + "worker_task_id varchar(80))");

        ClientAppEntity clientApp = new ClientAppEntity();
        clientApp.setClientAppId("app_01");
        clientApp.setTenantId("tenant_01");
        clientApp.setUpstreamSystemId("foggy-world-sim");
        when(clientAppService.requireActiveClientApp("tenant_01", "app_01"))
                .thenReturn(clientApp);
        when(resourceResolver.resolveRequiredAgent(
                "tenant_01", "app_01", "user_01", "agent_01"))
                .thenReturn(agentResource());
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId("pool_01");
        pool.setWorkerBackend("LANGGRAPH_BIZ");
        when(bizWorkerPoolService.requireAvailablePool(
                "tenant_01", ResourceOwnerType.PLATFORM, "tenant_01", "pool_01"))
                .thenReturn(pool);
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant_01"),
                eq("app_01"),
                any(),
                any(),
                isNull(),
                eq(LlmModelCategory.GENERAL)))
                .thenReturn(modelResource());
        when(resourceResolver.resolveOptionalModelForAgent(
                eq("tenant_01"), eq("app_01"), any(), eq(LlmModelCategory.VISION)))
                .thenReturn(Optional.empty());
        when(resourceResolver.resolveOptionalWorkspaceForAgent(
                eq("tenant_01"), eq("app_01"), eq("user_01"), any(), eq("dir_01")))
                .thenReturn(Optional.of(workspaceResource()));
        when(businessAgentSessionService.resolveReusableContextId(
                eq("tenant_01"),
                eq("app_01"),
                eq("user_01"),
                isNull(),
                eq("session_01")))
                .thenReturn(null);
        BusinessAgentSessionDTO session = new BusinessAgentSessionDTO();
        session.setContextId("bctx_01");
        when(businessAgentSessionService.bindTask(
                any(BusinessAgentTaskEntity.class), any(), any()))
                .thenReturn(session);

        when(workerTaskLauncher.getWorkerBackend()).thenReturn("LANGGRAPH_BIZ");
        when(workerTaskLauncher.resolveWorkerId(any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenAnswer(invocation -> {
                    tokenLifecycleService.recordExternal("worker-resolve");
                    return "worker_01";
                });
        when(workerTaskLauncher.launch(any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenAnswer(invocation -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    providerTransactionResource.set(
                            TransactionSynchronizationManager.getResource(dataSource));
                    tokenLifecycleService.recordExternal("provider-launch");
                    return BusinessAgentWorkerTaskLaunchResult.builder()
                            .workerTaskId("worker_task_01")
                            .workerSessionId("worker_session_01")
                            .contextId("bctx_01")
                            .workerId("worker_01")
                            .providerType("langgraph-biz-worker")
                            .build();
                });
        when(taskRepository.save(any(BusinessAgentTaskEntity.class)))
                .thenAnswer(invocation -> {
                    BusinessAgentTaskEntity task = invocation.getArgument(0);
                    tokenLifecycleService.recordExternal("task-save");
                    if (task.getId() == null) {
                        jdbc.update(
                                "insert into business_task_tx_probe (task_id) values (?)",
                                task.getTaskId());
                        task.setId(1L);
                    }
                    return task;
                });
    }

    @Test
    void inputSnapshotDefensivelyCopiesAndRedactsAllRequestContent() {
        CreateBusinessAgentTaskForm form = form();
        form.setClientContextJson("client-context-secret");
        form.setWorkdir("/secret/workdir");
        ArrayList<String> allowedDirs = new ArrayList<>();
        allowedDirs.add("/secret/dir");
        allowedDirs.add(null);
        ArrayList<String> allowedTools = new ArrayList<>();
        allowedTools.add("dangerous_tool");
        allowedTools.add(null);
        form.setAllowedDirs(allowedDirs);
        form.setAllowedTools(allowedTools);

        BusinessAgentTaskCreateInput input = BusinessAgentTaskCreateInput.snapshot(form);
        allowedDirs.clear();
        allowedTools.clear();

        assertEquals(java.util.Arrays.asList("/secret/dir", null), input.allowedDirs());
        assertEquals(java.util.Arrays.asList("dangerous_tool", null), input.allowedTools());
        assertThrows(UnsupportedOperationException.class, () -> input.allowedDirs().clear());
        assertThrows(UnsupportedOperationException.class, () -> input.allowedTools().clear());
        CreateBusinessAgentTaskForm restored = input.toForm();
        restored.getAllowedDirs().clear();
        restored.getAllowedTools().clear();
        assertEquals(2, input.allowedDirs().size());
        assertEquals(2, input.allowedTools().size());

        String safeText = input.toString();
        assertFalse(safeText.contains("client-context-secret"));
        assertFalse(safeText.contains("/secret/workdir"));
        assertFalse(safeText.contains("/secret/dir"));
        assertFalse(safeText.contains("dangerous_tool"));
    }

    @Test
    void freshProxyCommitsBeforeReturnAndSurvivesOuterRollback() throws Exception {
        assertTrue(AopUtils.isAopProxy(taskService));
        assertTrue(AopUtils.isAopProxy(tokenLifecycleService));
        Method freshMethod = BusinessAgentTaskService.class.getMethod(
                "executeFreshCreatePlan", BusinessAgentTaskPreparedFreshCreate.class);
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(
                freshMethod, Transactional.class);
        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());

        CreateBusinessAgentTaskForm originalForm = form();
        originalForm.setClientContextJson("original-client-context");
        BusinessAgentTaskPreparedFreshCreate prepared = taskService.prepareFreshCreate(
                "tenant_01", "actor_01", originalForm);
        originalForm.setClientContextJson("mutated-after-prepare");
        tokenLifecycleService.resetProbe();
        clearInvocations(
                taskRepository,
                businessAgentSessionService,
                workerTaskLauncher);

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> outer.executeWithoutResult(ignored -> {
                    Object outerResource = TransactionSynchronizationManager.getResource(dataSource);
                    assertNotNull(outerResource);
                    jdbc.update(
                            "insert into business_outer_tx_probe (marker) values ('outer')");

                    CreatedBusinessAgentTaskDTO result = taskService.executeFreshCreatePlan(prepared);

                    assertNotNull(result.getTaskId());
                    assertSame(outerResource, TransactionSynchronizationManager.getResource(dataSource));
                    assertNotNull(providerTransactionResource.get());
                    assertNotSame(outerResource, providerTransactionResource.get());
                    assertNotNull(tokenLifecycleService.issueTransactionResource());
                    assertNotNull(tokenLifecycleService.bindTransactionResource());
                    assertNotSame(
                            outerResource, tokenLifecycleService.issueTransactionResource());
                    assertNotSame(
                            providerTransactionResource.get(),
                            tokenLifecycleService.issueTransactionResource());
                    assertNotSame(
                            providerTransactionResource.get(),
                            tokenLifecycleService.bindTransactionResource());
                    assertEquals(1, independentCount("business_task_tx_probe"));
                    assertEquals(1, independentCount("business_token_tx_probe"));
                    assertEquals(
                            BusinessAgentTaskService.STATUS_ACTIVE,
                            independentTokenValue("status"));
                    assertEquals(0, independentCount("business_outer_tx_probe"));
                    throw new IllegalStateException("rollback outer");
                }));

        assertEquals("rollback outer", error.getMessage());
        assertEquals(1, independentCount("business_task_tx_probe"));
        assertEquals(1, independentCount("business_token_tx_probe"));
        assertEquals(0, independentCount("business_outer_tx_probe"));
        assertEquals(
                BusinessAgentTaskService.STATUS_ACTIVE,
                independentTokenValue("status"));
        assertEquals(
                List.of(
                        "worker-resolve",
                        "task-save",
                        "token-issue",
                        "provider-launch",
                        "task-save",
                        "token-bind"),
                tokenLifecycleService.events());
        verify(businessAgentSessionService).bindTask(
                any(BusinessAgentTaskEntity.class), any(), eq("original-client-context"));
    }

    @Test
    void freshFailureRollsBackTaskAndRevokesIndependentlyIssuedToken() {
        BusinessAgentTaskPreparedFreshCreate prepared = taskService.prepareFreshCreate(
                "tenant_01", "actor_01", form());
        tokenLifecycleService.resetProbe();
        clearInvocations(
                taskRepository,
                businessAgentSessionService,
                workerTaskLauncher);
        when(businessAgentSessionService.bindTask(
                any(BusinessAgentTaskEntity.class), any(), any()))
                .thenThrow(new IllegalStateException("session bind failed"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> taskService.executeFreshCreatePlan(prepared));

        assertEquals("session bind failed", error.getMessage());
        assertEquals(0, independentCount("business_task_tx_probe"));
        assertEquals(1, independentCount("business_token_tx_probe"));
        assertEquals(
                BusinessAgentTaskService.STATUS_REVOKED,
                independentTokenValue("status"));
        assertEquals(
                "task creation transaction rolled back",
                independentTokenValue("revoked_reason"));
        assertEquals(
                tokenLifecycleService.lastIssuedTokenId(),
                tokenLifecycleService.lastRevokedTokenId());
        assertNotNull(tokenLifecycleService.issueTransactionResource());
        assertNotNull(tokenLifecycleService.revokeTransactionResource());
        assertNotSame(
                providerTransactionResource.get(),
                tokenLifecycleService.issueTransactionResource());
        assertNotSame(
                providerTransactionResource.get(),
                tokenLifecycleService.revokeTransactionResource());
        assertNotSame(
                tokenLifecycleService.issueTransactionResource(),
                tokenLifecycleService.revokeTransactionResource());
        assertEquals(
                List.of(
                        "worker-resolve",
                        "task-save",
                        "token-issue",
                        "provider-launch",
                        "token-revoke"),
                tokenLifecycleService.events());
        verify(workerTaskLauncher).launch(any(BusinessAgentWorkerTaskLaunchRequest.class));
    }

    @Test
    void freshWorkerDriftFailsBeforeAnyMutationOrProviderEffect() {
        BusinessAgentTaskPreparedFreshCreate prepared = taskService.prepareFreshCreate(
                "tenant_01", "actor_01", form());
        tokenLifecycleService.resetProbe();
        clearInvocations(
                taskRepository,
                businessAgentSessionService,
                callerAuthorityService,
                workerTaskLauncher);
        when(workerTaskLauncher.resolveWorkerId(any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn("worker_02");

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> taskService.executeFreshCreatePlan(prepared));

        assertEquals(BusinessAgentTaskCreatePlan.PLAN_DRIFT, error.getMessage());
        verifyNoInteractions(taskRepository, callerAuthorityService);
        verify(businessAgentSessionService, never()).bindTask(any(), any(), any());
        verify(workerTaskLauncher, never()).launch(any());
        assertEquals(0, independentCount("business_task_tx_probe"));
        assertEquals(0, independentCount("business_token_tx_probe"));
    }

    @Test
    void freshClientContentDriftFailsBeforeAnyMutationOrProviderEffect() {
        CreateBusinessAgentTaskForm original = form();
        original.setClientContextJson("original-client-context");
        BusinessAgentTaskPreparedFreshCreate prepared = taskService.prepareFreshCreate(
                "tenant_01", "actor_01", original);
        CreateBusinessAgentTaskForm changed = form();
        changed.setClientContextJson("changed-client-context");
        BusinessAgentTaskPreparedFreshCreate drifted = new BusinessAgentTaskPreparedFreshCreate(
                prepared.plan(), BusinessAgentTaskCreateInput.snapshot(changed));
        tokenLifecycleService.resetProbe();
        clearInvocations(
                taskRepository,
                businessAgentSessionService,
                callerAuthorityService,
                workerTaskLauncher);

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> taskService.executeFreshCreatePlan(drifted));

        assertEquals(BusinessAgentTaskCreatePlan.PLAN_DRIFT, error.getMessage());
        verifyNoInteractions(taskRepository, callerAuthorityService);
        verify(businessAgentSessionService, never()).bindTask(any(), any(), any());
        verify(workerTaskLauncher, never()).launch(any());
        assertEquals(0, independentCount("business_task_tx_probe"));
        assertEquals(0, independentCount("business_token_tx_probe"));
    }

    @Test
    void freshModelDriftFailsBeforeAnyMutationOrProviderEffect() {
        BusinessAgentTaskPreparedFreshCreate prepared = taskService.prepareFreshCreate(
                "tenant_01", "actor_01", form());
        clearFreshEffectInvocations();
        when(resourceResolver.resolveRequiredModelForAgent(
                eq("tenant_01"),
                eq("app_01"),
                any(),
                any(),
                isNull(),
                eq(LlmModelCategory.GENERAL)))
                .thenReturn(new A2AgentResourceResolver.ResolvedModelResource(
                        "model_02",
                        "model_02",
                        null,
                        LlmModelCategory.GENERAL,
                        "qwen-max",
                        "MODEL_CONFIG_DEFAULT",
                        "LANGGRAPH_BIZ",
                        "AGENT_DEFAULT_MODEL:REQUESTED_MODEL_GRANT"));

        assertFreshPlanDriftBeforeMutation(prepared);
    }

    @Test
    void freshContextDriftFailsBeforeAnyMutationOrProviderEffect() {
        BusinessAgentTaskPreparedFreshCreate prepared = taskService.prepareFreshCreate(
                "tenant_01", "actor_01", form());
        clearFreshEffectInvocations();
        when(businessAgentSessionService.resolveReusableContextId(
                "tenant_01", "app_01", "user_01", null, "session_01"))
                .thenReturn("bctx_drift");

        assertFreshPlanDriftBeforeMutation(prepared);
    }

    @Test
    void freshWorkspaceDriftFailsBeforeAnyMutationOrProviderEffect() {
        BusinessAgentTaskPreparedFreshCreate prepared = taskService.prepareFreshCreate(
                "tenant_01", "actor_01", form());
        clearFreshEffectInvocations();
        when(resourceResolver.resolveOptionalWorkspaceForAgent(
                eq("tenant_01"), eq("app_01"), eq("user_01"), any(), eq("dir_01")))
                .thenReturn(Optional.of(new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir_01",
                        "worker_01",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.DELEGATED,
                        "/home/sa/workspace/drifted",
                        List.of("/home/sa/workspace"),
                        false,
                        null,
                        null,
                        null,
                        "WORKING_DIRECTORY:USER_PRIVATE")));

        assertFreshPlanDriftBeforeMutation(prepared);
    }

    @Test
    void nullPreparedCommandFailsBeforeResolutionOrMutation() {
        tokenLifecycleService.resetProbe();
        clearInvocations(
                taskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                resourceResolver,
                userGrantService,
                skillRegistryService,
                businessAgentSessionService,
                workerIdentityRepository,
                callerAuthorityService,
                workerTaskLauncher);

        NullPointerException error = assertThrows(
                NullPointerException.class,
                () -> taskService.executeFreshCreatePlan(null));

        assertEquals("prepared must not be null", error.getMessage());
        verifyNoInteractions(
                taskRepository,
                tokenRepository,
                clientAppService,
                bizWorkerPoolService,
                resourceResolver,
                userGrantService,
                skillRegistryService,
                businessAgentSessionService,
                workerIdentityRepository,
                callerAuthorityService,
                workerTaskLauncher);
        assertEquals(0, independentCount("business_token_tx_probe"));
        assertTrue(tokenLifecycleService.events().isEmpty());
    }

    private CreateBusinessAgentTaskForm form() {
        CreateBusinessAgentTaskForm form = new CreateBusinessAgentTaskForm();
        form.setClientAppId("app_01");
        form.setSessionId("session_01");
        form.setUpstreamUserId("user_01");
        form.setAgentId("agent_01");
        form.setDirectoryId("dir_01");
        form.setAllowedTools(List.of("read_file"));
        return form;
    }

    private void clearFreshEffectInvocations() {
        tokenLifecycleService.resetProbe();
        clearInvocations(
                taskRepository,
                businessAgentSessionService,
                callerAuthorityService,
                workerTaskLauncher);
    }

    private void assertFreshPlanDriftBeforeMutation(
            BusinessAgentTaskPreparedFreshCreate prepared) {
        SecurityException error = assertThrows(
                SecurityException.class,
                () -> taskService.executeFreshCreatePlan(prepared));
        assertEquals(BusinessAgentTaskCreatePlan.PLAN_DRIFT, error.getMessage());
        verifyNoInteractions(taskRepository, callerAuthorityService);
        verify(businessAgentSessionService, never()).bindTask(any(), any(), any());
        verify(workerTaskLauncher, never()).launch(any());
        assertEquals(0, independentCount("business_task_tx_probe"));
        assertEquals(0, independentCount("business_token_tx_probe"));
    }

    private A2AgentResourceResolver.ResolvedAgentResource agentResource() {
        return new A2AgentResourceResolver.ResolvedAgentResource(
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
                "model_01",
                null,
                "dir_01",
                "AGENT:CLIENT_APP");
    }

    private A2AgentResourceResolver.ResolvedModelResource modelResource() {
        return new A2AgentResourceResolver.ResolvedModelResource(
                "model_01",
                "model_01",
                null,
                LlmModelCategory.GENERAL,
                "qwen-plus",
                "MODEL_CONFIG_DEFAULT",
                "LANGGRAPH_BIZ",
                "AGENT_DEFAULT_MODEL:REQUESTED_MODEL_GRANT");
    }

    private A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource() {
        return new A2AgentResourceResolver.ResolvedWorkspaceResource(
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
                "WORKING_DIRECTORY:USER_PRIVATE");
    }

    private int independentCount(String table) {
        String sql = "select count(*) from " + table;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        } catch (SQLException error) {
            throw new IllegalStateException("failed to read disposable transaction probe", error);
        }
    }

    private String independentTokenValue(String column) {
        if (!List.of("status", "revoked_reason").contains(column)) {
            throw new IllegalArgumentException("unsupported token probe column");
        }
        String sql = "select " + column + " from business_token_tx_probe";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        } catch (SQLException error) {
            throw new IllegalStateException("failed to read disposable token probe", error);
        }
    }

    static class ProbeTokenLifecycleService extends BusinessTaskScopedTokenLifecycleService {

        private final DataSource dataSource;
        private final JdbcTemplate jdbc;
        private final List<String> events = new ArrayList<>();
        private final AtomicReference<Object> issueTransactionResource = new AtomicReference<>();
        private final AtomicReference<Object> bindTransactionResource = new AtomicReference<>();
        private final AtomicReference<Object> revokeTransactionResource = new AtomicReference<>();
        private final AtomicReference<String> lastIssuedTokenId = new AtomicReference<>();
        private final AtomicReference<String> lastRevokedTokenId = new AtomicReference<>();

        ProbeTokenLifecycleService(
                DataSource dataSource,
                BusinessTaskScopedTokenRepository tokenRepository) {
            super(
                    tokenRepository,
                    mock(BusinessTaskTerminalStateRepository.class),
                    mock(BusinessTaskScopedTokenPolicyService.class),
                    mock(BusinessAgentTaskScopedTokenRuntimeStore.class));
            this.dataSource = dataSource;
            this.jdbc = new JdbcTemplate(dataSource);
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public BusinessTaskScopedTokenEntity issuePreboundToken(
                BusinessTaskScopedTokenEntity token,
                String plainToken,
                String workerId,
                String workerLeaseId) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            issueTransactionResource.set(
                    TransactionSynchronizationManager.getResource(dataSource));
            token.setWorkerId(workerId);
            token.setWorkerLeaseId(workerLeaseId);
            token.setTokenVersion(BusinessTaskScopedTokenPolicyService.CURRENT_TOKEN_VERSION);
            token.setGeneration(BusinessTaskScopedTokenPolicyService.INITIAL_GENERATION);
            token.setAudience(BusinessTaskScopedTokenPolicyService.AUDIENCE_WORKER_GATEWAY);
            token.setIdentityAssurance(
                    BusinessTaskScopedTokenPolicyService.IDENTITY_ASSURANCE_CLIENT_APP_DELEGATED);
            token.setFunctionScopeJson("[]");
            token.setIssuedAt(LocalDateTime.now());
            token.setExpiresAt(LocalDateTime.now().plusMinutes(30));
            jdbc.update(
                    "insert into business_token_tx_probe "
                            + "(token_id, tenant_id, status) values (?, ?, ?)",
                    token.getTokenId(),
                    token.getTenantId(),
                    token.getStatus());
            lastIssuedTokenId.set(token.getTokenId());
            events.add("token-issue");
            return token;
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void bindIssuedTokenToWorkerTask(
                String tenantId,
                String tokenId,
                String plainToken,
                String workerTaskId,
                String workerSessionId,
                String workerId,
                String workerLeaseId) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            bindTransactionResource.set(
                    TransactionSynchronizationManager.getResource(dataSource));
            int updated = jdbc.update(
                    "update business_token_tx_probe set worker_task_id = ? "
                            + "where token_id = ? and tenant_id = ?",
                    workerTaskId,
                    tokenId,
                    tenantId);
            assertEquals(1, updated);
            events.add("token-bind");
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void revokeTaskScopedToken(
                String tenantId, String tokenId, String revokedBy, String reason) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            revokeTransactionResource.set(
                    TransactionSynchronizationManager.getResource(dataSource));
            int updated = jdbc.update(
                    "update business_token_tx_probe "
                            + "set status = ?, revoked_reason = ? "
                            + "where token_id = ? and tenant_id = ?",
                    BusinessAgentTaskService.STATUS_REVOKED,
                    reason,
                    tokenId,
                    tenantId);
            assertEquals(1, updated);
            lastRevokedTokenId.set(tokenId);
            events.add("token-revoke");
        }

        void recordExternal(String event) {
            events.add(event);
        }

        void resetProbe() {
            events.clear();
            issueTransactionResource.set(null);
            bindTransactionResource.set(null);
            revokeTransactionResource.set(null);
            lastIssuedTokenId.set(null);
            lastRevokedTokenId.set(null);
        }

        List<String> events() {
            return List.copyOf(events);
        }

        Object issueTransactionResource() {
            return issueTransactionResource.get();
        }

        Object bindTransactionResource() {
            return bindTransactionResource.get();
        }

        Object revokeTransactionResource() {
            return revokeTransactionResource.get();
        }

        String lastIssuedTokenId() {
            return lastIssuedTokenId.get();
        }

        String lastRevokedTokenId() {
            return lastRevokedTokenId.get();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:business_task_fresh_tx;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                    "sa",
                    "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        BusinessAgentTaskRepository taskRepository() {
            return mock(BusinessAgentTaskRepository.class);
        }

        @Bean
        BusinessTaskScopedTokenRepository tokenRepository() {
            return mock(BusinessTaskScopedTokenRepository.class);
        }

        @Bean
        ClientAppService clientAppService() {
            return mock(ClientAppService.class);
        }

        @Bean
        BizWorkerPoolService bizWorkerPoolService() {
            return mock(BizWorkerPoolService.class);
        }

        @Bean
        A2AgentResourceResolver resourceResolver() {
            return mock(A2AgentResourceResolver.class);
        }

        @Bean
        ClientAppUserGrantService userGrantService() {
            return mock(ClientAppUserGrantService.class);
        }

        @Bean
        SkillRegistryService skillRegistryService() {
            return mock(SkillRegistryService.class);
        }

        @Bean
        BusinessAgentSessionService businessAgentSessionService() {
            return mock(BusinessAgentSessionService.class);
        }

        @Bean
        BizWorkerIdentityRepository workerIdentityRepository() {
            return mock(BizWorkerIdentityRepository.class);
        }

        @Bean
        ProbeTokenLifecycleService tokenLifecycleService(
                DataSource dataSource,
                BusinessTaskScopedTokenRepository tokenRepository) {
            return new ProbeTokenLifecycleService(dataSource, tokenRepository);
        }

        @Bean
        BusinessTaskScopedCallerAuthorityService callerAuthorityService() {
            return mock(BusinessTaskScopedCallerAuthorityService.class);
        }

        @Bean
        BusinessAgentWorkerTaskLauncher workerTaskLauncher() {
            return mock(BusinessAgentWorkerTaskLauncher.class);
        }

        @Bean
        BusinessAgentTaskService taskService(
                BusinessAgentTaskRepository taskRepository,
                BusinessTaskScopedTokenRepository tokenRepository,
                ClientAppService clientAppService,
                BizWorkerPoolService bizWorkerPoolService,
                A2AgentResourceResolver resourceResolver,
                ClientAppUserGrantService userGrantService,
                SkillRegistryService skillRegistryService,
                BusinessAgentSessionService businessAgentSessionService,
                BizWorkerIdentityRepository workerIdentityRepository,
                BusinessTaskScopedTokenLifecycleService tokenLifecycleService,
                BusinessTaskScopedCallerAuthorityService callerAuthorityService,
                BusinessAgentWorkerTaskLauncher workerTaskLauncher) {
            return new BusinessAgentTaskService(
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
                    callerAuthorityService,
                    List.of(workerTaskLauncher));
        }
    }
}
