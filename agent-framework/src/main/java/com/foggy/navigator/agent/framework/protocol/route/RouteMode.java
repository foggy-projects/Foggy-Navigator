package com.foggy.navigator.agent.framework.protocol.route;

/**
 * 路由模式（前端如何处理跳转）
 */
public enum RouteMode {
    REDIRECT,           // 页面跳转
    REPLACE,            // 替换当前会话（同窗口）
    NEW_TAB,            // 新标签页
    MODAL,              // 模态框
    SPLIT_VIEW,         // 分屏视图
    BACKGROUND          // 后台执行（不切换UI）
}
