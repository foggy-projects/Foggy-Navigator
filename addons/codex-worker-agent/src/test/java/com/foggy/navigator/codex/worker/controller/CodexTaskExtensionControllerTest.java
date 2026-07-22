package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRegistryService;
import com.foggy.navigator.codex.worker.service.CodexRuntimeUnavailableException;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.session.service.SessionTaskResourceAccessService;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexTaskExtensionControllerTest {

    private static final String TASK_ID = "task-1";
    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String ARTIFACT_ID = "0123456789abcdef0123456789abcdef";

    @Mock
    private SessionTaskResourceAccessService resourceAccessService;

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

    private CodexTaskExtensionController controller;

    @BeforeEach
    void setUp() {
        setCurrentUser(USER_ID, TENANT_ID);
        controller = new CodexTaskExtensionController(
                resourceAccessService, taskService, workerManagementFacade,
                clientFactory, runtimeRegistryService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void controllerRequiresAuthenticationAndUsesUnifiedTaskRoute() {
        assertNotNull(CodexTaskExtensionController.class.getAnnotation(RequireAuth.class));
        RequestMapping mapping = CodexTaskExtensionController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/v1/tasks"}, mapping.value());
    }

    @Test
    void listCodexCanaryTasks_returnsCurrentUsersTypedAppServerPage() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId(TASK_ID)
                .userId(USER_ID)
                .providerType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE)
                .build();
        TaskPageResult page = TaskPageResult.of(List.of(task), 1L, 2, 25);
        when(taskService.listTasksPagedForProvider(
                USER_ID, TENANT_ID, 2, 25, null, "worker-1",
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE))
                .thenReturn(page);

        RX<TaskPageResult> response = controller.listCodexCanaryTasks(2, 25, "worker-1");

        assertEquals(page, response.getData());
        assertEquals(DispatchTaskDTO.class, response.getData().content().get(0).getClass());
        verify(taskService).listTasksPagedForProvider(
                USER_ID, TENANT_ID, 2, 25, null, "worker-1",
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
    }

    @Test
    void listCodexCanaryTasks_missingAuthenticatedPrincipalIsRejected() {
        UserContext.clear();

        assertThrows(SecurityException.class,
                () -> controller.listCodexCanaryTasks(0, 20, null));

        verifyNoInteractions(taskService);
    }

    @Test
    void listCodexCanaryTasks_rejectsInvalidPageAndSizeWithoutQuerying() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.listCodexCanaryTasks(-1, 20, null));
        assertThrows(IllegalArgumentException.class,
                () -> controller.listCodexCanaryTasks(0, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> controller.listCodexCanaryTasks(0, 201, null));
        assertThrows(IllegalArgumentException.class,
                () -> controller.listCodexCanaryTasks(Integer.MAX_VALUE, 2, null));

        verifyNoInteractions(taskService);
    }

    @Test
    void staleTurnCleanupEligibilityReturnsOwnedSafeFalseProjectionOnly() {
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getStaleTurnCleanupEligibility(TASK_ID, USER_ID, TENANT_ID))
                .thenReturn(new CodexTaskService.StaleTurnCleanupEligibility(
                        TASK_ID, false, "STALE_TURN_CLEANUP_TASK_NOT_TERMINAL"));

        RX<Map<String, Object>> response = controller.getStaleTurnCleanupEligibility(TASK_ID);

        assertEquals(TASK_ID, response.getData().get("taskId"));
        assertEquals(false, response.getData().get("eligible"));
        assertEquals("STALE_TURN_CLEANUP_TASK_NOT_TERMINAL", response.getData().get("reasonCode"));
        assertEquals(3, response.getData().size());
        verify(resourceAccessService).requireOwnedTask(TASK_ID, USER_ID, TENANT_ID);
        verify(taskService).getStaleTurnCleanupEligibility(TASK_ID, USER_ID, TENANT_ID);
        verifyNoInteractions(workerManagementFacade, clientFactory, runtimeRegistryService, client);
    }

    @Test
    void staleTurnCleanupEligibilityRequiresCurrentWorkerAccess() {
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getStaleTurnCleanupEligibility(TASK_ID, USER_ID, TENANT_ID))
                .thenReturn(new CodexTaskService.StaleTurnCleanupEligibility(TASK_ID, true, null));
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(appServerTask());

        RX<Map<String, Object>> response = controller.getStaleTurnCleanupEligibility(TASK_ID);

        assertEquals(true, response.getData().get("eligible"));
        assertFalse(response.getData().containsKey("reasonCode"));
        verify(workerManagementFacade).validateWorkerAccess(USER_ID, TENANT_ID, "worker-1");
    }

    @Test
    void staleTurnCleanupEligibilityHidesActionWhenWorkerAccessWasRevoked() {
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getStaleTurnCleanupEligibility(TASK_ID, USER_ID, TENANT_ID))
                .thenReturn(new CodexTaskService.StaleTurnCleanupEligibility(TASK_ID, true, null));
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(appServerTask());
        doThrow(new IllegalArgumentException("Worker not found"))
                .when(workerManagementFacade)
                .validateWorkerAccess(USER_ID, TENANT_ID, "worker-1");

        RX<Map<String, Object>> response = controller.getStaleTurnCleanupEligibility(TASK_ID);

        assertEquals(false, response.getData().get("eligible"));
        assertEquals("STALE_TURN_CLEANUP_UNAVAILABLE", response.getData().get("reasonCode"));
        assertEquals(3, response.getData().size());
        verify(workerManagementFacade).validateWorkerAccess(USER_ID, TENANT_ID, "worker-1");
    }

    @Test
    void staleTurnCleanupOwnedTenantlessTaskIsBodylessAndReturnsOnlySafeReceipt() {
        setCurrentUser(USER_ID, null);
        SessionTaskEntity owned = new SessionTaskEntity();
        owned.setTaskId(TASK_ID);
        owned.setSessionId("session-1");
        owned.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        owned.setWorkerId("worker-1");
        owned.setUserId(USER_ID);
        owned.setTenantId(null);
        CodexTaskEntity task = appServerTask();
        task.setTenantId(null);
        when(resourceAccessService.requireOwnedTask(TASK_ID, USER_ID, null)).thenReturn(owned);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        when(taskService.cleanupStaleTurn(TASK_ID, USER_ID, null))
                .thenReturn(new CodexTaskService.StaleTurnCleanupResult(
                        TASK_ID, "to-cleanup-1", "cleaned"));

        RX<Map<String, Object>> response = controller.cleanupStaleTurn(TASK_ID);

        assertEquals(TASK_ID, response.getData().get("taskId"));
        assertEquals("to-cleanup-1", response.getData().get("operationId"));
        assertEquals("cleaned", response.getData().get("status"));
        assertEquals(3, response.getData().size());
        assertFalse(response.getData().containsKey("workerTaskId"));
        assertFalse(response.getData().containsKey("threadId"));
        assertFalse(response.getData().containsKey("turnId"));
        assertFalse(response.getData().containsKey("runtimeId"));
        verify(resourceAccessService).requireOwnedTask(TASK_ID, USER_ID, null);
        verify(workerManagementFacade).validateWorkerAccess(USER_ID, null, "worker-1");
        verify(taskService).cleanupStaleTurn(TASK_ID, USER_ID, null);
        verifyNoInteractions(clientFactory, runtimeRegistryService, client);
    }

    @Test
    void staleTurnCleanupRejectsOwnershipBeforeCodexPrivateLookup() {
        setCurrentUser("other-user", TENANT_ID);
        when(resourceAccessService.requireOwnedTask(TASK_ID, "other-user", TENANT_ID))
                .thenThrow(new SecurityException("Resource access denied"));

        assertThrows(SecurityException.class, () -> controller.cleanupStaleTurn(TASK_ID));

        verifyNoInteractions(taskService, workerManagementFacade, clientFactory, runtimeRegistryService, client);
    }

    @Test
    void staleTurnCleanupExceptionHandlerMapsSafeConflictAndRetryableOutcomes() {
        ResponseEntity<RX<?>> conflict = controller.handleStaleTurnCleanupException(
                new CodexTaskService.StaleTurnCleanupException("STALE_TURN_CLEANUP_AFFINITY_CHANGED"));
        ResponseEntity<RX<?>> retryable = controller.handleStaleTurnCleanupException(
                new CodexTaskService.StaleTurnCleanupException(
                        "STALE_TURN_CLEANUP_UNCONFIRMED", true));

        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("STALE_TURN_CLEANUP_AFFINITY_CHANGED", conflict.getBody().getMsg());
        assertNull(conflict.getBody().getData());
        assertEquals(503, retryable.getStatusCode().value());
        assertEquals("STALE_TURN_CLEANUP_UNCONFIRMED", retryable.getBody().getMsg());
        assertNull(retryable.getBody().getData());
    }

    @Test
    void getSessionFileHints_ownerUsesTaskBoundThreadAndFiltersPrivateWorkerFields() {
        CodexTaskEntity task = sdkTask();
        stubOwnedTask(CodexTaskService.CODEX_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(CodexConfig.builder()
                .baseUrl("http://localhost:3051")
                .authToken("worker-secret")
                .build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://localhost:3051", "worker-secret"))
                .thenReturn(client);

        Map<String, Object> file = new LinkedHashMap<>();
        file.put("filePath", "D:/repo/src/app.ts");
        file.put("pathScope", "inside_cwd");
        file.put("openableInFileBrowser", true);
        file.put("local_path", "/worker/private/file-hints.jsonl");
        file.put("auth_token", "nested-secret");
        Map<String, Object> workerResult = new LinkedHashMap<>();
        workerResult.put("taskId", "attacker-task");
        workerResult.put("sessionId", "attacker-session");
        workerResult.put("files", List.of(file));
        workerResult.put("total", 1);
        workerResult.put("local_path", "/worker/private/file-hints.jsonl");
        workerResult.put("apiKey", "root-secret");
        when(client.getSessionFileHints("thread-1", 7, "2026-06-01", "2026-06-28"))
                .thenReturn(Mono.just(workerResult));

        RX<Map<String, Object>> response = controller.getSessionFileHints(
                TASK_ID, 7, "2026-06-01", "2026-06-28");

        Map<String, Object> body = response.getData();
        assertEquals(TASK_ID, body.get("taskId"));
        assertEquals("session-1", body.get("sessionId"));
        assertEquals("thread-1", body.get("codexThreadId"));
        assertEquals("dir-1", body.get("directoryId"));
        assertEquals("D:/repo", body.get("cwd"));
        assertFalse(body.containsKey("local_path"));
        assertFalse(body.containsKey("apiKey"));
        @SuppressWarnings("unchecked")
        Map<String, Object> returnedFile = (Map<String, Object>) ((List<?>) body.get("files")).get(0);
        assertEquals("D:/repo/src/app.ts", returnedFile.get("filePath"));
        assertFalse(returnedFile.containsKey("local_path"));
        assertFalse(returnedFile.containsKey("auth_token"));

        InOrder order = inOrder(resourceAccessService, taskService, workerManagementFacade, client);
        order.verify(resourceAccessService).requireOwnedTask(TASK_ID, USER_ID, TENANT_ID);
        order.verify(taskService).getTaskEntity(TASK_ID);
        order.verify(workerManagementFacade).validateWorkerAccess(USER_ID, TENANT_ID, "worker-1");
        order.verify(client).getSessionFileHints("thread-1", 7, "2026-06-01", "2026-06-28");
    }

    @Test
    void getSessionFileHints_crossUserIsRejectedBeforeCodexLookup() {
        setCurrentUser("other-user", TENANT_ID);
        when(resourceAccessService.requireOwnedTask(TASK_ID, "other-user", TENANT_ID))
                .thenThrow(new SecurityException("Resource access denied"));

        assertThrows(SecurityException.class,
                () -> controller.getSessionFileHints(TASK_ID, 30, null, null));

        verifyNoInteractions(taskService, workerManagementFacade, clientFactory, client,
                runtimeRegistryService);
    }

    @Test
    void getSessionFileHints_crossTenantIsRejectedBeforeCodexLookup() {
        setCurrentUser(USER_ID, "other-tenant");
        when(resourceAccessService.requireOwnedTask(TASK_ID, USER_ID, "other-tenant"))
                .thenThrow(new SecurityException("Resource access denied"));

        assertThrows(SecurityException.class,
                () -> controller.getSessionFileHints(TASK_ID, 30, null, null));

        verifyNoInteractions(taskService, workerManagementFacade, clientFactory, client,
                runtimeRegistryService);
    }

    @Test
    void getSessionFileHints_providerMismatchIsRejectedBeforePrivateTaskLookup() {
        stubOwnedTask("claude-worker");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.getSessionFileHints(TASK_ID, 30, null, null));

        assertEquals("Task not found: " + TASK_ID, error.getMessage());
        verifyNoInteractions(taskService, workerManagementFacade, clientFactory, client,
                runtimeRegistryService);
    }

    @Test
    void getSessionFileHints_runtimeMismatchDoesNotReachWorker() {
        CodexTaskEntity task = sdkTask();
        task.setRuntimeType(CodexRuntimeType.APP_SERVER.name());
        stubOwnedTask(CodexTaskService.CODEX_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);

        assertThrows(IllegalArgumentException.class,
                () -> controller.getSessionFileHints(TASK_ID, 30, null, null));

        verifyNoInteractions(workerManagementFacade, clientFactory, client, runtimeRegistryService);
    }

    @Test
    void getSessionFileHints_missingThreadReturnsBoundEmptyResultWithoutWorkerCredentials() {
        CodexTaskEntity task = sdkTask();
        task.setCodexThreadId(null);
        stubOwnedTask(CodexTaskService.CODEX_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);

        RX<Map<String, Object>> response = controller.getSessionFileHints(
                TASK_ID, 30, null, null);

        assertEquals(List.of(), response.getData().get("files"));
        assertEquals(0, response.getData().get("total"));
        assertEquals(TASK_ID, response.getData().get("taskId"));
        verify(workerManagementFacade).validateWorkerAccess(USER_ID, TENANT_ID, "worker-1");
        verify(workerManagementFacade, never()).getCodexConfig("worker-1");
        verifyNoInteractions(clientFactory, client, runtimeRegistryService);
    }

    @Test
    void getGeneratedImage_ownerUsesPinnedRuntimeAndCopiesOnlySafeHeaders() {
        byte[] image = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
        CodexTaskEntity task = appServerTask();
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        CodexRuntimeBinding binding = appServerBinding();
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 3, "worker-1", "instance-a")).thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:3", "http://127.0.0.1:3062", "runtime-secret", "instance-a"))
                .thenReturn(client);
        when(client.getGeneratedImage("worker-task-1", ARTIFACT_ID)).thenReturn(Mono.just(
                ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=generated.png")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer worker-secret")
                        .header("X-Worker-Local-Path", "/worker/generated/private.png")
                        .body(image)));

        ResponseEntity<byte[]> response = controller.getGeneratedImage(TASK_ID, ARTIFACT_ID);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals("inline",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertNull(response.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        assertNull(response.getHeaders().getFirst("X-Worker-Local-Path"));
        assertArrayEquals(image, response.getBody());

        InOrder order = inOrder(resourceAccessService, taskService, workerManagementFacade,
                runtimeRegistryService, clientFactory, client);
        order.verify(resourceAccessService).requireOwnedTask(TASK_ID, USER_ID, TENANT_ID);
        order.verify(taskService).getTaskEntity(TASK_ID);
        order.verify(workerManagementFacade).validateWorkerAccess(USER_ID, TENANT_ID, "worker-1");
        order.verify(runtimeRegistryService).resolveBoundRuntime(
                "app-main", 3, "worker-1", "instance-a");
        order.verify(clientFactory).getOrCreate(
                "runtime:app-main:3", "http://127.0.0.1:3062", "runtime-secret", "instance-a");
        order.verify(client).getGeneratedImage("worker-task-1", ARTIFACT_ID);
    }

    @Test
    void getContextUsage_ownerUsesPinnedRuntimeAndSanitizesWorkerPayload() {
        CodexTaskEntity task = appServerTask();
        task.setCodexThreadId("thread-1");
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 3, "worker-1", "instance-a")).thenReturn(appServerBinding());
        when(clientFactory.getOrCreate(
                "runtime:app-main:3", "http://127.0.0.1:3062", "runtime-secret", "instance-a"))
                .thenReturn(client);
        when(client.getTaskContextUsage("worker-task-1")).thenReturn(Mono.just(new LinkedHashMap<>(Map.of(
                "task_id", "attacker-task",
                "thread_id", "attacker-thread",
                "last_total_tokens", 81234,
                "model_context_window", 270000,
                "remaining_tokens", 188766,
                "status", "known",
                "auth_token", "secret"))));

        RX<Map<String, Object>> response = controller.getContextUsage(TASK_ID);

        assertEquals(TASK_ID, response.getData().get("taskId"));
        assertEquals("session-1", response.getData().get("sessionId"));
        assertEquals("thread-1", response.getData().get("codexThreadId"));
        assertEquals(81234, response.getData().get("current_tokens"));
        assertFalse(response.getData().containsKey("auth_token"));
        verify(workerManagementFacade).validateWorkerAccess(USER_ID, TENANT_ID, "worker-1");
        verify(client).getTaskContextUsage("worker-task-1");
    }

    @Test
    void terminationInspectionReturnsOnlyBrowserSafeStateFromPinnedRuntime() {
        CodexTaskEntity task = appServerTask();
        task.setStatus("CANCEL_REQUESTED");
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 3, "worker-1", "instance-a")).thenReturn(appServerBinding());
        when(clientFactory.getOrCreate(
                "runtime:app-main:3", "http://127.0.0.1:3062", "runtime-secret", "instance-a"))
                .thenReturn(client);
        when(client.getTerminationInspection("worker-task-1"))
                .thenReturn(Mono.just(new LinkedHashMap<>(Map.ofEntries(
                        Map.entry("task_id", "worker-task-1"),
                        Map.entry("thread_id", "private-thread"),
                        Map.entry("turn_id", "private-turn"),
                        Map.entry("app_server_instance_id", "private-instance"),
                        Map.entry("auth_token", "private-token"),
                        Map.entry("lifecycle_status", "abort_requested"),
                        Map.entry("provider_state", "in_progress"),
                        Map.entry("thread_status", "active"),
                        Map.entry("turn_status", "inProgress"),
                        Map.entry("recommended_action", "RETRY_INTERRUPT"),
                        Map.entry("checked_at", "2026-07-19T10:00:00Z")))));

        RX<Map<String, Object>> response = controller.getTerminationInspection(TASK_ID);

        assertEquals(TASK_ID, response.getData().get("taskId"));
        assertEquals("CANCEL_REQUESTED", response.getData().get("taskStatus"));
        assertEquals("in_progress", response.getData().get("providerState"));
        assertEquals("RETRY_INTERRUPT", response.getData().get("recommendedAction"));
        assertFalse(response.getData().containsKey("task_id"));
        assertFalse(response.getData().containsKey("thread_id"));
        assertFalse(response.getData().containsKey("turn_id"));
        assertFalse(response.getData().containsKey("app_server_instance_id"));
        assertFalse(response.getData().containsKey("auth_token"));
        verify(client).getTerminationInspection("worker-task-1");
    }

    @Test
    void terminationInspectionReturnsSafeRuntimeUnavailableCode() {
        CodexTaskEntity task = appServerTask();
        task.setStatus("CANCEL_REQUESTED");
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 3, "worker-1", "instance-a"))
                .thenThrow(new CodexRuntimeUnavailableException(
                        "CODEX_RUNTIME_INSTANCE_UNAVAILABLE", "private runtime details"));

        RX<Map<String, Object>> response = controller.getTerminationInspection(TASK_ID);

        assertEquals("CODEX_RUNTIME_INSTANCE_UNAVAILABLE", response.getMsg());
        assertNull(response.getData());
        verifyNoInteractions(clientFactory, client);
    }

    @Test
    void terminationInspectionKeepsGenericCodeForUnexpectedFailure() {
        CodexTaskEntity task = appServerTask();
        task.setStatus("CANCEL_REQUESTED");
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 3, "worker-1", "instance-a")).thenReturn(appServerBinding());
        when(clientFactory.getOrCreate(
                "runtime:app-main:3", "http://127.0.0.1:3062", "runtime-secret", "instance-a"))
                .thenReturn(client);
        when(client.getTerminationInspection("worker-task-1"))
                .thenReturn(Mono.error(new IllegalStateException("private unexpected details")));

        RX<Map<String, Object>> response = controller.getTerminationInspection(TASK_ID);

        assertEquals("CODEX_TERMINATION_INSPECTION_UNAVAILABLE", response.getMsg());
        assertNull(response.getData());
    }

    @Test
    void terminationRetryRequiresOwnedPinnedAppServerTaskAndReturnsSafeReceipt() {
        CodexTaskEntity task = appServerTask();
        task.setStatus("CANCEL_REQUESTED");
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        when(taskService.retryAppServerAbort(TASK_ID, USER_ID, TENANT_ID))
                .thenReturn(new CodexTaskService.AppServerAbortRetryResult(
                        TASK_ID, "to-retry-1", "interrupted", "ABORTED"));

        RX<Map<String, Object>> response = controller.retryTermination(TASK_ID);

        assertEquals(Map.of(
                "taskId", TASK_ID,
                "operationId", "to-retry-1",
                "providerState", "interrupted",
                "status", "ABORTED"), response.getData());
        verify(workerManagementFacade).validateWorkerAccess(USER_ID, TENANT_ID, "worker-1");
        verify(taskService).retryAppServerAbort(TASK_ID, USER_ID, TENANT_ID);
        verifyNoInteractions(clientFactory, runtimeRegistryService, client);
    }

    @Test
    void terminationRetrySupportsOwnedSdkTaskAndReturnsPendingReceipt() {
        CodexTaskEntity task = sdkTask();
        task.setStatus("CANCEL_REQUESTED");
        stubOwnedTask(CodexTaskService.CODEX_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        when(taskService.retrySdkAbort(TASK_ID, USER_ID, TENANT_ID))
                .thenReturn(new CodexTaskService.SdkAbortRetryResult(
                        TASK_ID, "to-sdk-retry-1", "cancel_requested", "CANCEL_REQUESTED"));

        RX<Map<String, Object>> response = controller.retryTermination(TASK_ID);

        assertEquals(Map.of(
                "taskId", TASK_ID,
                "operationId", "to-sdk-retry-1",
                "providerState", "cancel_requested",
                "status", "CANCEL_REQUESTED"), response.getData());
        verify(workerManagementFacade).validateWorkerAccess(USER_ID, TENANT_ID, "worker-1");
        verify(taskService).retrySdkAbort(TASK_ID, USER_ID, TENANT_ID);
        verifyNoInteractions(clientFactory, runtimeRegistryService, client);
    }

    @Test
    void compactContext_ownerUsesPinnedRuntimeAndOperationId() {
        CodexTaskEntity task = appServerTask();
        task.setCodexThreadId("thread-1");
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 3, "worker-1", "instance-a")).thenReturn(appServerBinding());
        when(clientFactory.getOrCreate(
                "runtime:app-main:3", "http://127.0.0.1:3062", "runtime-secret", "instance-a"))
                .thenReturn(client);
        when(client.compactTaskContext("worker-task-1", "compact-20260717-1"))
                .thenReturn(Mono.just(new LinkedHashMap<>(Map.of(
                        "task_id", "worker-task-1",
                        "operation_id", "compact-20260717-1",
                        "status", "completed",
                        "thread_id", "thread-1",
                        "compact_turn_id", "compact-turn-1"))));

        RX<Map<String, Object>> response = controller.compactContext(
                TASK_ID, new CodexTaskExtensionController.CompactContextRequest("compact-20260717-1"));

        assertEquals(TASK_ID, response.getData().get("taskId"));
        assertEquals("thread-1", response.getData().get("codexThreadId"));
        assertEquals("completed", response.getData().get("status"));
        assertEquals("compact-20260717-1", response.getData().get("operation_id"));
        assertEquals("compact-turn-1", response.getData().get("turn_id"));
        assertFalse(response.getData().containsKey("compact_turn_id"));
        verify(client).compactTaskContext("worker-task-1", "compact-20260717-1");
    }

    @Test
    void getCompactContextOperation_ownerReadsPinnedDurableOperation() {
        CodexTaskEntity task = appServerTask();
        task.setCodexThreadId("thread-1");
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 3, "worker-1", "instance-a")).thenReturn(appServerBinding());
        when(clientFactory.getOrCreate(
                "runtime:app-main:3", "http://127.0.0.1:3062", "runtime-secret", "instance-a"))
                .thenReturn(client);
        when(client.getTaskContextCompactOperation("worker-task-1", "compact-20260717-1"))
                .thenReturn(Mono.just(new LinkedHashMap<>(Map.of(
                        "operation_id", "compact-20260717-1",
                        "status", "completed",
                        "compact_turn_id", "compact-turn-1"))));

        RX<Map<String, Object>> response = controller.getCompactContextOperation(
                TASK_ID, "compact-20260717-1");

        assertEquals(TASK_ID, response.getData().get("taskId"));
        assertEquals("thread-1", response.getData().get("codexThreadId"));
        assertEquals("compact-turn-1", response.getData().get("turn_id"));
        verify(client).getTaskContextCompactOperation("worker-task-1", "compact-20260717-1");
    }

    @Test
    void compactContext_rejectsRunningTaskBeforeResolvingRuntime() {
        CodexTaskEntity task = appServerTask();
        task.setStatus("RUNNING");
        task.setCodexThreadId("thread-1");
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.compactContext(TASK_ID,
                        new CodexTaskExtensionController.CompactContextRequest("compact-20260717-1")));

        assertEquals("TASK_NOT_TERMINAL", error.getMessage());
        verifyNoInteractions(workerManagementFacade, runtimeRegistryService, clientFactory, client);
    }

    @Test
    void getGeneratedImage_invalidArtifactIdIsRejectedInsideOwnedTaskBoundary() {
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(appServerTask());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.getGeneratedImage(TASK_ID, "../../private.png"));

        assertEquals("artifactId is invalid", error.getMessage());
        InOrder order = inOrder(resourceAccessService, taskService);
        order.verify(resourceAccessService).requireOwnedTask(TASK_ID, USER_ID, TENANT_ID);
        order.verify(taskService).getTaskEntity(TASK_ID);
        verifyNoInteractions(workerManagementFacade, runtimeRegistryService, clientFactory, client);
    }

    @Test
    void getGeneratedImage_providerMismatchIsRejectedBeforePrivateTaskLookup() {
        stubOwnedTask(CodexTaskService.CODEX_PROVIDER_TYPE);

        assertThrows(IllegalArgumentException.class,
                () -> controller.getGeneratedImage(TASK_ID, ARTIFACT_ID));

        verifyNoInteractions(taskService, workerManagementFacade, runtimeRegistryService,
                clientFactory, client);
    }

    @Test
    void getGeneratedImage_missingPinnedInstanceDoesNotFallBackToAnotherRuntime() {
        CodexTaskEntity task = appServerTask();
        task.setRuntimeInstanceId(null);
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);

        assertThrows(IllegalArgumentException.class,
                () -> controller.getGeneratedImage(TASK_ID, ARTIFACT_ID));

        verifyNoInteractions(workerManagementFacade, runtimeRegistryService, clientFactory, client);
    }

    @Test
    void getGeneratedImage_neverForwardsWorkerFilename() {
        CodexTaskEntity task = appServerTask();
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        CodexRuntimeBinding binding = appServerBinding();
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 3, "worker-1", "instance-a")).thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:3", "http://127.0.0.1:3062", "runtime-secret", "instance-a"))
                .thenReturn(client);
        when(client.getGeneratedImage("worker-task-1", ARTIFACT_ID)).thenReturn(Mono.just(
                ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=/worker/generated/private.png")
                        .body(new byte[]{1})));

        ResponseEntity<byte[]> response = controller.getGeneratedImage(TASK_ID, ARTIFACT_ID);

        assertEquals("inline", response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void getGeneratedImage_rejectsNonRasterWorkerResponse() {
        CodexTaskEntity task = appServerTask();
        stubOwnedTask(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskService.getTaskEntity(TASK_ID)).thenReturn(task);
        CodexRuntimeBinding binding = appServerBinding();
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 3, "worker-1", "instance-a")).thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:3", "http://127.0.0.1:3062", "runtime-secret", "instance-a"))
                .thenReturn(client);
        when(client.getGeneratedImage("worker-task-1", ARTIFACT_ID)).thenReturn(Mono.just(
                ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body("<script>nope</script>".getBytes())));

        ResponseEntity<byte[]> response = controller.getGeneratedImage(TASK_ID, ARTIFACT_ID);

        assertEquals(500, response.getStatusCode().value());
        assertNull(response.getBody());
        assertNull(response.getHeaders().getContentType());
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    }

    private void setCurrentUser(String userId, String tenantId) {
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(userId)
                .tenantId(tenantId)
                .build());
    }

    private SessionTaskEntity stubOwnedTask(String providerType) {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId(TASK_ID);
        task.setSessionId("session-1");
        task.setProviderType(providerType);
        task.setWorkerId("worker-1");
        task.setUserId(USER_ID);
        task.setTenantId(TENANT_ID);
        when(resourceAccessService.requireOwnedTask(TASK_ID, USER_ID, TENANT_ID)).thenReturn(task);
        return task;
    }

    private CodexTaskEntity sdkTask() {
        CodexTaskEntity task = baseTask();
        task.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        task.setRuntimeId("legacy-sdk:worker-1");
        task.setRuntimeRevision(1);
        task.setRuntimeType("SDK_EXEC");
        task.setCodexThreadId("thread-1");
        return task;
    }

    private CodexTaskEntity appServerTask() {
        CodexTaskEntity task = baseTask();
        task.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        task.setRuntimeId("app-main");
        task.setRuntimeRevision(3);
        task.setRuntimeType(CodexRuntimeType.APP_SERVER.name());
        task.setRuntimeInstanceId("instance-a");
        task.setWorkerTaskId("worker-task-1");
        return task;
    }

    private CodexTaskEntity baseTask() {
        CodexTaskEntity task = new CodexTaskEntity();
        task.setTaskId(TASK_ID);
        task.setSessionId("session-1");
        task.setDirectoryId("dir-1");
        task.setWorkerId("worker-1");
        task.setUserId(USER_ID);
        task.setTenantId(TENANT_ID);
        task.setCwd("D:/repo");
        task.setStatus("COMPLETED");
        return task;
    }

    private CodexRuntimeBinding appServerBinding() {
        return CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeRevision(3)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .endpointUrl("http://127.0.0.1:3062")
                .authToken("runtime-secret")
                .instanceId("instance-a")
                .routingEpoch(7L)
                .build();
    }
}
