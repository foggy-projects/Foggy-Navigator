package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/** Exact {@code codex-worker} command port backed by the existing Codex task state machine. */
@Service
public class CodexSdkTaskCommandProvider implements TaskCommandProvider {

    private static final Set<TaskQueryCapability> CAPABILITIES = Set.of(
            TaskQueryCapability.CREATE_TASK_DIRECT,
            TaskQueryCapability.RESUME_TASK,
            TaskQueryCapability.RESPOND_TO_TASK,
            TaskQueryCapability.RECONNECT_TASK,
            TaskQueryCapability.CANCEL_TASK,
            TaskQueryCapability.DELETE_TASK,
            TaskQueryCapability.RESYNC_TASK,
            TaskQueryCapability.REWIND_TASK);

    private final CodexTaskService taskService;

    public CodexSdkTaskCommandProvider(CodexTaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public String getProviderType() {
        return CodexTaskService.CODEX_PROVIDER_TYPE;
    }

    @Override
    public Set<TaskQueryCapability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public DispatchTaskDTO createTaskDirect(
            Map<String, Object> params,
            String userId,
            String tenantId) {
        return taskService.createTaskDirectForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, params, userId, tenantId);
    }

    @Override
    public DispatchTaskDTO resumeTask(
            String userId,
            String tenantId,
            Map<String, Object> params) {
        return taskService.resumeTaskForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, userId, tenantId, params);
    }

    @Override
    public void respondToTask(
            String taskId,
            String userId,
            Map<String, Object> response) {
        taskService.respondToTaskForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, taskId, userId, response);
    }

    @Override
    public void reconnectTask(String taskId, String userId) {
        taskService.reconnectTaskForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, taskId, userId);
    }

    @Override
    public void cancelTaskDirect(String taskId, String userId) {
        taskService.cancelTaskDirectForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, taskId, userId);
    }

    @Override
    public void deleteTask(String userId, String taskId) {
        taskService.deleteTaskForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, userId, taskId);
    }

    @Override
    public Object resyncTask(String taskId, String userId) {
        return taskService.resyncTaskForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, taskId, userId);
    }

    @Override
    public Object rewindTask(
            String taskId,
            String userId,
            Map<String, Object> params) {
        return taskService.rewindTaskForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, taskId, userId, params);
    }
}
