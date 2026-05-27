package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import lombok.Data;

@Data
public class WorkingDirectoryRepairResultDTO {
    private String directoryId;
    private String tenantId;
    private ResourceOwnerType ownerType;
    private String ownerId;
    private WorkspaceScope workspaceScope;
    private String rootAgentId;
    private ResourceOwnerType rootAgentOwnerType;
    private String rootAgentOwnerId;
    private Boolean rootAgentOwnerRepaired;
    private Boolean defaultDirectory;
}
