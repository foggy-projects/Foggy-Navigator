package com.foggy.navigator.common.enums;

/**
 * Git 提供者类型
 */
public enum GitProviderType {
    GITHUB("GitHub"),
    GITLAB("GitLab"),
    GITEE("Gitee");

    private final String description;

    GitProviderType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
