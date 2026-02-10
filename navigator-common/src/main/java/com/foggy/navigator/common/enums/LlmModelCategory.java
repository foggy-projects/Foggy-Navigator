package com.foggy.navigator.common.enums;

/**
 * LLM 模型类别
 */
public enum LlmModelCategory {
    GENERAL("通用"),
    CODING("编程"),
    REASONING("推理"),
    VISION("视觉");

    private final String description;

    LlmModelCategory(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
