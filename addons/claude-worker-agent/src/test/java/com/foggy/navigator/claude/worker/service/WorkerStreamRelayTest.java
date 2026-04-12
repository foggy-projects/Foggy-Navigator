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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerStreamRelayTest {

    private ClaudeTaskRepository taskRepository;
    private ClaudeWorkerService workerService;
    private ClaudeTaskService taskService;
    private ClaudeWorkerClient client;
    private ApplicationEventPublisher eventPublisher;
    private WorkerStreamRelay relay;

    @BeforeEach
    void setUp() {
        taskRepository = mock(ClaudeTaskRepository.class);
        workerService = mock(ClaudeWorkerService.class);
        taskService = mock(ClaudeTaskService.class);
        client = mock(ClaudeWorkerClient.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        relay = new WorkerStreamRelay(
                workerService,
                taskService,
                taskRepository,
                mock(WorkingDirectoryRepository.class),
                mock(ConversationConfigService.class),
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
        entity.setLastAckedSeq(7);

        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-1");

        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(workerService.getWorkerEntity("worker-1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
        when(client.subscribeToTask("worker-task-9", 7)).thenReturn(Flux.never());
        when(taskService.resolveWorkerTaskLookupId(entity)).thenReturn("worker-task-9");

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client).subscribeToTask("worker-task-9", 7);
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
}
