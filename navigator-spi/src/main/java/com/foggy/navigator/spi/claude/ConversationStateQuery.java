package com.foggy.navigator.spi.claude;

import java.util.List;
import java.util.Map;

/**
 * 会话交互状态查询 SPI
 * 由 claude-worker-agent 模块实现，供 task-assistant 等模块使用。
 */
public interface ConversationStateQuery {

    /**
     * 查询指定交互状态的所有 sessionId（跨用户）
     *
     * @param states 交互状态列表，如 ["ARCHIVED", "ON_HOLD"]
     * @return 匹配的 sessionId 列表
     */
    List<String> findSessionIdsByStates(List<String> states);

    /**
     * 按用户分组查询指定交互状态的 sessionId
     *
     * @param states 交互状态列表，如 ["ARCHIVED", "ON_HOLD"]
     * @return Map: userId → sessionId 列表
     */
    Map<String, List<String>> findSessionIdsByStatesGroupByUser(List<String> states);
}
