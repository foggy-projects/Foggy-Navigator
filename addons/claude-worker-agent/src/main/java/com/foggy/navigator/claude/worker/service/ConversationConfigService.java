package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.model.dto.ConversationConfigDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ConversationConfigEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
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
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 会话级配置服务（置顶、自定义标题、Auth 绑定）。
 *
 * 当前统一写入 SessionEntity，不再以 claude_conversation_configs 作为主存储。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationConfigService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CLAUDE_PROVIDER_TYPE = "claude-worker";
    private static final String AGENT_TEAMS_CONFIG_ID = "agentTeamsConfigId";

    private final SessionEntityRepository sessionRepository;
    private final ClaudeTaskRepository taskRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final LlmModelManager llmModelManager;

    @Transactional
    public ConversationConfigEntity getOrCreate(String sessionId, String workerId, String userId) {
        SessionEntity session = getOrCreateSessionEntity(sessionId, workerId, userId);
        return toConfigEntity(session, workerId);
    }

    @Transactional
    public ConversationConfigEntity getOrCreateBySessionId(String sessionId, String userId) {
        String workerId = inferWorkerId(sessionId);
        return getOrCreate(sessionId, workerId, userId);
    }

    @Transactional
    public ConversationConfigEntity saveConfig(ConversationConfigEntity entity) {
        SessionEntity saved = persist(entity);
        return toConfigEntity(saved, entity.getWorkerId());
    }

    @Transactional
    public void updateAgentTeamsConfigId(String sessionId, String workerId, String userId, String agentTeamsConfigId) {
        ConversationConfigEntity entity = getOrCreate(sessionId, workerId, userId);
        entity.setAgentTeamsConfigId(agentTeamsConfigId);
        saveConfig(entity);
    }

    @Transactional
    public ConversationConfigDTO updatePin(String sessionId, String userId, boolean pinned) {
        ConversationConfigEntity entity = requireOwnedConfig(sessionId, userId);
        entity.setPinned(pinned);
        entity.setPinnedAt(pinned ? LocalDateTime.now() : null);
        return toDTO(saveConfig(entity));
    }

    @Transactional
    public ConversationConfigDTO updateTitle(String sessionId, String userId, String title) {
        ConversationConfigEntity entity = requireOwnedConfig(sessionId, userId);
        entity.setCustomTitle(blankToNull(title));
        return toDTO(saveConfig(entity));
    }

    @Transactional
    public ConversationConfigDTO bindAuth(String sessionId, String userId,
                                          String authMode, String authToken, String baseUrl) {
        ConversationConfigEntity entity = requireOwnedConfig(sessionId, userId);
        if (entity.getAuthBoundAt() != null) {
            throw new IllegalStateException("Auth already bound for this conversation");
        }
        entity.setAuthMode(blankToNull(authMode));
        if (authToken != null && !authToken.isEmpty()) {
            entity.setAuthToken(credentialEncryptor.encrypt(authToken));
        }
        entity.setBaseUrl(blankToNull(baseUrl));
        entity.setAuthBoundAt(LocalDateTime.now());
        ConversationConfigEntity saved = saveConfig(entity);
        updateAuthModelConfigId(sessionId, null);
        log.info("Auth bound for session {}: mode={}", sessionId, authMode);
        return toDTO(saved);
    }

    @Transactional
    public int batchBindAuth(List<String> sessionIds, String userId,
                             String authMode, String authToken, String baseUrl,
                             boolean skipExisting, String modelConfigId) {
        int bound = 0;
        for (String sessionId : sessionIds) {
            ConversationConfigEntity entity = getOrCreateBySessionId(sessionId, userId);
            if (!Objects.equals(entity.getUserId(), userId)) {
                continue;
            }
            if (skipExisting && entity.getAuthBoundAt() != null) {
                continue;
            }

            String finalAuthMode = authMode;
            String finalAuthToken = authToken;
            String finalBaseUrl = baseUrl;
            String finalModelConfigId = null;

            if (modelConfigId != null && !modelConfigId.isEmpty()) {
                try {
                    String workerId = entity.getWorkerId();
                    if (workerId != null) {
                        llmModelManager.validateModelAccessForWorker(modelConfigId, workerId);
                        LlmModelConfigDTO modelConfig = llmModelManager.getModelConfig(modelConfigId).orElse(null);
                        if (modelConfig != null) {
                            String decryptedApiKey = llmModelManager.getDecryptedApiKey(modelConfigId);
                            if (decryptedApiKey != null && !decryptedApiKey.isBlank()) {
                                finalAuthMode = (modelConfig.getBaseUrl() != null && !modelConfig.getBaseUrl().isEmpty())
                                        ? "CUSTOM_ENDPOINT" : "API_KEY";
                                finalAuthToken = decryptedApiKey;
                                finalBaseUrl = modelConfig.getBaseUrl();
                                finalModelConfigId = modelConfigId;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to resolve model config {}: {}", modelConfigId, e.getMessage());
                }
            }

            if (finalAuthMode != null && !finalAuthMode.isEmpty()) {
                entity.setAuthMode(finalAuthMode);
            }
            if (finalAuthToken != null && !finalAuthToken.isEmpty()) {
                entity.setAuthToken(credentialEncryptor.encrypt(finalAuthToken));
            }
            entity.setBaseUrl(blankToNull(finalBaseUrl));
            if (entity.getAuthBoundAt() == null) {
                entity.setAuthBoundAt(LocalDateTime.now());
            }
            saveConfig(entity);
            updateAuthModelConfigId(sessionId, finalModelConfigId);
            bound++;
        }
        log.info("Batch auth bound: {} sessions, mode={}", bound, authMode);
        return bound;
    }

    @Transactional
    public void bindAuthFromWorker(String sessionId, String workerId, String userId,
                                   Map<String, Object> workerAuthConfig) {
        ConversationConfigEntity entity = getOrCreate(sessionId, workerId, userId);
        if (entity.getAuthBoundAt() != null) {
            return;
        }

        String authMode = (String) workerAuthConfig.getOrDefault("auth_mode", "SUBSCRIPTION");
        String apiKey = (String) workerAuthConfig.get("api_key");
        String authToken = (String) workerAuthConfig.get("auth_token");
        String baseUrl = (String) workerAuthConfig.get("base_url");

        entity.setAuthMode(authMode);
        String tokenToStore = apiKey != null ? apiKey : authToken;
        if (tokenToStore != null) {
            entity.setAuthToken(credentialEncryptor.encrypt(tokenToStore));
        }
        entity.setBaseUrl(blankToNull(baseUrl));
        entity.setAuthBoundAt(LocalDateTime.now());
        saveConfig(entity);
        updateAuthModelConfigId(sessionId, null);
        log.info("Auth auto-bound from worker for session {}: mode={}", sessionId, authMode);
    }

    @Transactional
    public void bindAuthFromDirectory(String sessionId, String workerId, String userId,
                                      String authMode, String plainToken, String baseUrl) {
        ConversationConfigEntity entity = getOrCreate(sessionId, workerId, userId);
        if (entity.getAuthBoundAt() != null) {
            return;
        }
        entity.setAuthMode(authMode);
        if (plainToken != null && !plainToken.isEmpty()) {
            entity.setAuthToken(credentialEncryptor.encrypt(plainToken));
        }
        entity.setBaseUrl(blankToNull(baseUrl));
        entity.setAuthBoundAt(LocalDateTime.now());
        saveConfig(entity);
        updateAuthModelConfigId(sessionId, null);
        log.info("Auth auto-bound from directory for session {}: mode={}", sessionId, authMode);
    }

    @Transactional
    public ConversationConfigDTO updateTags(String sessionId, String userId, List<String> tags) {
        ConversationConfigEntity entity = requireOwnedConfig(sessionId, userId);
        try {
            entity.setTags(tags != null && !tags.isEmpty()
                    ? OBJECT_MAPPER.writeValueAsString(tags)
                    : null);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid tags format");
        }
        return toDTO(saveConfig(entity));
    }

    public List<ConversationConfigDTO> listBySessionIds(List<String> sessionIds) {
        return sessionRepository.findAllById(sessionIds).stream()
                .map(session -> toDTO(toConfigEntity(session, null)))
                .toList();
    }

    @Transactional
    public ConversationConfigDTO updateAuth(String sessionId, String userId,
                                            String authMode, String authToken, String baseUrl) {
        ConversationConfigEntity entity = requireOwnedConfig(sessionId, userId);
        if (authMode != null && !authMode.isEmpty()) {
            entity.setAuthMode(authMode);
        }
        if (authToken != null && !authToken.isEmpty()) {
            entity.setAuthToken(credentialEncryptor.encrypt(authToken));
        }
        if (baseUrl != null) {
            entity.setBaseUrl(blankToNull(baseUrl));
        }
        if (entity.getAuthBoundAt() == null) {
            entity.setAuthBoundAt(LocalDateTime.now());
        }
        ConversationConfigEntity saved = saveConfig(entity);
        updateAuthModelConfigId(sessionId, null);
        log.info("Auth updated for session {}: mode={}", sessionId, authMode);
        return toDTO(saved);
    }

    public List<String> findSessionIdsByInteractionState(String userId, String state) {
        return sessionRepository.findSessionIdsByInteractionState(userId, state);
    }

    public List<String> findSessionIdsByInteractionStates(String userId, List<String> states) {
        return sessionRepository.findSessionIdsByInteractionStateIn(userId, states);
    }

    public List<String> findSessionIdsByTitleKeyword(String userId, String keyword) {
        return sessionRepository.findSessionIdsByTitleKeyword(userId, keyword);
    }

    public List<String> findSessionIdsByTagKeyword(String userId, String keyword) {
        return sessionRepository.findSessionIdsByTagKeyword(userId, keyword);
    }

    @Transactional
    public void updateInteractionState(String sessionId, String state) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setInteractionState(state);
            session.setLastActivityAt(LocalDateTime.now());
            sessionRepository.save(session);
        });
    }

    @Transactional
    public ConversationConfigDTO archiveConversation(String sessionId, String userId) {
        ConversationConfigEntity entity = requireOwnedConfig(sessionId, userId);
        entity.setInteractionState("ARCHIVED");
        return toDTO(saveConfig(entity));
    }

    @Transactional
    public ConversationConfigDTO unarchiveConversation(String sessionId, String userId) {
        ConversationConfigEntity entity = requireOwnedConfig(sessionId, userId);
        entity.setInteractionState("AWAITING_REPLY");
        return toDTO(saveConfig(entity));
    }

    @Transactional
    public ConversationConfigDTO holdConversation(String sessionId, String userId) {
        ConversationConfigEntity entity = requireOwnedConfig(sessionId, userId);
        entity.setInteractionState("ON_HOLD");
        return toDTO(saveConfig(entity));
    }

    @Transactional
    public ConversationConfigDTO unholdConversation(String sessionId, String userId) {
        ConversationConfigEntity entity = requireOwnedConfig(sessionId, userId);
        entity.setInteractionState("AWAITING_REPLY");
        return toDTO(saveConfig(entity));
    }

    public ConversationConfigEntity getConfigEntity(String sessionId) {
        return sessionRepository.findById(sessionId)
                .map(session -> toConfigEntity(session, null))
                .orElse(null);
    }

    public String getDecryptedToken(ConversationConfigEntity entity) {
        if (entity.getAuthToken() == null) {
            return null;
        }
        return credentialEncryptor.decrypt(entity.getAuthToken());
    }

    public Map<String, ConversationConfigEntity> getConfigMapBySessionIds(List<String> sessionIds) {
        return sessionRepository.findAllById(sessionIds).stream()
                .map(session -> toConfigEntity(session, null))
                .collect(Collectors.toMap(ConversationConfigEntity::getSessionId, entity -> entity));
    }

    private ConversationConfigEntity requireOwnedConfig(String sessionId, String userId) {
        ConversationConfigEntity entity = getOrCreateBySessionId(sessionId, userId);
        if (!Objects.equals(entity.getUserId(), userId)) {
            throw new IllegalArgumentException("Access denied");
        }
        return entity;
    }

    private SessionEntity getOrCreateSessionEntity(String sessionId, String workerId, String userId) {
        SessionEntity existing = sessionRepository.findById(sessionId).orElse(null);
        if (existing != null) {
            boolean changed = false;
            if ((existing.getUserId() == null || existing.getUserId().isBlank()) && userId != null && !userId.isBlank()) {
                existing.setUserId(userId);
                changed = true;
            }
            if ((existing.getCurrentWorkerId() == null || existing.getCurrentWorkerId().isBlank()) && workerId != null && !workerId.isBlank()) {
                existing.setCurrentWorkerId(workerId);
                changed = true;
            }
            if (existing.getProviderType() == null || existing.getProviderType().isBlank()) {
                existing.setProviderType(CLAUDE_PROVIDER_TYPE);
                changed = true;
            }
            if (changed) {
                return sessionRepository.save(existing);
            }
            return existing;
        }

        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setProviderType(CLAUDE_PROVIDER_TYPE);
        session.setCurrentWorkerId(workerId);
        session.setStatus("ACTIVE");
        session.setInteractionState("PROCESSING");
        session.setLastActivityAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    private String inferWorkerId(String sessionId) {
        return taskRepository.findBySessionId(sessionId).stream()
                .findFirst()
                .map(ClaudeTaskEntity::getWorkerId)
                .orElse("unknown");
    }

    private SessionEntity persist(ConversationConfigEntity entity) {
        SessionEntity session = getOrCreateSessionEntity(entity.getSessionId(), entity.getWorkerId(), entity.getUserId());
        session.setPinned(Boolean.TRUE.equals(entity.getPinned()));
        session.setPinnedAt(entity.getPinnedAt());
        session.setTitle(blankToNull(entity.getCustomTitle()));
        session.setInteractionState(blankToNull(entity.getInteractionState()));
        session.setTagsJson(blankToNull(entity.getTags()));
        session.setAuthMode(blankToNull(entity.getAuthMode()));
        session.setAuthBoundAt(entity.getAuthBoundAt());
        session.setAuthBaseUrl(blankToNull(entity.getBaseUrl()));
        session.setAuthTokenCiphertext(blankToNull(entity.getAuthToken()));
        session.setCurrentWorkerId(firstNonBlank(entity.getWorkerId(), session.getCurrentWorkerId()));
        session.setProviderType(firstNonBlank(session.getProviderType(), CLAUDE_PROVIDER_TYPE));
        session.setProviderStateJson(mergeProviderState(session.getProviderStateJson(),
                AGENT_TEAMS_CONFIG_ID, entity.getAgentTeamsConfigId()));
        if (session.getLastActivityAt() == null) {
            session.setLastActivityAt(LocalDateTime.now());
        }
        return sessionRepository.save(session);
    }

    private void updateAuthModelConfigId(String sessionId, String modelConfigId) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setAuthModelConfigId(blankToNull(modelConfigId));
            sessionRepository.save(session);
        });
    }

    private ConversationConfigEntity toConfigEntity(SessionEntity session, String fallbackWorkerId) {
        ConversationConfigEntity entity = new ConversationConfigEntity();
        entity.setSessionId(session.getId());
        entity.setUserId(session.getUserId());
        entity.setWorkerId(firstNonBlank(session.getCurrentWorkerId(), fallbackWorkerId, inferWorkerId(session.getId())));
        entity.setPinned(Boolean.TRUE.equals(session.getPinned()));
        entity.setPinnedAt(session.getPinnedAt());
        entity.setCustomTitle(session.getTitle());
        entity.setAuthMode(session.getAuthMode());
        entity.setAuthToken(session.getAuthTokenCiphertext());
        entity.setBaseUrl(session.getAuthBaseUrl());
        entity.setAuthBoundAt(session.getAuthBoundAt());
        entity.setTags(session.getTagsJson());
        entity.setInteractionState(session.getInteractionState());
        entity.setAgentTeamsConfigId(readProviderStateValue(session.getProviderStateJson(), AGENT_TEAMS_CONFIG_ID));
        entity.setCreatedAt(session.getCreatedAt());
        entity.setUpdatedAt(session.getUpdatedAt());
        return entity;
    }

    private ConversationConfigDTO toDTO(ConversationConfigEntity entity) {
        String masked = null;
        if (entity.getAuthToken() != null && entity.getAuthBoundAt() != null) {
            try {
                masked = maskToken(credentialEncryptor.decrypt(entity.getAuthToken()));
            } catch (Exception e) {
                masked = "***";
            }
        }
        return ConversationConfigDTO.builder()
                .sessionId(entity.getSessionId())
                .pinned(Boolean.TRUE.equals(entity.getPinned()))
                .pinnedAt(entity.getPinnedAt())
                .customTitle(entity.getCustomTitle())
                .authMode(entity.getAuthMode())
                .authBound(entity.getAuthBoundAt() != null)
                .baseUrl(entity.getBaseUrl())
                .maskedAuthToken(masked)
                .tags(parseTags(entity.getTags()))
                .interactionState(entity.getInteractionState())
                .build();
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tags JSON: {}", tagsJson);
            return Collections.emptyList();
        }
    }

    private String readProviderStateValue(String providerStateJson, String key) {
        if (providerStateJson == null || providerStateJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> values = OBJECT_MAPPER.readValue(providerStateJson, new TypeReference<Map<String, Object>>() {});
            Object value = values.get(key);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to read provider state {} from session JSON", key, e);
            return null;
        }
    }

    private String mergeProviderState(String providerStateJson, String key, String value) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (providerStateJson != null && !providerStateJson.isBlank()) {
            try {
                values.putAll(OBJECT_MAPPER.readValue(providerStateJson, new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                log.warn("Failed to parse providerStateJson, recreating JSON: {}", providerStateJson);
            }
        }
        if (value == null || value.isBlank()) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        if (values.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize provider state");
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
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
}
