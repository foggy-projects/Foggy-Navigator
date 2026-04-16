package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphApprovalEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.model.form.ApproveTaskForm;
import com.foggy.navigator.langgraph.worker.repository.LanggraphApprovalRepository;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for LanggraphTaskService approval chain:
 * createApprovalRecord() and approveTask().
 */
class LanggraphTaskServiceApprovalTest {

    private LanggraphTaskRepository taskRepository;
    private LanggraphApprovalRepository approvalRepository;
    private LanggraphWorkerService workerService;
    private LanggraphWorkerClient workerClient;
    private LanggraphTaskService service;

    private static final String TASK_ID = "lgt_test001";
    private static final String SESSION_ID = "session-001";
    private static final String USER_ID = "user-1";
    private static final String WORKER_ID = "worker-1";

    @BeforeEach
    void setUp() {
        taskRepository = mock(LanggraphTaskRepository.class);
        approvalRepository = mock(LanggraphApprovalRepository.class);
        workerService = mock(LanggraphWorkerService.class);
        SessionManager sessionManager = mock(SessionManager.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Wire up workerService → workerClient chain
        workerClient = mock(LanggraphWorkerClient.class);
        LanggraphWorkerEntity workerEntity = new LanggraphWorkerEntity();
        workerEntity.setWorkerId(WORKER_ID);
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(workerEntity);
        when(workerService.createClient(workerEntity)).thenReturn(workerClient);

        service = new LanggraphTaskService(
                taskRepository, approvalRepository, workerService,
                sessionManager, publisher
        );
    }

    private LanggraphTaskEntity makeTaskEntity() {
        LanggraphTaskEntity entity = new LanggraphTaskEntity();
        entity.setTaskId(TASK_ID);
        entity.setSessionId(SESSION_ID);
        entity.setWorkerId(WORKER_ID);
        entity.setUserId(USER_ID);
        entity.setPrompt("test prompt");
        entity.setStatus("RUNNING");
        return entity;
    }

    private LanggraphApprovalEntity makePendingApproval() {
        LanggraphApprovalEntity entity = new LanggraphApprovalEntity();
        entity.setId(1L);
        entity.setTaskId(TASK_ID);
        entity.setSessionId(SESSION_ID);
        entity.setUserId(USER_ID);
        entity.setApprovalType("manual_dispatch");
        entity.setSummary("Need confirmation");
        entity.setStatus("PENDING");
        return entity;
    }

    // -- createApprovalRecord ------------------------------------------------

    @Nested
    class CreateApprovalRecord {

        @Test
        void creates_record_with_correct_fields() {
            LanggraphApprovalEntity result = service.createApprovalRecord(
                    TASK_ID, SESSION_ID, USER_ID,
                    "manual_dispatch", "Need confirmation", "{\"key\":\"val\"}"
            );

            assertNotNull(result);
            assertEquals(TASK_ID, result.getTaskId());
            assertEquals(SESSION_ID, result.getSessionId());
            assertEquals(USER_ID, result.getUserId());
            assertEquals("manual_dispatch", result.getApprovalType());
            assertEquals("Need confirmation", result.getSummary());
            assertEquals("{\"key\":\"val\"}", result.getPayload());
            assertEquals("PENDING", result.getStatus());
        }

        @Test
        void calls_repository_save() {
            service.createApprovalRecord(
                    TASK_ID, SESSION_ID, USER_ID,
                    "dispatch", "summary", null
            );

            ArgumentCaptor<LanggraphApprovalEntity> captor =
                    ArgumentCaptor.forClass(LanggraphApprovalEntity.class);
            verify(approvalRepository).save(captor.capture());
            assertEquals("PENDING", captor.getValue().getStatus());
        }
    }

    // -- approveTask ---------------------------------------------------------

    @Nested
    class ApproveTask {

        @BeforeEach
        void setUpApproval() {
            when(approvalRepository.findByTaskIdAndStatus(TASK_ID, "PENDING"))
                    .thenReturn(Optional.of(makePendingApproval()));
            when(taskRepository.findByTaskId(TASK_ID))
                    .thenReturn(Optional.of(makeTaskEntity()));
            when(workerClient.resumeTask(anyString(), anyString(), anyString()))
                    .thenReturn(Mono.just(Map.of("status", "RUNNING")));
        }

        @Test
        void approve_sets_status_to_approved() {
            ApproveTaskForm form = new ApproveTaskForm();
            form.setApprovalResult("approved");
            form.setComment("looks good");
            form.setReviewedBy("reviewer-1");

            service.approveTask(TASK_ID, form);

            ArgumentCaptor<LanggraphApprovalEntity> captor =
                    ArgumentCaptor.forClass(LanggraphApprovalEntity.class);
            verify(approvalRepository).save(captor.capture());

            LanggraphApprovalEntity saved = captor.getValue();
            assertEquals("APPROVED", saved.getStatus());
            assertEquals("approved", saved.getApprovalResult());
            assertEquals("looks good", saved.getComment());
            assertEquals("reviewer-1", saved.getReviewedBy());
            assertNotNull(saved.getReviewedAt());
        }

        @Test
        void reject_sets_status_to_rejected() {
            ApproveTaskForm form = new ApproveTaskForm();
            form.setApprovalResult("rejected");
            form.setComment("not ready");

            service.approveTask(TASK_ID, form);

            ArgumentCaptor<LanggraphApprovalEntity> captor =
                    ArgumentCaptor.forClass(LanggraphApprovalEntity.class);
            verify(approvalRepository).save(captor.capture());
            assertEquals("REJECTED", captor.getValue().getStatus());
        }

        @Test
        void calls_worker_resume_with_correct_params() {
            ApproveTaskForm form = new ApproveTaskForm();
            form.setApprovalResult("approved");
            form.setComment("ok");

            service.approveTask(TASK_ID, form);

            verify(workerClient).resumeTask(TASK_ID, "approved", "ok");
        }

        @Test
        void throws_when_no_pending_approval() {
            when(approvalRepository.findByTaskIdAndStatus(TASK_ID, "PENDING"))
                    .thenReturn(Optional.empty());

            ApproveTaskForm form = new ApproveTaskForm();
            form.setApprovalResult("approved");

            assertThrows(IllegalArgumentException.class, () ->
                    service.approveTask(TASK_ID, form));
            verify(workerClient, never()).resumeTask(any(), any(), any());
        }

        @Test
        void throws_when_task_not_found() {
            when(taskRepository.findByTaskId(TASK_ID))
                    .thenReturn(Optional.empty());

            ApproveTaskForm form = new ApproveTaskForm();
            form.setApprovalResult("approved");

            assertThrows(IllegalArgumentException.class, () ->
                    service.approveTask(TASK_ID, form));
        }
    }
}
