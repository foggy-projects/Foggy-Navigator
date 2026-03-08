package com.foggy.navigator.common.context;

import com.foggy.navigator.common.dto.CurrentUser;

/**
 * 用户上下文 - 线程安全的当前用户存储
 */
public class UserContext {

    private static final ThreadLocal<CurrentUser> CURRENT_USER = new ThreadLocal<>();

    /**
     * 设置当前用户
     */
    public static void setCurrentUser(CurrentUser user) {
        CURRENT_USER.set(user);
    }

    /**
     * 获取当前用户
     */
    public static CurrentUser getCurrentUser() {
        return CURRENT_USER.get();
    }

    /**
     * 清除当前用户（请求结束时调用）
     */
    public static void clear() {
        CURRENT_USER.remove();
    }

    /**
     * 获取当前用户ID
     */
    public static String getCurrentUserId() {
        CurrentUser user = getCurrentUser();
        return user != null ? user.getUserId() : null;
    }

    /**
     * 获取当前租户ID
     */
    public static String getCurrentTenantId() {
        CurrentUser user = getCurrentUser();
        return user != null ? user.getTenantId() : null;
    }

    /**
     * 检查是否已登录
     */
    public static boolean isAuthenticated() {
        return getCurrentUser() != null;
    }

    /**
     * 检查是否为超级管理员
     */
    public static boolean isSuperAdmin() {
        CurrentUser user = getCurrentUser();
        return user != null && user.isSuperAdmin();
    }
}
