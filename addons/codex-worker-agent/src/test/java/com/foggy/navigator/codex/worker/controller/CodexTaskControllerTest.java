package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.service.CodexStreamRelay;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexTaskControllerTest {

    @Mock
    private CodexTaskService taskService;

    @Mock
    private CodexStreamRelay streamRelay;

    @Mock
    private WorkerManagementFacade workerManagementFacade;

    @Mock
    private CodexWorkerClientFactory clientFactory;

    @Mock
    private CodexWorkerClient client;

    private CodexTaskController controller;

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .build());
        controller = new CodexTaskController(taskService, streamRelay, workerManagementFacade, clientFactory);
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

    @Test
    void getSessionFileHints_proxiesCodexThreadAndEnrichesTaskContext() {
        CodexTaskEntity task = task("user-1");
        Map<String, Object> workerResult = new LinkedHashMap<>();
        workerResult.put("session_id", "thread-1");
        workerResult.put("files", List.of(Map.of("filePath", "D:/repo/src/app.ts")));
        workerResult.put("total", 1);

        when(taskService.getTaskEntity("task-1")).thenReturn(task);
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(CodexConfig.builder()
                .baseUrl("http://localhost:3051")
                .authToken("worker-token")
                .build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://localhost:3051", "worker-token"))
                .thenReturn(client);
        when(client.getSessionFileHints("thread-1", 7, "2026-06-01", "2026-06-28"))
                .thenReturn(Mono.just(workerResult));

        RX<Map<String, Object>> result = controller.getSessionFileHints(
                "task-1", 7, "2026-06-01", "2026-06-28");

        assertEquals(1, result.getData().get("total"));
        assertEquals("task-1", result.getData().get("taskId"));
        assertEquals("session-1", result.getData().get("sessionId"));
        assertEquals("thread-1", result.getData().get("codexThreadId"));
        assertEquals("dir-1", result.getData().get("directoryId"));
        assertEquals("D:/repo", result.getData().get("cwd"));
        verify(workerManagementFacade).validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(client).getSessionFileHints("thread-1", 7, "2026-06-01", "2026-06-28");
    }

    @Test
    void getSessionFileHints_rejectsTasksOwnedByOtherUsers() {
        when(taskService.getTaskEntity("task-1")).thenReturn(task("other-user"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.getSessionFileHints("task-1", 30, null, null));

        assertEquals("Task not found: task-1", error.getMessage());
        verifyNoInteractions(workerManagementFacade, clientFactory, client);
    }

    private CodexTaskEntity task(String userId) {
        CodexTaskEntity task = new CodexTaskEntity();
        task.setTaskId("task-1");
        task.setSessionId("session-1");
        task.setDirectoryId("dir-1");
        task.setWorkerId("worker-1");
        task.setUserId(userId);
        task.setTenantId("tenant-1");
        task.setCwd("D:/repo");
        task.setCodexThreadId("thread-1");
        task.setStatus("COMPLETED");
        return task;
    }
}
