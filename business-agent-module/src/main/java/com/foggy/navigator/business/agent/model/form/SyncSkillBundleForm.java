package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

import java.util.List;

@Data
public class SyncSkillBundleForm {
    private String clientAppId;
    private String scope;
    private String accountId;
    private String skillId;
    private String name;
    private String description;
    private String status;
    private String markdownBody;
    private List<SkillResourceForm> resources;
    private List<SkillBundleFunctionForm> functions;
    private Boolean materialize;
}
