package com.foggy.navigator.common.enums;

/**
 * 配置项状态
 */
public enum ConfigItemStatus {
    NOT_STARTED("未开始"),
    IN_PROGRESS("配置中"),
    CONFIGURED("已配置"),
    VALIDATED("已验证"),
    FAILED("配置失败");

    private final String description;

    ConfigItemStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
