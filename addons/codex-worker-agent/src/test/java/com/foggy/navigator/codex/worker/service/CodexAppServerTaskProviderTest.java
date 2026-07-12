package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexAppServerTaskProviderTest {

    private CodexTaskService taskService;
    private CodexAppServerTaskProvider provider;

    @BeforeEach
    void setUp() {
        taskService = mock(CodexTaskService.class);
        provider = new CodexAppServerTaskProvider(taskService);
    }

    @Test
    void missingProviderDefaultsToAppServerWithoutRewritingExplicitProvider() {
        DispatchTaskDTO task = task();
        when(taskService.createTaskDirectForProvider(
                eq(provider.getProviderType()), any(), eq("user-1"), eq("tenant-1"))).thenReturn(task);
        when(taskService.resumeTaskForProvider(
                eq(provider.getProviderType()), eq("user-1"), eq("tenant-1"), any())).thenReturn(task);

        provider.createTaskDirect(Map.of("workerId", "worker-1", "prompt", "hello",
                "providerType", CodexTaskService.CODEX_PROVIDER_TYPE), "user-1", "tenant-1");
        provider.resumeTask("user-1", "tenant-1", Map.of(
                "workerId", "worker-1", "sessionId", "session-1", "prompt", "continue"));

        verify(taskService).createTaskDirectForProvider(eq(provider.getProviderType()),
                argThat(params -> CodexTaskService.CODEX_PROVIDER_TYPE.equals(
                        params.get("providerType"))),
                eq("user-1"), eq("tenant-1"));
        verify(taskService).resumeTaskForProvider(eq(provider.getProviderType()),
                eq("user-1"), eq("tenant-1"), argThat(params ->
                provider.getProviderType().equals(params.get("providerType"))));
    }

    @Test
    void commandsCannotCrossProviderBoundary() {
        when(taskService.getTaskByIdAndUserForProvider(
                "task-sdk", "user-1", provider.getProviderType())).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> provider.cancelTaskDirect("task-sdk", "user-1"));
        verify(taskService, never()).cancelTaskDirectForProvider(any(), any(), any());
    }

    @Test
    void commandDelegatesAfterProviderScopedLookup() {
        when(taskService.getTaskByIdAndUserForProvider(
                "task-app", "user-1", provider.getProviderType()))
                .thenReturn(Optional.of(task()));

        provider.deleteTask("user-1", "task-app");

        verify(taskService).deleteTaskForProvider(
                provider.getProviderType(), "user-1", "task-app");
    }

    @Test
    void reconnectIsProviderScopedAndAdvertised() {
        when(taskService.getTaskByIdAndUserForProvider(
                "task-app", "user-1", provider.getProviderType()))
                .thenReturn(Optional.of(task()));

        provider.reconnectTask("task-app", "user-1");

        verify(taskService).reconnectTaskForProvider(
                provider.getProviderType(), "task-app", "user-1");
        assertTrue(provider.getCapabilities().contains(TaskQueryCapability.RECONNECT_TASK));
    }

    @Test
    void providerTypeIsIndependent() {
        assertEquals(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, provider.getProviderType());
    }

    private DispatchTaskDTO task() {
        return DispatchTaskDTO.builder()
                .taskId("task-app")
                .providerType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE)
                .build();
    }
}
