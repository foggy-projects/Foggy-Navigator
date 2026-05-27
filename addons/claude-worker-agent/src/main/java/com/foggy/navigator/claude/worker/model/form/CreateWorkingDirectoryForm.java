package com.foggy.navigator.claude.worker.model.form;

import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import lombok.Data;

import java.util.List;

/**
 * 创建工作目录表单
 */
@Data
public class CreateWorkingDirectoryForm {
    private String workerId;
    private String projectName;
    private String path;
    /** optional, default "STANDARD". Values: STANDARD | PROJECT */
    private String directoryType;
    /** optional, 关联到 PROJECT 类型目录 */
    private String parentProjectId;
    /** optional, 默认认证模式 */
    private String defaultAuthMode;
    /** optional, 默认认证 Token（明文提交） */
    private String defaultAuthToken;
    /** optional, 默认 Base URL */
    private String defaultBaseUrl;
    /** optional, resource owner type for upstream-facing directory resources */
    private ResourceOwnerType ownerType;
    /** optional, resource owner id. USER_PRIVATE usually uses upstreamUserId. */
    private String ownerId;
    /** optional, upstream ClientApp boundary. */
    private String clientAppId;
    /** optional, upstream user boundary under clientAppId. */
    private String upstreamUserId;
    /** optional, default USER_PRIVATE. */
    private WorkspaceScope workspaceScope;
    /** optional, default DELEGATED. */
    private WorkingDirectoryResolverType resolverType;
    /** optional, resolved root reference. Defaults to path when omitted. */
    private String rootRef;
    /** optional, external resolver key. */
    private String resolverKey;
    /** optional, default false. */
    private Boolean readOnly;
    /** optional, allowed path prefixes. Defaults to resolved root when omitted at runtime. */
    private List<String> allowedPathPrefixes;
    private String quotaJson;
    private String retentionPolicyJson;
    private String concurrencyPolicyJson;
    /** optional, default true. */
    private Boolean enabled;
}
