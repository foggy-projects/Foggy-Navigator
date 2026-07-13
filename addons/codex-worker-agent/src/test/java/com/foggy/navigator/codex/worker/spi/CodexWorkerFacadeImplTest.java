package com.foggy.navigator.codex.worker.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    private CodexWorkerClient client;

    private CodexWorkerFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new CodexWorkerFacadeImpl(
                workerManagementFacade, clientFactory, taskService, new ObjectMapper());
    }

    @Test
    void createTaskForcesSdkProvider() {
        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any()))
                .thenReturn(CodexTaskDTO.builder().taskId("task-1").build());

        facade.createTask("user-1", Map.of(
                "tenantId", "tenant-1",
                "workerId", "worker-1",
                "prompt", "hello",
                "providerType", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE));

        var form = ArgumentCaptor.forClass(
                com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm.class);
        verify(taskService).createTask(eq("user-1"), eq("tenant-1"), form.capture());
        assertEquals(CodexTaskService.CODEX_PROVIDER_TYPE, form.getValue().getProviderType());
    }

    @Test
    void syncQueryAggregatesSdkStream() {
        mockSdkWorker();
        when(taskService.createTrackedSyncTask(
                "user-1", "worker-1", null, "check repo", "D:/repo", null,
                "thread-0", "gpt-5.6-sol"))
                .thenReturn("local-task-1");
        when(client.streamQuery(eq("check repo"), eq("D:/repo"), eq("thread-0"),
                eq("gpt-5.6-sol"), eq(2), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Flux.just(
                        sse("""
                                {"type":"assistant_text","task_id":"worker-task-9","session_id":"thread-1","content":"Hello "}
                                """),
                        sse("""
                                {"type":"result","task_id":"worker-task-9","session_id":"thread-1","result":"Final answer","duration_ms":9159,"input_tokens":101,"output_tokens":22,"num_turns":2,"model":"gpt-5.6-sol","cost_usd":0.12}
                                """)));

        Map<String, Object> result = facade.syncQuery(
                "user-1", "worker-1", "check repo", "D:/repo", "thread-0", 2, null);

        assertEquals("worker-task-9", result.get("workerTaskId"));
        assertEquals("thread-1", result.get("codexThreadId"));
        assertEquals("Final answer", result.get("resultText"));
        assertEquals(new BigDecimal("0.12"), result.get("costUsd"));
        assertNull(result.get("error"));
    }

    @Test
    void syncQueryUsesLatestCompletedAssistantItemInsteadOfJoiningDeltas() {
        mockSdkWorker();
        when(taskService.createTrackedSyncTask(
                "user-1", "worker-1", null, "check repo", "D:/repo", null,
                "thread-0", "gpt-5.6-sol"))
                .thenReturn("local-task-1");
        when(client.streamQuery(eq("check repo"), eq("D:/repo"), eq("thread-0"),
                eq("gpt-5.6-sol"), eq(2), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Flux.just(
                        sse("""
                                {"type":"assistant_text","subtype":"text_delta","task_id":"worker-task-9","session_id":"thread-1","content":"old fragment"}
                                """),
                        sse("""
                                {"type":"assistant_text","subtype":"commentary","task_id":"worker-task-9","session_id":"thread-1","content":"working"}
                                """),
                        sse("""
                                {"type":"assistant_text","task_id":"worker-task-9","session_id":"thread-1","content":"LATEST_COMPLETED_ITEM"}
                                """)));

        Map<String, Object> result = facade.syncQuery(
                "user-1", "worker-1", "check repo", "D:/repo", "thread-0", 2, null);

        assertEquals("LATEST_COMPLETED_ITEM", result.get("resultText"));
    }

    @Test
    void trackedSdkQueryPersistsTerminalResult() {
        mockSdkWorker();
        when(taskService.createTrackedSyncTask(
                "user-1", "worker-1", "session-1", "check repo", "D:/repo", null,
                "thread-0", "gpt-5.6-sol"))
                .thenReturn("local-task-1");
        when(client.streamQuery(eq("check repo"), eq("D:/repo"), eq("thread-0"),
                eq("gpt-5.6-sol"), eq(1), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Flux.just(sse("""
                        {"type":"result","task_id":"worker-task-9","session_id":"thread-1","result":"done","model":"gpt-5.6-sol"}
                        """)));

        Map<String, Object> result = facade.syncQueryTracked(
                "user-1", "worker-1", "check repo", "D:/repo", "thread-0", 1,
                null, "session-1");

        assertEquals("local-task-1", result.get("taskId"));
        verify(taskService).completeTask(
                eq("local-task-1"), eq("worker-task-9"), eq("thread-1"), eq("done"),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq("gpt-5.6-sol"));
    }

    @Test
    void syncFailureDoesNotExposeTransportDetails() {
        String sentinel = "http://internal-sdk:3051 bearer-secret";
        mockSdkWorker();
        when(taskService.createTrackedSyncTask(
                "user-1", "worker-1", null, "check repo", "D:/repo", null,
                null, "gpt-5.6-sol"))
                .thenReturn("local-task-1");
        when(client.streamQuery(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException(sentinel)));

        Map<String, Object> result = facade.syncQuery(
                "user-1", "worker-1", "check repo", "D:/repo", null, 1, "gpt-5.6-sol");

        assertEquals("CODEX_SYNC_QUERY_FAILED", result.get("error"));
        assertTrue(result.values().stream().noneMatch(value -> String.valueOf(value).contains(sentinel)));
        verify(taskService).failTask("local-task-1", null, null, "CODEX_SYNC_QUERY_FAILED");
    }

    @Test
    void abortRejectsAppServerTask() {
        CodexTaskEntity task = task(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "RUNNING");
        when(taskService.getTaskEntity("task-1")).thenReturn(task);

        assertThrows(IllegalArgumentException.class,
                () -> facade.abortTask("user-1", "task-1"));
    }

    @Test
    void statusUsesSdkProviderScope() {
        CodexTaskDTO task = CodexTaskDTO.builder()
                .taskId("task-1")
                .providerType(CodexTaskService.CODEX_PROVIDER_TYPE)
                .build();
        when(taskService.getTaskForProvider(
                "user-1", "task-1", CodexTaskService.CODEX_PROVIDER_TYPE)).thenReturn(task);

        Map<String, Object> result = facade.getTaskStatus("user-1", "task-1");

        assertEquals(CodexTaskService.CODEX_PROVIDER_TYPE, result.get("providerType"));
        verify(taskService).getTaskForProvider(
                "user-1", "task-1", CodexTaskService.CODEX_PROVIDER_TYPE);
    }

    @Test
    void abortReturnsReconciledSdkStatus() {
        CodexTaskEntity running = task(CodexTaskService.CODEX_PROVIDER_TYPE, "RUNNING");
        CodexTaskEntity completed = task(CodexTaskService.CODEX_PROVIDER_TYPE, "COMPLETED");
        when(taskService.getTaskEntity("task-1")).thenReturn(running, completed);

        Map<String, Object> result = facade.abortTask("user-1", "task-1");

        assertEquals("COMPLETED", result.get("status"));
        verify(taskService).abortTask("task-1");
    }

    private void mockSdkWorker() {
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://localhost:3051")
                        .authToken("worker-token")
                        .model("gpt-5.6-sol")
                        .build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://localhost:3051", "worker-token"))
                .thenReturn(client);
    }

    private CodexTaskEntity task(String providerType, String status) {
        CodexTaskEntity task = new CodexTaskEntity();
        task.setTaskId("task-1");
        task.setWorkerId("worker-1");
        task.setUserId("user-1");
        task.setProviderType(providerType);
        task.setStatus(status);
        return task;
    }

    private ServerSentEvent<String> sse(String data) {
        return ServerSentEvent.<String>builder().event("message").data(data.strip()).build();
    }
}
