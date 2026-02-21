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
     * 截断会话消息：删除第 N 个 USER 消息及之后的所有消息。
     * 用于会话回退场景。
     *
     * @param sessionId         会话ID
     * @param fromUserTurnIndex 从第几个 USER 消息开始删除（1-based）
     * @return 删除的消息数
     */
    default int truncateMessagesFromTurn(String sessionId, int fromUserTurnIndex) {
        // Default no-op for implementations that don't support truncation
        return 0;
    }

    /**
     * 查找用户的待办会话
     */
    List<Session> findPendingByUser(String userId);

    /**
     * 查找用户的所有会话
     */
    List<Session> findByUser(String userId);
}
