package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.SkillEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SkillDTO {
    private String tenantId;
    private String skillId;
    private String name;
    private String description;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SkillDTO fromEntity(SkillEntity entity) {
        if (entity == null) {
            return null;
        }
        SkillDTO dto = new SkillDTO();
        dto.setTenantId(entity.getTenantId());
        dto.setSkillId(entity.getSkillId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
