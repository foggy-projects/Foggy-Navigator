package com.foggy.navigator.langgraph.worker.adapter;

import com.foggy.navigator.common.dto.a2a.A2aContext;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphTaskDTO;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
import com.foggy.navigator.langgraph.worker.service.LanggraphTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LanggraphWorkerInnerA2aAgentTest {

    @Test
    void sendTask_usesAgentDefaultModelConfigWhenMetadataDoesNotOverride() {
        LanggraphTaskService taskService = mock(LanggraphTaskService.class);
        when(taskService.createTask(eq("admin_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class)))
                .thenReturn(LanggraphTaskDTO.builder()
                        .taskId("lgt_01")
                        .sessionId("session_01")
                        .workerId("worker_01")
                        .build());

        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("world-sim.bug-coordinator.decision.v1");
        entity.setName("World Sim");
        entity.setUserId("admin_01");
        entity.setTenantId("tenant_01");
        entity.setWorkerId("worker_01");
        entity.setDefaultModelConfigId("model_default");
        entity.setDefaultModel("sonnet");

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(entity, taskService);
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text("hello")));
        message.setContextId("ctx_01");
        message.setMetadata(Map.of("runtimeContext", Map.of("task_scoped_token", "btt_01")));

        agent.sendTask(A2aContext.builder()
                .message(message)
                .contextId("ctx_01")
                .navigatorSessionId("session_01")
                .build());

        ArgumentCaptor<CreateLanggraphTaskForm> captor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("admin_01"), eq("tenant_01"), captor.capture());
        assertEquals("model_default", captor.getValue().getModelConfigId());
        assertEquals("sonnet", captor.getValue().getModel());
        assertEquals("worker_01", captor.getValue().getWorkerId());
    }

    @Test
    void sendTask_metadataModelOverridesAgentDefault() {
        LanggraphTaskService taskService = mock(LanggraphTaskService.class);
        when(taskService.createTask(eq("admin_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class)))
                .thenReturn(LanggraphTaskDTO.builder().taskId("lgt_01").sessionId("session_01").build());

        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("agent_01");
        entity.setName("Agent");
        entity.setUserId("admin_01");
        entity.setTenantId("tenant_01");
        entity.setWorkerId("worker_01");
        entity.setDefaultModelConfigId("model_default");
        entity.setDefaultModel("sonnet");

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(entity, taskService);
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text("hello")));
        message.setMetadata(Map.of("modelConfigId", "model_override", "model", "opus", "maxTurns", 12));

        agent.sendTask(A2aContext.builder()
                .message(message)
                .contextId("ctx_01")
                .navigatorSessionId("session_01")
                .build());

        ArgumentCaptor<CreateLanggraphTaskForm> captor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("admin_01"), eq("tenant_01"), captor.capture());
        assertEquals("model_override", captor.getValue().getModelConfigId());
        assertEquals("opus", captor.getValue().getModel());
        assertEquals(12, captor.getValue().getMaxTurns());
    }
}
