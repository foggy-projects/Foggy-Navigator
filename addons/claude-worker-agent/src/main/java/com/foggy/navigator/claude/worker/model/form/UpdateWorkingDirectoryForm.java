package com.foggy.navigator.claude.worker.model.form;

import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import lombok.Data;

import java.util.List;

/**
 * 更新工作目录表单
 */
@Data
public class UpdateWorkingDirectoryForm {
    private String projectName;
    private String path;
    private String agentTeamsConfig;
    /** 仅 PROJECT 类型可编辑 */
    private String projectTaskPrompt;
    /** 可修改归属 PROJECT */
    private String parentProjectId;
    /** 默认认证模式, "" 清除, null 不改 */
    private String defaultAuthMode;
    /** 默认认证 Token（明文提交, "" 清除, null 不改） */
    private String defaultAuthToken;
    /** 默认 Base URL */
    private String defaultBaseUrl;
    /** 平台 LLM 配置 ID（选中后清空手动 auth 配置），"" 清除, null 不改 */
    private String defaultModelConfigId;
    /** 上游资源 owner，null 不改 */
    private ResourceOwnerType ownerType;
    /** 上游资源 owner id，"" 清除, null 不改 */
    private String ownerId;
    /** 上游 ClientApp 边界，"" 清除, null 不改 */
    private String clientAppId;
    /** 上游用户边界，"" 清除, null 不改 */
    private String upstreamUserId;
    /** 工作目录 scope，null 不改 */
    private WorkspaceScope workspaceScope;
    /** root resolver 类型，null 不改 */
    private WorkingDirectoryResolverType resolverType;
    /** "" 清除, null 不改 */
    private String rootRef;
    /** "" 清除, null 不改 */
    private String resolverKey;
    /** null 不改 */
    private Boolean readOnly;
    /** null 不改，[] 清空 */
    private List<String> allowedPathPrefixes;
    /** "" 清除, null 不改 */
    private String quotaJson;
    /** "" 清除, null 不改 */
    private String retentionPolicyJson;
    /** "" 清除, null 不改 */
    private String concurrencyPolicyJson;
    /** null 不改 */
    private Boolean enabled;
    /** 里程碑列表，null 不改，[] 清空 */
    private List<DirectoryMilestoneForm> milestones;
}
