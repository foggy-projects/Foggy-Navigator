package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
import com.foggy.navigator.langgraph.worker.repository.LanggraphApprovalRepository;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for LanggraphTaskService task lifecycle:
 * createTask, startTask, completeTask, failTask.
 */
class LanggraphTaskServiceTest {

    private LanggraphTaskRepository taskRepository;
    private SessionTaskRepository sessionTaskRepository;
    private SessionEntityRepository sessionEntityRepository;
    private SessionMessageRepository sessionMessageRepository;
    private LanggraphWorkerService workerService;
    private SessionManager sessionManager;
    private ApplicationEventPublisher eventPublisher;
    private LanggraphTaskService service;

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String WORKER_ID = "worker-1";
    private static final String SESSION_ID = "session-001";
    private static final String AGENT_ID = "tms-agent-v305";

    @BeforeEach
    void setUp() {
        taskRepository = mock(LanggraphTaskRepository.class);
        sessionTaskRepository = mock(SessionTaskRepository.class);
        sessionEntityRepository = mock(SessionEntityRepository.class);
        sessionMessageRepository = mock(SessionMessageRepository.class);
        LanggraphApprovalRepository approvalRepository = mock(LanggraphApprovalRepository.class);
        workerService = mock(LanggraphWorkerService.class);
        sessionManager = mock(SessionManager.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionTaskRepository.findByTaskId(anyString())).thenReturn(Optional.empty());
        when(sessionTaskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new LanggraphTaskService(
                taskRepository, approvalRepository, workerService,
                sessionManager, eventPublisher, sessionTaskRepository, sessionEntityRepository, sessionMessageRepository
        );
    }

    private CreateLanggraphTaskForm makeForm() {
        CreateLanggraphTaskForm form = new CreateLanggraphTaskForm();
        form.setAgentId(AGENT_ID);
        form.setWorkerId(WORKER_ID);
        form.setPrompt("分析异常订单");
        form.setSessionId(SESSION_ID);
        form.setModel("claude-sonnet");
        form.setModelConfigId("cfg-langgraph");
        return form;
    }

    // -- createTask ----------------------------------------------------------

    @Nested
    class CreateTask {

        @BeforeEach
        void stubSession() {
            // Session exists
            when(sessionManager.getSession(SESSION_ID)).thenReturn(mock(
                    com.foggy.navigator.agent.framework.session.Session.class));
        }

        @Test
        void persists_task_with_pending_status() {
            service.createTask(USER_ID, TENANT_ID, makeForm());

            ArgumentCaptor<LanggraphTaskEntity> captor =
                    ArgumentCaptor.forClass(LanggraphTaskEntity.class);
            verify(taskRepository).save(captor.capture());

            LanggraphTaskEntity saved = captor.getValue();
            assertEquals("PENDING", saved.getStatus());
            assertEquals(USER_ID, saved.getUserId());
            assertEquals(TENANT_ID, saved.getTenantId());
            assertEquals(WORKER_ID, saved.getWorkerId());
            assertEquals(AGENT_ID, saved.getAgentId());
            assertNotNull(saved.getTaskId());
            assertTrue(saved.getTaskId().startsWith("lgt_"));

            verify(sessionTaskRepository).save(argThat((SessionTaskEntity projection) ->
                    saved.getTaskId().equals(projection.getTaskId())
                            && SESSION_ID.equals(projection.getSessionId())
                            && WORKER_ID.equals(projection.getWorkerId())
                            && AGENT_ID.equals(projection.getAgentId())
                            && "langgraph-biz-worker".equals(projection.getProviderType())
                            && "cfg-langgraph".equals(projection.getModelConfigId())
            ));
        }

        @Test
        void publishes_worker_task_start_event() {
            service.createTask(USER_ID, TENANT_ID, makeForm());

            ArgumentCaptor<WorkerTaskStartEvent> captor =
                    ArgumentCaptor.forClass(WorkerTaskStartEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            WorkerTaskStartEvent event = captor.getValue();
            assertEquals(WORKER_ID, event.getWorkerId());
            assertEquals(USER_ID, event.getUserId());
            assertEquals("分析异常订单", event.getPrompt());
            assertEquals("langgraph-biz-worker", event.getProviderType());
            assertEquals("claude-sonnet", event.getModel());
            assertEquals("cfg-langgraph", event.getProviderConfigString("modelConfigId"));
        }

        @Test
        void creates_session_when_not_exists() {
            when(sessionManager.getSession(SESSION_ID)).thenReturn(null);
            when(sessionManager.createSession(any(SessionCreateRequest.class)))
                    .thenReturn("new-session-001");

            service.createTask(USER_ID, TENANT_ID, makeForm());

            verify(sessionManager).createSession(any(SessionCreateRequest.class));
            ArgumentCaptor<LanggraphTaskEntity> captor =
                    ArgumentCaptor.forClass(LanggraphTaskEntity.class);
            verify(taskRepository).save(captor.capture());
            assertEquals("new-session-001", captor.getValue().getSessionId());
        }

        @Test
        void forwards_context_in_event_provider_config() {
            CreateLanggraphTaskForm form = makeForm();
            form.setContext(Map.of("order_id", "ORD-001"));

            service.createTask(USER_ID, TENANT_ID, form);

            ArgumentCaptor<WorkerTaskStartEvent> captor =
                    ArgumentCaptor.forClass(WorkerTaskStartEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            @SuppressWarnings("unchecked")
            Map<String, Object> context =
                    (Map<String, Object>) captor.getValue().getProviderConfig().get("context");
            assertNotNull(context);
            assertEquals("ORD-001", context.get("order_id"));
        }
    }

    // -- Status transitions --------------------------------------------------

    @Nested
    class StatusTransitions {

        private LanggraphTaskEntity existingTask;

        @BeforeEach
        void stubExistingTask() {
            existingTask = new LanggraphTaskEntity();
            existingTask.setTaskId("lgt_existing");
            existingTask.setStatus("PENDING");
            when(taskRepository.findByTaskId("lgt_existing"))
                    .thenReturn(Optional.of(existingTask));
            when(taskRepository.findByTaskIdAndUserId("lgt_existing", USER_ID))
                    .thenReturn(Optional.of(existingTask));
        }

        @Test
        void startTask_sets_running() {
            service.startTask("lgt_existing");

            assertEquals("RUNNING", existingTask.getStatus());
            verify(taskRepository).save(existingTask);
        }

        @Test
        void completeTask_sets_completed_with_result() {
            service.completeTask("lgt_existing", "result text", "{\"key\":\"val\"}", 1234L);

            assertEquals("COMPLETED", existingTask.getStatus());
            assertEquals("result text", existingTask.getResultText());
            assertEquals("{\"key\":\"val\"}", existingTask.getStructuredOutput());
            assertEquals(1234L, existingTask.getDurationMs());
            verify(taskRepository).save(existingTask);
        }

        @Test
        void failTask_sets_failed_with_error() {
            service.failTask("lgt_existing", "connection timeout");

            assertEquals("FAILED", existingTask.getStatus());
            assertEquals("connection timeout", existingTask.getErrorMessage());
            verify(taskRepository).save(existingTask);
        }

        @Test
        void cancelTask_sets_aborted_for_active_task() {
            service.cancelTask("lgt_existing", USER_ID);

            assertEquals("ABORTED", existingTask.getStatus());
            assertEquals("Cancelled by user", existingTask.getErrorMessage());
            verify(taskRepository).save(existingTask);
        }

        @Test
        void deleteTask_deletes_completed_task() {
            existingTask.setStatus("COMPLETED");

            service.deleteTask(USER_ID, "lgt_existing");

            verify(taskRepository).delete(existingTask);
        }

        @Test
        void deleteTask_rejects_active_task() {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.deleteTask(USER_ID, "lgt_existing"));

            assertTrue(error.getMessage().contains("lgt_existing"));
            verify(taskRepository, never()).delete(any());
        }

        @Test
        void startTask_noop_when_not_found() {
            service.startTask("nonexistent");
            // Should not throw, just skip
            verify(taskRepository, never()).save(any());
        }
    }

    @Nested
    class WorkerSessions {

        @BeforeEach
        void stubWorker() {
            LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
            worker.setWorkerId(WORKER_ID);
            worker.setUserId(USER_ID);
            when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);
        }

        @Test
        void lists_sessions_from_unified_session_store() {
            SessionTaskEntity older = sessionTask("lgt_older", SESSION_ID, "COMPLETED",
                    LocalDateTime.of(2026, 4, 1, 10, 0));
            older.setModel("biz-default");
            older.setCwd("/workspace/orders");

            SessionTaskEntity latest = sessionTask("lgt_latest", SESSION_ID, "RUNNING",
                    LocalDateTime.of(2026, 4, 1, 10, 5));
            latest.setModel("biz-default");
            latest.setCwd("/workspace/orders");

            when(sessionTaskRepository.findByWorkerIdAndUserIdOrderByCreatedAtDesc(WORKER_ID, USER_ID))
                    .thenReturn(List.of(latest, older));

            List<Map<String, Object>> result = service.listWorkerSessions(WORKER_ID, USER_ID);

            assertEquals(1, result.size());
            assertEquals(SESSION_ID, result.get(0).get("session_id"));
            assertEquals("lgt_latest", result.get(0).get("latest_task_id"));
            assertEquals("/workspace/orders", result.get(0).get("project"));
        }

        @Test
        void counts_session_messages_by_role() {
            when(sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc(SESSION_ID))
                    .thenReturn(List.of(sessionTask("lgt_task", SESSION_ID, "RUNNING",
                            LocalDateTime.of(2026, 4, 1, 10, 0))));
            when(sessionMessageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                    .thenReturn(List.of(
                            sessionMessage("m1", "user", "close order", LocalDateTime.of(2026, 4, 1, 10, 0)),
                            sessionMessage("m2", "assistant", "needs approval", LocalDateTime.of(2026, 4, 1, 10, 1)),
                            sessionMessage("m3", "tool", "approval_required", LocalDateTime.of(2026, 4, 1, 10, 2))
                    ));

            Map<String, Object> result = service.getWorkerSessionMessageCount(WORKER_ID, SESSION_ID, USER_ID);

            assertEquals(1L, result.get("user_count"));
            assertEquals(1L, result.get("assistant_count"));
            assertEquals(3, result.get("total"));
        }

        @Test
        void returns_paginated_session_messages() {
            when(sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc(SESSION_ID))
                    .thenReturn(List.of(sessionTask("lgt_task", SESSION_ID, "RUNNING",
                            LocalDateTime.of(2026, 4, 1, 10, 0))));
            when(sessionMessageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
                    .thenReturn(List.of(
                            sessionMessage("m1", "user", "first", LocalDateTime.of(2026, 4, 1, 10, 0)),
                            sessionMessage("m2", "assistant", "second", LocalDateTime.of(2026, 4, 1, 10, 1)),
                            sessionMessage("m3", "assistant", "third", LocalDateTime.of(2026, 4, 1, 10, 2))
                    ));

            List<Map<String, Object>> result =
                    service.getWorkerSessionMessages(WORKER_ID, SESSION_ID, USER_ID, 1, 1);

            assertEquals(1, result.size());
            assertEquals("assistant", result.get(0).get("role"));
            assertEquals("second", result.get(0).get("content"));
            assertEquals("lgt_task", result.get(0).get("taskId"));
        }

        @Test
        void sync_sessions_reports_local_projection_total() {
            when(sessionTaskRepository.findByWorkerIdAndUserIdOrderByCreatedAtDesc(WORKER_ID, USER_ID))
                    .thenReturn(List.of(
                            sessionTask("lgt_task_1", SESSION_ID, "COMPLETED",
                                    LocalDateTime.of(2026, 4, 1, 10, 0)),
                            sessionTask("lgt_task_2", "session-002", "COMPLETED",
                                    LocalDateTime.of(2026, 4, 1, 11, 0))
                    ));

            Map<String, Object> result = service.syncWorkerSessions(WORKER_ID, USER_ID, TENANT_ID);

            assertEquals(0, result.get("synced"));
            assertEquals(2L, result.get("total"));
            assertEquals("session-store", result.get("source"));
        }
    }

    private SessionTaskEntity sessionTask(String taskId, String sessionId, String status, LocalDateTime createdAt) {
        SessionTaskEntity entity = new SessionTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setProviderType(LanggraphTaskService.PROVIDER_TYPE);
        entity.setWorkerId(WORKER_ID);
        entity.setUserId(USER_ID);
        entity.setStatus(status);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt.plusMinutes(1));
        return entity;
    }

    private SessionMessageEntity sessionMessage(String id, String role, String content, LocalDateTime createdAt) {
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId(id);
        entity.setSessionId(SESSION_ID);
        entity.setTaskId("lgt_task");
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt(createdAt);
        return entity;
    }
}
