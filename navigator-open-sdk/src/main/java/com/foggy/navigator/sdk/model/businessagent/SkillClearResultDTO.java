package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillClearResultDTO {
    private String scope;
    private String clientAppId;
    private String accountId;
    private String skillId;
    private boolean dryRun;
    private boolean executed;
    private List<String> skillIds;
    private int matchedSkillCount;
    private int skillBundleCount;
    private int legacySkillCount;
    private int clientAppSkillGrantCount;
    private int skillFunctionAllowlistCount;
    private int materializedBundleCount;
    private int cacheCount;
    private String workerClearStatus;
    private Integer workerStatusCode;
    private String workerResponse;

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public boolean isExecuted() { return executed; }
    public void setExecuted(boolean executed) { this.executed = executed; }
    public List<String> getSkillIds() { return skillIds; }
    public void setSkillIds(List<String> skillIds) { this.skillIds = skillIds; }
    public int getMatchedSkillCount() { return matchedSkillCount; }
    public void setMatchedSkillCount(int matchedSkillCount) { this.matchedSkillCount = matchedSkillCount; }
    public int getSkillBundleCount() { return skillBundleCount; }
    public void setSkillBundleCount(int skillBundleCount) { this.skillBundleCount = skillBundleCount; }
    public int getLegacySkillCount() { return legacySkillCount; }
    public void setLegacySkillCount(int legacySkillCount) { this.legacySkillCount = legacySkillCount; }
    public int getClientAppSkillGrantCount() { return clientAppSkillGrantCount; }
    public void setClientAppSkillGrantCount(int clientAppSkillGrantCount) { this.clientAppSkillGrantCount = clientAppSkillGrantCount; }
    public int getSkillFunctionAllowlistCount() { return skillFunctionAllowlistCount; }
    public void setSkillFunctionAllowlistCount(int skillFunctionAllowlistCount) { this.skillFunctionAllowlistCount = skillFunctionAllowlistCount; }
    public int getMaterializedBundleCount() { return materializedBundleCount; }
    public void setMaterializedBundleCount(int materializedBundleCount) { this.materializedBundleCount = materializedBundleCount; }
    public int getCacheCount() { return cacheCount; }
    public void setCacheCount(int cacheCount) { this.cacheCount = cacheCount; }
    public String getWorkerClearStatus() { return workerClearStatus; }
    public void setWorkerClearStatus(String workerClearStatus) { this.workerClearStatus = workerClearStatus; }
    public Integer getWorkerStatusCode() { return workerStatusCode; }
    public void setWorkerStatusCode(Integer workerStatusCode) { this.workerStatusCode = workerStatusCode; }
    public String getWorkerResponse() { return workerResponse; }
    public void setWorkerResponse(String workerResponse) { this.workerResponse = workerResponse; }
}
