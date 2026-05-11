package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphTaskDTO;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

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
        assertEquals("worker_01", result.getWorkerId());
        assertEquals(LanggraphTaskService.PROVIDER_TYPE, result.getProviderType());

        ArgumentCaptor<CreateLanggraphTaskForm> formCaptor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("actor_01"), eq("tenant_01"), formCaptor.capture());
        CreateLanggraphTaskForm form = formCaptor.getValue();
        assertEquals("skill_01", form.getAgentId());
        assertEquals("worker_01", form.getWorkerId());
        assertEquals("session_01", form.getSessionId());
        assertEquals("model_01", form.getModelConfigId());
        assertEquals("bt_01", form.getContext().get("businessTaskId"));
        assertEquals("app_01", form.getContext().get("clientAppId"));
        assertEquals("user_01", form.getContext().get("upstreamUserId"));
        assertFalse(form.getContext().containsKey("task_scoped_token"));
        assertEquals(Map.of("task_scoped_token", "rt_token"), form.getRuntimeContext());
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
                .clientAppId("app_01")
                .upstreamUserId("user_01")
                .skillId("skill_01")
                .workerPoolId("pool_01")
                .workerBackend("LANGGRAPH_BIZ")
                .modelConfigId("model_01")
                .taskScopedToken("rt_token")
                .build();
    }
}
