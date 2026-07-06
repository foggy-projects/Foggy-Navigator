package com.foggy.navigator.langgraph.worker.adapter;

import com.foggy.navigator.common.dto.a2a.A2aContext;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphTaskDTO;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
import com.foggy.navigator.langgraph.worker.service.LanggraphTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                        .directoryId("dir_default")
                        .cwd("D:/workspace/default")
                        .build());

        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("world-sim.bug-coordinator.decision.v1");
        entity.setName("World Sim");
        entity.setUserId("admin_01");
        entity.setTenantId("tenant_01");
        entity.setWorkerId("worker_01");
        entity.setDefaultModelConfigId("model_default");
        entity.setDefaultModel("sonnet");
        entity.setDefaultDirectoryId("dir_default");

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(entity, taskService, "worker_01");
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text("hello")));
        message.setContextId("ctx_01");
        message.setMetadata(Map.of(
                "skill_name", "tms.navigator.agent",
                "runtimeContext", Map.of("task_scoped_token", "btt_01")));

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
        assertEquals("dir_default", captor.getValue().getDirectoryId());
        assertNull(captor.getValue().getSkillName());
    }

    @Test
    void sendTask_usesRuntimeDirectoryAndCwdFromMetadataWhenPresent() {
        LanggraphTaskService taskService = mock(LanggraphTaskService.class);
        when(taskService.createTask(eq("admin_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class)))
                .thenReturn(LanggraphTaskDTO.builder()
                        .taskId("lgt_01")
                        .sessionId("session_01")
                        .workerId("worker_01")
                        .directoryId("dir_runtime")
                        .cwd("/home/navigator/actor-home")
                        .build());

        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("agent_01");
        entity.setName("Agent");
        entity.setUserId("admin_01");
        entity.setTenantId("tenant_01");
        entity.setDefaultDirectoryId("dir_default");

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(entity, taskService, "worker_01");
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text("hello")));
        message.setMetadata(Map.of(
                "directoryId", "dir_runtime",
                "cwd", "/home/navigator/actor-home"));

        A2aTask task = agent.sendTask(A2aContext.builder()
                .message(message)
                .contextId("ctx_01")
                .navigatorSessionId("session_01")
                .build());

        ArgumentCaptor<CreateLanggraphTaskForm> captor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("admin_01"), eq("tenant_01"), captor.capture());
        assertEquals("dir_runtime", captor.getValue().getDirectoryId());
        assertEquals("/home/navigator/actor-home", captor.getValue().getCwd());
        assertEquals("dir_runtime", task.getMetadata().get("directoryId"));
        assertEquals("/home/navigator/actor-home", task.getMetadata().get("cwd"));
    }

    @Test
    void sendTask_ignoresA2aSkillMetadataAliases() {
        LanggraphTaskService taskService = mock(LanggraphTaskService.class);
        when(taskService.createTask(eq("admin_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class)))
                .thenReturn(LanggraphTaskDTO.builder().taskId("lgt_01").sessionId("session_01").build());

        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("agent_01");
        entity.setName("Agent");
        entity.setUserId("admin_01");
        entity.setTenantId("tenant_01");

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(entity, taskService, "worker_01");
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text("hello")));
        message.setMetadata(Map.of(
                "skill_name", "canonical.skill",
                "skill_id", "legacy.skill",
                "context", Map.of(
                        "businessSkillName", "hidden.skill",
                        "skillId", "hidden.skill",
                        "keep", "value"
                ),
                "runtimeContext", Map.of(
                        "skill_name", "hidden.skill",
                        "task_scoped_token", "btt_01"
                )));

        A2aTask task = agent.sendTask(A2aContext.builder()
                .message(message)
                .contextId("ctx_01")
                .navigatorSessionId("session_01")
                .build());

        ArgumentCaptor<CreateLanggraphTaskForm> captor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("admin_01"), eq("tenant_01"), captor.capture());
        CreateLanggraphTaskForm form = captor.getValue();
        assertNull(form.getSkillName());
        assertEquals("value", form.getContext().get("keep"));
        assertFalse(form.getContext().containsKey("businessSkillName"));
        assertFalse(form.getContext().containsKey("skillId"));
        assertEquals("btt_01", form.getRuntimeContext().get("task_scoped_token"));
        assertFalse(form.getRuntimeContext().containsKey("skill_name"));

        Map<String, Object> echoedMetadata = task.getHistory().get(0).getMetadata();
        assertFalse(echoedMetadata.containsKey("skill_name"));
        assertFalse(echoedMetadata.containsKey("skill_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> echoedContext = (Map<String, Object>) echoedMetadata.get("context");
        assertEquals("value", echoedContext.get("keep"));
        assertFalse(echoedContext.containsKey("businessSkillName"));
        assertFalse(echoedContext.containsKey("skillId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> echoedRuntimeContext = (Map<String, Object>) echoedMetadata.get("runtimeContext");
        assertEquals("btt_01", echoedRuntimeContext.get("task_scoped_token"));
        assertFalse(echoedRuntimeContext.containsKey("skill_name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendTask_acceptsSnakeCaseRuntimeContextAlias() {
        LanggraphTaskService taskService = mock(LanggraphTaskService.class);
        when(taskService.createTask(eq("admin_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class)))
                .thenReturn(LanggraphTaskDTO.builder().taskId("lgt_01").sessionId("session_01").build());

        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("agent_01");
        entity.setName("Agent");
        entity.setUserId("admin_01");
        entity.setTenantId("tenant_01");

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(entity, taskService, "worker_01");
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text("hello")));
        message.setMetadata(Map.of(
                "runtime_context", Map.of(
                        "execution_policy", Map.of("workdir", "/workspace/user"),
                        "skill_name", "hidden.skill"
                )));

        A2aTask task = agent.sendTask(A2aContext.builder()
                .message(message)
                .contextId("ctx_01")
                .navigatorSessionId("session_01")
                .build());

        ArgumentCaptor<CreateLanggraphTaskForm> captor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("admin_01"), eq("tenant_01"), captor.capture());
        Map<String, Object> runtimeContext = captor.getValue().getRuntimeContext();
        Map<String, Object> executionPolicy = (Map<String, Object>) runtimeContext.get("execution_policy");
        assertEquals("/workspace/user", executionPolicy.get("workdir"));
        assertFalse(runtimeContext.containsKey("skill_name"));

        Map<String, Object> echoedMetadata = task.getHistory().get(0).getMetadata();
        Map<String, Object> echoedRuntimeContext = (Map<String, Object>) echoedMetadata.get("runtime_context");
        assertFalse(echoedRuntimeContext.containsKey("skill_name"));
        assertEquals(Map.of("workdir", "/workspace/user"), echoedRuntimeContext.get("execution_policy"));
    }

    @Test
    void sendTask_preservesOwnerContextWhileStrippingA2aSkillRoutingKeys() {
        LanggraphTaskService taskService = mock(LanggraphTaskService.class);
        when(taskService.createTask(eq("admin_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class)))
                .thenReturn(LanggraphTaskDTO.builder().taskId("lgt_01").sessionId("session_01").build());

        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("school-sim.actor.pm.m2.v1");
        entity.setName("PM");
        entity.setUserId("admin_01");
        entity.setTenantId("tenant_01");

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("clientAppId", "capp_01");
        context.put("upstreamUserId", "sim-upstream-user-local");
        context.put("accountId", "sim-upstream-user-local");
        context.put("account_id", "sim-upstream-user-local");
        context.put("rootAgentId", "school-sim.actor.pm.m2.v1");
        context.put("auto_inject_app_public_skills", true);
        context.put("businessSkillName", "hidden.skill");
        context.put("businessSkillId", "hidden.skill");
        context.put("skillId", "hidden.skill");
        context.put("skill_name", "hidden.skill");

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(entity, taskService, "worker_01");
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text(
                "Use the school-sim.actor.pm.m2.v1 skill and write the marker.")));
        message.setMetadata(Map.of(
                "skill_name", "hidden.skill",
                "skillId", "hidden.skill",
                "context", context,
                "runtimeContext", Map.of(
                        "task_scoped_token", "btt_01",
                        "skill_name", "hidden.skill"
                )));

        A2aTask task = agent.sendTask(A2aContext.builder()
                .message(message)
                .contextId("ctx_01")
                .navigatorSessionId("session_01")
                .build());

        ArgumentCaptor<CreateLanggraphTaskForm> captor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("admin_01"), eq("tenant_01"), captor.capture());
        CreateLanggraphTaskForm form = captor.getValue();
        assertNull(form.getSkillName());
        assertEquals("capp_01", form.getContext().get("clientAppId"));
        assertEquals("sim-upstream-user-local", form.getContext().get("upstreamUserId"));
        assertEquals("sim-upstream-user-local", form.getContext().get("accountId"));
        assertEquals("sim-upstream-user-local", form.getContext().get("account_id"));
        assertEquals("school-sim.actor.pm.m2.v1", form.getContext().get("rootAgentId"));
        assertEquals(true, form.getContext().get("auto_inject_app_public_skills"));
        assertFalse(form.getContext().containsKey("businessSkillName"));
        assertFalse(form.getContext().containsKey("businessSkillId"));
        assertFalse(form.getContext().containsKey("skillId"));
        assertFalse(form.getContext().containsKey("skill_name"));
        assertEquals("btt_01", form.getRuntimeContext().get("task_scoped_token"));
        assertFalse(form.getRuntimeContext().containsKey("skill_name"));

        Map<String, Object> echoedMetadata = task.getHistory().get(0).getMetadata();
        assertFalse(echoedMetadata.containsKey("skill_name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> echoedContext = (Map<String, Object>) echoedMetadata.get("context");
        assertEquals("capp_01", echoedContext.get("clientAppId"));
        assertEquals("sim-upstream-user-local", echoedContext.get("upstreamUserId"));
        assertEquals("sim-upstream-user-local", echoedContext.get("accountId"));
        assertEquals("sim-upstream-user-local", echoedContext.get("account_id"));
        assertEquals("school-sim.actor.pm.m2.v1", echoedContext.get("rootAgentId"));
        assertEquals(true, echoedContext.get("auto_inject_app_public_skills"));
        assertFalse(echoedContext.containsKey("businessSkillName"));
        assertFalse(echoedContext.containsKey("businessSkillId"));
        assertFalse(echoedContext.containsKey("skillId"));
        assertFalse(echoedContext.containsKey("skill_name"));
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

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(entity, taskService, "worker_01");
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

    @Test
    void sendTask_usesResolvedWorkerIdInsteadOfAgentStoredWorkerId() {
        LanggraphTaskService taskService = mock(LanggraphTaskService.class);
        when(taskService.createTask(eq("admin_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class)))
                .thenReturn(LanggraphTaskDTO.builder()
                        .taskId("lgt_01")
                        .sessionId("session_01")
                        .workerId("worker_default")
                        .build());

        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("agent_01");
        entity.setName("Agent");
        entity.setUserId("admin_01");
        entity.setTenantId("tenant_01");
        entity.setWorkerId("old_pool_id");

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(entity, taskService, "worker_default");
        agent.sendTask(A2aContext.builder()
                .message(A2aMessage.user(List.of(A2aPart.text("hello"))))
                .contextId("ctx_01")
                .build());

        ArgumentCaptor<CreateLanggraphTaskForm> captor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("admin_01"), eq("tenant_01"), captor.capture());
        assertEquals("worker_default", captor.getValue().getWorkerId());
    }

    @Test
    void sendTask_usesRuntimeWorkerIdFromMetadataWhenPresent() {
        LanggraphTaskService taskService = mock(LanggraphTaskService.class);
        when(taskService.createTask(eq("admin_01"), eq("tenant_01"), any(CreateLanggraphTaskForm.class)))
                .thenReturn(LanggraphTaskDTO.builder()
                        .taskId("lgt_01")
                        .sessionId("session_01")
                        .workerId("school-sim-wsl-biz")
                        .build());

        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("school-sim.actor.pm.m2.v1");
        entity.setName("PM");
        entity.setUserId("admin_01");
        entity.setTenantId("tenant_01");
        entity.setWorkerId("2ca910a6");

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(
                entity, taskService, "dev-langgraph-worker-20260504123547");
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text("write marker")));
        message.setMetadata(Map.of(
                "workerId", "school-sim-wsl-biz",
                "workerSource", "BIZ_WORKER_IDENTITY"));

        agent.sendTask(A2aContext.builder()
                .message(message)
                .contextId("ctx_01")
                .build());

        ArgumentCaptor<CreateLanggraphTaskForm> captor = ArgumentCaptor.forClass(CreateLanggraphTaskForm.class);
        verify(taskService).createTask(eq("admin_01"), eq("tenant_01"), captor.capture());
        assertEquals("school-sim-wsl-biz", captor.getValue().getWorkerId());
    }

    @Test
    void getTask_mapsFailedLanggraphTaskToA2aFailedState() {
        LanggraphTaskService taskService = mock(LanggraphTaskService.class);
        when(taskService.getTask("admin_01", "lgt_failed")).thenReturn(
                LanggraphTaskDTO.builder()
                        .taskId("lgt_failed")
                        .status("FAILED")
                        .resultText("storage_permission_denied: write_file permission denied for 'REPORT.md'.")
                        .build()
        );

        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setUserId("admin_01");

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(entity, taskService, "worker_01");

        A2aTask task = agent.getTask("lgt_failed").orElseThrow();

        assertEquals(A2aTaskState.FAILED, task.getStatus().getState());
        assertEquals(
                "storage_permission_denied: write_file permission denied for 'REPORT.md'.",
                task.getArtifacts().get(0).getParts().get(0).getText()
        );
    }

    @Test
    void cancelTask_recordsRecoverableInterruptionThroughTaskService() {
        LanggraphTaskService taskService = mock(LanggraphTaskService.class);
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setUserId("admin_01");

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(entity, taskService, "worker_01");

        agent.cancelTask("lgt_01");

        verify(taskService).cancelTaskDirect("lgt_01", "admin_01");
    }

    @Test
    void abortWorkerTask_recordsRecoverableInterruptionThroughTaskService() {
        LanggraphTaskService taskService = mock(LanggraphTaskService.class);
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setUserId("admin_01");

        LanggraphWorkerInnerA2aAgent agent = new LanggraphWorkerInnerA2aAgent(entity, taskService, "worker_01");

        agent.abortWorkerTask("lgt_01", "remote_01");

        verify(taskService).cancelTaskDirect("lgt_01", "admin_01");
    }
}
