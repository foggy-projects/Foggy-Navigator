package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.session.event.SessionEventListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.codec.ServerSentEvent;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static reactor.core.publisher.Mono.just;

class WorkerStreamRelayTest {

    private ClaudeTaskRepository taskRepository;
    private ClaudeWorkerService workerService;
    private ClaudeTaskService taskService;
    private ClaudeWorkerClient client;
    private SessionEventListener sessionEventListener;
    private ApplicationEventPublisher eventPublisher;
    private WorkerStreamRelay relay;

    @BeforeEach
    void setUp() {
        taskRepository = mock(ClaudeTaskRepository.class);
        workerService = mock(ClaudeWorkerService.class);
        taskService = mock(ClaudeTaskService.class);
        client = mock(ClaudeWorkerClient.class);
        sessionEventListener = mock(SessionEventListener.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        relay = new WorkerStreamRelay(
                workerService,
                taskService,
                taskRepository,
                mock(WorkingDirectoryRepository.class),
                mock(ConversationConfigService.class),
                sessionEventListener,
                eventPublisher,
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        relay.abortStream("local-task-1");
    }

    @Test
    void reconnectTaskUsesPersistedWorkerTaskIdAndAckSeq() {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setWorkerId("worker-1");
        entity.setSessionId("session-1");
        entity.setWorkerTaskId("worker-task-9");
        entity.setClaudeSessionId("claude-session-1");
        entity.setStatus("RUNNING");
        entity.setLastAckedSeq(7);

        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-1");

        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(workerService.getWorkerEntity("worker-1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
        when(client.getTaskStatus("worker-task-9")).thenReturn(just(Map.of("latest_seq", 9, "closed", false)));
        when(client.subscribeToTask("worker-task-9", 7)).thenReturn(Flux.never());
        when(taskService.resolveWorkerTaskLookupId(entity)).thenReturn("worker-task-9");

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client).subscribeToTask("worker-task-9", 7);
    }

    @Test
    void reconnectTaskSkipsWhenWorkerStreamAlreadyClosedAndAligned() {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setWorkerId("worker-1");
        entity.setSessionId("session-1");
        entity.setWorkerTaskId("worker-task-9");
        entity.setStatus("RUNNING");
        entity.setLastAckedSeq(7);

        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-1");

        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(workerService.getWorkerEntity("worker-1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
        when(taskService.resolveWorkerTaskLookupId(entity)).thenReturn("worker-task-9");
        when(client.getTaskStatus("worker-task-9")).thenReturn(just(Map.of("latest_seq", 7, "closed", true)));

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client, never()).subscribeToTask(anyString(), anyInt());
    }

    @Test
    void getWorkerTaskIdFallsBackToPersistedValue() {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setWorkerTaskId("worker-task-9");

        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(taskService.resolveWorkerTaskLookupId(entity)).thenReturn("worker-task-9");

        assertEquals("worker-task-9", relay.getWorkerTaskId("local-task-1"));
    }

    @Test
    void relayEventMapsUnknownSystemSubtypeToStateSync() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("system");
        event.setSubtype("heartbeat_warning");
        event.setContent("Still processing");
        event.setSessionId("claude-session-2");

        invokeRelayEvent("session-1", "local-task-1", event);

        verify(taskService).updateClaudeSessionId("local-task-1", "claude-session-2");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        AgentMessage message = assertInstanceOf(AgentMessage.class, captor.getValue());
        assertEquals(MessageType.STATE_SYNC, message.getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("Still processing", payload.get("content"));
        assertEquals("heartbeat_warning", payload.get("subtype"));
        assertEquals("local-task-1", payload.get("taskId"));
        assertEquals("claude-session-2", payload.get("claudeSessionId"));
    }

    @Test
    void relayEventKeepsWaitingSubtypeAsStateSync() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("system");
        event.setSubtype("waiting");
        event.setSessionId("claude-session-3");
        event.setData(Map.of("elapsed_seconds", 30, "timeout_seconds", 600));

        invokeRelayEvent("session-2", "local-task-2", event);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        AgentMessage message = assertInstanceOf(AgentMessage.class, captor.getValue());
        assertEquals(MessageType.STATE_SYNC, message.getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("waiting", payload.get("subtype"));
        assertEquals(30, payload.get("elapsedSeconds"));
        assertEquals(600, payload.get("timeoutSeconds"));
    }

    @Test
    void sequencedToolResultUsesReplayStableMessageIdAndSynchronousPersistence() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("tool_result");
        event.setSeq(42);
        event.setToolUseId("tool-42");
        event.setTool("read_file");
        event.setOutput("large result");

        invokeRelayEvent("session-42", "local-task-42", event);

        ArgumentCaptor<AgentMessage> durableCaptor = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener).handleMessageDurably(durableCaptor.capture());
        AgentMessage durable = durableCaptor.getValue();
        assertEquals("claude-event:local-task-42:42", durable.getMessageId());
        assertEquals(MessageType.TOOL_CALL_RESULT, durable.getType());

        ArgumentCaptor<Object> publishedCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(publishedCaptor.capture());
        AgentMessage published = assertInstanceOf(AgentMessage.class, publishedCaptor.getValue());
        assertEquals(durable.getMessageId(), published.getMessageId());
    }

    @Test
    void sequencedToolResultDoesNotAdvanceAckWhenDurablePersistenceFails() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("tool_result");
        event.setSeq(43);
        event.setTaskId("worker-task-43");
        event.setToolUseId("tool-43");
        event.setTool("read_file");
        event.setOutput("payload");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(new ObjectMapper().writeValueAsString(event))
                .build();
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(sessionEventListener).handleMessageDurably(any(AgentMessage.class));

        invokeSubscribeSseFlux(sse, "local-task-43", "session-43", "worker-43");

        verifyNoInteractions(taskService);
        assertEquals(0, relay.getLastAckedSeq("local-task-43").get());
    }

    @Test
    void legacyToolResultIsDurableBeforeItsCounterProgressAndKeepsStableIdentity() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("tool_result");
        event.setTaskId("worker-task-legacy");
        event.setToolUseId("tool-legacy");
        event.setTool("shell");
        event.setOutput("large legacy output");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(new ObjectMapper().writeValueAsString(event))
                .build();

        invokeSubscribeSseFlux(sse, "local-task-legacy", "session-legacy", "worker-legacy");

        ArgumentCaptor<AgentMessage> durableCaptor = ArgumentCaptor.forClass(AgentMessage.class);
        InOrder durableBeforeProgress = inOrder(sessionEventListener, taskService);
        durableBeforeProgress.verify(sessionEventListener).handleMessageDurably(durableCaptor.capture());
        durableBeforeProgress.verify(taskService).recordWorkerProgress(
                "local-task-legacy", "worker-task-legacy", null, null, 1, true);
        assertEquals("cl-lt:local-task-legacy:tool-legacy", durableCaptor.getValue().getMessageId());
        assertEquals(1, relay.getLastAckedSeq("local-task-legacy").get());
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
                .data(new ObjectMapper().writeValueAsString(event))
                .build();
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(sessionEventListener).handleMessageDurably(any(AgentMessage.class));

        invokeSubscribeSseFlux(sse, "local-task-legacy-failure", "session-legacy", "worker-legacy");

        verify(taskService, never()).recordWorkerProgress(any(), any(), any(), any(), any(), anyBoolean());
        assertEquals(0, relay.getLastAckedSeq("local-task-legacy-failure").get());
    }

    @Test
    void sequencedResultKeepsReplayTrackerWhenAtomicTerminalAckFails() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("result");
        event.setSeq(44);
        event.setTaskId("worker-task-44");
        event.setSessionId("claude-session-44");
        event.setContent("final reply");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(new ObjectMapper().writeValueAsString(event))
                .build();
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(taskService).completeTask(eq("local-task-44"), eq("worker-task-44"),
                        eq("claude-session-44"), eq("final reply"), any(), any(), any(), any(), any(), any(), eq(44));

        invokeSubscribeSseFlux(sse, "local-task-44", "session-44", "worker-44");

        verify(taskService).completeTask(eq("local-task-44"), eq("worker-task-44"),
                eq("claude-session-44"), eq("final reply"), any(), any(), any(), any(), any(), any(), eq(44));
        verify(taskService, never()).recordWorkerProgress(any(), any(), any(), any(), any(), anyBoolean());
        assertEquals(0, relay.getLastAckedSeq("local-task-44").get());
    }

    @Test
    void sequencedErrorKeepsReplayTrackerWhenAtomicTerminalAckFails() throws Exception {
        WorkerEvent event = new WorkerEvent();
        event.setType("error");
        event.setSeq(45);
        event.setTaskId("worker-task-45");
        event.setSessionId("claude-session-45");
        event.setError("worker failure");
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .data(new ObjectMapper().writeValueAsString(event))
                .build();
        doThrow(new IllegalStateException("mysql unavailable"))
                .when(taskService).failTask(eq("local-task-45"), eq("worker-task-45"),
                        eq("claude-session-45"), eq("worker failure"), eq(45));

        invokeSubscribeSseFlux(sse, "local-task-45", "session-45", "worker-45");

        verify(taskService).failTask(eq("local-task-45"), eq("worker-task-45"),
                eq("claude-session-45"), eq("worker failure"), eq(45));
        verify(taskService, never()).recordWorkerProgress(any(), any(), any(), any(), any(), anyBoolean());
        assertEquals(0, relay.getLastAckedSeq("local-task-45").get());
    }

    private void invokeRelayEvent(String sessionId, String taskId, WorkerEvent event) throws Exception {
        clearInvocations(eventPublisher, taskService);
        Method method = WorkerStreamRelay.class.getDeclaredMethod(
                "relayEvent",
                String.class,
                String.class,
                WorkerEvent.class,
                AtomicReference.class,
                AtomicReference.class
        );
        method.setAccessible(true);
        method.invoke(relay, sessionId, taskId, event, new AtomicReference<String>(), new AtomicReference<String>());
    }

    private void invokeSubscribeSseFlux(ServerSentEvent<String> sse, String taskId, String sessionId, String workerId)
            throws Exception {
        clearInvocations(eventPublisher, taskService);
        Method method = WorkerStreamRelay.class.getDeclaredMethod(
                "subscribeSseFlux",
                Flux.class,
                String.class,
                String.class,
                String.class,
                AtomicReference.class,
                AtomicReference.class,
                int.class
        );
        method.setAccessible(true);
        method.invoke(relay, Flux.just(sse), taskId, sessionId, workerId,
                new AtomicReference<String>(), new AtomicReference<String>(), 0);
    }
}
