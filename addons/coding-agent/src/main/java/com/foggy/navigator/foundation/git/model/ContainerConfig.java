package com.foggy.navigator.foundation.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OpenHands 容器配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerConfig {

    /**
     * LLM API Key
     */
    private String apiKey;

    /**
     * LLM 模型名称
     */
    private String modelName;

    /**
     * LLM API Base URL
     */
    private String apiBaseUrl;

    /**
     * Git 仓库 URL
     */
    private String gitRepoUrl;

    /**
     * Git 分支
     */
    private String gitBranch;

    /**
     * Git 凭证
     */
    private GitCredentials gitCredentials;
}
