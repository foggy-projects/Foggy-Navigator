package com.foggy.navigator.common.enums;

/**
 * 用户角色枚举
 */
public enum UserRole {
    /**
     * 超级管理员：全部权限
     */
    SUPER_ADMIN,

    /**
     * 租户管理员：租户内全部权限
     */
    TENANT_ADMIN,

    /**
     * 开发者：可以创建和管理资源
     */
    DEVELOPER,

    /**
     * 查看者：只读权限
     */
    VIEWER
}
