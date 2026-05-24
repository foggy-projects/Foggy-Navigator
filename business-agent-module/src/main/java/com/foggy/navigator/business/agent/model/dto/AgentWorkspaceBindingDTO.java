package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentWorkspaceBindingDTO {

    private Long id;
    private String tenantId;
    private String agentId;
    private String directoryId;
    private String clientAppId;
    private String projectName;
    private String rootRef;
    private String path;
    private WorkspaceScope workspaceScope;
    private ResourceOwnerType directoryOwnerType;
    private String directoryOwnerId;
    private Boolean defaultDirectory;
    private Boolean enabled;
    private LocalDateTime createdAt;

    public static AgentWorkspaceBindingDTO fromEntity(AgentDirectoryBindingEntity entity) {
        AgentWorkspaceBindingDTO dto = new AgentWorkspaceBindingDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setAgentId(entity.getAgentId());
        dto.setDirectoryId(entity.getDirectoryId());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
