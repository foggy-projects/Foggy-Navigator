package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.NativeSubtaskSnapshotResponseDTO;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.model.form.TaskCancelForm;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.session.service.TaskDispatchRequest;
import com.foggy.navigator.session.service.NativeSubtaskQueryService;
import com.foggy.navigator.session.service.TrustedNavigatorTaskTerminationCommandAdapter;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskControllerTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String REQUEST_ID =
            "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private TaskDispatchFacade taskDispatchFacade;
    @Mock
    private AgentSubmitPipeline agentSubmitPipeline;
    @Mock
    private NativeSubtaskQueryService nativeSubtaskQueryService;
    @Mock
    private TrustedNavigatorTaskTerminationCommandAdapter taskTerminationCommandAdapter;

    private TaskController controller;

    @BeforeEach
    void setUp() {
        controller = new TaskController(
                taskDispatchFacade,
                agentSubmitPipeline,
                nativeSubtaskQueryService,
                taskTerminationCommandAdapter);
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createTask_passesOptionalClientRequestIdWithoutPayloadProjection() {
        String clientRequestId = "550e8400-e29b-41d4-a716-446655440000";
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .workerId("worker-1")
                .prompt("hello")
                .metadata(Map.of("safe", "value"))
                .build();

        DispatchTaskDTO dto = DispatchTaskDTO.builder()
                .taskId("task-1")
                .agentId("agent-1")
                .providerType("claude-worker")
                .build();

        when(agentSubmitPipeline.submit(any(AgentTaskSubmitRequest.class)))
                .thenReturn(AgentTaskSubmitResult.of(null, dto));

        RX<DispatchTaskDTO> result = controller.createTask(request, clientRequestId);

        assertNotNull(result.getData());
        assertEquals("task-1", result.getData().getTaskId());
        ArgumentCaptor<AgentTaskSubmitRequest> submitCaptor =
                ArgumentCaptor.forClass(AgentTaskSubmitRequest.class);
        verify(agentSubmitPipeline).submit(submitCaptor.capture());
        AgentTaskSubmitRequest submitted = submitCaptor.getValue();
        assertEquals("session-1", submitted.getSessionId());
        assertEquals("worker-1", submitted.getWorkerId());
        assertEquals("hello", submitted.getPrompt());
        assertNotNull(submitted.getResolveContext());
        assertEquals("UI", submitted.getResolveContext().getRequestSource());
        assertEquals(clientRequestId, submitted.getClientRequestId());
        assertEquals(Map.of("safe", "value"), submitted.getMetadata());
        assertFalse(submitted.getMetadata().containsKey("clientRequestId"));
        assertFalse(submitted.getMetadata().containsValue(clientRequestId));
        verify(taskDispatchFacade, never()).createTask(any(), any());
    }

    @Test
    void createTask_withoutClientRequestIdLeavesCarrierAbsentForServerMint() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .prompt("hello")
                .build();
        DispatchTaskDTO dto = DispatchTaskDTO.builder().taskId("task-1").build();
        when(agentSubmitPipeline.submit(any(AgentTaskSubmitRequest.class)))
                .thenReturn(AgentTaskSubmitResult.of(null, dto));

        controller.createTask(request, null);

        verify(agentSubmitPipeline).submit(argThat(submitRequest ->
                submitRequest.getClientRequestId() == null
                        && submitRequest.getResolveContext() != null
                        && "UI".equals(submitRequest.getResolveContext().getRequestSource())));
    }

    @Test
    void getTask_found() {
        DispatchTaskDTO dto = DispatchTaskDTO.builder()
                .taskId("task-1")
                .status("RUNNING")
                .build();

        when(taskDispatchFacade.getTask(eq("task-1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(dto));

        RX<DispatchTaskDTO> result = controller.getTask("task-1");

        assertNotNull(result.getData());
        assertEquals("task-1", result.getData().getTaskId());
    }

    @Test
    void getTask_notFound() {
        when(taskDispatchFacade.getTask(eq("task-999"), any(AgentResolveContext.class)))
                .thenReturn(Optional.empty());

        RX<DispatchTaskDTO> result = controller.getTask("task-999");

        assertNull(result.getData());
    }

    @Test
    void getNativeSubtasks_returnsSnapshotAfterOwnershipCheck() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-1")
                .sessionId("session-1")
                .providerType("codex-worker")
                .build();
        NativeSubtaskSnapshotResponseDTO snapshot = NativeSubtaskSnapshotResponseDTO.builder()
                .contractVersion(1)
                .taskId("task-1")
                .subtasks(List.of())
                .build();
        when(taskDispatchFacade.getTask(eq("task-1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(task));
        when(nativeSubtaskQueryService.getSnapshot(task)).thenReturn(snapshot);

        RX<NativeSubtaskSnapshotResponseDTO> result = controller.getNativeSubtasks("task-1");

        assertSame(snapshot, result.getData());
        verify(nativeSubtaskQueryService).getSnapshot(task);
    }

    @Test
    void getNativeSubtasks_rejectsUnknownOrUnownedTask() {
        when(taskDispatchFacade.getTask(eq("task-other"), any(AgentResolveContext.class)))
                .thenReturn(Optional.empty());

        RX<NativeSubtaskSnapshotResponseDTO> result = controller.getNativeSubtasks("task-other");

        assertNull(result.getData());
        verifyNoInteractions(nativeSubtaskQueryService);
    }

    @Test
    void listTasks_withSessionId() {
        List<DispatchTaskDTO> tasks = List.of(
                DispatchTaskDTO.builder().taskId("task-1").build()
        );
        when(taskDispatchFacade.listTasksBySession(eq("session-1"), any(AgentResolveContext.class)))
                .thenReturn(tasks);

        RX<List<DispatchTaskDTO>> result = controller.listTasks("session-1");

        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        verify(taskDispatchFacade).listTasksBySession(eq("session-1"), argThat(context ->
                USER_ID.equals(context.getUserId()) && TENANT_ID.equals(context.getTenantId())));
        verify(taskDispatchFacade, never()).listActiveTasks(anyString());
    }

    @Test
    void listTasks_withoutSessionId_returnsActive() {
        List<DispatchTaskDTO> tasks = List.of(
                DispatchTaskDTO.builder().taskId("task-active-1").build()
        );
        when(taskDispatchFacade.listActiveTasks(USER_ID)).thenReturn(tasks);

        RX<List<DispatchTaskDTO>> result = controller.listTasks(null);

        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        assertEquals("task-active-1", result.getData().get(0).getTaskId());
        verify(taskDispatchFacade).listActiveTasks(USER_ID);
        verify(taskDispatchFacade, never()).listTasksBySession(anyString(), any(AgentResolveContext.class));
    }

    @Test
    void cancelTask_declaresOptionalClientRequestIdHeader() throws Exception {
        RequestHeader header = TaskController.class
                .getMethod("cancelTask", String.class, TaskCancelForm.class, String.class)
                .getParameters()[2]
                .getAnnotation(RequestHeader.class);

        assertNotNull(header);
        assertEquals("X-Navigator-Client-Request-Id", header.value());
        assertFalse(header.required());
    }

    @Test
    void cancelTask_passesRequestIdsAndNormalIntentToTrustedAdapter() {
        when(taskTerminationCommandAdapter.terminateUiTask(
                eq("task-1"), eq(false), any()))
                .thenReturn(acceptedTermination());

        RX<String> absent = controller.cancelTask("task-1", null, null);
        RX<String> blank = controller.cancelTask("task-1", null, "  ");
        RX<String> explicit = controller.cancelTask("task-1", null, REQUEST_ID);

        assertEquals("Cancellation request accepted", absent.getData());
        assertEquals("Cancellation request accepted", blank.getData());
        assertEquals("Cancellation request accepted", explicit.getData());
        verify(taskTerminationCommandAdapter).terminateUiTask("task-1", false, null);
        verify(taskTerminationCommandAdapter).terminateUiTask("task-1", false, "  ");
        verify(taskTerminationCommandAdapter).terminateUiTask(
                "task-1", false, REQUEST_ID);
        verify(taskDispatchFacade, never()).getTask(eq("task-1"), any());
        verify(taskDispatchFacade, never()).cancelTask(
                anyString(), any(), any(), anyBoolean());
    }

    @Test
    void cancelTask_passesForceAndKeepsExistingSuccessWording() {
        TaskCancelForm form = new TaskCancelForm();
        form.setForce(true);
        when(taskTerminationCommandAdapter.terminateUiTask(
                "task-1", true, REQUEST_ID)).thenReturn(acceptedTermination());

        RX<String> result = controller.cancelTask("task-1", form, REQUEST_ID);

        assertEquals("Force cancellation completed", result.getData());
        verify(taskTerminationCommandAdapter).terminateUiTask(
                "task-1", true, REQUEST_ID);
        verify(taskDispatchFacade, never()).cancelTask(
                anyString(), any(), any(), anyBoolean());
    }

    @Test
    void cancelTask_mapsCanonicalTerminalWithoutControllerTaskRead() {
        when(taskTerminationCommandAdapter.terminateUiTask(
                "task-1", false, REQUEST_ID))
                .thenReturn(new TrustedNavigatorTaskTerminationCommandAdapter.TerminationResult(
                        "TASK_ALREADY_TERMINAL_ABORTED", "ABORTED"));

        RX<String> result = controller.cancelTask("task-1", null, REQUEST_ID);

        assertEquals("Task already in terminal state: ABORTED", result.getData());
        verify(taskTerminationCommandAdapter).terminateUiTask(
                "task-1", false, REQUEST_ID);
        verify(taskDispatchFacade, never()).getTask(eq("task-1"), any());
        verify(taskDispatchFacade, never()).cancelTask(
                anyString(), any(), any(), anyBoolean());
    }

    @Test
    void cancelTask_distinguishesNotFoundInvalidUnsupportedAndStableFailures() {
        when(taskTerminationCommandAdapter.terminateUiTask(
                "task-1", false, REQUEST_ID))
                .thenThrow(
                        new IllegalArgumentException("Task not found: task-1"),
                        new IllegalArgumentException(
                                "clientRequestId must be a canonical UUID"),
                        new UnsupportedOperationException("provider detail"),
                        new IllegalStateException("TERMINATION_EFFECT_AMBIGUOUS"),
                        new IllegalStateException("unsafe provider detail"));

        RX<String> notFound = controller.cancelTask("task-1", null, REQUEST_ID);
        RX<String> invalidId = controller.cancelTask("task-1", null, REQUEST_ID);
        RX<String> unsupported = controller.cancelTask("task-1", null, REQUEST_ID);
        RX<String> ambiguous = controller.cancelTask("task-1", null, REQUEST_ID);
        RX<String> unsafe = controller.cancelTask("task-1", null, REQUEST_ID);

        assertEquals("Task not found: task-1", notFound.getMsg());
        assertEquals("clientRequestId must be a canonical UUID", invalidId.getMsg());
        assertEquals("TERMINATION_REQUEST_NOT_SUPPORTED", unsupported.getMsg());
        assertEquals("TERMINATION_EFFECT_AMBIGUOUS", ambiguous.getMsg());
        assertEquals("TERMINATION_REQUEST_FAILED", unsafe.getMsg());
        assertNull(ambiguous.getData());
        verify(taskTerminationCommandAdapter, times(5)).terminateUiTask(
                "task-1", false, REQUEST_ID);
    }

    @Test
    void cancelTask_pessimisticFailureDoesNotRereadRetryOrClaimTerminal() {
        when(taskTerminationCommandAdapter.terminateUiTask(
                "task-1", false, REQUEST_ID))
                .thenThrow(new org.springframework.dao.PessimisticLockingFailureException(
                        "Deadlock"));

        RX<String> result = controller.cancelTask("task-1", null, REQUEST_ID);

        assertNull(result.getData());
        assertEquals("Failed to cancel task due to concurrent update, please retry",
                result.getMsg());
        verify(taskTerminationCommandAdapter).terminateUiTask(
                "task-1", false, REQUEST_ID);
        verifyNoInteractions(taskDispatchFacade);
    }

    @Test
    void cancelTask_preservesSecurityFailure() {
        SecurityException denied = new SecurityException("Resource access denied");
        when(taskTerminationCommandAdapter.terminateUiTask(
                "task-1", false, REQUEST_ID)).thenThrow(denied);

        assertSame(denied, assertThrows(SecurityException.class,
                () -> controller.cancelTask("task-1", null, REQUEST_ID)));
        verifyNoInteractions(taskDispatchFacade);
    }

    @Test
    void respondToTask_success() {
        Map<String, Object> body = Map.of("decision", "approve");

        RX<String> result = controller.respondToTask("task-1", body);

        assertNotNull(result.getData());
        verify(taskDispatchFacade).respondToTask(eq("task-1"), argThat(context ->
                USER_ID.equals(context.getUserId()) && TENANT_ID.equals(context.getTenantId())), eq(body));
    }

    @Test
    void resumeTask_success() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-1")
                .prompt("continue")
                .build();

        DispatchTaskDTO dto = DispatchTaskDTO.builder()
                .taskId("task-resumed")
                .providerType("claude-worker")
                .build();

        when(taskDispatchFacade.resumeTask(eq(request), any(AgentResolveContext.class))).thenReturn(dto);

        RX<DispatchTaskDTO> result = controller.resumeTask(request);

        assertNotNull(result.getData());
        assertEquals("task-resumed", result.getData().getTaskId());
    }

    @Test
    void resumeTask_providerNotFound_returnsFailA() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-unknown")
                .directoryId("dir-unknown")
                .prompt("continue")
                .build();

        when(taskDispatchFacade.resumeTask(eq(request), any(AgentResolveContext.class)))
                .thenThrow(new IllegalArgumentException("No provider found for resume request"));

        RX<DispatchTaskDTO> result = controller.resumeTask(request);

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("No provider found"));
    }

    @Test
    void respondToTask_unsupported() {
        Map<String, Object> body = Map.of("decision", "approve");
        doThrow(new UnsupportedOperationException("respond not supported by codex-worker"))
                .when(taskDispatchFacade).respondToTask(eq("task-1"), any(AgentResolveContext.class), eq(body));

        RX<String> result = controller.respondToTask("task-1", body);

        assertNull(result.getData());
    }

    @Test
    void respondToTask_invalidResponse_returnsFailA() {
        Map<String, Object> body = Map.of("permissionId", "request-1", "answers", Map.of());
        doThrow(new IllegalArgumentException("invalid response"))
                .when(taskDispatchFacade).respondToTask(eq("task-1"), any(AgentResolveContext.class), eq(body));

        RX<String> result = controller.respondToTask("task-1", body);

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("invalid response"));
    }

    @Test
    void respondToTask_staleRequest_returnsFailB() {
        Map<String, Object> body = Map.of("permissionId", "stale", "answers", Map.of());
        doThrow(new IllegalStateException("CODEX_USER_INPUT_REQUEST_MISMATCH"))
                .when(taskDispatchFacade).respondToTask(eq("task-1"), any(AgentResolveContext.class), eq(body));

        RX<String> result = controller.respondToTask("task-1", body);

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("CODEX_USER_INPUT_REQUEST_MISMATCH"));
    }

    @Test
    void rewindTask_returnsProviderPayload() {
        Map<String, Object> body = Map.of("mode", "conversation_fork", "turnIndex", 2);
        Map<String, Object> payload = Map.of(
                "status", "rewound",
                "taskId", "task-1",
                "userPrompt", "Generate a file"
        );
        when(taskDispatchFacade.rewindTask(eq("task-1"), any(AgentResolveContext.class), eq(body)))
                .thenReturn(payload);

        RX<?> result = controller.rewindTask("task-1", body);

        assertEquals(payload, result.getData());
        verify(taskDispatchFacade).rewindTask(eq("task-1"), argThat(context ->
                USER_ID.equals(context.getUserId()) && TENANT_ID.equals(context.getTenantId())), eq(body));
    }

    @Test
    void rewindTask_validationFailureReturnsFailB() {
        Map<String, Object> body = Map.of("mode", "conversation_fork", "turnIndex", 2);
        when(taskDispatchFacade.rewindTask(eq("task-1"), any(AgentResolveContext.class), eq(body)))
                .thenThrow(new IllegalStateException("Cannot rewind a running task"));

        RX<?> result = controller.rewindTask("task-1", body);

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("Cannot rewind a running task"));
    }

    @Test
    void listWorkerSessions_delegatesWithCurrentUser() {
        List<Map<String, Object>> sessions = List.of(
                Map.of("sessionId", "worker-session-1", "source", "session-store")
        );
        when(taskDispatchFacade.listWorkerSessions("lg-worker-1", USER_ID)).thenReturn(sessions);

        RX<List<Map<String, Object>>> result = controller.listWorkerSessions("lg-worker-1");

        assertEquals(sessions, result.getData());
        verify(taskDispatchFacade).listWorkerSessions("lg-worker-1", USER_ID);
    }

    @Test
    void getWorkerSessionMessageCount_delegatesWithCurrentUser() {
        Map<String, Object> count = Map.of("total", 2, "user_count", 1, "assistant_count", 1);
        when(taskDispatchFacade.getWorkerSessionMessageCount("lg-worker-1", "worker-session-1", USER_ID))
                .thenReturn(count);

        RX<Map<String, Object>> result =
                controller.getWorkerSessionMessageCount("lg-worker-1", "worker-session-1");

        assertEquals(count, result.getData());
        verify(taskDispatchFacade).getWorkerSessionMessageCount("lg-worker-1", "worker-session-1", USER_ID);
    }

    @Test
    void getWorkerSessionMessages_delegatesWithCurrentUserAndPaging() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "assistant", "content", "ok", "taskId", "lgt-task-1")
        );
        when(taskDispatchFacade.getWorkerSessionMessages("lg-worker-1", "worker-session-1", USER_ID, 10, 20))
                .thenReturn(messages);

        RX<List<Map<String, Object>>> result =
                controller.getWorkerSessionMessages("lg-worker-1", "worker-session-1", 10, 20);

        assertEquals(messages, result.getData());
        verify(taskDispatchFacade).getWorkerSessionMessages("lg-worker-1", "worker-session-1", USER_ID, 10, 20);
    }

    @Test
    void syncWorkerSessions_delegatesWithCurrentUserAndTenant() {
        Map<String, Object> syncResult = Map.of("synced", 0, "total", 1, "source", "session-store");
        when(taskDispatchFacade.syncWorkerSessions("lg-worker-1", USER_ID, TENANT_ID)).thenReturn(syncResult);

        RX<Map<String, Object>> result = controller.syncWorkerSessions("lg-worker-1");

        assertEquals(syncResult, result.getData());
        verify(taskDispatchFacade).syncWorkerSessions("lg-worker-1", USER_ID, TENANT_ID);
    }

    @Test
    void syncWorkerSessions_failureReturnsFailB() {
        when(taskDispatchFacade.syncWorkerSessions("lg-worker-1", USER_ID, TENANT_ID))
                .thenThrow(new IllegalArgumentException("Worker not found"));

        RX<Map<String, Object>> result = controller.syncWorkerSessions("lg-worker-1");

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("Worker not found"));
    }

    private static TrustedNavigatorTaskTerminationCommandAdapter.TerminationResult
    acceptedTermination() {
        return new TrustedNavigatorTaskTerminationCommandAdapter.TerminationResult(
                "TERMINATION_REQUEST_ACCEPTED", null);
    }
}
