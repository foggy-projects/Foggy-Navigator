package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.dto.ConversationConfigDTO;
import com.foggy.navigator.claude.worker.model.entity.ConversationConfigEntity;
import com.foggy.navigator.claude.worker.repository.ConversationConfigRepository;
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
 * 会话级配置服务（置顶、自定义标题、Auth 绑定）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationConfigService {

    private final ConversationConfigRepository configRepository;
    private final CredentialEncryptor credentialEncryptor;

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
     * 切换置顶
     */
    @Transactional
    public ConversationConfigDTO updatePin(String sessionId, String userId, boolean pinned) {
        ConversationConfigEntity entity = configRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Config not found for session: " + sessionId));
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
        ConversationConfigEntity entity = configRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Config not found for session: " + sessionId));
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
        ConversationConfigEntity entity = configRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Config not found for session: " + sessionId));
        if (!entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Access denied");
        }
        if (entity.getAuthBoundAt() != null) {
            throw new IllegalStateException("Auth already bound for this conversation");
        }
        entity.setAuthMode(authMode);
        entity.setAuthToken(credentialEncryptor.encrypt(authToken));
        entity.setBaseUrl(baseUrl);
        entity.setAuthBoundAt(LocalDateTime.now());
        configRepository.save(entity);
        log.info("Auth bound for session {}: mode={}", sessionId, authMode);
        return toDTO(entity);
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
        return ConversationConfigDTO.builder()
                .sessionId(entity.getSessionId())
                .pinned(Boolean.TRUE.equals(entity.getPinned()))
                .pinnedAt(entity.getPinnedAt())
                .customTitle(entity.getCustomTitle())
                .authMode(entity.getAuthMode())
                .authBound(entity.getAuthBoundAt() != null)
                .baseUrl(entity.getBaseUrl())
                .build();
    }
}
