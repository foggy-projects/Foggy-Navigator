package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionListResponse;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionMessagesResponse;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskDiagnosticsDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskEvidenceDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskMessagesResponse;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.session.service.OpenApiSessionQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OpenApiDurableTaskSessionQueryFacadeTest {

    @Test
    void declaresReadOnlyTransactionAndOnlyFrozenDependencies() {
        Transactional transactional = OpenApiDurableTaskSessionQueryFacade.class
                .getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertTrue(transactional.readOnly());

        assertEquals(1, OpenApiDurableTaskSessionQueryFacade.class.getDeclaredConstructors().length);
        assertEquals(
                List.of(
                        OpenApiSessionQueryService.class,
                        OpenApiSessionProjectionMapper.class,
                        ObjectMapper.class),
                Arrays.asList(OpenApiDurableTaskSessionQueryFacade.class
                        .getDeclaredConstructors()[0]
                        .getParameterTypes()));

        Set<Class<?>> fieldTypes = Arrays.stream(
                        OpenApiDurableTaskSessionQueryFacade.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                OpenApiSessionQueryService.class,
                OpenApiSessionProjectionMapper.class,
                ObjectMapper.class,
                OpenApiTaskProjectionMapper.class), fieldTypes);

        assertEquals(
                List.of(String.class, String.class, String.class, int.class, boolean.class),
                Arrays.asList(methodParameterTypes(
                        "loadSessionMessages",
                        String.class,
                        String.class,
                        String.class,
                        int.class,
                        boolean.class)));
    }

    @Test
    void loadsOwnedDiagnosticsWithPreloadedFactsWithoutMutatingTask() {
        OpenApiSessionQueryService queryService = mock(OpenApiSessionQueryService.class);
        OpenApiDurableTaskSessionQueryFacade facade = facade(queryService);
        SessionTaskEntity task = task("task-1", "tenant-1", "agent-1", "RUNNING");
        String originalState = """
                {"workerStartedAt":"2026-08-03T10:01:00","workerBackend":"claude-worker"}
                """;
        task.setTaskStateJson(originalState);
        task.setProviderTaskId("provider-task-1");
        task.setLastAckedSeq(7);
        SessionMessageEntity latest = message(
                "message-latest", "session-1", "task-1", "ASSISTANT", "working", "{}");
        latest.setCreatedAt(LocalDateTime.of(2026, 8, 3, 10, 3));

        when(queryService.findTask("task-1")).thenReturn(Optional.of(task));
        when(queryService.resolveContextId("session-1")).thenReturn(Optional.of("ctx-1"));
        when(queryService.findLatestTaskMessage("task-1")).thenReturn(Optional.of(latest));
        when(queryService.countTaskMessages("task-1")).thenReturn(2L);

        OpenTaskDiagnosticsDTO diagnostics = facade.loadTaskDiagnostics(
                "task-1", "tenant-1", "agent-1");

        assertEquals("task-1", diagnostics.getTaskId());
        assertEquals("ctx-1", diagnostics.getContextId());
        assertEquals("RUNNING", diagnostics.getStatus());
        assertEquals(latest.getCreatedAt(), diagnostics.getLastObservedAt());
        assertEquals(2L, diagnostics.getMessagesCount());
        assertEquals("provider-task-1", diagnostics.getProviderTaskId());
        assertEquals(7L, diagnostics.getLastAckedSeq());
        assertEquals(originalState, task.getTaskStateJson());
        assertEquals("RUNNING", task.getStatus());

        InOrder order = inOrder(queryService);
        order.verify(queryService).findTask("task-1");
        order.verify(queryService).resolveContextId("session-1");
        order.verify(queryService).findLatestTaskMessage("task-1");
        order.verify(queryService).countTaskMessages("task-1");
        verifyNeverWrites(queryService);
    }

    @Test
    void blankDurableSessionIdStillUsesExistingContextLookupContract() {
        OpenApiSessionQueryService queryService = mock(OpenApiSessionQueryService.class);
        OpenApiDurableTaskSessionQueryFacade facade = facade(queryService);
        SessionTaskEntity task = task("task-blank-session", "tenant-1", "agent-1", "RUNNING");
        task.setSessionId(" ");

        when(queryService.findTask("task-blank-session")).thenReturn(Optional.of(task));
        when(queryService.resolveContextId(" ")).thenReturn(Optional.empty());
        when(queryService.findLatestTaskMessage("task-blank-session")).thenReturn(Optional.empty());
        when(queryService.countTaskMessages("task-blank-session")).thenReturn(0L);

        OpenTaskDiagnosticsDTO diagnostics = facade.loadTaskDiagnostics(
                "task-blank-session", "tenant-1", "agent-1");

        assertNull(diagnostics.getContextId());
        verify(queryService).resolveContextId(" ");
        verifyNeverWrites(queryService);
    }

    @Test
    void rejectsMissingAndForeignTasksWithoutLeakingOrRunningLaterQueries() {
        OpenApiSessionQueryService missingQuery = mock(OpenApiSessionQueryService.class);
        when(missingQuery.findTask("task-missing")).thenReturn(Optional.empty());
        assertTaskNotFound(() -> facade(missingQuery).loadTaskDiagnostics(
                "task-missing", "tenant-1", "agent-1"), "task-missing");
        verify(missingQuery).findTask("task-missing");
        verifyNoMoreInteractions(missingQuery);

        OpenApiSessionQueryService foreignTenantQuery = mock(OpenApiSessionQueryService.class);
        SessionTaskEntity foreignTenant = task(
                "task-foreign-tenant", "tenant-other", "agent-1", "RUNNING");
        when(foreignTenantQuery.findTask("task-foreign-tenant"))
                .thenReturn(Optional.of(foreignTenant));
        assertTaskNotFound(() -> facade(foreignTenantQuery).loadTaskEvidence(
                "task-foreign-tenant", "tenant-1", "agent-1"), "task-foreign-tenant");
        verify(foreignTenantQuery).findTask("task-foreign-tenant");
        verifyNoMoreInteractions(foreignTenantQuery);

        OpenApiSessionQueryService foreignAgentQuery = mock(OpenApiSessionQueryService.class);
        SessionTaskEntity foreignAgent = task(
                "task-foreign-agent", "tenant-1", "agent-other", "RUNNING");
        when(foreignAgentQuery.findTask("task-foreign-agent"))
                .thenReturn(Optional.of(foreignAgent));
        assertTaskNotFound(() -> facade(foreignAgentQuery).loadTaskMessages(
                "task-foreign-agent", "tenant-1", "agent-1", null, 50, false),
                "task-foreign-agent");
        verify(foreignAgentQuery).findTask("task-foreign-agent");
        verifyNoMoreInteractions(foreignAgentQuery);
    }

    @Test
    void loadsLatestTwoHundredEvidenceInDurableOrderWithoutMutatingInputs() {
        OpenApiSessionQueryService queryService = mock(OpenApiSessionQueryService.class);
        OpenApiDurableTaskSessionQueryFacade facade = facade(queryService);
        SessionTaskEntity task = task("task-1", "tenant-1", "agent-1", "COMPLETED");
        String taskState = """
                {"reportRefs":["frame-report://task/frame-1"]}
                """;
        task.setTaskStateJson(taskState);
        SessionMessageEntity first = message(
                "message-1",
                "session-1",
                "task-1",
                "ASSISTANT",
                "first",
                "{\"type\":\"TEXT\",\"artifactRefs\":[\"artifact://first\"]}");
        SessionMessageEntity last = message(
                "message-2",
                "session-1",
                "task-1",
                "ASSISTANT",
                "final token=raw-secret",
                "{\"type\":\"TASK_COMPLETED\",\"reportRefs\":[\"report://last\"]}");
        String firstMetadata = first.getMetadata();
        String lastMetadata = last.getMetadata();

        when(queryService.findTask("task-1")).thenReturn(Optional.of(task));
        when(queryService.resolveContextId("session-1")).thenReturn(Optional.of("ctx-1"));
        when(queryService.getLatestTaskMessages("task-1", 200))
                .thenReturn(List.of(first, last));

        OpenTaskEvidenceDTO evidence = facade.loadTaskEvidence(
                "task-1", "tenant-1", "agent-1");

        assertEquals("message-2", evidence.getFinalAnswer().getMessageId());
        assertEquals(List.of("frame-report://task/frame-1", "report://last"),
                evidence.getReportRefs().stream().map(ref -> ref.getRef()).toList());
        assertEquals(List.of("artifact://first"),
                evidence.getArtifactRefs().stream().map(ref -> ref.getPath()).toList());
        assertFalse(evidence.getFinalAnswer().getSummary().contains("raw-secret"));
        assertEquals(taskState, task.getTaskStateJson());
        assertEquals(firstMetadata, first.getMetadata());
        assertEquals(lastMetadata, last.getMetadata());
        verify(queryService).getLatestTaskMessages("task-1", 200);
        verifyNeverWrites(queryService);
    }

    @Test
    void taskMessagesKeepRawNullStatusAndApplyVisibilityAfterCursorPage() {
        OpenApiSessionQueryService queryService = mock(OpenApiSessionQueryService.class);
        OpenApiDurableTaskSessionQueryFacade facade = facade(queryService);
        SessionTaskEntity task = task("task-1", "tenant-1", "agent-1", null);
        task.setTaskStateJson("{malformed");
        SessionMessageEntity hidden = message(
                "message-hidden", "session-1", "task-1", "TOOL", "tool payload", "{malformed");
        SessionMessageEntity visible = message(
                "message-visible", "session-1", "task-1", "ASSISTANT", "visible", "{malformed");
        SessionMessageEntity extra = message(
                "message-extra", "session-1", "task-1", "ASSISTANT", "next page", "{}");
        String visibleMetadata = visible.getMetadata();

        when(queryService.findTask("task-1")).thenReturn(Optional.of(task));
        when(queryService.resolveContextId("session-1")).thenReturn(Optional.of("ctx-1"));
        when(queryService.getTaskMessages("task-1", "invalid-cursor", 2))
                .thenReturn(List.of(hidden, visible, extra));

        OpenTaskMessagesResponse response = facade.loadTaskMessages(
                "task-1", "tenant-1", "agent-1", "invalid-cursor", 2, false);

        assertEquals("UNKNOWN", response.getStatus());
        assertFalse(response.isTerminal());
        assertNull(response.getTerminalStatus());
        assertTrue(response.isHasMore());
        assertEquals("message-visible", response.getNextCursor());
        assertEquals(List.of("message-visible"), response.getMessages().stream()
                .map(message -> message.getMessageId())
                .toList());
        assertNull(response.getMessages().get(0).getStatus());
        assertFalse(response.getMessages().get(0).getTerminal());
        assertNull(response.getMessages().get(0).getMetadata());
        assertEquals("{malformed", task.getTaskStateJson());
        assertEquals(visibleMetadata, visible.getMetadata());
        verify(queryService).getTaskMessages("task-1", "invalid-cursor", 2);
        verifyNeverWrites(queryService);
    }

    @Test
    void failedTaskWithoutVisiblePageProducesSyntheticErrorWithoutExtraLimitIncrement() {
        OpenApiSessionQueryService queryService = mock(OpenApiSessionQueryService.class);
        OpenApiDurableTaskSessionQueryFacade facade = facade(queryService);
        SessionTaskEntity task = task("task-failed", "tenant-1", "agent-1", "FAILED");
        task.setErrorMessage("worker stream timeout");
        task.setUpdatedAt(LocalDateTime.of(2026, 8, 3, 10, 5));

        when(queryService.findTask("task-failed")).thenReturn(Optional.of(task));
        when(queryService.resolveContextId("session-1")).thenReturn(Optional.of("ctx-1"));
        when(queryService.getTaskMessages("task-failed", null, 50)).thenReturn(List.of());

        OpenTaskMessagesResponse response = facade.loadTaskMessages(
                "task-failed", "tenant-1", "agent-1", null, 50, false);

        assertEquals("FAILED", response.getStatus());
        assertTrue(response.isTerminal());
        assertEquals("FAILED", response.getTerminalStatus());
        assertFalse(response.isHasMore());
        assertEquals("task-error:task-failed", response.getNextCursor());
        assertEquals(1, response.getMessages().size());
        assertEquals("task-error:task-failed", response.getMessages().get(0).getMessageId());
        assertEquals("ERROR", response.getMessages().get(0).getType());
        verify(queryService).getTaskMessages("task-failed", null, 50);
        verifyNeverWrites(queryService);
    }

    @Test
    void sessionListClampsLimitAndUsesExactlyTwoPageBatches() {
        OpenApiSessionQueryService queryService = mock(OpenApiSessionQueryService.class);
        OpenApiDurableTaskSessionQueryFacade facade = facade(queryService);
        AgentConversationContextEntity first = context("ctx-1", "session-1");
        first.setClientContextJson("{malformed");
        AgentConversationContextEntity extra = context("ctx-extra", "session-extra");
        String originalContextJson = first.getClientContextJson();

        when(queryService.listSessions("owner-1", "agent-1", "invalid-cursor", 1))
                .thenReturn(List.of(first, extra));
        when(queryService.batchFindLatestTaskIds(List.of("session-1")))
                .thenReturn(Map.of("session-1", "task-1"));
        when(queryService.batchFindFirstUserMessageContents(List.of("session-1")))
                .thenReturn(Map.of("session-1", "first prompt"));

        OpenSessionListResponse response = facade.listSessions(
                "owner-1", "agent-1", 0, "invalid-cursor");

        assertTrue(response.isHasMore());
        assertEquals("ctx-1", response.getNextCursor());
        assertEquals(1, response.getSessions().size());
        assertEquals("task-1", response.getSessions().get(0).getLatestTaskId());
        assertEquals("first prompt", response.getSessions().get(0).getTitle());
        assertNull(response.getSessions().get(0).getClientContext());
        assertEquals(originalContextJson, first.getClientContextJson());
        verify(queryService).listSessions("owner-1", "agent-1", "invalid-cursor", 1);
        verify(queryService).batchFindLatestTaskIds(List.of("session-1"));
        verify(queryService).batchFindFirstUserMessageContents(List.of("session-1"));
        verifyNeverWrites(queryService);
    }

    @Test
    void sessionMessagesUseOwnerContextAndOneDistinctStatusBatch() {
        OpenApiSessionQueryService queryService = mock(OpenApiSessionQueryService.class);
        OpenApiDurableTaskSessionQueryFacade facade = facade(queryService);
        SessionMessageEntity first = message(
                "message-1", "session-1", "task-1", "USER", "first", "{}");
        SessionMessageEntity second = message(
                "message-2", "session-1", "task-1", "ASSISTANT", "second", "{malformed");
        SessionMessageEntity third = message(
                "message-3", "session-1", "task-2", "ASSISTANT", "third", "{}");
        SessionMessageEntity extra = message(
                "message-extra", "session-1", "task-extra", "ASSISTANT", "extra", "{}");
        String malformedMetadata = second.getMetadata();

        when(queryService.resolveSessionId("ctx-1", "owner-1"))
                .thenReturn(Optional.of("session-1"));
        when(queryService.getSessionMessages("session-1", "cursor-1", 3))
                .thenReturn(List.of(first, second, third, extra));
        when(queryService.batchFindTaskStatuses(List.of("task-1", "task-2")))
                .thenReturn(Map.of("task-1", "COMPLETED", "task-2", "ABORTED"));

        OpenSessionMessagesResponse response = facade.loadSessionMessages(
                "ctx-1", "owner-1", "cursor-1", 3, false);

        assertTrue(response.isHasMore());
        assertEquals("message-3", response.getNextCursor());
        assertEquals(List.of("message-1", "message-2", "message-3"),
                response.getMessages().stream().map(message -> message.getMessageId()).toList());
        assertEquals(List.of("COMPLETED", "COMPLETED", "CANCELLED"),
                response.getMessages().stream().map(message -> message.getStatus()).toList());
        assertFalse(response.getMessages().get(1).getTerminal());
        assertNull(response.getMessages().get(1).getMetadata());
        assertEquals(malformedMetadata, second.getMetadata());
        verify(queryService).resolveSessionId("ctx-1", "owner-1");
        verify(queryService).getSessionMessages("session-1", "cursor-1", 3);
        verify(queryService).batchFindTaskStatuses(List.of("task-1", "task-2"));
        verifyNeverWrites(queryService);

        OpenApiSessionQueryService invalidContextQuery = mock(OpenApiSessionQueryService.class);
        when(invalidContextQuery.resolveSessionId("ctx-missing", "owner-1"))
                .thenReturn(Optional.empty());
        RuntimeException error = assertThrows(RuntimeException.class, () -> facade(invalidContextQuery)
                .loadSessionMessages("ctx-missing", "owner-1", null, 50, false));
        assertTrue(error.getMessage().contains("Context not found: ctx-missing"));
        verify(invalidContextQuery).resolveSessionId("ctx-missing", "owner-1");
        verifyNoMoreInteractions(invalidContextQuery);
    }

    private OpenApiDurableTaskSessionQueryFacade facade(OpenApiSessionQueryService queryService) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new OpenApiDurableTaskSessionQueryFacade(
                queryService,
                new OpenApiSessionProjectionMapper(objectMapper),
                objectMapper);
    }

    private SessionTaskEntity task(
            String taskId,
            String tenantId,
            String agentId,
            String status) {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId("session-1");
        task.setTenantId(tenantId);
        task.setAgentId(agentId);
        task.setUserId("owner-1");
        task.setProviderType("CLAUDE_WORKER");
        task.setStatus(status);
        task.setCreatedAt(LocalDateTime.of(2026, 8, 3, 10, 0));
        task.setUpdatedAt(LocalDateTime.of(2026, 8, 3, 10, 2));
        return task;
    }

    private SessionMessageEntity message(
            String id,
            String sessionId,
            String taskId,
            String role,
            String content,
            String metadata) {
        SessionMessageEntity message = new SessionMessageEntity();
        message.setId(id);
        message.setSessionId(sessionId);
        message.setTaskId(taskId);
        message.setRole(role);
        message.setContent(content);
        message.setMetadata(metadata);
        message.setCreatedAt(LocalDateTime.of(2026, 8, 3, 10, 1));
        return message;
    }

    private AgentConversationContextEntity context(String contextId, String sessionId) {
        AgentConversationContextEntity context = new AgentConversationContextEntity();
        context.setContextId(contextId);
        context.setNavigatorSessionId(sessionId);
        context.setUserId("owner-1");
        context.setTargetAgentId("agent-1");
        context.setCreatedAt(LocalDateTime.of(2026, 8, 3, 10, 0));
        context.setLastAccessedAt(LocalDateTime.of(2026, 8, 3, 10, 2));
        return context;
    }

    private void assertTaskNotFound(Runnable invocation, String taskId) {
        RuntimeException error = assertThrows(RuntimeException.class, invocation::run);
        assertTrue(error.getMessage().contains("Task not found: " + taskId));
    }

    private void verifyNeverWrites(OpenApiSessionQueryService queryService) {
        verify(queryService, never()).updateClientContextJson(any(), any(), any(), any());
    }

    private Class<?>[] methodParameterTypes(String name, Class<?>... parameterTypes) {
        try {
            return OpenApiDurableTaskSessionQueryFacade.class
                    .getDeclaredMethod(name, parameterTypes)
                    .getParameterTypes();
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }
}
