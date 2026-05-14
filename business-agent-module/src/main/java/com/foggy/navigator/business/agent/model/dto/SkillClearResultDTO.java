package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.util.List;

@Data
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
}
