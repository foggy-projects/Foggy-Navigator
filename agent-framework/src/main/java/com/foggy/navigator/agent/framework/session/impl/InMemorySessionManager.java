package com.foggy.navigator.agent.framework.session.impl;

import com.foggy.navigator.agent.framework.session.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存实现的会话管理器
 * 通过 AgentFrameworkAutoConfiguration 条件注册（@ConditionalOnMissingBean）
 */
public class InMemorySessionManager implements SessionManager {

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Message>> messages = new ConcurrentHashMap<>();

    @Override
    public String createSession(SessionCreateRequest request) {
        String sessionId = UUID.randomUUID().toString();
        Session session = Session.builder()
                .id(sessionId)
                .userId(request.getUserId())
                .tenantId(request.getTenantId())
                .agentId(request.getAgentId())
                .parentSessionId(request.getParentSessionId())
                .taskName(request.getTaskName())
                .status(SessionStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        sessions.put(sessionId, session);
        messages.put(sessionId, new ArrayList<>());
        return sessionId;
    }

    @Override
    public Session getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    @Override
    public void updateStatus(String sessionId, SessionStatus status) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            session.setStatus(status);
            session.setUpdatedAt(LocalDateTime.now());
        }
    }

    @Override
    public String addMessage(String sessionId, Message message) {
        List<Message> sessionMessages = messages.get(sessionId);
        if (sessionMessages != null) {
            if (message.getId() == null) {
                message.setId(UUID.randomUUID().toString());
            }
            if (message.getCreatedAt() == null) {
                message.setCreatedAt(LocalDateTime.now());
            }
            message.setSessionId(sessionId);
            sessionMessages.add(message);

            // 更新会话时间
            Session session = sessions.get(sessionId);
            if (session != null) {
                session.setUpdatedAt(LocalDateTime.now());
            }
            return message.getId();
        }
        return null;
    }

    @Override
    public List<Message> getRecentMessages(String sessionId, int limit) {
        List<Message> sessionMessages = messages.get(sessionId);
        if (sessionMessages == null || sessionMessages.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, sessionMessages.size() - limit);
        return new ArrayList<>(sessionMessages.subList(start, sessionMessages.size()));
    }

    @Override
    public List<Message> getAllMessages(String sessionId) {
        List<Message> sessionMessages = messages.get(sessionId);
        return sessionMessages != null ? new ArrayList<>(sessionMessages) : List.of();
    }

    @Override
    public void closeSession(String sessionId) {
        updateStatus(sessionId, SessionStatus.COMPLETED);
    }

    @Override
    public List<Session> findPendingByUser(String userId) {
        return sessions.values().stream()
                .filter(s -> userId.equals(s.getUserId()))
                .filter(s -> s.getStatus() == SessionStatus.ACTIVE
                        || s.getStatus() == SessionStatus.PAUSED)
                .collect(Collectors.toList());
    }

    @Override
    public List<Session> findByUser(String userId) {
        return sessions.values().stream()
                .filter(s -> userId.equals(s.getUserId()))
                .collect(Collectors.toList());
    }
}
