package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillBundleDTO {
    private String tenantId;
    private String clientAppId;
    private String scope;
    private String accountId;
    private String skillId;
    private String name;
    private String description;
    private String status;
    private String contextVisibility;
    private SkillMaterializeResultDTO materializeResult;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
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
    public SkillMaterializeResultDTO getMaterializeResult() { return materializeResult; }
    public void setMaterializeResult(SkillMaterializeResultDTO materializeResult) { this.materializeResult = materializeResult; }
}
