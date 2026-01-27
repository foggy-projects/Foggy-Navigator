package com.foggy.navigator.common.form;

import com.foggy.navigator.common.enums.GitAuthType;
import lombok.Data;

/**
 * Git仓库配置
 * 支持私有仓库（GitLab、GitHub、Gitee等）
 */
@Data
public class GitRepoConfig {
    /**
     * Git仓库URL
     * 示例：
     * - 公开：https://github.com/org/repo.git
     * - 私有GitLab：https://gitlab.company.com/team/project.git
     * - 私有GitHub：https://github.com/private-org/private-repo.git
     */
    private String repoUrl;

    /**
     * Git分支（默认：main）
     */
    private String branch;

    /**
     * Git认证方式
     */
    private GitAuthType authType;

    /**
     * 访问令牌（AccessToken/PAT）
     * - GitHub: Personal Access Token
     * - GitLab: Project Access Token / Personal Access Token
     * - Gitee: 私人令牌
     * 后端加密存储
     */
    private String accessToken;

    /**
     * 用户名（BASIC认证时使用）
     */
    private String username;

    /**
     * 密码（BASIC认证时使用，后端加密存储）
     */
    private String password;

    /**
     * SSH私钥（SSH认证时使用，后端加密存储）
     */
    private String sshPrivateKey;

    /**
     * SSH公钥密码（可选）
     */
    private String sshPassphrase;
}
