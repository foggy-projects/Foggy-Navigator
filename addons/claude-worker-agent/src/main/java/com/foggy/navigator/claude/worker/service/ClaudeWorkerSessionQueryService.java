package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.WorkerSessionMessage;
import com.foggy.navigator.spi.agent.WorkerSessionMessageCount;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import com.foggy.navigator.spi.agent.WorkerSessionSummary;
import com.foggy.navigator.spi.agent.WorkerSessionSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Claude worker-session query SPI implementation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeWorkerSessionQueryService implements WorkerSessionQueryProvider {

    static final String PROVIDER_TYPE = "claude-worker";

    private static final Set<TaskQueryCapability> CAPABILITIES = Set.of(
            TaskQueryCapability.LIST_WORKER_SESSIONS,
            TaskQueryCapability.GET_WORKER_SESSION_MESSAGE_COUNT,
            TaskQueryCapability.GET_WORKER_SESSION_MESSAGES,
            TaskQueryCapability.SYNC_WORKER_SESSIONS);

    private final ClaudeWorkerService workerService;
    private final ClaudeTaskService taskService;

    @Override
    public String getProviderType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Set<TaskQueryCapability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<WorkerSessionSummary> listWorkerSessionSummaries(String workerId, String userId) {
        ClaudeWorkerEntity worker = requireWorker(workerId, userId);
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            List<Map<String, Object>> sessions = client.listSessions()
                    .block(Duration.ofSeconds(10));
            return WorkerSessionSummary.fromList(sessions);
        } catch (Exception e) {
            log.warn("Failed to list worker sessions: workerId={}, error={}", workerId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public WorkerSessionMessageCount getWorkerSessionMessageCountResult(String workerId, String sessionId,
                                                                        String userId) {
        ClaudeWorkerEntity worker = requireWorker(workerId, userId);
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            Map<String, Object> result = client.getSessionMessageCount(sessionId)
                    .block(Duration.ofSeconds(10));
            return WorkerSessionMessageCount.from(
                    result != null ? result : Map.of("user_count", 0, "assistant_count", 0, "total", 0));
        } catch (Exception e) {
            log.warn("Failed to get message count: workerId={}, sessionId={}, error={}",
                    workerId, sessionId, e.getMessage());
            return WorkerSessionMessageCount.empty();
        }
    }

    @Override
    public List<WorkerSessionMessage> listWorkerSessionMessages(String workerId, String sessionId,
                                                                String userId, Integer offset, Integer limit) {
        ClaudeWorkerEntity worker = requireWorker(workerId, userId);
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            List<Map<String, Object>> messages;
            if (offset != null || limit != null) {
                messages = client.getSessionMessages(sessionId, offset != null ? offset : 0, limit)
                        .block(Duration.ofSeconds(30));
            } else {
                messages = client.getSessionMessages(sessionId)
                        .block(Duration.ofSeconds(30));
            }
            return WorkerSessionMessage.fromList(messages);
        } catch (Exception e) {
            log.warn("Failed to get session messages: workerId={}, sessionId={}, error={}",
                    workerId, sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public WorkerSessionSyncResult syncWorkerSessionState(String workerId, String userId, String tenantId) {
        ClaudeWorkerEntity worker = requireWorker(workerId, userId);
        try {
            ClaudeWorkerClient client = workerService.createClient(worker);
            client.syncSessions().block(Duration.ofSeconds(30));

            List<Map<String, Object>> sessions = client.listSessions()
                    .block(Duration.ofSeconds(10));
            if (sessions == null) {
                sessions = List.of();
            }

            int created = taskService.syncLocalSessions(userId, tenantId, workerId, sessions);
            return WorkerSessionSyncResult.of(created, sessions.size());
        } catch (Exception e) {
            log.warn("Failed to sync sessions on worker: workerId={}, error={}", workerId, e.getMessage());
            throw new RuntimeException("同步失败: " + e.getMessage(), e);
        }
    }

    private ClaudeWorkerEntity requireWorker(String workerId, String userId) {
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
        if (worker == null || !Objects.equals(worker.getUserId(), userId)) {
            throw new IllegalArgumentException("Worker not found");
        }
        return worker;
    }
}
