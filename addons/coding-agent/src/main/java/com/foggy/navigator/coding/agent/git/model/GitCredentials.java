package com.foggy.navigator.coding.agent.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Git 凭证
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitCredentials {

    /**
     * 凭证类型: token, ssh, oauth
     */
    private String type;

    /**
     * Token (用于 token 类型)
     */
    private String token;

    /**
     * 用户名 (用于 basic auth)
     */
    private String username;

    /**
     * 密码 (用于 basic auth)
     */
    private String password;
}
