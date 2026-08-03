package com.foggy.navigator.session.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.DirectoryMilestoneDTO;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionRelationEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.session.dto.SessionForwardCreateRequest;
import com.foggy.navigator.session.dto.SessionForwardCreateResponse;
import com.foggy.navigator.session.dto.SessionRelationDTO;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.session.repository.SessionRelationRepository;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionForwardService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<DirectoryMilestoneDTO>> MILESTONE_LIST_TYPE =
            new TypeReference<>() {};

    private final SessionMessageRepository sessionMessageRepository;
    private final SessionRelationRepository sessionRelationRepository;
    private final SessionTaskRepository sessionTaskRepository;
    private final WorkingDirectoryRepository workingDirectoryRepository;
    private final TaskDispatchFacade taskDispatchFacade;
    private final AgentSubmitPipeline agentSubmitPipeline;
    private final SessionTaskResourceAccessService resourceAccessService;
    private final SessionForwardTransactionBoundary transactionBoundary;
    private final TrustedNavigatorTaskCreateCommandFactory forwardCommandFactory;
    private final SessionForwardTargetSessionReservationService targetReservationService;
    private final SessionForwardOutcomeStore outcomeStore;

    public SessionForwardCreateResponse forwardToNewSession(
            SessionForwardCreateRequest request,
            String userId,
            String tenantId
    ) {
        return forwardToNewSession(request, userId, tenantId, null);
    }

    public SessionForwardCreateResponse forwardToNewSession(
            SessionForwardCreateRequest request,
            String userId,
            String tenantId,
            @Nullable String clientRequestId
    ) {
        Objects.requireNonNull(request, "forward request must not be null");
        String targetMode = normalizeTargetMode(request.getTargetMode());
        return switch (targetMode) {
            case "EXISTING_SESSION" -> transactionBoundary.executeExistingTarget(
                    () -> forwardWithinBoundary(
                            request, userId, tenantId, targetMode, clientRequestId));
            case "NEW_SESSION" -> transactionBoundary.executeNewTarget(
                    () -> forwardWithinBoundary(
                            request, userId, tenantId, targetMode, clientRequestId));
            default -> throw new IllegalArgumentException("Unsupported targetMode: " + targetMode);
        };
    }

    private SessionForwardCreateResponse forwardWithinBoundary(
            SessionForwardCreateRequest request,
            String userId,
            String tenantId,
            String targetMode,
            @Nullable String clientRequestId
    ) {
        SessionEntity sourceSession = findOwnedSession(request.getSourceSessionId(), userId, tenantId,
                "Source session not found: ");
        ForwardSourceProjection sourceMessage =
                resolveSourceMessage(request, sourceSession, userId, tenantId);

        if (!"ASSISTANT".equalsIgnoreCase(sourceMessage.role())) {
            throw new IllegalArgumentException("Only assistant messages can be forwarded");
        }

        String prompt = resolvePrompt(request, sourceMessage);
        return switch (targetMode) {
            case "EXISTING_SESSION" -> forwardToExistingSession(request, userId, tenantId, sourceSession, sourceMessage, prompt);
            case "NEW_SESSION" -> forwardCreatingNewSession(
                    request,
                    userId,
                    tenantId,
                    sourceSession,
                    sourceMessage,
                    prompt,
                    clientRequestId);
            default -> throw new IllegalArgumentException("Unsupported targetMode: " + targetMode);
        };
    }

    private ForwardSourceProjection resolveSourceMessage(
            SessionForwardCreateRequest request,
            SessionEntity sourceSession,
            String userId,
            String tenantId
    ) {
        String sourceMessageId = blankToNull(request.getSourceMessageId());
        if (sourceMessageId != null) {
            Optional<SessionMessageEntity> sourceMessage = sessionMessageRepository.findById(sourceMessageId);
            if (sourceMessage.isPresent()) {
                SessionMessageEntity message = sourceMessage.get();
                if (!sourceSession.getId().equals(message.getSessionId())) {
                    throw new IllegalArgumentException("Source message not found: " + sourceMessageId);
                }
                return new ForwardSourceProjection(
                        SessionForwardNewSessionPlan.SourceKind.MESSAGE,
                        message.getId(),
                        message.getRole(),
                        message.getContent(),
                        message.getTaskId());
            }
        }

        String sourceTaskId = blankToNull(request.getSourceTaskId());
        if (sourceTaskId == null) {
            throw new IllegalArgumentException("Source message not found: " + request.getSourceMessageId());
        }
        return resolveRecoveredTaskResult(sourceSession, sourceTaskId, userId, tenantId);
    }

    private ForwardSourceProjection resolveRecoveredTaskResult(
            SessionEntity sourceSession,
            String sourceTaskId,
            String userId,
            String tenantId
    ) {
        SessionTaskEntity sourceTask = resourceAccessService.requireOwnedTask(sourceTaskId, userId, tenantId);
        if (!sourceSession.getId().equals(blankToNull(sourceTask.getSessionId()))) {
            throw new IllegalArgumentException("Source task does not belong to source session");
        }
        if (!"COMPLETED".equalsIgnoreCase(blankToNull(sourceTask.getStatus()))) {
            throw new IllegalArgumentException("Source task is not completed");
        }

        String resultText = blankToNull(sourceTask.getResultText());
        if (resultText == null) {
            throw new IllegalArgumentException("Source task result is empty");
        }

        return new ForwardSourceProjection(
                SessionForwardNewSessionPlan.SourceKind.TASK_RESULT,
                recoveredTaskResultMessageId(sourceSession.getId(), sourceTaskId),
                "ASSISTANT",
                resultText,
                sourceTaskId);
    }

    private String recoveredTaskResultMessageId(String sessionId, String taskId) {
        String source = "forward-task-result:" + sessionId + ":" + taskId;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Transactional(readOnly = true)
    public SessionRelationDTO findIncomingForwardRelation(String targetSessionId, String userId, String tenantId) {
        String normalizedTargetSessionId = blankToNull(targetSessionId);
        if (normalizedTargetSessionId == null) {
            throw new IllegalArgumentException("targetSessionId is required");
        }
        findOwnedSession(normalizedTargetSessionId, userId, tenantId, "Target session not found: ");
        return sessionRelationRepository
                .findFirstByUserIdAndRelationTypeAndTargetSessionIdOrderByCreatedAtDesc(
                        userId,
                        "FORWARD",
                        normalizedTargetSessionId
                )
                .map(SessionRelationDTO::fromEntity)
                .orElse(null);
    }

    private SessionForwardCreateResponse forwardCreatingNewSession(
            SessionForwardCreateRequest request,
            String userId,
            String tenantId,
            SessionEntity sourceSession,
            ForwardSourceProjection sourceMessage,
            String prompt,
            @Nullable String clientRequestId
    ) {
        String rootSessionId = resolveRootSessionId(sourceSession, userId, tenantId);
        List<String> images = SessionForwardNewSessionPlan.imagesFromWire(request.getImages());
        TaskDispatchRequest provisionalRequest = TaskDispatchRequest.builder()
                .workerId(blankToNull(request.getWorkerId()))
                .directoryId(blankToNull(request.getDirectoryId()))
                .cwd(request.getCwd())
                .prompt(prompt)
                .model(blankToNull(request.getModel()))
                .modelConfigId(blankToNull(request.getModelConfigId()))
                .permissionMode(blankToNull(request.getPermissionMode()))
                .agentId(blankToNull(request.getAgentId()))
                .maxTurns(request.getMaxTurns())
                .agentTeamsConfigId(blankToNull(request.getAgentTeamsConfigId()))
                .agentTeamsJson(request.getAgentTeamsJson())
                .images(images)
                .initializeRuntimeAffinity(true)
                .build();
        AgentResolveContext provisionalContext = buildContext(
                userId, tenantId, null, request.getModelConfigId());
        TaskCreateTargetResolver.CreateExecutionPlan resolvedTarget =
                taskDispatchFacade.resolveCreateExecutionPlan(
                        provisionalRequest, provisionalContext);
        resolvedTarget.requireMatches(provisionalRequest, provisionalContext);
        requireSessionlessResolvedTarget(resolvedTarget, userId, tenantId);

        String targetWorkerId = blankToNull(resolvedTarget.physicalWorkerId());
        if (targetWorkerId == null) {
            throw new IllegalArgumentException("workerId is required");
        }
        WorkingDirectoryEntity targetDirectory = resolveCanonicalTargetDirectory(
                resolvedTarget.directoryId(), userId, tenantId, targetWorkerId);
        String targetCwd = request.getCwd();
        if (isBlank(targetCwd) && targetDirectory != null) {
            targetCwd = targetDirectory.getPath();
        }
        String targetMilestoneId = resolveTargetMilestoneId(
                request, sourceSession, targetDirectory);

        SessionForwardNewSessionPlan plan = new SessionForwardNewSessionPlan(
                userId,
                tenantId,
                new SessionForwardNewSessionPlan.SourceSnapshot(
                        sourceSession.getId(),
                        sourceMessage.kind(),
                        sourceMessage.referenceId(),
                        sourceMessage.sourceTaskId(),
                        sourceMessage.content(),
                        sourceSession.getCurrentWorkerId(),
                        sourceSession.getCurrentDirectoryId(),
                        sourceSession.getMilestoneId()),
                rootSessionId,
                prompt,
                new SessionForwardNewSessionPlan.TargetExecution(
                        targetWorkerId,
                        resolvedTarget.directoryId(),
                        targetCwd,
                        resolvedTarget.logicalAgentId(),
                        targetMilestoneId,
                        resolvedTarget.model(),
                        resolvedTarget.modelConfigId(),
                        request.getPermissionMode(),
                        request.getMaxTurns(),
                        request.getAgentTeamsConfigId(),
                        request.getAgentTeamsJson(),
                        images));

        TrustedNavigatorTaskCreateCommandFactory.ForwardCommandScope scope =
                forwardCommandFactory.mintForwardScope(
                        clientRequestId, plan.semanticFingerprint());
        String targetSessionId =
                SessionForwardTargetSessionReservationService.deriveSessionId(
                        scope.clientRequestId(), plan.ownerUserId(), plan.tenantId());
        AgentTaskSubmitRequest submitRequest = plan.toSubmitRequest(
                scope.clientRequestId(), targetSessionId);
        forwardCommandFactory.preauthorizeForwardScope(scope, submitRequest);

        SessionForwardTargetSessionReservationService.ReservationResult reservation =
                targetReservationService.reserve(
                        scope.clientRequestId(), plan.reservationSpec());
        if (!targetSessionId.equals(reservation.sessionId())) {
            throw new IllegalStateException("FORWARD_SESSION_RESERVATION_ID_CONFLICT");
        }

        ForwardOutcomeParticipants participants = new ForwardOutcomeParticipants(
                plan,
                scope.clientRequestId(),
                targetSessionId,
                submitRequest,
                outcomeStore);
        AgentTaskSubmitResult submitResult = forwardCommandFactory.executeForwardScoped(
                scope,
                submitRequest,
                participants,
                () -> agentSubmitPipeline.submit(submitRequest));
        DispatchTaskDTO task = submitResult.getDispatchTask();
        if (task == null) {
            throw new IllegalStateException("Agent submit pipeline did not return dispatch task");
        }
        SessionForwardOutcomeStore.OutcomeSnapshot outcome =
                participants.requireOutcome(task);

        log.info("Forwarded assistant message to new session: sourceSessionId={}, sourceMessageId={}, targetSessionId={}, taskId={}",
                sourceSession.getId(), sourceMessage.referenceId(), targetSessionId, task.getTaskId());

        return buildResponse(
                outcome.relationId(),
                "NEW_SESSION",
                plan.source().sessionId(),
                plan.source().referenceId(),
                targetSessionId,
                task);
    }

    private SessionForwardCreateResponse forwardToExistingSession(
            SessionForwardCreateRequest request,
            String userId,
            String tenantId,
            SessionEntity sourceSession,
            ForwardSourceProjection sourceMessage,
            String prompt
    ) {
        String targetSessionId = blankToNull(request.getTargetSessionId());
        if (targetSessionId == null) {
            throw new IllegalArgumentException("targetSessionId is required for EXISTING_SESSION");
        }

        SessionEntity targetSession = findOwnedSession(targetSessionId, userId, tenantId,
                "Target session not found: ");
        ensureExistingForwardTargetAllowed(sourceSession, targetSession, userId, tenantId);
        SessionTaskEntity latestTask = resolveLatestTask(targetSession, userId, tenantId);

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
                .images(parseImagesList(request.getImages()))
                .build();

        AgentResolveContext context = buildContext(userId, tenantId, targetSession.getId(), latestTask.getModelConfigId());
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
                sourceSession.getId(), sourceMessage.referenceId(), targetSession.getId(), task.getTaskId());

        return buildResponse(relation.getId(), "EXISTING_SESSION", sourceSession.getId(), sourceMessage.referenceId(), targetSession.getId(), task);
    }

    private SessionEntity findOwnedSession(String sessionId, String userId, String tenantId, String messagePrefix) {
        SessionEntity session = resourceAccessService.requireOwnedSession(sessionId, userId, tenantId);
        if (session.getDeletedAt() != null) {
            throw new IllegalArgumentException(messagePrefix + sessionId);
        }
        return session;
    }

    private String resolvePrompt(
            SessionForwardCreateRequest request,
            ForwardSourceProjection sourceMessage) {
        String prompt = blankToNull(request.getPrompt());
        if (prompt == null) {
            prompt = blankToNull(sourceMessage.content());
        }
        if (prompt == null) {
            throw new IllegalArgumentException("Forward prompt cannot be empty");
        }
        return prompt;
    }

    private AgentResolveContext buildContext(String userId, String tenantId, String sessionId, String modelConfigId) {
        return AgentResolveContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .sessionId(sessionId)
                .modelConfigId(blankToNull(modelConfigId))
                .requestSource("UI_FORWARD")
                .build();
    }

    private SessionRelationEntity saveRelation(
            String targetMode,
            SessionEntity sourceSession,
            ForwardSourceProjection sourceMessage,
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
        relation.setSourceMessageId(sourceMessage.referenceId());
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

    private void requireSessionlessResolvedTarget(
            TaskCreateTargetResolver.CreateExecutionPlan resolvedTarget,
            String userId,
            String tenantId) {
        if (!Objects.equals(userId, resolvedTarget.ownerUserId())
                || !Objects.equals(blankToNull(tenantId), resolvedTarget.tenantId())) {
            throw new SecurityException("Resource access denied");
        }
        String resolvedSessionId = blankToNull(resolvedTarget.sessionId());
        TaskCreateContextNormalizer.PendingContextClaim pendingContextClaim =
                resolvedTarget.pendingContextClaim();
        if (resolvedTarget.canonicalContextProof() != null) {
            throw new IllegalStateException("FORWARD_TARGET_PREPLAN_SESSION_CONFLICT");
        }
        if (resolvedSessionId == null) {
            if (pendingContextClaim != null) {
                throw new IllegalStateException("FORWARD_TARGET_PREPLAN_SESSION_CONFLICT");
            }
            return;
        }
        if (pendingContextClaim == null
                || !resolvedSessionId.equals(pendingContextClaim.navigatorSessionId())) {
            throw new IllegalStateException("FORWARD_TARGET_PREPLAN_SESSION_CONFLICT");
        }
        if (!Objects.equals(userId, pendingContextClaim.ownerUserId())
                || !Objects.equals(
                        blankToNull(tenantId), pendingContextClaim.tenantId())
                || !Objects.equals(
                        resolvedTarget.logicalAgentId(),
                        pendingContextClaim.logicalAgentId())) {
            throw new SecurityException("Resource access denied");
        }
    }

    private WorkingDirectoryEntity resolveCanonicalTargetDirectory(
            @Nullable String resolvedDirectoryId,
            String userId,
            String tenantId,
            String resolvedWorkerId) {
        String directoryId = blankToNull(resolvedDirectoryId);
        if (directoryId == null) {
            return null;
        }
        WorkingDirectoryEntity directory = workingDirectoryRepository
                .findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Working directory not found: " + directoryId));
        if (!Objects.equals(directoryId, blankToNull(directory.getDirectoryId()))
                || !Objects.equals(userId, blankToNull(directory.getUserId()))
                || !Objects.equals(
                        blankToNull(tenantId), blankToNull(directory.getTenantId()))
                || !Boolean.TRUE.equals(directory.getEnabled())
                || !Objects.equals(blankToNull(directory.getWorkerId()), resolvedWorkerId)) {
            throw new IllegalStateException(
                    "FORWARD_TARGET_DIRECTORY_CHANGED_BEFORE_PLAN_FREEZE");
        }
        return directory;
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

    private void ensureExistingForwardTargetAllowed(SessionEntity sourceSession,
                                                    SessionEntity targetSession,
                                                    String userId,
                                                    String tenantId) {
        if (sourceSession.getId().equals(targetSession.getId())) {
            throw new IllegalArgumentException("Target session cannot be the same as source session");
        }
        boolean isDirectChild = sourceSession.getId().equals(blankToNull(targetSession.getParentSessionId()));
        String sourceRootId = resolveRootSessionId(sourceSession, userId, tenantId);
        String targetRootId = resolveRootSessionId(targetSession, userId, tenantId);
        boolean isSameRootBranch = sourceRootId.equals(targetRootId)
                && !targetSession.getId().equals(targetRootId);
        boolean hasForwardRelation = sessionRelationRepository.existsByUserIdAndRelationTypeAndSourceSessionIdAndTargetSessionId(
                userId,
                "FORWARD",
                sourceSession.getId(),
                targetSession.getId()
        );
        if (!isDirectChild && !isSameRootBranch && !hasForwardRelation) {
            throw new IllegalArgumentException("Target session must be a previously forwarded child session");
        }
    }

    private String resolveRootSessionId(SessionEntity session, String userId, String tenantId) {
        String rootId = session.getId();
        String parentId = blankToNull(session.getParentSessionId());
        Set<String> seen = new HashSet<>();
        seen.add(rootId);

        while (parentId != null && !seen.contains(parentId)) {
            SessionEntity parent = findOwnedSession(parentId, userId, tenantId, "Parent session not found: ");
            rootId = parent.getId();
            seen.add(rootId);
            parentId = blankToNull(parent.getParentSessionId());
        }

        return rootId;
    }

    private SessionTaskEntity resolveLatestTask(SessionEntity session, String userId, String tenantId) {
        String latestTaskId = blankToNull(session.getLatestTaskId());
        if (latestTaskId != null) {
            SessionTaskEntity latestTask = sessionTaskRepository
                    .findByTaskIdAndUserIdAndTenantId(latestTaskId, userId, tenantId)
                    .orElse(null);
            if (latestTask != null) {
                if (!session.getId().equals(blankToNull(latestTask.getSessionId()))) {
                    throw new IllegalStateException(
                            "Target session latest task binding is invalid");
                }
                return latestTask;
            }
        }
        return sessionTaskRepository
                .findBySessionIdAndUserIdAndTenantIdOrderByCreatedAtDesc(session.getId(), userId, tenantId)
                .stream()
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

    /**
     * Parse images string from forward request into List&lt;String&gt; for TaskDispatchRequest.
     * The frontend sends a single JSON string (e.g. '[{"name":...,"data":...,"mime_type":...}]').
     * Wrap it as a single-element list, matching the normalizeTaskForm convention.
     */
    private List<String> parseImagesList(String images) {
        String normalized = blankToNull(images);
        if (normalized == null) {
            return null;
        }
        return List.of(normalized);
    }

    private static final class ForwardOutcomeParticipants
            implements TrustedNavigatorTaskCreateCommandFactory.ForwardFreshParticipants {

        private final SessionForwardNewSessionPlan plan;
        private final String clientRequestId;
        private final String targetSessionId;
        private final AgentTaskSubmitRequest submitRequest;
        private final SessionForwardOutcomeStore outcomeStore;

        private ParticipantState state = ParticipantState.INITIAL;
        private DispatchTaskDTO completedTask;
        private SessionForwardOutcomeStore.OutcomeSnapshot freshOutcome;

        private ForwardOutcomeParticipants(
                SessionForwardNewSessionPlan plan,
                String clientRequestId,
                String targetSessionId,
                AgentTaskSubmitRequest submitRequest,
                SessionForwardOutcomeStore outcomeStore) {
            this.plan = Objects.requireNonNull(plan, "forward plan must not be null");
            this.clientRequestId = Objects.requireNonNull(
                    clientRequestId, "client request ID must not be null");
            this.targetSessionId = Objects.requireNonNull(
                    targetSessionId, "target session ID must not be null");
            this.submitRequest = Objects.requireNonNull(
                    submitRequest, "submit request must not be null");
            this.outcomeStore = Objects.requireNonNull(
                    outcomeStore, "outcome store must not be null");
        }

        @Override
        public synchronized void prepareFreshTask() {
            plan.requireExactPreparedSubmitRequest(
                    submitRequest,
                    clientRequestId,
                    targetSessionId);
            if (state != ParticipantState.INITIAL) {
                throw new IllegalStateException("FORWARD_OUTCOME_PREPARATION_CONFLICT");
            }
            state = ParticipantState.PREPARED;
        }

        @Override
        public synchronized void completeFreshTask(DispatchTaskDTO freshTask) {
            if (state != ParticipantState.PREPARED) {
                throw new IllegalStateException("FORWARD_OUTCOME_COMPLETION_CONFLICT");
            }
            SessionForwardOutcomeStore.OutcomeSpec expected =
                    SessionForwardOutcomeStore.OutcomeSpec.from(
                            plan, targetSessionId, freshTask);
            SessionForwardOutcomeStore.OutcomeSnapshot inserted =
                    outcomeStore.insertFresh(expected);
            if (!expected.equals(inserted.spec())) {
                throw new IllegalStateException("FORWARD_OUTCOME_INSERT_CONFLICT");
            }
            completedTask = freshTask;
            freshOutcome = inserted;
            state = ParticipantState.COMPLETED;
        }

        private synchronized SessionForwardOutcomeStore.OutcomeSnapshot requireOutcome(
                DispatchTaskDTO task) {
            Objects.requireNonNull(task, "dispatch task must not be null");
            SessionForwardOutcomeStore.OutcomeSpec expected =
                    SessionForwardOutcomeStore.OutcomeSpec.from(
                            plan, targetSessionId, task);
            if (state == ParticipantState.INITIAL) {
                return outcomeStore.requireExactReplay(expected);
            }
            if (state != ParticipantState.COMPLETED
                    || completedTask != task
                    || freshOutcome == null
                    || !expected.equals(freshOutcome.spec())) {
                throw new IllegalStateException("FORWARD_OUTCOME_STATE_CONFLICT");
            }
            return freshOutcome;
        }

        private enum ParticipantState {
            INITIAL,
            PREPARED,
            COMPLETED
        }
    }

    private record ForwardSourceProjection(
            SessionForwardNewSessionPlan.SourceKind kind,
            String referenceId,
            String role,
            String content,
            String sourceTaskId) {
    }
}
