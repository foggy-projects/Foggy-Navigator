package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class RepairUpstreamSystemWorkingDirectoryForm {
    private String tenantId;
    private String upstreamSystemId;
    private String rootAgentId;
    private Boolean setDefaultDirectory = true;
    private Boolean repairRootAgentOwner = false;
}
