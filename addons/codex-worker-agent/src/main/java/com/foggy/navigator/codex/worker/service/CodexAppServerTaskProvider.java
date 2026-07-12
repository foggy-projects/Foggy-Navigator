package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.TaskSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Task SPI boundary for the dedicated Codex app-server execution provider.
 */
@Service
@RequiredArgsConstructor
public class CodexAppServerTaskProvider
        implements TaskLookupProvider, TaskCommandProvider, TaskListingProvider {

    private static final Set<TaskQueryCapability> CAPABILITIES = Set.of(
            TaskQueryCapability.CREATE_TASK_DIRECT,
            TaskQueryCapability.RESUME_TASK,
            TaskQueryCapability.RESPOND_TO_TASK,
            TaskQueryCapability.RECONNECT_TASK,
            TaskQueryCapability.CANCEL_TASK,
            TaskQueryCapability.DELETE_TASK,
            TaskQueryCapability.RESYNC_TASK,
            TaskQueryCapability.REWIND_TASK,
            TaskQueryCapability.LIST_TASKS_PAGED,
            TaskQueryCapability.SEARCH_SESSIONS,
            TaskQueryCapability.LIST_TASKS_BY_DIRECTORY_PAGED);

    private final CodexTaskService codexTaskService;

    @Override
    public String getProviderType() {
        return CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE;
    }

    @Override
    public Set<TaskQueryCapability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public DispatchTaskDTO createTaskDirect(Map<String, Object> params, String userId, String tenantId) {
        return codexTaskService.createTaskDirectForProvider(
                getProviderType(), normalizeParams(params), userId, tenantId);
    }

    @Override
    public DispatchTaskDTO resumeTask(String userId, String tenantId, Map<String, Object> params) {
        return codexTaskService.resumeTaskForProvider(
                getProviderType(), userId, tenantId, normalizeParams(params));
    }

    @Override
    public void respondToTask(String taskId, String userId, Map<String, Object> response) {
        ensureTaskBelongsToProvider(taskId, userId);
        codexTaskService.respondToTaskForProvider(getProviderType(), taskId, userId, response);
    }

    @Override
    public void reconnectTask(String taskId, String userId) {
        ensureTaskBelongsToProvider(taskId, userId);
        codexTaskService.reconnectTaskForProvider(getProviderType(), taskId, userId);
    }

    @Override
    public Optional<DispatchTaskDTO> getTaskById(String taskId) {
        return codexTaskService.getTaskByIdForProvider(taskId, getProviderType());
    }

    @Override
    public Optional<DispatchTaskDTO> getTaskByIdAndUser(String taskId, String userId) {
        return codexTaskService.getTaskByIdAndUserForProvider(taskId, userId, getProviderType());
    }

    @Override
    public List<DispatchTaskDTO> listTasksBySession(String sessionId) {
        return codexTaskService.listTasksBySessionForProvider(sessionId, getProviderType());
    }

    @Override
    public List<DispatchTaskDTO> listActiveDispatchTasks(String userId) {
        return codexTaskService.listActiveDispatchTasksForProvider(userId, getProviderType());
    }

    @Override
    public TaskPageResult listTaskPage(String userId, int page, int size, String state) {
        return codexTaskService.listTasksPagedForProvider(userId, page, size, state, getProviderType());
    }

    @Deprecated(since = "1.4.0", forRemoval = false)
    @Override
    public Object listTasksPaged(String userId, int page, int size, String state) {
        return listTaskPage(userId, page, size, state);
    }

    @Override
    public TaskPageResult listDirectoryTaskPage(String userId, String directoryId,
                                                int page, int size, String state) {
        return codexTaskService.listTasksByDirectoryPagedForProvider(
                userId, directoryId, page, size, state, getProviderType());
    }

    @Deprecated(since = "1.4.0", forRemoval = false)
    @Override
    public Object listTasksByDirectoryPaged(String userId, String directoryId,
                                            int page, int size, String state) {
        return listDirectoryTaskPage(userId, directoryId, page, size, state);
    }

    @Override
    public TaskSearchResult searchSessionPage(String userId, String keyword, String workerId,
                                              String directoryId, int page, int size) {
        boolean hasFilter = (keyword != null && !keyword.isBlank())
                || (workerId != null && !workerId.isBlank())
                || (directoryId != null && !directoryId.isBlank());
        if (!hasFilter) {
            return TaskSearchResult.empty(page, size);
        }
        String normalizedKeyword = keyword != null
                ? keyword.trim().toLowerCase(java.util.Locale.ROOT) : null;
        return codexTaskService.searchSessionsForProvider(
                userId, normalizedKeyword, workerId, directoryId, page, size, getProviderType());
    }

    @Deprecated(since = "1.4.0", forRemoval = false)
    @Override
    public Object searchSessions(String userId, String keyword, String workerId,
                                 String directoryId, int page, int size) {
        return searchSessionPage(userId, keyword, workerId, directoryId, page, size);
    }

    @Override
    public void cancelTaskDirect(String taskId, String userId) {
        ensureTaskBelongsToProvider(taskId, userId);
        codexTaskService.cancelTaskDirectForProvider(getProviderType(), taskId, userId);
    }

    @Deprecated(since = "1.4.0", forRemoval = false)
    @Override
    public void cancelTask(String taskId, String userId) {
        cancelTaskDirect(taskId, userId);
    }

    @Override
    public void deleteTask(String userId, String taskId) {
        ensureTaskBelongsToProvider(taskId, userId);
        codexTaskService.deleteTaskForProvider(getProviderType(), userId, taskId);
    }

    @Override
    public Object resyncTask(String taskId, String userId) {
        ensureTaskBelongsToProvider(taskId, userId);
        return codexTaskService.resyncTaskForProvider(getProviderType(), taskId, userId);
    }

    @Override
    public Object rewindTask(String taskId, String userId, Map<String, Object> params) {
        ensureTaskBelongsToProvider(taskId, userId);
        return codexTaskService.rewindTaskForProvider(getProviderType(), taskId, userId, params);
    }

    private Map<String, Object> normalizeParams(Map<String, Object> params) {
        if (params == null) {
            throw new IllegalArgumentException("codex-app-server-worker params are required");
        }
        Map<String, Object> normalized = new LinkedHashMap<>(params);
        Object providerType = normalized.get("providerType");
        Object snakeProviderType = normalized.get("provider_type");
        boolean hasProviderType = providerType != null && !providerType.toString().isBlank();
        boolean hasSnakeProviderType = snakeProviderType != null
                && !snakeProviderType.toString().isBlank();
        if (!hasProviderType && !hasSnakeProviderType) {
            normalized.put("providerType", getProviderType());
        }
        return normalized;
    }

    private void ensureTaskBelongsToProvider(String taskId, String userId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        Optional<DispatchTaskDTO> task = userId != null && !userId.isBlank()
                ? codexTaskService.getTaskByIdAndUserForProvider(taskId, userId, getProviderType())
                : codexTaskService.getTaskByIdForProvider(taskId, getProviderType());
        if (task.isEmpty()) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
    }
}
