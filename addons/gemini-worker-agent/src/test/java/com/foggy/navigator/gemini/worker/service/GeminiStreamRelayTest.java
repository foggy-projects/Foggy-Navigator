package com.foggy.navigator.gemini.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.gemini.worker.client.GeminiWorkerClientFactory;
import com.foggy.navigator.gemini.worker.repository.GeminiTaskRepository;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.codec.ServerSentEvent;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GeminiStreamRelayTest {

    private GeminiTaskService taskService;
    private ApplicationEventPublisher eventPublisher;
    private ObjectMapper objectMapper;
    private GeminiStreamRelay relay;

    @BeforeEach
    void setUp() {
        taskService = mock(GeminiTaskService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        objectMapper = new ObjectMapper();
        relay = new GeminiStreamRelay(
                mock(WorkerManagementFacade.class),
                mock(GeminiWorkerClientFactory.class),
                taskService,
                mock(GeminiTaskRepository.class),
                eventPublisher,
                objectMapper
        );
    }

    @Test
    void relayWorkerEventPublishesSessionEndAndCompletesTaskUsingResultFallback() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("result");
        event.setTaskId("worker-task-1");
        event.setResult("SESSION_ONE");
        event.setCostUsd(new BigDecimal("0.12"));
        event.setInputTokens(11L);
        event.setOutputTokens(7L);
        event.setDurationMs(99L);
        event.setNumTurns(2);
        event.setModel("gemini-2.5-flash-lite");

        invokeRelayWorkerEvent("session-1", "local-task-1", event, "gemini-session-1");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());

        List<Object> publishedEvents = captor.getAllValues();
        AgentMessage resultMessage = assertInstanceOf(AgentMessage.class, publishedEvents.get(0));
        assertEquals(MessageType.TEXT_COMPLETE, resultMessage.getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> resultPayload = (Map<String, Object>) resultMessage.getPayload();
        assertEquals("SESSION_ONE", resultPayload.get("content"));
        assertEquals(Boolean.TRUE, resultPayload.get("isResult"));

        AgentMessage message = assertInstanceOf(AgentMessage.class, publishedEvents.get(1));
        assertEquals(MessageType.SESSION_END, message.getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("SESSION_ONE", payload.get("content"));
        assertEquals(Boolean.TRUE, payload.get("isResult"));
        assertEquals("gemini-session-1", payload.get("geminiSessionId"));
        assertEquals("gemini-2.5-flash-lite", payload.get("model"));
        assertEquals(11L, payload.get("inputTokens"));
        assertEquals(7L, payload.get("outputTokens"));
        assertEquals(99L, payload.get("durationMs"));

        verify(taskService).completeTask(
                "local-task-1",
                "worker-task-1",
                "gemini-session-1",
                "SESSION_ONE",
                new BigDecimal("0.12"),
                11L,
                7L,
                99L,
                2,
                "gemini-2.5-flash-lite"
        );
    }

    @Test
    void relayWorkerEventPublishesToolResultWithSuccessFlag() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("tool_result");
        event.setTool("read_file");
        event.setToolUseId("tool-123");
        event.setOutput("{\"ok\":true}");
        event.setIsError(false);

        invokeRelayWorkerEvent("session-2", "local-task-2", event, "gemini-session-2");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        AgentMessage message = assertInstanceOf(AgentMessage.class, captor.getValue());
        assertEquals(MessageType.TOOL_CALL_RESULT, message.getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("tool-123", payload.get("toolCallId"));
        assertEquals("read_file", payload.get("toolName"));
        assertEquals("{\"ok\":true}", payload.get("data"));
        assertEquals(Boolean.TRUE, payload.get("success"));
        assertEquals("gemini-session-2", payload.get("geminiSessionId"));
    }

    @Test
    void relayWorkerEventPublishesAssistantTextAsStreamChunk() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("assistant_text");
        event.setContent("partial text");

        invokeRelayWorkerEvent("session-3", "local-task-3", event, "gemini-session-3");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        AgentMessage message = assertInstanceOf(AgentMessage.class, captor.getValue());
        assertEquals(MessageType.TEXT_CHUNK, message.getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("partial text", payload.get("content"));
        assertEquals("gemini-session-3", payload.get("geminiSessionId"));
    }

    @Test
    void handleSseEventUpdatesDetectedSessionIdFromEventDataAndRecordsProgress() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("assistant_text");
        event.setTaskId("worker-task-2");
        event.setContent("OK");
        event.setSeq(5);
        event.setModel("gemini-2.5-flash-lite");
        event.setData(Map.of("geminiSessionId", "gemini-session-from-data"));

        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(objectMapper.writeValueAsString(event))
                .build();
        AtomicReference<String> detectedModel = new AtomicReference<>();
        AtomicReference<String> detectedSessionId = new AtomicReference<>();

        invokeHandleSseEvent(sse, "local-task-3", "session-3", detectedModel, detectedSessionId);

        assertEquals("gemini-session-from-data", detectedSessionId.get());
        assertEquals("gemini-2.5-flash-lite", detectedModel.get());
        verify(taskService).recordWorkerProgress(
                "local-task-3",
                "worker-task-2",
                "gemini-session-from-data",
                "gemini-2.5-flash-lite",
                5
        );

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        AgentMessage message = assertInstanceOf(AgentMessage.class, captor.getValue());
        assertEquals(MessageType.TEXT_CHUNK, message.getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("OK", payload.get("content"));
        assertEquals("gemini-session-from-data", payload.get("geminiSessionId"));
    }

    private void invokeRelayWorkerEvent(String sessionId, String taskId, WorkerEvent event, String geminiSessionId)
            throws Exception {
        clearInvocations(eventPublisher, taskService);
        Method method = GeminiStreamRelay.class.getDeclaredMethod(
                "relayWorkerEvent",
                String.class,
                String.class,
                WorkerEvent.class,
                String.class
        );
        method.setAccessible(true);
        method.invoke(relay, sessionId, taskId, event, geminiSessionId);
    }

    private void invokeHandleSseEvent(ServerSentEvent<String> sse, String taskId, String sessionId,
                                      AtomicReference<String> detectedModel,
                                      AtomicReference<String> detectedSessionId) throws Exception {
        clearInvocations(eventPublisher, taskService);
        Method method = GeminiStreamRelay.class.getDeclaredMethod(
                "handleSseEvent",
                ServerSentEvent.class,
                String.class,
                String.class,
                AtomicReference.class,
                AtomicReference.class
        );
        method.setAccessible(true);
        method.invoke(relay, sse, taskId, sessionId, detectedModel, detectedSessionId);
    }
}
