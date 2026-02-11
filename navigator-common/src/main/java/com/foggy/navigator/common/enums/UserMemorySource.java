package com.foggy.navigator.common.enums;

/**
 * 用户记忆来源
 */
public enum UserMemorySource {
    AUTO("自动"),
    MANUAL("手动");

    private final String description;

    UserMemorySource(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
