package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.model.dto.ConversationConfigDTO;
import com.foggy.navigator.claude.worker.model.entity.ConversationConfigEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.repository.ConversationConfigRepository;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话级配置服务（置顶、自定义标题、Auth 绑定、模型映射）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationConfigService {

    private final ConversationConfigRepository configRepository;
    private final ClaudeTaskRepository taskRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final ObjectMapper objectMapper;

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
                                           String authMode, String authToken, String baseUrl,
                                           String haikuModelName, String sonnetModelName, String opusModelName) {
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
        
        // 绑定模型映射
        if (haikuModelName != null || sonnetModelName != null || opusModelName != null) {
            entity.setHaikuModelName(haikuModelName);
            entity.setSonnetModelName(sonnetModelName);
            entity.setOpusModelName(opusModelName);
        }
        
        configRepository.save(entity);
        log.info("Auth bound for session {}: mode={}", sessionId, authMode);
        return toDTO(entity);
    }

    /**
     * 批量绑定 Auth（按 sessionId 列表）
     */
    @Transactional
    public int batchBindAuth(List<String> sessionIds, String userId,
                              String authMode, String authToken, String baseUrl,
                              boolean skipExisting,
                              String haikuModelName, String sonnetModelName, String opusModelName) {
        int bound = 0;
        for (String sessionId : sessionIds) {
            ConversationConfigEntity entity = getOrCreateBySessionId(sessionId, userId);
            if (!entity.getUserId().equals(userId)) continue;
            if (skipExisting && entity.getAuthBoundAt() != null) continue;
            if (entity.getAuthBoundAt() != null) continue; // immutable

            entity.setAuthMode(authMode);
            if (authToken != null && !authToken.isEmpty()) {
                entity.setAuthToken(credentialEncryptor.encrypt(authToken));
            }
            entity.setBaseUrl(baseUrl);
            entity.setAuthBoundAt(LocalDateTime.now());
            
            // 绑定模型映射
            if (haikuModelName != null || sonnetModelName != null || opusModelName != null) {
                entity.setHaikuModelName(haikuModelName);
                entity.setSonnetModelName(sonnetModelName);
                entity.setOpusModelName(opusModelName);
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
     * 批量获取配置（供前端合并）
     */
    public List<ConversationConfigDTO> listBySessionIds(List<String> sessionIds) {
        return configRepository.findBySessionIdIn(sessionIds).stream()
                .map(this::toDTO)
                .toList();
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
        return ConversationConfigDTO.builder()
                .sessionId(entity.getSessionId())
                .pinned(Boolean.TRUE.equals(entity.getPinned()))
                .pinnedAt(entity.getPinnedAt())
                .customTitle(entity.getCustomTitle())
                .authMode(entity.getAuthMode())
                .authBound(entity.getAuthBoundAt() != null)
                .baseUrl(entity.getBaseUrl())
                .maskedAuthToken(masked)
                .build();
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

    /**
     * 绑定模型映射到会话（仅在会话创建时绑定，之后除非主动修改否则不变）
     */
    @Transactional
    public void bindModelMapping(String sessionId, String workerId, String userId,
                                   String haikuModelName, String sonnetModelName, String opusModelName) {
        ConversationConfigEntity entity = getOrCreate(sessionId, workerId, userId);
        
        // 如果已经绑定了模型映射，则不再更新（除非主动修改）
        if (entity.getHaikuModelName() != null || entity.getSonnetModelName() != null || entity.getOpusModelName() != null) {
            log.debug("Model mapping already exists for session {}, skipping", sessionId);
            return;
        }
        
        if ((haikuModelName == null || haikuModelName.isEmpty())
                && (sonnetModelName == null || sonnetModelName.isEmpty())
                && (opusModelName == null || opusModelName.isEmpty())) {
            log.debug("Model mapping is empty, skipping for session {}", sessionId);
            return;
        }

        entity.setHaikuModelName(haikuModelName);
        entity.setSonnetModelName(sonnetModelName);
        entity.setOpusModelName(opusModelName);
        configRepository.save(entity);
        log.info("Model mapping bound for session {}: haiku={}, sonnet={}, opus={}", 
                sessionId, haikuModelName, sonnetModelName, opusModelName);
    }

    /**
     * 获取会话的模型映射
     */
    public String[] getModelMapping(ConversationConfigEntity entity) {
        return new String[] {
            entity.getHaikuModelName(),
            entity.getSonnetModelName(),
            entity.getOpusModelName()
        };
    }
}
