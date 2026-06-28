package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.service.CodexStreamRelay;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexTaskControllerTest {

    @Mock
    private CodexTaskService taskService;

    @Mock
    private CodexStreamRelay streamRelay;

    private CodexTaskController controller;

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .build());
        controller = new CodexTaskController(taskService, streamRelay);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createTaskPreservesExplicitCodexBizProviderTypeOnLegacyEndpoint() {
        CreateCodexTaskForm form = new CreateCodexTaskForm();
        form.setProviderType("codex-biz-worker");
        form.setWorkerId("worker-1");
        form.setPrompt("run actor task");
        form.setCodexHomeKey("tenant/world-sim/scenario-1/actor-1");
        when(taskService.createTask(eq("user-1"), eq("tenant-1"), argThat(request ->
                "codex-biz-worker".equals(request.getProviderType())
                        && "tenant/world-sim/scenario-1/actor-1".equals(request.getCodexHomeKey()))))
                .thenReturn(CodexTaskDTO.builder().taskId("task-biz-1").build());

        RX<CodexTaskDTO> result = controller.createTask(form);

        assertEquals("task-biz-1", result.getData().getTaskId());
        verify(taskService).createTask(eq("user-1"), eq("tenant-1"), argThat(request ->
                "codex-biz-worker".equals(request.getProviderType())
                        && "tenant/world-sim/scenario-1/actor-1".equals(request.getCodexHomeKey())));
    }
}
