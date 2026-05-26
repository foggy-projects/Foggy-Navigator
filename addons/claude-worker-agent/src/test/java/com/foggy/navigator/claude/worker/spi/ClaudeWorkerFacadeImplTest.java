package com.foggy.navigator.claude.worker.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.WorkerStreamRelay;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaudeWorkerFacadeImplTest {

    @Mock
    private ClaudeWorkerService workerService;

    @Mock
    private ClaudeTaskService taskService;

    @Mock
    private WorkerStreamRelay streamRelay;

    @Mock
    private WorkingDirectoryRepository directoryRepository;

    @Mock
    private LlmModelManager llmModelManager;

    @Mock
    private UserAuthService userAuthService;

    @Mock
    private ClaudeWorkerClient client;

    private ClaudeWorkerFacadeImpl facade;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        facade = new ClaudeWorkerFacadeImpl(
                workerService,
                taskService,
                streamRelay,
                directoryRepository,
                llmModelManager,
                userAuthService,
                new ObjectMapper(),
                directExecutor
        );
    }

    @Test
    void syncQueryAggregatesUpstreamTaskAndMetrics() {
        mockWorker("worker-1", "user-1");
        when(client.streamQuery(eq("check repo"), eq("D:/repo"), eq("claude-session-0"),
                eq("sonnet"), eq(2), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("bypassPermissions"), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Flux.just(
                        sse("""
                                {"type":"assistant_text","task_id":"worker-task-9","session_id":"claude-session-1","content":"Hello "}
                                """),
                        sse("""
                                {"type":"assistant_text","task_id":"worker-task-9","session_id":"claude-session-1","content":"World"}
                                """),
                        sse("""
                                {"type":"result","task_id":"worker-task-9","session_id":"claude-session-1","content":"Final answer","duration_ms":9159,"input_tokens":101,"output_tokens":22,"num_turns":2,"model":"sonnet","cost_usd":0.12}
                                """)
                ));

        Map<String, Object> result = facade.syncQuery(
                "user-1", "worker-1", "check repo", "D:/repo", "claude-session-0", 2, "sonnet");

        assertEquals("worker-task-9", result.get("workerTaskId"));
        assertEquals("claude-session-1", result.get("claudeSessionId"));
        assertEquals("Final answer", result.get("resultText"));
        assertEquals("sonnet", result.get("model"));
        assertEquals(9159L, result.get("durationMs"));
        assertEquals(101L, result.get("inputTokens"));
        assertEquals(22L, result.get("outputTokens"));
        assertEquals(2, result.get("numTurns"));
        assertEquals(new BigDecimal("0.12"), result.get("costUsd"));
        assertNull(result.get("error"));
    }

    @Test
    void syncQueryTrackedPersistsWorkerTaskIdAndResult() {
        mockWorker("worker-1", "user-1");
        when(taskService.createTrackedSyncTask("user-1", "worker-1", "session-1",
                "check repo", "D:/repo", null, "claude-session-0"))
                .thenReturn("local-task-1");
        when(client.streamQuery(eq("check repo"), eq("D:/repo"), eq("claude-session-0"),
                eq("sonnet"), eq(2), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("bypassPermissions"), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Flux.just(
                        sse("""
                                {"type":"result","task_id":"worker-task-9","session_id":"claude-session-1","content":"Final answer","duration_ms":9159,"input_tokens":101,"output_tokens":22,"num_turns":2,"model":"sonnet","cost_usd":0.12}
                                """)
                ));

        Map<String, Object> result = facade.syncQueryTracked(
                "user-1", "worker-1", "check repo", "D:/repo",
                "claude-session-0", 2, "sonnet", "session-1", null);

        assertEquals("local-task-1", result.get("taskId"));
        assertEquals("worker-task-9", result.get("workerTaskId"));

        verify(taskService).persistTrackedSyncMessages("session-1", "check repo", "Final answer");
        verify(taskService).completeTask(
                "local-task-1",
                "worker-task-9",
                "claude-session-1",
                "Final answer",
                new BigDecimal("0.12"),
                101L,
                22L,
                9159L,
                2,
                "sonnet"
        );
    }

    @Test
    void validateWorkerOwnershipAllowsSameTenantWorker() {
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-1");
        worker.setUserId("owner-1");
        worker.setTenantId("tenant-1");
        when(workerService.getWorkerEntity("worker-1")).thenReturn(worker);

        com.foggy.navigator.common.dto.UserDTO user = new com.foggy.navigator.common.dto.UserDTO();
        user.setTenantId("tenant-1");
        when(userAuthService.getUser("agent-owner-1")).thenReturn(Optional.of(user));

        assertDoesNotThrow(() -> facade.validateWorkerOwnership("agent-owner-1", "worker-1"));
    }

    @Test
    void validateWorkerAccessAllowsExplicitTenantWorker() {
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-1");
        worker.setUserId("owner-1");
        worker.setTenantId("tenant-1");
        when(workerService.getWorkerEntity("worker-1")).thenReturn(worker);

        assertDoesNotThrow(() -> facade.validateWorkerAccess("agent-owner-1", "tenant-1", "worker-1"));
    }

    @Test
    void getCodexConfigReturnsConfiguredCodexEndpoint() {
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-1");
        CodexConfig configured = CodexConfig.builder()
                .baseUrl("http://127.0.0.1:3051")
                .authToken("plain-codex-token")
                .build();
        when(workerService.getWorkerEntity("worker-1")).thenReturn(worker);
        when(workerService.getDecryptedCodexConfig(worker)).thenReturn(configured);

        CodexConfig result = facade.getCodexConfig("worker-1");

        assertEquals("http://127.0.0.1:3051", result.getBaseUrl());
        assertEquals("plain-codex-token", result.getAuthToken());
    }

    @Test
    void getCodexConfigFallsBackToLegacyWorkerConnection() {
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-1");
        worker.setBaseUrl("http://127.0.0.1:3051");
        when(workerService.getWorkerEntity("worker-1")).thenReturn(worker);
        when(workerService.getDecryptedCodexConfig(worker)).thenReturn(null);
        when(workerService.getDecryptedToken(worker)).thenReturn("plain-worker-token");

        CodexConfig result = facade.getCodexConfig("worker-1");

        assertEquals("http://127.0.0.1:3051", result.getBaseUrl());
        assertEquals("plain-worker-token", result.getAuthToken());
    }

    private void mockWorker(String workerId, String userId) {
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId(workerId);
        worker.setUserId(userId);
        when(workerService.getWorkerEntity(workerId)).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
    }

    private ServerSentEvent<String> sse(String data) {
        return ServerSentEvent.<String>builder()
                .event("message")
                .data(data.strip())
                .build();
    }
}
