package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

import java.util.List;

@Data
public class SyncBusinessAgentBundleForm {
    private String clientAppId;
    /** Agent code used by upstream as NAVI_AGENT_CODE. */
    private String agentId;
    /** Alias accepted for upstream-facing manifests. If set and agentId is empty, it becomes agentId. */
    private String agentCode;
    private String skillId;
    private String name;
    private String description;
    private String status;
    private String workerId;
    private String defaultDirectoryId;
    private String defaultModelConfigId;
    private String defaultModel;
    private String markdownBody;
    private String contextVisibility;
    private List<SkillResourceForm> resources;
    private List<SkillBundleFunctionForm> functions;
    private Boolean materialize;
}
