package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.SkillFunctionAllowlistEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SkillFunctionAllowlistDTO {
    private String allowlistId;
    private String tenantId;
    private String skillId;
    private String functionId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SkillFunctionAllowlistDTO fromEntity(SkillFunctionAllowlistEntity entity) {
        if (entity == null) {
            return null;
        }
        SkillFunctionAllowlistDTO dto = new SkillFunctionAllowlistDTO();
        dto.setAllowlistId(entity.getAllowlistId());
        dto.setTenantId(entity.getTenantId());
        dto.setSkillId(entity.getSkillId());
        dto.setFunctionId(entity.getFunctionId());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
