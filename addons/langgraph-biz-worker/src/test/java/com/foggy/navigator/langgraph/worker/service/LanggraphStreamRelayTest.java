package com.foggy.navigator.langgraph.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.session.event.SessionEventListener;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.codec.ServerSentEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LanggraphStreamRelayTest {

    private LanggraphTaskService taskService;
    private SessionEventListener sessionEventListener;
    private LlmModelManager llmModelManager;
    private LanggraphStreamRelay relay;

    @BeforeEach
    void setUp() {
        taskService = mock(LanggraphTaskService.class);
        sessionEventListener = mock(SessionEventListener.class);
        llmModelManager = mock(LlmModelManager.class);
        relay = new LanggraphStreamRelay(
                mock(LanggraphWorkerService.class),
                taskService,
                sessionEventListener,
                new ObjectMapper(),
                llmModelManager
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
                  },
                  "execution_report_ref": "frame-report://lgt-task-1/fn-1",
                  "execution_report_digest": {"status": "AWAITING_APPROVAL"}
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

        ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener).handleMessage(captor.capture());
        AgentMessage message = captor.getValue();
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
        assertEquals("frame-report://lgt-task-1/fn-1", payload.get("execution_report_ref"));
        assertEquals("AWAITING_APPROVAL", ((Map<?, ?>) payload.get("execution_report_digest")).get("status"));
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

    @Test
    void toolUseAndResultEventsPublishToolMessages() throws Exception {
        String taskId = "lgt-task-3";
        String sessionId = "session-3";

        String toolUse = """
                {
                  "type": "tool_use",
                  "content": "tms.dataset.listModels",
                  "skill_frame_id": "frm-1",
                  "parent_frame_id": "frm-parent",
                  "skill_id": "foggy-query-agent"
                }
                """;
        String toolResult = """
                {
                  "type": "tool_result",
                  "content": "{\\"ok\\":true,\\"count\\":19}",
                  "skill_frame_id": "frm-1",
                  "parent_frame_id": "frm-parent",
                  "skill_id": "foggy-query-agent",
                  "execution_report_ref": "frame-report://lgt-task-3/frm-1",
                  "execution_report_digest": {"status": "COMPLETED"}
                }
                """;

        invokeHandleEvent(ServerSentEvent.<String>builder().data(toolUse).build(), taskId, sessionId);
        invokeHandleEvent(ServerSentEvent.<String>builder().data(toolResult).build(), taskId, sessionId);

        ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener, times(2)).handleMessage(captor.capture());
        List<AgentMessage> events = captor.getAllValues();

        AgentMessage start = events.get(0);
        assertEquals(MessageType.TOOL_CALL_START, start.getType());
        assertEquals(taskId, start.getTaskId());
        @SuppressWarnings("unchecked")
        Map<String, Object> startPayload = (Map<String, Object>) start.getPayload();
        assertEquals("tms.dataset.listModels", startPayload.get("toolName"));
        assertEquals("frm-1", startPayload.get("skillFrameId"));
        assertEquals("frm-parent", startPayload.get("parentFrameId"));
        assertEquals("foggy-query-agent", startPayload.get("skillId"));

        AgentMessage result = events.get(1);
        assertEquals(MessageType.TOOL_CALL_RESULT, result.getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> resultPayload = (Map<String, Object>) result.getPayload();
        assertEquals(true, resultPayload.get("success"));
        assertEquals("tool_result", resultPayload.get("subtype"));
        assertEquals("frm-1", resultPayload.get("skillFrameId"));
        assertEquals("frm-parent", resultPayload.get("parentFrameId"));
        assertEquals("frame-report://lgt-task-3/frm-1", resultPayload.get("execution_report_ref"));
        assertEquals("COMPLETED", ((Map<?, ?>) resultPayload.get("execution_report_digest")).get("status"));
    }

    @Test
    void skillFrameClosePublishesExecutionReportFields() throws Exception {
        String taskId = "lgt-task-close";
        String sessionId = "session-close";
        String data = """
                {
                  "type": "skill_frame_close",
                  "content": "child done",
                  "skill_frame_id": "frm-child",
                  "parent_frame_id": "frm-root",
                  "skill_id": "tms-fulfillment-agent",
                  "execution_report_ref": "frame-report://lgt-task-close/frm-child",
                  "execution_report_digest": {"status": "COMPLETED"}
                }
                """;

        invokeHandleEvent(ServerSentEvent.<String>builder().data(data).build(), taskId, sessionId);

        ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener).handleMessage(captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) captor.getValue().getPayload();
        assertEquals("skill_frame_close", payload.get("subtype"));
        assertEquals("frame-report://lgt-task-close/frm-child", payload.get("execution_report_ref"));
        assertEquals("COMPLETED", ((Map<?, ?>) payload.get("execution_report_digest")).get("status"));
    }

    @Test
    void taskProgressPublishesStateSyncForRetryVisibility() throws Exception {
        String taskId = "lgt-task-progress";
        String sessionId = "session-progress";
        String data = """
                {
                  "type": "task_progress",
                  "content": "LLM call retrying after LLM_REQUEST_TIMEOUT (1/2)",
                  "progress_type": "llm_retrying",
                  "reason": "LLM_REQUEST_TIMEOUT",
                  "attempt": 1,
                  "max_attempts": 2,
                  "next_retry_after_ms": 1000,
                  "remaining_ms": 120000,
                  "skill_frame_id": "frm-root",
                  "presentation_hint": "debug_detail",
                  "payload": {"operation": "skill_agent.invoke"}
                }
                """;

        invokeHandleEvent(ServerSentEvent.<String>builder().data(data).build(), taskId, sessionId);

        ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener).handleMessage(captor.capture());
        AgentMessage message = captor.getValue();
        assertEquals(MessageType.STATE_SYNC, message.getType());
        assertEquals(taskId, message.getTaskId());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("task_progress", payload.get("subtype"));
        assertEquals("llm_retrying", payload.get("progressType"));
        assertEquals("LLM_REQUEST_TIMEOUT", payload.get("reason"));
        assertEquals(1, payload.get("attempt"));
        assertEquals(2, payload.get("maxAttempts"));
        assertEquals("frm-root", payload.get("skillFrameId"));
        assertEquals("debug_detail", payload.get("presentationHint"));
        assertEquals("skill_agent.invoke", ((Map<?, ?>) payload.get("payload")).get("operation"));
    }

    @Test
    void resultMessageIsHandledBeforeTaskIsCompleted() throws Exception {
        String taskId = "lgt-task-4";
        String sessionId = "session-4";
        String data = """
                {
                  "type": "result",
                  "content": "done",
                  "duration_ms": 42,
                  "structured_output": {"ok": true},
                  "execution_report_ref": "frame-report://lgt-task-4/root",
                  "execution_report_digest": {"status": "COMPLETED"}
                }
                """;

        invokeHandleEvent(ServerSentEvent.<String>builder().data(data).build(), taskId, sessionId);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(sessionEventListener, taskService);
        inOrder.verify(sessionEventListener).handleMessage(org.mockito.ArgumentMatchers.argThat(message ->
                message.getType() == MessageType.TASK_COMPLETED
                        && taskId.equals(message.getTaskId())
                        && message.getPayload() instanceof Map<?, ?> payload
                        && "frame-report://lgt-task-4/root".equals(payload.get("execution_report_ref"))
        ));
        inOrder.verify(taskService).completeTask(
                taskId,
                "done",
                "{\"ok\":true}",
                42L
        );
    }

    @Test
    void streamErrorRecordsRecoverableInterruptionBeforeFailingTask() throws Exception {
        String taskId = "lgt-task-5";
        String sessionId = "session-5";

        invokeHandleStreamError(new RuntimeException("connection reset"), taskId, sessionId);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(taskService, sessionEventListener);
        inOrder.verify(taskService).recordTaskInterruption(taskId, "stream_error", "connection reset");
        inOrder.verify(taskService).failTask(taskId, "Stream error: connection reset");
        inOrder.verify(sessionEventListener).handleMessage(org.mockito.ArgumentMatchers.argThat(message ->
                message.getType() == MessageType.ERROR
                        && taskId.equals(message.getTaskId())
        ));
    }

    @Test
    void workerErrorRecordsProjectionWithoutCallingWorkerAgain() throws Exception {
        String taskId = "lgt-task-worker-error";
        String sessionId = "session-worker-error";
        String data = """
                {
                  "type": "error",
                  "reason": "llm_retry_exhausted",
                  "error": "LLM request timed out"
                }
                """;

        invokeHandleEvent(ServerSentEvent.<String>builder().data(data).build(), taskId, sessionId);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(taskService, sessionEventListener);
        inOrder.verify(taskService).recordTaskInterruptionProjection(
                taskId,
                "llm_retry_exhausted",
                "LLM request timed out"
        );
        inOrder.verify(sessionEventListener).handleMessage(org.mockito.ArgumentMatchers.argThat(message ->
                message.getType() == MessageType.ERROR
                        && taskId.equals(message.getTaskId())
        ));
        inOrder.verify(taskService).failTask(taskId, "LLM request timed out");
    }


    @Test
    void resolveLlmConfigBuildsWorkerRequestConfigFromModelConfig() throws Exception {
        LlmModelConfigDTO model = new LlmModelConfigDTO();
        model.setId("cfg-e2e");
        model.setBaseUrl("http://mock-llm:8000");
        model.setModelName("navigator-e2e-scripted");
        model.setEnvVars(Map.of("NAVI_LLM_PROVIDER", "openai"));
        when(llmModelManager.getModelConfig("cfg-e2e")).thenReturn(Optional.of(model));
        when(llmModelManager.getDecryptedApiKey("cfg-e2e")).thenReturn("mock-key");

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) invokeResolveLlmConfig("cfg-e2e", "worker-1");

        assertEquals("cfg-e2e", config.get("model_config_id"));
        assertEquals("openai", config.get("provider"));
        assertEquals("http://mock-llm:8000", config.get("base_url"));
        assertEquals("navigator-e2e-scripted", config.get("model"));
        assertEquals("mock-key", config.get("api_key"));
    }

    @Test
    void resolveVisionLlmConfigReturnsNullWhenVisionModelIdIsBlank() throws Exception {
        assertNull(invokeResolveVisionLlmConfig(" ", "worker-1"));
    }

    @Test
    void resolveVisionLlmConfigRethrowsConfiguredVisionModelFailure() throws Exception {
        when(llmModelManager.getModelConfig("vision-missing")).thenReturn(Optional.empty());

        InvocationTargetException ex = assertThrows(
                InvocationTargetException.class,
                () -> invokeResolveVisionLlmConfig("vision-missing", "worker-1")
        );

        assertEquals("Model config not found: vision-missing", ex.getCause().getMessage());
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

    private Object invokeResolveLlmConfig(String modelConfigId, String workerId) throws Exception {
        Method method = LanggraphStreamRelay.class.getDeclaredMethod(
                "resolveLlmConfig",
                String.class,
                String.class
        );
        method.setAccessible(true);
        return method.invoke(relay, modelConfigId, workerId);
    }

    private Object invokeResolveVisionLlmConfig(String modelConfigId, String workerId) throws Exception {
        Method method = LanggraphStreamRelay.class.getDeclaredMethod(
                "resolveVisionLlmConfig",
                String.class,
                String.class
        );
        method.setAccessible(true);
        return method.invoke(relay, modelConfigId, workerId);
    }

    private void invokeHandleStreamError(Throwable error, String taskId, String sessionId) throws Exception {
        Method method = LanggraphStreamRelay.class.getDeclaredMethod(
                "handleStreamError",
                Throwable.class,
                String.class,
                String.class
        );
        method.setAccessible(true);
        method.invoke(relay, error, taskId, sessionId);
    }
}
