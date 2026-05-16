package com.foggy.navigator.agent.framework.session;

/**
 * 会话状态
 */
public enum SessionStatus {
    ACTIVE,     // 活跃
    PAUSED,     // 暂停
    COMPLETED,  // 已完成
    DELEGATED,  // 已分派
    DELETED     // 历史兼容：旧数据可能使用该状态表示已删除
}
