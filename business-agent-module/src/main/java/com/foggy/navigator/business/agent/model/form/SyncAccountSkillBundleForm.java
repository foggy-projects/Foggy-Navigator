package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

import java.util.List;

@Data
public class SyncAccountSkillBundleForm {
    private String skillId;
    private String name;
    private String description;
    private String status;
    private String markdownBody;
    private List<SkillResourceForm> resources;
    private List<SkillBundleFunctionForm> functions;
    private Boolean materialize;
}
