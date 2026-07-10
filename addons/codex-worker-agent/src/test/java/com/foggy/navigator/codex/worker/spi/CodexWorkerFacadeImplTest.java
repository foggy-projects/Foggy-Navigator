package com.foggy.navigator.codex.worker.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.service.CodexAppServerAcceptanceService;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRegistryService;
import com.foggy.navigator.codex.worker.service.CodexStreamRelay;
import com.foggy.navigator.codex.worker.service.CodexTaskRuntimeStateService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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
    private CodexRuntimeRegistryService runtimeRegistryService;

    @Mock
    private CodexTaskRuntimeStateService taskRuntimeStateService;

    @Mock
    private CodexAppServerAcceptanceService appServerAcceptanceService;

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
                runtimeRegistryService,
                taskRuntimeStateService,
                appServerAcceptanceService,
                new ObjectMapper()
        );
        org.mockito.Mockito.lenient().when(taskRuntimeStateService.markSubscribed(any()))
                .thenReturn(true);
    }

    @Test
    void syncQueryAggregatesUpstreamTaskAndMetrics() {
        mockWorker("worker-1", "gpt-5.4-mini");
        stubTrackedTask(null, "thread-0", "gpt-5.4-mini", legacyTask());
        when(client.streamQuery(eq("check repo"), eq("D:/repo"), eq("thread-0"),
                eq("gpt-5.4-mini"), eq(2), eq(null), eq(null), eq(null), eq(null), eq(null)))
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
        verify(client).streamQuery(eq("check repo"), eq("D:/repo"), eq("thread-0"),
                eq("gpt-5.4-mini"), eq(2), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void syncQueryTrackedPersistsWorkerTaskIdAndResult() {
        mockWorker("worker-1", "gpt-5.4-mini");
        when(taskService.createTrackedSyncTask("user-1", "worker-1", "session-1",
                "check repo", "D:/repo", null, "thread-0", "gpt-5.4-mini"))
                .thenReturn("local-task-1");
        CodexTaskEntity legacyTask = legacyTask();
        when(taskService.getTaskEntity("local-task-1")).thenReturn(legacyTask);
        when(runtimeRegistryService.resolveBoundRuntime(
                "legacy-sdk:worker-1", 1, "worker-1", null))
                .thenReturn(CodexRuntimeBinding.legacySdk("worker-1"));
        when(client.streamQuery(eq("check repo"), eq("D:/repo"), eq("thread-0"),
                eq("gpt-5.4-mini"), eq(2), eq(null), eq(null), eq(null), eq(null), eq(null)))
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

    @Test
    void syncQueryUsesCreateThenSubscribeForAppServerAffinity() {
        CodexTaskEntity task = appServerTask();
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(null);
        stubTrackedTask(null, null, "codex-ultra", task);
        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeRevision(1)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .endpointUrl("http://127.0.0.1:3062")
                .authToken("runtime-token")
                .instanceId("instance-a")
                .routingEpoch(1L)
                .build();
        when(runtimeRegistryService.resolveBoundRuntime("app-main", 1, "worker-1", "instance-a"))
                .thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:1", "http://127.0.0.1:3062", "runtime-token", "instance-a"))
                .thenReturn(client);
        Map<String, Object> request = Map.of("prompt", "check repo", "model", "codex-ultra");
        when(client.buildTaskRequest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        when(appServerAcceptanceService.accept(client, "local-task-1", request))
                .thenReturn("worker-task-9");
        when(client.subscribeToTask("worker-task-9", 0)).thenReturn(Flux.just(sse("""
                {"type":"result","task_id":"worker-task-9","session_id":"thread-1","result":"done","model":"gpt-5.6-sol"}
                """)));

        Map<String, Object> result = facade.syncQuery(
                "user-1", "worker-1", "check repo", "D:/repo", null, 1, "codex-ultra");

        assertEquals("local-task-1", result.get("taskId"));
        assertEquals("worker-task-9", result.get("workerTaskId"));
        verify(taskRuntimeStateService).prepareAcceptance("local-task-1", request);
        verify(client).buildTaskRequest(
                eq("check repo"), eq("D:/repo"), isNull(), eq("codex-ultra"), eq(1),
                isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
        verify(appServerAcceptanceService).accept(client, "local-task-1", request);
        verify(taskRuntimeStateService).markSubscribed("local-task-1");
        verify(client).subscribeToTask("worker-task-9", 0);
        verify(client, never()).streamQuery(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptedAppServerStreamFailureRemainsRecoverableInsteadOfFailingTask() {
        CodexTaskEntity task = appServerTask();
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(null);
        stubTrackedTask(null, null, "codex-ultra", task);
        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeRevision(1)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .endpointUrl("http://127.0.0.1:3062")
                .authToken("runtime-token")
                .instanceId("instance-a")
                .build();
        when(runtimeRegistryService.resolveBoundRuntime("app-main", 1, "worker-1", "instance-a"))
                .thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:1", "http://127.0.0.1:3062", "runtime-token", "instance-a"))
                .thenReturn(client);
        Map<String, Object> request = Map.of("prompt", "check repo");
        when(client.buildTaskRequest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        when(appServerAcceptanceService.accept(client, "local-task-1", request))
                .thenReturn("worker-task-9");
        when(client.subscribeToTask("worker-task-9", 0))
                .thenReturn(Flux.error(new java.util.concurrent.TimeoutException("stream timed out")));

        Map<String, Object> result = facade.syncQuery(
                "user-1", "worker-1", "check repo", "D:/repo", null, 1, "codex-ultra");

        assertEquals("worker-task-9", result.get("workerTaskId"));
        assertTrue(result.get("error").toString().contains("CODEX_SYNC_RESULT_UNKNOWN"));
        verify(taskService, never()).failTask(eq("local-task-1"), any(), any(), any());
        verify(streamRelay).reconnectTask("local-task-1", null, "worker-1");
    }

    @Test
    void completedAppServerStreamWithoutTerminalEventSchedulesAcceptedTaskReconciliation() {
        CodexTaskEntity task = appServerTask();
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(null);
        stubTrackedTask("session-1", null, "codex-ultra", task);
        stubAppServerRuntime();
        Map<String, Object> request = Map.of("prompt", "check repo");
        when(client.buildTaskRequest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        when(appServerAcceptanceService.accept(client, "local-task-1", request))
                .thenReturn("worker-task-9");
        when(client.subscribeToTask("worker-task-9", 0)).thenReturn(Flux.just(sse("""
                {"type":"assistant_text","task_id":"worker-task-9","content":"partial output"}
                """)));

        Map<String, Object> result = facade.syncQueryTracked(
                "user-1", "worker-1", "check repo", "D:/repo", null, 1,
                "codex-ultra", "session-1");

        assertEquals("worker-task-9", result.get("workerTaskId"));
        assertEquals("CODEX_SYNC_RESULT_UNKNOWN", result.get("error"));
        verify(taskService, never()).completeTask(eq("local-task-1"), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(taskService, never()).failTask(eq("local-task-1"), any(), any(), any());
        verify(streamRelay).reconnectTask("local-task-1", "session-1", "worker-1");
        verify(appServerAcceptanceService).accept(client, "local-task-1", request);
    }

    @Test
    void appServerRejectsMultipleTurnsBeforeAcceptance() {
        CodexTaskEntity task = appServerTask();
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(null);
        stubTrackedTask(null, null, "codex-ultra", task);
        stubAppServerBinding();

        Map<String, Object> result = facade.syncQuery(
                "user-1", "worker-1", "check repo", "D:/repo", null, 2, "codex-ultra");

        assertEquals("UNSUPPORTED_MAX_TURNS", result.get("error"));
        verify(taskService).failTask(eq("local-task-1"), isNull(), isNull(),
                eq("UNSUPPORTED_MAX_TURNS"));
        verify(taskRuntimeStateService, never()).prepareAcceptance(any(), any());
        verify(appServerAcceptanceService, never()).accept(any(), any(), any());
        verify(clientFactory, never()).getOrCreate(any(), any(), any());
        verify(client, never()).buildTaskRequest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void abortClaimAfterAcceptancePreventsSyncSubscription() {
        CodexTaskEntity task = appServerTask();
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(null);
        stubTrackedTask("session-1", null, "codex-ultra", task);
        stubAppServerRuntime();
        Map<String, Object> request = Map.of("prompt", "check repo");
        when(client.buildTaskRequest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        when(appServerAcceptanceService.accept(client, "local-task-1", request))
                .thenReturn("local-task-1");
        when(taskRuntimeStateService.markSubscribed("local-task-1")).thenReturn(false);

        Map<String, Object> result = facade.syncQueryTracked(
                "user-1", "worker-1", "check repo", "D:/repo", null, 1,
                "codex-ultra", "session-1");

        assertEquals("CODEX_SYNC_RESULT_UNKNOWN", result.get("error"));
        verify(client, never()).subscribeToTask(any(), anyInt());
        verify(streamRelay).reconnectTask("local-task-1", "session-1", "worker-1");
    }

    @Test
    void syncFailureDoesNotExposeTransportDetails() {
        String sentinel = "http://internal-runtime:3062 bearer-secret";
        mockWorker("worker-1", "gpt-5.4-mini");
        stubTrackedTask(null, null, "gpt-5.4-mini", legacyTask());
        when(client.streamQuery(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException(sentinel)));

        Map<String, Object> result = facade.syncQuery(
                "user-1", "worker-1", "check repo", "D:/repo", null, 1, "gpt-5.4-mini");

        assertEquals("CODEX_SYNC_QUERY_FAILED", result.get("error"));
        assertTrue(result.values().stream().noneMatch(value -> String.valueOf(value).contains(sentinel)));
        verify(taskService).failTask("local-task-1", null, null, "CODEX_SYNC_QUERY_FAILED");
    }

    @Test
    void abortTaskReturnsReconciledTerminalStatus() {
        CodexTaskEntity running = legacyTask();
        running.setUserId("user-1");
        running.setStatus("RUNNING");
        CodexTaskEntity completed = legacyTask();
        completed.setUserId("user-1");
        completed.setStatus("COMPLETED");
        when(taskService.getTaskEntity("local-task-1")).thenReturn(running, completed);

        Map<String, Object> result = facade.abortTask("user-1", "local-task-1");

        assertEquals("COMPLETED", result.get("status"));
        verify(taskService).abortTask("local-task-1");
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

    private void stubTrackedTask(String sessionId, String codexThreadId, String model, CodexTaskEntity task) {
        when(taskService.createTrackedSyncTask(
                "user-1", "worker-1", sessionId, "check repo", "D:/repo", null, codexThreadId, model))
                .thenReturn("local-task-1");
        when(taskService.getTaskEntity("local-task-1")).thenReturn(task);
        if (CodexRuntimeType.SDK_EXEC.name().equals(task.getRuntimeType())) {
            when(runtimeRegistryService.resolveBoundRuntime(
                    "legacy-sdk:worker-1", 1, "worker-1", null))
                    .thenReturn(CodexRuntimeBinding.legacySdk("worker-1"));
        }
    }

    private CodexTaskEntity legacyTask() {
        CodexTaskEntity task = new CodexTaskEntity();
        task.setTaskId("local-task-1");
        task.setWorkerId("worker-1");
        task.setRuntimeId("legacy-sdk:worker-1");
        task.setRuntimeRevision(1);
        task.setRuntimeType("SDK_EXEC");
        return task;
    }

    private CodexTaskEntity appServerTask() {
        CodexTaskEntity task = legacyTask();
        task.setRuntimeId("app-main");
        task.setRuntimeType("APP_SERVER");
        task.setRuntimeInstanceId("instance-a");
        return task;
    }

    private void stubAppServerRuntime() {
        stubAppServerBinding();
        when(clientFactory.getOrCreate(
                "runtime:app-main:1", "http://127.0.0.1:3062", "runtime-token", "instance-a"))
                .thenReturn(client);
    }

    private void stubAppServerBinding() {
        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeRevision(1)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .endpointUrl("http://127.0.0.1:3062")
                .authToken("runtime-token")
                .instanceId("instance-a")
                .routingEpoch(1L)
                .build();
        when(runtimeRegistryService.resolveBoundRuntime("app-main", 1, "worker-1", "instance-a"))
                .thenReturn(binding);
    }

    private ServerSentEvent<String> sse(String data) {
        return ServerSentEvent.<String>builder()
                .event("message")
                .data(data.strip())
                .build();
    }
}
