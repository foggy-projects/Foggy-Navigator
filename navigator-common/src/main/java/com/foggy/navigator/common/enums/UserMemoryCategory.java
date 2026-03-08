package com.foggy.navigator.common.enums;

/**
 * 用户记忆类别
 */
public enum UserMemoryCategory {
    PREFERENCE("偏好"),
    FACT("事实"),
    NOTE("备注");

    private final String description;

    UserMemoryCategory(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
