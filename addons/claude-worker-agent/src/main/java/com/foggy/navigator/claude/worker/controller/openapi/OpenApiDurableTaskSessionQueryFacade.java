package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.support.BusinessAgentSessionMessageVisibility;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionListResponse;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionMessageDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionMessagesResponse;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionSummaryDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskDiagnosticsDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskEvidenceDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskMessagesResponse;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.session.service.OpenApiSessionQueryService;
import com.foggyframework.core.ex.RX;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only gathering and projection for durable Open API Task and Session views.
 *
 * <p>HTTP credentials, route resolution and logical Agent validation remain caller-owned. This
 * facade only reads durable facts after those checks and performs no provider observation,
 * lifecycle decision or mutation.
 */
@Service
@Transactional(readOnly = true)
public class OpenApiDurableTaskSessionQueryFacade {

    private final OpenApiSessionQueryService sessionQueryService;
    private final OpenApiSessionProjectionMapper sessionProjectionMapper;
    private final ObjectMapper objectMapper;
    private final OpenApiTaskProjectionMapper taskProjectionMapper = new OpenApiTaskProjectionMapper();

    public OpenApiDurableTaskSessionQueryFacade(
            OpenApiSessionQueryService sessionQueryService,
            OpenApiSessionProjectionMapper sessionProjectionMapper,
            ObjectMapper objectMapper) {
        this.sessionQueryService = sessionQueryService;
        this.sessionProjectionMapper = sessionProjectionMapper;
        this.objectMapper = objectMapper;
    }

    public OpenTaskDiagnosticsDTO loadTaskDiagnostics(
            String taskId,
            String tenantId,
            String agentId) {
        SessionTaskEntity taskEntity = requireOwnedTask(taskId, tenantId, agentId);
        String contextId = resolveContextId(taskEntity.getSessionId());
        LocalDateTime lastMessageAt = sessionQueryService.findLatestTaskMessage(taskId)
                .map(SessionMessageEntity::getCreatedAt)
                .orElse(null);
        long messagesCount = sessionQueryService.countTaskMessages(taskId);
        return taskProjectionMapper.mapDiagnostics(
                objectMapper,
                taskEntity,
                agentId,
                contextId,
                lastMessageAt,
                messagesCount);
    }

    public OpenTaskEvidenceDTO loadTaskEvidence(
            String taskId,
            String tenantId,
            String agentId) {
        SessionTaskEntity taskEntity = requireOwnedTask(taskId, tenantId, agentId);
        String contextId = resolveContextId(taskEntity.getSessionId());
        List<SessionMessageEntity> messages = sessionQueryService.getLatestTaskMessages(taskId, 200);
        return taskProjectionMapper.mapEvidence(
                objectMapper, taskEntity, agentId, contextId, messages);
    }

    public OpenTaskMessagesResponse loadTaskMessages(
            String taskId,
            String tenantId,
            String agentId,
            String cursor,
            int limit,
            boolean includeInternal) {
        SessionTaskEntity taskEntity = requireOwnedTask(taskId, tenantId, agentId);
        String contextId = resolveContextId(taskEntity.getSessionId());

        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<SessionMessageEntity> messages = sessionQueryService.getTaskMessages(
                taskId, cursor, safeLimit);
        boolean hasMore = messages.size() > safeLimit;
        List<SessionMessageEntity> page = hasMore ? messages.subList(0, safeLimit) : messages;
        String nextCursor = page.isEmpty() ? cursor : page.get(page.size() - 1).getId();

        OpenApiTaskProjectionMapper.TaskStatusProjection taskStatus =
                taskProjectionMapper.projectStatus(taskEntity.getStatus());
        List<OpenSessionMessageDTO> projectedMessages = page.stream()
                .filter(message -> includeInternal
                        || BusinessAgentSessionMessageVisibility.isVisibleByDefault(message))
                .map(message -> sessionProjectionMapper.mapMessage(
                        message, contextId, taskStatus.messageStatus()))
                .toList();

        OpenApiTaskProjectionMapper.TaskMessageProjection taskProjection =
                taskProjectionMapper.projectTaskMessages(
                        objectMapper, taskEntity, projectedMessages);
        String status = taskProjection.status().responseStatus();
        String terminalStatus = taskProjection.status().terminalStatus();
        Map<String, Object> taskState = taskProjection.taskState();
        String failureSummary = taskProjection.failureSummary();
        String failureStage = taskProjection.failureStage();
        if (projectedMessages.isEmpty() && "FAILED".equals(terminalStatus)) {
            projectedMessages = List.of(sessionProjectionMapper.mapSyntheticTaskError(
                    taskEntity,
                    contextId,
                    status,
                    terminalStatus,
                    failureSummary,
                    failureStage));
            nextCursor = "task-error:" + taskEntity.getTaskId();
        }

        return OpenTaskMessagesResponse.builder()
                .taskId(taskId)
                .contextId(contextId)
                .workerTaskId(taskEntity.getProviderTaskId())
                .providerTaskId(taskEntity.getProviderTaskId())
                .lastAckedSeq(taskEntity.getLastAckedSeq())
                .modelConfigId(OpenApiProjectionSupport.firstNonBlank(
                        taskEntity.getModelConfigId(),
                        OpenApiProjectionSupport.stringValue(taskState.get("modelConfigId"))))
                .modelConfigSource(OpenApiProjectionSupport.stringValue(
                        taskState.get("modelConfigSource")))
                .workerBackend(OpenApiProjectionSupport.firstNonBlank(
                        OpenApiProjectionSupport.stringValue(taskState.get("workerBackend")),
                        taskProjectionMapper.workerBackendFromProviderType(taskEntity.getProviderType())))
                .providerType(taskEntity.getProviderType())
                .taskSource(OpenApiProjectionSupport.firstNonBlank(
                        taskEntity.getSource(),
                        OpenApiProjectionSupport.stringValue(taskState.get("taskSource"))))
                .workerSource(OpenApiProjectionSupport.stringValue(taskState.get("workerSource")))
                .backendSource(OpenApiProjectionSupport.stringValue(taskState.get("backendSource")))
                .failureStage(failureStage)
                .failureSummary(failureSummary)
                .messages(projectedMessages)
                .status(status)
                .terminal(terminalStatus != null)
                .terminalStatus(terminalStatus)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    public OpenSessionListResponse listSessions(
            String userId,
            String agentId,
            int limit,
            String cursor) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<AgentConversationContextEntity> contexts = sessionQueryService.listSessions(
                userId, agentId, cursor, safeLimit);
        boolean hasMore = contexts.size() > safeLimit;
        List<AgentConversationContextEntity> page = hasMore
                ? contexts.subList(0, safeLimit)
                : contexts;

        List<String> sessionIds = page.stream()
                .map(AgentConversationContextEntity::getNavigatorSessionId)
                .filter(OpenApiProjectionSupport::hasText)
                .toList();
        Map<String, String> latestTaskMap = sessionQueryService.batchFindLatestTaskIds(sessionIds);
        Map<String, String> firstUserMessageMap =
                sessionQueryService.batchFindFirstUserMessageContents(sessionIds);
        List<OpenSessionSummaryDTO> summaries = page.stream()
                .map(context -> sessionProjectionMapper.mapSummary(
                        context, agentId, latestTaskMap, firstUserMessageMap))
                .toList();
        String nextCursor = page.isEmpty()
                ? null
                : page.get(page.size() - 1).getContextId();

        return OpenSessionListResponse.builder()
                .sessions(summaries)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    public OpenSessionMessagesResponse loadSessionMessages(
            String contextId,
            String userId,
            String cursor,
            int limit,
            boolean includeInternal) {
        String sessionId = sessionQueryService.resolveSessionId(contextId, userId)
                .orElseThrow(() -> RX.throwB("Context not found: " + contextId));
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<SessionMessageEntity> messages = sessionQueryService.getSessionMessages(
                sessionId, cursor, safeLimit);
        boolean hasMore = messages.size() > safeLimit;
        List<SessionMessageEntity> page = hasMore ? messages.subList(0, safeLimit) : messages;
        String nextCursor = page.isEmpty() ? cursor : page.get(page.size() - 1).getId();

        List<String> taskIds = page.stream()
                .map(SessionMessageEntity::getTaskId)
                .filter(OpenApiProjectionSupport::hasText)
                .distinct()
                .toList();
        Map<String, String> rawTaskStatusMap = sessionQueryService.batchFindTaskStatuses(taskIds);
        Map<String, String> taskStatusMap = rawTaskStatusMap == null ? Map.of() : rawTaskStatusMap;
        List<OpenSessionMessageDTO> projectedMessages = page.stream()
                .filter(message -> includeInternal
                        || BusinessAgentSessionMessageVisibility.isVisibleByDefault(message))
                .map(message -> {
                    String rawStatus = taskStatusMap.get(message.getTaskId());
                    String mappedStatus = OpenApiProjectionSupport.hasText(rawStatus)
                            ? taskProjectionMapper.mapTaskStatus(rawStatus)
                            : null;
                    return sessionProjectionMapper.mapMessage(message, contextId, mappedStatus);
                })
                .toList();

        return OpenSessionMessagesResponse.builder()
                .contextId(contextId)
                .messages(projectedMessages)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    private SessionTaskEntity requireOwnedTask(
            String taskId,
            String tenantId,
            String agentId) {
        SessionTaskEntity taskEntity = sessionQueryService.findTask(taskId)
                .orElseThrow(() -> RX.throwB("Task not found: " + taskId));
        if (!tenantId.equals(taskEntity.getTenantId())
                || !agentId.equals(taskEntity.getAgentId())) {
            throw RX.throwB("Task not found: " + taskId);
        }
        return taskEntity;
    }

    private String resolveContextId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessionQueryService.resolveContextId(sessionId).orElse(null);
    }
}
