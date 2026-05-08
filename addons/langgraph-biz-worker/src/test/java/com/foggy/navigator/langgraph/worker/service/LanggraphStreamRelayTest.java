package com.foggy.navigator.langgraph.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.codec.ServerSentEvent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LanggraphStreamRelayTest {

    private LanggraphTaskService taskService;
    private ApplicationEventPublisher eventPublisher;
    private LanggraphStreamRelay relay;

    @BeforeEach
    void setUp() {
        taskService = mock(LanggraphTaskService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        relay = new LanggraphStreamRelay(
                mock(LanggraphWorkerService.class),
                taskService,
                eventPublisher,
                new ObjectMapper()
        );
    }

    @Test
    void approvalRequiredCreatesApprovalRecordAndPublishesStateSync() throws Exception {
        String taskId = "lgt-task-1";
        String sessionId = "session-1";
        when(taskService.getTaskById(taskId)).thenReturn(Optional.of(
                DispatchTaskDTO.builder()
                        .taskId(taskId)
                        .sessionId(sessionId)
                        .userId("user-1")
                        .build()
        ));

        String data = """
                {
                  "type": "approval_required",
                  "content": "提交关单申请",
                  "approval_type": "order_close_apply",
                  "script_run_id": "sr_001",
                  "suspend_id": "sp_001",
                  "reason": "order.close_apply.submit",
                  "timeout_at": "2026-05-01T10:00:00Z",
                  "payload": {
                    "script_run_id": "sr_001",
                    "suspend_id": "sp_001",
                    "reason": "order.close_apply.submit"
                  }
                }
                """;

        invokeHandleEvent(ServerSentEvent.<String>builder().data(data).build(), taskId, sessionId);

        verify(taskService).createApprovalRecord(
                taskId,
                sessionId,
                "user-1",
                "order_close_apply",
                "提交关单申请",
                "{\"script_run_id\":\"sr_001\",\"suspend_id\":\"sp_001\",\"reason\":\"order.close_apply.submit\"}"
        );

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        AgentMessage message = assertInstanceOf(AgentMessage.class, captor.getValue());
        assertEquals(MessageType.STATE_SYNC, message.getType());
        assertEquals(taskId, message.getTaskId());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("approval_required", payload.get("subtype"));
        assertEquals("order_close_apply", payload.get("approvalType"));
        assertEquals("sr_001", payload.get("scriptRunId"));
        assertEquals("sp_001", payload.get("suspendId"));
        assertEquals("order.close_apply.submit", payload.get("reason"));
        assertEquals("2026-05-01T10:00:00Z", payload.get("timeoutAt"));
    }

    @Test
    void skillApprovalRequestRemainsSupported() throws Exception {
        String taskId = "lgt-task-2";
        String sessionId = "session-2";
        when(taskService.getTaskById(taskId)).thenReturn(Optional.of(
                DispatchTaskDTO.builder()
                        .taskId(taskId)
                        .sessionId(sessionId)
                        .userId("user-2")
                        .build()
        ));

        String data = """
                {
                  "type": "skill_approval_request",
                  "content": "Need confirmation",
                  "approval_type": "manual_dispatch",
                  "payload": {"frame_id": "frame-1"}
                }
                """;

        invokeHandleEvent(ServerSentEvent.<String>builder().data(data).build(), taskId, sessionId);

        verify(taskService).createApprovalRecord(
                taskId,
                sessionId,
                "user-2",
                "manual_dispatch",
                "Need confirmation",
                "{\"frame_id\":\"frame-1\"}"
        );
    }

    private void invokeHandleEvent(
            ServerSentEvent<String> event,
            String taskId,
            String sessionId
    ) throws Exception {
        Method method = LanggraphStreamRelay.class.getDeclaredMethod(
                "handleEvent",
                ServerSentEvent.class,
                String.class,
                String.class
        );
        method.setAccessible(true);
        method.invoke(relay, event, taskId, sessionId);
    }
}
