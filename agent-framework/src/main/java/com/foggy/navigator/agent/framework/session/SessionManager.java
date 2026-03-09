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
     * 从给定的 sessionId 列表中筛选出 summary 为空的会话ID
     */
    default List<String> findSessionIdsWithoutSummary(List<String> sessionIds) {
        return List.of();
    }

    /**
     * 获取会话的首条消息 + 最近 N 条消息（去重）
     */
    default List<Message> getFirstAndRecentMessages(String sessionId, int recentCount) {
        return getRecentMessages(sessionId, recentCount);
    }

    /**
     * 更新会话摘要
     */
    default void updateSessionSummary(String sessionId, String summary) {
        // no-op by default
    }

    /**
     * 获取会话的最新 N 条消息（分页加载，从尾部开始）。
     * 返回的消息按时间正序排列（ASC）。
     *
     * @param sessionId 会话ID
     * @param limit     每页条数
     * @param offset    从尾部偏移量（0=最新的 limit 条）
     * @return 按时间正序排列的消息列表
     */
    default List<Message> getLatestMessages(String sessionId, int limit, int offset) {
        // Default fallback: load all and slice
        List<Message> all = getAllMessages(sessionId);
        int total = all.size();
        // offset is from the end: skip the last (offset) messages, take (limit) before them
        int endIndex = Math.max(0, total - offset);
        int startIndex = Math.max(0, endIndex - limit);
        return all.subList(startIndex, endIndex);
    }

    /**
     * 统计会话消息总数
     */
    default long countMessages(String sessionId) {
        return getAllMessages(sessionId).size();
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
