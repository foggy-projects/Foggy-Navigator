package com.foggy.navigator.agent.framework.session;

/**
 * 消息角色
 */
public enum MessageRole {
    USER,       // 用户消息
    ASSISTANT,  // AI助手消息
    SYSTEM,     // 系统消息
    TOOL        // 工具调用结果
}
