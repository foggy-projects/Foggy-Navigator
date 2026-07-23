package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.BizWorkerPoolWorkerSelector;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexBusinessAgentWorkerTaskLauncherTest {

    @Mock
    private BizWorkerPoolMemberRepository poolMemberRepository;
    @Mock
    private BizWorkerPoolService bizWorkerPoolService;
    @Mock
    private CodexTaskService codexTaskService;

    @Test
    void getWorkerBackendReturnsOpenAiCodex() {
        CodexBusinessAgentWorkerTaskLauncher launcher = launcher();

        assertEquals(ClientAppModelConfigGrantService.OPENAI_CODEX_BACKEND, launcher.getWorkerBackend());
    }

    @Test
    void launchCreatesCodexBizWorkerTaskWithScopedAccount() {
        CodexBusinessAgentWorkerTaskLauncher launcher = launcher();
        BusinessAgentWorkerTaskLaunchRequest request = fullRequestBuilder()
                .physicalWorkerId("codex_worker_01")
                .selectedWorkerId("codex_worker_01")
                .build();
        when(bizWorkerPoolService.requireAvailablePool(
                "tenant_01", ResourceOwnerType.PLATFORM,
                "tenant_01", "pool_01"))
                .thenReturn(pool());
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01"))
                .thenReturn(List.of(member(
                        "pool_01", "codex_worker_01", BizWorkerPoolService.STATUS_ENABLED)));
        when(codexTaskService.createTaskDirectForProvider(
                eq(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE), anyMap(), eq("owner_01"), eq("tenant_01")))
                .thenReturn(DispatchTaskDTO.builder()
                        .taskId("ct_01")
                        .sessionId("session_01")
                        .contextId("bctx_01")
                        .workerId("codex_worker_01")
                        .build());

        BusinessAgentWorkerTaskLaunchResult result = launcher.launch(request);

        assertEquals("ct_01", result.getWorkerTaskId());
        assertEquals("session_01", result.getWorkerSessionId());
        assertEquals("bctx_01", result.getContextId());
        assertEquals("codex_worker_01", result.getWorkerId());
        assertEquals(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE, result.getProviderType());

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(codexTaskService).createTaskDirectForProvider(
                eq(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE), paramsCaptor.capture(),
                eq("owner_01"), eq("tenant_01"));
        verify(codexTaskService, never()).createTaskDirect(anyMap(), eq("owner_01"), eq("tenant_01"));
        Map<String, Object> params = paramsCaptor.getValue();
        assertEquals(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE, params.get("providerType"));
        assertEquals("agent_01", params.get("agentId"));
        assertEquals("codex_worker_01", params.get("workerId"));
        assertEquals("session_01", params.get("sessionId"));
        assertEquals("bctx_01", params.get("contextId"));
        assertEquals("model_cfg_01", params.get("modelConfigId"));
        assertEquals("gpt-5-codex", params.get("model"));
        assertEquals("dir_01", params.get("directoryId"));
        assertEquals("/home/sa/workspace/app-01", params.get("cwd"));
        assertEquals("tenant_01/app_01/scenario-1.actor-1", params.get("codexHomeKey"));
        assertEquals("tenant_01/app_01/scenario-1.actor-1", params.get("privateAccountId"));
        assertIterableEquals(List.of("/home/sa/workspace/app-01", "/home/sa/workspace/shared/reference"), (List<String>) params.get("additionalDirectories"));
        String developerInstructions = (String) params.get("developerInstructions");
        assertTrue(developerInstructions.contains("business_task_id: bat_01"));
        assertFalse(developerInstructions.contains("task_scoped_token"));
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeContext = (Map<String, Object>) params.get("businessRuntimeContext");
        assertEquals("task_token_01", runtimeContext.get("task_scoped_token"));
        assertEquals("codex_worker_01", runtimeContext.get("worker_id"));
        assertEquals("bwl_test_01", runtimeContext.get("worker_lease_id"));
        assertEquals("bat_01", runtimeContext.get("business_task_id"));
        assertIterableEquals(List.of("business.functions.invoke"), (List<String>) runtimeContext.get("allowed_tools"));
    }

    @Test
    void launchPreservesExplicitEmptyAllowedToolsInBusinessRuntimeContext() {
        CodexBusinessAgentWorkerTaskLauncher launcher = launcher();
        BusinessAgentWorkerTaskLaunchRequest request = fullRequestBuilder()
                .physicalWorkerId("codex_worker_01")
                .selectedWorkerId("codex_worker_01")
                .allowedTools(List.of())
                .build();
        when(bizWorkerPoolService.requireAvailablePool(
                "tenant_01", ResourceOwnerType.PLATFORM, "tenant_01", "pool_01"))
                .thenReturn(pool());
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01"))
                .thenReturn(List.of(member(
                        "pool_01", "codex_worker_01", BizWorkerPoolService.STATUS_ENABLED)));
        when(codexTaskService.createTaskDirectForProvider(
                eq(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE), anyMap(), eq("owner_01"), eq("tenant_01")))
                .thenReturn(DispatchTaskDTO.builder()
                        .taskId("ct_empty_tools")
                        .sessionId("session_01")
                        .contextId("bctx_01")
                        .workerId("codex_worker_01")
                        .build());

        launcher.launch(request);

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(codexTaskService).createTaskDirectForProvider(
                eq(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE), paramsCaptor.capture(),
                eq("owner_01"), eq("tenant_01"));
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeContext =
                (Map<String, Object>) paramsCaptor.getValue().get("businessRuntimeContext");
        assertTrue(runtimeContext.containsKey("allowed_tools"));
        assertEquals(List.of(), runtimeContext.get("allowed_tools"));
        assertTrue(((String) paramsCaptor.getValue().get("developerInstructions"))
                .contains("allowed_tools: []"));
    }

    @Test
    void launchUsesFirstEnabledPoolMemberWhenPhysicalWorkerMissing() {
        CodexBusinessAgentWorkerTaskLauncher launcher = launcher();
        when(bizWorkerPoolService.requireAvailablePool(
                "tenant_01", ResourceOwnerType.PLATFORM,
                "tenant_01", "pool_01"))
                .thenReturn(pool());
        BizWorkerPoolMemberEntity disabled = member("pool_01", "disabled_worker", "DISABLED");
        BizWorkerPoolMemberEntity enabled = member("pool_01", "enabled_worker", BizWorkerPoolService.STATUS_ENABLED);
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01")).thenReturn(List.of(disabled, enabled));
        when(codexTaskService.createTaskDirectForProvider(
                eq(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE), anyMap(), eq("owner_01"), eq("tenant_01")))
                .thenReturn(DispatchTaskDTO.builder()
                        .taskId("ct_02")
                        .sessionId("session_01")
                        .contextId("bctx_01")
                        .workerId("enabled_worker")
                        .build());

        BusinessAgentWorkerTaskLaunchRequest request = fullRequestBuilder().build();
        request.setSelectedWorkerId(launcher.resolveWorkerId(request));
        BusinessAgentWorkerTaskLaunchResult result = launcher.launch(request);

        assertEquals("enabled_worker", result.getWorkerId());
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(codexTaskService).createTaskDirectForProvider(
                eq(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE), paramsCaptor.capture(),
                eq("owner_01"), eq("tenant_01"));
        assertEquals("enabled_worker", paramsCaptor.getValue().get("workerId"));
    }

    @Test
    void resolveWorkerIdSelectsFromPoolWithoutCallingProvider() {
        CodexBusinessAgentWorkerTaskLauncher launcher = launcher();
        when(bizWorkerPoolService.requireAvailablePool(
                "tenant_01", ResourceOwnerType.PLATFORM, "tenant_01", "pool_01"))
                .thenReturn(pool());
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01"))
                .thenReturn(List.of(member(
                        "pool_01", "codex_worker_01", BizWorkerPoolService.STATUS_ENABLED)));

        String workerId = launcher.resolveWorkerId(fullRequestBuilder().build());

        assertEquals("codex_worker_01", workerId);
        verifyNoInteractions(codexTaskService);
    }

    @Test
    void resolveWorkerIdUsesDirectPhysicalWorkerWhenPoolOwnerIsAbsent() {
        CodexBusinessAgentWorkerTaskLauncher launcher = launcher();
        BusinessAgentWorkerTaskLaunchRequest request = fullRequestBuilder()
                .workerPoolId("codex_worker_direct")
                .workerPoolOwnerType(null)
                .workerPoolOwnerId(null)
                .physicalWorkerId("codex_worker_direct")
                .build();

        assertEquals("codex_worker_direct", launcher.resolveWorkerId(request));
        verifyNoInteractions(bizWorkerPoolService, poolMemberRepository, codexTaskService);
    }

    @Test
    void launchRejectsPreselectedWorkerThatIsNotEnabledPoolMember() {
        CodexBusinessAgentWorkerTaskLauncher launcher = launcher();
        when(bizWorkerPoolService.requireAvailablePool(
                "tenant_01", ResourceOwnerType.PLATFORM, "tenant_01", "pool_01"))
                .thenReturn(pool());
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01"))
                .thenReturn(List.of(member(
                        "pool_01", "codex_worker_01", BizWorkerPoolService.STATUS_ENABLED)));
        BusinessAgentWorkerTaskLaunchRequest request = fullRequestBuilder()
                .selectedWorkerId("codex_worker_other")
                .build();

        SecurityException error = assertThrows(
                SecurityException.class, () -> launcher.launch(request));

        assertTrue(error.getMessage().contains("not an enabled pool member"));
        verifyNoInteractions(codexTaskService);
    }

    @Test
    void launchRejectsTaskCreatedOnDifferentWorker() {
        CodexBusinessAgentWorkerTaskLauncher launcher = launcher();
        when(bizWorkerPoolService.requireAvailablePool(
                "tenant_01", ResourceOwnerType.PLATFORM, "tenant_01", "pool_01"))
                .thenReturn(pool());
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01"))
                .thenReturn(List.of(member(
                        "pool_01", "codex_worker_01", BizWorkerPoolService.STATUS_ENABLED)));
        when(codexTaskService.createTaskDirectForProvider(
                eq(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE), anyMap(),
                eq("owner_01"), eq("tenant_01")))
                .thenReturn(DispatchTaskDTO.builder()
                        .taskId("ct_other")
                        .workerId("codex_worker_other")
                        .build());
        BusinessAgentWorkerTaskLaunchRequest request = fullRequestBuilder()
                .selectedWorkerId("codex_worker_01")
                .build();

        SecurityException error = assertThrows(
                SecurityException.class, () -> launcher.launch(request));

        assertEquals("Codex task was created on a different worker", error.getMessage());
    }

    private BusinessAgentWorkerTaskLaunchRequest.BusinessAgentWorkerTaskLaunchRequestBuilder fullRequestBuilder() {
        return BusinessAgentWorkerTaskLaunchRequest.builder()
                .tenantId("tenant_01")
                .actorUserId("owner_01")
                .businessTaskId("bat_01")
                .sessionId("session_01")
                .contextId("bctx_01")
                .clientAppId("app_01")
                .upstreamUserId("scenario-1.actor-1")
                .agentId("agent_01")
                .skillId("skill_01")
                .skillName("dispatch order")
                .workerPoolId("pool_01")
                .workerPoolOwnerType(ResourceOwnerType.PLATFORM)
                .workerPoolOwnerId("tenant_01")
                .workerBackend(ClientAppModelConfigGrantService.OPENAI_CODEX_BACKEND)
                .modelConfigId("model_cfg_01")
                .model("gpt-5-codex")
                .directoryId("dir_01")
                .workdir("/home/sa/workspace/app-01")
                .allowedDirs(List.of("/home/sa/workspace/app-01", "/home/sa/workspace/shared/reference"))
                .allowedTools(List.of("business.functions.invoke"))
                .workerLeaseId("bwl_test_01")
                .taskScopedToken("task_token_01");
    }

    private CodexBusinessAgentWorkerTaskLauncher launcher() {
        return new CodexBusinessAgentWorkerTaskLauncher(
                new BizWorkerPoolWorkerSelector(bizWorkerPoolService, poolMemberRepository),
                new CodexBizTaskProvider(codexTaskService));
    }

    private BizWorkerPoolEntity pool() {
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId("pool_01");
        pool.setTenantId("tenant_01");
        pool.setOwnerType(ResourceOwnerType.PLATFORM);
        pool.setOwnerId("tenant_01");
        pool.setWorkerBackend(ClientAppModelConfigGrantService.OPENAI_CODEX_BACKEND);
        pool.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        pool.setHealthStatus(BizWorkerPoolService.HEALTHY);
        return pool;
    }

    private BizWorkerPoolMemberEntity member(String poolId, String workerId, String status) {
        BizWorkerPoolMemberEntity entity = new BizWorkerPoolMemberEntity();
        entity.setPoolId(poolId);
        entity.setWorkerId(workerId);
        entity.setStatus(status);
        return entity;
    }
}
