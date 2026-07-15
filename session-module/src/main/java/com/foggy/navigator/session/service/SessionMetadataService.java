package com.foggy.navigator.session.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.DirectoryMilestoneDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.session.dto.SessionConfigDTO;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionMetadataService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String MODEL_CONFIG_ACCESS_DENIED =
            "LLM model config is not available for this session";
    private static final Set<String> ACTIVE_TASK_STATUSES = Set.of(
            "PENDING", "RUNNING", "AWAITING_PERMISSION", "AWAITING_INPUT");

    private final SessionRepository sessionRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final LlmModelManager llmModelManager;
    private final WorkingDirectoryRepository workingDirectoryRepository;
    private final SessionTaskRepository sessionTaskRepository;

    @Transactional(readOnly = true)
    public List<SessionConfigDTO> listBySessionIds(String userId, List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        Map<String, SessionEntity> sessions = sessionRepository.findAllById(sessionIds).stream()
                .filter(session -> userId.equals(session.getUserId()))
                .filter(session -> session.getDeletedAt() == null)
                .collect(Collectors.toMap(SessionEntity::getId, session -> session, (left, right) -> left, LinkedHashMap::new));
        return sessionIds.stream()
                .map(sessions::get)
                .filter(session -> session != null)
                .map(this::toDTO)
                .toList();
    }

    @Transactional
    public SessionConfigDTO updatePin(String sessionId, String userId, boolean pinned) {
        SessionEntity session = requireOwnedSession(sessionId, userId);
        session.setPinned(pinned);
        session.setPinnedAt(pinned ? LocalDateTime.now() : null);
        return toDTO(sessionRepository.save(session));
    }

    @Transactional
    public SessionConfigDTO updateTitle(String sessionId, String userId, String title) {
        SessionEntity session = requireOwnedSession(sessionId, userId);
        session.setTitle(blankToNull(title));
        return toDTO(sessionRepository.save(session));
    }

    @Transactional
    public SessionConfigDTO updateMilestone(String sessionId, String userId, String milestoneId) {
        SessionEntity session = requireOwnedSession(sessionId, userId);
        String normalizedMilestoneId = blankToNull(milestoneId);
        if (normalizedMilestoneId != null) {
            validateMilestoneOwnership(session, userId, normalizedMilestoneId);
        }
        session.setMilestoneId(normalizedMilestoneId);
        return toDTO(sessionRepository.save(session));
    }

    @Transactional
    public SessionConfigDTO updateTags(String sessionId, String userId, List<String> tags) {
        SessionEntity session = requireOwnedSession(sessionId, userId);
        session.setTagsJson(writeTags(tags));
        return toDTO(sessionRepository.save(session));
    }

    @Transactional
    public SessionConfigDTO bindAuth(String sessionId, String userId, String authMode, String authToken,
                                     String baseUrl, String modelConfigId) {
        SessionEntity session = requireOwnedSession(sessionId, userId);
        if (session.getAuthBoundAt() != null) {
            throw new IllegalStateException("Auth already bound for this conversation");
        }
        ResolvedAuth resolvedAuth = resolveAuthBinding(session, authMode, authToken, baseUrl, modelConfigId);
        applyAuth(session, resolvedAuth.authMode(), resolvedAuth.authToken(), resolvedAuth.baseUrl(),
                resolvedAuth.modelConfigId(), true);
        log.info("Auth bound for session {}: mode={}", sessionId, resolvedAuth.authMode());
        return toDTO(sessionRepository.save(session));
    }

    @Transactional
    public SessionConfigDTO updateAuth(String sessionId, String userId, String authMode, String authToken,
                                       String baseUrl, String modelConfigId) {
        SessionEntity session = requireOwnedSession(sessionId, userId);
        ResolvedAuth resolvedAuth = resolveAuthBinding(session, authMode, authToken, baseUrl, modelConfigId);
        applyAuth(session, resolvedAuth.authMode(), resolvedAuth.authToken(), resolvedAuth.baseUrl(),
                resolvedAuth.modelConfigId(), false);
        log.info("Auth updated for session {}: mode={}", sessionId, resolvedAuth.authMode());
        return toDTO(sessionRepository.save(session));
    }

    @Transactional
    public int batchBindAuth(List<String> sessionIds, String userId, String authMode, String authToken,
                             String baseUrl, boolean skipExisting, String modelConfigId) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        int bound = 0;
        for (String sessionId : sessionIds) {
            SessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId)
                    .filter(entity -> entity.getDeletedAt() == null)
                    .orElse(null);
            if (session == null) {
                continue;
            }
            if (skipExisting && session.getAuthBoundAt() != null) {
                continue;
            }

            ResolvedAuth resolvedAuth = resolveAuthBinding(session, authMode, authToken, baseUrl, modelConfigId);
            applyAuth(session, resolvedAuth.authMode(), resolvedAuth.authToken(), resolvedAuth.baseUrl(),
                    resolvedAuth.modelConfigId(), true);
            sessionRepository.save(session);
            bound++;
        }
        log.info("Batch auth bound: {} sessions, mode={}", bound, authMode);
        return bound;
    }

    @Transactional
    public SessionConfigDTO archiveConversation(String sessionId, String userId) {
        SessionEntity session = requireOwnedSessionForInteractionState(sessionId, userId);
        List<SessionEntity> sessionsToArchive = sessionsForParentCascade(session, userId);
        assertNoActiveTasksForOperation(sessionsToArchive, userId, "archive");
        return updateInteractionState(sessionsToArchive, session, "ARCHIVED");
    }

    @Transactional
    public SessionConfigDTO unarchiveConversation(String sessionId, String userId) {
        return updateInteractionState(sessionId, userId, "AWAITING_REPLY");
    }

    @Transactional
    public SessionConfigDTO holdConversation(String sessionId, String userId) {
        SessionEntity session = requireOwnedSessionForInteractionState(sessionId, userId);
        return updateInteractionState(sessionsForParentCascade(session, userId), session, "ON_HOLD");
    }

    @Transactional
    public SessionConfigDTO unholdConversation(String sessionId, String userId) {
        return updateInteractionState(sessionId, userId, "AWAITING_REPLY");
    }

    @Transactional
    public boolean deleteConversation(String sessionId, String userId) {
        SessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId).orElse(null);
        if (session != null) {
            List<SessionEntity> sessionsToDelete = sessionsForParentCascade(session, userId);
            assertNoActiveTasksForOperation(sessionsToDelete, userId, "delete");
            LocalDateTime now = LocalDateTime.now();
            for (SessionEntity target : sessionsToDelete) {
                softDeleteSession(target, now);
            }
            log.info("Session soft-deleted with cascade: sessionId={}, userId={}, affected={}",
                    sessionId, userId, sessionsToDelete.size());
            return true;
        }

        List<SessionTaskEntity> tasks = sessionTaskRepository
                .findBySessionIdAndUserIdOrderByCreatedAtDesc(sessionId, userId);
        if (tasks.stream().anyMatch(task -> isActiveTaskStatus(task.getStatus()))) {
            throw new IllegalStateException("Cannot delete a session with active tasks. Please abort it first.");
        }

        if (tasks.isEmpty()) {
            log.info("Delete session ignored because no owned session or task projection exists: sessionId={}, userId={}",
                    sessionId, userId);
            return false;
        }
        session = createSessionMetadataFromTaskProjection(sessionId, userId, tasks.get(0));
        softDeleteSession(session, LocalDateTime.now());
        log.info("Session soft-deleted from task projection: sessionId={}, userId={}", sessionId, userId);
        return true;
    }

    private boolean isActiveTaskStatus(String status) {
        return status != null && ACTIVE_TASK_STATUSES.contains(status.toUpperCase(Locale.ROOT));
    }

    private List<SessionEntity> sessionsForParentCascade(SessionEntity session, String userId) {
        if (blankToNull(session.getParentSessionId()) != null) {
            return List.of(session);
        }
        List<SessionEntity> children = sessionRepository.findActiveChildrenByParentSessionId(userId, session.getId());
        if (children.isEmpty()) {
            return List.of(session);
        }
        List<SessionEntity> result = new java.util.ArrayList<>(children.size() + 1);
        result.add(session);
        result.addAll(children);
        return result;
    }

    private void assertNoActiveTasksForOperation(List<SessionEntity> sessions, String userId, String operation) {
        List<String> sessionIds = sessions.stream()
                .map(SessionEntity::getId)
                .toList();
        if (sessionIds.isEmpty()) {
            return;
        }
        boolean hasActiveTask = sessionTaskRepository
                .findBySessionIdInAndUserIdOrderByCreatedAtDesc(sessionIds, userId)
                .stream()
                .anyMatch(task -> isActiveTaskStatus(task.getStatus()));
        if (hasActiveTask) {
            throw new IllegalStateException("Cannot " + operation + " a session with active tasks. Please abort it first.");
        }
    }

    private void softDeleteSession(SessionEntity session, LocalDateTime deletedAt) {
        if (session.getDeletedAt() != null) {
            return;
        }
        session.setDeletedAt(deletedAt);
        session.setStatus("DELETED");
        session.setInteractionState("DELETED");
        session.setPinned(false);
        session.setPinnedAt(null);
        session.setLastActivityAt(deletedAt);
        sessionRepository.save(session);
    }

    private SessionConfigDTO updateInteractionState(List<SessionEntity> sessions, SessionEntity currentSession, String interactionState) {
        SessionEntity savedCurrent = null;
        for (SessionEntity target : sessions) {
            target.setInteractionState(interactionState);
            SessionEntity saved = sessionRepository.save(target);
            if (target.getId().equals(currentSession.getId())) {
                savedCurrent = saved;
            }
        }
        return toDTO(savedCurrent != null ? savedCurrent : currentSession);
    }

    private SessionConfigDTO updateInteractionState(String sessionId, String userId, String interactionState) {
        SessionEntity session = requireOwnedSessionForInteractionState(sessionId, userId);
        session.setInteractionState(interactionState);
        return toDTO(sessionRepository.save(session));
    }

    private SessionEntity requireOwnedSession(String sessionId, String userId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .filter(session -> session.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    private SessionEntity requireOwnedSessionForInteractionState(String sessionId, String userId) {
        SessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId).orElse(null);
        if (session != null) {
            if (session.getDeletedAt() != null) {
                throw new IllegalStateException("Session already deleted: " + sessionId);
            }
            return session;
        }
        return createSessionMetadataFromTaskProjection(sessionId, userId);
    }

    private SessionEntity createSessionMetadataFromTaskProjection(String sessionId, String userId) {
        SessionTaskEntity latestTask = sessionTaskRepository.findFirstBySessionIdAndUserIdOrderByCreatedAtDesc(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        return createSessionMetadataFromTaskProjection(sessionId, userId, latestTask);
    }

    private SessionEntity createSessionMetadataFromTaskProjection(String sessionId, String userId, SessionTaskEntity latestTask) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setTenantId(latestTask.getTenantId());
        session.setAgentId(blankToNull(latestTask.getAgentId()));
        session.setProviderType(blankToNull(latestTask.getProviderType()));
        session.setStatus("ACTIVE");
        session.setInteractionState("PROCESSING");
        session.setPinned(false);
        session.setCurrentWorkerId(blankToNull(latestTask.getWorkerId()));
        session.setCurrentDirectoryId(blankToNull(latestTask.getDirectoryId()));
        session.setLatestTaskId(blankToNull(latestTask.getTaskId()));
        session.setLatestModel(blankToNull(latestTask.getModel()));
        session.setLastActivityAt(firstNonNull(latestTask.getUpdatedAt(), latestTask.getCreatedAt(), LocalDateTime.now()));
        session.setCreatedAt(firstNonNull(latestTask.getCreatedAt(), LocalDateTime.now()));
        session.setUpdatedAt(firstNonNull(latestTask.getUpdatedAt(), latestTask.getCreatedAt(), LocalDateTime.now()));
        log.info("Created missing session metadata from task projection: sessionId={}, userId={}, taskId={}",
                sessionId, userId, latestTask.getTaskId());
        return session;
    }

    private void validateMilestoneOwnership(SessionEntity session, String userId, String milestoneId) {
        String directoryId = blankToNull(session.getCurrentDirectoryId());
        if (directoryId == null) {
            throw new IllegalArgumentException("Session is not bound to a working directory");
        }
        WorkingDirectoryEntity directory = workingDirectoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Working directory not found: " + directoryId));
        boolean exists = parseMilestones(directory.getMilestonesJson()).stream()
                .map(DirectoryMilestoneDTO::getId)
                .anyMatch(milestoneId::equals);
        if (!exists) {
            throw new IllegalArgumentException("Milestone not found in directory: " + milestoneId);
        }
    }

    private ResolvedAuth resolveAuthBinding(SessionEntity session, String authMode, String authToken,
                                            String baseUrl, String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return new ResolvedAuth(blankToNull(authMode), blankToNull(authToken), blankToNull(baseUrl), null);
        }

        LlmModelConfigDTO modelConfig = llmModelManager.getModelConfig(modelConfigId)
                .orElseThrow(() -> new IllegalArgumentException(MODEL_CONFIG_ACCESS_DENIED));
        requireModelConfigOwnedBySessionTenant(session, modelConfig);

        String workerId = blankToNull(session.getCurrentWorkerId());
        if (workerId != null) {
            llmModelManager.validateModelAccessForWorker(modelConfigId, workerId);
        }

        if (isSubscriptionConfig(modelConfig)) {
            return new ResolvedAuth("SUBSCRIPTION", null, null, modelConfigId);
        }

        String decryptedApiKey = llmModelManager.getDecryptedApiKey(modelConfigId);
        if (decryptedApiKey == null || decryptedApiKey.isBlank()) {
            return new ResolvedAuth(blankToNull(authMode), blankToNull(authToken), blankToNull(baseUrl), null);
        }

        String resolvedMode = modelConfig.getBaseUrl() != null && !modelConfig.getBaseUrl().isBlank()
                ? "CUSTOM_ENDPOINT"
                : "API_KEY";
        return new ResolvedAuth(resolvedMode, decryptedApiKey, blankToNull(modelConfig.getBaseUrl()), modelConfigId);
    }

    /**
     * An explicit model configuration is a credential-bearing resource.  Session
     * ownership alone does not authorize a configuration from another tenant or
     * a legacy configuration whose ownership metadata is incomplete.
     */
    private void requireModelConfigOwnedBySessionTenant(
            SessionEntity session, LlmModelConfigDTO modelConfig) {
        String sessionTenantId = blankToNull(session.getTenantId());
        String modelTenantId = blankToNull(modelConfig.getTenantId());
        if (!Boolean.TRUE.equals(modelConfig.getEnabled())
                || sessionTenantId == null
                || modelTenantId == null
                || !sessionTenantId.equals(modelTenantId)
                || modelConfig.getOwnerType() == null
                || blankToNull(modelConfig.getOwnerId()) == null) {
            throw new IllegalArgumentException(MODEL_CONFIG_ACCESS_DENIED);
        }
    }

    private boolean isSubscriptionConfig(LlmModelConfigDTO modelConfig) {
        return modelConfig != null
                && modelConfig.getWorkerBackend() != null
                && !modelConfig.getWorkerBackend().isBlank()
                && (modelConfig.getBaseUrl() == null || modelConfig.getBaseUrl().isBlank())
                && !Boolean.TRUE.equals(modelConfig.getHasApiKey());
    }

    private void applyAuth(SessionEntity session, String authMode, String authToken, String baseUrl,
                           String modelConfigId, boolean setBoundAtWhenMissing) {
        if (authMode != null && !authMode.isBlank()) {
            session.setAuthMode(authMode);
        }
        if (authToken != null && !authToken.isBlank()) {
            session.setAuthTokenCiphertext(credentialEncryptor.encrypt(authToken));
        }
        if (baseUrl != null) {
            session.setAuthBaseUrl(blankToNull(baseUrl));
        }
        session.setAuthModelConfigId(blankToNull(modelConfigId));
        if (setBoundAtWhenMissing && session.getAuthBoundAt() == null) {
            session.setAuthBoundAt(LocalDateTime.now());
        }
    }

    private SessionConfigDTO toDTO(SessionEntity session) {
        String maskedAuthToken = null;
        if (session.getAuthTokenCiphertext() != null && session.getAuthBoundAt() != null) {
            try {
                maskedAuthToken = maskToken(credentialEncryptor.decrypt(session.getAuthTokenCiphertext()));
            } catch (Exception e) {
                maskedAuthToken = "***";
            }
        }
        return SessionConfigDTO.builder()
                .sessionId(session.getId())
                .pinned(Boolean.TRUE.equals(session.getPinned()))
                .pinnedAt(session.getPinnedAt())
                .customTitle(session.getTitle())
                .authMode(session.getAuthMode())
                .authBound(session.getAuthBoundAt() != null)
                .authModelConfigId(session.getAuthModelConfigId())
                .baseUrl(session.getAuthBaseUrl())
                .maskedAuthToken(maskedAuthToken)
                .tags(parseTags(session.getTagsJson()))
                .interactionState(session.getInteractionState())
                .milestoneId(session.getMilestoneId())
                .build();
    }

    private List<DirectoryMilestoneDTO> parseMilestones(String milestonesJson) {
        if (milestonesJson == null || milestonesJson.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(milestonesJson, new TypeReference<List<DirectoryMilestoneDTO>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse milestones JSON: {}", milestonesJson);
            return List.of();
        }
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse session tags JSON: {}", tagsJson);
            return Collections.emptyList();
        }
    }

    private String writeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(tags.stream()
                    .map(this::blankToNull)
                    .filter(tag -> tag != null)
                    .toList());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid tags format", e);
        }
    }

    private String maskToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        if (token.length() <= 10) {
            return token.substring(0, 2) + "****" + token.substring(token.length() - 2);
        }
        return token.substring(0, 6) + "****" + token.substring(token.length() - 4);
    }

    private String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
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

    private record ResolvedAuth(String authMode, String authToken, String baseUrl, String modelConfigId) {
    }
}
