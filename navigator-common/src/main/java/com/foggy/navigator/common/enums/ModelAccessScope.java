package com.foggy.navigator.common.enums;

/**
 * AI 模型访问范围
 */
public enum ModelAccessScope {
    GLOBAL,      // 所有 Worker 可用
    RESTRICTED   // 仅指定 Worker 可用
}
