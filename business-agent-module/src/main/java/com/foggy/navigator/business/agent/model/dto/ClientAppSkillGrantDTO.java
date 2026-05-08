package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.ClientAppSkillGrantEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ClientAppSkillGrantDTO {
    private String grantId;
    private String tenantId;
    private String clientAppId;
    private String skillId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ClientAppSkillGrantDTO fromEntity(ClientAppSkillGrantEntity entity) {
        if (entity == null) {
            return null;
        }
        ClientAppSkillGrantDTO dto = new ClientAppSkillGrantDTO();
        dto.setGrantId(entity.getGrantId());
        dto.setTenantId(entity.getTenantId());
        dto.setClientAppId(entity.getClientAppId());
        dto.setSkillId(entity.getSkillId());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
