package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexBusinessAgentWorkerTaskLauncherTest {

    @Mock
    private BizWorkerPoolMemberRepository poolMemberRepository;
    @Mock
    private CodexTaskService codexTaskService;

    @Test
    void getWorkerBackendReturnsOpenAiCodex() {
        CodexBusinessAgentWorkerTaskLauncher launcher = new CodexBusinessAgentWorkerTaskLauncher(
                poolMemberRepository, codexTaskService);

        assertEquals(ClientAppModelConfigGrantService.OPENAI_CODEX_BACKEND, launcher.getWorkerBackend());
    }

    @Test
    void launchCreatesCodexBizWorkerTaskWithScopedAccount() {
        CodexBusinessAgentWorkerTaskLauncher launcher = new CodexBusinessAgentWorkerTaskLauncher(
                poolMemberRepository, codexTaskService);
        BusinessAgentWorkerTaskLaunchRequest request = fullRequestBuilder()
                .physicalWorkerId("codex_worker_01")
                .build();
        when(codexTaskService.createTaskDirect(anyMap(), eq("owner_01"), eq("tenant_01")))
                .thenReturn(DispatchTaskDTO.builder()
                        .taskId("ct_01")
                        .sessionId("session_01")
                        .contextId("bctx_01")
                        .build());

        BusinessAgentWorkerTaskLaunchResult result = launcher.launch(request);

        assertEquals("ct_01", result.getWorkerTaskId());
        assertEquals("session_01", result.getWorkerSessionId());
        assertEquals("bctx_01", result.getContextId());
        assertEquals("codex_worker_01", result.getWorkerId());
        assertEquals(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE, result.getProviderType());

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(codexTaskService).createTaskDirect(paramsCaptor.capture(), eq("owner_01"), eq("tenant_01"));
        Map<String, Object> params = paramsCaptor.getValue();
        assertEquals(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE, params.get("providerType"));
        assertEquals("agent_01", params.get("agentId"));
        assertEquals("codex_worker_01", params.get("workerId"));
        assertEquals("session_01", params.get("sessionId"));
        assertEquals("bctx_01", params.get("contextId"));
        assertEquals("model_cfg_01", params.get("modelConfigId"));
        assertEquals("gpt-5-codex", params.get("model"));
        assertEquals("dir_01", params.get("directoryId"));
        assertEquals("D:/workspaces/app-01", params.get("cwd"));
        assertEquals("tenant_01/app_01/scenario-1.actor-1", params.get("codexHomeKey"));
        assertEquals("tenant_01/app_01/scenario-1.actor-1", params.get("privateAccountId"));
        assertIterableEquals(List.of("D:/workspaces/app-01", "D:/shared/reference"), (List<String>) params.get("additionalDirectories"));
        String developerInstructions = (String) params.get("developerInstructions");
        assertTrue(developerInstructions.contains("business_task_id: bat_01"));
        assertFalse(developerInstructions.contains("task_scoped_token"));
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeContext = (Map<String, Object>) params.get("businessRuntimeContext");
        assertEquals("task_token_01", runtimeContext.get("task_scoped_token"));
        assertEquals("bat_01", runtimeContext.get("business_task_id"));
        assertIterableEquals(List.of("business.functions.invoke"), (List<String>) runtimeContext.get("allowed_tools"));
    }

    @Test
    void launchUsesFirstEnabledPoolMemberWhenPhysicalWorkerMissing() {
        CodexBusinessAgentWorkerTaskLauncher launcher = new CodexBusinessAgentWorkerTaskLauncher(
                poolMemberRepository, codexTaskService);
        BizWorkerPoolMemberEntity disabled = member("pool_01", "disabled_worker", "DISABLED");
        BizWorkerPoolMemberEntity enabled = member("pool_01", "enabled_worker", BizWorkerPoolService.STATUS_ENABLED);
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01")).thenReturn(List.of(disabled, enabled));
        when(codexTaskService.createTaskDirect(anyMap(), eq("owner_01"), eq("tenant_01")))
                .thenReturn(DispatchTaskDTO.builder()
                        .taskId("ct_02")
                        .sessionId("session_01")
                        .contextId("bctx_01")
                        .build());

        BusinessAgentWorkerTaskLaunchResult result = launcher.launch(fullRequestBuilder().build());

        assertEquals("enabled_worker", result.getWorkerId());
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(codexTaskService).createTaskDirect(paramsCaptor.capture(), eq("owner_01"), eq("tenant_01"));
        assertEquals("enabled_worker", paramsCaptor.getValue().get("workerId"));
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
                .workerBackend(ClientAppModelConfigGrantService.OPENAI_CODEX_BACKEND)
                .modelConfigId("model_cfg_01")
                .model("gpt-5-codex")
                .directoryId("dir_01")
                .workdir("D:/workspaces/app-01")
                .allowedDirs(List.of("D:/workspaces/app-01", "D:/shared/reference"))
                .allowedTools(List.of("business.functions.invoke"))
                .taskScopedToken("task_token_01");
    }

    private BizWorkerPoolMemberEntity member(String poolId, String workerId, String status) {
        BizWorkerPoolMemberEntity entity = new BizWorkerPoolMemberEntity();
        entity.setPoolId(poolId);
        entity.setWorkerId(workerId);
        entity.setStatus(status);
        return entity;
    }
}
