package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexStreamRelayTest {

    private CodexTaskRepository taskRepository;
    private CodexWorkerClientFactory clientFactory;
    private ClaudeWorkerFacade claudeWorkerFacade;
    private CodexWorkerClient client;
    private CodexStreamRelay relay;

    @BeforeEach
    void setUp() {
        taskRepository = mock(CodexTaskRepository.class);
        clientFactory = mock(CodexWorkerClientFactory.class);
        claudeWorkerFacade = mock(ClaudeWorkerFacade.class);
        client = mock(CodexWorkerClient.class);

        relay = new CodexStreamRelay(
                claudeWorkerFacade,
                clientFactory,
                mock(CodexTaskService.class),
                taskRepository,
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
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId("local-task-1");
        entity.setWorkerId("worker-1");
        entity.setSessionId("session-1");
        entity.setWorkerTaskId("worker-task-9");
        entity.setCodexThreadId("thread-1");
        entity.setLastAckedSeq(7);

        when(taskRepository.findByTaskId("local-task-1")).thenReturn(Optional.of(entity));
        when(claudeWorkerFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:3051")
                        .authToken("worker-token")
                        .build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://localhost:3051", "worker-token"))
                .thenReturn(client);
        when(client.subscribeToTask("worker-task-9", 7)).thenReturn(Flux.never());

        relay.reconnectTask("local-task-1", "session-1", "worker-1");

        verify(client).subscribeToTask("worker-task-9", 7);
    }
}
