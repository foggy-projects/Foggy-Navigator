package com.foggy.navigator.agent.framework.protocol.route;

/**
 * 路由动作类型
 */
public enum RouteAction {
    DELEGATE,      // 分派给其他Agent
    RETURN,        // 返回父会话
    SWITCH,        // 切换会话（不创建新会话）
    SPAWN,         // 创建并行会话
    CLOSE          // 关闭当前会话
}
