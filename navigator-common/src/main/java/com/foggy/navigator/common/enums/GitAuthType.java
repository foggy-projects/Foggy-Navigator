package com.foggy.navigator.common.enums;

/**
 * Git认证方式
 */
public enum GitAuthType {
    NONE,           // 公开仓库，无需认证
    ACCESS_TOKEN,   // 访问令牌（推荐，适用于GitHub/GitLab/Gitee）
    BASIC,          // 用户名密码（不推荐，部分平台已废弃）
    SSH             // SSH密钥（适用于企业内部GitLab）
}
