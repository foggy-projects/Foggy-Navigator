package com.foggy.navigator.claude.worker.model.form;

import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ClientAppDirectoryInitForm {
    private String workerId;
    private String path;
    private String projectName;
    private Map<String, String> files;
    private WorkspaceScope workspaceScope;
    private String upstreamUserId;
    private WorkingDirectoryResolverType resolverType;
    private String rootRef;
    private String resolverKey;
    private Boolean readOnly;
    private List<String> allowedPathPrefixes;
    private String quotaJson;
    private String retentionPolicyJson;
    private String concurrencyPolicyJson;
    private Boolean enabled;
}
