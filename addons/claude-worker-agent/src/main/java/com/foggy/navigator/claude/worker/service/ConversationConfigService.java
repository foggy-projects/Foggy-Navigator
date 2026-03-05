package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.dto.ConversationConfigDTO;
import com.foggy.navigator.claude.worker.model.entity.ConversationConfigEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.repository.ConversationConfigRepository;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话级配置服务（置顶、自定义标题、Auth 绑定）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationConfigService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ConversationConfigRepository configRepository;
    private final ClaudeTaskRepository taskRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final LlmModelManager llmModelManager;

    /**
     * 获取或创建会话配置
     */
    @Transactional
    public ConversationConfigEntity getOrCreate(String sessionId, String workerId, String userId) {
        return configRepository.findBySessionId(sessionId)
                .orElseGet(() -> {
                    ConversationConfigEntity entity = new ConversationConfigEntity();
                    entity.setSessionId(sessionId);
                    entity.setWorkerId(workerId);
                    entity.setUserId(userId);
                    return configRepository.save(entity);
                });
    }

    /**
     * 按 sessionId 获取或自动创建配置（从 task 表推断 workerId）
     */
    @Transactional
    public ConversationConfigEntity getOrCreateBySessionId(String sessionId, String userId) {
        return configRepository.findBySessionId(sessionId)
                .orElseGet(() -> {
                    // 从 task 表推断 workerId
                    String workerId = taskRepository.findBySessionId(sessionId).stream()
                            .findFirst()
                            .map(ClaudeTaskEntity::getWorkerId)
                            .orElse("unknown");
                    ConversationConfigEntity entity = new ConversationConfigEntity();
                    entity.setSessionId(sessionId);
                    entity.setWorkerId(workerId);
                    entity.setUserId(userId);
                    return configRepository.save(entity);
                });
    }

    /**
     * 切换置顶
     */
    @Transactional
    public ConversationConfigDTO updatePin(String sessionId, String userId, boolean pinned) {
        ConversationConfigEntity entity = getOrCreateBySessionId(sessionId, userId);
        if (!entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Access denied");
        }
        entity.setPinned(pinned);
        entity.setPinnedAt(pinned ? LocalDateTime.now() : null);
        configRepository.save(entity);
        return toDTO(entity);
    }

    /**
     * 设置自定义标题
     */
    @Transactional
    public ConversationConfigDTO updateTitle(String sessionId, String userId, String title) {
        ConversationConfigEntity entity = getOrCreateBySessionId(sessionId, userId);
        if (!entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Access denied");
        }
        entity.setCustomTitle(title);
        configRepository.save(entity);
        return toDTO(entity);
    }

    /**
     * 手动绑定 Auth（仅当尚未绑定时）
     */
    @Transactional
    public ConversationConfigDTO bindAuth(String sessionId, String userId,
                                           String authMode, String authToken, String baseUrl) {
        ConversationConfigEntity entity = getOrCreateBySessionId(sessionId, userId);
        if (!entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Access denied");
        }
        if (entity.getAuthBoundAt() != null) {
            throw new IllegalStateException("Auth already bound for this conversation");
        }
        entity.setAuthMode(authMode);
        if (authToken != null && !authToken.isEmpty()) {
            entity.setAuthToken(credentialEncryptor.encrypt(authToken));
        }
        entity.setBaseUrl(baseUrl);
        entity.setAuthBoundAt(LocalDateTime.now());
        configRepository.save(entity);
        log.info("Auth bound for session {}: mode={}", sessionId, authMode);
        return toDTO(entity);
    }

    /**
     * 批量绑定 Auth（按 sessionId 列表）
     * 优先级：
     * 1. 如果提供了 modelConfigId，使用平台模型配置的 apiKey + baseUrl
     * 2. 否则使用手动提供的 authToken + baseUrl
     */
    @Transactional
    public int batchBindAuth(List<String> sessionIds, String userId,
                              String authMode, String authToken, String baseUrl,
                              boolean skipExisting, String modelConfigId) {
        int bound = 0;
        for (String sessionId : sessionIds) {
            ConversationConfigEntity entity = getOrCreateBySessionId(sessionId, userId);
            if (!entity.getUserId().equals(userId)) continue;
            // 如果跳过已有配置，且已绑定，则跳过
            if (skipExisting && entity.getAuthBoundAt() != null) continue;

            // 决定使用哪个 auth 配置
            String finalAuthMode = authMode;
            String finalAuthToken = authToken;
            String finalBaseUrl = baseUrl;

            if (modelConfigId != null && !modelConfigId.isEmpty()) {
                // 使用平台模型配置的 auth
                try {
                    // 通过 TaskRepository 获取 workerId
                    String workerId = taskRepository.findBySessionId(sessionId).stream()
                            .findFirst()
                            .map(ClaudeTaskEntity::getWorkerId)
                            .orElse(null);
                    if (workerId != null) {
                        // 校验 Worker 是否有权使用该模型
                        llmModelManager.validateModelAccessForWorker(modelConfigId, workerId);
                        var modelConfig = llmModelManager.getModelConfig(modelConfigId).orElse(null);
                        if (modelConfig != null && Boolean.TRUE.equals(modelConfig.getHasApiKey())) {
                            String decryptedApiKey = llmModelManager.getDecryptedApiKey(modelConfigId);
                            finalAuthMode = (modelConfig.getBaseUrl() != null && !modelConfig.getBaseUrl().isEmpty())
                                    ? "CUSTOM_ENDPOINT" : "API_KEY";
                            finalAuthToken = decryptedApiKey;
                            finalBaseUrl = modelConfig.getBaseUrl();
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to resolve model config {}: {}", modelConfigId, e.getMessage());
                }
            }

            // 允许更新已绑定的配置（用户明确操作）
            if (finalAuthMode != null && !finalAuthMode.isEmpty()) {
                entity.setAuthMode(finalAuthMode);
            }
            if (finalAuthToken != null && !finalAuthToken.isEmpty()) {
                entity.setAuthToken(credentialEncryptor.encrypt(finalAuthToken));
            }
            if (finalBaseUrl != null && !finalBaseUrl.isEmpty()) {
                entity.setBaseUrl(finalBaseUrl);
            }
            if (entity.getAuthBoundAt() == null) {
                entity.setAuthBoundAt(LocalDateTime.now());
            }
            configRepository.save(entity);
            bound++;
        }
        log.info("Batch auth bound: {} sessions, mode={}", bound, authMode);
        return bound;
    }

    /**
     * 从 Worker 配置自动绑定 Auth（仅当尚未绑定时）
     */
    @Transactional
    public void bindAuthFromWorker(String sessionId, String workerId, String userId,
                                    Map<String, Object> workerAuthConfig) {
        ConversationConfigEntity entity = getOrCreate(sessionId, workerId, userId);
        if (entity.getAuthBoundAt() != null) {
            return; // already bound
        }
        String authMode = (String) workerAuthConfig.getOrDefault("auth_mode", "SUBSCRIPTION");
        String apiKey = (String) workerAuthConfig.get("api_key");
        String authToken = (String) workerAuthConfig.get("auth_token");
        String baseUrl = (String) workerAuthConfig.get("base_url");

        entity.setAuthMode(authMode);
        // Encrypt whichever token is present
        String tokenToStore = apiKey != null ? apiKey : authToken;
        if (tokenToStore != null) {
            entity.setAuthToken(credentialEncryptor.encrypt(tokenToStore));
        }
        entity.setBaseUrl(baseUrl);
        entity.setAuthBoundAt(LocalDateTime.now());
        configRepository.save(entity);
        log.info("Auth auto-bound from worker for session {}: mode={}", sessionId, authMode);
    }

    /**
     * 从 WorkingDirectory 默认配置自动绑定 Auth（仅当尚未绑定时）
     */
    @Transactional
    public void bindAuthFromDirectory(String sessionId, String workerId, String userId,
                                       String authMode, String plainToken, String baseUrl) {
        ConversationConfigEntity entity = getOrCreate(sessionId, workerId, userId);
        if (entity.getAuthBoundAt() != null) {
            return; // already bound, don't override
        }
        entity.setAuthMode(authMode);
        if (plainToken != null && !plainToken.isEmpty()) {
            entity.setAuthToken(credentialEncryptor.encrypt(plainToken));
        }
        entity.setBaseUrl(baseUrl);
        entity.setAuthBoundAt(LocalDateTime.now());
        configRepository.save(entity);
        log.info("Auth auto-bound from directory for session {}: mode={}", sessionId, authMode);
    }

    /**
     * 更新会话标签
     */
    @Transactional
    public ConversationConfigDTO updateTags(String sessionId, String userId, List<String> tags) {
        ConversationConfigEntity entity = getOrCreateBySessionId(sessionId, userId);
        if (!entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Access denied");
        }
        try {
            entity.setTags(tags != null && !tags.isEmpty()
                    ? OBJECT_MAPPER.writeValueAsString(tags)
                    : null);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid tags format");
        }
        configRepository.save(entity);
        return toDTO(entity);
    }

    /**
     * 批量获取配置（供前端合并）
     */
    public List<ConversationConfigDTO> listBySessionIds(List<String> sessionIds) {
        return configRepository.findBySessionIdIn(sessionIds).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 更新已绑定的 Auth 配置（用户明确操作，允许覆盖）
     */
    @Transactional
    public ConversationConfigDTO updateAuth(String sessionId, String userId,
                                           String authMode, String authToken, String baseUrl) {
        ConversationConfigEntity entity = getOrCreateBySessionId(sessionId, userId);
        if (!entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Access denied");
        }
        // 允许更新已绑定的配置（用户明确操作）
        if (authMode != null && !authMode.isEmpty()) {
            entity.setAuthMode(authMode);
        }
        if (authToken != null && !authToken.isEmpty()) {
            entity.setAuthToken(credentialEncryptor.encrypt(authToken));
        }
        if (baseUrl != null && !baseUrl.isEmpty()) {
            entity.setBaseUrl(baseUrl);
        }
        if (entity.getAuthBoundAt() == null) {
            entity.setAuthBoundAt(LocalDateTime.now());
        }
        configRepository.save(entity);
        log.info("Auth updated for session {}: mode={}", sessionId, authMode);
        return toDTO(entity);
    }

    /**
     * 查询指定状态的 sessionIds
     */
    public List<String> findSessionIdsByInteractionState(String userId, String state) {
        return configRepository.findSessionIdsByInteractionState(userId, state);
    }

    /**
     * 查询多个状态的 sessionIds（多选筛选）
     */
    public List<String> findSessionIdsByInteractionStates(String userId, List<String> states) {
        return configRepository.findSessionIdsByInteractionStateIn(userId, states);
    }

    /**
     * 更新会话交互状态
     */
    @Transactional
    public void updateInteractionState(String sessionId, String state) {
        configRepository.findBySessionId(sessionId).ifPresent(entity -> {
            entity.setInteractionState(state);
            configRepository.save(entity);
        });
    }

    /**
     * 归档会话
     */
    @Transactional
    public ConversationConfigDTO archiveConversation(String sessionId, String userId) {
        ConversationConfigEntity entity = getOrCreateBySessionId(sessionId, userId);
        if (!entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Access denied");
        }
        entity.setInteractionState("ARCHIVED");
        configRepository.save(entity);
        return toDTO(entity);
    }

    /**
     * 取消归档
     */
    @Transactional
    public ConversationConfigDTO unarchiveConversation(String sessionId, String userId) {
        ConversationConfigEntity entity = getOrCreateBySessionId(sessionId, userId);
        if (!entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Access denied");
        }
        entity.setInteractionState("AWAITING_REPLY");
        configRepository.save(entity);
        return toDTO(entity);
    }

    /**
     * 获取实体（内部使用，用于解密 token）
     */
    public ConversationConfigEntity getConfigEntity(String sessionId) {
        return configRepository.findBySessionId(sessionId).orElse(null);
    }

    /**
     * 解密 auth token
     */
    public String getDecryptedToken(ConversationConfigEntity entity) {
        if (entity.getAuthToken() == null) return null;
        return credentialEncryptor.decrypt(entity.getAuthToken());
    }

    /**
     * 批量获取配置 Map（sessionId -> entity）
     */
    public Map<String, ConversationConfigEntity> getConfigMapBySessionIds(List<String> sessionIds) {
        return configRepository.findBySessionIdIn(sessionIds).stream()
                .collect(Collectors.toMap(ConversationConfigEntity::getSessionId, e -> e));
    }

    private ConversationConfigDTO toDTO(ConversationConfigEntity entity) {
        String masked = null;
        if (entity.getAuthToken() != null && entity.getAuthBoundAt() != null) {
            try {
                String plain = credentialEncryptor.decrypt(entity.getAuthToken());
                masked = maskToken(plain);
            } catch (Exception e) {
                masked = "***";
            }
        }
        List<String> tagList = parseTags(entity.getTags());
        return ConversationConfigDTO.builder()
                .sessionId(entity.getSessionId())
                .pinned(Boolean.TRUE.equals(entity.getPinned()))
                .pinnedAt(entity.getPinnedAt())
                .customTitle(entity.getCustomTitle())
                .authMode(entity.getAuthMode())
                .authBound(entity.getAuthBoundAt() != null)
                .baseUrl(entity.getBaseUrl())
                .maskedAuthToken(masked)
                .tags(tagList)
                .interactionState(entity.getInteractionState())
                .build();
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) return Collections.emptyList();
        try {
            return OBJECT_MAPPER.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tags JSON: {}", tagsJson);
            return Collections.emptyList();
        }
    }

    /**
     * 脱敏 token：保留前6位和后4位，中间用 **** 替代
     */
    private String maskToken(String token) {
        if (token == null || token.isEmpty()) return null;
        if (token.length() <= 10) {
            return token.substring(0, 2) + "****" + token.substring(token.length() - 2);
        }
        return token.substring(0, 6) + "****" + token.substring(token.length() - 4);
    }
}
