package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.agent.TaskSearchResult;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodexBizTaskProviderTest {

    private CodexTaskService codexTaskService;
    private CodexBizTaskProvider provider;

    @BeforeEach
    void setUp() {
        codexTaskService = mock(CodexTaskService.class);
        provider = new CodexBizTaskProvider(codexTaskService);
    }

    @Test
    void getProviderType_returnsCodexBizWorker() {
        assertEquals("codex-biz-worker", provider.getProviderType());
    }

    @Test
    void lookupAndListingMethodsUseCodexBizProviderFilter() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-biz-1")
                .providerType("codex-biz-worker")
                .build();
        TaskPageResult page = TaskPageResult.of(List.of(task), 1, 0, 20);
        TaskSearchResult search = TaskSearchResult.of(List.of(Map.of("sessionId", "session-biz-1")), 1, 0, 20);

        when(codexTaskService.getTaskByIdForProvider("task-biz-1", "codex-biz-worker"))
                .thenReturn(Optional.of(task));
        when(codexTaskService.getTaskByIdAndUserForProvider("task-biz-1", "user-1", "codex-biz-worker"))
                .thenReturn(Optional.of(task));
        when(codexTaskService.listTasksBySessionForProvider("session-biz-1", "codex-biz-worker"))
                .thenReturn(List.of(task));
        when(codexTaskService.listActiveDispatchTasksForProvider("user-1", "codex-biz-worker"))
                .thenReturn(List.of(task));
        when(codexTaskService.listTasksPagedForProvider("user-1", 0, 20, null, "codex-biz-worker"))
                .thenReturn(page);
        when(codexTaskService.listTasksByDirectoryPagedForProvider("user-1", "dir-1", 0, 20, null,
                "codex-biz-worker")).thenReturn(page);
        when(codexTaskService.searchSessionsForProvider("user-1", "actor", "worker-1", "dir-1", 0, 20,
                "codex-biz-worker")).thenReturn(search);

        assertEquals(Optional.of(task), provider.getTaskById("task-biz-1"));
        assertEquals(Optional.of(task), provider.getTaskByIdAndUser("task-biz-1", "user-1"));
        assertEquals(List.of(task), provider.listTasksBySession("session-biz-1"));
        assertEquals(List.of(task), provider.listActiveDispatchTasks("user-1"));
        assertEquals(page, provider.listTaskPage("user-1", 0, 20, null));
        assertEquals(page, provider.listDirectoryTaskPage("user-1", "dir-1", 0, 20, null));
        assertEquals(search, provider.searchSessionPage("user-1", " Actor ", "worker-1", "dir-1", 0, 20));

        verify(codexTaskService).getTaskByIdForProvider("task-biz-1", "codex-biz-worker");
        verify(codexTaskService).getTaskByIdAndUserForProvider("task-biz-1", "user-1", "codex-biz-worker");
        verify(codexTaskService).listTasksBySessionForProvider("session-biz-1", "codex-biz-worker");
        verify(codexTaskService).listActiveDispatchTasksForProvider("user-1", "codex-biz-worker");
        verify(codexTaskService).listTasksPagedForProvider("user-1", 0, 20, null, "codex-biz-worker");
        verify(codexTaskService).listTasksByDirectoryPagedForProvider("user-1", "dir-1", 0, 20, null,
                "codex-biz-worker");
        verify(codexTaskService).searchSessionsForProvider("user-1", "actor", "worker-1", "dir-1", 0, 20,
                "codex-biz-worker");
    }

    @Test
    void exposesOnlySupportedTaskProviderPorts() {
        assertInstanceOf(TaskLookupProvider.class, provider);
        assertInstanceOf(TaskCommandProvider.class, provider);
        assertInstanceOf(TaskListingProvider.class, provider);
        assertFalse(provider instanceof TaskQueryProvider);
        assertFalse(provider instanceof WorkerSessionQueryProvider);
    }

    @Test
    void createTaskDirect_requiresCodexHomeKeyOrPrivateAccountId() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> provider.createTaskDirect(Map.of(
                        "workerId", "worker-1",
                        "prompt", "hello"
                ), "user-1", "tenant-1"));

        assertEquals("codex-biz-worker requires codexHomeKey or privateAccountId", error.getMessage());
        verifyNoInteractions(codexTaskService);
    }

    @Test
    void createTaskDirect_normalizesIdentityProviderTypeAndDefaultPolicy() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-biz-1")
                .providerType("codex-biz-worker")
                .build();
        when(codexTaskService.createTaskDirect(any(), eq("user-1"), eq("tenant-1"))).thenReturn(task);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workerId", "worker-1");
        params.put("prompt", "hello");
        params.put("privateAccountId", "tenant/world-sim/scenario-1/actor-1");

        DispatchTaskDTO result = provider.createTaskDirect(params, "user-1", "tenant-1");

        assertEquals("task-biz-1", result.getTaskId());
        verify(codexTaskService).createTaskDirect(argThat(normalized ->
                        "codex-biz-worker".equals(normalized.get("providerType"))
                                && "tenant/world-sim/scenario-1/actor-1".equals(normalized.get("codexHomeKey"))
                                && "workspace-write".equals(normalized.get("sandboxMode"))
                                && "never".equals(normalized.get("approvalPolicy"))
                                && Boolean.FALSE.equals(normalized.get("networkAccessEnabled"))
                                && "disabled".equals(normalized.get("webSearchMode"))),
                eq("user-1"), eq("tenant-1"));
    }

    @Test
    void createTaskDirect_preservesExplicitPolicyValues() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-biz-2")
                .providerType("codex-biz-worker")
                .build();
        when(codexTaskService.createTaskDirect(any(), eq("user-1"), eq("tenant-1"))).thenReturn(task);

        provider.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "codexHomeKey", "actor-home",
                "codexPolicy", Map.of(
                        "sandboxMode", "read-only",
                        "approvalPolicy", "on-request",
                        "networkAccessEnabled", true,
                        "webSearchMode", "enabled"
                )
        ), "user-1", "tenant-1");

        verify(codexTaskService).createTaskDirect(argThat(normalized ->
                        "actor-home".equals(normalized.get("codexHomeKey"))
                                && !normalized.containsKey("sandboxMode")
                                && !normalized.containsKey("approvalPolicy")
                                && !normalized.containsKey("networkAccessEnabled")
                                && !normalized.containsKey("webSearchMode")),
                eq("user-1"), eq("tenant-1"));
    }

    @Test
    void createTaskDirect_acceptsSnakeCaseAccountAndPolicyAliases() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-biz-3")
                .providerType("codex-biz-worker")
                .build();
        when(codexTaskService.createTaskDirect(any(), eq("user-1"), eq("tenant-1"))).thenReturn(task);

        Map<String, Object> policy = Map.of(
                "sandbox_mode", "workspace-write",
                "approval_policy", "never",
                "network_access_enabled", false,
                "web_search_mode", "disabled");

        provider.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "private_account_id", "tenant/world-sim/scenario-1/actor-2",
                "codex_policy", policy
        ), "user-1", "tenant-1");

        verify(codexTaskService).createTaskDirect(argThat(normalized ->
                        "codex-biz-worker".equals(normalized.get("providerType"))
                                && "tenant/world-sim/scenario-1/actor-2".equals(normalized.get("codexHomeKey"))
                                && policy.equals(normalized.get("codex_policy"))
                                && !normalized.containsKey("sandboxMode")
                                && !normalized.containsKey("approvalPolicy")
                                && !normalized.containsKey("networkAccessEnabled")
                                && !normalized.containsKey("webSearchMode")),
                eq("user-1"), eq("tenant-1"));
    }

    @Test
    void resumeTask_normalizesPrivateAccountAliasAndDefaultPolicy() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-biz-resume")
                .providerType("codex-biz-worker")
                .build();
        when(codexTaskService.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(task);

        DispatchTaskDTO result = provider.resumeTask("user-1", "tenant-1", Map.of(
                "workerId", "worker-1",
                "sessionId", "session-biz-1",
                "prompt", "continue",
                "private_account_id", "tenant/world-sim/scenario-1/actor-3"
        ));

        assertEquals("task-biz-resume", result.getTaskId());
        verify(codexTaskService).resumeTask(eq("user-1"), eq("tenant-1"), argThat(normalized ->
                "codex-biz-worker".equals(normalized.get("providerType"))
                        && "tenant/world-sim/scenario-1/actor-3".equals(normalized.get("codexHomeKey"))
                        && "workspace-write".equals(normalized.get("sandboxMode"))
                        && "never".equals(normalized.get("approvalPolicy"))
                        && Boolean.FALSE.equals(normalized.get("networkAccessEnabled"))
                        && "disabled".equals(normalized.get("webSearchMode"))));
    }

    @Test
    void cancelTask_rejectsTaskOutsideCodexBizProvider() {
        when(codexTaskService.getTaskByIdAndUserForProvider(
                "task-1", "user-1", "codex-biz-worker")).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> provider.cancelTaskDirect("task-1", "user-1"));

        assertEquals("Task not found: task-1", error.getMessage());
        verify(codexTaskService, never()).cancelTaskDirect(any(), any());
    }

    @Test
    void deleteTask_validatesProviderBeforeDelegating() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-biz-1")
                .providerType("codex-biz-worker")
                .build();
        when(codexTaskService.getTaskByIdAndUserForProvider(
                "task-biz-1", "user-1", "codex-biz-worker")).thenReturn(Optional.of(task));

        provider.deleteTask("user-1", "task-biz-1");

        verify(codexTaskService).deleteTask("user-1", "task-biz-1");
    }

    @Test
    void resyncTask_rejectsTaskOutsideCodexBizProvider() {
        when(codexTaskService.getTaskByIdAndUserForProvider(
                "task-1", "user-1", "codex-biz-worker")).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> provider.resyncTask("task-1", "user-1"));

        assertEquals("Task not found: task-1", error.getMessage());
        verify(codexTaskService, never()).resyncTask(any(), any());
    }

    @Test
    void rewindTask_validatesProviderBeforeDelegating() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-biz-1")
                .providerType("codex-biz-worker")
                .build();
        when(codexTaskService.getTaskByIdAndUserForProvider(
                "task-biz-1", "user-1", "codex-biz-worker")).thenReturn(Optional.of(task));
        Map<String, Object> params = Map.of("turnIndex", 1);
        Map<String, Object> result = Map.of("status", "rewound");
        when(codexTaskService.rewindTask("task-biz-1", "user-1", params)).thenReturn(result);

        Object actual = provider.rewindTask("task-biz-1", "user-1", params);

        assertEquals(result, actual);
        verify(codexTaskService).rewindTask("task-biz-1", "user-1", params);
    }
}
