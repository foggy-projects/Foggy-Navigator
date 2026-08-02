package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.agent.framework.diagnostic.ErrorEnvelope;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.session.service.ErrorDiagnosticService;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskSearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Read-only Codex Task lookup, listing, grouping, paging and search projection. */
@Service
@Transactional(readOnly = true)
public class CodexTaskQueryService {

    private static final List<String> ACTIVE_STATUSES =
            List.of("RUNNING", "AWAITING_PERMISSION", "AWAITING_INPUT", "CANCEL_REQUESTED");

    private final CodexTaskRepository taskRepository;
    private final CodexTaskProjectionMapper taskProjectionMapper = new CodexTaskProjectionMapper();

    @Autowired(required = false)
    @Nullable
    private SessionTaskRepository sessionTaskRepository;

    @Autowired(required = false)
    @Nullable
    private SessionEntityRepository sessionEntityRepository;

    @Autowired(required = false)
    @Nullable
    private ErrorDiagnosticService errorDiagnosticService;

    public CodexTaskQueryService(CodexTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public DispatchTaskDTO getTask(String userId, String taskId) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return projectTask(entity);
    }

    public DispatchTaskDTO getTaskForProvider(
            String userId,
            String taskId,
            String providerType) {
        CodexTaskEntity entity = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        ResolvedBatch facts = resolveFacts(List.of(entity), true);
        if (!normalizeProviderType(providerType).equals(facts.forEntity(entity).providerType())) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return projectTasks(List.of(entity), facts).get(0);
    }

    public CodexTaskEntity getTaskEntity(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    public List<DispatchTaskDTO> listTasks(String userId) {
        return projectQuery(taskRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    public List<DispatchTaskDTO> listTasksForProvider(String userId, String providerType) {
        return projectProviderQuery(
                taskRepository.findByUserIdOrderByCreatedAtDesc(userId), providerType);
    }

    public List<DispatchTaskDTO> listTasksByWorker(String userId, String workerId) {
        return projectQuery(taskRepository.findByWorkerIdAndUserId(workerId, userId));
    }

    public List<DispatchTaskDTO> listTasksByWorkerForProvider(
            String userId,
            String workerId,
            String providerType) {
        return projectProviderQuery(
                taskRepository.findByWorkerIdAndUserId(workerId, userId), providerType);
    }

    public Optional<DispatchTaskDTO> getTaskById(String taskId) {
        return getTaskByIdForProvider(taskId, ProviderRouteRegistry.PROVIDER_CODEX_WORKER);
    }

    public Optional<DispatchTaskDTO> getTaskByIdAndUser(String taskId, String userId) {
        return getTaskByIdAndUserForProvider(
                taskId, userId, ProviderRouteRegistry.PROVIDER_CODEX_WORKER);
    }

    public List<DispatchTaskDTO> listTasksBySession(String sessionId) {
        return listTasksBySessionForProvider(
                sessionId, ProviderRouteRegistry.PROVIDER_CODEX_WORKER);
    }

    public Optional<DispatchTaskDTO> getTaskByIdForProvider(
            String taskId,
            String providerType) {
        return taskRepository.findByTaskId(taskId)
                .flatMap(entity -> projectIfProvider(entity, providerType));
    }

    public Optional<DispatchTaskDTO> getTaskByIdAndUserForProvider(
            String taskId,
            String userId,
            String providerType) {
        return taskRepository.findByTaskIdAndUserId(taskId, userId)
                .flatMap(entity -> projectIfProvider(entity, providerType));
    }

    public List<DispatchTaskDTO> listTasksBySessionForProvider(
            String sessionId,
            String providerType) {
        return projectProviderQuery(taskRepository.findBySessionId(sessionId), providerType);
    }

    public List<DispatchTaskDTO> listActiveDispatchTasks(String userId) {
        return listActiveDispatchTasksForProvider(
                userId, ProviderRouteRegistry.PROVIDER_CODEX_WORKER);
    }

    public List<DispatchTaskDTO> listActiveDispatchTasksForProvider(
            String userId,
            String providerType) {
        return projectProviderQuery(
                taskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                        userId, ACTIVE_STATUSES),
                providerType);
    }

    public TaskPageResult listTaskPage(
            String userId,
            int page,
            int size,
            String state) {
        return listTasksPagedForProvider(
                userId, page, size, state, ProviderRouteRegistry.PROVIDER_CODEX_WORKER);
    }

    public TaskPageResult listTasksPagedForProvider(
            String userId,
            int page,
            int size,
            String state,
            String providerType) {
        List<CodexTaskEntity> tasks = taskRepository.findByUserIdOrderByCreatedAtDesc(userId);
        ResolvedBatch facts = resolveFacts(tasks, true);
        return buildSessionPage(
                filterByProvider(tasks, facts, providerType), facts, page, size, state);
    }

    public TaskPageResult listTasksPagedForProvider(
            String userId,
            String tenantId,
            int page,
            int size,
            String state,
            String workerId,
            String providerType) {
        List<CodexTaskEntity> tasks = taskRepository
                .findByUserIdAndTenantIdOrderByCreatedAtDesc(userId, tenantId);
        ResolvedBatch facts = resolveFacts(tasks, true);
        tasks = filterByProvider(tasks, facts, providerType);
        if (workerId != null && !workerId.isBlank()) {
            String normalizedWorkerId = workerId.trim();
            tasks = tasks.stream()
                    .filter(task -> normalizedWorkerId.equals(task.getWorkerId()))
                    .toList();
        }
        return buildSessionPage(tasks, facts, page, size, state);
    }

    public TaskPageResult listDirectoryTaskPage(
            String userId,
            String directoryId,
            int page,
            int size,
            String state) {
        return listTasksByDirectoryPagedForProvider(
                userId,
                directoryId,
                page,
                size,
                state,
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER);
    }

    public TaskPageResult listTasksByDirectoryPagedForProvider(
            String userId,
            String directoryId,
            int page,
            int size,
            String state,
            String providerType) {
        List<CodexTaskEntity> tasks = taskRepository
                .findByDirectoryIdAndUserIdOrderByCreatedAtDesc(directoryId, userId);
        ResolvedBatch facts = resolveFacts(tasks, true);
        return buildSessionPage(
                filterByProvider(tasks, facts, providerType), facts, page, size, state);
    }

    public TaskSearchResult searchSessionPage(
            String userId,
            String keyword,
            String workerId,
            String directoryId,
            int page,
            int size) {
        boolean hasFilter = hasText(keyword) || hasText(workerId) || hasText(directoryId);
        if (!hasFilter) {
            return TaskSearchResult.empty(page, size);
        }
        String normalizedKeyword = keyword != null
                ? keyword.trim().toLowerCase(Locale.ROOT)
                : null;
        return searchSessionsForProvider(
                userId,
                normalizedKeyword,
                workerId,
                directoryId,
                page,
                size,
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER);
    }

    public TaskSearchResult searchSessionsForProvider(
            String userId,
            String normalizedKeyword,
            String workerId,
            String directoryId,
            int page,
            int size,
            String providerType) {
        List<CodexTaskEntity> tasks = taskRepository.findByUserIdOrderByCreatedAtDesc(userId);
        ResolvedBatch facts = resolveFacts(tasks, false);
        List<List<CodexTaskEntity>> sessions = new ArrayList<>(groupTasksBySession(
                filterByProvider(tasks, facts, providerType)).values());
        List<Map<String, Object>> filtered = sessions.stream()
                .filter(sessionTasks -> matchesSessionFilters(
                        sessionTasks, normalizedKeyword, workerId, directoryId))
                .map(this::toSearchResult)
                .sorted((left, right) -> compareNullableTime(
                        (LocalDateTime) right.get("updatedAt"),
                        (LocalDateTime) left.get("updatedAt")))
                .toList();

        long total = filtered.size();
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return TaskSearchResult.of(filtered.subList(from, to), total, page, size);
    }

    public DispatchTaskDTO projectTask(CodexTaskEntity entity) {
        ResolvedBatch facts = resolveFacts(List.of(entity), true);
        return projectTasks(List.of(entity), facts).get(0);
    }

    private Optional<DispatchTaskDTO> projectIfProvider(
            CodexTaskEntity entity,
            String providerType) {
        ResolvedBatch facts = resolveFacts(List.of(entity), true);
        if (!normalizeProviderType(providerType).equals(facts.forEntity(entity).providerType())) {
            return Optional.empty();
        }
        return Optional.of(projectTasks(List.of(entity), facts).get(0));
    }

    private List<DispatchTaskDTO> projectQuery(List<CodexTaskEntity> entities) {
        ResolvedBatch facts = resolveFacts(entities, true);
        return projectTasks(entities, facts);
    }

    private List<DispatchTaskDTO> projectProviderQuery(
            List<CodexTaskEntity> entities,
            String providerType) {
        ResolvedBatch facts = resolveFacts(entities, true);
        return projectTasks(filterByProvider(entities, facts, providerType), facts);
    }

    private List<DispatchTaskDTO> projectTasks(
            List<CodexTaskEntity> entities,
            ResolvedBatch facts) {
        if (entities.isEmpty()) {
            return List.of();
        }
        LocalDateTime observedAt = LocalDateTime.now();
        Map<String, ErrorEnvelope> errors = resolveErrorEnvelopes(entities);
        return entities.stream()
                .map(entity -> {
                    ResolvedTaskFacts taskFacts = facts.forEntity(entity);
                    return taskProjectionMapper.toDispatchTask(
                            entity,
                            taskFacts.logicalAgentId(),
                            taskFacts.providerType(),
                            taskFacts.contextId(),
                            errors.get(entity.getTaskId()),
                            observedAt);
                })
                .toList();
    }

    private TaskPageResult buildSessionPage(
            List<CodexTaskEntity> tasks,
            ResolvedBatch facts,
            int page,
            int size,
            String interactionState) {
        Set<String> states = parseInteractionStates(interactionState);
        List<List<CodexTaskEntity>> sessions = new ArrayList<>(
                groupTasksBySession(tasks).values());
        if (!states.isEmpty()) {
            sessions = sessions.stream()
                    .filter(sessionTasks -> states.contains(taskProjectionMapper.interactionState(
                            sessionTasks.get(0).getStatus())))
                    .toList();
        }

        long totalSessions = sessions.size();
        int from = Math.min(page * size, sessions.size());
        int to = Math.min(from + size, sessions.size());
        List<CodexTaskEntity> pageTasks = sessions.subList(from, to).stream()
                .flatMap(Collection::stream)
                .toList();
        return TaskPageResult.of(
                projectTasks(pageTasks, facts), totalSessions, page, size);
    }

    private Map<String, List<CodexTaskEntity>> groupTasksBySession(
            List<CodexTaskEntity> tasks) {
        Map<String, List<CodexTaskEntity>> grouped = new LinkedHashMap<>();
        for (CodexTaskEntity task : tasks) {
            String sessionKey = hasText(task.getSessionId())
                    ? task.getSessionId()
                    : "task:" + task.getTaskId();
            grouped.computeIfAbsent(sessionKey, ignored -> new ArrayList<>()).add(task);
        }
        return grouped;
    }

    private Set<String> parseInteractionStates(String interactionState) {
        if (!hasText(interactionState)) {
            return Set.of();
        }
        return Arrays.stream(interactionState.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
    }

    private boolean matchesSessionFilters(
            List<CodexTaskEntity> tasks,
            String keyword,
            String workerId,
            String directoryId) {
        CodexTaskEntity latestTask = tasks.get(0);
        if (hasText(workerId) && !workerId.equals(latestTask.getWorkerId())) {
            return false;
        }
        if (hasText(directoryId) && !directoryId.equals(latestTask.getDirectoryId())) {
            return false;
        }
        if (!hasText(keyword)) {
            return true;
        }
        return tasks.stream().anyMatch(task -> containsIgnoreCase(task.getPrompt(), keyword))
                || tasks.stream().anyMatch(task -> containsIgnoreCase(task.getResultText(), keyword));
    }

    private Map<String, Object> toSearchResult(List<CodexTaskEntity> tasks) {
        CodexTaskEntity latestTask = tasks.get(0);
        CodexTaskEntity earliestTask = tasks.get(tasks.size() - 1);
        BigDecimal totalCost = tasks.stream()
                .map(task -> task.getCostUsd() != null ? task.getCostUsd() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDateTime updatedAt = tasks.stream()
                .map(CodexTaskEntity::getUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(latestTask.getUpdatedAt());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", latestTask.getSessionId());
        result.put("workerId", latestTask.getWorkerId());
        result.put("directoryId", latestTask.getDirectoryId());
        result.put("firstPrompt", truncate(earliestTask.getPrompt(), 200));
        result.put("customTitle", null);
        result.put("tags", List.of());
        result.put("interactionState", taskProjectionMapper.interactionState(latestTask.getStatus()));
        result.put("latestTaskId", latestTask.getTaskId());
        result.put("latestStatus", latestTask.getStatus());
        result.put("model", latestTask.getModel());
        result.put("cwd", latestTask.getCwd());
        result.put("source", latestTask.getSource());
        result.put("totalCost", totalCost);
        result.put("createdAt", earliestTask.getCreatedAt());
        result.put("updatedAt", updatedAt);
        return result;
    }

    private ResolvedBatch resolveFacts(
            List<CodexTaskEntity> entities,
            boolean projectionRequired) {
        IdentityHashMap<CodexTaskEntity, ResolvedTaskFacts> resolved = new IdentityHashMap<>();
        if (entities.isEmpty()) {
            return new ResolvedBatch(resolved);
        }

        LinkedHashSet<String> taskIds = entities.stream()
                .filter(entity -> !hasText(entity.getProviderType())
                        || (projectionRequired && (!hasText(entity.getResolvedAgentId())
                        || !hasText(entity.getContextId()))))
                .map(CodexTaskEntity::getTaskId)
                .filter(CodexTaskQueryService::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, SessionTaskEntity> sessionTasks = new LinkedHashMap<>();
        if (sessionTaskRepository != null && !taskIds.isEmpty()) {
            sessionTaskRepository.findByTaskIdIn(taskIds).forEach(sessionTask ->
                    sessionTasks.putIfAbsent(sessionTask.getTaskId(), sessionTask));
        }

        LinkedHashSet<String> sessionIds = entities.stream()
                .filter(entity -> {
                    SessionTaskEntity sessionTask = sessionTasks.get(entity.getTaskId());
                    boolean providerMissing = !hasText(firstNonBlank(
                            entity.getProviderType(),
                            sessionTask != null ? sessionTask.getProviderType() : null));
                    boolean agentMissing = projectionRequired && !hasText(firstNonBlank(
                            entity.getResolvedAgentId(),
                            sessionTask != null ? sessionTask.getAgentId() : null));
                    return providerMissing || agentMissing;
                })
                .map(CodexTaskEntity::getSessionId)
                .filter(CodexTaskQueryService::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, SessionEntity> sessions = new LinkedHashMap<>();
        if (sessionEntityRepository != null && !sessionIds.isEmpty()) {
            sessionEntityRepository.findAllById(sessionIds).forEach(session ->
                    sessions.putIfAbsent(session.getId(), session));
        }

        for (CodexTaskEntity entity : entities) {
            SessionTaskEntity sessionTask = sessionTasks.get(entity.getTaskId());
            SessionEntity session = sessions.get(entity.getSessionId());
            String providerType = normalizeProviderType(firstNonBlank(
                    entity.getProviderType(),
                    sessionTask != null ? sessionTask.getProviderType() : null,
                    session != null ? session.getProviderType() : null,
                    ProviderRouteRegistry.PROVIDER_CODEX_WORKER));
            String logicalAgentId = projectionRequired
                    ? firstNonBlank(
                            entity.getResolvedAgentId(),
                            sessionTask != null ? sessionTask.getAgentId() : null,
                            session != null ? session.getAgentId() : null)
                    : null;
            String contextId = projectionRequired
                    ? firstNonBlank(
                            entity.getContextId(),
                            sessionTask != null
                                    ? ProviderStateCodec.readStringOrNull(
                                            sessionTask.getTaskStateJson(),
                                            ProviderStateCodec.FIELD_CONTEXT_ID)
                                    : null)
                    : null;
            resolved.put(entity, new ResolvedTaskFacts(
                    providerType, logicalAgentId, contextId));
        }
        return new ResolvedBatch(resolved);
    }

    private Map<String, ErrorEnvelope> resolveErrorEnvelopes(
            List<CodexTaskEntity> entities) {
        Map<String, ErrorEnvelope> errors = new LinkedHashMap<>();
        if (errorDiagnosticService == null) {
            return errors;
        }
        LinkedHashSet<String> failedTaskIds = entities.stream()
                .filter(entity -> "FAILED".equals(entity.getStatus()))
                .map(CodexTaskEntity::getTaskId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String taskId : failedTaskIds) {
            errors.put(taskId, errorDiagnosticService.findLatestEnvelope(taskId));
        }
        return errors;
    }

    private List<CodexTaskEntity> filterByProvider(
            List<CodexTaskEntity> entities,
            ResolvedBatch facts,
            String providerType) {
        String expected = normalizeProviderType(providerType);
        return entities.stream()
                .filter(entity -> expected.equals(facts.forEntity(entity).providerType()))
                .toList();
    }

    private String normalizeProviderType(String providerType) {
        String normalized = firstNonBlank(
                providerType, ProviderRouteRegistry.PROVIDER_CODEX_WORKER);
        if (ProviderRouteRegistry.PROVIDER_CODEX_WORKER.equals(normalized)
                || ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER.equals(normalized)
                || ProviderRouteRegistry.PROVIDER_CODEX_BIZ_WORKER.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported Codex providerType: " + normalized);
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private int compareNullableTime(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ResolvedTaskFacts(
            String providerType,
            String logicalAgentId,
            String contextId) {
    }

    private static final class ResolvedBatch {
        private final IdentityHashMap<CodexTaskEntity, ResolvedTaskFacts> facts;

        private ResolvedBatch(IdentityHashMap<CodexTaskEntity, ResolvedTaskFacts> facts) {
            this.facts = facts;
        }

        private ResolvedTaskFacts forEntity(CodexTaskEntity entity) {
            ResolvedTaskFacts value = facts.get(entity);
            if (value == null) {
                throw new IllegalStateException("Missing resolved query facts for Codex task");
            }
            return value;
        }
    }
}
