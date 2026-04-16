package com.foggy.navigator.session.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.session.*;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JPA持久化的会话管理器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JpaSessionManager implements SessionManager {

    private static final Set<String> KNOWN_PROVIDER_TYPES = Set.of("claude-worker", "codex-worker");

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    @Nullable
    private final AgentContextStore contextStore;

    @Override
    @Transactional
    public String createSession(SessionCreateRequest request) {
        String sessionId = UUID.randomUUID().toString();
        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId(request.getUserId());
        entity.setTenantId(request.getTenantId());
        entity.setAgentId(request.getAgentId());
        String providerType = resolveProviderType(request);
        if (providerType != null) {
            entity.setProviderType(providerType);
            entity.setBindingSource("EXPLICIT_AGENT");
        }
        entity.setParentSessionId(request.getParentSessionId());
        entity.setTitle(request.getTaskName());
        entity.setStatus(SessionStatus.ACTIVE.name());
        entity.setInteractionState("PROCESSING");
        entity.setLastActivityAt(LocalDateTime.now());
        sessionRepository.save(entity);
        return sessionId;
    }

    private String resolveProviderType(SessionCreateRequest request) {
        if (request.getProviderType() != null && !request.getProviderType().isBlank()) {
            return request.getProviderType();
        }
        if (request.getAgentId() != null && KNOWN_PROVIDER_TYPES.contains(request.getAgentId())) {
            return request.getAgentId();
        }
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public Session getSession(String sessionId) {
        return sessionRepository.findById(sessionId)
                .map(this::toSession)
                .orElse(null);
    }

    @Override
    @Transactional
    public void updateStatus(String sessionId, SessionStatus status) {
        sessionRepository.findById(sessionId).ifPresent(entity -> {
            entity.setStatus(status.name());
            sessionRepository.save(entity);
        });
    }

    @Override
    @Transactional
    public String addMessage(String sessionId, Message message) {
        String messageId = message.getId() != null ? message.getId() : UUID.randomUUID().toString();
        LocalDateTime createdAt = resolveMessageCreatedAt(sessionId, message.getCreatedAt());
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId(messageId);
        entity.setSessionId(sessionId);
        entity.setTaskId(message.getTaskId());
        entity.setRole(message.getRole().name());
        entity.setContent(message.getContent());
        entity.setMetadata(serializeMetadata(message.getMetadata()));
        entity.setCreatedAt(createdAt);
        messageRepository.save(entity);

        // 更新会话时间
        sessionRepository.findById(sessionId).ifPresent(session -> {
            LocalDateTime now = LocalDateTime.now();
            session.setUpdatedAt(now);
            session.setLastActivityAt(now);
            sessionRepository.save(session);
        });

        return messageId;
    }

    private LocalDateTime resolveMessageCreatedAt(String sessionId, LocalDateTime requestedCreatedAt) {
        LocalDateTime candidate = requestedCreatedAt != null ? requestedCreatedAt : LocalDateTime.now();
        LocalDateTime latestCreatedAt = messageRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId)
                .map(SessionMessageEntity::getCreatedAt)
                .orElse(null);
        if (latestCreatedAt != null && !candidate.isAfter(latestCreatedAt)) {
            return latestCreatedAt.plusNanos(1);
        }
        return candidate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> getRecentMessages(String sessionId, int limit) {
        List<SessionMessageEntity> entities = messageRepository
                .findTop50BySessionIdOrderByCreatedAtDesc(sessionId);
        // Take only 'limit' items and reverse to chronological order
        List<SessionMessageEntity> limited = entities.stream()
                .limit(limit)
                .collect(Collectors.toList());
        Collections.reverse(limited);
        return limited.stream().map(this::toMessage).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> getAllMessages(String sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                .map(this::toMessage)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> getLatestMessages(String sessionId, int limit, int offset) {
        // Query (offset + limit) messages in DESC order, then skip offset, take limit
        Pageable pageable = PageRequest.of(0, offset + limit);
        List<SessionMessageEntity> descEntities =
                messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);

        // Skip 'offset' items from the DESC result (these are the newest, already loaded)
        List<SessionMessageEntity> slice = descEntities.stream()
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());

        // Reverse to chronological order (ASC)
        Collections.reverse(slice);
        return slice.stream().map(this::toMessage).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long countMessages(String sessionId) {
        return messageRepository.countBySessionId(sessionId);
    }

    @Override
    @Transactional
    public void closeSession(String sessionId) {
        updateStatus(sessionId, SessionStatus.COMPLETED);
    }

    @Override
    @Transactional
    public void deleteSession(String sessionId) {
        if (contextStore != null) {
            contextStore.deleteByNavigatorSessionId(sessionId);
        }
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
        log.info("Session deleted: sessionId={}", sessionId);
    }

    @Override
    @Transactional
    public int truncateMessagesFromTurn(String sessionId, int fromUserTurnIndex) {
        List<SessionMessageEntity> all = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int userTurn = 0;
        List<String> toDelete = new java.util.ArrayList<>();
        boolean cutting = false;

        for (SessionMessageEntity msg : all) {
            if (cutting) {
                toDelete.add(msg.getId());
                continue;
            }
            if ("USER".equals(msg.getRole())) {
                userTurn++;
                if (userTurn >= fromUserTurnIndex) {
                    cutting = true;
                    toDelete.add(msg.getId());
                }
            }
        }

        if (!toDelete.isEmpty()) {
            messageRepository.deleteAllById(toDelete);
            log.info("Truncated session {} from user turn {}: deleted {} messages",
                    sessionId, fromUserTurnIndex, toDelete.size());
        }
        return toDelete.size();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findSessionIdsWithoutSummary(List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        return sessionRepository.findAllById(sessionIds).stream()
                .filter(e -> e.getSummary() == null || e.getSummary().isBlank())
                .map(SessionEntity::getId)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> getFirstAndRecentMessages(String sessionId, int recentCount) {
        List<SessionMessageEntity> all = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (all.isEmpty()) {
            return List.of();
        }
        // First message
        SessionMessageEntity first = all.get(0);
        // Recent N messages (from the end)
        int startIndex = Math.max(1, all.size() - recentCount);
        List<Message> result = new ArrayList<>();
        result.add(toMessage(first));
        for (int i = startIndex; i < all.size(); i++) {
            if (i == 0) continue; // skip first, already added
            result.add(toMessage(all.get(i)));
        }
        return result;
    }

    @Override
    @Transactional
    public void updateSessionSummary(String sessionId, String summary) {
        sessionRepository.findById(sessionId).ifPresent(entity -> {
            entity.setSummary(summary);
            entity.setLastActivityAt(LocalDateTime.now());
            sessionRepository.save(entity);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<Session> findPendingByUser(String userId) {
        List<String> statuses = List.of(SessionStatus.ACTIVE.name(), SessionStatus.PAUSED.name());
        return sessionRepository.findByUserIdAndStatusInOrderByUpdatedAtDesc(userId, statuses)
                .stream()
                .map(this::toSession)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Session> findByUser(String userId) {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream()
                .map(this::toSession)
                .collect(Collectors.toList());
    }

    // ===== Entity ↔ POJO 转换 =====

    private Session toSession(SessionEntity entity) {
        return Session.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .tenantId(entity.getTenantId())
                .agentId(entity.getAgentId())
                .parentSessionId(entity.getParentSessionId())
                .status(SessionStatus.valueOf(entity.getStatus()))
                .taskName(entity.getTitle())
                .summary(entity.getSummary())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private Message toMessage(SessionMessageEntity entity) {
        return Message.builder()
                .id(entity.getId())
                .sessionId(entity.getSessionId())
                .role(MessageRole.valueOf(entity.getRole()))
                .content(entity.getContent())
                .metadata(deserializeMetadata(entity.getMetadata()))
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata", e);
            return null;
        }
    }

    private Map<String, Object> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize metadata: {}", json, e);
            return null;
        }
    }
}
