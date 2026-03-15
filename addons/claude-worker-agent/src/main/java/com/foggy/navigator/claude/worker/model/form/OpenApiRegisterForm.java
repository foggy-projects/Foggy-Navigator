package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 第三方系统自助注册表单
 */
@Data
public class OpenApiRegisterForm {

    /** 第三方系统名称（用于生成 tenantId） */
    private String systemName;

    /** 管理员用户名 */
    private String adminUsername;

    /** 管理员密码 */
    private String adminPassword;
}
