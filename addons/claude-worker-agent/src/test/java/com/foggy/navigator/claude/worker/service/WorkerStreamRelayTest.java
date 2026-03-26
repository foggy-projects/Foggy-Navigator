package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerStreamRelayTest {

    private ClaudeTaskRepository taskRepository;
    private ClaudeWorkerService workerService;
    private ClaudeTaskService taskService;
    private ClaudeWorkerClient client;
    private WorkerStreamRelay relay;

    @BeforeEach
    void setUp() {
        taskRepository = mock(ClaudeTaskRepository.class);
        workerService = mock(ClaudeWorkerService.class);
        taskService = mock(ClaudeTaskService.class);
        client = mock(ClaudeWorkerClient.class);

        relay = new WorkerStreamRelay(
                workerService,
                taskService,
                taskRepository,
                mock(WorkingDirectoryRepository.class),
                mock(ConversationConfigService.class),
                mock(ApplicationEventPublisher.class),
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
}
