package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRegistryService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexTaskControllerTest {

    @Mock
    private CodexTaskService taskService;

    @Mock
    private WorkerManagementFacade workerManagementFacade;

    @Mock
    private CodexWorkerClientFactory clientFactory;

    @Mock
    private CodexRuntimeRegistryService runtimeRegistryService;

    @Mock
    private CodexWorkerClient client;

    private CodexTaskController controller;

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .build());
        controller = new CodexTaskController(
                taskService, workerManagementFacade, clientFactory, runtimeRegistryService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void legacyEndpointForcesSdkProviderType() {
        CreateCodexTaskForm form = new CreateCodexTaskForm();
        form.setProviderType("codex-biz-worker");
        form.setWorkerId("worker-1");
        form.setPrompt("run actor task");
        form.setCodexHomeKey("tenant/world-sim/scenario-1/actor-1");
        when(taskService.createTask(eq("user-1"), eq("tenant-1"), argThat(request ->
                "codex-worker".equals(request.getProviderType())
                        && "tenant/world-sim/scenario-1/actor-1".equals(request.getCodexHomeKey()))))
                .thenReturn(CodexTaskDTO.builder().taskId("task-sdk-1").build());

        RX<CodexTaskDTO> result = controller.createTask(form);

        assertEquals("task-sdk-1", result.getData().getTaskId());
        verify(taskService).createTask(eq("user-1"), eq("tenant-1"), argThat(request ->
                "codex-worker".equals(request.getProviderType())
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

    @Test
    void getSessionFileHintsRejectsAppServerProvider() {
        CodexTaskEntity task = task("user-1");
        task.setRuntimeId("app-main");
        task.setRuntimeType("APP_SERVER");
        task.setRuntimeInstanceId("instance-a");
        task.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity("task-1")).thenReturn(task);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.getSessionFileHints("task-1", 30, null, null));

        assertEquals("Task not found: task-1", error.getMessage());
        verifyNoInteractions(workerManagementFacade, clientFactory, client);
    }

    @Test
    void legacyCommandsRejectAppServerTasksBeforeMutation() {
        when(taskService.getTaskForProvider(
                "user-1", "task-app", CodexTaskService.CODEX_PROVIDER_TYPE))
                .thenThrow(new IllegalArgumentException("Task not found: task-app"));

        assertThrows(IllegalArgumentException.class, () -> controller.abortTask("task-app"));
        assertThrows(IllegalArgumentException.class, () -> controller.reconnectTask("task-app"));

        verify(taskService, never()).cancelTaskDirect("task-app", "user-1");
        verify(taskService, never()).reconnectTask("task-app", "user-1");
    }

    @Test
    void getGeneratedImageUsesPinnedAppServerRuntimeAndProxiesSafeHeaders() {
        String artifactId = "0123456789abcdef0123456789abcdef";
        byte[] image = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
        CodexTaskEntity task = task("user-1");
        task.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        task.setRuntimeId("app-main");
        task.setRuntimeRevision(3);
        task.setRuntimeType(CodexRuntimeType.APP_SERVER.name());
        task.setRuntimeInstanceId("instance-a");
        task.setWorkerTaskId("worker-task-1");
        when(taskService.getTaskEntity("task-1")).thenReturn(task);

        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeRevision(3)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .endpointUrl("http://127.0.0.1:3062")
                .authToken("runtime-token")
                .instanceId("instance-a")
                .routingEpoch(7L)
                .build();
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 3, "worker-1", "instance-a")).thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:3", "http://127.0.0.1:3062", "runtime-token", "instance-a"))
                .thenReturn(client);
        when(client.getGeneratedImage("worker-task-1", artifactId)).thenReturn(Mono.just(
                ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=generated.png")
                        .body(image)));

        ResponseEntity<byte[]> response = controller.getGeneratedImage("task-1", artifactId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        assertEquals("inline; filename=generated.png",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertArrayEquals(image, response.getBody());
        verify(workerManagementFacade).validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(client).getGeneratedImage("worker-task-1", artifactId);
    }

    @Test
    void legacyListUsesSdkProviderScope() {
        when(taskService.listTasksForProvider("user-1", CodexTaskService.CODEX_PROVIDER_TYPE))
                .thenReturn(List.of());

        controller.listTasks(null);

        verify(taskService).listTasksForProvider("user-1", CodexTaskService.CODEX_PROVIDER_TYPE);
        verify(taskService, never()).listTasks("user-1");
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
        task.setRuntimeId("legacy-sdk:worker-1");
        task.setRuntimeRevision(1);
        task.setRuntimeType("SDK_EXEC");
        task.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        task.setStatus("COMPLETED");
        return task;
    }
}
