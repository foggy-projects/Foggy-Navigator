package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionListDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionMessageDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionMessagesDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentSessionEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import com.foggy.navigator.business.agent.repository.BusinessAgentSessionMessageRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentSessionRepository;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BusinessAgentSessionService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    private static final String CONTEXT_ID_FORMAT = "bctx_yyyyMMdd_<hash>_<id>";
    private static final Pattern CONTEXT_ID_PATTERN =
            Pattern.compile("^bctx_(\\d{8})_[0-9a-f]{2}_[A-Za-z0-9._-]+$");
    private static final DateTimeFormatter CONTEXT_ID_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final BusinessAgentSessionRepository sessionRepository;
    private final BusinessAgentSessionMessageRepository messageRepository;
    private final ClientAppUserGrantService userGrantService;

    @Transactional
    public BusinessAgentSessionDTO bindTask(
            BusinessAgentTaskEntity task,
            String requestedContextId,
            String clientContextJson) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        return bindSession(
                task.getTenantId(),
                task.getClientAppId(),
                task.getUpstreamUserId(),
                task.getUpstreamUserId(),
                requestedContextId,
                task.getSessionId(),
                task.getSkillId(),
                task.getSkillId(),
                task.getTaskId(),
                clientContextJson);
    }

    @Transactional
    public BusinessAgentSessionDTO bindOpenApiSession(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String contextId,
            String sessionId,
            String skillId,
            String taskId,
            String clientContextJson) {
        return bindSession(
                tenantId,
                clientAppId,
                upstreamUserId,
                upstreamUserId,
                contextId,
                sessionId,
                skillId,
                skillId,
                taskId,
                clientContextJson);
    }

    @Transactional(readOnly = true)
    public BusinessAgentSessionDTO getSession(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String contextId) {
        validateGrant(tenantId, clientAppId, upstreamUserId);
        BusinessAgentSessionEntity entity = findSession(tenantId, clientAppId, upstreamUserId, contextId);
        return BusinessAgentSessionDTO.fromEntity(entity);
    }

    @Transactional(readOnly = true)
    public BusinessAgentSessionListDTO listSessions(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String cursor,
            int limit) {
        validateGrant(tenantId, clientAppId, upstreamUserId);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        Pageable pageable = PageRequest.of(0, safeLimit + 1);
        List<BusinessAgentSessionEntity> sessions;
        String normalizedCursor = normalizeContextId(cursor);
        if (normalizedCursor != null) {
            LocalDateTime cursorTime = sessionRepository
                    .findByTenantIdAndClientAppIdAndUpstreamUserIdAndContextId(
                            tenantId, clientAppId, upstreamUserId, normalizedCursor)
                    .map(BusinessAgentSessionEntity::getLastAccessedAt)
                    .orElse(null);
            sessions = cursorTime == null
                    ? sessionRepository.findByTenantIdAndClientAppIdAndUpstreamUserIdOrderByLastAccessedAtDesc(
                            tenantId, clientAppId, upstreamUserId, pageable)
                    : sessionRepository.findByTenantIdAndClientAppIdAndUpstreamUserIdAndLastAccessedAtBeforeOrderByLastAccessedAtDesc(
                            tenantId, clientAppId, upstreamUserId, cursorTime, pageable);
        } else {
            sessions = sessionRepository.findByTenantIdAndClientAppIdAndUpstreamUserIdOrderByLastAccessedAtDesc(
                    tenantId, clientAppId, upstreamUserId, pageable);
        }
        boolean hasMore = sessions.size() > safeLimit;
        List<BusinessAgentSessionEntity> page = hasMore ? sessions.subList(0, safeLimit) : sessions;
        Map<String, String> firstUserMessageMap = batchFindFirstUserMessageContents(
                page.stream()
                        .map(BusinessAgentSessionEntity::getSessionId)
                        .toList());

        BusinessAgentSessionListDTO dto = new BusinessAgentSessionListDTO();
        dto.setSessions(page.stream()
                .map(session -> toSessionDTO(session, firstUserMessageMap))
                .toList());
        dto.setNextCursor(page.isEmpty() ? null : page.get(page.size() - 1).getContextId());
        dto.setHasMore(hasMore);
        return dto;
    }

    @Transactional(readOnly = true)
    public BusinessAgentSessionMessagesDTO getMessages(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String contextId,
            String cursor,
            int limit) {
        validateGrant(tenantId, clientAppId, upstreamUserId);
        BusinessAgentSessionEntity session = findSession(tenantId, clientAppId, upstreamUserId, contextId);

        int safeLimit = Math.min(Math.max(limit, 1), 200);
        Pageable pageable = PageRequest.of(0, safeLimit + 1);
        List<SessionMessageEntity> messages;
        if (StringUtils.hasText(cursor)) {
            LocalDateTime cursorTime = messageRepository.findById(cursor)
                    .filter(message -> session.getSessionId().equals(message.getSessionId()))
                    .map(SessionMessageEntity::getCreatedAt)
                    .orElse(null);
            messages = cursorTime == null
                    ? messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getSessionId(), pageable)
                    : messageRepository.findBySessionIdAfterTime(session.getSessionId(), cursorTime, pageable);
        } else {
            messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getSessionId(), pageable);
        }

        boolean hasMore = messages.size() > safeLimit;
        List<SessionMessageEntity> page = hasMore ? messages.subList(0, safeLimit) : messages;

        BusinessAgentSessionMessagesDTO dto = new BusinessAgentSessionMessagesDTO();
        dto.setContextId(session.getContextId());
        dto.setSessionId(session.getSessionId());
        dto.setMessages(page.stream()
                .map(message -> BusinessAgentSessionMessageDTO.fromEntity(message, session.getContextId()))
                .toList());
        dto.setNextCursor(page.isEmpty() ? cursor : page.get(page.size() - 1).getId());
        dto.setHasMore(hasMore);
        return dto;
    }

    private BusinessAgentSessionEntity findSession(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String contextId) {
        String normalizedContextId = requireContextId(contextId);
        return sessionRepository.findByTenantIdAndClientAppIdAndUpstreamUserIdAndContextId(
                        tenantId, clientAppId, upstreamUserId, normalizedContextId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "business agent session not found: " + normalizedContextId));
    }

    private BusinessAgentSessionDTO bindSession(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String accountId,
            String requestedContextId,
            String sessionId,
            String skillId,
            String agentId,
            String taskId,
            String clientContextJson) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(upstreamUserId, "upstreamUserId is required");
        requireText(sessionId, "sessionId is required");

        String normalizedContextId = normalizeContextId(requestedContextId);
        BusinessAgentSessionEntity entity = sessionRepository
                .findByTenantIdAndClientAppIdAndUpstreamUserIdAndSessionId(
                        tenantId, clientAppId, upstreamUserId, sessionId)
                .orElse(null);

        if (entity != null && normalizedContextId != null
                && !normalizedContextId.equals(entity.getContextId())) {
            throw new IllegalArgumentException("business agent session context mismatch");
        }
        if (entity == null && normalizedContextId != null) {
            entity = sessionRepository.findByTenantIdAndClientAppIdAndUpstreamUserIdAndContextId(
                            tenantId, clientAppId, upstreamUserId, normalizedContextId)
                    .orElse(null);
            if (entity != null && !sessionId.equals(entity.getSessionId())) {
                throw new IllegalArgumentException("business agent session context mismatch");
            }
        }
        if (entity == null) {
            entity = new BusinessAgentSessionEntity();
            entity.setTenantId(tenantId);
            entity.setClientAppId(clientAppId);
            entity.setUpstreamUserId(upstreamUserId);
            entity.setSessionId(sessionId);
            entity.setContextId(normalizedContextId != null ? normalizedContextId : generateContextId());
        }

        entity.setAccountId(StringUtils.hasText(accountId) ? accountId : upstreamUserId);
        entity.setSkillId(skillId);
        entity.setAgentId(agentId);
        entity.setLatestTaskId(taskId);
        entity.setStatus(STATUS_ACTIVE);
        if (clientContextJson != null) {
            entity.setClientContextJson(clientContextJson);
        }
        entity.setLastAccessedAt(LocalDateTime.now());
        return BusinessAgentSessionDTO.fromEntity(sessionRepository.save(entity));
    }

    private BusinessAgentSessionDTO toSessionDTO(
            BusinessAgentSessionEntity entity,
            Map<String, String> firstUserMessageMap) {
        BusinessAgentSessionDTO dto = BusinessAgentSessionDTO.fromEntity(entity);
        if (dto != null && entity != null) {
            dto.setTitle(truncate(firstUserMessageMap.get(entity.getSessionId()), 120));
        }
        return dto;
    }

    private Map<String, String> batchFindFirstUserMessageContents(Collection<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        List<SessionMessageEntity> messages = messageRepository
                .findBySessionIdInAndRoleOrderBySessionIdAscCreatedAtAsc(sessionIds, "USER");
        return messages.stream()
                .filter(message -> StringUtils.hasText(message.getContent()))
                .collect(Collectors.toMap(
                        SessionMessageEntity::getSessionId,
                        SessionMessageEntity::getContent,
                        (first, second) -> first
                ));
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private void validateGrant(String tenantId, String clientAppId, String upstreamUserId) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(upstreamUserId, "upstreamUserId is required");
        userGrantService.checkUpstreamUserAccess(tenantId, clientAppId, upstreamUserId);
    }

    private String generateContextId() {
        String entropy = UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
        String shard = entropy.substring(0, 2);
        return "bctx_" + LocalDate.now().format(CONTEXT_ID_DATE_FORMATTER) + "_" + shard + "_" + entropy;
    }

    private String requireContextId(String contextId) {
        String normalized = normalizeContextId(contextId);
        if (normalized == null) {
            throw new IllegalArgumentException("contextId is required");
        }
        return normalized;
    }

    private String normalizeContextId(String contextId) {
        if (!StringUtils.hasText(contextId)) {
            return null;
        }
        String normalized = contextId.trim();
        Matcher matcher = CONTEXT_ID_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("contextId must match " + CONTEXT_ID_FORMAT);
        }
        try {
            LocalDate.parse(matcher.group(1), CONTEXT_ID_DATE_FORMATTER);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("contextId must match " + CONTEXT_ID_FORMAT);
        }
        return normalized;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
