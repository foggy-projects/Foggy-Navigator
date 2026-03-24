package com.foggy.navigator.codex.worker.adapter;

import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexWorkerA2aAgentTest {

    @Mock
    private CodexTaskService taskService;

    @Test
    void sendTask_usesRequestedDirectoryCwdAndModelConfig() {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("agent-1");
        entity.setUserId("user-1");
        entity.setTenantId("tenant-1");
        entity.setName("codex-agent");
        entity.setAgentType("LOCAL_CODEX_WORKER");
        entity.setWorkerId("worker-1");
        entity.setDefaultDirectoryId("dir-default");

        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                .taskId("task-1")
                .sessionId("session-1")
                .workerId("worker-1")
                .directoryId("dir-requested")
                .build());

        CodexWorkerA2aAgent agent = new CodexWorkerA2aAgent(entity, taskService, "D:\\default", null);
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("hi")))
                .metadata(Map.of(
                        "directoryId", "dir-requested",
                        "cwd", "D:\\requested",
                        "modelConfigId", "model-config-1"
                ))
                .build();

        agent.sendTask(message);

        ArgumentCaptor<CreateCodexTaskForm> captor = ArgumentCaptor.forClass(CreateCodexTaskForm.class);
        verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
        assertEquals("dir-requested", captor.getValue().getDirectoryId());
        assertEquals("D:\\requested", captor.getValue().getCwd());
        assertEquals("model-config-1", captor.getValue().getModelConfigId());
    }
}
