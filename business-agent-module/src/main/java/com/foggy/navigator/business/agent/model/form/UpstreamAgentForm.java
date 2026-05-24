package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class UpstreamAgentForm {
    private String agentId;
    private String name;
    private String description;
    private String agentType;
    private String workerId;
    private String defaultDirectoryId;
    private String defaultModelConfigId;
    private String defaultModel;
    private String skillsJson;
    private String agentProfileJson;
    private Boolean enabled;
}
