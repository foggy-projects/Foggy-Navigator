package com.foggy.navigator.common.enums;

/**
 * Skill 状态
 */
public enum SkillStatus {
    ENABLED("启用"),
    DISABLED("禁用"),
    DRAFT("草稿");

    private final String description;

    SkillStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
