package com.foggy.navigator.common.form;

import com.foggy.navigator.common.enums.GitProviderType;
import lombok.Data;

/**
 * Git 提供者配置表单
 */
@Data
public class GitProviderConfigForm {

    /**
     * Git 提供者类型
     */
    private GitProviderType providerType;

    /**
     * 服务地址（GitLab 私有部署时需填写）
     */
    private String baseUrl;

    /**
     * 访问令牌
     */
    private String accessToken;

    /**
     * 用户名（可选）
     */
    private String username;
}
