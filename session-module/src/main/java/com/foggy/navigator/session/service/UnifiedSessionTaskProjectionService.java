package com.foggy.navigator.session.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.util.TaskResponseTimeoutSupport;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
final class UnifiedSessionTaskProjectionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SessionRepository sessionRepository;
    @Nullable
    private final WorkingDirectoryRepository workingDirectoryRepository;

    UnifiedSessionTaskProjectionService(SessionRepository sessionRepository,
                                        @Nullable WorkingDirectoryRepository workingDirectoryRepository) {
        this.sessionRepository = sessionRepository;
        this.workingDirectoryRepository = workingDirectoryRepository;
    }

    Object listTasksPagedFromSessionStore(SessionTaskRepository sessionTaskRepository,
                                          String userId,
                                          String directoryId,
                                          int page,
                                          int size,
                                          String state,
                                          boolean compact) {
        List<SessionTaskEntity> tasks = directoryId == null || directoryId.isBlank()
                ? sessionTaskRepository.findByUserIdOrderByCreatedAtDesc(userId)
                : sessionTaskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc(directoryId, userId);
        if (tasks.isEmpty()) {
            return null;
        }

        List<UnifiedSessionView> sessions = buildUnifiedSessionViews(tasks, userId, directoryId, state);
        int from = Math.min(page * size, sessions.size());
        int to = Math.min(from + size, sessions.size());
        List<?> content = sessions.subList(from, to).stream()
                .map(view -> compact ? toCompactSessionSummaryItem(view) : toSessionSummaryDispatchTaskDTO(view))
                .toList();

        return Map.of(
                "content", content,
                "totalSessions", (long) sessions.size(),
                "page", page,
                "size", size
        );
    }

    Object searchSessionsFromSessionStore(SessionTaskRepository sessionTaskRepository,
                                          String userId,
                                          String keyword,
                                          String workerId,
                                          String directoryId,
                                          int page,
                                          int size) {
        List<SessionTaskEntity> tasks = sessionTaskRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (tasks.isEmpty()) {
            return null;
        }

        String normalizedKeyword = keyword != null ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        List<UnifiedSessionView> sessions = buildUnifiedSessionViews(tasks, userId, directoryId, null).stream()
                .filter(view -> matchesWorkerFilter(view, workerId))
                .filter(view -> matchesKeywordFilter(view, normalizedKeyword))
                .toList();

        int from = Math.min(page * size, sessions.size());
        int to = Math.min(from + size, sessions.size());
        List<Map<String, Object>> results = sessions.subList(from, to).stream()
                .map(this::toSearchResult)
                .toList();

        return Map.of(
                "results", results,
                "total", (long) sessions.size(),
                "page", page,
                "size", size
        );
    }

    Map<String, Object> buildTaskPageResponse(List<Object> taskItems, long totalSessions, int page, int size) {
        Map<String, List<Object>> sessions = new LinkedHashMap<>();
        for (Object item : taskItems) {
            String sessionId = readStringProperty(item, "sessionId");
            String key = (sessionId != null && !sessionId.isBlank())
                    ? sessionId
                    : Optional.ofNullable(readStringProperty(item, "taskId")).orElse(UUID.randomUUID().toString());
            sessions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }

        List<List<Object>> sortedSessions = new ArrayList<>(sessions.values());
        sortedSessions.sort((left, right) -> compareNullableTime(
                latestTaskTime(right),
                latestTaskTime(left)));

        int from = Math.min(page * size, sortedSessions.size());
        int to = Math.min(from + size, sortedSessions.size());
        List<Object> content = sortedSessions.subList(from, to).stream()
                .map(this::toSessionSummaryItem)
                .toList();

        return Map.of(
                "content", content,
                "totalSessions", totalSessions,
                "page", page,
                "size", size
        );
    }

    TaskPageEnvelope toTaskPageEnvelope(Object pageResult) {
        if (pageResult instanceof TaskPageResult result) {
            return new TaskPageEnvelope(new ArrayList<>(result.content()), result.totalSessions());
        }
        return new TaskPageEnvelope(
                readListProperty(pageResult, "content"),
                readLongProperty(pageResult, "totalSessions")
        );
    }

    SearchEnvelope toSearchEnvelope(Object searchResult) {
        if (searchResult instanceof TaskSearchResult result) {
            return new SearchEnvelope(new ArrayList<>(result.results()), result.total());
        }
        return new SearchEnvelope(
                readListProperty(searchResult, "results"),
                readLongProperty(searchResult, "total")
        );
    }

    Object toCompactTaskItem(Object task) {
        Map<String, Object> item = new LinkedHashMap<>();
        putIfPresent(item, "taskId", readProperty(task, "taskId"));
        putIfPresent(item, "workerTaskId", readProperty(task, "workerTaskId"));
        putIfPresent(item, "sessionId", readProperty(task, "sessionId"));
        putIfPresent(item, "parentSessionId", readProperty(task, "parentSessionId"));
        putIfPresent(item, "workerId", readProperty(task, "workerId"));
        putIfPresent(item, "agentId", readProperty(task, "agentId"));
        putIfPresent(item, "providerType", readProperty(task, "providerType"));
        putIfPresent(item, "prompt", truncate(readStringProperty(task, "prompt"), 500));
        putIfPresent(item, "cwd", readProperty(task, "cwd"));
        putIfPresent(item, "directoryId", readProperty(task, "directoryId"));
        putIfPresent(item, "status", readProperty(task, "status"));
        putIfPresent(item, "model", readProperty(task, "model"));
        putIfPresent(item, "modelConfigId", readProperty(task, "modelConfigId"));
        putIfPresent(item, "costUsd", readProperty(task, "costUsd"));
        putIfPresent(item, "inputTokens", readProperty(task, "inputTokens"));
        putIfPresent(item, "outputTokens", readProperty(task, "outputTokens"));
        putIfPresent(item, "durationMs", readProperty(task, "durationMs"));
        putIfPresent(item, "numTurns", readProperty(task, "numTurns"));
        putIfPresent(item, "lastOutputAt", readProperty(task, "lastOutputAt"));
        putIfPresent(item, "responseTimedOut", readProperty(task, "responseTimedOut"));
        putIfPresent(item, "silentForSeconds", readProperty(task, "silentForSeconds"));
        putIfPresent(item, "responseTimeoutThresholdSeconds", readProperty(task, "responseTimeoutThresholdSeconds"));
        putIfPresent(item, "source", readProperty(task, "source"));
        putIfPresent(item, "createdAt", readProperty(task, "createdAt"));
        putIfPresent(item, "updatedAt", readProperty(task, "updatedAt"));
        putIfPresent(item, "sessionTaskCount", readProperty(task, "sessionTaskCount"));
        putIfPresent(item, "sessionTotalCostUsd", readProperty(task, "sessionTotalCostUsd"));
        putIfPresent(item, "sessionInputTokens", readProperty(task, "sessionInputTokens"));
        putIfPresent(item, "sessionOutputTokens", readProperty(task, "sessionOutputTokens"));
        putIfPresent(item, "sessionFirstPrompt", truncate(readStringProperty(task, "sessionFirstPrompt"), 500));
        putIfPresent(item, "claudeSessionId", readProperty(task, "claudeSessionId"));
        putIfPresent(item, "codexThreadId", readProperty(task, "codexThreadId"));
        putIfPresent(item, "geminiSessionId", readProperty(task, "geminiSessionId"));
        putIfPresent(item, "contextId", readProperty(task, "contextId"));
        putIfPresent(item, "fileCheckpointingEnabled", readProperty(task, "fileCheckpointingEnabled"));
        return item;
    }

    List<DispatchTaskDTO> toDispatchTaskDTOs(List<SessionTaskEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        Map<String, String> directoryNames = loadDirectoryNames(entities);
        Map<String, SessionEntity> sessionsById = loadSessions(entities.stream()
                .map(SessionTaskEntity::getSessionId)
                .filter(Objects::nonNull)
                .toList());
        return entities.stream()
                .map(entity -> toDispatchTaskDTO(entity, directoryNames, sessionsById))
                .toList();
    }

    DispatchTaskDTO toDispatchTaskDTO(SessionTaskEntity entity) {
        return toDispatchTaskDTO(
                entity,
                loadDirectoryNames(List.of(entity)),
                loadSessions(List.of(entity.getSessionId()))
        );
    }

    String readStringProperty(Object target, String property) {
        Object value = readProperty(target, property);
        return value != null ? value.toString() : null;
    }

    LocalDateTime readDateTimeProperty(Object target, String property) {
        Object value = readProperty(target, property);
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toLocalDateTime();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return OffsetDateTime.parse(text).toLocalDateTime();
            } catch (Exception ignored) {
                try {
                    return LocalDateTime.parse(text);
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
        }
        return null;
    }

    int compareNullableTime(LocalDateTime left, LocalDateTime right) {
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

    private LocalDateTime latestTaskTime(List<Object> tasks) {
        return tasks.stream()
                .map(this::latestItemTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private LocalDateTime latestItemTime(Object task) {
        LocalDateTime createdAt = readDateTimeProperty(task, "createdAt");
        LocalDateTime updatedAt = readDateTimeProperty(task, "updatedAt");
        return compareNullableTime(createdAt, updatedAt) >= 0 ? createdAt : updatedAt;
    }

    private List<Object> readListProperty(Object target, String property) {
        Object value = readProperty(target, property);
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return List.of();
    }

    private long readLongProperty(Object target, String property) {
        Object value = readProperty(target, property);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private Object readProperty(Object target, String property) {
        if (target == null) {
            return null;
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(property);
        }
        try {
            Method getter = target.getClass().getMethod("get" + Character.toUpperCase(property.charAt(0)) + property.substring(1));
            return getter.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<UnifiedSessionView> buildUnifiedSessionViews(List<SessionTaskEntity> tasks, String userId,
                                                              String directoryId, String state) {
        Map<String, List<SessionTaskEntity>> grouped = groupSessionTasks(tasks);
        Map<String, SessionEntity> sessionsById = loadSessions(grouped.keySet());
        Set<String> stateFilter = parseInteractionStates(state);

        return grouped.entrySet().stream()
                .map(entry -> toUnifiedSessionView(entry.getKey(), entry.getValue(), sessionsById.get(entry.getKey())))
                .filter(view -> view.session() == null || view.session().getDeletedAt() == null)
                .filter(view -> view.session() == null || userId.equals(view.session().getUserId()))
                .filter(view -> matchesDirectoryFilter(view, directoryId))
                .filter(view -> stateFilter.isEmpty() || stateFilter.contains(resolveInteractionState(view)))
                .sorted((left, right) -> compareNullableTime(resolveSessionSortTime(right), resolveSessionSortTime(left)))
                .toList();
    }

    private Map<String, List<SessionTaskEntity>> groupSessionTasks(List<SessionTaskEntity> tasks) {
        Map<String, List<SessionTaskEntity>> grouped = new LinkedHashMap<>();
        for (SessionTaskEntity task : tasks) {
            String key = task.getSessionId() != null && !task.getSessionId().isBlank()
                    ? task.getSessionId()
                    : "task:" + task.getTaskId();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(task);
        }
        grouped.values().forEach(group -> group.sort((left, right) -> compareNullableTime(
                firstNonNull(right.getCreatedAt(), right.getUpdatedAt()),
                firstNonNull(left.getCreatedAt(), left.getUpdatedAt()))));
        return grouped;
    }

    private Map<String, SessionEntity> loadSessions(Collection<String> sessionIds) {
        List<String> persistedSessionIds = sessionIds.stream()
                .filter(id -> id != null && !id.isBlank() && !id.startsWith("task:"))
                .toList();
        if (persistedSessionIds.isEmpty()) {
            return Map.of();
        }
        return sessionRepository.findAllById(persistedSessionIds).stream()
                .collect(Collectors.toMap(SessionEntity::getId, session -> session));
    }

    private UnifiedSessionView toUnifiedSessionView(String sessionKey, List<SessionTaskEntity> tasks, SessionEntity session) {
        SessionTaskEntity latestTask = tasks.get(0);
        SessionTaskEntity earliestTask = tasks.get(tasks.size() - 1);
        return new UnifiedSessionView(sessionKey, session, tasks, latestTask, earliestTask);
    }

    private boolean matchesWorkerFilter(UnifiedSessionView view, String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return true;
        }
        String currentWorkerId = firstNonBlank(
                view.latestTask().getWorkerId(),
                view.session() != null ? view.session().getCurrentWorkerId() : null
        );
        return workerId.equals(currentWorkerId);
    }

    private boolean matchesDirectoryFilter(UnifiedSessionView view, String directoryId) {
        if (directoryId == null || directoryId.isBlank()) {
            return true;
        }
        String currentDirectoryId = firstNonBlank(
                view.latestTask().getDirectoryId(),
                view.session() != null ? view.session().getCurrentDirectoryId() : null
        );
        return directoryId.equals(currentDirectoryId);
    }

    private boolean matchesKeywordFilter(UnifiedSessionView view, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        if (view.session() != null) {
            if (containsIgnoreCase(view.session().getTitle(), keyword)
                    || containsIgnoreCase(view.session().getTagsJson(), keyword)) {
                return true;
            }
        }
        return view.tasks().stream().anyMatch(task ->
                containsIgnoreCase(task.getPrompt(), keyword)
                        || containsIgnoreCase(task.getResultText(), keyword));
    }

    private Map<String, Object> toSearchResult(UnifiedSessionView view) {
        BigDecimal totalCost = view.tasks().stream()
                .map(task -> task.getCostUsd() != null ? task.getCostUsd() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", firstNonBlank(view.latestTask().getSessionId(), view.sessionKey()));
        result.put("parentSessionId", view.session() != null ? view.session().getParentSessionId() : null);
        result.put("workerId", firstNonBlank(view.latestTask().getWorkerId(),
                view.session() != null ? view.session().getCurrentWorkerId() : null));
        result.put("directoryId", firstNonBlank(view.latestTask().getDirectoryId(),
                view.session() != null ? view.session().getCurrentDirectoryId() : null));
        result.put("firstPrompt", truncate(view.earliestTask().getPrompt(), 200));
        result.put("customTitle", view.session() != null ? view.session().getTitle() : null);
        result.put("tags", view.session() != null ? parseTags(view.session().getTagsJson()) : List.of());
        result.put("interactionState", resolveInteractionState(view));
        result.put("milestoneId", view.session() != null ? view.session().getMilestoneId() : null);
        result.put("latestTaskId", view.latestTask().getTaskId());
        result.put("latestStatus", view.latestTask().getStatus());
        result.put("model", firstNonBlank(view.latestTask().getModel(),
                view.session() != null ? view.session().getLatestModel() : null));
        result.put("modelConfigId", view.latestTask().getModelConfigId());
        result.put("cwd", view.latestTask().getCwd());
        result.put("source", view.latestTask().getSource());
        result.put("totalCost", totalCost);
        result.put("createdAt", view.earliestTask().getCreatedAt());
        result.put("updatedAt", resolveSessionSortTime(view));
        return result;
    }

    private DispatchTaskDTO toSessionSummaryDispatchTaskDTO(UnifiedSessionView view) {
        DispatchTaskDTO summary = toDispatchTaskDTO(view.latestTask());
        applySessionSummaryFields(
                summary,
                view.tasks().size(),
                sumCost(view.tasks()),
                sumInputTokens(view.tasks()),
                sumOutputTokens(view.tasks()),
                view.earliestTask().getPrompt()
        );
        return summary;
    }

    private Map<String, Object> toCompactSessionSummaryItem(UnifiedSessionView view) {
        SessionTaskEntity latestTask = view.latestTask();
        SessionTaskEntity earliestTask = view.earliestTask();
        SessionEntity session = view.session();
        Map<String, Object> state = parseJsonObject(latestTask.getTaskStateJson());

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskId", latestTask.getTaskId());
        item.put("workerTaskId", latestTask.getProviderTaskId());
        item.put("sessionId", firstNonBlank(latestTask.getSessionId(), view.sessionKey()));
        item.put("parentSessionId", session != null ? session.getParentSessionId() : null);
        item.put("workerId", firstNonBlank(latestTask.getWorkerId(), session != null ? session.getCurrentWorkerId() : null));
        item.put("agentId", latestTask.getAgentId());
        item.put("providerType", latestTask.getProviderType());
        item.put("prompt", truncate(latestTask.getPrompt(), 500));
        item.put("cwd", latestTask.getCwd());
        item.put("directoryId", firstNonBlank(latestTask.getDirectoryId(), session != null ? session.getCurrentDirectoryId() : null));
        item.put("status", latestTask.getStatus());
        item.put("model", firstNonBlank(latestTask.getModel(), session != null ? session.getLatestModel() : null));
        item.put("modelConfigId", latestTask.getModelConfigId());
        item.put("costUsd", latestTask.getCostUsd());
        item.put("inputTokens", latestTask.getInputTokens());
        item.put("outputTokens", latestTask.getOutputTokens());
        item.put("durationMs", latestTask.getDurationMs());
        item.put("numTurns", latestTask.getNumTurns());
        item.put("lastOutputAt", latestTask.getLastOutputAt());
        item.put("responseTimedOut", TaskResponseTimeoutSupport.isResponseTimedOut(
                latestTask.getStatus(), latestTask.getLastOutputAt(), latestTask.getCreatedAt(), LocalDateTime.now()));
        item.put("silentForSeconds", TaskResponseTimeoutSupport.silentForSeconds(
                latestTask.getStatus(), latestTask.getLastOutputAt(), latestTask.getCreatedAt(), LocalDateTime.now()));
        item.put("responseTimeoutThresholdSeconds", TaskResponseTimeoutSupport.DEFAULT_RESPONSE_TIMEOUT_SECONDS);
        item.put("source", latestTask.getSource());
        item.put("createdAt", latestTask.getCreatedAt());
        item.put("updatedAt", latestTask.getUpdatedAt());
        item.put("sessionTaskCount", view.tasks().size());
        item.put("sessionTotalCostUsd", sumCost(view.tasks()));
        item.put("sessionInputTokens", sumInputTokens(view.tasks()));
        item.put("sessionOutputTokens", sumOutputTokens(view.tasks()));
        item.put("sessionFirstPrompt", truncate(earliestTask.getPrompt(), 500));
        item.put("claudeSessionId", asString(state.get("claudeSessionId")));
        item.put("codexThreadId", asString(state.get("codexThreadId")));
        item.put("geminiSessionId", asString(state.get("geminiSessionId")));
        item.put("contextId", asString(state.get("contextId")));
        item.put("fileCheckpointingEnabled", asBoolean(state.get("fileCheckpointingEnabled")));
        item.put("interactionState", resolveInteractionState(view));
        return item;
    }

    private Object toSessionSummaryItem(List<Object> sessionTasks) {
        Object latest = sessionTasks.stream()
                .max((left, right) -> compareNullableTime(latestItemTime(left), latestItemTime(right)))
                .orElse(null);
        if (latest == null) {
            return Map.of();
        }

        BigDecimal totalCost = sessionTasks.stream()
                .map(task -> readProperty(task, "costUsd"))
                .map(this::toBigDecimal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Long inputTokens = sumLongProperty(sessionTasks, "inputTokens");
        Long outputTokens = sumLongProperty(sessionTasks, "outputTokens");
        String firstPrompt = sessionTasks.stream()
                .min((left, right) -> compareNullableTime(latestItemTime(left), latestItemTime(right)))
                .map(task -> readStringProperty(task, "prompt"))
                .orElse(null);

        if (latest instanceof DispatchTaskDTO dto) {
            applySessionSummaryFields(dto, sessionTasks.size(), totalCost, inputTokens, outputTokens, firstPrompt);
            return dto;
        }
        if (latest instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    copy.put(key.toString(), value);
                }
            });
            copy.put("sessionTaskCount", sessionTasks.size());
            copy.put("sessionTotalCostUsd", totalCost);
            copy.put("sessionInputTokens", inputTokens);
            copy.put("sessionOutputTokens", outputTokens);
            copy.put("sessionFirstPrompt", firstPrompt);
            return copy;
        }
        setSessionSummaryByReflection(latest, sessionTasks.size(), totalCost, inputTokens, outputTokens, firstPrompt);
        return latest;
    }

    private void applySessionSummaryFields(DispatchTaskDTO dto, int taskCount, BigDecimal totalCost,
                                           Long inputTokens, Long outputTokens, String firstPrompt) {
        dto.setSessionTaskCount(taskCount);
        dto.setSessionTotalCostUsd(totalCost);
        dto.setSessionInputTokens(inputTokens);
        dto.setSessionOutputTokens(outputTokens);
        dto.setSessionFirstPrompt(firstPrompt);
    }

    private void setSessionSummaryByReflection(Object target, int taskCount, BigDecimal totalCost,
                                               Long inputTokens, Long outputTokens, String firstPrompt) {
        invokeSetter(target, "setSessionTaskCount", Integer.class, taskCount);
        invokeSetter(target, "setSessionTotalCostUsd", BigDecimal.class, totalCost);
        invokeSetter(target, "setSessionInputTokens", Long.class, inputTokens);
        invokeSetter(target, "setSessionOutputTokens", Long.class, outputTokens);
        invokeSetter(target, "setSessionFirstPrompt", String.class, firstPrompt);
    }

    private void invokeSetter(Object target, String methodName, Class<?> parameterType, Object value) {
        try {
            Method setter = target.getClass().getMethod(methodName, parameterType);
            setter.invoke(target, value);
        } catch (Exception ignored) {
        }
    }

    private DispatchTaskDTO toDispatchTaskDTO(SessionTaskEntity entity,
                                              Map<String, String> directoryNames,
                                              Map<String, SessionEntity> sessionsById) {
        Map<String, Object> state = parseJsonObject(entity.getTaskStateJson());
        SessionEntity session = sessionsById.get(entity.getSessionId());
        String directoryId = entity.getDirectoryId();
        DispatchTaskDTO.DispatchTaskDTOBuilder builder = DispatchTaskDTO.builder()
                .taskId(entity.getTaskId())
                .workerTaskId(entity.getProviderTaskId())
                .sessionId(entity.getSessionId())
                .parentSessionId(session != null ? session.getParentSessionId() : null)
                .workerId(entity.getWorkerId())
                .userId(entity.getUserId())
                .agentId(entity.getAgentId())
                .providerType(entity.getProviderType())
                .prompt(entity.getPrompt())
                .cwd(entity.getCwd())
                .directoryId(directoryId)
                .status(entity.getStatus())
                .model(entity.getModel())
                .costUsd(entity.getCostUsd())
                .inputTokens(entity.getInputTokens())
                .outputTokens(entity.getOutputTokens())
                .durationMs(entity.getDurationMs())
                .numTurns(entity.getNumTurns())
                .resultText(entity.getResultText())
                .errorMessage(entity.getErrorMessage())
                .lastAckedSeq(entity.getLastAckedSeq())
                .lastOutputAt(entity.getLastOutputAt())
                .responseTimedOut(TaskResponseTimeoutSupport.isResponseTimedOut(
                        entity.getStatus(), entity.getLastOutputAt(), entity.getCreatedAt(), LocalDateTime.now()))
                .silentForSeconds(TaskResponseTimeoutSupport.silentForSeconds(
                        entity.getStatus(), entity.getLastOutputAt(), entity.getCreatedAt(), LocalDateTime.now()))
                .responseTimeoutThresholdSeconds(TaskResponseTimeoutSupport.DEFAULT_RESPONSE_TIMEOUT_SECONDS)
                .source(entity.getSource())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .directoryName(directoryId == null ? null : directoryNames.get(directoryId))
                .claudeSessionId(asString(state.get("claudeSessionId")))
                .codexThreadId(asString(state.get("codexThreadId")))
                .geminiSessionId(asString(state.get("geminiSessionId")))
                .contextId(asString(state.get("contextId")))
                .modelConfigId(entity.getModelConfigId())
                .fileCheckpointingEnabled(asBoolean(state.get("fileCheckpointingEnabled")));
        if (state.containsKey("checkpoints")) {
            builder.checkpoints(writeJson(state.get("checkpoints")));
        }
        return builder.build();
    }

    private Map<String, String> loadDirectoryNames(List<SessionTaskEntity> entities) {
        if (workingDirectoryRepository == null || entities == null || entities.isEmpty()) {
            return Map.of();
        }
        List<String> directoryIds = entities.stream()
                .map(SessionTaskEntity::getDirectoryId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (directoryIds.isEmpty()) {
            return Map.of();
        }
        List<WorkingDirectoryEntity> directories = workingDirectoryRepository.findByDirectoryIdIn(directoryIds);
        if (directories == null || directories.isEmpty()) {
            return Map.of();
        }
        return directories.stream()
                .collect(Collectors.toMap(
                        WorkingDirectoryEntity::getDirectoryId,
                        WorkingDirectoryEntity::getProjectName,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse task/session JSON payload: {}", json);
            return Map.of();
        }
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse session tags JSON: {}", tagsJson);
            return List.of();
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task state payload", e);
        }
    }

    private Set<String> parseInteractionStates(String interactionState) {
        if (interactionState == null || interactionState.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(interactionState.split(","))
                .map(String::trim)
                .filter(state -> !state.isEmpty())
                .collect(Collectors.toSet());
    }

    private String resolveInteractionState(UnifiedSessionView view) {
        if (view.session() != null && view.session().getInteractionState() != null
                && !view.session().getInteractionState().isBlank()) {
            return view.session().getInteractionState();
        }
        return deriveInteractionState(view.latestTask().getStatus());
    }

    private String deriveInteractionState(String taskStatus) {
        if ("RUNNING".equals(taskStatus) || "PENDING".equals(taskStatus)) {
            return "PROCESSING";
        }
        if ("COMPLETED".equals(taskStatus) || "FAILED".equals(taskStatus)
                || "ABORTED".equals(taskStatus) || "AWAITING_PERMISSION".equals(taskStatus)
                || "AWAITING_INPUT".equals(taskStatus)) {
            return "AWAITING_REPLY";
        }
        return null;
    }

    private LocalDateTime resolveSessionSortTime(UnifiedSessionView view) {
        if (view.session() != null) {
            LocalDateTime sessionTime = firstNonNull(view.session().getLastActivityAt(), view.session().getUpdatedAt());
            if (sessionTime != null) {
                return sessionTime;
            }
        }
        return firstNonNull(view.latestTask().getUpdatedAt(), view.latestTask().getCreatedAt());
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

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private BigDecimal sumCost(List<SessionTaskEntity> tasks) {
        return tasks.stream()
                .map(task -> task.getCostUsd() != null ? task.getCostUsd() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Long sumInputTokens(List<SessionTaskEntity> tasks) {
        return tasks.stream()
                .map(SessionTaskEntity::getInputTokens)
                .filter(Objects::nonNull)
                .reduce(0L, Long::sum);
    }

    private Long sumOutputTokens(List<SessionTaskEntity> tasks) {
        return tasks.stream()
                .map(SessionTaskEntity::getOutputTokens)
                .filter(Objects::nonNull)
                .reduce(0L, Long::sum);
    }

    private Long sumLongProperty(List<Object> tasks, String property) {
        return tasks.stream()
                .map(task -> readProperty(task, property))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .reduce(0L, Long::sum);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    record TaskPageEnvelope(List<Object> content, long totalSessions) {
    }

    record SearchEnvelope(List<Object> results, long total) {
    }

    private record UnifiedSessionView(String sessionKey,
                                      SessionEntity session,
                                      List<SessionTaskEntity> tasks,
                                      SessionTaskEntity latestTask,
                                      SessionTaskEntity earliestTask) {
    }
}
