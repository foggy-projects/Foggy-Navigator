package com.foggy.navigator.common.form;

import lombok.Data;

/**
 * 用户注册表单
 */
@Data
public class UserRegisterForm {

    /**
     * 租户ID（可选，由超级管理员创建用户时指定）
     */
    private String tenantId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 显示名称
     */
    private String displayName;

    /**
     * 角色列表（逗号分隔）
     */
    private String roles;
}
