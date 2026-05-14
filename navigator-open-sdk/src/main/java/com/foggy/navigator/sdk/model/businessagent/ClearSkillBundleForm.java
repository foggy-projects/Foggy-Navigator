package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ClearSkillBundleForm {
    private String clientAppId;
    private String accountId;
    private String skillId;
    private Boolean dryRun;

    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public Boolean getDryRun() { return dryRun; }
    public void setDryRun(Boolean dryRun) { this.dryRun = dryRun; }
}
