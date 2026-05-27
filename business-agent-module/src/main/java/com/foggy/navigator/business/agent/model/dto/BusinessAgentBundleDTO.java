package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BusinessAgentBundleDTO {
    private String tenantId;
    private String clientAppId;
    private String agentId;
    private String skillId;
    private ResourceOwnerType ownerType;
    private String ownerId;
    private String name;
    private String description;
    private String agentType;
    private String workerId;
    private String defaultDirectoryId;
    private String agentProfile;
    private String defaultModelConfigId;
    private String defaultModel;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private SkillBundleDTO skillBundle;

    public static BusinessAgentBundleDTO fromEntity(
            CodingAgentEntity entity,
            String clientAppId,
            String skillId,
            SkillBundleDTO skillBundle) {
        BusinessAgentBundleDTO dto = new BusinessAgentBundleDTO();
        dto.setTenantId(entity.getTenantId());
        dto.setClientAppId(clientAppId);
        dto.setAgentId(entity.getAgentId());
        dto.setSkillId(skillId);
        dto.setOwnerType(entity.getOwnerType());
        dto.setOwnerId(entity.getOwnerId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setAgentType(entity.getAgentType());
        dto.setWorkerId(entity.getWorkerId());
        dto.setDefaultDirectoryId(entity.getDefaultDirectoryId());
        dto.setAgentProfile(entity.getAgentProfile());
        dto.setDefaultModelConfigId(entity.getDefaultModelConfigId());
        dto.setDefaultModel(entity.getDefaultModel());
        dto.setEnabled(entity.getEnabled());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setSkillBundle(skillBundle);
        return dto;
    }
}
