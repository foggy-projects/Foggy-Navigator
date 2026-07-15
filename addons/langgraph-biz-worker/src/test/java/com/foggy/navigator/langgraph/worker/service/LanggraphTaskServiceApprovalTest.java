package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphApprovalEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphApprovalRepository;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.session.repository.SessionMessageRepository;
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
 * createApprovalRecord() and the unified respondToTask command.
 */
class LanggraphTaskServiceApprovalTest {

    private LanggraphTaskRepository taskRepository;
    private LanggraphApprovalRepository approvalRepository;
    private LanggraphWorkerService workerService;
    private LanggraphWorkerClient workerClient;
    private LanggraphTaskService service;

    private static final String TASK_ID = "lgt_test001";
    private static final String SESSION_ID = "session-001";
    private static final String CONTEXT_ID = "ctx-001";
    private static final String USER_ID = "user-1";
    private static final String WORKER_ID = "worker-1";

    @BeforeEach
    void setUp() {
        taskRepository = mock(LanggraphTaskRepository.class);
        approvalRepository = mock(LanggraphApprovalRepository.class);
        workerService = mock(LanggraphWorkerService.class);
        SessionManager sessionManager = mock(SessionManager.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SessionTaskRepository sessionTaskRepository = mock(SessionTaskRepository.class);
        SessionEntityRepository sessionEntityRepository = mock(SessionEntityRepository.class);
        SessionMessageRepository sessionMessageRepository = mock(SessionMessageRepository.class);

        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Wire up workerService → workerClient chain
        workerClient = mock(LanggraphWorkerClient.class);
        LanggraphWorkerEntity workerEntity = new LanggraphWorkerEntity();
        workerEntity.setWorkerId(WORKER_ID);
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(workerEntity);
        when(workerService.createClient(workerEntity)).thenReturn(workerClient);

        service = new LanggraphTaskService(
                taskRepository, approvalRepository, workerService,
                sessionManager, publisher, sessionTaskRepository, sessionEntityRepository, sessionMessageRepository
        );
    }

    private LanggraphTaskEntity makeTaskEntity() {
        LanggraphTaskEntity entity = new LanggraphTaskEntity();
        entity.setTaskId(TASK_ID);
        entity.setSessionId(SESSION_ID);
        entity.setContextId(CONTEXT_ID);
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

    // -- respondToTask -------------------------------------------------------

    @Nested
    class RespondToTask {

        @BeforeEach
        void setUpApproval() {
            when(approvalRepository.findByTaskIdAndUserIdAndStatus(TASK_ID, USER_ID, "PENDING"))
                    .thenReturn(Optional.of(makePendingApproval()));
            when(taskRepository.findByTaskIdAndUserId(TASK_ID, USER_ID))
                    .thenReturn(Optional.of(makeTaskEntity()));
            when(workerClient.resumeTask(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Mono.just(Map.of("status", "RUNNING")));
        }

        @Test
        void approve_uses_authenticated_user_as_reviewer_and_ignores_spoofed_identity() {
            service.respondToTask(TASK_ID, USER_ID, Map.of(
                    "approvalResult", "approved",
                    "comment", "looks good",
                    "reviewedBy", "spoofed-reviewer",
                    "userId", "spoofed-user"));

            ArgumentCaptor<LanggraphApprovalEntity> captor =
                    ArgumentCaptor.forClass(LanggraphApprovalEntity.class);
            verify(approvalRepository).save(captor.capture());

            LanggraphApprovalEntity saved = captor.getValue();
            assertEquals("APPROVED", saved.getStatus());
            assertEquals("approved", saved.getApprovalResult());
            assertEquals("looks good", saved.getComment());
            assertEquals(USER_ID, saved.getReviewedBy());
            assertNotNull(saved.getReviewedAt());
            verify(taskRepository).findByTaskIdAndUserId(TASK_ID, USER_ID);
            verify(approvalRepository).findByTaskIdAndUserIdAndStatus(TASK_ID, USER_ID, "PENDING");
        }

        @Test
        void reject_sets_status_to_rejected() {
            service.respondToTask(TASK_ID, USER_ID, Map.of(
                    "approvalResult", "rejected",
                    "comment", "not ready"));

            ArgumentCaptor<LanggraphApprovalEntity> captor =
                    ArgumentCaptor.forClass(LanggraphApprovalEntity.class);
            verify(approvalRepository).save(captor.capture());
            assertEquals("REJECTED", captor.getValue().getStatus());
        }

        @Test
        void calls_worker_resume_with_correct_params() {
            service.respondToTask(TASK_ID, USER_ID, Map.of(
                    "decision", "allow",
                    "comment", "ok"));

            verify(workerClient).resumeTask(TASK_ID, SESSION_ID, CONTEXT_ID, "approved", "ok");
        }

        @Test
        void throws_when_no_pending_approval() {
            when(approvalRepository.findByTaskIdAndUserIdAndStatus(TASK_ID, USER_ID, "PENDING"))
                    .thenReturn(Optional.empty());

            assertThrows(IllegalStateException.class, () ->
                    service.respondToTask(TASK_ID, USER_ID, Map.of("approvalResult", "approved")));
            verify(workerClient, never()).resumeTask(any(), any(), any(), any(), any());
        }

        @Test
        void rejects_task_not_owned_by_authenticated_user_before_approval_lookup() {
            when(taskRepository.findByTaskIdAndUserId(TASK_ID, USER_ID))
                    .thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () ->
                    service.respondToTask(TASK_ID, USER_ID, Map.of("approvalResult", "approved")));
            verify(approvalRepository, never()).findByTaskIdAndUserIdAndStatus(any(), any(), any());
            verify(workerClient, never()).resumeTask(any(), any(), any(), any(), any());
        }

        @Test
        void rejects_invalid_decision_without_persisting_or_resuming() {
            assertThrows(IllegalArgumentException.class, () ->
                    service.respondToTask(TASK_ID, USER_ID, Map.of("approvalResult", "later")));

            verify(approvalRepository, never()).save(any());
            verify(workerClient, never()).resumeTask(any(), any(), any(), any(), any());
        }
    }
}
