package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudeTaskServiceRewindTest {

    private static final String TASK_ID = "task-rw-001";
    private static final String SESSION_ID = "session-rw-001";
    private static final String CLAUDE_SESSION_ID = "claude-session-rw-001";
    private static final String WORKER_ID = "worker-rw-001";
    private static final String USER_ID = "user-rw-001";

    private ClaudeTaskService service;
    private ClaudeTaskRepository taskRepository;
    private SessionManager sessionManager;
    private ClaudeWorkerService workerService;
    private SessionEntityRepository sessionEntityRepository;

    @BeforeEach
    void setUp() {
        taskRepository = mock(ClaudeTaskRepository.class);
        sessionManager = mock(SessionManager.class);
        workerService = mock(ClaudeWorkerService.class);
        sessionEntityRepository = mock(SessionEntityRepository.class);

        var agentTeamsConfigService = mock(AgentTeamsConfigService.class);
        var directoryService = mock(WorkingDirectoryService.class);
        var workingDirectoryRepository = mock(WorkingDirectoryRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var llmModelManager = mock(LlmModelManager.class);
        var userAuthService = mock(UserAuthService.class);
        var credentialEncryptor = mock(com.foggy.navigator.common.security.CredentialEncryptor.class);
        var txTemplate = mock(TransactionTemplate.class);

        var codingAgentRepository = mock(com.foggy.navigator.claude.worker.repository.CodingAgentRepository.class);
        service = new ClaudeTaskService(
                taskRepository,
                workerService,
                agentTeamsConfigService,
                codingAgentRepository,
                directoryService,
                workingDirectoryRepository,
                sessionManager,
                publisher,
                llmModelManager,
                userAuthService,
                credentialEncryptor,
                txTemplate
        );
        ReflectionTestUtils.setField(service, "sessionEntityRepository", sessionEntityRepository);
    }

    @Test
    void rewindTask_conversationFork_returnsPromptAndTruncatesMessages() {
        ClaudeTaskEntity task = createCompletedTask();
        ClaudeWorkerEntity worker = createWorkerEntity();
        ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);

        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(task));
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
        when(client.rewindConversation(CLAUDE_SESSION_ID, 2))
                .thenReturn(reactor.core.publisher.Mono.just(Map.of(
                        "status", "rewound",
                        "user_prompt", "Generate a file"
                )));
        when(sessionManager.truncateMessagesFromTurn(SESSION_ID, 2)).thenReturn(5);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) service.rewindTask(
                TASK_ID, USER_ID, Map.of("mode", "conversation_fork", "turnIndex", 2));

        assertEquals("rewound", result.get("status"));
        assertEquals(TASK_ID, result.get("taskId"));
        assertEquals("Generate a file", result.get("userPrompt"));
        assertEquals(2, result.get("turnIndex"));
        assertEquals(CLAUDE_SESSION_ID, result.get("claudeSessionId"));
        verify(client).rewindConversation(CLAUDE_SESSION_ID, 2);
        verify(sessionManager).truncateMessagesFromTurn(SESSION_ID, 2);
    }

    @Test
    void rewindTask_fileRewind_rewindsFilesAndConversation() {
        ClaudeTaskEntity task = createCompletedTask();
        ClaudeWorkerEntity worker = createWorkerEntity();
        ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);

        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(task));
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
        when(client.rewindFiles(CLAUDE_SESSION_ID, "cp-1", "D:\\projects"))
                .thenReturn(reactor.core.publisher.Mono.just(Map.of("status", "rewound")));
        when(client.rewindConversation(CLAUDE_SESSION_ID, 2))
                .thenReturn(reactor.core.publisher.Mono.just(Map.of(
                        "status", "rewound",
                        "user_prompt", "Original prompt"
                )));
        when(sessionManager.truncateMessagesFromTurn(SESSION_ID, 2)).thenReturn(8);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) service.rewindTask(
                TASK_ID, USER_ID, Map.of(
                        "mode", "file_rewind",
                        "checkpointId", "cp-1",
                        "turnIndex", 2
                ));

        assertEquals("rewound", result.get("status"));
        assertEquals("cp-1", result.get("checkpointId"));
        assertEquals("Original prompt", result.get("userPrompt"));
        verify(client).rewindFiles(CLAUDE_SESSION_ID, "cp-1", "D:\\projects");
        verify(client).rewindConversation(CLAUDE_SESSION_ID, 2);
        verify(sessionManager).truncateMessagesFromTurn(SESSION_ID, 2);
    }

    @Test
    void rewindTask_firstTurnSessionCleared_removesClaudeSessionIdFromProviderState() {
        ClaudeTaskEntity task = createCompletedTask();
        ClaudeWorkerEntity worker = createWorkerEntity();
        ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setProviderType("claude-worker");
        session.setProviderStateJson(ProviderStateCodec.mergeSessionValues(
                "{\"other\":\"keep\"}",
                "claude-worker",
                Map.of(
                        ProviderStateCodec.FIELD_CLAUDE_SESSION_ID, CLAUDE_SESSION_ID,
                        ProviderStateCodec.FIELD_AGENT_TEAMS_CONFIG_ID, "teams-1"
                )));

        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(task));
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
        when(client.rewindFiles(CLAUDE_SESSION_ID, "cp-1", "D:\\projects"))
                .thenReturn(reactor.core.publisher.Mono.just(Map.of("status", "rewound")));
        when(client.rewindConversation(CLAUDE_SESSION_ID, 1))
                .thenReturn(reactor.core.publisher.Mono.just(Map.of(
                        "status", "rewound",
                        "session_cleared", true
                )));
        when(sessionManager.truncateMessagesFromTurn(SESSION_ID, 1)).thenReturn(4);
        when(sessionEntityRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionEntityRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) service.rewindTask(
                TASK_ID, USER_ID, Map.of(
                        "mode", "file_rewind",
                        "checkpointId", "cp-1",
                        "turnIndex", 1
                ));

        assertNull(result.get("claudeSessionId"));
        Map<String, Object> state = ProviderStateCodec.parseObject(session.getProviderStateJson());
        assertFalse(state.containsKey(ProviderStateCodec.FIELD_CLAUDE_SESSION_ID));
        assertEquals("teams-1", state.get(ProviderStateCodec.FIELD_AGENT_TEAMS_CONFIG_ID));
        assertEquals("keep", state.get("other"));
        assertEquals(ProviderStateCodec.CURRENT_SCHEMA_VERSION, state.get(ProviderStateCodec.FIELD_SCHEMA_VERSION));
        assertEquals("claude-worker", state.get(ProviderStateCodec.FIELD_PROVIDER_TYPE));
        verify(sessionEntityRepository).save(session);
    }

    @Test
    void rewindTask_rejectsRunningTask() {
        ClaudeTaskEntity task = createTask("RUNNING");
        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(task));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.rewindTask(TASK_ID, USER_ID, Map.of("mode", "conversation_fork", "turnIndex", 1)));

        assertEquals("Cannot rewind a running task", ex.getMessage());
    }

    @Test
    void rewindTask_requiresCheckpointIdForFileRewind() {
        ClaudeTaskEntity task = createCompletedTask();
        ClaudeWorkerEntity worker = createWorkerEntity();
        ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);

        when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(task));
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.rewindTask(TASK_ID, USER_ID, Map.of("mode", "file_rewind", "turnIndex", 1)));

        assertEquals("checkpointId is required for file_rewind mode", ex.getMessage());
    }

    private ClaudeTaskEntity createCompletedTask() {
        return createTask("COMPLETED");
    }

    private ClaudeTaskEntity createTask(String status) {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId(TASK_ID);
        entity.setSessionId(SESSION_ID);
        entity.setClaudeSessionId(CLAUDE_SESSION_ID);
        entity.setWorkerId(WORKER_ID);
        entity.setUserId(USER_ID);
        entity.setStatus(status);
        entity.setCwd("D:\\projects");
        return entity;
    }

    private ClaudeWorkerEntity createWorkerEntity() {
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId(WORKER_ID);
        worker.setUserId(USER_ID);
        worker.setStatus("ONLINE");
        return worker;
    }
}
