package com.foggy.navigator.common.service;

import java.util.List;
import java.util.Map;

/**
 * Coding 会话信息查询服务接口
 * 由 coding-agent 模块实现，供 session-module 查询 coding 会话扩展信息
 */
public interface CodingConversationLookup {

    /**
     * 查询指定 Session 对应的 Conversation 扩展信息
     *
     * @param sessionId session ID
     * @return 扩展信息 Map，包含 conversationId, sandboxStatus, gitRepoUrl 等
     *         如果没有对应的 Conversation，返回 null
     */
    Map<String, Object> getConversationInfo(String sessionId);

    /**
     * 批量查询 Session 对应的 Conversation 扩展信息
     *
     * @param sessionIds session ID 列表
     * @return sessionId -> 扩展信息 Map 的映射
     */
    Map<String, Map<String, Object>> getConversationInfoBatch(List<String> sessionIds);

    /**
     * 删除指定 Session 关联的 Conversation（含 sandbox 停止）
     *
     * @param sessionId session ID
     * @return true 如果删除成功或不存在关联 Conversation
     */
    boolean deleteConversationBySessionId(String sessionId);
}
