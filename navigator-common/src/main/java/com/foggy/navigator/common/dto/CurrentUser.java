package com.foggy.navigator.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 当前登录用户上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUser {

    private String userId;
    private String username;
    private String tenantId;
    private String roles;

    /**
     * 是否为超级管理员（ROOT）
     */
    public boolean isSuperAdmin() {
        return roles != null && roles.contains("SUPER_ADMIN");
    }

    /**
     * 是否为租户管理员
     */
    public boolean isTenantAdmin() {
        return roles != null && roles.contains("TENANT_ADMIN");
    }

    /**
     * 获取角色列表
     */
    public List<String> getRoleList() {
        if (roles == null || roles.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(roles.split(","));
    }

    /**
     * 检查是否有指定角色
     */
    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    /**
     * 检查是否可以访问指定租户的数据
     * - 超级管理员可以访问所有租户
     * - 普通用户只能访问自己的租户
     */
    public boolean canAccessTenant(String targetTenantId) {
        if (isSuperAdmin()) {
            return true;
        }
        return tenantId != null && tenantId.equals(targetTenantId);
    }
}
