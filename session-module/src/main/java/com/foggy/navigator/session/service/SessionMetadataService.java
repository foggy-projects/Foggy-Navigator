package com.foggy.navigator.session.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.SessionEntity;
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
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionMetadataService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SessionRepository sessionRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final LlmModelManager llmModelManager;

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
    public SessionConfigDTO updateTags(String sessionId, String userId, List<String> tags) {
        SessionEntity session = requireOwnedSession(sessionId, userId);
        session.setTagsJson(writeTags(tags));
        return toDTO(sessionRepository.save(session));
    }

    @Transactional
    public SessionConfigDTO bindAuth(String sessionId, String userId, String authMode, String authToken, String baseUrl) {
        SessionEntity session = requireOwnedSession(sessionId, userId);
        if (session.getAuthBoundAt() != null) {
            throw new IllegalStateException("Auth already bound for this conversation");
        }
        applyAuth(session, authMode, authToken, baseUrl, null, true);
        log.info("Auth bound for session {}: mode={}", sessionId, authMode);
        return toDTO(sessionRepository.save(session));
    }

    @Transactional
    public SessionConfigDTO updateAuth(String sessionId, String userId, String authMode, String authToken, String baseUrl) {
        SessionEntity session = requireOwnedSession(sessionId, userId);
        applyAuth(session, authMode, authToken, baseUrl, null, false);
        log.info("Auth updated for session {}: mode={}", sessionId, authMode);
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
        return updateInteractionState(sessionId, userId, "ARCHIVED");
    }

    @Transactional
    public SessionConfigDTO unarchiveConversation(String sessionId, String userId) {
        return updateInteractionState(sessionId, userId, "AWAITING_REPLY");
    }

    @Transactional
    public SessionConfigDTO holdConversation(String sessionId, String userId) {
        return updateInteractionState(sessionId, userId, "ON_HOLD");
    }

    @Transactional
    public SessionConfigDTO unholdConversation(String sessionId, String userId) {
        return updateInteractionState(sessionId, userId, "AWAITING_REPLY");
    }

    private SessionConfigDTO updateInteractionState(String sessionId, String userId, String interactionState) {
        SessionEntity session = requireOwnedSession(sessionId, userId);
        session.setInteractionState(interactionState);
        return toDTO(sessionRepository.save(session));
    }

    private SessionEntity requireOwnedSession(String sessionId, String userId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .filter(session -> session.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    private ResolvedAuth resolveAuthBinding(SessionEntity session, String authMode, String authToken,
                                            String baseUrl, String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return new ResolvedAuth(blankToNull(authMode), blankToNull(authToken), blankToNull(baseUrl), null);
        }

        String workerId = blankToNull(session.getCurrentWorkerId());
        if (workerId != null) {
            llmModelManager.validateModelAccessForWorker(modelConfigId, workerId);
        }

        LlmModelConfigDTO modelConfig = llmModelManager.getModelConfig(modelConfigId).orElse(null);
        if (modelConfig == null) {
            return new ResolvedAuth(blankToNull(authMode), blankToNull(authToken), blankToNull(baseUrl), null);
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
                .baseUrl(session.getAuthBaseUrl())
                .maskedAuthToken(maskedAuthToken)
                .tags(parseTags(session.getTagsJson()))
                .interactionState(session.getInteractionState())
                .build();
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

    private record ResolvedAuth(String authMode, String authToken, String baseUrl, String modelConfigId) {
    }
}
