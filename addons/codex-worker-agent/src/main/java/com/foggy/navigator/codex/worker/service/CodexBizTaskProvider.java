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
 * Dedicated Codex business execution route.
 * <p>
 * The execution substrate is still the Codex worker/SDK bridge, but routing,
 * account identity, and default policy are explicit to codex-biz-worker.
 */
@Service
@RequiredArgsConstructor
public class CodexBizTaskProvider implements TaskLookupProvider, TaskCommandProvider, TaskListingProvider {

    private static final String DEFAULT_SANDBOX_MODE = "workspace-write";
    private static final String DEFAULT_APPROVAL_POLICY = "never";
    private static final String DEFAULT_WEB_SEARCH_MODE = "disabled";
    private static final Set<TaskQueryCapability> CAPABILITIES = Set.of(
            TaskQueryCapability.CREATE_TASK_DIRECT,
            TaskQueryCapability.RESUME_TASK,
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
        return CodexTaskService.CODEX_BIZ_PROVIDER_TYPE;
    }

    @Override
    public Set<TaskQueryCapability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public DispatchTaskDTO createTaskDirect(Map<String, Object> params, String userId, String tenantId) {
        return codexTaskService.createTaskDirect(normalizeParams(params), userId, tenantId);
    }

    @Override
    public DispatchTaskDTO resumeTask(String userId, String tenantId, Map<String, Object> params) {
        return codexTaskService.resumeTask(userId, tenantId, normalizeParams(params));
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

    @Deprecated(since = "1.3.1", forRemoval = false)
    @Override
    public Object listTasksPaged(String userId, int page, int size, String state) {
        return listTaskPage(userId, page, size, state);
    }

    @Override
    public TaskPageResult listDirectoryTaskPage(String userId, String directoryId, int page, int size, String state) {
        return codexTaskService.listTasksByDirectoryPagedForProvider(
                userId, directoryId, page, size, state, getProviderType());
    }

    @Deprecated(since = "1.3.1", forRemoval = false)
    @Override
    public Object listTasksByDirectoryPaged(String userId, String directoryId, int page, int size, String state) {
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
        String normalizedKeyword = keyword != null ? keyword.trim().toLowerCase(java.util.Locale.ROOT) : null;
        return codexTaskService.searchSessionsForProvider(
                userId, normalizedKeyword, workerId, directoryId, page, size, getProviderType());
    }

    @Deprecated(since = "1.3.1", forRemoval = false)
    @Override
    public Object searchSessions(String userId, String keyword, String workerId,
                                 String directoryId, int page, int size) {
        return searchSessionPage(userId, keyword, workerId, directoryId, page, size);
    }

    @Override
    public void cancelTaskDirect(String taskId, String userId) {
        ensureTaskBelongsToProvider(taskId, userId);
        codexTaskService.cancelTaskDirect(taskId, userId);
    }

    @Deprecated(since = "1.3.1", forRemoval = false)
    @Override
    public void cancelTask(String taskId, String userId) {
        cancelTaskDirect(taskId, userId);
    }

    @Override
    public void deleteTask(String userId, String taskId) {
        ensureTaskBelongsToProvider(taskId, userId);
        codexTaskService.deleteTask(userId, taskId);
    }

    @Override
    public Object resyncTask(String taskId, String userId) {
        ensureTaskBelongsToProvider(taskId, userId);
        return codexTaskService.resyncTask(taskId, userId);
    }

    @Override
    public Object rewindTask(String taskId, String userId, Map<String, Object> params) {
        ensureTaskBelongsToProvider(taskId, userId);
        return codexTaskService.rewindTask(taskId, userId, params);
    }

    private Map<String, Object> normalizeParams(Map<String, Object> params) {
        if (params == null) {
            throw new IllegalArgumentException("codex-biz-worker params are required");
        }
        Map<String, Object> normalized = new LinkedHashMap<>(params);
        normalized.put("providerType", getProviderType());

        String codexHomeKey = firstNonBlank(
                stringParam(normalized, "codexHomeKey"),
                stringParam(normalized, "codex_home_key"),
                stringParam(normalized, "privateAccountId"),
                stringParam(normalized, "private_account_id"));
        if (codexHomeKey == null) {
            throw new IllegalArgumentException(
                    "codex-biz-worker requires codexHomeKey or privateAccountId");
        }
        normalized.put("codexHomeKey", codexHomeKey);

        Map<String, Object> policy = mapParam(firstPresent(normalized, "codexPolicy", "codex_policy"));
        if (firstNonBlank(
                stringParam(normalized, "sandboxMode"),
                stringParam(normalized, "sandbox_mode"),
                stringMap(policy, "sandboxMode"),
                stringMap(policy, "sandbox_mode")) == null) {
            normalized.put("sandboxMode", DEFAULT_SANDBOX_MODE);
        }
        if (firstNonBlank(
                stringParam(normalized, "approvalPolicy"),
                stringParam(normalized, "approval_policy"),
                stringMap(policy, "approvalPolicy"),
                stringMap(policy, "approval_policy")) == null) {
            normalized.put("approvalPolicy", DEFAULT_APPROVAL_POLICY);
        }
        if (firstPresent(normalized, "networkAccessEnabled", "network_access_enabled") == null
                && firstPresent(policy, "networkAccessEnabled", "network_access_enabled") == null) {
            normalized.put("networkAccessEnabled", false);
        }
        if (firstNonBlank(
                stringParam(normalized, "webSearchMode"),
                stringParam(normalized, "web_search_mode"),
                stringMap(policy, "webSearchMode"),
                stringMap(policy, "web_search_mode")) == null) {
            normalized.put("webSearchMode", DEFAULT_WEB_SEARCH_MODE);
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

    private Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private String stringParam(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapParam(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private String stringMap(Map<String, Object> values, String key) {
        if (values == null) {
            return null;
        }
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
