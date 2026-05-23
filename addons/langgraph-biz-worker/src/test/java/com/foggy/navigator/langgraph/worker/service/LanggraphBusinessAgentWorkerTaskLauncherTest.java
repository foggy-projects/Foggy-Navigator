package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphTaskDTO;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
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
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LanggraphBusinessAgentWorkerTaskLauncherTest {

    @Mock
    private BizWorkerPoolMemberRepository poolMemberRepository;

    @Mock
    private LanggraphWorkerService workerService;

    @Mock
    private LanggraphTaskService taskService;

    @InjectMocks
    private LanggraphBusinessAgentWorkerTaskLauncher launcher;

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
        when(workerService.getWorkerEntity("worker_01")).thenReturn(worker);

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
        assertEquals("skill_01", form.getAgentId());
        assertNull(form.getSkillName());
        assertEquals("worker_01", form.getWorkerId());
        assertEquals("session_01", form.getSessionId());
        assertEquals("ctx_01", form.getContextId());
        assertEquals("model_01", form.getModelConfigId());
        assertFalse(form.getPrompt().contains("skill_01"));
        assertEquals("bt_01", form.getContext().get("businessTaskId"));
        assertEquals("ctx_01", form.getContext().get("contextId"));
        assertEquals("ctx_01", form.getContext().get("context_id"));
        assertEquals("session_01", form.getContext().get("session_id"));
        assertEquals("app_01", form.getContext().get("clientAppId"));
        assertEquals("skill_01", form.getContext().get("businessSkillId"));
        assertEquals("skill_01", form.getContext().get("businessSkillName"));
        assertEquals("user_01", form.getContext().get("upstreamUserId"));
        assertEquals("user_01", form.getContext().get("accountId"));
        assertEquals("user_01", form.getContext().get("account_id"));
        assertEquals(true, form.getContext().get("auto_inject_app_public_skills"));
        assertFalse(form.getContext().containsKey("task_scoped_token"));
        assertFalse(form.getContext().containsKey("skillId"));
        assertFalse(form.getContext().containsKey("skill_name"));
        assertFalse(form.getContext().containsKey("skillName"));
        Map<String, Object> runtimeContext = form.getRuntimeContext();
        assertEquals("rt_token", runtimeContext.get("task_scoped_token"));
        assertEquals("skill_01", runtimeContext.get("skill_name"));
        assertEquals("vision_model_01", runtimeContext.get("vision_model_config_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> executionPolicy = (Map<String, Object>) runtimeContext.get("execution_policy");
        assertEquals("D:/workspace/app", executionPolicy.get("workdir"));
        assertEquals(List.of("D:/workspace"), executionPolicy.get("allowed_dirs"));
        assertEquals(List.of("read_file", "invoke_business_function"), executionPolicy.get("allowed_tools"));
        assertDoesNotThrow(() -> OffsetDateTime.parse((String) runtimeContext.get("current_time")));
        assertDoesNotThrow(() -> LocalDate.parse((String) runtimeContext.get("business_date")));
        assertTrue(((String) runtimeContext.get("timezone")).length() > 0);
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
        when(workerService.getWorkerEntity("worker_01")).thenReturn(worker);

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
        when(workerService.getWorkerEntity("worker_01")).thenReturn(worker);

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
        when(workerService.getWorkerEntity("worker_01")).thenReturn(worker);

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
        assertEquals("D:/workspace/app", executionPolicy.get("workdir"));
        assertEquals(List.of("D:/workspace"), executionPolicy.get("allowed_dirs"));
        assertEquals(List.of("read_file", "write_file", "patch_file", "command"), executionPolicy.get("allowed_tools"));
    }

    @Test
    void launch_rejectsMissingEnabledPoolMember() {
        BizWorkerPoolMemberEntity disabled = new BizWorkerPoolMemberEntity();
        disabled.setWorkerId("worker_01");
        disabled.setStatus("DISABLED");
        when(poolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool_01")).thenReturn(List.of(disabled));

        assertThrows(IllegalStateException.class, () -> launcher.launch(request()));
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
        when(workerService.getWorkerEntity("worker_01")).thenReturn(worker);

        assertThrows(SecurityException.class, () -> launcher.launch(request()));
        verifyNoInteractions(taskService);
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
                .skillId("skill_01")
                .skillName("skill_01")
                .workerPoolId("pool_01")
                .workerBackend("LANGGRAPH_BIZ")
                .modelConfigId("model_01")
                .visionModelConfigId("vision_model_01")
                .taskScopedToken("rt_token")
                .workdir("D:/workspace/app")
                .allowedDirs(List.of("D:/workspace"))
                .allowedTools(List.of("read_file", "invoke_business_function"))
                .build();
    }
}
