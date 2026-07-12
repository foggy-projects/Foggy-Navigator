package com.foggy.navigator.gemini.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.common.model.GeminiConfig;
import com.foggy.navigator.gemini.worker.client.GeminiWorkerClient;
import com.foggy.navigator.gemini.worker.client.GeminiWorkerClientFactory;
import com.foggy.navigator.gemini.worker.model.entity.GeminiTaskEntity;
import com.foggy.navigator.gemini.worker.repository.GeminiTaskRepository;
import com.foggy.navigator.session.event.SessionEventListener;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiStreamRelayTest {

    private GeminiTaskService taskService;
    private WorkerManagementFacade workerManagementFacade;
    private GeminiWorkerClientFactory clientFactory;
    private GeminiTaskRepository taskRepository;
    private SessionEventListener sessionEventListener;
    private ApplicationEventPublisher eventPublisher;
    private ObjectMapper objectMapper;
    private GeminiStreamRelay relay;

    @BeforeEach
    void setUp() {
        taskService = mock(GeminiTaskService.class);
        workerManagementFacade = mock(WorkerManagementFacade.class);
        clientFactory = mock(GeminiWorkerClientFactory.class);
        taskRepository = mock(GeminiTaskRepository.class);
        sessionEventListener = mock(SessionEventListener.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        objectMapper = new ObjectMapper();
        relay = new GeminiStreamRelay(
                workerManagementFacade,
                clientFactory,
                taskService,
                taskRepository,
                sessionEventListener,
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
        assertEquals(null, resultPayload.get("isResult"));

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
        event.setSeq(12);

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
        assertEquals("gemini-event:local-task-2:12:event", message.getMessageId());
        verify(sessionEventListener).handleMessageDurably(message);
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
                5,
                true
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

    @Test
    void sequencedToolResultDoesNotAdvanceAckWhenDurablePersistenceFails() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("tool_result");
        event.setSeq(9);
        event.setTaskId("worker-task-9");
        event.setToolUseId("tool-9");
        event.setTool("read_file");
        event.setOutput("payload");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(objectMapper.writeValueAsString(event))
                .build();
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(sessionEventListener).handleMessageDurably(any(AgentMessage.class));

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () ->
                invokeHandleSseEvent(sse, "local-task-9", "session-9",
                        new AtomicReference<>(), new AtomicReference<>()));

        assertInstanceOf(RuntimeException.class, thrown.getCause());
        verify(taskService).rememberWorkerIdentity("local-task-9", "worker-task-9", null, null);
        verify(taskService, never()).recordWorkerProgress(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void legacyToolResultIsDurableBeforeCounterProgressAndKeepsStableIdentity() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("tool_result");
        event.setTaskId("worker-task-legacy");
        event.setToolUseId("tool-legacy");
        event.setTool("shell");
        event.setOutput("large legacy output");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(objectMapper.writeValueAsString(event))
                .build();

        invokeHandleSseEvent(sse, "local-task-legacy", "session-legacy",
                new AtomicReference<>(), new AtomicReference<>());

        ArgumentCaptor<AgentMessage> durableCaptor = ArgumentCaptor.forClass(AgentMessage.class);
        InOrder durableBeforeProgress = inOrder(sessionEventListener, taskService);
        durableBeforeProgress.verify(sessionEventListener).handleMessageDurably(durableCaptor.capture());
        durableBeforeProgress.verify(taskService).recordWorkerProgress(
                "local-task-legacy", "worker-task-legacy", null, null, 1, true);
        assertEquals("gm-lt:local-task-legacy:tool-legacy", durableCaptor.getValue().getMessageId());
        assertEquals(1, acknowledgedSequences().get("local-task-legacy").get());
    }

    @Test
    void legacyToolResultDoesNotAdvanceCounterWhenDurablePersistenceFails() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("tool_result");
        event.setTaskId("worker-task-legacy-failure");
        event.setToolUseId("tool-legacy-failure");
        event.setTool("shell");
        event.setOutput("payload");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(objectMapper.writeValueAsString(event))
                .build();
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(sessionEventListener).handleMessageDurably(any(AgentMessage.class));

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () ->
                invokeHandleSseEvent(sse, "local-task-legacy-failure", "session-legacy",
                        new AtomicReference<>(), new AtomicReference<>()));

        assertInstanceOf(RuntimeException.class, thrown.getCause());
        verify(taskService, never()).recordWorkerProgress(any(), any(), any(), any(), any(), anyBoolean());
        assertEquals(0, acknowledgedSequences().get("local-task-legacy-failure").get());
    }

    @Test
    void replayedLegacyToolResultUsesTheSamePayloadIdentity() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("tool_result");
        event.setTaskId("worker-task-legacy-replay");
        event.setToolUseId("tool-legacy-replay");
        event.setTool("shell");
        event.setOutput("same payload");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(objectMapper.writeValueAsString(event))
                .build();

        invokeHandleSseEvent(sse, "local-task-legacy-replay", "session-legacy",
                new AtomicReference<>(), new AtomicReference<>());
        invokeHandleSseEvent(sse, "local-task-legacy-replay", "session-legacy",
                new AtomicReference<>(), new AtomicReference<>());

        ArgumentCaptor<AgentMessage> messages = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener, times(2)).handleMessageDurably(messages.capture());
        assertEquals(messages.getAllValues().get(0).getMessageId(), messages.getAllValues().get(1).getMessageId());
    }

    @Test
    void initialStreamDurablePersistenceFailureDoesNotFailTaskOrAdvanceAck() throws Exception {
        WorkerEvent workerEvent = new WorkerEvent();
        workerEvent.setType("tool_result");
        workerEvent.setSeq(13);
        workerEvent.setTaskId("worker-task-13");
        workerEvent.setToolUseId("tool-13");
        workerEvent.setTool("read_file");
        workerEvent.setOutput("payload");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(objectMapper.writeValueAsString(workerEvent))
                .build();

        GeminiConfig config = GeminiConfig.builder().baseUrl("http://gemini-worker").build();
        GeminiWorkerClient client = mock(GeminiWorkerClient.class);
        when(workerManagementFacade.getGeminiConfig("worker-13")).thenReturn(config);
        when(clientFactory.getOrCreate(eq("worker-13:gemini"), eq("http://gemini-worker"), any()))
                .thenReturn(client);
        when(client.streamQuery(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.just(sse));
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(sessionEventListener).handleMessageDurably(any(AgentMessage.class));

        relay.onTaskStart(WorkerTaskStartEvent.builder()
                .taskId("local-task-13")
                .sessionId("session-13")
                .workerId("worker-13")
                .prompt("prompt")
                .providerConfig(Map.of())
                .build());

        verify(sessionEventListener).handleMessageDurably(any(AgentMessage.class));
        verify(taskService).rememberWorkerIdentity("local-task-13", "worker-task-13", null, null);
        verify(taskService, never()).recordWorkerProgress(any(), any(), any(), any(), any(), anyBoolean());
        verify(taskService, never()).failTask(any(), any(), any(), any());
    }

    @Test
    void sequencedResultKeepsReplayCursorWhenAtomicTerminalAckFails() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("result");
        event.setSeq(17);
        event.setTaskId("worker-task-17");
        event.setSessionId("gemini-session-17");
        event.setResult("final result");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(objectMapper.writeValueAsString(event))
                .build();
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(taskService).completeTask("local-task-17", "worker-task-17", "gemini-session-17",
                        "final result", null, null, null, null, null, null, 17);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () ->
                invokeHandleSseEvent(sse, "local-task-17", "session-17",
                        new AtomicReference<>(), new AtomicReference<>()));

        assertInstanceOf(RuntimeException.class, thrown.getCause());
        verify(taskService).completeTask("local-task-17", "worker-task-17", "gemini-session-17",
                "final result", null, null, null, null, null, null, 17);
        verify(taskService, never()).recordWorkerProgress(any(), any(), any(), any(), any(), anyBoolean());
        assertEquals(0, acknowledgedSequences().get("local-task-17").get());
    }

    @Test
    void sequencedErrorKeepsReplayCursorWhenAtomicTerminalAckFails() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("error");
        event.setSeq(18);
        event.setTaskId("worker-task-18");
        event.setSessionId("gemini-session-18");
        event.setError("worker failed");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(objectMapper.writeValueAsString(event))
                .build();
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(taskService).failTask("local-task-18", "worker-task-18", "gemini-session-18",
                        "worker failed", 18);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () ->
                invokeHandleSseEvent(sse, "local-task-18", "session-18",
                        new AtomicReference<>(), new AtomicReference<>()));

        assertInstanceOf(RuntimeException.class, thrown.getCause());
        verify(taskService).failTask("local-task-18", "worker-task-18", "gemini-session-18",
                "worker failed", 18);
        verify(taskService, never()).recordWorkerProgress(any(), any(), any(), any(), any(), anyBoolean());
        assertEquals(0, acknowledgedSequences().get("local-task-18").get());
    }

    @Test
    void reconnectTaskReplaysFromPersistedIdentityAndAck() {
        GeminiTaskEntity task = new GeminiTaskEntity();
        task.setStatus("RUNNING");
        task.setWorkerTaskId("worker-task-21");
        task.setLastAckedSeq(7);
        task.setGeminiSessionId("gemini-session-21");
        GeminiConfig config = GeminiConfig.builder().baseUrl("http://gemini-worker").build();
        GeminiWorkerClient client = mock(GeminiWorkerClient.class);
        when(taskRepository.findByTaskId("local-task-21")).thenReturn(Optional.of(task));
        when(workerManagementFacade.getGeminiConfig("worker-21")).thenReturn(config);
        when(clientFactory.getOrCreate(eq("worker-21:gemini"), eq("http://gemini-worker"), any()))
                .thenReturn(client);
        when(client.subscribeToTask("worker-task-21", 7)).thenReturn(Flux.never());

        relay.reconnectTask("local-task-21", "session-21", "worker-21");

        verify(client).subscribeToTask("worker-task-21", 7);
        relay.abortStream("local-task-21");
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

    @SuppressWarnings("unchecked")
    private Map<String, AtomicInteger> acknowledgedSequences() {
        return (Map<String, AtomicInteger>) ReflectionTestUtils.getField(relay, "lastAckedSeq");
    }
}
