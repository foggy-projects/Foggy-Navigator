package com.foggy.navigator.common.form;

import lombok.Data;

/**
 * 用户登录表单
 */
@Data
public class UserLoginForm {

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;
}
