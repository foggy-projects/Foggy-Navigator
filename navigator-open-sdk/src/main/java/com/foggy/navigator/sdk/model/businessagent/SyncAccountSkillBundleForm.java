package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SyncAccountSkillBundleForm {
    private String skillId;
    private String name;
    private String description;
    private String status;
    private String contextVisibility;
    private String markdownBody;
    private List<SkillResourceForm> resources;
    private List<SkillBundleFunctionForm> functions;
    private Boolean materialize;

    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getContextVisibility() { return contextVisibility; }
    public void setContextVisibility(String contextVisibility) { this.contextVisibility = contextVisibility; }
    public String getMarkdownBody() { return markdownBody; }
    public void setMarkdownBody(String markdownBody) { this.markdownBody = markdownBody; }
    public List<SkillResourceForm> getResources() { return resources; }
    public void setResources(List<SkillResourceForm> resources) { this.resources = resources; }
    public List<SkillBundleFunctionForm> getFunctions() { return functions; }
    public void setFunctions(List<SkillBundleFunctionForm> functions) { this.functions = functions; }
    public Boolean getMaterialize() { return materialize; }
    public void setMaterialize(Boolean materialize) { this.materialize = materialize; }
}
