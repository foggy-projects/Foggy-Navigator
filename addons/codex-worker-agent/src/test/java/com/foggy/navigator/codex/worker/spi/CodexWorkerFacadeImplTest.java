package com.foggy.navigator.codex.worker.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.service.CodexStreamRelay;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexWorkerFacadeImplTest {

    @Mock
    private WorkerManagementFacade workerManagementFacade;

    @Mock
    private CodexWorkerClientFactory clientFactory;

    @Mock
    private CodexTaskService taskService;

    @Mock
    private CodexStreamRelay streamRelay;

    @Mock
    private CodexWorkerClient client;

    private CodexWorkerFacadeImpl facade;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        facade = new CodexWorkerFacadeImpl(
                workerManagementFacade,
                clientFactory,
                taskService,
                streamRelay,
                new ObjectMapper()
        );
    }

    @Test
    void syncQueryAggregatesUpstreamTaskAndMetrics() {
        mockWorker("worker-1", "gpt-5.4-mini");
        when(client.streamQuery(eq("check repo"), eq("D:/repo"), eq("thread-0"),
                eq("gpt-5.4-mini"), eq(2), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(Flux.just(
                        sse("""
                                {"type":"assistant_text","task_id":"worker-task-9","session_id":"thread-1","content":"Hello "}
                                """),
                        sse("""
                                {"type":"assistant_text","task_id":"worker-task-9","session_id":"thread-1","content":"World"}
                                """),
                        sse("""
                                {"type":"result","task_id":"worker-task-9","session_id":"thread-1","result":"Final answer","duration_ms":9159,"input_tokens":101,"output_tokens":22,"num_turns":2,"model":"gpt-5.4-mini","cost_usd":0.12}
                                """)
                ));

        Map<String, Object> result = facade.syncQuery(
                "user-1", "worker-1", "check repo", "D:/repo", "thread-0", 2, null);

        assertEquals("worker-task-9", result.get("workerTaskId"));
        assertEquals("thread-1", result.get("codexThreadId"));
        assertEquals("Final answer", result.get("resultText"));
        assertEquals("gpt-5.4-mini", result.get("model"));
        assertEquals(9159L, result.get("durationMs"));
        assertEquals(101L, result.get("inputTokens"));
        assertEquals(22L, result.get("outputTokens"));
        assertEquals(2, result.get("numTurns"));
        assertEquals(new BigDecimal("0.12"), result.get("costUsd"));
        assertNull(result.get("error"));
    }

    @Test
    void syncQueryTrackedPersistsWorkerTaskIdAndResult() {
        mockWorker("worker-1", "gpt-5.4-mini");
        when(taskService.createTrackedSyncTask("user-1", "worker-1", "session-1",
                "check repo", "D:/repo", null, "thread-0"))
                .thenReturn("local-task-1");
        when(client.streamQuery(eq("check repo"), eq("D:/repo"), eq("thread-0"),
                eq("gpt-5.4-mini"), eq(2), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(Flux.just(
                        sse("""
                                {"type":"result","task_id":"worker-task-9","session_id":"thread-1","result":"Final answer","duration_ms":9159,"input_tokens":101,"output_tokens":22,"num_turns":2,"model":"gpt-5.4-mini","cost_usd":0.12}
                                """)
                ));

        Map<String, Object> result = facade.syncQueryTracked(
                "user-1", "worker-1", "check repo", "D:/repo", "thread-0", 2, null, "session-1");

        assertEquals("local-task-1", result.get("taskId"));
        assertEquals("worker-task-9", result.get("workerTaskId"));

        ArgumentCaptor<BigDecimal> costCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(taskService).completeTask(
                eq("local-task-1"),
                eq("worker-task-9"),
                eq("thread-1"),
                eq("Final answer"),
                costCaptor.capture(),
                eq(101L),
                eq(22L),
                eq(9159L),
                eq(2),
                eq("gpt-5.4-mini")
        );
        assertEquals(new BigDecimal("0.12"), costCaptor.getValue());
    }

    private void mockWorker(String workerId, String defaultModel) {
        when(workerManagementFacade.getCodexConfig(workerId))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:3051")
                        .authToken("worker-token")
                        .model(defaultModel)
                        .build());
        when(clientFactory.getOrCreate(workerId + ":codex", "http://localhost:3051", "worker-token"))
                .thenReturn(client);
    }

    private ServerSentEvent<String> sse(String data) {
        return ServerSentEvent.<String>builder()
                .event("message")
                .data(data.strip())
                .build();
    }
}
