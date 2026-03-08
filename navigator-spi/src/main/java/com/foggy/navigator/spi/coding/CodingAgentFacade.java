package com.foggy.navigator.spi.coding;

import java.util.List;
import java.util.Map;

/**
 * Coding Agent 门面接口
 * 供 tutor-agent 的 BuiltInTool 通过 SPI 调用 coding-agent 功能，
 * 避免 HTTP 自调用和 ThreadLocal 跨线程问题
 */
public interface CodingAgentFacade {

    /**
     * 列出用户配置的所有 Git 凭证
     */
    List<Map<String, Object>> listGitCredentials(String userId);

    /**
     * 列出指定 Git 凭证下的所有项目
     */
    List<Map<String, Object>> listGitProjects(String userId, String credentialId);

    /**
     * 列出指定项目的所有分支
     */
    List<Map<String, Object>> listGitBranches(String userId, String credentialId, String projectId);

    /**
     * 创建编码会话
     */
    Map<String, Object> createConversation(String userId, Map<String, Object> params);

    /**
     * 向编码会话发送消息
     */
    Map<String, Object> sendMessage(String userId, String conversationId, Map<String, Object> params);

    /**
     * 查询编码会话状态
     */
    Map<String, Object> getConversationStatus(String userId, String conversationId);
}
