package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.common.event.WorkerGatewayResumeEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.session.event.SessionEventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LanggraphWorkerResumeEventListenerTest {

    @Mock
    private LanggraphTaskRepository taskRepository;

    @Mock
    private LanggraphWorkerService workerService;

    @Mock
    private LanggraphWorkerClient workerClient;

    @Mock
    private SessionEventListener sessionEventListener;

    @InjectMocks
    private LanggraphWorkerResumeEventListener listener;

    // ===== Helper =====

    private LanggraphTaskEntity buildTask(String taskId, String sessionId, String workerId, String tenantId) {
        LanggraphTaskEntity task = new LanggraphTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId(sessionId);
        task.setWorkerId(workerId);
        task.setTenantId(tenantId);
        task.setContextId("ctx_" + taskId);
        return task;
    }

    // ===== Stage 4C: existing passing tests (updated for Stage 7B) =====

    @Test
    void handleWorkerGatewayResumeEvent_success_taskAndSessionAndTenantMatch() {
        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("t_1")
                .sessionId("s_1")
                .suspendId("sus_1")
                .approvalResult("approved")
                .comment("OK")
                .tenantId("tenant_1")
                .build();

        LanggraphTaskEntity task = buildTask("t_1", "s_1", "w_1", "tenant_1");
        when(taskRepository.findByTaskId("t_1")).thenReturn(Optional.of(task));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        when(workerService.getWorkerEntity("w_1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(workerClient);
        when(workerClient.resumeTask("t_1", "s_1", "ctx_t_1", "approved", "OK")).thenReturn(Mono.empty());

        listener.handleWorkerGatewayResumeEvent(event);

        verify(workerClient).resumeTask("t_1", "s_1", "ctx_t_1", "approved", "OK");
    }

    @Test
    void handleWorkerGatewayResumeEvent_success_usesWorkerSessionForDispatchAndKeepsBusinessSession() {
        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("lgt_1")
                .sessionId("worker_session_1")
                .businessSessionId("business_session_1")
                .suspendId("sus_1")
                .approvalResult("approved")
                .comment("OK")
                .tenantId("tenant_1")
                .build();

        LanggraphTaskEntity task = buildTask("lgt_1", "worker_session_1", "w_1", "tenant_1");
        when(taskRepository.findByTaskId("lgt_1")).thenReturn(Optional.of(task));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        when(workerService.getWorkerEntity("w_1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(workerClient);
        when(workerClient.resumeTask("lgt_1", "worker_session_1", "ctx_lgt_1", "approved", "OK")).thenReturn(Mono.empty());

        listener.handleWorkerGatewayResumeEvent(event);

        verify(workerClient).resumeTask("lgt_1", "worker_session_1", "ctx_lgt_1", "approved", "OK");
    }

    @Test
    void handleWorkerGatewayResumeEvent_workerResume404_onlyLogsConversationNotificationFailure() {
        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("lgt_1")
                .sessionId("worker_session_1")
                .businessSessionId("business_session_1")
                .suspendId("sus_1")
                .approvalResult("approved")
                .comment("OK")
                .tenantId("tenant_1")
                .build();

        LanggraphTaskEntity task = buildTask("lgt_1", "worker_session_1", "w_1", "tenant_1");
        when(taskRepository.findByTaskId("lgt_1")).thenReturn(Optional.of(task));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        when(workerService.getWorkerEntity("w_1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(workerClient);
        when(workerClient.resumeTask("lgt_1", "worker_session_1", "ctx_lgt_1", "approved", "OK"))
                .thenReturn(Mono.error(WebClientResponseException.create(
                        404,
                        "Not Found",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8
                )));

        listener.handleWorkerGatewayResumeEvent(event);

        verify(workerClient).resumeTask("lgt_1", "worker_session_1", "ctx_lgt_1", "approved", "OK");
    }

    @Test
    void handleWorkerGatewayResumeEvent_workerResume500_doesNotOwnBusinessExecution() {
        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("lgt_1")
                .sessionId("worker_session_1")
                .businessSessionId("business_session_1")
                .suspendId("sus_1")
                .approvalResult("approved")
                .comment("OK")
                .tenantId("tenant_1")
                .build();

        LanggraphTaskEntity task = buildTask("lgt_1", "worker_session_1", "w_1", "tenant_1");
        when(taskRepository.findByTaskId("lgt_1")).thenReturn(Optional.of(task));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        when(workerService.getWorkerEntity("w_1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(workerClient);
        when(workerClient.resumeTask("lgt_1", "worker_session_1", "ctx_lgt_1", "approved", "OK"))
                .thenReturn(Mono.error(WebClientResponseException.create(
                        500,
                        "Internal Server Error",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8
                )));

        listener.handleWorkerGatewayResumeEvent(event);

        verify(workerClient).resumeTask("lgt_1", "worker_session_1", "ctx_lgt_1", "approved", "OK");
    }

    @Test
    void handleWorkerGatewayResumeEvent_success_noEventTenantId_backwardCompatible() {
        // Event with no tenantId — backward-compatible path, should still dispatch
        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("t_1")
                .sessionId("s_1")
                .suspendId("sus_1")
                .approvalResult("approved")
                .comment("OK")
                .build();

        LanggraphTaskEntity task = buildTask("t_1", "s_1", "w_1", "tenant_1");
        when(taskRepository.findByTaskId("t_1")).thenReturn(Optional.of(task));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        when(workerService.getWorkerEntity("w_1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(workerClient);
        when(workerClient.resumeTask("t_1", "s_1", "ctx_t_1", "approved", "OK")).thenReturn(Mono.empty());

        listener.handleWorkerGatewayResumeEvent(event);

        verify(workerClient).resumeTask("t_1", "s_1", "ctx_t_1", "approved", "OK");
    }

    @Test
    void handleWorkerGatewayResumeEvent_publishesResumeMessageWhenWorkerReturnsContent() {
        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("lgt_1")
                .sessionId("worker_session_1")
                .businessSessionId("business_session_1")
                .suspendId("sus_1")
                .approvalResult("approved")
                .comment("OK")
                .tenantId("tenant_1")
                .build();

        LanggraphTaskEntity task = buildTask("lgt_1", "worker_session_1", "w_1", "tenant_1");
        when(taskRepository.findByTaskId("lgt_1")).thenReturn(Optional.of(task));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        when(workerService.getWorkerEntity("w_1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(workerClient);
        when(workerClient.resumeTask("lgt_1", "worker_session_1", "ctx_lgt_1", "approved", "OK"))
                .thenReturn(Mono.just(Map.of(
                        "resume_message", Map.of(
                                "content", "审批已通过，已继续提交关单申请。",
                                "source", "function_input.approved",
                                "execution_report_ref", "frame-report://lgt_1/fn_1",
                                "execution_report_digest", Map.of("status", "COMPLETED")
                        )
                )));

        listener.handleWorkerGatewayResumeEvent(event);

        ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener).handleMessage(captor.capture());

        AgentMessage message = captor.getValue();
        assertEquals("worker_session_1", message.getSessionId());
        assertEquals(LanggraphTaskService.PROVIDER_TYPE, message.getAgentId());
        assertEquals(MessageType.TEXT_COMPLETE, message.getType());
        assertEquals("lgt_1", message.getTaskId());

        Map<?, ?> payload = assertInstanceOf(Map.class, message.getPayload());
        assertEquals("审批已通过，已继续提交关单申请。", payload.get("content"));
        assertEquals("function_input.approved", payload.get("source"));
        assertEquals("post_approval_message", payload.get("subtype"));
        assertEquals("lgt_1", payload.get("taskId"));
        assertEquals("sus_1", payload.get("suspendId"));
        assertEquals("approved", payload.get("approvalResult"));
        assertEquals("frame-report://lgt_1/fn_1", payload.get("execution_report_ref"));
        assertEquals("COMPLETED", ((Map<?, ?>) payload.get("execution_report_digest")).get("status"));
    }

    @Test
    void handleWorkerGatewayResumeEvent_fails_on_session_mismatch() {
        LanggraphTaskEntity task = buildTask("t_1", "s_different", "w_1", "tenant_1");
        when(taskRepository.findByTaskId("t_1")).thenReturn(Optional.of(task));

        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("t_1")
                .sessionId("s_1")
                .suspendId("sus_1")
                .approvalResult("approved")
                .tenantId("tenant_1")
                .build();

        listener.handleWorkerGatewayResumeEvent(event);

        verifyNoInteractions(workerService);
    }

    @Test
    void handleWorkerGatewayResumeEvent_ignores_missing_task() {
        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("t_1")
                .build();

        when(taskRepository.findByTaskId("t_1")).thenReturn(Optional.empty());

        listener.handleWorkerGatewayResumeEvent(event);

        verifyNoInteractions(workerService);
    }

    @Test
    void handleWorkerGatewayResumeEvent_ignores_missing_workerId() {
        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("t_1")
                .sessionId("s_1")
                .tenantId("tenant_1")
                .build();

        LanggraphTaskEntity task = buildTask("t_1", "s_1", null, "tenant_1");
        when(taskRepository.findByTaskId("t_1")).thenReturn(Optional.of(task));

        listener.handleWorkerGatewayResumeEvent(event);

        verifyNoInteractions(workerService);
    }

    @Test
    void handleWorkerGatewayResumeEvent_rejects_missing_contextId() {
        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("t_1")
                .sessionId("s_1")
                .tenantId("tenant_1")
                .approvalResult("approved")
                .build();

        LanggraphTaskEntity task = buildTask("t_1", "s_1", "w_1", "tenant_1");
        task.setContextId(null);
        when(taskRepository.findByTaskId("t_1")).thenReturn(Optional.of(task));

        listener.handleWorkerGatewayResumeEvent(event);

        verifyNoInteractions(workerService);
    }

    // ===== Stage 7B: tenant binding hardening =====

    @Test
    void handleWorkerGatewayResumeEvent_rejects_tenantMismatch() {
        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("t_1")
                .sessionId("s_1")
                .tenantId("tenant_evil")     // mismatched tenant
                .approvalResult("approved")
                .build();

        // Task is registered under a different tenant
        LanggraphTaskEntity task = buildTask("t_1", "s_1", "w_1", "tenant_legit");
        when(taskRepository.findByTaskId("t_1")).thenReturn(Optional.of(task));

        listener.handleWorkerGatewayResumeEvent(event);

        // Must not dispatch
        verifyNoInteractions(workerService);
    }

    @Test
    void handleWorkerGatewayResumeEvent_rejects_eventHasTenantId_butTaskHasNone() {
        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("t_1")
                .sessionId("s_1")
                .tenantId("tenant_1")       // event carries tenantId
                .approvalResult("approved")
                .build();

        // Task entity has no tenantId — fail closed
        LanggraphTaskEntity task = buildTask("t_1", "s_1", "w_1", null);
        when(taskRepository.findByTaskId("t_1")).thenReturn(Optional.of(task));

        listener.handleWorkerGatewayResumeEvent(event);

        verifyNoInteractions(workerService);
    }
}
