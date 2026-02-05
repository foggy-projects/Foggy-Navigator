package com.foggy.navigator.agent.framework.session;

import java.util.List;

/**
 * 会话管理器
 */
public interface SessionManager {

    /**
     * 创建新会话
     */
    String createSession(SessionCreateRequest request);

    /**
     * 获取会话
     */
    Session getSession(String sessionId);

    /**
     * 更新会话状态
     */
    void updateStatus(String sessionId, SessionStatus status);

    /**
     * 添加消息
     */
    String addMessage(String sessionId, Message message);

    /**
     * 获取会话的最近N条消息
     */
    List<Message> getRecentMessages(String sessionId, int limit);

    /**
     * 获取会话的所有消息
     */
    List<Message> getAllMessages(String sessionId);

    /**
     * 结束会话
     */
    void closeSession(String sessionId);

    /**
     * 删除会话及其所有消息
     */
    void deleteSession(String sessionId);

    /**
     * 查找用户的待办会话
     */
    List<Session> findPendingByUser(String userId);

    /**
     * 查找用户的所有会话
     */
    List<Session> findByUser(String userId);
}
