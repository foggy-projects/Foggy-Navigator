package com.foggy.navigator.agent.framework.protocol.state;

/**
 * 状态同步模式
 */
public enum SyncMode {
    FULL,       // 全量替换
    PATCH,      // 增量更新 (JSON Patch RFC 6902)
    DELETE      // 删除状态
}
