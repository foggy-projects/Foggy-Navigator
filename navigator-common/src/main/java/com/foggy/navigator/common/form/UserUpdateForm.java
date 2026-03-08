package com.foggy.navigator.common.form;

import com.foggy.navigator.common.enums.UserStatus;
import lombok.Data;

/**
 * 用户更新表单
 */
@Data
public class UserUpdateForm {

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

    /**
     * 用户状态
     */
    private UserStatus status;

    /**
     * 新密码（可选）
     */
    private String newPassword;
}
