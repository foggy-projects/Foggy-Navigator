package com.foggy.navigator.common.enums;

/**
 * Skill 作用域
 */
public enum SkillScope {
    SYSTEM("系统内置"),
    GLOBAL("全局共享"),
    AGENT("Agent专属"),
    TENANT("租户自定义");

    private final String description;

    SkillScope(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
