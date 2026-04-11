package com.foggy.navigator.session.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.dto.DirectoryMilestoneDTO;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionRelationEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.util.DirectoryAgentId;
import com.foggy.navigator.session.dto.SessionForwardCreateRequest;
import com.foggy.navigator.session.dto.SessionForwardCreateResponse;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.session.repository.SessionRelationRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionForwardService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<DirectoryMilestoneDTO>> MILESTONE_LIST_TYPE =
            new TypeReference<>() {};

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final SessionRelationRepository sessionRelationRepository;
    private final SessionTaskRepository sessionTaskRepository;
    private final WorkingDirectoryRepository workingDirectoryRepository;
    private final SessionManager sessionManager;
    private final TaskDispatchFacade taskDispatchFacade;

    @Transactional
    public SessionForwardCreateResponse forwardToNewSession(
            SessionForwardCreateRequest request,
            String userId,
            String tenantId
    ) {
        SessionEntity sourceSession = findOwnedSession(request.getSourceSessionId(), userId, "Source session not found: ");
        SessionMessageEntity sourceMessage = sessionMessageRepository.findById(request.getSourceMessageId())
                .filter(message -> sourceSession.getId().equals(message.getSessionId()))
                .orElseThrow(() -> new IllegalArgumentException("Source message not found: " + request.getSourceMessageId()));

        if (!"ASSISTANT".equalsIgnoreCase(sourceMessage.getRole())) {
            throw new IllegalArgumentException("Only assistant messages can be forwarded");
        }

        String prompt = resolvePrompt(request, sourceMessage);
        String targetMode = normalizeTargetMode(request.getTargetMode());
        return switch (targetMode) {
            case "EXISTING_SESSION" -> forwardToExistingSession(request, userId, tenantId, sourceSession, sourceMessage, prompt);
            case "NEW_SESSION" -> forwardCreatingNewSession(request, userId, tenantId, sourceSession, sourceMessage, prompt);
            default -> throw new IllegalArgumentException("Unsupported targetMode: " + targetMode);
        };
    }

    private SessionForwardCreateResponse forwardCreatingNewSession(
            SessionForwardCreateRequest request,
            String userId,
            String tenantId,
            SessionEntity sourceSession,
            SessionMessageEntity sourceMessage,
            String prompt
    ) {
        WorkingDirectoryEntity targetDirectory = resolveTargetDirectory(request, userId);
        normalizeTargetContext(request, targetDirectory);
        String targetMilestoneId = resolveTargetMilestoneId(request, sourceSession, targetDirectory);

        String targetSessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(userId)
                .tenantId(tenantId)
                .agentId(blankToNull(request.getAgentId()))
                .parentSessionId(sourceSession.getId())
                .taskName(truncate(prompt, 120))
                .build());

        SessionEntity targetSession = sessionRepository.findById(targetSessionId)
                .orElseThrow(() -> new IllegalStateException("Created session not found: " + targetSessionId));
        targetSession.setCurrentWorkerId(blankToNull(request.getWorkerId()));
        targetSession.setCurrentDirectoryId(blankToNull(request.getDirectoryId()));
        targetSession.setMilestoneId(targetMilestoneId);
        targetSession.setLatestModel(blankToNull(request.getModel()));
        sessionRepository.save(targetSession);

        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder()
                .sessionId(targetSessionId)
                .workerId(request.getWorkerId())
                .directoryId(request.getDirectoryId())
                .cwd(request.getCwd())
                .prompt(prompt)
                .model(request.getModel())
                .modelConfigId(request.getModelConfigId())
                .permissionMode(request.getPermissionMode())
                .agentId(request.getAgentId())
                .maxTurns(request.getMaxTurns())
                .agentTeamsConfigId(request.getAgentTeamsConfigId())
                .agentTeamsJson(request.getAgentTeamsJson())
                .build();

        AgentResolveContext context = buildContext(userId, tenantId, targetSessionId);
        DispatchTaskDTO task = taskDispatchFacade.createTask(dispatchRequest, context);

        SessionRelationEntity relation = saveRelation(
                "NEW_SESSION",
                sourceSession,
                sourceMessage,
                targetSessionId,
                blankToNull(request.getWorkerId()),
                blankToNull(request.getDirectoryId()),
                targetMilestoneId,
                firstNonBlank(task.getModelConfigId(), request.getModelConfigId()),
                task.getProviderType(),
                prompt,
                userId,
                tenantId
        );

        log.info("Forwarded assistant message to new session: sourceSessionId={}, sourceMessageId={}, targetSessionId={}, taskId={}",
                sourceSession.getId(), sourceMessage.getId(), targetSessionId, task.getTaskId());

        return buildResponse(relation.getId(), "NEW_SESSION", sourceSession.getId(), sourceMessage.getId(), targetSessionId, task);
    }

    private SessionForwardCreateResponse forwardToExistingSession(
            SessionForwardCreateRequest request,
            String userId,
            String tenantId,
            SessionEntity sourceSession,
            SessionMessageEntity sourceMessage,
            String prompt
    ) {
        String targetSessionId = blankToNull(request.getTargetSessionId());
        if (targetSessionId == null) {
            throw new IllegalArgumentException("targetSessionId is required for EXISTING_SESSION");
        }

        SessionEntity targetSession = findOwnedSession(targetSessionId, userId, "Target session not found: ");
        ensureExistingForwardTargetAllowed(sourceSession, targetSession, userId);
        SessionTaskEntity latestTask = resolveLatestTask(targetSession, userId);

        String targetWorkerId = firstNonBlank(latestTask.getWorkerId(), targetSession.getCurrentWorkerId());
        if (targetWorkerId == null) {
            throw new IllegalArgumentException("Target session worker is missing: " + targetSessionId);
        }

        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder()
                .sessionId(targetSession.getId())
                .agentId(firstNonBlank(targetSession.getAgentId(), latestTask.getAgentId()))
                .workerId(targetWorkerId)
                .directoryId(firstNonBlank(latestTask.getDirectoryId(), targetSession.getCurrentDirectoryId()))
                .cwd(blankToNull(latestTask.getCwd()))
                .prompt(prompt)
                .model(firstNonBlank(latestTask.getModel(), targetSession.getLatestModel()))
                .modelConfigId(blankToNull(latestTask.getModelConfigId()))
                .build();

        AgentResolveContext context = buildContext(userId, tenantId, targetSession.getId());
        DispatchTaskDTO task = taskDispatchFacade.resumeTask(dispatchRequest, context);

        SessionRelationEntity relation = saveRelation(
                "EXISTING_SESSION",
                sourceSession,
                sourceMessage,
                targetSession.getId(),
                firstNonBlank(latestTask.getWorkerId(), targetSession.getCurrentWorkerId()),
                firstNonBlank(latestTask.getDirectoryId(), targetSession.getCurrentDirectoryId()),
                blankToNull(targetSession.getMilestoneId()),
                firstNonBlank(task.getModelConfigId(), latestTask.getModelConfigId()),
                task.getProviderType(),
                prompt,
                userId,
                tenantId
        );

        log.info("Forwarded assistant message to existing session: sourceSessionId={}, sourceMessageId={}, targetSessionId={}, taskId={}",
                sourceSession.getId(), sourceMessage.getId(), targetSession.getId(), task.getTaskId());

        return buildResponse(relation.getId(), "EXISTING_SESSION", sourceSession.getId(), sourceMessage.getId(), targetSession.getId(), task);
    }

    private SessionEntity findOwnedSession(String sessionId, String userId, String messagePrefix) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .filter(session -> session.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException(messagePrefix + sessionId));
    }

    private String resolvePrompt(SessionForwardCreateRequest request, SessionMessageEntity sourceMessage) {
        String prompt = blankToNull(request.getPrompt());
        if (prompt == null) {
            prompt = blankToNull(sourceMessage.getContent());
        }
        if (prompt == null) {
            throw new IllegalArgumentException("Forward prompt cannot be empty");
        }
        return prompt;
    }

    private AgentResolveContext buildContext(String userId, String tenantId, String sessionId) {
        return AgentResolveContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .sessionId(sessionId)
                .requestSource("UI_FORWARD")
                .build();
    }

    private SessionRelationEntity saveRelation(
            String targetMode,
            SessionEntity sourceSession,
            SessionMessageEntity sourceMessage,
            String targetSessionId,
            String targetWorkerId,
            String targetDirectoryId,
            String targetMilestoneId,
            String targetModelConfigId,
            String targetProviderType,
            String prompt,
            String userId,
            String tenantId
    ) {
        SessionRelationEntity relation = new SessionRelationEntity();
        relation.setUserId(userId);
        relation.setTenantId(tenantId);
        relation.setRelationType("FORWARD");
        relation.setTargetMode(targetMode);
        relation.setSourceSessionId(sourceSession.getId());
        relation.setSourceMessageId(sourceMessage.getId());
        relation.setTargetSessionId(targetSessionId);
        relation.setSourceWorkerId(sourceSession.getCurrentWorkerId());
        relation.setSourceDirectoryId(sourceSession.getCurrentDirectoryId());
        relation.setSourceMilestoneId(sourceSession.getMilestoneId());
        relation.setTargetWorkerId(targetWorkerId);
        relation.setTargetDirectoryId(targetDirectoryId);
        relation.setTargetMilestoneId(targetMilestoneId);
        relation.setTargetProviderType(targetProviderType);
        relation.setTargetModelConfigId(targetModelConfigId);
        relation.setMetadataJson(writeMetadata(prompt, targetMode));
        return sessionRelationRepository.save(relation);
    }

    private SessionForwardCreateResponse buildResponse(
            Long relationId,
            String targetMode,
            String sourceSessionId,
            String sourceMessageId,
            String targetSessionId,
            DispatchTaskDTO task
    ) {
        return SessionForwardCreateResponse.builder()
                .relationId(relationId)
                .targetMode(targetMode)
                .sourceSessionId(sourceSessionId)
                .sourceMessageId(sourceMessageId)
                .targetSessionId(targetSessionId)
                .task(task)
                .build();
    }

    private WorkingDirectoryEntity resolveTargetDirectory(SessionForwardCreateRequest request, String userId) {
        String directoryId = blankToNull(request.getDirectoryId());
        if (directoryId == null) {
            return null;
        }
        return workingDirectoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Working directory not found: " + directoryId));
    }

    private void normalizeTargetContext(SessionForwardCreateRequest request, WorkingDirectoryEntity targetDirectory) {
        if (targetDirectory != null) {
            if (isBlank(request.getWorkerId())) {
                request.setWorkerId(targetDirectory.getWorkerId());
            } else if (!targetDirectory.getWorkerId().equals(request.getWorkerId())) {
                throw new IllegalArgumentException("Working directory does not belong to worker: " + request.getDirectoryId());
            }
            if (isBlank(request.getCwd())) {
                request.setCwd(targetDirectory.getPath());
            }
            if (isBlank(request.getAgentId())) {
                request.setAgentId(DirectoryAgentId.of(targetDirectory.getDirectoryId()));
            }
        }
        if (isBlank(request.getWorkerId())) {
            throw new IllegalArgumentException("workerId is required");
        }
    }

    private String resolveTargetMilestoneId(
            SessionForwardCreateRequest request,
            SessionEntity sourceSession,
            WorkingDirectoryEntity targetDirectory
    ) {
        String requestedMilestoneId = blankToNull(request.getMilestoneId());
        if (requestedMilestoneId != null) {
            if (targetDirectory == null) {
                throw new IllegalArgumentException("Milestone requires a target working directory");
            }
            ensureMilestoneExists(targetDirectory, requestedMilestoneId);
            return requestedMilestoneId;
        }

        if (targetDirectory == null) {
            return null;
        }

        String sourceDirectoryId = blankToNull(sourceSession.getCurrentDirectoryId());
        String sourceMilestoneId = blankToNull(sourceSession.getMilestoneId());
        if (sourceMilestoneId == null || sourceDirectoryId == null) {
            return null;
        }
        if (!targetDirectory.getDirectoryId().equals(sourceDirectoryId)) {
            return null;
        }
        ensureMilestoneExists(targetDirectory, sourceMilestoneId);
        return sourceMilestoneId;
    }

    private void ensureExistingForwardTargetAllowed(SessionEntity sourceSession, SessionEntity targetSession, String userId) {
        if (sourceSession.getId().equals(targetSession.getId())) {
            throw new IllegalArgumentException("Target session cannot be the same as source session");
        }
        boolean isDirectChild = sourceSession.getId().equals(blankToNull(targetSession.getParentSessionId()));
        boolean hasForwardRelation = sessionRelationRepository.existsByUserIdAndRelationTypeAndSourceSessionIdAndTargetSessionId(
                userId,
                "FORWARD",
                sourceSession.getId(),
                targetSession.getId()
        );
        if (!isDirectChild && !hasForwardRelation) {
            throw new IllegalArgumentException("Target session must be a previously forwarded child session");
        }
    }

    private SessionTaskEntity resolveLatestTask(SessionEntity session, String userId) {
        String latestTaskId = blankToNull(session.getLatestTaskId());
        if (latestTaskId != null) {
            SessionTaskEntity latestTask = sessionTaskRepository.findByTaskIdAndUserId(latestTaskId, userId).orElse(null);
            if (latestTask != null) {
                return latestTask;
            }
        }
        return sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc(session.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Target session has no task history: " + session.getId()));
    }

    private void ensureMilestoneExists(WorkingDirectoryEntity directory, String milestoneId) {
        boolean exists = parseMilestones(directory.getMilestonesJson()).stream()
                .map(DirectoryMilestoneDTO::getId)
                .anyMatch(milestoneId::equals);
        if (!exists) {
            throw new IllegalArgumentException("Milestone not found in directory: " + milestoneId);
        }
    }

    private List<DirectoryMilestoneDTO> parseMilestones(String milestonesJson) {
        if (milestonesJson == null || milestonesJson.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(milestonesJson, MILESTONE_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse milestones json: {}", milestonesJson, e);
            return List.of();
        }
    }

    private String writeMetadata(String prompt, String targetMode) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("targetMode", targetMode);
            metadata.put("promptPreview", truncate(prompt, 200));
            metadata.put("promptLength", prompt.length());
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("Failed to serialize session forward metadata", e);
            return null;
        }
    }

    private String normalizeTargetMode(String targetMode) {
        String normalized = blankToNull(targetMode);
        if (normalized == null) {
            return "NEW_SESSION";
        }
        return normalized.trim().toUpperCase();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String firstNonBlank(String first, String second) {
        String normalizedFirst = blankToNull(first);
        return normalizedFirst != null ? normalizedFirst : blankToNull(second);
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return blankToNull(value) == null;
    }
}
