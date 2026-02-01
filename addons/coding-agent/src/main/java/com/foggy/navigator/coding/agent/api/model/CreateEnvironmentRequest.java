package com.foggy.navigator.coding.agent.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建环境请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateEnvironmentRequest {

    /**
     * 用户ID
     * 必填
     */
    private String userId;

    /**
     * 项目ID
     * 必填
     */
    private String projectId;

    /**
     * Git 仓库地址
     * 必填
     */
    private String gitRepoUrl;

    /**
     * 分支名称
     * 必填
     */
    private String branchName;

    /**
     * Git 凭证
     * 可选
     */
    private GitCredentials gitCredentials;

    /**
     * 验证服务地址
     * 可选，为空则使用默认配置
     */
    private String validationServiceUrl;

    /**
     * OpenAI API Key
     * 可选，为空则使用默认配置
     */
    private String apiKey;

    /**
     * LLM 模型名称
     * 可选，默认: gpt-4
     */
    private String modelName;

    /**
     * Git 凭证
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GitCredentials {
        private String type;  // token, ssh, oauth
        private String token;
        private String username;
        private String password;
    }
}
