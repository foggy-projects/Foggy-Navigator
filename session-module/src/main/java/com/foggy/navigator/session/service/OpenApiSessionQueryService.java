package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.repository.AgentConversationContextRepository;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Open API 会话与消息查询服务
 * <p>
 * 为 Open API 提供 contextId 读模型和 taskId + cursor 增量消息查询能力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OpenApiSessionQueryService {

    private final AgentConversationContextRepository contextRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionTaskRepository taskRepository;

    // ── 1. 会话列表 ──

    /**
     * 按 userId + agentId 查询会话列表（cursor 分页）
     *
     * @param userId  用户 ID
     * @param agentId Agent ID
     * @param limit   每页条数
     * @return 会话上下文列表
     */
    public List<AgentConversationContextEntity> listSessions(String userId, String agentId, int limit) {
        return listSessions(userId, agentId, null, limit);
    }

    public List<AgentConversationContextEntity> listSessions(String userId, String agentId,
                                                             String cursor, int limit) {
        Pageable pageable = PageRequest.of(0, limit + 1); // 多取一条判 hasMore
        if (cursor == null || cursor.isBlank()) {
            return contextRepository.findByUserIdAndTargetAgentIdOrderByLastAccessedAtDesc(
                    userId, agentId, pageable);
        }
        LocalDateTime cursorTime = contextRepository.findByContextIdAndUserId(cursor, userId)
                .filter(ctx -> agentId.equals(ctx.getTargetAgentId()))
                .map(AgentConversationContextEntity::getLastAccessedAt)
                .orElse(null);
        if (cursorTime == null) {
            return contextRepository.findByUserIdAndTargetAgentIdOrderByLastAccessedAtDesc(
                    userId, agentId, pageable);
        }
        return contextRepository.findByUserIdAndTargetAgentIdAndLastAccessedAtBeforeOrderByLastAccessedAtDesc(
                userId, agentId, cursorTime, pageable);
    }

    @Transactional
    public void updateClientContextJson(String contextId, String userId, String agentId,
                                        String clientContextJson) {
        contextRepository.findByContextIdAndUserId(contextId, userId)
                .filter(ctx -> agentId.equals(ctx.getTargetAgentId()))
                .ifPresent(ctx -> {
                    ctx.setClientContextJson(clientContextJson);
                    contextRepository.save(ctx);
                });
    }

    // ── 2. contextId 读模型 ──

    /**
     * 按 contextId + agentId 查找会话上下文
     */
    public Optional<AgentConversationContextEntity> findContext(String contextId, String agentId) {
        return contextRepository.findByContextIdAndTargetAgentId(contextId, agentId);
    }

    /**
     * 按 contextId + userId 查找会话上下文（含 userId 校验）
     */
    public Optional<AgentConversationContextEntity> findContextForUser(
            String contextId, String userId) {
        return contextRepository.findByContextIdAndUserId(contextId, userId);
    }

    /**
     * 从 contextId 解析出内部 sessionId
     *
     * @return sessionId，如果 contextId 无映射或无 navigatorSessionId 则返回 empty
     */
    public Optional<String> resolveSessionId(String contextId, String userId) {
        return contextRepository.findByContextIdAndUserId(contextId, userId)
                .map(AgentConversationContextEntity::getNavigatorSessionId)
                .filter(s -> s != null && !s.isBlank());
    }

    // ── 3. 会话消息列表（按 sessionId） ──

    /**
     * 按 sessionId 查询消息（cursor 分页，升序）
     * <p>
     * cursor 是上一页最后一条消息的 ID，服务端解析其 createdAt 作为增量起点。
     */
    public List<SessionMessageEntity> getSessionMessages(String sessionId, String cursor, int limit) {
        Pageable pageable = PageRequest.of(0, limit + 1);
        if (cursor == null || cursor.isBlank()) {
            return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId, pageable);
        }
        java.time.LocalDateTime afterTime = resolveCursorTime(cursor);
        if (afterTime == null) {
            return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId, pageable);
        }
        return messageRepository.findBySessionIdAfterTime(sessionId, afterTime, pageable);
    }

    // ── 4. 任务增量消息（按 taskId） ──

    /**
     * 按 taskId 查询增量消息（cursor 分页，升序）
     * <p>
     * cursor 是上一页最后一条消息的 ID，服务端解析其 createdAt 作为增量起点。
     */
    public List<SessionMessageEntity> getTaskMessages(String taskId, String cursor, int limit) {
        Pageable pageable = PageRequest.of(0, limit + 1);
        if (cursor == null || cursor.isBlank()) {
            return messageRepository.findByTaskIdOrderByCreatedAtAsc(taskId, pageable);
        }
        java.time.LocalDateTime afterTime = resolveCursorTime(cursor);
        if (afterTime == null) {
            return messageRepository.findByTaskIdOrderByCreatedAtAsc(taskId, pageable);
        }
        return messageRepository.findByTaskIdAfterTime(taskId, afterTime, pageable);
    }

    /**
     * 从 cursor（消息 ID）解析 createdAt 时间戳
     */
    private java.time.LocalDateTime resolveCursorTime(String cursorMessageId) {
        return messageRepository.findById(cursorMessageId)
                .map(SessionMessageEntity::getCreatedAt)
                .orElse(null);
    }

    /**
     * 从 sessionId 反查 contextId
     */
    public Optional<String> resolveContextId(String sessionId) {
        return contextRepository.findByNavigatorSessionId(sessionId)
                .map(AgentConversationContextEntity::getContextId);
    }

    // ── 5. 任务查询 ──

    /**
     * 按 taskId 查找任务
     */
    public Optional<SessionTaskEntity> findTask(String taskId) {
        return taskRepository.findByTaskId(taskId);
    }

    /**
     * 查找某个会话最近的任务（用于会话摘要中的 latestTaskId）
     */
    public Optional<SessionTaskEntity> findLatestTaskBySessionId(String sessionId) {
        List<SessionTaskEntity> tasks = taskRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
        return tasks.isEmpty() ? Optional.empty() : Optional.of(tasks.get(0));
    }

    /**
     * 批量查找每个 sessionId 的最新任务 ID（消除 N+1）
     *
     * @return sessionId → latestTaskId 映射
     */
    public Map<String, String> batchFindLatestTaskIds(Collection<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        // 一次查出所有相关任务（按 createdAt 降序），然后在内存中取每个 session 的第一个
        List<SessionTaskEntity> tasks = taskRepository.findBySessionIdInOrderByCreatedAtDesc(sessionIds);
        return tasks.stream()
                .collect(Collectors.toMap(
                        SessionTaskEntity::getSessionId,
                        SessionTaskEntity::getTaskId,
                        (first, second) -> first  // 已按降序排，取第一个即最新
                ));
    }

    /**
     * 批量查找每个 sessionId 的首条用户消息，用作无显式别名时的默认会话标题。
     *
     * @return sessionId → first USER message content
     */
    public Map<String, String> batchFindFirstUserMessageContents(Collection<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        List<SessionMessageEntity> messages = messageRepository
                .findBySessionIdInAndRoleOrderBySessionIdAscCreatedAtAsc(sessionIds, "USER");
        return messages.stream()
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .collect(Collectors.toMap(
                        SessionMessageEntity::getSessionId,
                        SessionMessageEntity::getContent,
                        (first, second) -> first
                ));
    }
}
