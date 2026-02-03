package com.foggy.navigator.coding.agent.api.model;

import com.foggy.navigator.coding.agent.git.model.GitCredentials;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateConversationRequest {

    private String userId;

    private String projectId;

    // === 新的 Git 项目选择流程 ===
    // Git 凭证 ID（从 GitCredentialEntity 获取）
    private String gitCredentialId;

    // Git 项目 ID（GitLab 数字ID 或 GitHub owner/repo）
    private String gitProjectId;

    // 基准分支（如 main, develop）
    private String baseBranch;

    // 任务描述（用于生成工作分支名）
    private String taskDescription;

    // === 旧字段（兼容老接口）===
    @Deprecated
    private String gitRepoUrl;

    @Deprecated
    private String branchName;

    @Deprecated
    private GitCredentials gitCredentials;

    private String initialMessage;
}
