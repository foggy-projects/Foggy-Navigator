package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
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
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
    private LanggraphWorkerClient workerClient;
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
        workerClient = mock(LanggraphWorkerClient.class);
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
            CreateLanggraphTaskForm form = makeForm();
            form.setSkillName("tms.navigator.agent");
            form.setMaxTurns(12);
            service.createTask(USER_ID, TENANT_ID, form);

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
            assertEquals(Integer.valueOf(12), event.getProviderConfigValue("maxTurns"));
            assertEquals("tms.navigator.agent", event.getProviderConfigString("skill_name"));
            assertEquals("tms.navigator.agent", event.getProviderConfigString("skillName"));
        }

        @Test
        void createTaskDirect_accepts_legacy_skill_alias_and_publishes_canonical_skillName() {
            var savedTask = new java.util.concurrent.atomic.AtomicReference<LanggraphTaskEntity>();
            when(taskRepository.save(any(LanggraphTaskEntity.class))).thenAnswer(inv -> {
                LanggraphTaskEntity entity = inv.getArgument(0);
                savedTask.set(entity);
                return entity;
            });
            when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> {
                LanggraphTaskEntity entity = savedTask.get();
                return entity != null && invocation.getArgument(0).equals(entity.getTaskId())
                        ? Optional.of(entity)
                        : Optional.empty();
            });

            Map<String, Object> params = directTaskParams();
            params.put("skill_id", "legacy.skill");

            service.createTaskDirect(params, USER_ID, TENANT_ID);

            ArgumentCaptor<WorkerTaskStartEvent> captor =
                    ArgumentCaptor.forClass(WorkerTaskStartEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            WorkerTaskStartEvent event = captor.getValue();
            assertEquals("legacy.skill", event.getProviderConfigString("skill_name"));
            assertEquals("legacy.skill", event.getProviderConfigString("skillName"));
        }

        @Test
        void createTaskDirect_rejects_conflicting_skill_aliases() {
            Map<String, Object> params = directTaskParams();
            params.put("skill_name", "canonical.skill");
            params.put("skill_id", "legacy.skill");

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.createTaskDirect(params, USER_ID, TENANT_ID));

            assertEquals("skill_name aliases must resolve to the same value", error.getMessage());
            verify(eventPublisher, never()).publishEvent(any());
            verify(taskRepository, never()).save(any());
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
            form.setContextId("ctx-1");
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
            assertEquals("ctx-1", context.get("contextId"));
            assertEquals("ctx-1", context.get("context_id"));
            assertEquals(SESSION_ID, context.get("session_id"));
        }

        @Test
        void projects_task_deadline_from_runtime_context() {
            CreateLanggraphTaskForm form = makeForm();
            form.setRuntimeContext(Map.of("taskDeadlineAt", "2026-05-18T10:00:00Z"));

            service.createTask(USER_ID, TENANT_ID, form);

            verify(sessionTaskRepository).save(argThat((SessionTaskEntity projection) ->
                    projection.getTaskStateJson() != null
                            && projection.getTaskStateJson().contains("\"taskDeadlineAt\":\"2026-05-18T10:00:00Z\"")
            ));
        }

        @Test
        void forwards_recent_conversation_and_persists_current_user_prompt() {
            when(sessionMessageRepository.findBySessionIdOrderByCreatedAtDesc(eq(SESSION_ID), any()))
                    .thenReturn(List.of(
                            sessionMessage("m3", "assistant", "Opening frame", LocalDateTime.of(2026, 4, 1, 10, 2),
                                    "{\"type\":\"STATE_SYNC\"}"),
                            sessionMessage("m2", "assistant", "工单状态正常", LocalDateTime.of(2026, 4, 1, 10, 1),
                                    "{\"type\":\"TEXT_COMPLETE\"}"),
                            sessionMessage("m1", "user", "之前查工单", LocalDateTime.of(2026, 4, 1, 10, 0),
                                    "{\"type\":\"USER\"}")
                    ));

            service.createTask(USER_ID, TENANT_ID, makeForm());

            ArgumentCaptor<WorkerTaskStartEvent> eventCaptor =
                    ArgumentCaptor.forClass(WorkerTaskStartEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());

            @SuppressWarnings("unchecked")
            Map<String, Object> context =
                    (Map<String, Object>) eventCaptor.getValue().getProviderConfig().get("context");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> recentConversation =
                    (List<Map<String, Object>>) context.get("recentConversation");
            assertEquals(2, recentConversation.size());
            assertEquals("之前查工单", recentConversation.get(0).get("content"));
            assertEquals("工单状态正常", recentConversation.get(1).get("content"));

            verify(sessionManager).addMessage(eq(SESSION_ID), argThat(message ->
                    message.getRole() != null
                            && "USER".equals(message.getRole().name())
                            && "分析异常订单".equals(message.getContent())
                            && eventCaptor.getValue().getTaskId().equals(message.getTaskId())
            ));
        }
    }

    private Map<String, Object> directTaskParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("agentId", AGENT_ID);
        params.put("workerId", WORKER_ID);
        params.put("prompt", "分析异常订单");
        params.put("sessionId", SESSION_ID);
        params.put("model", "claude-sonnet");
        params.put("modelConfigId", "cfg-langgraph");
        return params;
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
            assertEquals("FAILED", existingTask.getTaskSubStatus());
            assertEquals("connection timeout", existingTask.getErrorMessage());
            verify(taskRepository).save(existingTask);
        }

        @Test
        void cancelTask_sets_aborted_for_active_task() {
            service.cancelTask("lgt_existing", USER_ID);

            assertEquals("ABORTED", existingTask.getStatus());
            assertEquals("INTERRUPTED", existingTask.getTaskSubStatus());
            assertEquals("user_cancelled", existingTask.getInterruptionReason());
            assertEquals(true, existingTask.getRecoverable());
            assertEquals("Cancelled by user", existingTask.getErrorMessage());
            verify(taskRepository).save(existingTask);
        }

        @Test
        void cancelTask_records_recoverable_interruption_on_worker() {
            existingTask.setWorkerId(WORKER_ID);
            existingTask.setSessionId(SESSION_ID);
            existingTask.setContextId("ctx-1");
            existingTask.setAgentId(AGENT_ID);
            LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
            worker.setWorkerId(WORKER_ID);
            when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);
            when(workerService.createClient(worker)).thenReturn(workerClient);
            when(workerClient.recordInterruption(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyMap()
            )).thenReturn(Mono.just(Map.of("status", "recorded")));

            service.cancelTask("lgt_existing", USER_ID);

            verify(workerClient).recordInterruption(
                    eq("lgt_existing"),
                    eq(SESSION_ID),
                    eq("ctx-1"),
                    eq("user_cancelled"),
                    eq("Cancelled by user"),
                    argThat(context -> "ctx-1".equals(context.get("contextId"))
                            && SESSION_ID.equals(context.get("session_id"))
                            && AGENT_ID.equals(context.get("agentId"))
                            && "ABORTED".equals(context.get("taskStatus")))
            );
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
        return sessionMessage(id, role, content, createdAt, null);
    }

    private SessionMessageEntity sessionMessage(String id, String role, String content, LocalDateTime createdAt,
                                                String metadata) {
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId(id);
        entity.setSessionId(SESSION_ID);
        entity.setTaskId("lgt_task");
        entity.setRole(role);
        entity.setContent(content);
        entity.setMetadata(metadata);
        entity.setCreatedAt(createdAt);
        return entity;
    }
}
