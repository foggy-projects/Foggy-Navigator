package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LanggraphWorkerSessionQueryServiceTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String WORKER_ID = "worker-1";
    private static final String SESSION_ID = "session-001";

    private LanggraphWorkerService workerService;
    private SessionTaskRepository sessionTaskRepository;
    private SessionMessageRepository sessionMessageRepository;
    private LanggraphWorkerSessionQueryService service;

    @BeforeEach
    void setUp() {
        workerService = mock(LanggraphWorkerService.class);
        sessionTaskRepository = mock(SessionTaskRepository.class);
        sessionMessageRepository = mock(SessionMessageRepository.class);

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId(WORKER_ID);
        worker.setUserId(USER_ID);
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);

        service = new LanggraphWorkerSessionQueryService(
                workerService,
                sessionTaskRepository,
                sessionMessageRepository);
    }

    @Test
    void declares_worker_session_capabilities_only() {
        assertEquals(LanggraphTaskService.PROVIDER_TYPE, service.getProviderType());
        assertTrue(service.supports(TaskQueryCapability.LIST_WORKER_SESSIONS));
        assertTrue(service.supports(TaskQueryCapability.GET_WORKER_SESSION_MESSAGE_COUNT));
        assertTrue(service.supports(TaskQueryCapability.GET_WORKER_SESSION_MESSAGES));
        assertTrue(service.supports(TaskQueryCapability.SYNC_WORKER_SESSIONS));
        assertFalse(service.supports(TaskQueryCapability.CREATE_TASK_DIRECT));
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

    @Test
    void rejects_worker_owned_by_other_user() {
        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId(WORKER_ID);
        worker.setUserId("user-2");
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.listWorkerSessions(WORKER_ID, USER_ID));

        assertEquals("Worker not found: " + WORKER_ID, error.getMessage());
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
