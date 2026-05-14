package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class ClearSkillBundleForm {
    private String clientAppId;
    private String accountId;
    private String skillId;
    private Boolean dryRun;
}
