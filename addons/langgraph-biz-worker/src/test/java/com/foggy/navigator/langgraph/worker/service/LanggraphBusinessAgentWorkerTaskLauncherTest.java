package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphTaskDTO;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LanggraphBusinessAgentWorkerTaskLauncherTest {

    @Mock
    private BizWorkerPoolRepository poolRepository;

    @Mock
    private BizWorkerPoolMemberRepository poolMemberRepository;

    @Mock
    private LanggraphWorkerService workerService;

    @Mock
    private LanggraphTaskService taskService;

    @InjectMocks
    private LanggraphBusinessAgentWorkerTaskLauncher launcher;

    @BeforeEach
    void setUp() {
        lenient().when(poolRepository.findByPoolIdAndTenantId("pool_01", "tenant_01"))
                .thenReturn(Optional.of(platformPool()));
    }

    @Test
    void getWorkerBackend_returnsLanggraphBiz() {
        assertEquals(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND, launcher.getWorkerBackend());
    }

    @Test
    void launch_createsLanggraphTaskFromEnabledPoolMember() {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setWorkerId("worker_01");
        member.setStatus("ENABLED");
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01")).thenReturn(List.of(member));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId("worker_01");
        worker.setTenantId("tenant_01");
        when(workerService.getBusinessAgentWorkerEntity(
                "worker_01", ResourceOwnerType.PLATFORM, "tenant_01")).thenReturn(worker);

        LanggraphTaskDTO taskDTO = LanggraphTaskDTO.builder()
                .taskId("lgt_01")
                .workerId("worker_01")
                .sessionId("session_01")
                .build();
        when(taskService.createTask(eq("actor_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class))).thenReturn(taskDTO);

        BusinessAgentWorkerTaskLaunchResult result = launcher.launch(request());

        assertEquals("lgt_01", result.getWorkerTaskId());
        assertEquals("session_01", result.getWorkerSessionId());
        assertEquals("ctx_01", result.getContextId());
        assertEquals("worker_01", result.getWorkerId());
        assertEquals(LanggraphTaskService.PROVIDER_TYPE, result.getProviderType());

        ArgumentCaptor<CreateLanggraphTaskForm> formCaptor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("actor_01"), eq("tenant_01"), formCaptor.capture());
        CreateLanggraphTaskForm form = formCaptor.getValue();
        assertEquals("agent_01", form.getAgentId());
        assertNull(form.getSkillName());
        assertEquals("worker_01", form.getWorkerId());
        assertEquals("session_01", form.getSessionId());
        assertEquals("ctx_01", form.getContextId());
        assertEquals("model_01", form.getModelConfigId());
        assertEquals("dir_01", form.getDirectoryId());
        assertEquals("/home/sa/workspace/app", form.getCwd());
        assertFalse(form.getPrompt().contains("skill_01"));
        assertEquals("bt_01", form.getContext().get("businessTaskId"));
        assertEquals("ctx_01", form.getContext().get("contextId"));
        assertEquals("ctx_01", form.getContext().get("context_id"));
        assertEquals("session_01", form.getContext().get("session_id"));
        assertEquals("app_01", form.getContext().get("clientAppId"));
        assertEquals("agent_01", form.getContext().get("businessAgentId"));
        assertEquals("skill_01", form.getContext().get("businessSkillId"));
        assertEquals("skill_01", form.getContext().get("businessSkillName"));
        assertEquals("user_01", form.getContext().get("upstreamUserId"));
        assertEquals("user_01", form.getContext().get("accountId"));
        assertEquals("user_01", form.getContext().get("account_id"));
        assertEquals("dir_01", form.getContext().get("directoryId"));
        assertEquals("dir_01", form.getContext().get("workingDirectoryId"));
        assertEquals("USER_PRIVATE", form.getContext().get("workspaceScope"));
        assertEquals("DELEGATED", form.getContext().get("workspaceResolverType"));
        assertEquals(false, form.getContext().get("workspaceReadOnly"));
        assertEquals(true, form.getContext().get("auto_inject_app_public_skills"));
        assertFalse(form.getContext().containsKey("task_scoped_token"));
        assertFalse(form.getContext().containsKey("skillId"));
        assertFalse(form.getContext().containsKey("skill_name"));
        assertFalse(form.getContext().containsKey("skillName"));
        Map<String, Object> runtimeContext = form.getRuntimeContext();
        assertEquals("rt_token", runtimeContext.get("task_scoped_token"));
        assertEquals("worker_01", runtimeContext.get("worker_id"));
        assertEquals("bwl_test_01", runtimeContext.get("worker_lease_id"));
        assertEquals("skill_01", runtimeContext.get("skill_name"));
        assertEquals("vision_model_01", runtimeContext.get("vision_model_config_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> executionPolicy = (Map<String, Object>) runtimeContext.get("execution_policy");
        assertEquals("dir_01", executionPolicy.get("directory_id"));
        assertEquals("USER_PRIVATE", executionPolicy.get("workspace_scope"));
        assertEquals("DELEGATED", executionPolicy.get("workspace_resolver_type"));
        assertEquals(false, executionPolicy.get("read_only"));
        assertEquals("/home/sa/workspace/app", executionPolicy.get("workdir"));
        assertEquals(List.of("/home/sa/workspace"), executionPolicy.get("allowed_dirs"));
        assertEquals(List.of("read_file", "invoke_business_function"), executionPolicy.get("allowed_tools"));
        assertDoesNotThrow(() -> OffsetDateTime.parse((String) runtimeContext.get("current_time")));
        assertDoesNotThrow(() -> LocalDate.parse((String) runtimeContext.get("business_date")));
        assertTrue(((String) runtimeContext.get("timezone")).length() > 0);
    }

    @Test
    void resolveWorkerId_selectsFromDatabaseWithoutAllocatingContextOrCreatingTask() {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setWorkerId("worker_01");
        member.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01"))
                .thenReturn(List.of(member));
        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId("worker_01");
        when(workerService.getBusinessAgentWorkerEntity(
                "worker_01", ResourceOwnerType.PLATFORM, "tenant_01"))
                .thenReturn(worker);
        BusinessAgentWorkerTaskLaunchRequest request = request();
        request.setContextId(null);
        request.setSelectedWorkerId(null);

        String workerId = launcher.resolveWorkerId(request);

        assertEquals("worker_01", workerId);
        verify(workerService, never()).createClient(any());
        verifyNoInteractions(taskService);
    }

    @Test
    void launch_rejectsTaskCreatedOnDifferentWorker() {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setWorkerId("worker_01");
        member.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01"))
                .thenReturn(List.of(member));
        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId("worker_01");
        worker.setTenantId("tenant_01");
        when(workerService.getBusinessAgentWorkerEntity(
                "worker_01", ResourceOwnerType.PLATFORM, "tenant_01"))
                .thenReturn(worker);
        when(taskService.createTask(
                eq("actor_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class)))
                .thenReturn(LanggraphTaskDTO.builder()
                        .taskId("lgt_other")
                        .workerId("worker_other")
                        .build());

        SecurityException error = assertThrows(
                SecurityException.class, () -> launcher.launch(request()));

        assertEquals("LangGraph task was created on a different worker", error.getMessage());
    }

    @Test
    void launch_allocatesContextFromWorkerWhenMissing() {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setWorkerId("worker_01");
        member.setStatus("ENABLED");
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01")).thenReturn(List.of(member));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId("worker_01");
        worker.setTenantId("tenant_01");
        when(workerService.getBusinessAgentWorkerEntity(
                "worker_01", ResourceOwnerType.PLATFORM, "tenant_01")).thenReturn(worker);

        LanggraphWorkerClient client = mock(LanggraphWorkerClient.class);
        when(workerService.createClient(worker)).thenReturn(client);
        when(client.allocateContext()).thenReturn(Mono.just(Map.of(
                "contextId", "bctx_20260520_ab_allocated"
        )));

        LanggraphTaskDTO taskDTO = LanggraphTaskDTO.builder()
                .taskId("lgt_01")
                .workerId("worker_01")
                .sessionId("session_01")
                .build();
        when(taskService.createTask(eq("actor_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class))).thenReturn(taskDTO);

        BusinessAgentWorkerTaskLaunchRequest request = request();
        request.setContextId(null);

        BusinessAgentWorkerTaskLaunchResult result = launcher.launch(request);

        assertEquals("bctx_20260520_ab_allocated", result.getContextId());
        ArgumentCaptor<CreateLanggraphTaskForm> formCaptor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("actor_01"), eq("tenant_01"), formCaptor.capture());
        assertEquals("bctx_20260520_ab_allocated", formCaptor.getValue().getContextId());
        assertEquals("bctx_20260520_ab_allocated", formCaptor.getValue().getContext().get("contextId"));
        assertEquals("bctx_20260520_ab_allocated", formCaptor.getValue().getContext().get("context_id"));
    }

    @Test
    void launch_generatesNavigatorContextWhenWorkerContextRouteMissing() {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setWorkerId("worker_01");
        member.setStatus("ENABLED");
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01")).thenReturn(List.of(member));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId("worker_01");
        worker.setTenantId("tenant_01");
        when(workerService.getBusinessAgentWorkerEntity(
                "worker_01", ResourceOwnerType.PLATFORM, "tenant_01")).thenReturn(worker);

        LanggraphWorkerClient client = mock(LanggraphWorkerClient.class);
        when(workerService.createClient(worker)).thenReturn(client);
        when(client.allocateContext()).thenReturn(Mono.error(WebClientResponseException.create(
                404,
                "Not Found",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        )));

        LanggraphTaskDTO taskDTO = LanggraphTaskDTO.builder()
                .taskId("lgt_01")
                .workerId("worker_01")
                .sessionId("session_01")
                .build();
        when(taskService.createTask(eq("actor_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class))).thenReturn(taskDTO);

        BusinessAgentWorkerTaskLaunchRequest request = request();
        request.setContextId(null);

        BusinessAgentWorkerTaskLaunchResult result = launcher.launch(request);

        assertTrue(result.getContextId().matches("bctx_\\d{8}_[0-9a-f]{2}_[0-9a-f]{32}"));
        ArgumentCaptor<CreateLanggraphTaskForm> formCaptor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("actor_01"), eq("tenant_01"), formCaptor.capture());
        assertEquals(result.getContextId(), formCaptor.getValue().getContextId());
        assertEquals(result.getContextId(), formCaptor.getValue().getContext().get("contextId"));
        assertEquals(result.getContextId(), formCaptor.getValue().getContext().get("context_id"));
    }

    @Test
    void launch_preservesExplicitCommandExecutionPolicy() {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setWorkerId("worker_01");
        member.setStatus("ENABLED");
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01")).thenReturn(List.of(member));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId("worker_01");
        worker.setTenantId("tenant_01");
        when(workerService.getBusinessAgentWorkerEntity(
                "worker_01", ResourceOwnerType.PLATFORM, "tenant_01")).thenReturn(worker);

        LanggraphTaskDTO taskDTO = LanggraphTaskDTO.builder()
                .taskId("lgt_01")
                .workerId("worker_01")
                .sessionId("session_01")
                .build();
        when(taskService.createTask(eq("actor_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class))).thenReturn(taskDTO);

        BusinessAgentWorkerTaskLaunchRequest request = request();
        request.setAllowedTools(List.of("read_file", "write_file", "patch_file", "command"));

        launcher.launch(request);

        ArgumentCaptor<CreateLanggraphTaskForm> formCaptor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("actor_01"), eq("tenant_01"), formCaptor.capture());
        Map<String, Object> runtimeContext = formCaptor.getValue().getRuntimeContext();
        @SuppressWarnings("unchecked")
        Map<String, Object> executionPolicy = (Map<String, Object>) runtimeContext.get("execution_policy");
        assertEquals("dir_01", executionPolicy.get("directory_id"));
        assertEquals("/home/sa/workspace/app", executionPolicy.get("workdir"));
        assertEquals(List.of("/home/sa/workspace"), executionPolicy.get("allowed_dirs"));
        assertEquals(List.of("read_file", "write_file", "patch_file", "command"), executionPolicy.get("allowed_tools"));
    }

    @Test
    void launch_preservesWorkspacePolicyPayloads() {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setWorkerId("worker_01");
        member.setStatus("ENABLED");
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01")).thenReturn(List.of(member));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId("worker_01");
        worker.setTenantId("tenant_01");
        when(workerService.getBusinessAgentWorkerEntity(
                "worker_01", ResourceOwnerType.PLATFORM, "tenant_01")).thenReturn(worker);

        LanggraphTaskDTO taskDTO = LanggraphTaskDTO.builder()
                .taskId("lgt_01")
                .workerId("worker_01")
                .sessionId("session_01")
                .build();
        when(taskService.createTask(eq("actor_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class))).thenReturn(taskDTO);

        BusinessAgentWorkerTaskLaunchRequest request = request();
        request.setWorkspaceQuotaPolicy(Map.of("maxBytes", 1048576));
        request.setWorkspaceRetentionPolicy(Map.of("days", 7));
        request.setWorkspaceConcurrencyPolicy(Map.of("maxWriters", 1));

        launcher.launch(request);

        ArgumentCaptor<CreateLanggraphTaskForm> formCaptor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("actor_01"), eq("tenant_01"), formCaptor.capture());
        Map<String, Object> runtimeContext = formCaptor.getValue().getRuntimeContext();
        @SuppressWarnings("unchecked")
        Map<String, Object> executionPolicy = (Map<String, Object>) runtimeContext.get("execution_policy");
        assertEquals(Map.of("maxBytes", 1048576), executionPolicy.get("quota_policy"));
        assertEquals(Map.of("days", 7), executionPolicy.get("retention_policy"));
        assertEquals(Map.of("maxWriters", 1), executionPolicy.get("concurrency_policy"));
    }

    @Test
    void launch_rejectsMissingEnabledPoolMember() {
        BizWorkerPoolMemberEntity disabled = new BizWorkerPoolMemberEntity();
        disabled.setWorkerId("worker_01");
        disabled.setStatus("DISABLED");
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01")).thenReturn(List.of(disabled));

        assertThrows(SecurityException.class, () -> launcher.launch(request()));
        verifyNoInteractions(taskService);
    }

    @Test
    void launch_rejectsWorkerTenantMismatch() {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setWorkerId("worker_01");
        member.setStatus("ENABLED");
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01")).thenReturn(List.of(member));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId("worker_01");
        worker.setTenantId("tenant_other");
        when(workerService.getBusinessAgentWorkerEntity(
                "worker_01", ResourceOwnerType.PLATFORM, "tenant_01")).thenReturn(worker);

        assertThrows(SecurityException.class, () -> launcher.launch(request()));
        verifyNoInteractions(taskService);
    }

    @Test
    void launch_rejectsPhysicalWorkerThatIsNotEnabledPoolMember() {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setWorkerId("worker_01");
        member.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01"))
                .thenReturn(List.of(member));

        BusinessAgentWorkerTaskLaunchRequest request = request();
        request.setSelectedWorkerId("worker_other");

        assertThrows(SecurityException.class, () -> launcher.launch(request));
        verifyNoInteractions(workerService, taskService);
    }

    @Test
    void launch_rejectsIdentityThatBecameUnavailableAfterMemberWasAdded() {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setWorkerId("worker_01");
        member.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01"))
                .thenReturn(List.of(member));
        when(workerService.getBusinessAgentWorkerEntity(
                "worker_01", ResourceOwnerType.PLATFORM, "tenant_01"))
                .thenThrow(new IllegalStateException("LangGraph worker identity is not healthy: worker_01"));

        assertThrows(IllegalStateException.class, () -> launcher.launch(request()));
        verifyNoInteractions(taskService);
    }

    @Test
    void launch_rejectsCrossTenantPoolInsteadOfTreatingItAsPhysicalRoute() {
        when(poolRepository.findByPoolIdAndTenantId("pool_01", "tenant_01"))
                .thenReturn(Optional.empty());
        BizWorkerPoolEntity otherTenantPool = platformPool();
        otherTenantPool.setTenantId("tenant_other");
        when(poolRepository.findByPoolId("pool_01")).thenReturn(Optional.of(otherTenantPool));

        BusinessAgentWorkerTaskLaunchRequest request = request();
        request.setPhysicalWorkerId("pool_01");

        assertThrows(SecurityException.class, () -> launcher.launch(request));
        verifyNoInteractions(poolMemberRepository, workerService, taskService);
    }

    @Test
    void resolveWorkerId_preservesServerResolvedUpstreamScopeForPhysicalRoute() {
        BusinessAgentWorkerTaskLaunchRequest request = request();
        request.setWorkerPoolId("worker_upstream_01");
        request.setPhysicalWorkerId("worker_upstream_01");
        request.setUpstreamSystemId("ups_01");
        when(poolRepository.findByPoolIdAndTenantId("worker_upstream_01", "tenant_01"))
                .thenReturn(Optional.empty());
        when(poolRepository.findByPoolId("worker_upstream_01")).thenReturn(Optional.empty());

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId("worker_upstream_01");
        when(workerService.getBusinessAgentWorkerEntity(
                "worker_upstream_01", ResourceOwnerType.UPSTREAM_SYSTEM, "ups_01"))
                .thenReturn(worker);

        assertEquals("worker_upstream_01", launcher.resolveWorkerId(request));
        verify(workerService).getBusinessAgentWorkerEntity(
                "worker_upstream_01", ResourceOwnerType.UPSTREAM_SYSTEM, "ups_01");
    }

    @Test
    void resolveWorkerId_keepsCanonicalPlatformCompatibilityWhenPhysicalScopeIsBlank() {
        BusinessAgentWorkerTaskLaunchRequest request = request();
        request.setWorkerPoolId("worker_platform_01");
        request.setPhysicalWorkerId("worker_platform_01");
        request.setUpstreamSystemId("  ");
        when(poolRepository.findByPoolIdAndTenantId("worker_platform_01", "tenant_01"))
                .thenReturn(Optional.empty());
        when(poolRepository.findByPoolId("worker_platform_01")).thenReturn(Optional.empty());

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId("worker_platform_01");
        when(workerService.getBusinessAgentWorkerEntity("worker_platform_01", null, null))
                .thenReturn(worker);

        assertEquals("worker_platform_01", launcher.resolveWorkerId(request));
        verify(workerService).getBusinessAgentWorkerEntity("worker_platform_01", null, null);
    }

    @Test
    void launch_rejectsPhysicalUpstreamIdentityMismatchBeforeTaskDispatch() {
        BusinessAgentWorkerTaskLaunchRequest request = request();
        request.setWorkerPoolId("worker_upstream_01");
        request.setPhysicalWorkerId("worker_upstream_01");
        request.setSelectedWorkerId("worker_upstream_01");
        request.setUpstreamSystemId("ups_other");
        when(poolRepository.findByPoolIdAndTenantId("worker_upstream_01", "tenant_01"))
                .thenReturn(Optional.empty());
        when(poolRepository.findByPoolId("worker_upstream_01")).thenReturn(Optional.empty());
        when(workerService.getBusinessAgentWorkerEntity(
                "worker_upstream_01", ResourceOwnerType.UPSTREAM_SYSTEM, "ups_other"))
                .thenThrow(new SecurityException("LangGraph worker identity is not visible to worker pool"));

        assertThrows(SecurityException.class, () -> launcher.launch(request));

        verify(workerService).getBusinessAgentWorkerEntity(
                "worker_upstream_01", ResourceOwnerType.UPSTREAM_SYSTEM, "ups_other");
        verify(workerService, never()).getBusinessAgentWorkerEntity("worker_upstream_01", null, null);
        verifyNoInteractions(poolMemberRepository, taskService);
    }

    @Test
    void launch_rejectsPoolBackendMismatch() {
        BizWorkerPoolEntity pool = platformPool();
        pool.setWorkerBackend("CODEX");
        when(poolRepository.findByPoolIdAndTenantId("pool_01", "tenant_01"))
                .thenReturn(Optional.of(pool));

        assertThrows(IllegalStateException.class, () -> launcher.launch(request()));
        verifyNoInteractions(poolMemberRepository, workerService, taskService);
    }

    private BizWorkerPoolEntity platformPool() {
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId("pool_01");
        pool.setTenantId("tenant_01");
        pool.setOwnerType(ResourceOwnerType.PLATFORM);
        pool.setOwnerId("tenant_01");
        pool.setWorkerBackend(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        pool.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        pool.setHealthStatus(BizWorkerPoolService.HEALTHY);
        return pool;
    }

    private BusinessAgentWorkerTaskLaunchRequest request() {
        return BusinessAgentWorkerTaskLaunchRequest.builder()
                .tenantId("tenant_01")
                .actorUserId("actor_01")
                .businessTaskId("bt_01")
                .sessionId("session_01")
                .contextId("ctx_01")
                .clientAppId("app_01")
                .upstreamUserId("user_01")
                .agentId("agent_01")
                .skillId("skill_01")
                .skillName("skill_01")
                .workerPoolId("pool_01")
                .workerPoolOwnerType(ResourceOwnerType.PLATFORM)
                .workerPoolOwnerId("tenant_01")
                .selectedWorkerId("worker_01")
                .workerLeaseId("bwl_test_01")
                .workerBackend("LANGGRAPH_BIZ")
                .modelConfigId("model_01")
                .visionModelConfigId("vision_model_01")
                .directoryId("dir_01")
                .workspaceScope("USER_PRIVATE")
                .workspaceResolverType("DELEGATED")
                .workspaceReadOnly(false)
                .taskScopedToken("rt_token")
                .workdir("/home/sa/workspace/app")
                .allowedDirs(List.of("/home/sa/workspace"))
                .allowedTools(List.of("read_file", "invoke_business_function"))
                .build();
    }
}
