package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CodexSdkTaskCommandProviderTest {

    private CodexTaskService taskService;
    private CodexSdkTaskCommandProvider provider;

    @BeforeEach
    void setUp() {
        taskService = mock(CodexTaskService.class);
        provider = new CodexSdkTaskCommandProvider(taskService);
    }

    @Test
    void isExactSdkCommandPortWithOnlyFrozenDependencyAndCapabilities() {
        assertInstanceOf(TaskCommandProvider.class, provider);
        assertFalse(provider instanceof TaskLookupProvider);
        assertFalse(provider instanceof TaskListingProvider);
        assertEquals(CodexTaskService.CODEX_PROVIDER_TYPE, provider.getProviderType());
        assertEquals(Set.of(
                TaskQueryCapability.CREATE_TASK_DIRECT,
                TaskQueryCapability.RESUME_TASK,
                TaskQueryCapability.RESPOND_TO_TASK,
                TaskQueryCapability.RECONNECT_TASK,
                TaskQueryCapability.CANCEL_TASK,
                TaskQueryCapability.DELETE_TASK,
                TaskQueryCapability.RESYNC_TASK,
                TaskQueryCapability.REWIND_TASK), provider.getCapabilities());
        assertEquals(List.of(CodexTaskService.class), List.of(
                CodexSdkTaskCommandProvider.class.getDeclaredConstructors()[0]
                        .getParameterTypes()));
        assertFalse(Arrays.stream(CodexSdkTaskCommandProvider.class.getDeclaredMethods())
                .anyMatch(method -> method.getAnnotation(Transactional.class) != null));
    }

    @Test
    void delegatesAllEightCommandsWithSameObjectsAndUnwrappedResults() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workerId", "worker-1");
        params.put("nullable", null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answers", Map.of("choice", "one"));
        response.put("nullable", null);

        DispatchTaskDTO created = DispatchTaskDTO.builder().taskId("created").build();
        DispatchTaskDTO resumed = DispatchTaskDTO.builder().taskId("resumed").build();
        Object resynced = new Object();
        Object rewound = new Object();
        when(taskService.createTaskDirectForProvider(
                eq(CodexTaskService.CODEX_PROVIDER_TYPE), same(params),
                eq("user-1"), eq("tenant-1"))).thenReturn(created);
        when(taskService.resumeTaskForProvider(
                eq(CodexTaskService.CODEX_PROVIDER_TYPE), eq("user-1"),
                eq("tenant-1"), same(params))).thenReturn(resumed);
        when(taskService.resyncTaskForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, "task-1", "user-1"))
                .thenReturn(resynced);
        when(taskService.rewindTaskForProvider(
                eq(CodexTaskService.CODEX_PROVIDER_TYPE), eq("task-1"),
                eq("user-1"), same(params))).thenReturn(rewound);

        assertSame(created, provider.createTaskDirect(params, "user-1", "tenant-1"));
        assertSame(resumed, provider.resumeTask("user-1", "tenant-1", params));
        provider.respondToTask("task-1", "user-1", response);
        provider.reconnectTask("task-1", "user-1");
        provider.cancelTaskDirect("task-1", "user-1");
        provider.deleteTask("user-1", "task-1");
        assertSame(resynced, provider.resyncTask("task-1", "user-1"));
        assertSame(rewound, provider.rewindTask("task-1", "user-1", params));

        verify(taskService).createTaskDirectForProvider(
                eq(CodexTaskService.CODEX_PROVIDER_TYPE), same(params),
                eq("user-1"), eq("tenant-1"));
        verify(taskService).resumeTaskForProvider(
                eq(CodexTaskService.CODEX_PROVIDER_TYPE), eq("user-1"),
                eq("tenant-1"), same(params));
        verify(taskService).respondToTaskForProvider(
                eq(CodexTaskService.CODEX_PROVIDER_TYPE), eq("task-1"),
                eq("user-1"), same(response));
        verify(taskService).reconnectTaskForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, "task-1", "user-1");
        verify(taskService).cancelTaskDirectForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, "task-1", "user-1");
        verify(taskService).deleteTaskForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, "user-1", "task-1");
        verify(taskService).resyncTaskForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, "task-1", "user-1");
        verify(taskService).rewindTaskForProvider(
                eq(CodexTaskService.CODEX_PROVIDER_TYPE), eq("task-1"),
                eq("user-1"), same(params));
        verifyNoMoreInteractions(taskService);
    }

    @Test
    void fixesSdkRouteAndPropagatesServiceRejectionWithoutWrapping() {
        Map<String, Object> appServerParams = new LinkedHashMap<>();
        appServerParams.put("providerType", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        appServerParams.put("nullable", null);
        IllegalArgumentException mismatch = new IllegalArgumentException(
                "CODEX_TASK_PROVIDER_MISMATCH");
        doThrow(mismatch).when(taskService).createTaskDirectForProvider(
                eq(CodexTaskService.CODEX_PROVIDER_TYPE), same(appServerParams),
                eq("user-1"), eq("tenant-1"));

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> provider.createTaskDirect(
                        appServerParams, "user-1", "tenant-1"));

        assertSame(mismatch, thrown);
        verify(taskService).createTaskDirectForProvider(
                eq(CodexTaskService.CODEX_PROVIDER_TYPE), same(appServerParams),
                eq("user-1"), eq("tenant-1"));
        verifyNoMoreInteractions(taskService);
    }

    @Test
    void keepsForceCancelAndCheckpointScanUnsupportedWithoutDelegation() {
        assertThrows(UnsupportedOperationException.class,
                () -> provider.cancelTaskDirect("task-1", "user-1", true));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.scanCheckpoints("task-1", "user-1"));

        verifyNoInteractions(taskService);
    }
}
