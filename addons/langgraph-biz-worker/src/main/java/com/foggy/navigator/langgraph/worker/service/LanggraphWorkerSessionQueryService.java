package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.WorkerSessionMessage;
import com.foggy.navigator.spi.agent.WorkerSessionMessageCount;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import com.foggy.navigator.spi.agent.WorkerSessionSummary;
import com.foggy.navigator.spi.agent.WorkerSessionSyncResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * LangGraph worker-session query SPI implementation.
 */
@Service
@RequiredArgsConstructor
public class LanggraphWorkerSessionQueryService implements WorkerSessionQueryProvider {

    private static final Set<TaskQueryCapability> CAPABILITIES = Set.of(
            TaskQueryCapability.LIST_WORKER_SESSIONS,
            TaskQueryCapability.GET_WORKER_SESSION_MESSAGE_COUNT,
            TaskQueryCapability.GET_WORKER_SESSION_MESSAGES,
            TaskQueryCapability.SYNC_WORKER_SESSIONS);

    private final LanggraphWorkerService workerService;
    private final SessionTaskRepository sessionTaskRepository;
    private final SessionMessageRepository sessionMessageRepository;

    @Override
    public String getProviderType() {
        return LanggraphTaskService.PROVIDER_TYPE;
    }

    @Override
    public Set<TaskQueryCapability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<WorkerSessionSummary> listWorkerSessionSummaries(String workerId, String userId) {
        assertWorkerOwnedByUser(workerId, userId);

        Map<String, SessionTaskEntity> latestBySession = new LinkedHashMap<>();
        for (SessionTaskEntity task : sessionTaskRepository.findByWorkerIdAndUserIdOrderByCreatedAtDesc(workerId, userId)) {
            if (LanggraphTaskService.PROVIDER_TYPE.equals(task.getProviderType()) && task.getSessionId() != null) {
                latestBySession.putIfAbsent(task.getSessionId(), task);
            }
        }

        return latestBySession.values().stream()
                .map(task -> WorkerSessionSummary.from(toWorkerSessionMap(task)))
                .toList();
    }

    @Deprecated(since = "1.3.1", forRemoval = false)
    @Override
    public List<Map<String, Object>> listWorkerSessions(String workerId, String userId) {
        return listWorkerSessionSummaries(workerId, userId).stream()
                .map(WorkerSessionSummary::toMap)
                .toList();
    }

    @Override
    public WorkerSessionMessageCount getWorkerSessionMessageCountResult(String workerId, String sessionId, String userId) {
        assertSessionOwnedByWorker(workerId, sessionId, userId);

        List<SessionMessageEntity> messages = sessionMessageRepository.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId);
        long userCount = messages.stream().filter(message -> "user".equalsIgnoreCase(message.getRole())).count();
        long assistantCount = messages.stream().filter(message -> "assistant".equalsIgnoreCase(message.getRole())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user_count", userCount);
        result.put("assistant_count", assistantCount);
        result.put("total", messages.size());
        return WorkerSessionMessageCount.from(result);
    }

    @Deprecated(since = "1.3.1", forRemoval = false)
    @Override
    public Map<String, Object> getWorkerSessionMessageCount(String workerId, String sessionId, String userId) {
        return getWorkerSessionMessageCountResult(workerId, sessionId, userId).toMap();
    }

    @Override
    public List<WorkerSessionMessage> listWorkerSessionMessages(String workerId, String sessionId,
                                                                String userId, Integer offset, Integer limit) {
        assertSessionOwnedByWorker(workerId, sessionId, userId);

        List<SessionMessageEntity> messages = sessionMessageRepository.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId);
        int fromIndex = Math.max(0, offset == null ? 0 : offset);
        if (fromIndex >= messages.size()) {
            return List.of();
        }
        int requestedLimit = limit == null ? messages.size() - fromIndex : Math.max(0, limit);
        int toIndex = Math.min(messages.size(), fromIndex + requestedLimit);
        if (toIndex <= fromIndex) {
            return List.of();
        }

        return messages.subList(fromIndex, toIndex).stream()
                .map(message -> WorkerSessionMessage.from(toWorkerSessionMessageMap(message)))
                .toList();
    }

    @Deprecated(since = "1.3.1", forRemoval = false)
    @Override
    public List<Map<String, Object>> getWorkerSessionMessages(String workerId, String sessionId,
                                                              String userId, Integer offset, Integer limit) {
        return listWorkerSessionMessages(workerId, sessionId, userId, offset, limit).stream()
                .map(WorkerSessionMessage::toMap)
                .toList();
    }

    @Override
    public WorkerSessionSyncResult syncWorkerSessionState(String workerId, String userId, String tenantId) {
        assertWorkerOwnedByUser(workerId, userId);

        long total = sessionTaskRepository.findByWorkerIdAndUserIdOrderByCreatedAtDesc(workerId, userId).stream()
                .filter(task -> LanggraphTaskService.PROVIDER_TYPE.equals(task.getProviderType()))
                .map(SessionTaskEntity::getSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("synced", 0);
        result.put("total", total);
        result.put("source", "session-store");
        return WorkerSessionSyncResult.from(result);
    }

    @Deprecated(since = "1.3.1", forRemoval = false)
    @Override
    public Map<String, Object> syncWorkerSessions(String workerId, String userId, String tenantId) {
        return syncWorkerSessionState(workerId, userId, tenantId).toMap();
    }

    private void assertWorkerOwnedByUser(String workerId, String userId) {
        LanggraphWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (worker == null || !Objects.equals(worker.getUserId(), userId)) {
            throw new IllegalArgumentException("Worker not found: " + workerId);
        }
    }

    private void assertSessionOwnedByWorker(String workerId, String sessionId, String userId) {
        assertWorkerOwnedByUser(workerId, userId);
        boolean owned = sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc(sessionId).stream()
                .anyMatch(task -> LanggraphTaskService.PROVIDER_TYPE.equals(task.getProviderType())
                        && Objects.equals(workerId, task.getWorkerId())
                        && Objects.equals(userId, task.getUserId()));
        if (!owned) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
    }

    private Map<String, Object> toWorkerSessionMap(SessionTaskEntity task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("session_id", task.getSessionId());
        map.put("sessionId", task.getSessionId());
        map.put("worker_id", task.getWorkerId());
        map.put("workerId", task.getWorkerId());
        map.put("project", firstNotBlank(task.getCwd(), task.getDirectoryId(), "LangGraph"));
        map.put("model", firstNotBlank(task.getModel(), "biz-default"));
        map.put("status", task.getStatus());
        map.put("latest_task_id", task.getTaskId());
        map.put("taskId", task.getTaskId());
        map.put("prompt", task.getPrompt());
        map.put("created_at", task.getCreatedAt());
        map.put("updated_at", task.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toWorkerSessionMessageMap(SessionMessageEntity message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", firstNotBlank(message.getRole(), "assistant"));
        map.put("content", firstNotBlank(message.getContent(), ""));
        map.put("timestamp", message.getCreatedAt());
        map.put("taskId", message.getTaskId());
        return map;
    }

    private static String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
