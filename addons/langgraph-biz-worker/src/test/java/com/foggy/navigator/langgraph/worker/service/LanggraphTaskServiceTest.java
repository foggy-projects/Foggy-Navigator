package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
import com.foggy.navigator.langgraph.worker.repository.LanggraphApprovalRepository;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

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
    private SessionManager sessionManager;
    private ApplicationEventPublisher eventPublisher;
    private LanggraphTaskService service;

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String WORKER_ID = "worker-1";
    private static final String SESSION_ID = "session-001";

    @BeforeEach
    void setUp() {
        taskRepository = mock(LanggraphTaskRepository.class);
        LanggraphApprovalRepository approvalRepository = mock(LanggraphApprovalRepository.class);
        LanggraphWorkerService workerService = mock(LanggraphWorkerService.class);
        sessionManager = mock(SessionManager.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new LanggraphTaskService(
                taskRepository, approvalRepository, workerService,
                sessionManager, eventPublisher
        );
    }

    private CreateLanggraphTaskForm makeForm() {
        CreateLanggraphTaskForm form = new CreateLanggraphTaskForm();
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
            assertNotNull(saved.getTaskId());
            assertTrue(saved.getTaskId().startsWith("lgt_"));
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
}
